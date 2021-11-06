/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.codeInsight.hint.HintUtil
import com.intellij.codeInsight.template.impl.editorActions.TypedActionHandlerBase
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ex.ClipboardUtil
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.actionSystem.TypedAction
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.HintHint
import com.intellij.ui.JBColor
import com.intellij.ui.LightweightHint
import com.intellij.util.text.StringSearcher
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class IncrementalSearchHandler(private val forward: Boolean) : EditorActionHandler() {

    private class PerEditorSearchData {
        var hint: MyHint? = null
        var lastSearch = ""
        var currentForward = false
        var lastInput: InputEvent? = null
    }

    private class PerCaretSearchData {
        var history: List<CaretState> = listOf()
        var startOffset: Int = 0
    }

    private data class CaretState(val offset: Int, val matchOffset: Int, val matchEndOffset: Int, val startOffset: Int)

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val project = editor.project ?: return
        if (!ourActionsRegistered) {
            val actionManager = EditorActionManager.getInstance()

            val typedAction = TypedAction.getInstance()
            typedAction.setupRawHandler(MyTypedHandler(typedAction.rawHandler))

            mapOf(
                IdeActions.ACTION_EDITOR_BACKSPACE to ::BackSpaceHandler,
                IdeActions.ACTION_EDITOR_MOVE_CARET_UP to ::UpHandler,
                IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN to ::DownHandler,
                IdeActions.ACTION_EDITOR_ENTER to ::EnterHandler,
                IdeActions.ACTION_EDITOR_COPY to ::HandlerToHide,
                IdeActions.ACTION_EDITOR_MOVE_LINE_START to ::HandlerToHide,
                IdeActions.ACTION_EDITOR_PASTE to ::MyPasteHandler
            ).forEach { (name, constructor) -> actionManager.setActionHandler(name, constructor(actionManager.getActionHandler(name))) }

            ourActionsRegistered = true
        }

        val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY) ?: PerEditorSearchData()
        val lastForward = data.currentForward
        data.currentForward = forward
        val currentHint = data.hint
        if (currentHint != null) return updatePositionAndHint(editor, currentHint, forward, null, lastForward)

        val hint = MyHint(forward, project, editor)

        val component = editor.component
        val x = SwingUtilities.convertPoint(component, 0, 0, component).x
        val y = -hint.component.preferredSize.height
        val p = SwingUtilities.convertPoint(component, x, y, component.rootPane.layeredPane)

        HintManagerImpl.getInstanceImpl().showEditorHint(
            hint,
            editor,
            p,
            HintManagerImpl.HIDE_BY_ESCAPE or HintManagerImpl.HIDE_BY_TEXT_CHANGE,
            0,
            false,
            HintHint(editor, p).setAwtTooltip(false)
        )

        editor.caretModel.runForEachCaret {
            val caretData = PerCaretSearchData()
            caretData.startOffset = it.offset
            it.putUserData(SEARCH_DATA_IN_CARET_KEY, caretData)
        }

        data.hint = hint
        editor.putUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY, data)
    }

    private class MySelectionListener : SelectionListener {
        override fun selectionChanged(e: SelectionEvent) {
            e.editor?.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint?.hide()
        }
    }

    private class MyCaretListener : CaretListener {
        override fun caretPositionChanged(e: CaretEvent) {
            e.editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint?.hide()
        }

        override fun caretAdded(e: CaretEvent) {
            e.editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint?.hide()
        }

        override fun caretRemoved(e: CaretEvent) {
            val oldOffset = e.editor.logicalPositionToOffset(e.oldPosition)
            val highlighter = e.editor.markupModel.allHighlighters.firstOrNull { highlighter ->
                highlighter.startOffset <= oldOffset && oldOffset <= highlighter.endOffset
            }
            highlighter?.dispose()
        }
    }

    private class MyDocumentListener(val editor: Editor) : DocumentListener {
        override fun documentChanged(e: DocumentEvent) {
            editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint?.hide()
        }
    }

    private class MyHint(forward: Boolean, val project: Project, private val editor: Editor) : LightweightHint(MyPanel()) {
        private data class HintState(val text: String, val color: Color, val title: String)

        private val caretListener = MyCaretListener()
        private val selectionListener = MySelectionListener()
        private val documentListener = MyDocumentListener(editor)

        private var ignoreCaretMove = false
        private var history: List<HintState> = listOf()
        private val labelTitle = newLabel(getLabelText(forward, isWrapped = false, notFound = false))
        val labelTarget = newLabel("")

        init {
            labelTitle.font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            component.add(labelTitle, BorderLayout.WEST)
            component.add(labelTarget, BorderLayout.CENTER)
            component.border = BorderFactory.createLineBorder(JBColor.black)
            editor.caretModel.addCaretListener(caretListener)
            editor.selectionModel.addSelectionListener(selectionListener)
            editor.document.addDocumentListener(documentListener)
        }

        private fun newLabel(text: String): JLabel {
            val label = JLabel(text)
            label.background = HintUtil.getInformationColor()
            label.foreground = JBColor.foreground()
            label.isOpaque = true
            return label
        }

        fun doWithoutHandler(f: () -> Unit) {
            ignoreCaretMove = true
            f()
            ignoreCaretMove = false
        }

        fun update(targetText: String, color: Color, titleText: String) {
            labelTitle.text = titleText
            labelTarget.text = targetText
            labelTarget.foreground = color
            this.pack()
        }

        fun pushHistory() {
            this.history += HintState(this.labelTarget.text, this.labelTarget.foreground, this.labelTitle.text)
        }

        fun popHistory(editor: Editor) {
            val hintState = this.history.lastOrNull() ?: return
            this.history = this.history.dropLast(1)
            this.update(hintState.text, hintState.color, hintState.title)
            editor.caretModel.runForEachCaret(fun(caret: Caret) {
                val caretData = caret.getUserData(SEARCH_DATA_IN_CARET_KEY) ?: return
                val caretState = caretData.history.lastOrNull() ?: return
                val isPrimary = caret == editor.caretModel.primaryCaret
                caretData.history = caretData.history.dropLast(1)
                val highlighter = editor.markupModel.allHighlighters.firstOrNull { highlighter ->
                    highlighter.startOffset <= caret.offset && caret.offset <= highlighter.endOffset
                }

                doWithoutHandler { caret.moveToOffset(caretState.offset) }
                highlighter?.dispose()
                addRangeHighlighter(editor, caretState.matchOffset, caretState.matchEndOffset)
                if (isPrimary) {
                    editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                }
                IdeDocumentHistory.getInstance(project).includeCurrentCommandAsNavigation()
                caretData.startOffset = caretState.startOffset
            })
        }

        override fun hide() {
            if (!isVisible || ignoreCaretMove) return

            super.hide()
            editor.markupModel.removeAllHighlighters()

            val editorData = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY) ?: return
            val hint = editorData.hint ?: return
            editorData.lastSearch = hint.labelTarget.text
            editorData.hint = null
            editor.document.removeDocumentListener(documentListener)
            editor.caretModel.removeCaretListener(caretListener)
            editor.selectionModel.removeSelectionListener(selectionListener)
        }
    }

    private class MyPanel : JPanel(BorderLayout()) {
        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            return Dimension(maxOf(size.width + 10, 200), size.height)
        }
    }

    class MyTypedHandler(originalHandler: TypedActionHandler?) : TypedActionHandlerBase(originalHandler) {
        override fun execute(editor: Editor, charTyped: Char, dataContext: DataContext) {
            val editorData = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            val hint = editorData?.hint
            if (hint == null) myOriginalHandler?.execute(editor, charTyped, dataContext)
            else updatePositionAndHint(editor, hint, editorData.currentForward, charTyped.toString(), editorData.currentForward)
        }
    }

    abstract class BaseEditorActionHandler(protected val myOriginalHandler: EditorActionHandler) : EditorActionHandler() {
        override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean {
            val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            return data?.hint != null || myOriginalHandler.isEnabled(editor, caret, dataContext)
        }
    }

    class BackSpaceHandler(myOriginalHandler: EditorActionHandler) : BaseEditorActionHandler(myOriginalHandler) {
        public override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val hint = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint
            hint ?: return myOriginalHandler.execute(editor, caret, dataContext)
            hint.popHistory(editor)
        }
    }

    class UpHandler(myOriginalHandler: EditorActionHandler) : BaseEditorActionHandler(myOriginalHandler) {
        public override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val editorData = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            val hint = editorData?.hint ?: return myOriginalHandler.execute(editor, caret, dataContext)

            updatePositionAndHint(editor, hint, false, null, editorData.currentForward)
        }
    }

    class DownHandler(myOriginalHandler: EditorActionHandler) : BaseEditorActionHandler(myOriginalHandler) {
        public override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val editorData = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            val hint = editorData?.hint ?: return myOriginalHandler.execute(editor, caret, dataContext)

            updatePositionAndHint(editor, hint, true, null, editorData.currentForward)
        }
    }

    class EnterHandler(myOriginalHandler: EditorActionHandler) : BaseEditorActionHandler(myOriginalHandler) {
        public override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val hint = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint
            if (hint == null) myOriginalHandler.execute(editor, caret, dataContext)
            else hint.hide()
        }
    }

    class HandlerToHide(myOriginalHandler: EditorActionHandler) : BaseEditorActionHandler(myOriginalHandler) {
        public override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            myOriginalHandler.execute(editor, caret, dataContext)
            editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint?.hide()
        }
    }

    class MyPasteHandler(myOriginalHandler: EditorActionHandler) : BaseEditorActionHandler(myOriginalHandler) {
        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val editorData = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            val text = ClipboardUtil.getTextInClipboard()
            val hint = editorData?.hint
            if (hint == null || text == null || text.isEmpty()) myOriginalHandler.execute(editor, caret, dataContext)
            else updatePositionAndHint(editor, hint, editorData.currentForward, text, editorData.currentForward)
        }
    }

    companion object {
        private val SEARCH_DATA_IN_EDITOR_VIEW_KEY = Key.create<PerEditorSearchData>("ISearchHandler.SEARCH_DATA_IN_EDITOR_VIEW_KEY")
        private val SEARCH_DATA_IN_CARET_KEY = Key.create<PerCaretSearchData>("ISearchHandler.SEARCH_DATA_IN_CARET_KEY")

        private var ourActionsRegistered = false

        data class SearchResult(val forward: Boolean, val isWrapped: Boolean, val notFound: Boolean, val isPrimary: Boolean) {
            val labelText = getLabelText(forward, isWrapped, notFound)
            val color: Color = if (notFound) JBColor.RED else JBColor.foreground()
        }

        private fun getLabelText(forward: Boolean, isWrapped: Boolean, notFound: Boolean): String = sequenceOf(
            if (notFound) "Failing" else null,
            if (isWrapped) "Wrapped" else null,
            "I-search",
            if (!forward) "Backward" else null
        ).filterNotNull().joinToString(" ") + ": "

        private fun updatePositionAndHint(
            editor: Editor,
            hint: MyHint,
            forward: Boolean,
            charTyped: String?,
            lastForward: Boolean,
        ) {
            val editorData = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY) ?: return

            hint.pushHistory()
            val lastSearchWord = hint.labelTarget.text
            if (charTyped != null) hint.labelTarget.text += charTyped
            val target = hint.labelTarget.text.ifEmpty { editorData.lastSearch }
            if (target.isEmpty()) return

            val input = when {
                charTyped != null -> InputString(charTyped)
                forward -> Forward
                else -> Backward
            }
            editorData.lastInput = input

            val results = mutableListOf<SearchResult>()
            editor.caretModel.runForEachCaret { caret ->
                val isPrimary = caret == editor.caretModel.primaryCaret
                val caretData = caret.getUserData(SEARCH_DATA_IN_CARET_KEY)!!
                val highlighter = editor.markupModel.allHighlighters.firstOrNull { highlighter ->
                    highlighter.startOffset <= caret.offset && caret.offset <= highlighter.endOffset
                } ?: addRangeHighlighter(editor, caret.offset, caret.offset)
                caretData.history += CaretState(caret.offset, highlighter.startOffset, highlighter.endOffset, caretData.startOffset)

                val newCaretState = newNewSearch(
                    editor.document.charsSequence,
                    lastSearchWord,
                    caret.offset,
                    input,
                    caretData.startOffset,
                    lastForward
                )

                if (newCaretState != null) {
                    updateCaretAndHighlight(hint, caret, newCaretState, highlighter, editor, isPrimary, caretData)

                    results.add(SearchResult(forward, newCaretState.wrapped, false, isPrimary))
                } else {
                    results.add(SearchResult(forward, isWrapped = false, notFound = false, isPrimary))
                }
            }

            val result = results.firstOrNull { it.isPrimary } ?: return // should never be null
            hint.update(target, result.color, result.labelText)
        }

        private fun updateCaretAndHighlight(
            hint: MyHint,
            caret: Caret,
            newCaretState: NewNewCaretState,
            highlighter: RangeHighlighter,
            editor: Editor,
            isPrimary: Boolean,
            caretData: PerCaretSearchData
        ) {
            // Since cursor moving remove hints, avoid it.
            hint.doWithoutHandler { caret.moveToOffset(newCaretState.caretOffset) }

            highlighter.dispose()
            addRangeHighlighter(editor, newCaretState.highlightOffset, newCaretState.highlightEndLength)

            if (isPrimary) {
                editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            }
            IdeDocumentHistory.getInstance(hint.project).includeCurrentCommandAsNavigation()
            caretData.startOffset = newCaretState.startOffset
        }

        private fun addRangeHighlighter(editor: Editor, startOffset: Int, endOffset: Int): RangeHighlighter =
            editor.markupModel.addRangeHighlighter(
                startOffset,
                endOffset,
                HighlighterLayer.LAST + 1,
                editor.colorsScheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES),
                HighlighterTargetArea.EXACT_RANGE
            )

        private fun detectSmartCaseSensitive(prefix: String): Boolean =
            prefix.any { Character.isUpperCase(it) && Character.toUpperCase(it) != Character.toLowerCase(it) }

        /**
         * Searches [searchWord] in [text] from [from].
         * Returns the position of the first found [searchWord]. Returns -1 if [searchWord] is not found.
         */
        private fun search(text: CharSequence, searchWord: String, from: Int): Int {
            val searcher = StringSearcher(searchWord, detectSmartCaseSensitive(searchWord), true)
            return searcher.scan(text, from, text.length)
        }

        data class NewCaretState(val startOffset: Int, val caretOffset: Int)

        data class NTuple4<T1, T2, T3, T4>(val t1: T1, val t2: T2, val t3: T3, val t4: T4)
        sealed interface InputEvent
        data class InputString(val str: String) : InputEvent
        object Forward : InputEvent
        object Backward : InputEvent

        data class NewNewCaretState(
            val startOffset: Int,
            val caretOffset: Int,
            val searchWord: String,
            val highlightOffset: Int,
            val highlightEndLength: Int,
            val wrapped: Boolean,
        )

        private fun newNewSearch(
            _text: CharSequence,
            lastSearchWord: String,
            _caretOffset: Int,
            input: InputEvent,
            lastStartOffset: Int,
            lastForward: Boolean
        ): NewNewCaretState? {
            val forward = when (input) {
                is Forward -> true
                is Backward -> false
                is InputString -> lastForward
            }
            val rawSearchWord = when (input) {
                is InputString -> lastSearchWord + input.str
                else -> lastSearchWord
            }
            val rawStartOffset = when (input) {
                is InputString -> lastStartOffset
                else -> _caretOffset
            }

            val (text, searchWord, startOffset, caretOffset) =
                if (forward) NTuple4(_text, rawSearchWord, rawStartOffset, _caretOffset)
                else NTuple4(_text.reversed(), rawSearchWord.reversed(), _text.length - rawStartOffset, _text.length - _caretOffset)

            val offsetCandidate =
                if (text.length == startOffset) -1 // In the case the caret is at the end of the text.
                else search(text, searchWord, startOffset)
            val offset =
                if (offsetCandidate < 0) search(text, searchWord, 0)
                else offsetCandidate
            if (offset < 0) return null
            val wrapped = offsetCandidate < 0 && offset >= 0

            val next = (input is Forward && lastForward) || (input is Backward && !lastForward)
            val newStartOffset =
                if (next) caretOffset
                else startOffset
            val newCaretOffset = offset + searchWord.length
            val newHighlightOffset = offset
            val newHighlightEndOffset = offset + searchWord.length

            return if (forward) NewNewCaretState(newStartOffset, newCaretOffset, searchWord, newHighlightOffset, newHighlightEndOffset, wrapped)
            else NewNewCaretState(
                text.length - newStartOffset,
                text.length - newCaretOffset,
                rawSearchWord,
                text.length - newHighlightEndOffset,
                text.length - newHighlightOffset,
                wrapped
            )
        }
    }
}
