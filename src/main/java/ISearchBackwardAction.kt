import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware

class ISearchBackwardAction : AnAction(), DumbAware {
    init {
        isEnabledInModalContext = true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val dataContext = e.dataContext
        val project = CommonDataKeys.PROJECT.getData(dataContext) ?: return
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return

        IncrementalSearchHandler().invoke(project, editor)
    }

    override fun update(event: AnActionEvent) {
        val presentation = event.presentation
        val dataContext = event.dataContext
        val project = CommonDataKeys.PROJECT.getData(dataContext)
        if (project == null) {
            presentation.isEnabled = false
            return
        }

        val editor = CommonDataKeys.EDITOR.getData(dataContext)
        if (editor == null) {
            presentation.isEnabled = false
            return
        }

        presentation.isEnabled = true
    }
}