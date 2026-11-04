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
        myFixture.addFileToProject("configs/data/natl.yaml", "tile: 256\n")
        myFixture.addFileToProject("configs/data/base.yaml", "tile: 128\n")
    }

    fun testGroupOption() =
        assertEquals("model/tiny.yaml", resolvesTo("configs/train.yaml", "defaults:\n  - _self_\n  - model: tiny\n", "tiny"))

    fun testOverrideAbsoluteFromSubfolder() = assertEquals(
        "model/tiny.yaml",
        resolvesTo("configs/experiment/proto.yaml", "# @package _global_\ndefaults:\n  - override /model: tiny\n", "tiny"),
    )

    fun testPackageSuffix() =
        assertEquals("data/natl.yaml", resolvesTo("configs/train.yaml", "defaults:\n  - data@ocean: natl\n", "natl"))

    fun testBareEntryReadsOwnFolder() =
        assertEquals("data/base.yaml", resolvesTo("configs/data/natl2.yaml", "defaults:\n  - base\n", "base"))

    fun testMissingOptionalIsUnresolved() =
        assertNull(resolvesTo("configs/train.yaml", "defaults:\n  - optional local: default\n", "default"))

    fun testNotADefaultsList() {
        assertNull(refAt("configs/a.yaml", "model: tiny\n", "tiny"))
        assertNull(refAt("configs/b.yaml", "defaults:\n  - _self_\n", "_self_"))
        assertNull(refAt("configs/c.yaml", "defaults:\n  - experiment: null\n", "null"))
    }

    private fun target(value: String, word: String, key: String = "_target_") = myFixture.run {
        addFileToProject("pkg/__init__.py", "from pkg.models.sentinel3mae import Sentinel3MAELitModule\n")
        addFileToProject("pkg/models/__init__.py", "")
        addFileToProject("pkg/models/sentinel3mae.py", "class Sentinel3MAELitModule:\n    def __init__(self, lr: float, depth=4, **kw):\n        pass\n")
        refAt("configs/model/m.yaml", "net:\n  $key: $value\n", word)
    }

    fun testTargetClass() {
        val cls = target("pkg.models.sentinel3mae.Sentinel3MAELitModule", "Sentinel3MAELitModule")?.resolve()
        assertEquals("Sentinel3MAELitModule", (cls as? PyClass)?.name)
    }

    fun testTargetModule() {
        val module = target("pkg.models.sentinel3mae.Sentinel3MAELitModule", "sentinel3mae")?.resolve()
        assertEquals("sentinel3mae.py", (module as? PyFile)?.name)
    }

    fun testTargetReExport() {
        val cls = target("pkg.Sentinel3MAELitModule", "Sentinel3MAELitModule")?.resolve()
        assertEquals("Sentinel3MAELitModule", (cls as? PyClass)?.name)
    }

    fun testNotATarget() = assertNull(target("pkg.models.sentinel3mae", "sentinel3mae", key = "path"))

    /** `file:key` of every target of the interpolation reference at [word]. */
    private fun interpolates(path: String, text: String, word: String): List<String> =
        (refAt(path, text, word) as PsiPolyVariantReference).multiResolve(false)
            .map { (it.element as YAMLKeyValue).let { kv -> "${kv.containingFile.name}:${kv.keyText}" } }.sorted()

    fun testInterpolationGroupKeyOffersEveryOption() = assertEquals(
        listOf("base.yaml:tile", "natl.yaml:tile"),
        interpolates("configs/model/m.yaml", "img_size: \${data.tile}\n", "data.tile"),
    )

    fun testInterpolationPrimaryConfigKey() {
        myFixture.addFileToProject("configs/train.yaml", "seed: 7\n")
        assertEquals(listOf("train.yaml:seed"), interpolates("configs/model/m.yaml", "seed: \${seed}\n", "seed}"))
    }

    fun testInterpolationSameFile() =
        assertEquals(listOf("c.yaml:a"), interpolates("configs/c.yaml", "a: 1\nb: \"x_\${a}\"\n", "a}"))

    fun testResolverIsNotAReference() = assertNull(refAt("configs/d.yaml", "home: \${oc.env:HOME}\n", "oc.env"))

    fun testGroupOf() {
        assertEquals("server/db", groupOf("override /server/db@backup"))
        assertEquals("local", groupOf("optional local"))
    }

    fun testKwargResolvesToInitParameter() {
        target("pkg.models.sentinel3mae.Sentinel3MAELitModule", "x")
        val text = "net:\n  _target_: pkg.models.sentinel3mae.Sentinel3MAELitModule\n  lr: 0.1\n"
        val param = refAt("configs/model/k.yaml", text, "lr")?.resolve()
        assertEquals("lr", (param as? PyNamedParameter)?.name)
        assertNull(refAt("configs/model/k2.yaml", "net:\n  lr: 0.1\n", "lr"))
    }

    fun testRenameParameterUpdatesKwarg() {
        target("pkg.models.sentinel3mae.Sentinel3MAELitModule", "x")
        val yaml = myFixture.addFileToProject("configs/model/k.yaml", "_target_: pkg.models.sentinel3mae.Sentinel3MAELitModule\nlr: 0.1\n")
        val param = yaml.findReferenceAt(yaml.text.indexOf("lr"))!!.resolve()!!
        myFixture.renameElement(param, "learning_rate")
        assertTrue(yaml.text, "learning_rate: 0.1" in yaml.text)
    }

    fun testTargetDocShowsDocstring() {
        val ref = target("pkg.models.sentinel3mae.Sentinel3MAELitModule", "Sentinel3MAELitModule")!!
        val cls = ref.resolve()!!
        val original = ref.element.containingFile.findElementAt(ref.element.textRange.startOffset + ref.rangeInElement.startOffset)
        val doc = DocumentationManager.getProviderFromElement(cls, original).generateDoc(cls, original)
        assertTrue(doc, doc!!.contains("Sentinel3MAELitModule") && "pkg.models.sentinel3mae" in doc)
    }

    private fun complete(path: String, text: String): List<String> {
        myFixture.configureFromTempProjectFile(myFixture.addFileToProject(path, text).virtualFile.path.substringAfter("/src/"))
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    fun testCompleteDefaultsOption() =
        assertSameElements(complete("configs/train.yaml", "defaults:\n  - data: <caret>\n"), "natl", "base")

    fun testCompleteTargetModule() {
        target("pkg.models.sentinel3mae.Sentinel3MAELitModule", "x")
        assertContainsElements(complete("configs/model/t.yaml", "_target_: pkg.models.<caret>\n"), "sentinel3mae")
    }

    fun testCompleteInterpolationKey() {
        complete("configs/model/i.yaml", "img: \${data.<caret>}\n") // the only candidate is inserted directly
        myFixture.checkResult("img: \${data.tile}\n")
    }

    fun testCompleteKwargs() {
        target("pkg.models.sentinel3mae.Sentinel3MAELitModule", "x")
        val keys = complete("configs/model/c.yaml", "_target_: pkg.models.sentinel3mae.Sentinel3MAELitModule\nlr: 1\n<caret>\n")
        assertContainsElements(keys, "depth: ", "_partial_: ")
        assertDoesntContain(keys, "lr: ", "self: ", "kw: ")
    }
}
