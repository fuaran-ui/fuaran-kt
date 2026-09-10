// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// `fuaran-driver` — the server-driven (SDUI) driver over the confined session (Phase 545). A pure-JVM
// module: it fetches an initial tree, applies streamed `TreeOp`s against a `TreeSession`, re-projects
// the tree, and posts interaction events back — all over a transport-agnostic seam. The reference
// transport is dependency-light (`HttpURLConnection`, JDK stdlib), the seam left open for an OkHttp
// consumer. No native dependency: the driver programs against the `TreeSession` seam, so its tests run
// against an in-JVM fixture HTTP server + a fake session with no emulator and no Rust core.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":fuaran-ui"))
    testImplementation(kotlin("test"))
    testImplementation(project(":fuaran-ui"))
}

// Maven Central — the artifact shape only; identity, licence and SCM are configured once in the
// root build. An empty javadoc jar satisfies Central's javadoc requirement (no Dokka here).
mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
}
