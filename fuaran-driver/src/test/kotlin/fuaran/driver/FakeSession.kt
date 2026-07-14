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
class FakeSession(initialTreeJson: String) : TreeSession {
    private var current: String = initialTreeJson
    val stateWrites: MutableList<Pair<String, String>> = mutableListOf()
    var closed = false
        private set

    override fun treeJson(): String = current

    override fun applyOp(opJson: String) {
        val op = Json.parse(opJson) as? JsonObject ?: throw FuaranException("INVALID_JSON", null, null, "op not an object")
        when ((op["cmd"] as? JsonString)?.value) {
            "replace" -> {
                val node = op["node"] ?: throw FuaranException("MISSING_FIELD", "node", null, "replace op missing node")
                current = node.encode()
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
