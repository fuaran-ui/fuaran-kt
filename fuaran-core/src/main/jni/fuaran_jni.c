/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright Diametrical Ltd.
 *
 * The hand-written JNI shim over the fuaran-rs C-ABI (../../../../fuaran-rs/include/fuaran.h).
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
#include <stddef.h>
#include <stdint.h>

/* --- fuaran-rs C-ABI (subset used by the session binding) ------------------------- */
typedef struct FuaranSession FuaranSession;
typedef struct FuaranBuf {
    uint8_t *ptr;
    size_t len;
} FuaranBuf;

extern uint8_t *fuaran_alloc(size_t len);
extern void fuaran_dealloc(uint8_t *ptr, size_t len);
extern FuaranSession *fuaran_session_new(const uint8_t *ptr, size_t len);
extern void fuaran_session_free(FuaranSession *session);
extern FuaranBuf fuaran_session_render(FuaranSession *session);
extern FuaranBuf fuaran_session_tree_json(FuaranSession *session);
extern FuaranBuf fuaran_session_apply_op(FuaranSession *session, const uint8_t *ptr, size_t len);
extern FuaranBuf fuaran_session_set_state(FuaranSession *session, const uint8_t *k_ptr, size_t k_len,
                                          const uint8_t *v_ptr, size_t v_len);
extern FuaranBuf fuaran_session_set_filter(FuaranSession *session, const uint8_t *k_ptr, size_t k_len,
                                           const uint8_t *v_ptr, size_t v_len);
extern FuaranBuf fuaran_session_set_query(FuaranSession *session, const uint8_t *k_ptr, size_t k_len,
                                          const uint8_t *v_ptr, size_t v_len);
extern FuaranBuf fuaran_last_error(void);

/* --- marshalling helpers ----------------------------------------------------------- */

/* Copy a Rust-owned FuaranBuf into a fresh jbyteArray, then free the buffer. */
static jbyteArray buf_to_jarray(JNIEnv *env, FuaranBuf b) {
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
