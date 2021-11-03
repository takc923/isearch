import com.intellij.openapi.editor.actionSystem.EditorAction

class ISearchBackwardAction : EditorAction(IncrementalSearchHandler(false))
