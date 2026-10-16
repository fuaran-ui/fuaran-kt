// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui.idl

import fuaran.ui.Json
import fuaran.ui.JsonArray
import fuaran.ui.JsonObject
import fuaran.ui.JsonString
import fuaran.ui.JsonValue

/**
 * The corpus's `idl.json`, read into the handful of shapes the model generator needs (Phase 2299).
 *
 * The IDL is the language-neutral data rendering of the wire vocabulary: kinds with their
 * category, records, tagged unions, bare-string enums and the node envelope's trait fields, each
 * field carrying a type and an optionality class. This reader is deliberately strict — an IDL
 * shape it does not recognise is an error naming where it was met, never a silently skipped field,
 * because a generator that quietly drops what it cannot read produces a model that LOOKS complete.
 *
 * `ops` and `harden` are not read: a render projection decodes no op and hardens no tree.
 */
sealed interface IdlType

/** `str` / `int` / `float` / `bool` / `json` / `node` — the scalar and opaque positions. */
data class IdlPrim(val name: String) : IdlType

data class IdlList(val of: IdlType) : IdlType

/** A JSON object keyed by string; only the value type is declared. */
data class IdlMap(val values: IdlType) : IdlType

/** A reference to a named enum, record or union. Union type ARGUMENTS are read and not kept. */
data class IdlRef(val sort: String, val name: String) : IdlType

/** A union's type parameter (`Binding<T>`'s `T`). */
data object IdlVar : IdlType

/** A host-declared slot whose WIRE form is [wire]; the host representation is the host's own. */
data class IdlHosted(val wire: IdlType) : IdlType

/** A closure slot: on the wire it is at most the `"<closure>"` sentinel. */
data object IdlFn : IdlType

sealed interface IdlOptionality

data object Required : IdlOptionality

data object Optional : IdlOptionality

/** Present on the wire only when it differs from [default] (the IDL's own value encoding). */
data class OmitDefault(val default: JsonObject) : IdlOptionality

/** Carried by a host's own surface only; nothing observable on the wire. */
data object HostOnly : IdlOptionality

data class IdlField(val name: String, val type: IdlType, val optionality: IdlOptionality)

data class IdlKind(val tag: String, val category: String, val fields: List<IdlField>)

data class IdlRecord(val name: String, val fields: List<IdlField>)

data class IdlCase(val tag: String, val fields: List<IdlField>)

data class IdlUnion(val name: String, val cases: List<IdlCase>)

/** [hostCases] is present when the wire tokens are not valid host case names (`asc`, `email`). */
data class IdlEnum(val name: String, val cases: List<String>, val hostCases: List<String>?)

data class Idl(
    val kinds: List<IdlKind>,
    val records: List<IdlRecord>,
    val unions: List<IdlUnion>,
    val enums: List<IdlEnum>,
    val nodeFields: List<IdlField>,
)

class IdlShapeException(message: String) : Exception(message)

private fun JsonValue.obj(at: String): JsonObject = this as? JsonObject ?: throw IdlShapeException("$at: expected an object")

private fun JsonObject.arr(key: String, at: String): List<JsonValue> =
    (this[key] as? JsonArray)?.items ?: throw IdlShapeException("$at.$key: expected an array")

private fun JsonObject.str(key: String, at: String): String =
    (this[key] as? JsonString)?.value ?: throw IdlShapeException("$at.$key: expected a string")

private fun JsonObject.tag(at: String): String = str("\$type", at)

private fun readType(v: JsonValue, at: String): IdlType {
    // `hosted.wire` spells a scalar wire form as a bare string ("json") and a structured one as a type.
    if (v is JsonString) return IdlPrim(v.value)
    val o = v.obj(at)
    return when (val t = o.tag(at)) {
        "str", "int", "float", "bool", "json", "node" -> IdlPrim(t)
        "list" -> IdlList(readType(o["of"] ?: throw IdlShapeException("$at.of: absent"), "$at.of"))
        "map" -> IdlMap(readType(o["values"] ?: throw IdlShapeException("$at.values: absent"), "$at.values"))
        "enum", "record", "union" -> IdlRef(t, o.str("name", at))
        "var" -> IdlVar
        "hosted" -> IdlHosted(readType(o["wire"] ?: throw IdlShapeException("$at.wire: absent"), "$at.wire"))
        "fn" -> IdlFn
        else -> throw IdlShapeException("$at: unrecognised IDL type '$t'")
    }
}

private fun readOptionality(v: JsonValue, at: String): IdlOptionality {
    val o = v.obj(at)
    return when (val t = o.tag(at)) {
        "required" -> Required
        "optional" -> Optional
        "omitDefault" -> OmitDefault(o["default"]?.obj("$at.default") ?: throw IdlShapeException("$at.default: absent"))
        "hostOnly" -> HostOnly
        else -> throw IdlShapeException("$at: unrecognised optionality class '$t'")
    }
}

private fun readFields(o: JsonObject, at: String): List<IdlField> =
    o.arr("fields", at).mapIndexed { i, f ->
        val fo = f.obj("$at.fields[$i]")
        val name = fo.str("name", "$at.fields[$i]")
        IdlField(
            name,
            readType(fo["type"] ?: throw IdlShapeException("$at.$name.type: absent"), "$at.$name.type"),
            readOptionality(fo["optionality"] ?: throw IdlShapeException("$at.$name.optionality: absent"), "$at.$name"),
        )
    }

fun readIdl(text: String): Idl {
    val root = Json.parse(text).obj("$")
    val kinds =
        root.arr("kinds", "$").mapIndexed { i, k ->
            val o = k.obj("$.kinds[$i]")
            val tag = o.str("tag", "$.kinds[$i]")
            IdlKind(tag, o.str("category", "$.kinds[$i]"), readFields(o, tag))
        }
    val records =
        root.arr("records", "$").mapIndexed { i, r ->
            val o = r.obj("$.records[$i]")
            val name = o.str("name", "$.records[$i]")
            IdlRecord(name, readFields(o, name))
        }
    val unions =
        root.arr("unions", "$").mapIndexed { i, u ->
            val o = u.obj("$.unions[$i]")
            val name = o.str("name", "$.unions[$i]")
            IdlUnion(
                name,
                o.arr("cases", name).mapIndexed { j, c ->
                    val co = c.obj("$name.cases[$j]")
                    val tag = co.str("tag", "$name.cases[$j]")
                    IdlCase(tag, readFields(co, "$name.$tag"))
                },
            )
        }
    val enums =
        root.arr("enums", "$").mapIndexed { i, e ->
            val o = e.obj("$.enums[$i]")
            val name = o.str("name", "$.enums[$i]")
            fun strings(key: String) =
                o.arr(key, name).map { (it as? JsonString)?.value ?: throw IdlShapeException("$name.$key: expected strings") }
            IdlEnum(name, strings("cases"), if (o["hostCases"] != null) strings("hostCases") else null)
        }
    val nodeFields = readFields(JsonObject(mapOf("fields" to (root["nodeFields"] ?: JsonArray(emptyList())))), "Node")
    return Idl(kinds, records, unions, enums, nodeFields)
}
