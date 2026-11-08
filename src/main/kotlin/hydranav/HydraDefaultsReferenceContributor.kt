package hydranav

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.ResolveResult
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.QualifiedName
import com.intellij.util.ProcessingContext
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyNamedParameter
import com.jetbrains.python.psi.PyPsiFacade
import org.jetbrains.yaml.YAMLUtil
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/** Makes the option of each Hydra `defaults:` entry a reference to its YAML file. */
class HydraDefaultsReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(YAMLScalar::class.java), Provider)
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(YAMLKeyValue::class.java), KwargProvider)
    }
}

private object Provider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
        interpolationReferences(scalar)?.let { return it }
        targetReferences(scalar)?.let { return it }
        val path = defaultsPath(scalar) ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(HydraConfigReference(scalar, path))
    }
}

// `${a.b}`, relative `${.a}` / `${..a}`, and the key of `${oc.select:a.b,default}`; other resolvers don't match
private val INTERPOLATION = Regex("""\$\{(?:oc\.select:\s*)?(\.*)(\w[\w.]*)\s*[,}]""")
private val ENV_INTERPOLATION = Regex("""\$\{oc\.env:\s*(\w+)\s*[,}]""")

/** One reference per `${a.b}` or `${oc.env:X}` in [scalar]; null when it has none. Only regexes here: this runs on every scalar. */
private fun interpolationReferences(scalar: YAMLScalar): Array<PsiReference>? {
    if ("\${" !in scalar.text) return null
    val keys = INTERPOLATION.findAll(scalar.text).mapNotNull { match ->
        val dots = match.groups[1]!!.value.length
        val key = match.groups[2]!!
        val path = key.value.split('.')
        if (path.any { it.isEmpty() }) null
        else HydraInterpolationReference(scalar, TextRange(key.range.first - dots, key.range.last + 1), path, dots)
    }
    val envs = ENV_INTERPOLATION.findAll(scalar.text).map { match ->
        val name = match.groups[1]!!
        HydraEnvReference(scalar, TextRange(name.range.first, name.range.last + 1), name.value)
    }
    return (keys + envs).toList<PsiReference>().toTypedArray()
}

/**
 * `${oc.env:DB_HOST}` → the `DB_HOST=...` line of the nearest `.env` file, going up from this file's folder.
 * Only the key is linked, nothing is read from the real environment, and a `.env` is only a guess at where
 * the variable comes from (Hydra doesn't load it by itself). Runs on click only.
 */
private class HydraEnvReference(scalar: YAMLScalar, range: TextRange, private val name: String) :
    PsiReferenceBase<YAMLScalar>(scalar, range, true) {

    override fun resolve(): PsiElement? {
        val index = ProjectFileIndex.getInstance(element.project)
        val line = Regex("""(?m)^[ \t]*(?:export[ \t]+)?(${Regex.escape(name)})[ \t]*=""") // [ \t] not \s: no crossing lines
        var dir = element.containingFile.originalFile.virtualFile?.parent
        while (dir != null && index.isInContent(dir)) {
            val file = dir.findChild(".env")?.takeIf { !it.isDirectory && !it.`is`(VFileProperty.SYMLINK) }?.let { element.manager.findFile(it) }
            val hit = file?.let { line.find(it.text) }
            if (file != null && hit != null) return file.findElementAt(hit.groups[1]!!.range.first)
            dir = dir.parent
        }
        return null
    }
}

/**
 * `${data.image_size}` → every `image_size:` that can fill it. Which `data` option is used is only known at run time,
 * so all candidates are offered and the IDE shows a chooser when there are several. Going up from the file's
 * folder, the nearest level with a match wins. At each level it checks the key path `data.image_size` in the YAML
 * files there, and `image_size` in each YAML file of the `data` folder.
 * Relative `${.x}` (sibling) and `${..x}` (parent's sibling) are resolved by walking up this file's PSI, no folders.
 * ponytail: nested groups, `# @package` remapping, completion of relative keys and rename are not handled.
 */
private class HydraInterpolationReference(
    scalar: YAMLScalar, range: TextRange, private val path: List<String>, private val dots: Int,
) : PsiPolyVariantReferenceBase<YAMLScalar>(scalar, range, true) {

    private fun resolveRelative(): Array<ResolveResult> {
        var mapping = PsiTreeUtil.getParentOfType(element, YAMLMapping::class.java)
        repeat(dots - 1) {
            mapping = PsiTreeUtil.getParentOfType(mapping, YAMLMapping::class.java)
        }
        var key: YAMLKeyValue? = null
        for (name in path) {
            key = mapping?.getKeyValueByKey(name) ?: return ResolveResult.EMPTY_ARRAY
            mapping = key.value as? YAMLMapping
        }
        return key?.let { PsiElementResolveResult.createResults(it) } ?: ResolveResult.EMPTY_ARRAY
    }

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> {
        if (dots > 0) return resolveRelative()
        val index = ProjectFileIndex.getInstance(element.project)
        var dir = element.containingFile.originalFile.virtualFile?.parent
        while (dir != null && index.isInContent(dir)) {
            val group = dir.findChild(path[0])?.takeIf { it.isDirectory && path.size > 1 }
            val keys = (yamlFiles(dir).mapNotNull { YAMLUtil.getQualifiedKeyInFile(it, path) } +
                yamlFiles(group).mapNotNull { YAMLUtil.getQualifiedKeyInFile(it, path.drop(1)) })
                .filter { it.value != element } // `seed: ${seed}` is not its own definition
            if (keys.isNotEmpty()) return PsiElementResolveResult.createResults(keys)
            dir = dir.parent
        }
        return ResolveResult.EMPTY_ARRAY
    }

    /** Ctrl+Space in `${data.ti}`: the keys one level below `data`, at the nearest folder level that has any. */
    override fun getVariants(): Array<Any> {
        if (dots > 0) return emptyArray()
        val parent = path.dropLast(1)
        val index = ProjectFileIndex.getInstance(element.project)
        var dir = element.containingFile.originalFile.virtualFile?.parent
        while (dir != null && index.isInContent(dir)) {
            val group = parent.firstOrNull()?.let { dir.findChild(it) }?.takeIf { it.isDirectory }
            val names = yamlFiles(dir).flatMap { keysAt(it, parent) } + yamlFiles(group).flatMap { keysAt(it, parent.drop(1)) } +
                (if (parent.isEmpty()) dir.children.filter { it.isDirectory }.map { it.name } else emptyList())
            if (names.isNotEmpty()) return names.distinct().map { (parent + it).joinToString(".") }.toTypedArray()
            dir = dir.parent
        }
        return emptyArray()
    }

    private fun keysAt(file: YAMLFile, path: List<String>): List<String> {
        val mapping = if (path.isEmpty()) file.documents.firstOrNull()?.topLevelValue
        else YAMLUtil.getQualifiedKeyInFile(file, path)?.value
        return (mapping as? YAMLMapping)?.keyValues?.map { it.keyText }.orEmpty()
    }

    private fun yamlFiles(dir: VirtualFile?): List<YAMLFile> =
        dir?.children.orEmpty().filter { it.extension == "yaml" || it.extension == "yml" }
            .mapNotNull { element.manager.findFile(it) as? YAMLFile }
}

/** One reference per dotted segment of a `_target_: pkg.module.Class` value; null when [scalar] is not one. */
private fun targetReferences(scalar: YAMLScalar): Array<PsiReference>? {
    val keyValue = scalar.parent as? YAMLKeyValue ?: return null
    if (keyValue.keyText != "_target_" || keyValue.value != scalar) return null
    val range = ElementManipulators.getValueTextRange(scalar)
    val names = range.substring(scalar.text).split('.')
    if (names.any { it.isBlank() || "\${" in it }) return null
    var start = range.startOffset
    return Array(names.size) { i ->
        HydraTargetReference(scalar, TextRange.from(start, names[i].length), names.subList(0, i + 1))
            .also { start += names[i].length + 1 }
    }
}

/** Resolves `a.b.c` with PyCharm's import resolver, falling back to name `c` inside module/package `a.b`. */
private class HydraTargetReference(scalar: YAMLScalar, range: TextRange, private val names: List<String>) :
    PsiReferenceBase<YAMLScalar>(scalar, range, true) { // soft: a third-party target may not be installed

    override fun resolve(): PsiElement? = resolveTarget(element, names)

    /** Ctrl+Space: the subpackages, modules and names inside the segment before this one. */
    override fun getVariants(): Array<Any> {
        val manager = element.manager
        val (dirs, file) = if (names.size == 1) {
            val roots = ProjectRootManager.getInstance(element.project).run { contentSourceRoots.ifEmpty { contentRoots } }
            roots.mapNotNull { manager.findDirectory(it) } to null
        } else when (val parent = resolveTarget(element, names.dropLast(1))) {
            is PsiDirectory -> listOf(parent) to parent.findFile("__init__.py") as? PyFile
            is PyFile -> emptyList<PsiDirectory>() to parent
            is PyClass -> return parent.methods.mapNotNull { it.name }.filterNot { it.startsWith("_") }.toTypedArray()
            else -> return emptyArray()
        }
        val modules = dirs.flatMap { d ->
            d.subdirectories.map { it.name } + d.files.filter { it.name.endsWith(".py") && it.name != "__init__.py" }
                .map { it.name.removeSuffix(".py") }
        }
        val members = file?.let { f -> f.topLevelClasses.mapNotNull { it.name } + f.topLevelFunctions.mapNotNull { it.name } +
            f.importTargets.mapNotNull { it.asName ?: it.importedQName?.lastComponent } +
            f.fromImports.flatMap { imp -> imp.importElements.mapNotNull { it.visibleName } } }.orEmpty()
        return (modules + members).filterNot { it.startsWith("_") }.distinct().toTypedArray()
    }

    override fun handleElementRename(newElementName: String): PsiElement =
        super.handleElementRename(newElementName.removeSuffix(".py"))
}

/** `a.b.c` → package, module, class, or a method or nested class of a class (`pkg.Foo.from_config`). */
private fun resolveTarget(foothold: PsiElement, names: List<String>): PsiElement? {
    val facade = PyPsiFacade.getInstance(foothold.project)
    facade.resolveQualifiedName(QualifiedName.fromComponents(names), facade.createResolveContextFromFoothold(foothold))
        .firstOrNull()?.let { return it }
    if (names.size < 2) return null
    val module = when (val parent = resolveTarget(foothold, names.dropLast(1))) {
        is PyClass -> return parent.findMethodByName(names.last(), true, null) ?: parent.findNestedClass(names.last(), true)
        is PyFile -> parent
        is PsiDirectory -> parent.findFile("__init__.py") as? PyFile
        else -> null
    } ?: return null
    return module.multiResolveName(names.last()).firstOrNull()?.element
}

/**
 * `group/option` for `- group: option`, or `option` for a bare `- option`, when [scalar] is the
 * option of an entry in a top-level `defaults:` list; null otherwise.
 */
internal fun defaultsPath(scalar: YAMLScalar): String? {
    val option = scalar.textValue.trim()
    if (option.isEmpty() || option == "null" || option == "_self_" || "\${" in option) return null
    val parent = scalar.parent
    val (item, group) = when {
        parent is YAMLSequenceItem -> parent to null
        parent is YAMLKeyValue && parent.value == scalar -> {
            val mapping = parent.parent as? YAMLMapping ?: return null
            if (mapping.keyValues.size != 1) return null
            (mapping.parent as? YAMLSequenceItem ?: return null) to groupOf(parent.keyText)
        }
        else -> return null
    }
    val defaults = item.parent?.parent as? YAMLKeyValue ?: return null
    if (defaults.keyText != "defaults" || defaults.parent?.parent !is YAMLDocument) return null
    return if (group == null) option else "$group/$option"
}

/** `override /server/db@backup` → `server/db`: drops the keywords, the package and the leading `/`. */
internal fun groupOf(key: String): String =
    key.split(Regex("\\s+")).last { it != "override" && it != "optional" }
        .substringBefore('@').removePrefix("/")

/**
 * Resolves to the first `<dir>/<path>.yaml` going up from the current file's folder. Hydra reads a
 * relative group from the config's own folder and an absolute one (`/model`) from the config root,
 * and both are on that walk, so no config root needs setting.
 * ponytail: the first match up the tree wins; a project nesting two config roots could pick the outer one.
 */
private class HydraConfigReference(scalar: YAMLScalar, private val path: String) :
    PsiReferenceBase<YAMLScalar>(
        scalar,
        ElementManipulators.getManipulator(scalar)?.getRangeInElement(scalar) ?: TextRange(0, scalar.textLength),
        true, // soft: an `optional` entry may name a file that is not there
    ) {

    override fun resolve(): PsiElement? {
        var dir = element.containingFile.originalFile.virtualFile?.parent
        while (dir != null) {
            dir.findFileByRelativePath("$path.yaml")?.let { return element.manager.findFile(it) }
            dir = dir.parent
        }
        return null
    }

    /** Ctrl+Space: the `*.yaml` options of the nearest `<group>` folder, or of this file's folder for a bare entry. */
    override fun getVariants(): Array<Any> {
        val group = path.substringBeforeLast('/', "")
        val self = element.containingFile.originalFile.virtualFile
        var dir = self?.parent
        while (dir != null) {
            val groupDir = if (group.isEmpty()) dir else dir.findFileByRelativePath(group)
            if (groupDir != null && groupDir.isDirectory) {
                return groupDir.children.filter { it != self && (it.extension == "yaml" || it.extension == "yml") }
                    .map { it.nameWithoutExtension }.toTypedArray()
            }
            dir = dir.parent
        }
        return emptyArray()
    }

    // Renaming model/base.yaml to model/big.yaml writes `big`, not `big.yaml`.
    override fun handleElementRename(newElementName: String): PsiElement =
        super.handleElementRename(newElementName.removeSuffix(".yaml"))
}

/** `lr:` beside a `_target_:` refers to that parameter of the target's `__init__`. Only PSI checks here. */
private object KwargProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val keyValue = element as? YAMLKeyValue ?: return PsiReference.EMPTY_ARRAY
        val key = keyValue.key ?: return PsiReference.EMPTY_ARRAY
        if (keyValue.keyText.startsWith("_")) return PsiReference.EMPTY_ARRAY
        val mapping = keyValue.parent as? YAMLMapping ?: return PsiReference.EMPTY_ARRAY
        if (isDefaultsEntry(mapping)) return groupReference(keyValue, key.textRangeInParent)
        if (targetNames(mapping) == null) return PsiReference.EMPTY_ARRAY
        return arrayOf(HydraKwargReference(keyValue, key.textRangeInParent))
    }
}

/** `- model: base` / `- override /server/db@pkg: x`: [mapping] is the lone pair of a top-level `defaults:` list item. */
private fun isDefaultsEntry(mapping: YAMLMapping): Boolean {
    val defaults = mapping.parent?.takeIf { mapping.keyValues.size == 1 }?.parent?.parent as? YAMLKeyValue ?: return false
    return mapping.parent is YAMLSequenceItem && defaults.keyText == "defaults" && defaults.parent?.parent is YAMLDocument
}

private fun groupReference(keyValue: YAMLKeyValue, keyRange: TextRange): Array<PsiReference> {
    val group = groupOf(keyValue.keyText)
    val at = keyRange.startOffset + keyValue.keyText.indexOf(group)
    return arrayOf(HydraGroupReference(keyValue, TextRange.from(at, group.length), group))
}

/** The group of a `defaults:` entry → its folder, so renaming the folder rewrites the entry. Runs on click or rename only. */
private class HydraGroupReference(keyValue: YAMLKeyValue, range: TextRange, private val group: String) :
    PsiReferenceBase<YAMLKeyValue>(keyValue, range, true) {

    override fun resolve(): PsiElement? {
        var dir = element.containingFile.originalFile.virtualFile?.parent
        while (dir != null) {
            dir.findFileByRelativePath(group)?.takeIf { it.isDirectory }?.let { return element.manager.findDirectory(it) }
            dir = dir.parent
        }
        return null
    }

    // ponytail: only the last folder of `server/db` is tracked; renaming `server` leaves the entry
    override fun handleElementRename(newElementName: String): PsiElement =
        element.setName(element.keyText.replaceRange(rangeInElement.let { it.startOffset - keyStart()..it.endOffset - keyStart() - 1 },
            group.substringBeforeLast('/', "").let { if (it.isEmpty()) newElementName else "$it/$newElementName" }))

    private fun keyStart() = element.key?.textRangeInParent?.startOffset ?: 0
}

private class HydraKwargReference(keyValue: YAMLKeyValue, range: TextRange) :
    PsiReferenceBase<YAMLKeyValue>(keyValue, range, true) { // soft: `**kwargs` targets accept any key

    override fun resolve(): PsiElement? {
        val mapping = element.parent as? YAMLMapping ?: return null
        return targetCallable(mapping)?.parameterList?.findParameterByName(element.keyText)
    }

    override fun handleElementRename(newElementName: String): PsiElement = element.setName(newElementName)
}

/** The dotted names of [mapping]'s `_target_`, or null when it has none. */
internal fun targetNames(mapping: YAMLMapping): List<String>? {
    val value = (mapping.getKeyValueByKey("_target_")?.value as? YAMLScalar)?.textValue?.trim() ?: return null
    return value.split('.').takeIf { names -> names.none { it.isBlank() || "\${" in it } }
}

/** The function a `_target_` calls: the class's `__init__`, or the function or method itself. */
internal fun targetCallable(mapping: YAMLMapping): PyFunction? {
    val names = targetNames(mapping) ?: return null
    return when (val target = resolveTarget(mapping, names)) {
        is PyClass -> target.findInitOrNew(true, null) // null context: TypeEvalContext.Companion is missing before 2026.1
        is PyFunction -> target
        else -> null
    }
}

/**
 * Completion while typing or on Ctrl+Space:
 * - a group key on a `defaults:` line (`- override /mo` → `model: `),
 * - a new key in a `_target_` block: the parameters not set yet, then Hydra's own `_partial_` & co,
 * - the value of such a key: the parameter's `Literal` values, `true`/`false`, `null`, and its default.
 * ponytail: the `_target_` cases resolve Python on each keystroke there (cached by PyCharm); gate on
 * `parameters.invocationCount == 0` to go back to Ctrl+Space only.
 */
class HydraKwargCompletionContributor : com.intellij.codeInsight.completion.CompletionContributor() {
    override fun beforeCompletion(context: com.intellij.codeInsight.completion.CompletionInitializationContext) {
        // the default dummy ends in a space, which breaks the `${...}` match and `getVariants` above
        context.dummyIdentifier = com.intellij.codeInsight.completion.CompletionUtil.DUMMY_IDENTIFIER_TRIMMED
    }

    override fun fillCompletionVariants(
        parameters: com.intellij.codeInsight.completion.CompletionParameters,
        result: com.intellij.codeInsight.completion.CompletionResultSet,
    ) {
        val position = parameters.position
        val document = parameters.editor.document
        val lineStart = document.getLineStartOffset(document.getLineNumber(parameters.offset))
        val typed = document.charsSequence.subSequence(lineStart, parameters.offset)
        DEFAULTS_PACKAGE.matchEntire(typed)?.let { match ->
            if (inDefaults(position)) result.withPrefixMatcher(match.groupValues[1]).run { listOf("_global_", "_group_").forEach { addElement(LookupElementBuilder.create(it)) } }
            return
        }
        DEFAULTS_KEY.matchEntire(typed)?.let { match ->
            if (!inDefaults(position)) return
            val file = parameters.originalFile.virtualFile ?: return
            val groups = result.withPrefixMatcher(match.groupValues[1])
            configGroups(file, position).forEach { groups.addElement(LookupElementBuilder.create("$it: ").withPresentableText(it)) }
            if (typed.matches(Regex("""\s*-\s*\w*"""))) {
                listOf("override ", "optional ", "_self_").forEach { groups.addElement(LookupElementBuilder.create(it)) }
            }
            return
        }
        if (typed.matches(Regex("""\s*\w*"""))) return completeKeys(position, result) // a key at the start of its line
        val keyValue = position.parent?.parent as? YAMLKeyValue ?: return
        if (keyValue.value != position.parent) return
        val mapping = keyValue.parent as? YAMLMapping ?: return
        val param = targetCallable(mapping)?.parameterList?.findParameterByName(keyValue.keyText) as? PyNamedParameter ?: return
        (annotationValues(param.annotationValue.orEmpty()) + listOfNotNull(param.defaultValueText?.let(::yamlValue)))
            .distinct().forEach { result.addElement(LookupElementBuilder.create(it)) }
    }

    private fun completeKeys(position: PsiElement, result: com.intellij.codeInsight.completion.CompletionResultSet) {
        val mapping = generateSequence(position) { it.parent }.filterIsInstance<YAMLMapping>()
            .firstOrNull { targetNames(it) != null } ?: return
        val taken = mapping.keyValues.map { it.keyText }.toSet()
        val params = targetCallable(mapping)?.parameterList?.parameters.orEmpty().filterIsInstance<PyNamedParameter>()
            .filter { !it.isSelf && !it.isPositionalContainer && !it.isKeywordContainer }.mapNotNull { it.name }
        (params + listOf("_partial_", "_recursive_", "_convert_")).filter { it !in taken }.forEach {
            result.addElement(LookupElementBuilder.create("$it: ").withPresentableText(it))
        }
    }
}

/** `  - override /mo` on a `defaults:` line: group 1 is the group typed so far. */
private val DEFAULTS_KEY = Regex("""\s*-\s*(?:(?:override|optional)\s+)*/?([\w/]*)""")

/** `  - data@` on a `defaults:` line: group 1 is the package typed so far. */
private val DEFAULTS_PACKAGE = Regex("""\s*-\s*(?:(?:override|optional)\s+)*/?[\w/]+@(\w*)""")

private fun inDefaults(position: PsiElement): Boolean = generateSequence(position) { it.parent }.filterIsInstance<YAMLKeyValue>()
    .any { it.keyText == "defaults" && it.parent?.parent is YAMLDocument }

/**
 * The folders holding YAML at the nearest level above [file] that has any: the config groups.
 * Nested groups come back as `model/encoder`, two levels deep at most.
 * ponytail: a file in a leaf group (`experiment/x.yaml`) gets the root groups, not its own siblings.
 */
private fun configGroups(file: VirtualFile, foothold: PsiElement): List<String> {
    val index = ProjectFileIndex.getInstance(foothold.project)
    return generateSequence(file.parent) { it.parent }.takeWhile { index.isInContent(it) }
        .map { dir -> yamlGroups(dir, "", 2) }
        .firstOrNull { it.isNotEmpty() }.orEmpty()
}

/**
 * The YAML values an annotation allows: `Literal['a', 'b']` → a, b; `bool` → true, false; `Optional`/`None` → null.
 * ponytail: reads the annotation text, so it works without a Python SDK; type aliases and `Enum` members are skipped.
 */
private fun annotationValues(annotation: String): List<String> =
    Regex("""Literal\[([^\]]*)]""").findAll(annotation).flatMap { it.groupValues[1].split(',') }.map { yamlValue(it.trim()) }.toList() +
        (if (Regex("""\bbool\b""").containsMatchIn(annotation)) listOf("true", "false") else emptyList()) +
        (if (Regex("""\bOptional\[|\bNone\b""").containsMatchIn(annotation)) listOf("null") else emptyList())

/** A Python literal as YAML: `None` → `null`, `True` → `true`, `'adam'` → `adam`. */
private fun yamlValue(python: String): String = when (python) {
    "None" -> "null"
    "True" -> "true"
    "False" -> "false"
    else -> python.removeSurrounding("\"").removeSurrounding("'")
}

private fun yamlGroups(dir: VirtualFile, prefix: String, depth: Int): List<String> = if (depth == 0) emptyList() else
    dir.children.filter { it.isDirectory }.flatMap { d ->
        (if (d.children.any { it.extension == "yaml" || it.extension == "yml" }) listOf(prefix + d.name) else emptyList()) +
            yamlGroups(d, "$prefix${d.name}/", depth - 1)
    }
