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

import com.intellij.codeInsight.editorActions.PasteHandler
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
import com.intellij.openapi.editor.actionSystem.EditorTextInsertHandler
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
import com.intellij.util.Producer
import com.intellij.util.text.StringSearcher
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.Transferable
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class IncrementalSearchHandler(private val searchBack: Boolean) : EditorActionHandler() {

    private class PerEditorSearchData {
        internal var hint: MyHint? = null
        internal var lastSearch = ""
        internal var currentSearchBack = true
    }

    private class PerCaretSearchData {
        internal var segmentHighlighter: RangeHighlighter? = null
        internal var history: List<CaretState> = listOf()
        internal var matchLength: Int = 0
    }

    private data class CaretState(internal val offset: Int, internal val matchLength: Int)

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        val project = editor.project ?: return
        if (!ourActionsRegistered) {
            val actionManager = EditorActionManager.getInstance()

            val typedAction = actionManager.typedAction
            typedAction.setupRawHandler(MyTypedHandler(typedAction.rawHandler))

            actionManager.setActionHandler(IdeActions.ACTION_EDITOR_BACKSPACE, BackSpaceHandler(actionManager.getActionHandler(IdeActions.ACTION_EDITOR_BACKSPACE)))
            actionManager.setActionHandler(IdeActions.ACTION_EDITOR_MOVE_CARET_UP, UpHandler(actionManager.getActionHandler(IdeActions.ACTION_EDITOR_MOVE_CARET_UP)))
            actionManager.setActionHandler(IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN, DownHandler(actionManager.getActionHandler(IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN)))
            actionManager.setActionHandler(IdeActions.ACTION_EDITOR_ENTER, EnterHandler(actionManager.getActionHandler(IdeActions.ACTION_EDITOR_ENTER)))
            actionManager.setActionHandler(IdeActions.ACTION_EDITOR_COPY, HandlerToHide(actionManager.getActionHandler(IdeActions.ACTION_EDITOR_COPY)))
            actionManager.setActionHandler(IdeActions.ACTION_EDITOR_MOVE_LINE_START, HandlerToHide(actionManager.getActionHandler(IdeActions.ACTION_EDITOR_MOVE_LINE_START)))
            actionManager.setActionHandler(IdeActions.ACTION_EDITOR_PASTE, MyPasteHandler(actionManager.getActionHandler(IdeActions.ACTION_EDITOR_PASTE)))

            ourActionsRegistered = true
        }

        val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY) ?: PerEditorSearchData()
        data.currentSearchBack = searchBack
        val currentHint = data.hint
        if (currentHint != null) return updatePositionAndHint(editor, currentHint, searchBack)

        val hint = MyHint(searchBack, project, editor)

        val component = editor.component
        val x = SwingUtilities.convertPoint(component, 0, 0, component).x
        val y = -hint.component.preferredSize.height
        val p = SwingUtilities.convertPoint(component, x, y, component.rootPane.layeredPane)

        HintManagerImpl.getInstanceImpl().showEditorHint(hint, editor, p, HintManagerImpl.HIDE_BY_ESCAPE or HintManagerImpl.HIDE_BY_TEXT_CHANGE, 0, false, HintHint(editor, p).setAwtTooltip(false))

        editor.caretModel.runForEachCaret { it.putUserData(SEARCH_DATA_IN_CARET_KEY, PerCaretSearchData()) }

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
            val caretData = e.caret?.getUserData(SEARCH_DATA_IN_CARET_KEY) ?: return
            caretData.segmentHighlighter?.dispose()
            caretData.segmentHighlighter = null
        }
    }

    private class MyDocumentListener(val editor: Editor) : DocumentListener {
        override fun documentChanged(e: DocumentEvent) {
            editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint?.hide()
        }
    }

    private class MyHint(searchBack: Boolean, val project: Project, private val editor: Editor) : LightweightHint(MyPanel()) {
        private data class HintState(internal val text: String, internal val color: Color, internal val title: String)

        private val caretListener = MyCaretListener()
        private val selectionListener = MySelectionListener()
        private val documentListener = MyDocumentListener(editor)

        private var ignoreCaretMove = false
        private var history: List<HintState> = listOf()
        private val labelTitle = newLabel(getLabelText(searchBack, false, false))
        internal val labelTarget = newLabel("")

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

        fun pushHistory(editor: Editor) {
            editor.caretModel.runForEachCaret {
                val caretData = it.getUserData(SEARCH_DATA_IN_CARET_KEY)!!
                caretData.history += CaretState(it.offset, caretData.matchLength)
            }
            this.history += HintState(this.labelTarget.text, this.labelTarget.foreground, this.labelTitle.text)
        }

        fun popHistory(editor: Editor) {
            val hintState = this.history.lastOrNull() ?: return
            this.history = this.history.dropLast(1)
            this.update(hintState.text, hintState.color, hintState.title)
            editor.caretModel.runForEachCaret(fun(caret: Caret) {
                val caretData = caret.getUserData(SEARCH_DATA_IN_CARET_KEY) ?: return
                val caretState = caretData.history.lastOrNull() ?: return
                caretData.history = caretData.history.dropLast(1)
                moveCaret(caretData, this, caret, caretState.offset, editor, caretState.matchLength)
            })
        }

        override fun hide() {
            if (!isVisible || ignoreCaretMove) return

            super.hide()
            // Recursive runForEachCaret invocations are not allowed. So now using allCarets.forEach
            editor.caretModel.allCarets.forEach {
                val caretData = it.getUserData(SEARCH_DATA_IN_CARET_KEY)
                caretData?.segmentHighlighter?.dispose()
                caretData?.segmentHighlighter = null
            }

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
            else updatePositionAndHint(editor, hint, editorData.currentSearchBack, charTyped.toString())
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
            val hint = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint
            if (hint == null) myOriginalHandler.execute(editor, caret, dataContext)
            else updatePositionAndHint(editor, hint, true)
        }
    }

    class DownHandler(myOriginalHandler: EditorActionHandler) : BaseEditorActionHandler(myOriginalHandler) {
        public override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val hint = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint
            if (hint == null) myOriginalHandler.execute(editor, caret, dataContext)
            else updatePositionAndHint(editor, hint, false)
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

    class MyPasteHandler(myOriginalHandler: EditorActionHandler) : BaseEditorActionHandler(myOriginalHandler), EditorTextInsertHandler {
        override fun execute(editor: Editor?, dataContext: DataContext?, producer: Producer<Transferable>?) {
            val originalPasteHandler = myOriginalHandler as? PasteHandler ?: return
            originalPasteHandler.execute(editor, dataContext, producer)
        }

        override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val editorData = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            val text = ClipboardUtil.getTextInClipboard()
            val hint = editorData?.hint
            if (hint == null || text == null || text.isEmpty()) myOriginalHandler.execute(editor, caret, dataContext)
            else updatePositionAndHint(editor, hint, editorData.currentSearchBack, text)
        }
    }

    companion object {
        private val SEARCH_DATA_IN_EDITOR_VIEW_KEY = Key.create<PerEditorSearchData>("ISearchHandler.SEARCH_DATA_IN_EDITOR_VIEW_KEY")
        private val SEARCH_DATA_IN_CARET_KEY = Key.create<PerCaretSearchData>("ISearchHandler.SEARCH_DATA_IN_CARET_KEY")

        private var ourActionsRegistered = false

        data class SearchResult(val searchBack: Boolean, val isWrapped: Boolean, val notFound: Boolean) {
            val labelText = getLabelText(searchBack, isWrapped, notFound)
            val color: Color = if (notFound) JBColor.RED else JBColor.foreground()
        }

        private fun getLabelText(searchBack: Boolean, isWrapped: Boolean, notFound: Boolean): String = sequenceOf(
                if (notFound) "Failing" else null,
                if (isWrapped) "Wrapped" else null,
                "I-search",
                if (searchBack) "Backward" else null
        ).filterNotNull().joinToString(" ") + ": "

        private fun search(currentOffset: Int, target: String, text: CharSequence, searchBack: Boolean, isNext: Boolean): Int {
            val searcher = StringSearcher(target, detectSmartCaseSensitive(target), !searchBack)
            val diffForNext = if (isNext) 1 else 0
            val (_start, _end) = when {
                searchBack -> Pair(0, currentOffset + target.length - diffForNext)
                else -> Pair(currentOffset + diffForNext, text.length)
            }
            val max = if (searchBack) text.lastIndex else text.length
            val start = minOf(max, maxOf(0, _start))
            val end = minOf(max, maxOf(0, _end))
            return searcher.scan(text, start, end)
        }

        private fun searchWhole(target: String, text: CharSequence, searchBack: Boolean): Int =
                search(if (searchBack) text.lastIndex else 0, target, text, searchBack, false)

        private fun updatePositionAndHint(editor: Editor, hint: MyHint, searchBack: Boolean, charTyped: String? = null) {
            val editorData = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY) ?: return
            hint.pushHistory(editor)

            if (charTyped != null) hint.labelTarget.text += charTyped
            val target = hint.labelTarget.text.ifEmpty { editorData.lastSearch }.ifEmpty { return }
            // search from current offset if using lastSearch
            val isNext = charTyped == null && hint.labelTarget.text.isNotEmpty() || hint.labelTarget.text.length == 1 && searchBack

            val results = mutableListOf<SearchResult>()
            editor.caretModel.runForEachCaret { results.add(updatePosition(target, it, editor, hint, searchBack, isNext)) }

            if (areNotCaretAndHintUpdated(editor, isNext)) return hint.popHistory(editor)

            val result = if (searchBack) results.first() else results.last()
            hint.update(target, result.color, result.labelText)
        }

        private fun areNotCaretAndHintUpdated(editor: Editor, isNext: Boolean): Boolean = editor.caretModel.allCarets.all { caret ->
            val caretData = caret.getUserData(SEARCH_DATA_IN_CARET_KEY)!!
            caretData.history.lastOrNull() == CaretState(caret.offset, caretData.matchLength)
        } && isNext

        private fun updatePosition(target: String, caret: Caret, editor: Editor, hint: MyHint, searchBack: Boolean, isNext: Boolean): SearchResult {
            val caretData = caret.getUserData(SEARCH_DATA_IN_CARET_KEY)!!
            val tmpResult = search(caret.offset, target, editor.document.charsSequence, searchBack, isNext)
            val searchResult = when {
                tmpResult < 0 && isNext -> searchWhole(target, editor.document.charsSequence, searchBack)
                else -> tmpResult
            }
            val isNotFound = searchResult < 0
            val (matchLength, newOffset) = when {
                isNotFound -> Pair(caretData.matchLength, caret.offset)
                else -> Pair(target.length, searchResult)
            }
            moveCaret(caretData, hint, caret, newOffset, editor, matchLength)
            return SearchResult(searchBack, tmpResult != searchResult, isNotFound)
        }

        private fun moveCaret(caretData: PerCaretSearchData, hint: MyHint, caret: Caret, index: Int, editor: Editor, matchLength: Int) {
            caretData.segmentHighlighter?.dispose()
            caretData.segmentHighlighter = null
            hint.doWithoutHandler {
                caret.moveToOffset(index)
                editor.selectionModel.removeSelection()
                editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            }
            addHighlight(editor, caretData, caret.offset, matchLength)
            IdeDocumentHistory.getInstance(hint.project).includeCurrentCommandAsNavigation()
        }

        private fun addHighlight(editor: Editor, caretData: PerCaretSearchData, index: Int, matchLength: Int) {
            val attributes = editor.colorsScheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)
            caretData.matchLength = matchLength
            caretData.segmentHighlighter = editor.markupModel
                    .addRangeHighlighter(index, index + matchLength, HighlighterLayer.LAST + 1, attributes, HighlighterTargetArea.EXACT_RANGE)
        }

        private fun detectSmartCaseSensitive(prefix: String): Boolean =
                prefix.any { Character.isUpperCase(it) && Character.toUpperCase(it) != Character.toLowerCase(it) }
    }
}
