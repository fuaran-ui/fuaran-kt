// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// `fuaran-core` — the C-ABI JNI binding under the Kotlin surface: the hand-written JNI
// shim over the fuaran-rs `fuaran_*` session surface, plus the native packaging (a
// desktop `.dll`/`.so`/`.dylib` for tests; Android per-ABI `.so`s in `jniLibs/` via
// cargo-ndk). Depends on `fuaran-ui` for the render-projection decoder the round-trip
// test re-projects into. (Phase 543.)
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":fuaran-ui"))
    testImplementation(kotlin("test"))
    testImplementation(project(":fuaran-ui"))
}
