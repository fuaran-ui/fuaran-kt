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
         * length, array length, total node count, the expression-node count of 21.8, or
         * the skeleton-row ceiling of 21.9. The input is well-formed JSON; it is refused
         * for naming more work than the format carries, which is exactly why this is not
         * [INVALID_JSON]: calling a well-formed-but-too-deep document malformed sends an
         * author to repair the wrong thing. [detail] names the limit and the observed
         * shape so they know which bound to come back under.
         *
         * The last two are bounds on a VALUE rather than on the shape of a walk, and each
         * sits at the slot that reads it — AFTER 7.1 has decided the slot's type, never
         * instead of it. So a `Skeleton` whose `rows` is fractional or outside signed
         * 32-bit stays [WRONG_TYPE]; only a value the slot can hold reaches this code.
         */
        const val LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    }
}

/**
 * The failures a live host can meet while RE-PROJECTING a session's tree, mapped onto the single
 * typed shape a host surfaces — or `null` for a throwable that is not one of them.
 *
 * This exists because there are several of them and only one was ever caught. A host loop naturally
 * expects [FuaranException]: the Rust core rejected the write, the last-good tree stands, show the
 * error. But the core also accepts wire vocabulary this decode-only projection does not model — a
 * newer node kind, a widened enum — and a write that lands one makes the re-projection throw a
 * [FuaranDecodeException] instead. From a reader's point of view the two events are identical (the
 * tree on screen is the last-good one; something is wrong with what arrived after it), so catching
 * one and not the other is not a policy — it is the difference between an error banner and a
 * crashed app, decided by which of two indistinguishable causes happened to occur.
 *
 * Naming the set HERE, once, is what keeps the interaction host and the server-driven driver
 * agreeing about it. The mapping is lossless: a decode failure already carries the code and the
 * `$`-rooted path a [FuaranException] does, and the reader's own lexer failures — which carry no
 * path, a malformed token having no place in the tree — are reported at the document root.
 *
 * **Not in the set, deliberately:** [FuaranSessionClosedException]. A call on a freed handle is a
 * caller lifecycle defect, not a property of the data, and surfacing it as a survivable reject
 * would leave a host showing an error banner over a session that can never work again.
 */
fun asProjectionFailure(t: Throwable): FuaranException? =
    when (t) {
        is FuaranException -> t
        is FuaranDecodeException -> FuaranException(t.code, t.path, "decode", t.detail)
        is JsonSyntaxException ->
            FuaranException(
                FuaranDecodeException.INVALID_JSON,
                "$",
                "decode",
                t.message ?: "the session returned text that is not valid JSON",
            )
        is JsonLimitException ->
            FuaranException(
                FuaranDecodeException.LIMIT_EXCEEDED,
                "$",
                "decode",
                t.message ?: "the session returned a document past a wire limit",
            )
        // The reader's number grammar is total, so nothing on the decode path should reach a
        // `NumberFormatException` any more — this arm is the floor under that claim. A lexeme the
        // grammar admitted but the JDK would not convert is a defect in the DATA path, and a host
        // that met one would rather show the banner than unwind its main thread over a number.
        is NumberFormatException ->
            FuaranException(
                FuaranDecodeException.INVALID_JSON,
                "$",
                "decode",
                "a number in the session's document could not be read: ${t.message}",
            )
        else -> null
    }
