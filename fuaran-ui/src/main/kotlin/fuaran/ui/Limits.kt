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
     * Bounds TREE-ITEM nesting — the longest chain of `TreeItem` rows inside ONE `Tree` node, the
     * outermost row counting as 1 (WIRE_FORMAT.md 3.6.12 + 21.5).
     *
     * **The same figure as [MAX_NODE_DEPTH], on a THIRD and separate axis** — the `TreeOp.Batch`
     * lesson applied again. A whole hierarchy lives inside one node, so the node axis cannot see
     * it at all; and at roughly two JSON levels per row the syntactic bound at 256 is nowhere near
     * reached, so [MAX_JSON_DEPTH] cannot either. Sharing the number is the specification's choice
     * and not a coincidence to be tidied into an alias: the two axes are declared separately
     * because either could move without the other.
     */
    const val MAX_TREE_ITEM_DEPTH: Int = 24

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

    /**
     * Bounds the `ColExpr` nodes in ONE expression (WIRE_FORMAT.md 21.8).
     *
     * The bounds above do not see what an expression COSTS. An expression is small in bytes
     * and shallow in JSON relative to the work it names, so a document well inside every
     * structural limit can still name an evaluation that is not — and what a host does with
     * an expression is EVALUATE it.
     *
     * Counted per EXPRESSION rather than per document or per pipeline: a tree may carry many
     * bounded expressions, and twenty `derive` steps of ten nodes each are twenty cheap
     * evaluations rather than one expensive one, so a whole-pipeline sum would refuse that
     * legitimate shape while catching no blow-up this bound misses. The aggregate is the
     * document's own size bound's job.
     *
     * Its SCOPE is EVERY expression a decoded document can name, and there are exactly two
     * positions: a `Binding.Expr`'s `expr` (3.3.2), and the expression a `Binding.Transform`
     * pipeline embeds — a `derive` step's `expr`, a `filter` step's `pred`. Those two are the
     * whole surface as a fact about the vocabulary rather than as a promise: `filter` and
     * `derive` are the only pipeline steps carrying an expression, and the operand of a
     * `join` / `union` / `intersect` / `except` is a data source, never another pipeline.
     *
     * The pipeline positions were EXPLICITLY EXCLUDED until 21.8 was amended, which is what
     * made the bound bypassable by wrapping an expression in a Transform — including the one
     * shape a `Binding.Expr` already refused. Closing that changes what an already-shipped
     * decoder accepts, so the refusal is stated rather than left to be read off the code: a
     * document past the bound is refused OUTRIGHT, with no profile boundary and no
     * grandfathering, because 21.2 rules 1 and 2 admit no second acceptance class.
     *
     * ONE count and not a count plus a depth: depth is at most the node count for every
     * expression, so an expression 600 deep is already 600 nodes and already refused, and a
     * second number would be one more figure to keep in step across the hosts while refusing
     * nothing this one does not.
     */
    const val MAX_EXPR_NODES: Int = 512

    /**
     * Bounds the value of ONE `Skeleton` node's `rows` slot (WIRE_FORMAT.md 21.9).
     *
     * The first bound here that a document breaches with four digits rather than with bulk,
     * and [MAX_EXPR_NODES]'s argument applies more sharply because this is not even an
     * evaluation — the rows are simply not present in the input. A renderer emits one
     * placeholder row per count, so a `Skeleton` naming a hundred million rows is a handful
     * of bytes, one node and three JSON levels. Every structural limit is satisfied, and each
     * is satisfied because none of them is looking at the value.
     *
     * Counted per NODE rather than per document: a tree may carry many `Skeleton` nodes, each
     * bounded here, with the whole still bounded by [MAX_NODES].
     *
     * **7.1 decides FIRST, and the ORDER is the whole of what keeps the two rules apart.**
     * 7.1 governs what a typed integer slot can HOLD, and `2147483647` is finite,
     * fraction-free and inside signed 32-bit, so 7.1 admits it; this bound then refuses it
     * for the work it names. So a value that is not an integer at all stays `WRONG_TYPE` and
     * never a limit breach, and a 32-bit-valid value past the bound is `LIMIT_EXCEEDED` and
     * never a wrong type. Reading this as a narrowing of the slot's TYPE gets both halves
     * wrong at once: it answers `WRONG_TYPE` at the 32-bit maximum AND refuses the
     * at-the-bound document 21.2 rule 1 obliges every host to accept.
     *
     * An UPPER bound only, and the omission is deliberate. A negative count expands nothing,
     * so it is not a resource breach, and answering `LIMIT_EXCEEDED` for it would tell an
     * author to come back under a ceiling when what they wrote is a count that cannot be
     * drawn at all. That is an authoring defect, and it belongs to the pre-emit validator
     * family this decode-only surface does not carry.
     */
    const val MAX_SKELETON_ROWS: Int = 10_000
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
