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

    public static native byte[] sessionApplyOp(long handle, byte[] opJson);

    public static native byte[] sessionSetState(long handle, byte[] key, byte[] value);

    public static native byte[] sessionSetFilter(long handle, byte[] key, byte[] value);

    public static native byte[] sessionSetQuery(long handle, byte[] key, byte[] value);
}
