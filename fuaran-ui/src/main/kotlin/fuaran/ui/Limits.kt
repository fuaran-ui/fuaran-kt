// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.ui

/**
 * Decode-side resource limits for untrusted wire input (WIRE_FORMAT.md 21).
 *
 * WHY THIS EXISTS. The wire format promises that decoding is total: a malformed or
 * hostile input yields a structured, typed refusal, never a crash and never an error
 * outside the declared contract. That promise held on *semantics* here — a wrong-typed
 * field, an unrecognised discriminator — and was silent on *shape*. This decoder is a
 * recursive descent over a recursive document, and nothing bounded the recursion: a
 * payload of a few hundred kilobytes consisting only of `[[[[[…` — two bytes per level —
 * walks straight down, and a `StackOverflowError` is not a `FuaranDecodeException`.
 *
 * Two measured symptoms this closes on this surface, both from the shared corpus:
 * a 257-level bare-nesting document was refused as `WRONG_TYPE` (the parser built it
 * happily and the node decoder then said "not an object"), which is an actively wrong
 * diagnosis — it sends an author to repair a shape that is not the problem; and a
 * 25-level node tree, one past the limit, was **accepted outright**, so a document every
 * other host refuses decoded here.
 *
 * ## The figures are protocol numbers, not tuning knobs
 *
 * They are part of the format: a document within them is one every host MUST decode, and
 * a document beyond them is one every host MUST refuse, with the same typed error.
 * Changing one is a format change — it moves in the specification and across every host,
 * never here alone. 21.4 records how [MAX_NODE_DEPTH] was derived, by bisecting each
 * walk's true overflow depth on the reference host; it is not re-derived per host.
 *
 * The two depth numbers are separate because neither derives from the other. One tree
 * level costs several JSON levels (a `Box` costs three — the node object, its `children`
 * array, the child object), and a structured payload slot nests freely WITHIN one node
 * and consumes no node depth at all. A host must never report a node-depth breach as a
 * syntax-depth breach.
 *
 * ## Why the counters are thread-locals rather than plain top-level state
 *
 * [decodeNode] is public API of a library, so concurrent decode from several threads is
 * expected usage. Shared mutable counters would be a plain data race — and the damaging
 * kind: not a crash, but two decodes silently mis-bounding each other under load, which
 * would show up as a valid tree refused on a busy machine and never on a quiet one. A
 * [ThreadLocal] is the JVM analogue of the thread-local counters the Rust host uses, and
 * it buys the same guarantee the Go host bought by threading a walk state through 81
 * signatures — without touching the ~200 decode functions here, which is the difference
 * between a guard that lands and one that gets deferred.
 *
 * Counters are decremented in `finally`, which is what makes them correct on the ERROR
 * paths — and on a default-deny decoder those are most of the paths. A refused decode
 * must not leave a residue that mis-bounds the next one.
 */
object WireLimits {
    /**
     * Bounds NODE nesting — the longest root-to-leaf chain of `Node` objects, the root
     * counting as 1.
     */
    const val MAX_NODE_DEPTH: Int = 24

    /**
     * Bounds SYNTACTIC nesting: every `{` and `[` counts, whether it carries a node, a
     * spec, or a structured payload — and whether or not it is empty. The empty
     * composite is the trap: an implementation that tests the bound *after* deciding a
     * `{}` / `[]` is empty leaves exactly one level unmeasured, because the innermost
     * level of a `[[[…]]]` payload is always the empty one. That off-by-one cost the
     * host family a cross-host divergence; the check here runs before the empty arm.
     */
    const val MAX_JSON_DEPTH: Int = 256

    /** Bounds a single decoded JSON string, in characters. */
    const val MAX_STRING_LENGTH: Int = 1_048_576

    /** Bounds a single JSON array's elements, and a single JSON object's members. */
    const val MAX_ARRAY_LENGTH: Int = 100_000

    /**
     * Bounds the total node count of one document.
     *
     * Needed even once depth is bounded, because the depth, string and array limits
     * together still admit a document that is hostile by being WIDE — 24 levels of
     * 100 000 siblings is within every other limit. Its cost is linear in the input, but
     * the constant is not: a decoded tree is far larger in memory than the bytes that
     * produced it.
     */
    const val MAX_NODES: Int = 100_000
}

/** One decode call's 21 node-axis counters. See the thread-local note on [WireLimits]. */
private class WalkState {
    var nodeDepth: Int = 0
    var nodes: Int = 0
}

/**
 * The node-axis bound. The syntactic, string and width bounds live in the JSON reader —
 * they belong where the bytes are read, before anything is allocated.
 */
internal object NodeWalk {
    private val state = ThreadLocal.withInitial { WalkState() }

    /**
     * Called on the way DOWN, before the recursion that would breach the bound — never
     * afterwards by measuring the tree that was built. A check that runs after the walk
     * it is meant to bound has already paid the cost it exists to refuse, and on a host
     * with a hard stack limit it never runs at all.
     */
    fun enterNode(path: String) {
        val s = state.get()
        if (s.nodeDepth >= WireLimits.MAX_NODE_DEPTH) {
            throw FuaranDecodeException(
                FuaranDecodeException.LIMIT_EXCEEDED,
                path,
                "node nesting deeper than the wire limit MAX_NODE_DEPTH = ${WireLimits.MAX_NODE_DEPTH}; " +
                    "expected a tree nesting nodes no more than ${WireLimits.MAX_NODE_DEPTH} levels deep",
            )
        }
        s.nodes++
        if (s.nodes > WireLimits.MAX_NODES) {
            throw FuaranDecodeException(
                FuaranDecodeException.LIMIT_EXCEEDED,
                path,
                "the document holds more than the wire limit MAX_NODES = ${WireLimits.MAX_NODES} nodes; " +
                    "expected a tree of no more than ${WireLimits.MAX_NODES} nodes in total",
            )
        }
        s.nodeDepth++
    }

    fun exitNode() {
        state.get().nodeDepth--
    }

    /**
     * Starts a fresh document. The `finally`-paired [exitNode] already leaves the depth
     * balanced across a thrown decode, so this is belt-and-braces for depth — but the
     * NODE COUNT is per-document and genuinely needs clearing, or the second decode on a
     * long-lived thread inherits the first one's budget.
     */
    fun beginDocument() {
        val s = state.get()
        s.nodeDepth = 0
        s.nodes = 0
    }
}
