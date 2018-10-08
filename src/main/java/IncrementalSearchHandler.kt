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
import org.jetbrains.annotations.NonNls
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

class IncrementalSearchHandler {

    private class PerHintSearchData constructor(internal val project: Project, internal val label: JLabel) {

        internal var searchStart: Int = 0
        internal var segmentHighlighter: RangeHighlighter? = null
        internal var ignoreCaretMove = false
    }

    private class PerEditorSearchData {
        internal var hint: LightweightHint? = null
        internal var lastSearch: String = ""
    }

    operator fun invoke(project: Project, editor: Editor, searchBack: Boolean) {
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

        var data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
        if (data == null) {
            data = PerEditorSearchData()
        } else if (data.hint != null) {
            val hint = data.hint ?: return
            searchNext(editor, hint, searchBack)
            return
        }

        val label1 = MyLabel(" " + CodeInsightBundle.message("incremental.search.tooltip.prefix"))
        label1.font = UIUtil.getLabelFont().deriveFont(Font.BOLD)

        val panel = MyPanel(label1)
        panel.add(label1, BorderLayout.WEST)
        panel.add(label2, BorderLayout.CENTER)
        panel.border = BorderFactory.createLineBorder(JBColor.black)

        val documentListener = arrayOfNulls<DocumentListener>(1)
        val caretListener = arrayOfNulls<CaretListener>(1)
        val document = editor.document

        val hint = object : LightweightHint(panel) {
            override fun hide() {
                val data = getUserData(SEARCH_DATA_IN_HINT_KEY) ?: return
                val prefix = data.label.text

                super.hide()

                data.segmentHighlighter?.dispose()
                val editorData = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY) ?: return
                editorData.hint = null
                editorData.lastSearch = prefix

                val dListener = documentListener[0]
                if (dListener != null) {
                    document.removeDocumentListener(dListener)
                    documentListener[0] = null
                }

                val cListener = caretListener[0]
                if (cListener != null) {
                    editor.caretModel.removeCaretListener(cListener)
                }
            }
        }

        val dListener = object : DocumentListener {
            override fun documentChanged(e: DocumentEvent?) {
                if (!hint.isVisible) return
                hint.hide()
            }
        }
        documentListener[0] = dListener
        document.addDocumentListener(dListener)

        val listener = object : CaretListener {
            override fun caretPositionChanged(e: CaretEvent?) {
                val data = hint.getUserData(SEARCH_DATA_IN_HINT_KEY)
                if (data != null && data.ignoreCaretMove) return
                if (!hint.isVisible) return
                hint.hide()
            }
        }
        caretListener[0] = listener
        editor.caretModel.addCaretListener(listener)

        val component = editor.component
        val x = SwingUtilities.convertPoint(component, 0, 0, component).x
        val y = -hint.component.preferredSize.height
        val p = SwingUtilities.convertPoint(component, x, y, component.rootPane.layeredPane)

        HintManagerImpl.getInstanceImpl().showEditorHint(hint, editor, p, HintManagerImpl.HIDE_BY_ESCAPE or HintManagerImpl.HIDE_BY_TEXT_CHANGE, 0, false, HintHint(editor, p).setAwtTooltip(false))

        val hintData = PerHintSearchData(project, label2)
        hintData.searchStart = editor.caretModel.offset
        hint.putUserData(SEARCH_DATA_IN_HINT_KEY, hintData)

        data.hint = hint
        editor.putUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY, data)

        if (hintData.label.text.isNotEmpty()) {
            updatePosition(editor, hintData, true, searchBack)
        }
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
            val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            val hint = data?.hint
            if (hint == null) {
                myOriginalHandler?.execute(editor, charTyped, dataContext)
            } else {
                val hintData = hint.getUserData(SEARCH_DATA_IN_HINT_KEY) ?: return
                var text = hintData.label.text
                text += charTyped
                hintData.label.text = text
                val comp = hint.component as MyPanel
                if (comp.truePreferredSize.width > comp.size.width) {
                    val bounds = hint.bounds
                    hint.pack()
                    hint.updateLocation(bounds.x, bounds.y)
                }
                updatePosition(editor, hintData, false, true)
            }
        }
    }

    class BackSpaceHandler constructor(private val myOriginalHandler: EditorActionHandler) : EditorActionHandler() {

        public override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            if (data?.hint == null) {
                myOriginalHandler.execute(editor, caret, dataContext)
            } else {
                val hint = data.hint ?: return
                val hintData = hint.getUserData(SEARCH_DATA_IN_HINT_KEY) ?: return
                var text = hintData.label.text
                if (text.isNotEmpty()) {
                    text = text.substring(0, text.length - 1)
                }
                hintData.label.text = text
                updatePosition(editor, hintData, false, false)
            }
        }
    }

    class UpHandler constructor(private val myOriginalHandler: EditorActionHandler) : EditorActionHandler() {

        public override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            val hint = data?.hint
            if (hint == null) {
                myOriginalHandler.execute(editor, caret, dataContext)
            } else {
                searchBackwardNext(editor, hint)
            }
        }

        override fun isEnabled(editor: Editor, dataContext: DataContext): Boolean {
            val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            return data?.hint != null || myOriginalHandler.isEnabled(editor, dataContext)
        }
    }

    class DownHandler constructor(private val myOriginalHandler: EditorActionHandler) : EditorActionHandler() {

        public override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            val hint = data?.hint
            if (hint == null) {
                myOriginalHandler.execute(editor, caret, dataContext)
            } else {
                searchForwardNext(editor, hint)
            }
        }

        override fun isEnabled(editor: Editor, dataContext: DataContext): Boolean {
            val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            return data?.hint != null || myOriginalHandler.isEnabled(editor, dataContext)
        }
    }

    class EnterHandler constructor(private val myOriginalHandler: EditorActionHandler) : EditorActionHandler() {

        public override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext) {
            val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            val hint = data?.hint
            if (hint == null) {
                myOriginalHandler.execute(editor, caret, dataContext)
            } else if (hint.isVisible) {
                hint.hide()
            }
        }

        override fun isEnabled(editor: Editor, dataContext: DataContext): Boolean {
            val data = editor.getUserData(SEARCH_DATA_IN_EDITOR_VIEW_KEY)
            return data?.hint != null || myOriginalHandler.isEnabled(editor, dataContext)
        }
    }

    companion object {
        private val SEARCH_DATA_IN_EDITOR_VIEW_KEY = Key.create<PerEditorSearchData>("IncrementalSearchHandler.SEARCH_DATA_IN_EDITOR_VIEW_KEY")
        private val SEARCH_DATA_IN_HINT_KEY = Key.create<PerHintSearchData>("IncrementalSearchHandler.SEARCH_DATA_IN_HINT_KEY")

        private var ourActionsRegistered = false

        private fun searchNext(editor: Editor, hint: LightweightHint, searchBack: Boolean) {
            if (searchBack) {
                searchBackwardNext(editor, hint)
            } else {
                searchForwardNext(editor, hint)
            }
        }

        private fun searchBackwardNext(editor: Editor, hint: LightweightHint) {
            val hintData = hint.getUserData(SEARCH_DATA_IN_HINT_KEY) ?: return
            hintData.label.text ?: return
            hintData.searchStart = editor.caretModel.offset
            if (hintData.searchStart == 0) return
            hintData.searchStart--
            updatePosition(editor, hintData, true, true)
            hintData.searchStart = editor.caretModel.offset
        }

        private fun searchForwardNext(editor: Editor, hint: LightweightHint) {
            val hintData = hint.getUserData(SEARCH_DATA_IN_HINT_KEY) ?: return
            hintData.label.text ?: return
            hintData.searchStart = editor.caretModel.offset
            if (hintData.searchStart == editor.document.textLength) return
            hintData.searchStart++
            updatePosition(editor, hintData, true, false)
            hintData.searchStart = editor.caretModel.offset
        }

        private fun acceptableRegExp(pattern: String): Boolean {
            val len = pattern.length

            for (i in 0 until len) {
                when (pattern[i]) {
                    '*' -> return true
                }
            }

            return false
        }

        private fun updatePosition(editor: Editor, data: PerHintSearchData, nothingIfFailed: Boolean, searchBack: Boolean) {
            val prefix = data.label.text
            var matchLength = prefix.length
            var index: Int

            if (matchLength == 0) {
                index = data.searchStart
            } else {
                val document = editor.document
                val text = document.charsSequence
                val length = document.textLength
                val caseSensitive = detectSmartCaseSensitive(prefix)

                if (acceptableRegExp(prefix)) {
                    @NonNls val buf = StringBuffer(prefix.length)
                    val len = prefix.length

                    for (i in 0 until len) {
                        val ch = prefix[i]

                        // bother only * withing text
                        if (ch == '*' && i != 0 && i != len - 1) {
                            buf.append("\\w")
                        } else if ("{}[].+^$*()?".indexOf(ch) != -1) {
                            // do not bother with other metachars
                            buf.append('\\')
                        }
                        buf.append(ch)
                    }

                    try {
                        val pattern = Pattern.compile(buf.toString(), if (caseSensitive) 0 else Pattern.CASE_INSENSITIVE)
                        val matcher = pattern.matcher(text)
                        if (searchBack) {
                            var lastStart = -1
                            var lastEnd = -1

                            while (matcher.find() && matcher.start() < data.searchStart) {
                                lastStart = matcher.start()
                                lastEnd = matcher.end()
                            }

                            index = lastStart
                            matchLength = lastEnd - lastStart
                        } else if (matcher.find(data.searchStart) || !nothingIfFailed && matcher.find(0)) {
                            index = matcher.start()
                            matchLength = matcher.end() - matcher.start()
                        } else {
                            index = -1
                        }
                    } catch (ex: PatternSyntaxException) {
                        index = -1 // let the user to make the garbage pattern
                    }

                } else {
                    val searcher = StringSearcher(prefix, caseSensitive, !searchBack)

                    if (searchBack) {
                        index = searcher.scan(text, 0, Math.max(0, data.searchStart - 1))
                    } else {
                        index = searcher.scan(text, data.searchStart, length)
                        index = if (index < 0) -1 else index
                    }
                    if (index < 0 && !nothingIfFailed) {
                        index = searcher.scan(text, 0, Math.max(0, text.length - 1))
                    }
                }
            }

            if (nothingIfFailed && index < 0) return
            if (data.segmentHighlighter != null) {
                data.segmentHighlighter!!.dispose()
                data.segmentHighlighter = null
            }
            if (index < 0) {
                data.label.foreground = JBColor.RED
            } else {
                data.label.foreground = JBColor.foreground()
                if (matchLength > 0) {
                    val attributes = editor.colorsScheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)
                    data.segmentHighlighter = editor.markupModel
                            .addRangeHighlighter(index, index + matchLength, HighlighterLayer.LAST + 1, attributes, HighlighterTargetArea.EXACT_RANGE)
                }
                data.ignoreCaretMove = true
                editor.caretModel.moveToOffset(index)
                editor.selectionModel.removeSelection()
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
                data.ignoreCaretMove = false
                IdeDocumentHistory.getInstance(data.project).includeCurrentCommandAsNavigation()
            }
        }

        private fun detectSmartCaseSensitive(prefix: String): Boolean {
            var hasUpperCase = false
            for (i in 0 until prefix.length) {
                val c = prefix[i]
                if (Character.isUpperCase(c) && Character.toUpperCase(c) != Character.toLowerCase(c)) {
                    hasUpperCase = true
                    break
                }
            }
            return hasUpperCase
        }
    }
}
