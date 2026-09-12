// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.core

import fuaran.ui.Box
import fuaran.ui.FuaranException
import fuaran.ui.FuaranSession
import fuaran.ui.Node
import fuaran.ui.Placement
import fuaran.ui.decodeNode
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * All five placement verbs, EXERCISED against the live Rust core (Phase 1703).
 *
 * They existed on the core's library surface from Phase 833 and were reachable from nowhere else:
 * this tier is a decode-only projection, so without the C-ABI entry points the only way to place a
 * node from Kotlin was to author the ops by hand — a second implementation of an algebra the core
 * is the reference for. Phase 1673 surfaced `move` alone and left the general question open; this
 * answers it, and the answer is legible here rather than in a header comment because a verb nothing
 * calls is a declaration, not a capability.
 *
 * **A JUnit class rather than another `main()` harness**, and that is what makes it run in CI at
 * all. `run.ps1` compiles a hand-listed set of `main()`-driven harnesses with `kotlinc` and no JUnit
 * on the classpath; the workflow has no `kotlinc`. `:fuaran-core:test` is the one leg both can
 * reach, so this is written for it and `run.ps1` now invokes it too. `SessionTest.kt`'s Phase-1673
 * move checks stay where they are, under the narrower seed that predates this: they are driven by
 * the other launcher, and moving them would trade one gap for another.
 */
class PlacementRoundTripTest {
    companion object {
        @BeforeClass
        @JvmStatic
        fun loadNative() = NativeLeg.loadOrSkip()

        /**
         * The placement seed, SHARED BYTE-FOR-BYTE with the Swift surface's own placement leg
         * (`Tests/FuaranUITests/SessionTests.swift`). One seed, two hosts: the two projections are
         * independent of each other and depend on the same core, so a divergence in what they
         * exercise it with would be exactly the kind of difference nobody notices until the two
         * answers differ.
         *
         * `note` is a CHILDLESS kind, and it is here rather than a third Box on purpose: the
         * refusals half of this surface — `ChildlessKind` on a place, `CannotNudgeRoot` on the root
         * — needs a node that cannot take children and a sibling list to nudge within, and a seed
         * that affords only the happy path certifies the half nobody gets wrong.
         */
        private const val SEED =
            """{"id":"root","kind":{"${'$'}type":"Box","children":[""" +
                """{"id":"left","kind":{"${'$'}type":"Box","children":[""" +
                """{"id":"a","kind":{"${'$'}type":"Markdown","text":"A"}},""" +
                """{"id":"b","kind":{"${'$'}type":"Markdown","text":"B"}}],""" +
                """"layout":{"${'$'}type":"Flex","direction":"Vertical","wrap":false},"role":"Group"}},""" +
                """{"id":"right","kind":{"${'$'}type":"Box","children":[],""" +
                """"layout":{"${'$'}type":"Flex","direction":"Vertical","wrap":false},"role":"Group"}},""" +
                """{"id":"note","kind":{"${'$'}type":"Markdown","text":"N"}}],""" +
                """"layout":{"${'$'}type":"Flex","direction":"Vertical","wrap":false},"role":"Group"}}"""

        /**
         * A canonical wire `Node` document to insert. The tier is decode-only, so a caller hands
         * over a document rather than a `Node` — this is what one looks like at the call site.
         */
        private const val FRESH_NODE =
            """{"id":"fresh","kind":{"${'$'}type":"Markdown","text":"F"}}"""

        /** A subtree lifted from somewhere else: `tray` collides with nothing, `a` collides. */
        private const val CLIPBOARD =
            """{"id":"tray","kind":{"${'$'}type":"Box","children":[""" +
                """{"id":"a","kind":{"${'$'}type":"Markdown","text":"A2"}},""" +
                """{"id":"z","kind":{"${'$'}type":"Markdown","text":"Z"}}],""" +
                """"layout":{"${'$'}type":"Flex","direction":"Vertical","wrap":false},"role":"Group"}}"""
    }

    /**
     * The ids of a Box's children, read off the session's OWN re-encoded tree — so every assertion
     * below is about the tree the CORE holds, never a Kotlin-side echo of the request. Box-only,
     * which is all the seed contains for a parent; a non-Box parent reads as childless and fails
     * loudly rather than quietly matching an empty expectation.
     */
    private fun childIds(treeJson: String, parentId: String): List<String> {
        fun kids(node: Node): List<Node> = (node.kind as? Box)?.children ?: emptyList()
        fun find(node: Node): Node? {
            if (node.id == parentId) return node
            for (child in kids(node)) {
                val hit = find(child)
                if (hit != null) return hit
            }
            return null
        }
        val parent = find(decodeNode(treeJson)) ?: error("no node '$parentId' in the session tree")
        return kids(parent).map { it.id }
    }

    /** Run a call that must be REFUSED, and hand back the typed refusal it raised. */
    private fun refusal(body: () -> Unit): FuaranException =
        try {
            body()
            fail("expected a typed placement refusal, and the call returned instead")
        } catch (e: FuaranException) {
            e
        }

    // --- move ----------------------------------------------------------------------------------

    @Test
    fun moveRelocatesANodeAndKeepsItsId() {
        FuaranSession.create(NativeBridge, SEED).use { session ->
            val envelope = session.move(source = "a", parentId = "right", placement = Placement.Last)
            assertTrue(envelope.contains("\"MoveNode\""), "expected a MoveNode op, got: $envelope")

            val tree = session.treeJson()
            assertEquals(listOf("b"), childIds(tree, "left"), "the moved node should have left its old parent")
            // Under its OWN id: a move mints nothing and remaps nothing. That is the whole
            // difference between this verb and a duplicate.
            assertEquals(listOf("a"), childIds(tree, "right"), "the moved node should have arrived under its own id")
        }
    }

    // --- place ---------------------------------------------------------------------------------

    /**
     * `place` inserts a node the tree did not have, in POSITION — the reorder the core folds into
     * the same call, which is the whole reason the verb exists over a bare `InsertChild` (that op
     * appends, and says nothing about where).
     */
    @Test
    fun placeInsertsANewNodeInPosition() {
        FuaranSession.create(NativeBridge, SEED).use { session ->
            val envelope = session.place(childJson = FRESH_NODE, parentId = "left", placement = Placement.Before("b"))
            assertTrue(envelope.contains("\"InsertChild\""), "expected an InsertChild op, got: $envelope")
            assertTrue(
                envelope.contains("\"ReorderChildren\""),
                "a Before placement must carry the reorder that puts it there, got: $envelope",
            )
            assertEquals(listOf("a", "fresh", "b"), childIds(session.treeJson(), "left"))
        }
    }

    /**
     * A place into a kind with no children field is refused BEFORE any op is emitted, with the
     * apply-side code pre-stated — what a drop target greys itself out on.
     */
    @Test
    fun placeIntoAChildlessKindIsRefusedAndChangesNothing() {
        FuaranSession.create(NativeBridge, SEED).use { session ->
            val before = session.treeJson()
            val e = refusal { session.place(childJson = FRESH_NODE, parentId = "note", placement = Placement.Last) }
            assertEquals("placement", e.errorClass)
            assertEquals("ChildlessKind", e.code)
            assertEquals(before, session.treeJson(), "a refused place must leave the held tree untouched")
        }
    }

    /**
     * A `place` whose child id ALREADY EXISTS is refused rather than remapped. Stated as a test
     * because it is the one input on which two verbs of this family differ: `paste` remaps that id
     * and `place` refuses it, and a surface that quietly did either would be wrong half the time.
     */
    @Test
    fun placeRefusesACollidingIdWherePasteWouldRemapIt() {
        FuaranSession.create(NativeBridge, SEED).use { session ->
            val colliding = """{"id":"a","kind":{"${'$'}type":"Markdown","text":"A2"}}"""
            val e = refusal { session.place(childJson = colliding, parentId = "right", placement = Placement.Last) }
            assertEquals("placement", e.errorClass)
            assertEquals("DuplicateId", e.code)
        }
    }

    /**
     * A malformed node DOCUMENT is judged by the core's parser, not by this tier: the splice is not
     * a hole in the encoder, because the decoder that owns the judgement is the one that makes it —
     * and it comes back as a `request` error, distinct from every `placement` refusal above.
     */
    @Test
    fun aMalformedChildDocumentIsARequestError() {
        FuaranSession.create(NativeBridge, SEED).use { session ->
            val broken = """{"id":"broken","kind":{"${'$'}type":"NoSuchKind"}}"""
            val e = refusal { session.place(childJson = broken, parentId = "right", placement = Placement.Last) }
            assertEquals("request", e.errorClass, "got: ${e.message}")
        }
    }

    // --- nudge ---------------------------------------------------------------------------------

    @Test
    fun nudgeMovesANodeAmongItsSiblings() {
        FuaranSession.create(NativeBridge, SEED).use { session ->
            val envelope = session.nudge(target = "a", delta = 1)
            assertTrue(envelope.contains("\"ReorderChildren\""), "expected a ReorderChildren op, got: $envelope")
            assertEquals(listOf("b", "a"), childIds(session.treeJson(), "left"))
        }
    }

    /**
     * The root has no siblings, so nudging it is refused rather than clamped — and a repeat past
     * the end of the sibling list is refused the same way, so a caller can tell "did not move" from
     * "moved nowhere".
     */
    @Test
    fun nudgingTheRootAndNudgingPastTheEndAreBothRefused() {
        FuaranSession.create(NativeBridge, SEED).use { session ->
            assertEquals("CannotNudgeRoot", refusal { session.nudge(target = "root", delta = 1) }.code)
            val e = refusal { session.nudge(target = "a", delta = -1) }
            assertEquals("placement", e.errorClass)
            assertEquals("NudgeOutOfRange", e.code)
        }
    }

    // --- duplicate -----------------------------------------------------------------------------

    /**
     * `duplicate` MINTS: the copy cannot carry the source's id, so the derived strategy names it
     * `<oldId>-copy`. That is the property separating it from `move`, which keeps the id and mints
     * nothing — and the source must still be where it was.
     */
    @Test
    fun duplicateMintsACopyRatherThanMovingTheSource() {
        FuaranSession.create(NativeBridge, SEED).use { session ->
            session.duplicate(source = "note", parentId = "right", placement = Placement.Last)
            val tree = session.treeJson()
            assertEquals(listOf("note-copy"), childIds(tree, "right"))
            assertEquals(
                listOf("left", "right", "note"),
                childIds(tree, "root"),
                "the source must still be where it was — a duplicate is not a move",
            )
        }
    }

    /** `idPrefix` selects the DETERMINISTIC strategy, so a caller that must predict the ids can. */
    @Test
    fun duplicateUnderAnIdPrefixMintsPredictableIds() {
        FuaranSession.create(NativeBridge, SEED).use { session ->
            session.duplicate(source = "left", parentId = "right", placement = Placement.Last, idPrefix = "dup")
            val tree = session.treeJson()
            assertEquals(listOf("dup-1"), childIds(tree, "right"))
            assertEquals(
                listOf("dup-2", "dup-3"),
                childIds(tree, "dup-1"),
                "every id in the clone is minted in traversal order under the prefix",
            )
        }
    }

    // --- paste ---------------------------------------------------------------------------------

    /**
     * `paste` places a subtree from ELSEWHERE, remapping the ids that collide with the tree it
     * lands in and preserving the ones that do not. That remapping is the whole difference from
     * `place`, which refuses a collision.
     */
    @Test
    fun pasteRemapsCollidingIdsAndPreservesTheRest() {
        FuaranSession.create(NativeBridge, SEED).use { session ->
            session.paste(subtreeJson = CLIPBOARD, parentId = "right", placement = Placement.Last)
            val tree = session.treeJson()
            assertEquals(listOf("tray"), childIds(tree, "right"), "'tray' collides with nothing, so it keeps its id")
            assertEquals(
                listOf("a-copy", "z"),
                childIds(tree, "tray"),
                "'a' collides and is remapped; 'z' does not and is preserved",
            )
        }
    }
}

/**
 * The gate on every live-native leg in this module.
 *
 * The shim is supplied through `-Dfuaran.lib` (built by `dev-scripts/build-native-desktop.ps1` and
 * forwarded by `:fuaran-core:test` from `-Pfuaran.lib` / `FUARAN_LIB`). With it absent the legs
 * SKIP, which is right for a box with no Rust toolchain and is exactly how a suite comes to report
 * green having certified nothing — so `-Pfuaran.requireNative=1` (or `FUARAN_REQUIRE_NATIVE=1`)
 * turns the skip into a FAILURE. CI sets it, because a workflow that goes to the trouble of
 * building the shim and then silently skips the tests that use it has proved nothing at all and
 * said otherwise.
 *
 * The flag is deliberately NOT derived from `fuaran.lib` being set. The failure it guards is
 * exactly the case where the caller built a shim and did not manage to hand its path over — so a
 * guard keyed to that path would be absent in the one state it exists to catch.
 */
internal object NativeLeg {
    private var loaded = false

    /** `-Pfuaran.requireNative` (forwarded as a system property) or the environment variable. */
    private fun nativeRequired(): Boolean =
        (System.getProperty("fuaran.requireNative") ?: System.getenv("FUARAN_REQUIRE_NATIVE")) == "1"

    @Synchronized
    fun loadOrSkip() {
        val libPath = System.getProperty("fuaran.lib")
        if (libPath.isNullOrBlank()) {
            check(!nativeRequired()) {
                "the native leg was REQUIRED but -Dfuaran.lib is unset, so it would have SKIPPED — " +
                    "and a skipped gate is not a passing one. Pass -Pfuaran.lib=<abs path to the JNI shim>."
            }
            assumeTrue(
                "SKIP: -Dfuaran.lib not set (desktop JNI shim unavailable — Rust toolchain / C compiler absent).",
                false,
            )
            return
        }
        if (!loaded) {
            NativeBridge.load(libPath)
            loaded = true
        }
    }
}
