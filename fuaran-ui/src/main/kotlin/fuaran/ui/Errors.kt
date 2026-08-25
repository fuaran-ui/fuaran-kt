// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

/**
 * A structured, recoverable decode failure — the Kotlin surface's echo of the wire
 * spec's `DecodeError` envelope (WIRE_FORMAT.md 6). The render projection is
 * decode-only, so these are raised rather than returned; the code + `$`-rooted path
 * mirror the canonical codes so a caller can reason about a malformed tree the
 * same way the codec hosts do.
 *
 * Note the deliberate posture: an unrecognised discriminator is a typed defect
 * ([UNKNOWN_DU_CASE] / [WRONG_NODE_KIND]), never a silent fallback arm — a new wire
 * kind surfaces loudly here until its sealed case + decoder arm land.
 */
class FuaranDecodeException(
    val code: String,
    val path: String,
    val detail: String,
) : Exception("$code at $path: $detail") {
    companion object {
        const val INVALID_JSON = "INVALID_JSON"
        const val MISSING_FIELD = "MISSING_FIELD"
        const val WRONG_TYPE = "WRONG_TYPE"
        const val UNKNOWN_DU_CASE = "UNKNOWN_DU_CASE"
        const val WRONG_NODE_KIND = "WRONG_NODE_KIND"
        const val EMPTY_NODE_ID = "EMPTY_NODE_ID"

        /**
         * A [WireLimits] resource bound is breached — node depth, JSON depth, string
         * length, array length, or total node count. The input is well-formed JSON; it
         * is refused for being structurally unbounded, which is exactly why this is not
         * [INVALID_JSON]: calling a well-formed-but-too-deep document malformed sends an
         * author to repair the wrong thing. [detail] names the limit and the observed
         * shape so they know which bound to come back under.
         */
        const val LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    }
}
