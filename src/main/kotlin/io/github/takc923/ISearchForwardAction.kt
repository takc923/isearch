package io.github.takc923

import com.intellij.openapi.editor.actionSystem.EditorAction

class ISearchForwardAction : EditorAction(IncrementalSearchHandler(false))
