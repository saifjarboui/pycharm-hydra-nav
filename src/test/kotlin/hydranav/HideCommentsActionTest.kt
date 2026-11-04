package hydranav

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class HideCommentsActionTest : BasePlatformTestCase() {

    // Build the language's own fold regions first, as the IDE does on open.
    private fun toggle() {
        com.intellij.testFramework.EditorTestUtil.buildInitialFoldingsInBackground(myFixture.editor)
        myFixture.performEditorAction("hydranav.HideComments")
    }

    private fun hiddenText() = myFixture.editor.foldingModel.allFoldRegions
        .filter { !it.isExpanded }
        .map { myFixture.editor.document.getText(com.intellij.openapi.util.TextRange(it.startOffset, it.endOffset)) }

    fun testPythonCommentsAndDocstrings() {
        myFixture.configureByText(
            "a.py",
            "\"\"\"mod doc\"\"\"\n# full line\ndef f():\n    \"\"\"fn doc\"\"\"\n    x = 1  # trailing\n    return x\n",
        )
        toggle()
        val ownRegions = myFixture.editor.foldingModel.allFoldRegions.count { it.isExpanded }
        val hidden = hiddenText()
        assertEquals(4, hidden.size)
        assertTrue(hidden.any { it == "# full line\n" })
        assertTrue(hidden.any { it == "  # trailing" })
        toggle()
        assertTrue(hiddenText().isEmpty())
        assertEquals(ownRegions, myFixture.editor.foldingModel.allFoldRegions.size)
    }

    fun testClosingFileShowsComments() {
        myFixture.configureByText("a.yaml", "# note\nkey: 1\n")
        toggle()
        assertEquals(1, hiddenText().size)
        ShowCommentsOnClose().beforeFileClosed(
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project),
            myFixture.file.virtualFile,
        )
        assertTrue(hiddenText().isEmpty())
    }

    fun testYamlComment() {
        myFixture.configureByText("a.yaml", "# note\nkey: 1\n")
        toggle()
        assertEquals(listOf("# note\n"), hiddenText())
    }

    fun testYamlCommentAtEndOfBlock() {
        myFixture.configureByText(
            "a.yaml",
            "defaults:\n  - model: x\n  # - data: y\n\ntrainer:\n  max_epochs: 10  # t\n  # gpus: 1\n  # more\n\ncallbacks: x\n",
        )
        toggle()
        val hidden = hiddenText()
        assertEquals(4, hidden.size)
        listOf("# - data: y", "# t", "# gpus: 1", "# more").forEach { c -> assertTrue(c, hidden.any { it.contains(c) }) }
        toggle()
        assertTrue(hiddenText().isEmpty())
    }
}
