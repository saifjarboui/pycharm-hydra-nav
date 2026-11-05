package hydranav

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyDocStringOwner
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction

/** Toggles folding of every comment and Python docstring in the editor. Runs only when invoked. */
class HideCommentsAction : DumbAwareToggleAction() {

    // EDT: the fold model is read here; a cheap loop over folds, only while a menu is open.
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    // Always visible: the macOS menu bar's context can lack the file, which used to hide the entry.
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null
        e.presentation.icon = if (Toggleable.isSelected(e.presentation)) AllIcons.General.InspectionsOK else null
    }

    override fun isSelected(e: AnActionEvent) =
        e.getData(CommonDataKeys.EDITOR)?.foldingModel?.allFoldRegions?.any { it.isOurs() } == true

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        if (!state) return editor.showHiddenComments()
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)
            ?: PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        editor.foldingModel.runBatchFoldingOperation {
            run {
                val docs = PsiTreeUtil.collectElementsOfType(file, PyDocStringOwner::class.java)
                    .filter { it is PyFile || it is PyClass || it is PyFunction }
                    .mapNotNull { it.docStringExpression }
                // addFoldRegion rejects a range that half-overlaps the language's own folds
                // (a YAML block ends before its last comment's newline), so try narrower ranges.
                val regions = (PsiTreeUtil.collectElementsOfType(file, PsiComment::class.java) + docs)
                    .distinctBy { it.textRange.startOffset }
                    .mapNotNull { el ->
                        editor.foldRangesFor(el).firstNotNullOfOrNull {
                            editor.foldingModel.addFoldRegion(it.startOffset, it.endOffset, "")
                        }
                    }
                regions.forEach { it.isExpanded = false }
            }
        }
    }
}

// Our folds are found by their empty placeholder, so ones the IDE restored from its saved state count too.
// ponytail: another plugin's empty-placeholder folds would also be removed; tag regions if that ever happens.
private fun FoldRegion.isOurs() = placeholderText.isEmpty()

internal fun Editor.showHiddenComments() {
    val hidden = foldingModel.allFoldRegions.filter { it.isOurs() }
    if (hidden.isEmpty()) return
    foldingModel.runBatchFoldingOperation { hidden.forEach { foldingModel.removeFoldRegion(it) } }
}

/** Shows comments again before the IDE saves editor state on exit, where no file-close event fires. */
class ShowCommentsOnProjectClose : ProjectCloseListener {
    override fun projectClosingBeforeSave(project: Project) {
        FileEditorManager.getInstance(project).allEditors.filterIsInstance<TextEditor>()
            .forEach { it.editor.showHiddenComments() }
    }
}

/** Closing a file turns the feature off, so the IDE doesn't persist the hidden state and reopening shows the original. */
class ShowCommentsOnClose : FileEditorManagerListener.Before {
    override fun beforeFileClosed(source: FileEditorManager, file: VirtualFile) {
        source.getEditors(file).filterIsInstance<TextEditor>().forEach { it.editor.showHiddenComments() }
    }
}

/**
 * Widest first: the whole line with its newline for a comment alone on its line, else from the
 * whitespace before it; then that without the newline; then the element itself.
 */
private fun Editor.foldRangesFor(el: PsiElement): List<TextRange> {
    val text = document.charsSequence
    val r = el.textRange
    if (r.isEmpty) return emptyList()
    var start = r.startOffset
    while (start > 0 && text[start - 1].let { it == ' ' || it == '\t' }) start--
    var end = r.endOffset
    if (start == 0 || text[start - 1] == '\n') {
        var e = end
        while (e < text.length && text[e].let { it == ' ' || it == '\t' }) e++
        if (e == text.length) end = e else if (text[e] == '\n') end = e + 1
    }
    return listOf(TextRange(start, end), TextRange(start, r.endOffset), r).distinct()
}
