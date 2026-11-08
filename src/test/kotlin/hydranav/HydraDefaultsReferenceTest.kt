package hydranav

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import org.jetbrains.yaml.psi.YAMLKeyValue
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyNamedParameter
import com.intellij.codeInsight.documentation.DocumentationManager

class HydraDefaultsReferenceTest : BasePlatformTestCase() {

    private fun refAt(path: String, text: String, word: String) =
        myFixture.addFileToProject(path, text).findReferenceAt(text.indexOf(word))

    private fun resolvesTo(path: String, text: String, word: String): String? =
        (refAt(path, text, word)?.resolve() as? PsiFile)?.virtualFile?.path?.substringAfter("/configs/")

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject("configs/model/tiny.yaml", "dim: 96\n")
        myFixture.addFileToProject("configs/data/cifar10.yaml", "image_size: 256\n")
        myFixture.addFileToProject("configs/data/base.yaml", "image_size: 128\n")
    }

    fun testGroupOption() =
        assertEquals("model/tiny.yaml", resolvesTo("configs/train.yaml", "defaults:\n  - _self_\n  - model: tiny\n", "tiny"))

    fun testOverrideAbsoluteFromSubfolder() = assertEquals(
        "model/tiny.yaml",
        resolvesTo("configs/experiment/proto.yaml", "# @package _global_\ndefaults:\n  - override /model: tiny\n", "tiny"),
    )

    fun testPackageSuffix() =
        assertEquals("data/cifar10.yaml", resolvesTo("configs/train.yaml", "defaults:\n  - data@eval: cifar10\n", "cifar10"))

    fun testBareEntryReadsOwnFolder() =
        assertEquals("data/base.yaml", resolvesTo("configs/data/imagenet.yaml", "defaults:\n  - base\n", "base"))

    fun testMissingOptionalIsUnresolved() =
        assertNull(resolvesTo("configs/train.yaml", "defaults:\n  - optional local: default\n", "default"))

    fun testNotADefaultsList() {
        assertNull(refAt("configs/a.yaml", "model: tiny\n", "tiny"))
        assertNull(refAt("configs/b.yaml", "defaults:\n  - _self_\n", "_self_"))
        assertNull(refAt("configs/c.yaml", "defaults:\n  - experiment: null\n", "null"))
    }

    private fun target(value: String, word: String, key: String = "_target_") = myFixture.run {
        addFileToProject("pkg/__init__.py", "from pkg.models.resnet import ResNetClassifier\n")
        addFileToProject("pkg/models/__init__.py", "")
        addFileToProject(
            "pkg/models/resnet.py",
            "from typing import Literal, Optional\n" +
                "class ResNetClassifier:\n    def __init__(self, lr: float, depth=4, **kw):\n        pass\n" +
                "    @classmethod\n    def from_config(cls, path: str, mode: Literal['fast', 'slow'] = 'fast', " +
                "strict: bool = True, seed: Optional[int] = None):\n        pass\n",
        )
        refAt("configs/model/m.yaml", "net:\n  $key: $value\n", word)
    }

    fun testTargetClass() {
        val cls = target("pkg.models.resnet.ResNetClassifier", "ResNetClassifier")?.resolve()
        assertEquals("ResNetClassifier", (cls as? PyClass)?.name)
    }

    fun testTargetModule() {
        val module = target("pkg.models.resnet.ResNetClassifier", "resnet")?.resolve()
        assertEquals("resnet.py", (module as? PyFile)?.name)
    }

    fun testTargetReExport() {
        val cls = target("pkg.ResNetClassifier", "ResNetClassifier")?.resolve()
        assertEquals("ResNetClassifier", (cls as? PyClass)?.name)
    }

    fun testNotATarget() = assertNull(target("pkg.models.resnet", "resnet", key = "path"))

    /** `file:key` of every target of the interpolation reference at [word]. */
    private fun interpolates(path: String, text: String, word: String): List<String> =
        (refAt(path, text, word) as PsiPolyVariantReference).multiResolve(false)
            .map { (it.element as YAMLKeyValue).let { kv -> "${kv.containingFile.name}:${kv.keyText}" } }.sorted()

    fun testInterpolationGroupKeyOffersEveryOption() = assertEquals(
        listOf("base.yaml:image_size", "cifar10.yaml:image_size"),
        interpolates("configs/model/m.yaml", "img_size: \${data.image_size}\n", "data.image_size"),
    )

    fun testInterpolationPrimaryConfigKey() {
        myFixture.addFileToProject("configs/train.yaml", "seed: 7\n")
        assertEquals(listOf("train.yaml:seed"), interpolates("configs/model/m.yaml", "seed: \${seed}\n", "seed}"))
    }

    fun testInterpolationSameFile() =
        assertEquals(listOf("c.yaml:a"), interpolates("configs/c.yaml", "a: 1\nb: \"x_\${a}\"\n", "a}"))

    fun testRelativeSibling() =
        assertEquals(listOf("c.yaml:b"), interpolates("configs/c.yaml", "m:\n  a: \${.b}\n  b: 1\n", ".b}"))

    fun testRelativeParentLevel() =
        assertEquals(listOf("c.yaml:b"), interpolates("configs/c.yaml", "b: 1\nm:\n  a: \${..b}\n", "..b}"))

    fun testRelativeNestedPath() =
        assertEquals(listOf("c.yaml:y"), interpolates("configs/c.yaml", "m:\n  a: \${.x.y}\n  x:\n    y: 1\n", ".x.y}"))

    fun testRelativeMissingIsUnresolved() =
        assertNull(refAt("configs/c.yaml", "m:\n  a: \${.nope}\n", ".nope")?.resolve())

    fun testOcSelectKey() =
        assertEquals(listOf("c.yaml:seed"), interpolates("configs/c.yaml", "seed: 1\nx: \${oc.select:seed,0}\n", "seed,"))

    private fun envTarget(text: String, word: String): String? =
        refAt("configs/d.yaml", text, word)?.resolve()?.let { it.containingFile.name + ":" + it.text }

    fun testEnvKeyResolvesToDotEnvLine() {
        myFixture.addFileToProject(".env", "# c\nOTHER=1\nexport DB_HOST=db\n")
        assertEquals(".env:DB_HOST", envTarget("h: \${oc.env:DB_HOST,localhost}\n", "DB_HOST"))
    }

    fun testEnvKeyNotInDotEnvIsUnresolved() {
        myFixture.addFileToProject(".env", "OTHER=1\n")
        assertNull(envTarget("h: \${oc.env:DB_HOST}\n", "DB_HOST"))
    }

    fun testEnvKeyAndEqualsOnDifferentLinesDoNotMatch() {
        myFixture.addFileToProject(".env", "\n".repeat(50_000) + "DB_HOST\n=x\n")
        assertNull(envTarget("h: \${oc.env:DB_HOST}\n", "DB_HOST"))
    }

    fun testEnvKeyAfterLongBlankRun() {
        myFixture.addFileToProject(".env", "\n".repeat(50_000) + "DB_PORT=1\n")
        assertEquals(".env:DB_PORT", envTarget("h: \${oc.env:DB_PORT}\n", "DB_PORT"))
    }

    fun testOtherResolverIsNotAReference() = assertNull(refAt("configs/d.yaml", "n: \${oc.decode:1}\n", "oc.decode"))

    fun testGroupOf() {
        assertEquals("server/db", groupOf("override /server/db@backup"))
        assertEquals("local", groupOf("optional local"))
    }

    fun testKwargResolvesToInitParameter() {
        target("pkg.models.resnet.ResNetClassifier", "x")
        val text = "net:\n  _target_: pkg.models.resnet.ResNetClassifier\n  lr: 0.1\n"
        val param = refAt("configs/model/k.yaml", text, "lr")?.resolve()
        assertEquals("lr", (param as? PyNamedParameter)?.name)
        assertNull(refAt("configs/model/k2.yaml", "net:\n  lr: 0.1\n", "lr"))
    }

    fun testKwargResolvesToInheritedInit() {
        target("pkg.models.resnet.ResNetClassifier", "x")
        myFixture.addFileToProject(
            "pkg/models/child.py",
            "from pkg.models.resnet import ResNetClassifier\nclass Child(ResNetClassifier):\n    pass\n",
        )
        val text = "net:\n  _target_: pkg.models.child.Child\n  lr: 0.1\n"
        val param = refAt("configs/model/inherited.yaml", text, "lr")?.resolve()
        assertEquals("lr", (param as? PyNamedParameter)?.name)
    }

    fun testRenameParameterUpdatesKwarg() {
        target("pkg.models.resnet.ResNetClassifier", "x")
        val yaml = myFixture.addFileToProject("configs/model/k.yaml", "_target_: pkg.models.resnet.ResNetClassifier\nlr: 0.1\n")
        val param = yaml.findReferenceAt(yaml.text.indexOf("lr"))!!.resolve()!!
        myFixture.renameElement(param, "learning_rate")
        assertTrue(yaml.text, "learning_rate: 0.1" in yaml.text)
    }

    fun testTargetDocShowsDocstring() {
        val ref = target("pkg.models.resnet.ResNetClassifier", "ResNetClassifier")!!
        val cls = ref.resolve()!!
        val original = ref.element.containingFile.findElementAt(ref.element.textRange.startOffset + ref.rangeInElement.startOffset)
        val doc = DocumentationManager.getProviderFromElement(cls, original).generateDoc(cls, original)
        assertTrue(doc, doc!!.contains("ResNetClassifier") && "pkg.models.resnet" in doc)
    }

    /** [invocationCount] 0 is the popup while typing, 1 is Ctrl+Space. */
    private fun complete(path: String, text: String, invocationCount: Int = 1): List<String> {
        myFixture.configureFromTempProjectFile(myFixture.addFileToProject(path, text).virtualFile.path.substringAfter("/src/"))
        myFixture.complete(com.intellij.codeInsight.completion.CompletionType.BASIC, invocationCount)
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testCompleteDefaultsOption() =
        assertSameElements(complete("configs/train.yaml", "defaults:\n  - data: <caret>\n"), "cifar10", "base")

    fun testCompleteTargetModule() {
        target("pkg.models.resnet.ResNetClassifier", "x")
        assertContainsElements(complete("configs/model/t.yaml", "_target_: pkg.models.<caret>\n"), "resnet")
    }

    fun testCompleteInterpolationKey() {
        complete("configs/model/i.yaml", "img: \${data.<caret>}\n") // the only candidate is inserted directly
        myFixture.checkResult("img: \${data.image_size}\n")
    }

    fun testCompleteKwargs() {
        target("pkg.models.resnet.ResNetClassifier", "x")
        val keys = complete("configs/model/c.yaml", "_target_: pkg.models.resnet.ResNetClassifier\nlr: 1\n<caret>\n")
        assertContainsElements(keys, "depth: ", "_partial_: ")
        assertDoesntContain(keys, "lr: ", "self: ", "kw: ")
    }

    private val method = "pkg.models.resnet.ResNetClassifier.from_config"

    fun testTargetClassMethod() {
        val fn = target(method, "from_config")?.resolve()
        assertEquals("from_config", (fn as? com.jetbrains.python.psi.PyFunction)?.name)
    }

    fun testCompleteMethodKwargsWhileTyping() {
        target(method, "x")
        val keys = complete("configs/model/w.yaml", "_target_: $method\npath: x\ns<caret>\n", invocationCount = 0)
        assertContainsElements(keys, "strict: ", "seed: ")
        assertDoesntContain(keys, "path: ", "cls: ")
    }

    fun testCompleteKwargValues() {
        target(method, "x")
        assertSameElements(complete("configs/model/v1.yaml", "_target_: $method\nmode: <caret>\n"), "fast", "slow")
        assertSameElements(complete("configs/model/v2.yaml", "_target_: $method\nstrict: <caret>\n"), "true", "false")
        assertContainsElements(complete("configs/model/v3.yaml", "_target_: $method\nseed: <caret>\n"), "null")
    }

    fun testCompleteDefaultsGroup() {
        assertContainsElements(complete("configs/train.yaml", "defaults:\n  - <caret>\n"), "model: ", "data: ")
        complete("configs/experiment/p.yaml", "defaults:\n  - override /mo<caret>\n") // the only candidate is inserted directly
        myFixture.checkResult("defaults:\n  - override /model: <caret>\n")
    }

    fun testRenameGroupFolderUpdatesEntry() {
        val yaml = myFixture.addFileToProject("configs/train.yaml", "defaults:\n  - override /model: tiny\n")
        myFixture.renameElement(myFixture.findFileInTempDir("configs/model").let { myFixture.psiManager.findDirectory(it)!! }, "net")
        assertTrue(yaml.text, "override /net: tiny" in yaml.text)
    }

    fun testRenameConfigFileUpdatesEntry() {
        val yaml = myFixture.addFileToProject("configs/train.yaml", "defaults:\n  - model: tiny\n")
        myFixture.renameElement(myFixture.findFileInTempDir("configs/model/tiny.yaml").let { myFixture.psiManager.findFile(it)!! }, "big.yaml")
        assertTrue(yaml.text, "model: big" in yaml.text)
    }

    fun testCompleteDefaultsKeywords() =
        assertContainsElements(complete("configs/train.yaml", "defaults:\n  - <caret>\n"), "override ", "optional ", "_self_")

    fun testCompleteNestedGroup() {
        myFixture.addFileToProject("configs/model/enc/a.yaml", "x: 1\n")
        assertContainsElements(complete("configs/train.yaml", "defaults:\n  - <caret>\n"), "model/enc: ")
    }

    fun testCompletePackage() =
        assertSameElements(complete("configs/train.yaml", "defaults:\n  - data@<caret>\n"), "_global_", "_group_")
}
