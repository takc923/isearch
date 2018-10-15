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

import com.intellij.codeInsight.CodeInsightBundle
import com.intellij.codeInsight.hint.HintManagerImpl
import com.intellij.codeInsight.hint.HintUtil
import com.intellij.codeInsight.template.impl.editorActions.TypedActionHandlerBase
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actionSystem.EditorActionManager
import com.intellij.openapi.editor.actionSystem.TypedActionHandler
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
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
import java.awt.*
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class IncrementalSearchHandler {

    private class PerHintSearchData constructor(internal val project: Project, internal val label: JLabel) {
        internal var ignoreCaretMove = false
    }

    private class PerEditorSearchData {
        internal var hint: LightweightHint? = null
    }

    private class PerCaretSearchData constructor() {
        constructor(caretState: CaretState) : this() {
            this.history.add(caretState)
        }

        internal var segmentHighlighter: RangeHighlighter? = null
        internal val history: MutableList<CaretState> = mutableListOf()
    }

    private class CaretState(internal val offset: Int, internal val matchLength: Int, internal val hintState: HintState)
    private class HintState(internal val text: String, internal val color: Color)

    operator fun invoke(project: Project, editor: Editor, searchBack: Boolean) {
        currentSearchBack = searchBack
        if (!ourActionsRegistered) {
            val actionManager = EditorActionManager.getInstance()

            val typedAction = actionManager.typedAction
            typedAction.setupRawHandler(MyTypedHandler(typedAction.rawHandler))

            actionManager.setActionHandler(IdeActions.ACTION_EDITOR_BACKSPACE, BackSpaceHandler(actionManager.getActionHandler(IdeActions.ACTION_EDITOR_BACKSPACE)))
            actionManager.setActionHandler(IdeActions.ACTION_EDITOR_MOVE_CARET_UP, UpHandler(actionManager.getActionHandler(IdeActions.ACTION_EDITOR_MOVE_CARET_UP)))
            actionManager.setActionHandler(IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN, DownHandler(actionManager.getActionHandler(IdeActions.ACTION_EDITOR_MOVE_CARET_DOWN)))
            actionManager.setActionHandler(IdeActions.ACTION_EDITOR_ENTER, EnterHandler(actionManager.getActionHandler(IdeActions.ACTION_EDITOR_ENTER)))

            ourActionsRegistered = true
        }

        val label2: JLabel = MyLabel("")

        val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY) ?: PerEditorSearchData()
        val dataHint = data.hint
        if (dataHint != null) {
            return editor.caretModel.runForEachCaret { searchNext(it, editor, dataHint, searchBack) }
        }

        val label1 = MyLabel(" " + CodeInsightBundle.message("incremental.search.tooltip.prefix"))
        label1.font = UIUtil.getLabelFont().deriveFont(Font.BOLD)

        val panel = MyPanel(label1)
        panel.add(label1, BorderLayout.WEST)
        panel.add(label2, BorderLayout.CENTER)
        panel.border = BorderFactory.createLineBorder(JBColor.black)

        val document = editor.document

        val documentListener = object : DocumentListener {
            override fun documentChanged(e: DocumentEvent?) {
                editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint?.hide()
            }
        }
        document.addDocumentListener(documentListener)

        val caretListener = object : CaretListener {
            override fun caretPositionChanged(e: CaretEvent?) {
                val hint = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint ?: return
                val data = hint.getUserData(SEARCH_DATA_IN_HINT_KEY)
                if (data != null && data.ignoreCaretMove) return
                hint.hide()
            }
        }
        editor.caretModel.addCaretListener(caretListener)

        val hint = object : LightweightHint(panel) {
            override fun hide() {
                if (!isVisible) return

                super.hide()
                editor.caretModel.allCarets.forEach {
                    val caretData = it.getUserData(SEARCH_DATA_IN_CARET_KEY)
                    caretData?.segmentHighlighter?.dispose()
                    caretData?.segmentHighlighter = null
                }

                val editorData = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY) ?: return
                editorData.hint = null
                document.removeDocumentListener(documentListener)
                editor.caretModel.removeCaretListener(caretListener)
            }
        }

        val component = editor.component
        val x = SwingUtilities.convertPoint(component, 0, 0, component).x
        val y = -hint.component.preferredSize.height
        val p = SwingUtilities.convertPoint(component, x, y, component.rootPane.layeredPane)

        HintManagerImpl.getInstanceImpl().showEditorHint(hint, editor, p, HintManagerImpl.HIDE_BY_ESCAPE or HintManagerImpl.HIDE_BY_TEXT_CHANGE, 0, false, HintHint(editor, p).setAwtTooltip(false))

        val hintData = PerHintSearchData(project, label2)
        hint.putUserData(SEARCH_DATA_IN_HINT_KEY, hintData)

        editor.caretModel.runForEachCaret { it?.putUserData(SEARCH_DATA_IN_CARET_KEY, PerCaretSearchData(CaretState(it.offset, 0, HintState("", JBColor.foreground())))) }

        data.hint = hint
        editor.putUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY, data)
    }

    private class MyLabel constructor(text: String) : JLabel(text) {
        init {
            this.background = HintUtil.getInformationColor()
            this.foreground = JBColor.foreground()
            this.isOpaque = true
        }
    }

    private class MyPanel constructor(private val myLeft: Component) : JPanel(BorderLayout()) {

        val truePreferredSize: Dimension
            get() = super.getPreferredSize()

        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            val lSize = myLeft.preferredSize
            return Dimension(size.width + lSize.width, size.height)
        }
    }

    class MyTypedHandler constructor(originalHandler: TypedActionHandler?) : TypedActionHandlerBase(originalHandler) {

        override fun execute(editor: Editor, charTyped: Char, dataContext: DataContext) {
            val hint = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint
            hint ?: return myOriginalHandler?.execute(editor, charTyped, dataContext) ?: Unit
            val hintData = hint.getUserData(SEARCH_DATA_IN_HINT_KEY) ?: return
            hintData.label.text += charTyped
            val comp = hint.component as MyPanel
            // todo
            if (comp.truePreferredSize.width > comp.size.width) {
                val bounds = hint.bounds
                hint.pack()
                hint.updateLocation(bounds.x, bounds.y)
            }
            editor.caretModel.runForEachCaret { updatePosition(it, editor, hintData, currentSearchBack, it.offset) }
        }
    }

    class BackSpaceHandler constructor(private val myOriginalHandler: EditorActionHandler) : EditorActionHandler() {

        public override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val hint = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint
            hint ?: return myOriginalHandler.execute(editor, caret, dataContext)
            val hintData = hint.getUserData(SEARCH_DATA_IN_HINT_KEY) ?: return
            editor.caretModel.runForEachCaret {
                hintData.label.text
                val caretData = it.getUserData(SEARCH_DATA_IN_CARET_KEY)
                if (caretData != null) {
                    caretData.history.removeAt(caretData.history.lastIndex)
                    val lastState = caretData.history.last()

                    caretData.segmentHighlighter?.dispose()
                    caretData.segmentHighlighter = null
                    hintData.label.text = lastState.hintState.text
                    hintData.label.foreground = lastState.hintState.color
                    if (lastState.matchLength > 0) {
                        addHighlight(editor, caretData, lastState.offset, lastState.matchLength)
                    }
                    hintData.ignoreCaretMove = true
                    it.moveToOffset(lastState.offset)
                    editor.selectionModel.removeSelection()
                    editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
                    hintData.ignoreCaretMove = false
                    IdeDocumentHistory.getInstance(hintData.project).includeCurrentCommandAsNavigation()
                }
            }
        }
    }

    class UpHandler constructor(private val myOriginalHandler: EditorActionHandler) : EditorActionHandler() {

        public override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val hint = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint
            if (hint == null) myOriginalHandler.execute(editor, caret, dataContext)
            else editor.caretModel.runForEachCaret { searchBackwardNext(it, editor, hint) }
        }

        override fun isEnabled(editor: Editor, dataContext: DataContext): Boolean {
            val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            return data?.hint != null || myOriginalHandler.isEnabled(editor, dataContext)
        }
    }

    class DownHandler constructor(private val myOriginalHandler: EditorActionHandler) : EditorActionHandler() {

        public override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val hint = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint
            if (hint == null) myOriginalHandler.execute(editor, caret, dataContext)
            else editor.caretModel.runForEachCaret { searchForwardNext(it, editor, hint) }
        }

        override fun isEnabled(editor: Editor, dataContext: DataContext): Boolean {
            val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            return data?.hint != null || myOriginalHandler.isEnabled(editor, dataContext)
        }
    }

    class EnterHandler constructor(private val myOriginalHandler: EditorActionHandler) : EditorActionHandler() {

        public override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val hint = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)?.hint
            if (hint == null) myOriginalHandler.execute(editor, caret, dataContext)
            else hint.hide()
        }

        override fun isEnabled(editor: Editor, dataContext: DataContext): Boolean {
            val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            return data?.hint != null || myOriginalHandler.isEnabled(editor, dataContext)
        }
    }

    companion object {
        private val SEARCH_DATA_IN_EDITOR_VIEW_KEY = Key.create<PerEditorSearchData>("ISearchHandler.SEARCH_DATA_IN_EDITOR_VIEW_KEY")
        private val SEARCH_DATA_IN_HINT_KEY = Key.create<PerHintSearchData>("ISearchHandler.SEARCH_DATA_IN_HINT_KEY")
        private val SEARCH_DATA_IN_CARET_KEY = Key.create<PerCaretSearchData>("ISearchHandler.SEARCH_DATA_IN_CARET_KEY")

        private var ourActionsRegistered = false

        private var currentSearchBack = false

        private fun searchNext(caret: Caret, editor: Editor, hint: LightweightHint, searchBack: Boolean) =
                if (searchBack) searchBackwardNext(caret, editor, hint)
                else searchForwardNext(caret, editor, hint)

        private fun searchBackwardNext(caret: Caret, editor: Editor, hint: LightweightHint) {
            val hintData = hint.getUserData(SEARCH_DATA_IN_HINT_KEY) ?: return
            val text = hintData.label.text ?: return
            updatePosition(caret, editor, hintData, true, caret.offset + text.length - 1)
        }

        private fun searchForwardNext(caret: Caret, editor: Editor, hint: LightweightHint) {
            val hintData = hint.getUserData(SEARCH_DATA_IN_HINT_KEY) ?: return
            hintData.label.text ?: return
            updatePosition(caret, editor, hintData, false, caret.offset + 1)
        }

        private fun updatePosition(caret: Caret, editor: Editor, data: PerHintSearchData, searchBack: Boolean, searchStart: Int) {
            val prefix = data.label.text
            val targetLength = prefix.length
            val caretData = caret.getUserData(SEARCH_DATA_IN_CARET_KEY) ?: return
            val document = editor.document
            val text = document.charsSequence
            val caseSensitive = detectSmartCaseSensitive(prefix)

            val searchResult = when {
                searchBack -> StringSearcher(prefix, caseSensitive, !searchBack).scan(text, 0, maxOf(0, searchStart - 1))
                else -> StringSearcher(prefix, caseSensitive, !searchBack).scan(text, searchStart, document.textLength)
            }

            caretData.segmentHighlighter?.dispose()
            caretData.segmentHighlighter = null

            val color = if (searchResult < 0) JBColor.RED else JBColor.foreground()
            val matchLength = if (searchResult < 0) 0 else targetLength
            val index = if (searchResult < 0) caret.offset else searchResult

            data.label.foreground = color
            data.ignoreCaretMove = true
            caret.moveToOffset(index)
            editor.selectionModel.removeSelection()
            editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
            data.ignoreCaretMove = false
            addHighlight(editor, caretData, caret.offset, matchLength)
            IdeDocumentHistory.getInstance(data.project).includeCurrentCommandAsNavigation()
            caretData.history.add(CaretState(caret.offset, matchLength, HintState(data.label.text, data.label.foreground)))
        }

        private fun addHighlight(editor: Editor, caretData: PerCaretSearchData, index: Int, matchLength: Int) {
            val attributes = editor.colorsScheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)
            caretData.segmentHighlighter = editor.markupModel
                    .addRangeHighlighter(index, index + matchLength, HighlighterLayer.LAST + 1, attributes, HighlighterTargetArea.EXACT_RANGE)
        }

        private fun detectSmartCaseSensitive(prefix: String): Boolean =
                prefix.any { Character.isUpperCase(it) && Character.toUpperCase(it) != Character.toLowerCase(it) }

    }
}
