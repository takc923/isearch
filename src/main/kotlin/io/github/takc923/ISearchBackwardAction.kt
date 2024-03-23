package io.github.takc923

import com.intellij.openapi.editor.actionSystem.EditorAction

class ISearchBackwardAction : EditorAction(IncrementalSearchHandler(true))
