// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui.idl

/**
 * The sealed tree model's generator (Phase 2299): the corpus's `idl.json` in, this surface's
 * public model out — the node envelope, the sealed `NodeKind` hierarchy with one class per kind,
 * every record and union the dialect does not keep by hand, the bare-string enums, the
 * `NodeCategory` spine, the kind-tag set the decoder dispatches on, and one case-tag set per IDL
 * union (Phase 2331) that the decoder's union dispatches are exhaustive over.
 *
 * It is THIS repository's generator, written in Kotlin and reading a language-neutral data file. It
 * shares no code with any other surface and transpiles nothing: the IDL says what the vocabulary
 * is, and the rules below say how this surface spells it.
 *
 * ## The rules (uniform — every divergence from them is a [DIALECT] entry with a reason)
 *
 *  * **Names.** A kind, record, union or enum is named as the IDL names it. A union case class is
 *    `{Case}{Union}` (`Static` of `Binding` is `StaticBinding`). A field is its IDL name; a Kotlin
 *    hard keyword is escaped with backticks.
 *  * **Types.** `str` String, `int` Int, `float` Double, `bool` Boolean, `json` JsonValue, `node`
 *    Node, list `List<T>`, map `Map<String, T>`, an enum / record / union its own type. A union's
 *    type ARGUMENTS are erased (`Binding<str>` is `Binding`) and its type PARAMETER is the raw
 *    `JsonValue`; a `hosted` slot is its declared wire form's mapping.
 *  * **Optionality.** Required: non-null, no default. Optional: nullable, `= null`.
 *    Omit-at-default: non-null, the IDL's declared default as the constructor default. A default
 *    the rules cannot spell is a generation error, never a guess.
 *  * **What is not modelled.** A host-only field, and a closure (`fn`) slot — on the wire it is at
 *    most the `"<closure>"` sentinel, and a render projection cannot call it.
 *  * **Shape.** A kind is a `data class` implementing `NodeKind`; a record a `data class`; a union a
 *    `sealed interface` whose cases are `data class`es, or `data object`s when nothing is modelled.
 *    Order is IDL declaration order, for fields and enum constants alike.
 *  * **Categories.** A kind's category is the IDL's; `category()` and `discriminator()` are
 *    exhaustive `when`s with no `else`.
 *
 * Recursive slots need no rule of their own: a `node` field is `Node` and a self-referential record
 * (`TreeItem.children`) is a list of itself, as the IDL states.
 *
 * Refusals are [DialectRefusal]s naming the subject, raised before anything is written.
 */
class DialectRefusal(message: String) : Exception(message)

/** Where this generator lives — named in the generated headers. */
const val GENERATOR_PATH = "fuaran-ui/src/test/kotlin/fuaran/ui/idl/ModelGenerator.kt"

/** The generated files, repository-relative. */
val GENERATED_FILES =
    listOf(
        "fuaran-ui/src/main/kotlin/fuaran/ui/Model.generated.kt",
        "fuaran-ui/src/main/kotlin/fuaran/ui/Enums.generated.kt",
        "fuaran-ui/src/main/kotlin/fuaran/ui/NodeCategory.generated.kt",
        "fuaran-ui/src/main/kotlin/fuaran/ui/UnionTags.generated.kt",
    )

private const val MAX_LINE = 120

private val KOTLIN_HARD_KEYWORDS =
    setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is",
        "null", "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof",
        "val", "var", "when", "while",
    )

private fun escapeName(name: String): String = if (name in KOTLIN_HARD_KEYWORDS) "`$name`" else name

private data class KParam(val name: String, val type: String, val nullable: Boolean, val default: String?, val doc: String?) {
    fun render(): String = "val $name: $type${if (nullable) "?" else ""}${if (default != null) " = $default" else ""}"
}

private sealed interface Decl {
    val name: String
}

private data class DataDecl(override val name: String, val params: List<KParam>, val parent: String?) : Decl

private data class ObjectDecl(override val name: String, val parent: String?) : Decl

private data class SealedDecl(override val name: String) : Decl

/** [wires] non-null: the constants carry their wire token through `WireEnum`. */
private data class EnumDecl(override val name: String, val constants: List<String>, val wires: List<String>?) : Decl

/** Generate every file of [GENERATED_FILES] from [idl], or throw [DialectRefusal]. */
fun generateModel(idl: Idl, idlSha256: String, dialect: List<DialectEntry>, docs: Map<String, String>): Map<String, String> =
    ModelGenerator(idl, idlSha256, dialect, docs).run()

private class ModelGenerator(
    private val idl: Idl,
    private val sha: String,
    private val dialect: List<DialectEntry>,
    private val docs: Map<String, String>,
) {
    private val used = HashSet<Int>()
    private val usedDocs = HashSet<String>()

    private val enumsByName = idl.enums.associateBy { it.name }
    private val recordsByName = idl.records.associateBy { it.name }
    private val unionsByName = idl.unions.associateBy { it.name }
    private val kindsByTag = idl.kinds.associateBy { it.tag }

    /** Consume the single entry of type [T] satisfying [p], or null. Two entries for one subject is a refusal. */
    private inline fun <reified T : DialectEntry> take(p: (T) -> Boolean): T? {
        val hits = dialect.withIndex().filter { (_, e) -> e is T && p(e) }
        if (hits.size > 1) refuse("two dialect entries for one subject: ${hits.map { it.value }}")
        val hit = hits.singleOrNull() ?: return null
        used.add(hit.index)
        return hit.value as T
    }

    private fun refuse(message: String): Nothing = throw DialectRefusal(message)

    private fun doc(key: String): String? = docs[key]?.also { usedDocs.add(key) }

    // ----------------------------------------------------------------------------------------- //
    // Names
    // ----------------------------------------------------------------------------------------- //

    private fun idlTypeExists(name: String) =
        name in enumsByName || name in recordsByName || name in unionsByName || name in kindsByTag

    private val notModelled: Set<String> =
        dialect.filterIsInstance<TypeNotModelled>().map { e ->
            if (e.idl !in enumsByName && e.idl !in recordsByName && e.idl !in unionsByName) {
                refuse("TypeNotModelled names '${e.idl}', which is not an IDL record, union or enum")
            }
            used.add(dialect.indexOf(e))
            e.idl
        }.toSet()

    private val handWritten: Map<String, HandWritten> =
        dialect.filterIsInstance<HandWritten>().associate { e ->
            if (e.idl !in recordsByName && e.idl !in unionsByName) {
                refuse("HandWritten names '${e.idl}', which is not an IDL record or union")
            }
            used.add(dialect.indexOf(e))
            e.idl to e
        }

    private val typeNames: Map<String, String> =
        dialect.filterIsInstance<TypeName>().associate { e ->
            if (!idlTypeExists(e.idl)) refuse("TypeName names '${e.idl}', which the IDL does not declare")
            if (e.kotlin == e.idl) refuse("TypeName for '${e.idl}' spells the name the rule already gives")
            used.add(dialect.indexOf(e))
            e.idl to e.kotlin
        }

    private fun ktTypeName(idlName: String): String = typeNames[idlName] ?: idlName

    private fun caseClassName(union: IdlUnion, case: IdlCase): String {
        val pattern = caseNamePattern(union)
        val byPattern = pattern.replace("{case}", case.tag)
        val explicit = take<CaseName> { it.union == union.name && it.case == case.tag } ?: return byPattern
        if (explicit.kotlin == byPattern) refuse("CaseName ${union.name}.${case.tag} spells the name the pattern already gives")
        return explicit.kotlin
    }

    private val casePatterns = HashMap<String, String>()

    private fun caseNamePattern(union: IdlUnion): String =
        casePatterns.getOrPut(union.name) {
            val rule = "{case}${ktTypeName(union.name)}"
            val entry = take<CaseNames> { it.union == union.name } ?: return@getOrPut rule
            if (entry.pattern == rule) refuse("CaseNames for '${union.name}' spells the rule's own pattern")
            entry.pattern
        }

    private fun enumConstants(e: IdlEnum): Pair<List<String>, List<String>?> {
        val wireTokens = take<WireTokenConstants> { it.enum == e.name }
        if (wireTokens != null && e.hostCases == null) refuse("WireTokenConstants for '${e.name}', whose cases are already host names")
        val constants = if (e.hostCases != null && wireTokens == null) e.hostCases else e.cases
        val wires = if (e.hostCases != null && wireTokens == null) e.cases else null
        return constants to wires
    }

    private fun enumConstant(e: IdlEnum, case: String): String {
        val i = e.cases.indexOf(case)
        if (i < 0) refuse("enum ${e.name} has no case '$case'")
        return constantsMemo.getOrPut(e.name) { enumConstants(e) }.first[i]
    }

    private val constantsMemo = HashMap<String, Pair<List<String>, List<String>?>>()

    // ----------------------------------------------------------------------------------------- //
    // Types and defaults
    // ----------------------------------------------------------------------------------------- //

    private fun ktType(t: IdlType, at: String): String =
        when (t) {
            is IdlPrim ->
                when (t.name) {
                    "str" -> "String"
                    "int" -> "Int"
                    "float" -> "Double"
                    "bool" -> "Boolean"
                    "json" -> "JsonValue"
                    "node" -> "Node"
                    else -> refuse("$at: no rule maps the IDL type '${t.name}'")
                }
            is IdlList -> "List<${ktType(t.of, at)}>"
            is IdlMap -> "Map<String, ${ktType(t.values, at)}>"
            is IdlRef -> {
                if (t.name in notModelled) {
                    refuse(
                        "$at references ${t.name}, which the dialect does not model — give the field a FieldType entry",
                    )
                }
                ktTypeName(t.name)
            }
            IdlVar -> "JsonValue"
            is IdlHosted -> ktType(t.wire, at)
            IdlFn -> refuse("$at: a closure slot has no Kotlin type")
        }

    private fun renderDefault(t: IdlType, v: fuaran.ui.JsonObject, at: String): String {
        val tag = (v["\$type"] as? fuaran.ui.JsonString)?.value
        fun cannot(): Nothing = refuse("$at: no rule spells the declared default ${v.members} — add an Optionality entry")
        return when {
            t is IdlPrim && t.name == "bool" && tag == "bool" -> ((v["value"] as? fuaran.ui.JsonBool)?.value ?: cannot()).toString()
            t is IdlPrim && t.name == "int" && tag == "int" -> (v["value"] as? fuaran.ui.JsonNumber)?.raw ?: cannot()
            t is IdlPrim && t.name == "float" && tag == "float" ->
                ((v["value"] as? fuaran.ui.JsonNumber)?.raw ?: cannot()).let { if ('.' in it || 'e' in it) it else "$it.0" }
            t is IdlPrim && t.name == "str" && tag == "str" -> "\"" + ((v["value"] as? fuaran.ui.JsonString)?.value ?: cannot()) + "\""
            t is IdlRef && t.sort == "enum" && tag == "enum" -> {
                val e = enumsByName.getValue(t.name)
                "${ktTypeName(e.name)}.${enumConstant(e, (v["case"] as? fuaran.ui.JsonString)?.value ?: cannot())}"
            }
            t is IdlList && tag == "list" ->
                if ((v["items"] as? fuaran.ui.JsonArray)?.items?.isEmpty() == true) "emptyList()" else cannot()
            t is IdlRef && t.sort == "union" && tag == "union" -> {
                val u = unionsByName.getValue(t.name)
                val c = u.cases.firstOrNull { it.tag == (v["tag"] as? fuaran.ui.JsonString)?.value } ?: cannot()
                if (caseParams(u, c).isNotEmpty()) cannot()
                caseClass(u, c)
            }
            else -> cannot()
        }
    }

    // ----------------------------------------------------------------------------------------- //
    // Fields
    // ----------------------------------------------------------------------------------------- //

    private fun params(owner: String, ktOwner: String, fields: List<IdlField>, prefix: List<KParam> = emptyList()): List<KParam> {
        val out = ArrayList(prefix)
        for (f in fields) {
            val at = "$owner.${f.name}"
            val presence = take<PresenceFlag> { it.owner == owner && it.field == f.name }
            val ruleOmits = f.optionality == HostOnly || f.type == IdlFn
            if (presence != null) {
                if (f.type != IdlFn) refuse("PresenceFlag $at: the field is not a closure slot")
                rejectFieldOverrides(owner, f.name)
                out.add(KParam(presence.kotlin, "Boolean", false, "false", doc("$ktOwner.${presence.kotlin}")))
                continue
            }
            if (ruleOmits) continue

            val ruleName = escapeName(f.name)
            val name =
                take<FieldName> { it.owner == owner && it.field == f.name }?.let {
                    if (it.kotlin == ruleName) refuse("FieldName $at spells the rule's own name")
                    it.kotlin
                } ?: ruleName
            val typeEntry = take<FieldType> { it.owner == owner && it.field == f.name }
            val ruleType by lazy { ktType(f.type, at) }
            val type =
                typeEntry?.let {
                    // A type the rules cannot map at all (a reference to an unmodelled type) makes any override non-redundant.
                    val rule = runCatching { ruleType }.getOrNull()
                    if (it.kotlin == rule) refuse("FieldType $at spells the rule's own type")
                    it.kotlin
                } ?: ruleType
            val repr = take<Optionality> { it.owner == owner && it.field == f.name }
            val (nullable, default) =
                if (repr != null) {
                    val rule = runCatching { ruleOptionality(f, at) }.getOrNull()
                    if (rule == (repr.nullable to repr.default)) refuse("Optionality $at states the rule's own representation")
                    repr.nullable to repr.default
                } else {
                    if (typeEntry != null && f.optionality is OmitDefault) {
                        refuse(
                            "$at: a FieldType on an omit-at-default field needs an Optionality entry (its default is " +
                                "spelled in the IDL type)",
                        )
                    }
                    ruleOptionality(f, at)
                }
            out.add(KParam(name, type, nullable, default, doc("$ktOwner.${name.trim('`')}")))
        }
        val order = take<FieldOrder> { it.owner == owner } ?: return out
        val names = out.map { it.name }
        if (order.order == names) refuse("FieldOrder $owner states the rule's own (IDL) order")
        // A field the IDL gained is never dropped to keep a pinned order: the order must place it (append it, so
        // every positional call still binds), or generation fails naming it.
        val unplaced = names.filter { it !in order.order }
        if (unplaced.isNotEmpty()) {
            refuse("FieldOrder $owner does not place the modelled field(s) $unplaced — append them to its order")
        }
        if (order.order.sorted() != names.sorted()) {
            refuse("FieldOrder $owner is not a permutation of the modelled fields $names (got ${order.order})")
        }
        return order.order.map { n -> out.first { it.name == n } }
    }

    private fun rejectFieldOverrides(owner: String, field: String) {
        val stray =
            dialect.filter {
                (it is FieldName && it.owner == owner && it.field == field) ||
                    (it is FieldType && it.owner == owner && it.field == field) ||
                    (it is Optionality && it.owner == owner && it.field == field)
            }
        if (stray.isNotEmpty()) refuse("$owner.$field is not modelled, so these entries cannot apply: $stray")
    }

    private fun ruleOptionality(f: IdlField, at: String): Pair<Boolean, String?> =
        when (val o = f.optionality) {
            Required -> false to null
            Optional -> true to "null"
            is OmitDefault -> false to renderDefault(f.type, o.default, at)
            HostOnly -> refuse("$at: unreachable")
        }

    // ----------------------------------------------------------------------------------------- //
    // Declarations
    // ----------------------------------------------------------------------------------------- //

    private val caseParamsMemo = HashMap<String, List<KParam>>()

    private fun caseClass(u: IdlUnion, c: IdlCase): String = caseClassMemo.getOrPut("${u.name}.${c.tag}") { caseClassName(u, c) }

    private val caseClassMemo = HashMap<String, String>()

    private fun caseParams(u: IdlUnion, c: IdlCase): List<KParam> =
        caseParamsMemo.getOrPut("${u.name}.${c.tag}") { params("${u.name}.${c.tag}", caseClass(u, c), c.fields) }

    private fun kindName(k: IdlKind) = ktTypeName(k.tag)

    fun run(): Map<String, String> {
        // Hand-written and not-modelled types are named before anything references them.
        val model = StringBuilder()
        val enums = StringBuilder()
        val spine = StringBuilder()

        // The envelope: `id` and `kind` are the node's identity, ahead of the IDL's trait fields.
        val envelope =
            params(
                "Node",
                "Node",
                idl.nodeFields,
                prefix =
                    listOf(
                        KParam("id", "String", false, null, doc("Node.id")),
                        KParam("kind", "NodeKind", false, null, doc("Node.kind")),
                    ),
            )
        val decls = ArrayList<Pair<Decl, String?>>()
        decls.add(DataDecl("Node", envelope, null) to doc("Node"))
        decls.add(SealedDecl("NodeKind") to doc("NodeKind"))
        for (k in idl.kinds) {
            val name = kindName(k)
            val ps = params(k.tag, name, k.fields)
            decls.add((if (ps.isEmpty()) ObjectDecl(name, "NodeKind") else DataDecl(name, ps, "NodeKind")) to doc(name))
        }
        for (r in idl.records) {
            if (r.name in notModelled || r.name in handWritten) continue
            val name = ktTypeName(r.name)
            decls.add(DataDecl(name, params(r.name, name, r.fields), null) to doc(name))
        }
        for (u in idl.unions) {
            if (u.name in notModelled || u.name in handWritten) continue
            val name = ktTypeName(u.name)
            decls.add(SealedDecl(name) to doc(name))
            for (c in u.cases) {
                val cn = caseClass(u, c)
                val ps = caseParams(u, c)
                decls.add((if (ps.isEmpty()) ObjectDecl(cn, name) else DataDecl(cn, ps, name)) to doc(cn))
            }
        }
        val enumDecls = ArrayList<Pair<Decl, String?>>()
        // An enum carrying dialect extras: (IDL name, Kotlin name, the constants the IDL declares).
        val idlCaseSets = ArrayList<Triple<String, String, List<String>>>()
        for (e in idl.enums) {
            if (e.name in notModelled) continue
            val name = ktTypeName(e.name)
            val (constants0, wires0) = constantsMemo.getOrPut(e.name) { enumConstants(e) }
            val constants = ArrayList(constants0)
            val wires = wires0?.let { ArrayList(it) }
            for (x in dialect.withIndex().filter { (_, it) -> it is ExtraEnumCase && it.enum == e.name }) {
                val extra = x.value as ExtraEnumCase
                if (extra.constant in constants) refuse("ExtraEnumCase ${e.name}.${extra.constant}: the IDL already has that case")
                used.add(x.index)
                constants.add(extra.constant)
                wires?.add(extra.constant)
            }
            val order = take<EnumOrder> { it.enum == e.name }
            val ordered =
                if (order == null) {
                    constants
                } else {
                    if (order.order == constants) refuse("EnumOrder ${e.name} states the rule's own (IDL) order")
                    if (order.order.sorted() != constants.sorted()) refuse("EnumOrder ${e.name} is not a permutation of $constants")
                    order.order
                }
            val orderedWires = wires?.let { w -> ordered.map { c -> w[constants.indexOf(c)] } }
            enumDecls.add(EnumDecl(name, ordered, orderedWires) to doc(name))
            if (constants.size > constants0.size) idlCaseSets.add(Triple(e.name, name, constants0))
        }

        // Orphans: every enum-scoped entry must name an IDL enum the model carries.
        for ((i, e) in dialect.withIndex()) {
            val enumName =
                when (e) {
                    is EnumOrder -> e.enum
                    is ExtraEnumCase -> e.enum
                    is WireTokenConstants -> e.enum
                    else -> null
                } ?: continue
            if (i !in used) {
                refuse("${e::class.simpleName} names enum '$enumName', which the IDL does not declare or the model does not carry")
            }
        }

        // Categories.
        val categoryNames =
            idl.kinds.map { it.category }.distinct().associateWith { c ->
                take<CategoryName> { it.idl == c }?.let {
                    if (it.kotlin == c) refuse("CategoryName '$c' spells the IDL's own name")
                    it.kotlin
                } ?: c
            }
        val categoryOf =
            idl.kinds.associate { k ->
                val rule = categoryNames.getValue(k.category)
                k.tag to (
                    take<CategoryOf> { it.kind == k.tag }?.let {
                        if (it.category == rule) refuse("CategoryOf ${k.tag} states the IDL's own category")
                        it.category
                    } ?: rule
                    )
            }
        val categoryRule = idl.kinds.map { categoryNames.getValue(it.category) }.distinct()
        val categoryOrder =
            take<CategoryOrder> { true }?.let {
                if (it.order == categoryRule) refuse("CategoryOrder states the rule's own order")
                if (it.order.sorted() != categoryRule.sorted()) refuse("CategoryOrder is not a permutation of $categoryRule")
                it.order
            } ?: categoryRule
        for ((tag, c) in categoryOf) if (c !in categoryOrder) refuse("kind $tag is categorised '$c', which is not a category")

        // Hand-written pins: every IDL member mapped, every key an IDL member.
        val pins = StringBuilder()
        for ((name, hw) in handWritten) {
            val members =
                recordsByName[name]?.fields?.map { it.name } ?: unionsByName.getValue(name).cases.map { it.tag }
            val missing = members.filter { it !in hw.members }
            if (missing.isNotEmpty()) refuse("HandWritten $name does not map IDL member(s) $missing — the hand type has drifted")
            val stray = hw.members.keys.filter { it !in members }
            if (stray.isNotEmpty()) refuse("HandWritten $name maps $stray, which the IDL does not have")
            val kt = ktTypeName(name)
            if (name in recordsByName) {
                val props = hw.members.values.distinct()
                pins.append("\n/** `$kt` is hand-written; the IDL fields ${members.joinToString()} land in ${props.joinToString()}. */\n")
                val reads = props.joinToString { "v.$it" }
                pins.append("@Suppress(\"unused\")\nprivate fun idlPin$kt(v: $kt): List<Any?> = listOf($reads)\n")
            } else {
                pins.append("\n/** `$kt` is hand-written; every IDL case maps onto one Kotlin case. */\n")
                pins.append("@Suppress(\"unused\")\nprivate fun idlPin$kt(v: $kt): Unit =\n    when (v) {\n")
                for (cls in hw.members.values.distinct()) {
                    val from = hw.members.filterValues { it == cls }.keys.joinToString()
                    pins.append("        is $cls -> Unit // $from\n")
                }
                pins.append("    }\n")
            }
        }

        val unusedEntries = dialect.withIndex().filter { it.index !in used }.map { it.value }
        if (unusedEntries.isNotEmpty()) {
            refuse("dialect entries name nothing the IDL has (or nothing the rules reach):\n  " + unusedEntries.joinToString("\n  "))
        }

        val header = header()
        model.append(header).append("package fuaran.ui\n")
        for ((d, dc) in decls) model.append("\n").append(emit(d, dc))
        enums.append(header).append("package fuaran.ui\n")
        for ((d, dc) in enumDecls) enums.append("\n").append(emit(d, dc))
        for ((idlName, kt, cases) in idlCaseSets) {
            enums.append(
                "\n/**\n * The [$kt] constants the IDL's `$idlName` declares — the only ones the decoder admits.\n" +
                    " * The rest are dialect extras kept for source compatibility; a wire document naming one is refused.\n */\n",
            )
            enums.append("internal val idlCasesOf$kt: Set<$kt> = setOf(${cases.joinToString { "$kt.$it" }})\n")
        }

        spine.append(header).append("@file:JvmName(\"NodeCategoryKt\")\n\npackage fuaran.ui\n\n")
        spine.append(emit(EnumDecl("NodeCategory", categoryOrder, null), doc("NodeCategory")))
        doc("category")?.let { spine.append("\n").append(kdoc(it, "")) } ?: spine.append("\n")
        spine.append("fun NodeKind.category(): NodeCategory =\n    when (this) {\n")
        for (k in idl.kinds) spine.append("        is ${kindName(k)} -> NodeCategory.${categoryOf.getValue(k.tag)}\n")
        spine.append("    }\n")
        doc("discriminator")?.let { spine.append("\n").append(kdoc(it, "")) } ?: spine.append("\n")
        spine.append("fun NodeKind.discriminator(): String =\n    when (this) {\n")
        for (k in idl.kinds) spine.append("        is ${kindName(k)} -> \"${k.tag}\"\n")
        spine.append("    }\n")
        spine.append(
            "\n/**\n * The wire kind tags, one constant per IDL kind row — what the decoder dispatches a node's `kind.\$type` on.\n" +
                " * Its `when` over this set has no `else`, so a kind added to the IDL and regenerated is a compile\n" +
                " * error in the decoder until its arm is written.\n */\n",
        )
        spine.append("internal enum class NodeKindTag(val wire: String) {\n")
        for (k in idl.kinds) spine.append("    ${k.tag}(\"${k.tag}\"),\n")
        spine.append("    ;\n\n    companion object {\n")
        spine.append("        private val BY_WIRE = entries.associateBy { it.wire }\n\n")
        spine.append("        /** The kind a wire tag names, or null when the vocabulary has no such kind. */\n")
        spine.append("        fun ofWire(tag: String): NodeKindTag? = BY_WIRE[tag]\n    }\n}\n")
        spine.append(pins)

        val tags = StringBuilder()
        tags.append(header).append("package fuaran.ui\n")
        for (u in idl.unions) {
            val kt = ktTypeName(u.name)
            val named = if (kt == u.name) "`${u.name}`" else "`${u.name}` (Kotlin `$kt`)"
            tags.append(
                "\n/**\n * The wire case tags of the IDL union $named, in IDL case order — what the decoder\n" +
                    " * dispatches a `\$type` on. Its `when` over this set has no `else`, so a case added to the IDL and\n" +
                    " * regenerated is a compile error in the decoder until its arm is written.\n */\n",
            )
            tags.append("internal enum class ${kt}Tag(override val wire: String) : WireEnum {\n")
            for (c in u.cases) tags.append("    ${c.tag}(\"${c.tag}\"),\n")
            tags.append("}\n")
        }

        val strayDocs = docs.keys.filter { it !in usedDocs }
        if (strayDocs.isNotEmpty()) refuse("model docs name declarations the generator does not emit: $strayDocs")

        return mapOf(
            GENERATED_FILES[0] to model.toString(),
            GENERATED_FILES[1] to enums.toString(),
            GENERATED_FILES[2] to spine.toString(),
            GENERATED_FILES[3] to tags.toString(),
        )
    }

    private fun header(): String =
        "// SPDX-License-Identifier: Apache-2.0\n" +
            "// Copyright Diametrical Ltd.\n" +
            "//\n" +
            "// GENERATED from the wire corpus's idl.json — do not edit.\n" +
            "//   generator: $GENERATOR_PATH\n" +
            "//   idl.json sha256: $sha\n" +
            "// Regenerate with FUARAN_IDL_REGENERATE=1 (see IdlModelCheck.kt); the drift check fails while this is stale.\n"

    // ----------------------------------------------------------------------------------------- //
    // Emission
    // ----------------------------------------------------------------------------------------- //

    private fun emit(d: Decl, docText: String?): String {
        val sb = StringBuilder()
        if (docText != null) sb.append(kdoc(docText, ""))
        when (d) {
            is SealedDecl -> sb.append("sealed interface ${d.name}\n")
            is ObjectDecl -> sb.append("data object ${d.name}${d.parent?.let { " : $it" } ?: ""}\n")
            is DataDecl -> {
                val tail = ")" + (d.parent?.let { " : $it" } ?: "")
                val one = "data class ${d.name}(" + d.params.joinToString(", ") { it.render() } + tail
                if (d.params.none { it.doc != null } && one.length <= MAX_LINE) {
                    sb.append(one).append("\n")
                } else {
                    sb.append("data class ${d.name}(\n")
                    for (p in d.params) {
                        if (p.doc != null) sb.append(kdoc(p.doc, "    "))
                        sb.append("    ").append(p.render()).append(",\n")
                    }
                    sb.append(tail).append("\n")
                }
            }
            is EnumDecl -> {
                val suppress = d.constants.any { it.first().isLowerCase() }
                if (suppress) sb.append("@Suppress(\"EnumEntryName\")\n")
                val head =
                    if (d.wires != null) "enum class ${d.name}(override val wire: String) : WireEnum {" else "enum class ${d.name} {"
                val items = d.constants.mapIndexed { i, c -> if (d.wires != null) "$c(\"${d.wires[i]}\")" else c }
                val one = "$head ${items.joinToString(", ")} }"
                if (one.length <= MAX_LINE) {
                    sb.append(one).append("\n")
                } else {
                    sb.append(head).append("\n")
                    for (it in items) sb.append("    ").append(it).append(",\n")
                    sb.append("}\n")
                }
            }
        }
        return sb.toString()
    }

    private fun kdoc(text: String, indent: String): String {
        val lines = text.trimEnd().split("\n")
        if (lines.size == 1 && indent.length + lines[0].length + 7 <= MAX_LINE) return "$indent/** ${lines[0]} */\n"
        return "$indent/**\n" + lines.joinToString("") { if (it.isBlank()) "$indent *\n" else "$indent * $it\n" } + "$indent */\n"
    }
}
