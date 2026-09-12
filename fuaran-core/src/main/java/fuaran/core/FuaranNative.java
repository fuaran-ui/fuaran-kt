// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
package fuaran.core;

/**
 * The raw JNI bridge to the fuaran-rs C-ABI ({@code fuaran_*} in
 * {@code ../fuaran-rs/include/fuaran.h}).
 *
 * <p>This is a standalone Java class with {@code native} declarations so {@code javac -h}
 * can generate the JNI header directly (JDK 21 has no {@code javah}); the hand-written C
 * shim in {@code src/main/jni/} implements the {@code Java_fuaran_core_FuaranNative_*}
 * symbols and forwards to the C-ABI, marshalling {@code byte[]} per the header's
 * ownership rules. It deliberately depends on nothing else so header generation needs
 * only {@code jni.h}. The Kotlin adapter {@code NativeBridge} wraps these statics behind
 * the {@code FuaranNativeBridge} seam; the {@code FuaranSession} wrapper enforces the
 * single-owner thread-confinement contract.
 *
 * <p>All text crosses as UTF-8 {@code byte[]}; the session handle is an opaque
 * {@code long} (the C-ABI pointer). Buffer-returning natives read {@code len} bytes at
 * the returned {@code (ptr,len)} pair and free it with {@code fuaran_dealloc} inside the
 * shim, so every {@code byte[]} returned here is owned by the JVM.
 */
public final class FuaranNative {
    private FuaranNative() {}

    /** Load the JNI shim shared library from an absolute path (dev/desktop test leg). */
    public static void load(String absolutePath) {
        System.load(absolutePath);
    }

    /** Load the JNI shim by library name from {@code java.library.path} (Android / packaged). */
    public static void loadLibrary(String name) {
        System.loadLibrary(name);
    }

    /** Decode a node JSON into a new session handle; {@code 0} on failure (see {@link #lastError()}). */
    public static native long sessionNew(byte[] nodeJson);

    /** The last {@code sessionNew} failure envelope on this thread (empty on success). Per-thread. */
    public static native byte[] lastError();

    public static native void sessionFree(long handle);

    public static native byte[] sessionRender(long handle);

    public static native byte[] sessionTreeJson(long handle);

    /**
     * The current tree as a RESOLVED PROJECTION (Phase 650): {@code tree_json} with every
     * scalar-slot {@code Binding.Transform} folded to the value it evaluates to, so a
     * decode-only surface renders resolved compute values. Additive — {@code sessionTreeJson}
     * is unchanged.
     */
    public static native byte[] sessionProjectResolved(long handle);

    /**
     * The RESOLVED ROWS of one row-bearing node (DataGrid / Chart / Map / Sparkline), addressed
     * by node id. Out-of-band because a resolved COLLECTION cannot ride the tree — the wire's
     * {@code Static} slot erases one to {@code "<opaque>"} — so a decode-only surface cannot get
     * rows from {@code sessionTreeJson} at all. Returns
     * {@code {"resolved":true,"rows":[...]}} / {@code {"resolved":false}} / a NO_ROW_SOURCE
     * error envelope; the three are distinct on purpose.
     */
    public static native byte[] sessionResolvedRows(long handle, byte[] nodeId);

    /**
     * RELOCATE a node already in the tree (Phase 1673) from a canonical-JSON request
     * document: {@code {"source":"...","parentId":"...","placement":"Last"|"First"|"Before"|"After",
     * "anchor":"..."?}}. The node KEEPS ITS ID — the core emits {@code MoveNode} (plus a
     * {@code ReorderChildren} when appending does not already give the wanted order) — which is
     * why a caller cannot spell a move as place-then-remove: between those two ops the moved id
     * either does not exist or exists twice.
     *
     * <p>Returns {@code {"ok":true,"op":{...}}} with the emitted op, or an error envelope whose
     * class is {@code "placement"} (the apply-side refusal this move would have met, pre-stated)
     * or {@code "request"}. On refusal the held tree is untouched.
     */
    public static native byte[] sessionMove(long handle, byte[] requestJson);

    /**
     * INSERT a new node among a parent's children (Phase 1703):
     * {@code {"parentId":"...","placement":"...","anchor":"..."?,"child":{...node...}}}.
     *
     * <p>A {@code place} mints and remaps NOTHING: an id in {@code child} that is already in the
     * tree is refused as {@code DuplicateId}, where {@link #sessionPaste} would remap it. Same
     * envelope contract as {@link #sessionMove}.
     */
    public static native byte[] sessionPlace(long handle, byte[] requestJson);

    /**
     * Move a node one or more positions among its OWN siblings (Phase 1703):
     * {@code {"target":"...","delta":±n}}. No destination — it never leaves its parent.
     *
     * <p>Refuses {@code CannotNudgeRoot} and {@code NudgeOutOfRange} rather than clamping, so a
     * held-key repeat stops at the end of the sibling list instead of silently doing nothing.
     */
    public static native byte[] sessionNudge(long handle, byte[] requestJson);

    /**
     * COPY a node already in the tree and place the copy (Phase 1703):
     * {@code {"source":"...","parentId":"...","placement":"...","anchor":"..."?,"idPrefix":"..."?}}.
     *
     * <p>Every id in the clone that collides with one already in the tree is remapped; ids that do
     * not collide are preserved. {@code idPrefix} selects the deterministic strategy
     * ({@code <prefix>-1}, {@code -2}, … in traversal order); omitting it takes the derived one
     * ({@code <oldId>-copy}, then {@code -copy-2}, …).
     */
    public static native byte[] sessionDuplicate(long handle, byte[] requestJson);

    /**
     * Place a subtree lifted from ANOTHER tree (Phase 1703):
     * {@code {"subtree":{...node...},"parentId":"...","placement":"...","anchor":"..."?,"idPrefix":"..."?}}.
     * The id-remapping contract of {@link #sessionDuplicate}; what differs is that the subtree
     * arrives as a document rather than as an id.
     */
    public static native byte[] sessionPaste(long handle, byte[] requestJson);

    public static native byte[] sessionApplyOp(long handle, byte[] opJson);

    public static native byte[] sessionSetState(long handle, byte[] key, byte[] value);

    public static native byte[] sessionSetFilter(long handle, byte[] key, byte[] value);

    public static native byte[] sessionSetQuery(long handle, byte[] key, byte[] value);
}
