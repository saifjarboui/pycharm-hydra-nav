package hydranav

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Key
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

private val FOLDS = Key.create<List<FoldRegion>>("hydranav.hiddenComments")

/** Toggles folding of every comment and Python docstring in the editor. Runs only when invoked. */
class HideCommentsAction : DumbAwareAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    // Always visible: the macOS menu bar's context can lack the file, which used to hide the entry.
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE)
            ?: PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        if (editor.getUserData(FOLDS) != null) {
            editor.showHiddenComments()
            return
        }
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
                editor.putUserData(FOLDS, regions)
            }
        }
    }
}

internal fun Editor.showHiddenComments() {
    val hidden = getUserData(FOLDS) ?: return
    foldingModel.runBatchFoldingOperation { hidden.forEach { foldingModel.removeFoldRegion(it) } }
    putUserData(FOLDS, null)
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
