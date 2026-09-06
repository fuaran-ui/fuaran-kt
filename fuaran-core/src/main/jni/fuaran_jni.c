/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Diametrical Ltd.
 *
 * The hand-written JNI shim over the Fuaran core C-ABI (see the include note below).
 * It implements the Java_fuaran_core_FuaranNative_* symbols the JVM binds for the
 * `native` declarations in FuaranNative.java, forwarding to the `fuaran_*` session
 * surface. No JNA/JNR — a thin, dependency-light C layer per the house stance.
 *
 * Buffer ownership (native ABI): every text-returning C-ABI function returns a
 * Rust-owned `FuaranBuf { uint8_t* ptr; size_t len }` BY VALUE (the packed-u64 form is
 * wasm32-only). This shim copies `len` bytes (no trailing NUL) into a fresh jbyteArray,
 * then frees the buffer with fuaran_dealloc(ptr, len). Input buffers are allocated with
 * fuaran_alloc, filled from the jbyteArray, borrowed for the one call, then freed.
 */
#include "generated/fuaran_core_FuaranNative.h"
#include <limits.h>
#include <stddef.h>
#include <stdint.h>

/*
 * The C-ABI is INCLUDED, not re-declared.
 *
 * `include/fuaran.h` beside this file is a generated, byte-compared copy of the reference header
 * the Rust core ships - the same discipline the reference stylesheet has: one canonical artefact,
 * copies regenerated in the producing repo and byte-compared in every consumer's gate, never
 * hand-edited. The gate compares it against the sibling checkout when one is present and reports
 * NOT CHECKED when it is not, because a single-repo checkout has no sibling and "nothing to
 * compare" must never read as "compared".
 *
 * This file used to carry its own `FuaranBuf` struct and its own `extern` prototypes, which is the
 * worst available shape for an FFI shim: a change to the return ABI or to any signature compiled
 * CLEANLY here against the stale local declarations, and the disagreement surfaced at RUN time as
 * a corrupted buffer or a wild pointer - on a device, inside an app, with nothing pointing back at
 * the header change that caused it. Including the real header turns an ABI change into a compile
 * error in the one place that can still act on it.
 */
#include "include/fuaran.h"

/* --- marshalling helpers ----------------------------------------------------------- */

/*
 * Copy a Rust-owned FuaranBuf into a fresh jbyteArray, then free the buffer.
 *
 * Two failure modes are answered here rather than left to the JVM, and both used to end with a
 * NULL returned into a Kotlin declaration typed as a non-null ByteArray:
 *
 *   - `b.len` above the maximum jsize. `(jsize)b.len` TRUNCATES, so a payload past 2 GiB became a
 *     small array and the shim copied only the low bytes — silently handing Kotlin a truncated
 *     document that decodes as malformed JSON, with nothing to say where it was cut. Refused
 *     instead: the array cannot exist, so no array is claimed to.
 *   - `NewByteArray` returning NULL, with an OutOfMemoryError already pending on the thread. That
 *     is the JNI protocol's own way of reporting a failed allocation, and the JVM raises it on
 *     return to Java, so the Kotlin side sees the error rather than a null in a non-null slot.
 *
 * The first case has no pending exception, so this throws one itself — returning a bare NULL there
 * would be exactly the null-in-a-non-null-slot the JVM's own path avoids.
 *
 * The Rust buffer is freed on EVERY path, refusals included: it is owned by this call the moment
 * it is returned, and an early return that skipped the free would leak it.
 */
static jbyteArray buf_to_jarray(JNIEnv *env, FuaranBuf b) {
    if (b.len > (size_t)INT_MAX) {
        if (b.ptr != NULL) {
            fuaran_dealloc(b.ptr, b.len);
        }
        jclass err = (*env)->FindClass(env, "java/lang/OutOfMemoryError");
        if (err != NULL) {
            (*env)->ThrowNew(env, err, "fuaran: payload exceeds the maximum Java array length");
        }
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize)b.len);
    if (out != NULL && b.len > 0) {
        (*env)->SetByteArrayRegion(env, out, 0, (jsize)b.len, (const jbyte *)b.ptr);
    }
    if (b.ptr != NULL) {
        fuaran_dealloc(b.ptr, b.len);
    }
    return out;
}

/*
 * Copy a jbyteArray into a fuaran_alloc'd input buffer. On success writes *out_ptr /
 * *out_len (caller must fuaran_dealloc after the borrowing call). A zero-length array
 * yields a NULL/0 pair, which the C-ABI accepts. Returns 0 on allocation failure.
 */
static int jarray_to_input(JNIEnv *env, jbyteArray arr, uint8_t **out_ptr, size_t *out_len) {
    jsize n = (*env)->GetArrayLength(env, arr);
    if (n == 0) {
        *out_ptr = NULL;
        *out_len = 0;
        return 1;
    }
    uint8_t *in = fuaran_alloc((size_t)n);
    if (in == NULL) {
        return 0;
    }
    (*env)->GetByteArrayRegion(env, arr, 0, n, (jbyte *)in);
    *out_ptr = in;
    *out_len = (size_t)n;
    return 1;
}

static FuaranSession *as_session(jlong handle) {
    return (FuaranSession *)(intptr_t)handle;
}

/* --- native methods ---------------------------------------------------------------- */

JNIEXPORT jlong JNICALL Java_fuaran_core_FuaranNative_sessionNew(JNIEnv *env, jclass cls, jbyteArray nodeJson) {
    (void)cls;
    uint8_t *in;
    size_t len;
    if (!jarray_to_input(env, nodeJson, &in, &len)) {
        return 0L;
    }
    FuaranSession *s = fuaran_session_new(in, len);
    if (in != NULL) {
        fuaran_dealloc(in, len);
    }
    return (jlong)(intptr_t)s;
}

JNIEXPORT jbyteArray JNICALL Java_fuaran_core_FuaranNative_lastError(JNIEnv *env, jclass cls) {
    (void)cls;
    return buf_to_jarray(env, fuaran_last_error());
}

JNIEXPORT void JNICALL Java_fuaran_core_FuaranNative_sessionFree(JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    fuaran_session_free(as_session(handle));
}

JNIEXPORT jbyteArray JNICALL Java_fuaran_core_FuaranNative_sessionRender(JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    return buf_to_jarray(env, fuaran_session_render(as_session(handle)));
}

JNIEXPORT jbyteArray JNICALL Java_fuaran_core_FuaranNative_sessionTreeJson(JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    return buf_to_jarray(env, fuaran_session_tree_json(as_session(handle)));
}

JNIEXPORT jbyteArray JNICALL Java_fuaran_core_FuaranNative_sessionProjectResolved(JNIEnv *env, jclass cls,
                                                                                  jlong handle) {
    (void)cls;
    return buf_to_jarray(env, fuaran_session_project_resolved(as_session(handle)));
}

JNIEXPORT jbyteArray JNICALL Java_fuaran_core_FuaranNative_sessionResolvedRows(JNIEnv *env, jclass cls,
                                                                               jlong handle, jbyteArray nodeId) {
    (void)cls;
    uint8_t *in;
    size_t len;
    if (!jarray_to_input(env, nodeId, &in, &len)) {
        return buf_to_jarray(env, (FuaranBuf){NULL, 0});
    }
    FuaranBuf out = fuaran_session_resolved_rows(as_session(handle), in, len);
    if (in != NULL) {
        fuaran_dealloc(in, len);
    }
    return buf_to_jarray(env, out);
}

JNIEXPORT jbyteArray JNICALL Java_fuaran_core_FuaranNative_sessionApplyOp(JNIEnv *env, jclass cls, jlong handle,
                                                                          jbyteArray opJson) {
    (void)cls;
    uint8_t *in;
    size_t len;
    if (!jarray_to_input(env, opJson, &in, &len)) {
        return buf_to_jarray(env, (FuaranBuf){NULL, 0});
    }
    FuaranBuf out = fuaran_session_apply_op(as_session(handle), in, len);
    if (in != NULL) {
        fuaran_dealloc(in, len);
    }
    return buf_to_jarray(env, out);
}

/* Shared body for the three set_* slot writers. */
static jbyteArray write_slot(JNIEnv *env, jlong handle, jbyteArray key, jbyteArray value,
                             FuaranBuf (*fn)(FuaranSession *, const uint8_t *, size_t, const uint8_t *, size_t)) {
    uint8_t *k;
    size_t klen;
    uint8_t *v;
    size_t vlen;
    if (!jarray_to_input(env, key, &k, &klen)) {
        return buf_to_jarray(env, (FuaranBuf){NULL, 0});
    }
    if (!jarray_to_input(env, value, &v, &vlen)) {
        if (k != NULL) fuaran_dealloc(k, klen);
        return buf_to_jarray(env, (FuaranBuf){NULL, 0});
    }
    FuaranBuf out = fn(as_session(handle), k, klen, v, vlen);
    if (k != NULL) fuaran_dealloc(k, klen);
    if (v != NULL) fuaran_dealloc(v, vlen);
    return buf_to_jarray(env, out);
}

JNIEXPORT jbyteArray JNICALL Java_fuaran_core_FuaranNative_sessionSetState(JNIEnv *env, jclass cls, jlong handle,
                                                                           jbyteArray key, jbyteArray value) {
    (void)cls;
    return write_slot(env, handle, key, value, fuaran_session_set_state);
}

JNIEXPORT jbyteArray JNICALL Java_fuaran_core_FuaranNative_sessionSetFilter(JNIEnv *env, jclass cls, jlong handle,
                                                                            jbyteArray key, jbyteArray value) {
    (void)cls;
    return write_slot(env, handle, key, value, fuaran_session_set_filter);
}

JNIEXPORT jbyteArray JNICALL Java_fuaran_core_FuaranNative_sessionSetQuery(JNIEnv *env, jclass cls, jlong handle,
                                                                           jbyteArray key, jbyteArray value) {
    (void)cls;
    return write_slot(env, handle, key, value, fuaran_session_set_query);
}
