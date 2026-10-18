// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui.idl

/**
 * The generator's carry rule, checked by PERTURBING the IDL (Phase 2331) — so the NEXT field the
 * language gains is carried by this surface or fails generation loudly, never dropped quietly.
 *
 *  1. An optional `str` field added to a kind whose order is the rule's (IDL order) is generated as a
 *     nullable property, `= null`, appended LAST in the constructor.
 *  2. The same field added to a kind whose constructor order the dialect pins ([FieldOrder]) makes
 *     generation REFUSE, naming the field — a pinned order is a permutation of what is modelled, and
 *     the field is never dropped to keep it one.
 *  3. A case added to a union appears in that union's generated case-tag set, so the decoder's
 *     `else`-free dispatch over it stops compiling until the arm is written.
 *
 * Run by the drift check ([main] in `IdlModelCheck.kt`) before it compares anything, against the same
 * `idl.json`. Returns the failures; empty is a pass.
 */
fun generatorRuleChecks(idl: Idl): List<String> {
    val failures = ArrayList<String>()
    val probe = IdlField("probeField", IdlPrim("str"), Optional)
    fun withField(tag: String) = idl.copy(kinds = idl.kinds.map { if (it.tag == tag) it.copy(fields = it.fields + probe) else it })
    fun generate(i: Idl) = generateModel(i, "probe", DIALECT, MODEL_DOCS)

    val pinned = DIALECT.filterIsInstance<FieldOrder>().map { it.owner }.toSet()
    val renamed = DIALECT.filterIsInstance<TypeName>().map { it.idl }.toSet()

    // 1 — rule-ordered kind: carried, nullable, last.
    val free = idl.kinds.firstOrNull { it.tag !in pinned && it.tag !in renamed && it.fields.isNotEmpty() }
    if (free == null) {
        failures += "no kind with the rule's own order to perturb"
    } else {
        val model = generate(withField(free.tag)).getValue(GENERATED_FILES[0])
        val start = model.indexOf("data class ${free.tag}(")
        val end = if (start < 0) -1 else model.indexOf(") : NodeKind", start)
        val decl = if (end < 0) "" else model.substring(start, end)
        val last = Regex("val [A-Za-z`]+: [^,\n]+").findAll(decl).lastOrNull()?.value
        if (last != "val probeField: String? = null") {
            failures += "an optional str field added to ${free.tag} is not carried last as `val probeField: String? = null` " +
                "(last property: ${last ?: "<none>"})"
        }
    }

    // 2 — pinned-order kind: refused, naming the field.
    val held = idl.kinds.firstOrNull { it.tag in pinned }
    if (held == null) {
        failures += "no kind with a pinned FieldOrder to perturb"
    } else {
        try {
            generate(withField(held.tag))
            failures += "an optional str field added to ${held.tag} (a pinned FieldOrder) generated without refusal"
        } catch (e: DialectRefusal) {
            if ("probeField" !in (e.message ?: "")) failures += "the refusal for ${held.tag}.probeField does not name the field: ${e.message}"
        }
    }

    // 3 — a union case reaches the generated tag set.
    val union = idl.unions.first()
    val withCase = idl.copy(unions = idl.unions.map { if (it === union) it.copy(cases = it.cases + IdlCase("ProbeCase", emptyList())) else it })
    val tags = generate(withCase).getValue(GENERATED_FILES[3])
    if ("    ProbeCase(\"ProbeCase\"),\n" !in tags) failures += "a case added to ${union.name} is absent from its generated tag set"

    return failures
}
