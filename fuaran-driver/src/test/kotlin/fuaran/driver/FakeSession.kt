// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.driver

import fuaran.ui.FuaranException
import fuaran.ui.Json
import fuaran.ui.JsonObject
import fuaran.ui.JsonString
import fuaran.ui.TreeSession
import fuaran.ui.encode

/**
 * An in-memory [TreeSession] for the driver tests — no native Rust core, no JNI. It holds the current
 * canonical node JSON and interprets a **tiny fixture op protocol** (the driver itself is op-agnostic:
 * it only forwards raw op JSON to [applyOp] and re-reads [treeJson], so the semantics of the op are
 * the session's concern — here, the fake's):
 *
 *  - `{"cmd":"replace","node":<nodeJson>}` — adopt a new tree.
 *  - `{"cmd":"reject","code":"...","path":"..."}` — reject, throwing a typed [FuaranException]
 *    (the fake stand-in for the Rust validator rejecting an op).
 *
 * The live Rust session is exercised separately (the native round-trip leg); this fake exists so the
 * *driver loop* — fetch, apply, re-project, survive-a-reject, post-back — is provable with zero native
 * surface, always green on any box.
 */
class FakeSession(
    initialTreeJson: String,
    /**
     * The tree this fake hands back from [projectResolved] — the stand-in for the Rust core's
     * resolved projection, in which every scalar `Binding.Transform` has been folded to the value
     * it evaluates to.
     *
     * A SEPARATE seed on purpose, and that is what makes the driver's choice of channel observable
     * at all: a fake whose two reads returned the same bytes would pass whether the driver called
     * `treeJson()` or `projectResolved()` — which is precisely how the driver came to be reading
     * the wrong one for as long as it did. `null` means "this fake has no evaluator and no
     * Transform to resolve", the honest answer for the loop tests and one that is now stated
     * rather than inherited from an interface default.
     */
    private val resolvedTreeJson: String? = null,
) : TreeSession {
    private var current: String = initialTreeJson
    val stateWrites: MutableList<Pair<String, String>> = mutableListOf()
    var closed = false
        private set

    override fun treeJson(): String = current

    override fun projectResolved(): String = resolvedTreeJson ?: current

    override fun applyOp(opJson: String) {
        val op = Json.parse(opJson) as? JsonObject ?: throw FuaranException("INVALID_JSON", null, null, "op not an object")
        when ((op["cmd"] as? JsonString)?.value) {
            "replace" -> {
                val node = op["node"] ?: throw FuaranException("MISSING_FIELD", "node", null, "replace op missing node")
                current = node.encode()
            }
            // The fixture's stand-in for the core's `SetState`: the slot's value becomes the tree's
            // visible text, so an applied `SetState` is observable in the PROJECTION rather than only
            // in a list the fake keeps. That is what makes the reply-channel test prove the thing it
            // claims — that an event's consequences reach the screen — instead of proving only that a
            // string was handed to a session.
            "setState" -> {
                val key = (op["key"] as? JsonString)?.value
                    ?: throw FuaranException("MISSING_FIELD", "key", null, "setState op missing key")
                val value = (op["value"] as? JsonString)?.value.orEmpty()
                stateWrites.add(key to value)
                current =
                    """{"id":"root","kind":{"${'$'}type":"Markdown","text":{"${'$'}type":"Literal","text":"$value"}}}"""
            }
            "reject" -> {
                val code = (op["code"] as? JsonString)?.value ?: "VALIDATION_REJECT"
                val path = (op["path"] as? JsonString)?.value
                throw FuaranException(code, path, "validation", "fixture reject")
            }
            else -> throw FuaranException("UNKNOWN_OP", null, null, "unrecognised fixture op")
        }
    }

    override fun setState(key: String, valueJson: String) {
        stateWrites.add(key to valueJson)
    }

    override fun setFilter(key: String, valueJson: String) {
        stateWrites.add("filter:$key" to valueJson)
    }

    override fun setQuery(key: String, valueJson: String) {
        stateWrites.add("query:$key" to valueJson)
    }

    override fun close() {
        closed = true
    }
}
