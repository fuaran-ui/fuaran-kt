// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// `fuaran-ui` — the pure-JVM native Kotlin surface (sealed tree model +
// render-projection decoder) over the Rust reference core of the Fuaran UI wire format.
// Dependency-light by mandate: the JSON reader is hand-rolled, so there is no runtime
// dependency. Testable on any JVM without Android tooling.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test"))
}
