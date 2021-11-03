import com.intellij.openapi.editor.actionSystem.EditorAction

class ISearchForwardAction : EditorAction(IncrementalSearchHandler(true))
