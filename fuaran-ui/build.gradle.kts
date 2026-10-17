// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// `fuaran-ui` — the pure-JVM native Kotlin surface (sealed tree model +
// render-projection decoder) over the Rust reference core of the Fuaran UI wire format.
// Dependency-light by mandate: the JSON reader is hand-rolled, so there is no runtime
// dependency. Testable on any JVM without Android tooling.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.SourcesJar

plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvmToolchain(21)
    // Emit Java-17 bytecode so an Android library (`:fuaran-renderer`, whose D8/desugar target is
    // JVM 17) can consume this module by project dependency. The toolchain still runs on JDK 21.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
    // Phase 2301 — the ONE corpus locator every harness resolves through, shared by every module's
    // test source set (and compiled by name onto run.ps1's direct kotlinc line).
    sourceSets.named("test") { kotlin.srcDir(rootProject.file("test-support/kotlin")) }
}

// Keep the (source-free) Java compile task's target in lockstep with Kotlin's 17 so Gradle's
// JVM-target-consistency check passes despite the JDK-21 toolchain.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(kotlin("test"))
}

// The corpus decode harness is `main()`-driven, not JUnit, so the `test` task
// never runs it — until now it was reachable only through `run.ps1`'s direct
// `kotlinc` build, i.e. only on a machine with kotlinc on PATH. This task makes
// it runnable by Gradle on any platform (notably CI) with no change to the
// harness itself: it exits non-zero on any failed check.
tasks.register<JavaExec>("corpusCheck") {
    group = "verification"
    description = "Decode every node-round-trip fixture in the shared wire-format corpus."
    mainClass.set("fuaran.ui.CorpusDecodeTestKt")
    classpath = sourceSets["test"].runtimeClasspath
    // The harness finds the corpus through `WireCorpus` (test-support/): $FUARAN_WIRE_FIXTURES,
    // -Dfuaran.corpus, $FUARAN_CORPUS, then a sibling checkout — and FAILS when it finds none.
    // Forwarded explicitly rather than trusted to reach the harness JVM ambiently.
    System.getProperty("fuaran.corpus")?.let { systemProperty("fuaran.corpus", it) }
    System.getenv("FUARAN_WIRE_FIXTURES")?.let { environment("FUARAN_WIRE_FIXTURES", it) }
    // The decode gate certifies the GENERATED model, so it first proves that model is the one
    // `idl.json` says it is.
    dependsOn("idlModelCheck")
}

// Phase 2299 — the sealed tree model is generated from the corpus's `idl.json` by this module's own
// generator (`src/test/kotlin/fuaran/ui/idl/`). This compares the generated files with what the IDL
// produces and fails naming the regenerate command; `FUARAN_IDL_REGENERATE=1` rewrites them instead.
// The corpus is found through the same `WireCorpus` locator as the decode harness, and is required.
tasks.register<JavaExec>("idlModelCheck") {
    group = "verification"
    description = "Fail when the generated model no longer matches the corpus idl.json (FUARAN_IDL_REGENERATE=1 rewrites it)."
    mainClass.set("fuaran.ui.idl.IdlModelCheckKt")
    classpath = sourceSets["test"].runtimeClasspath
    System.getProperty("fuaran.corpus")?.let { systemProperty("fuaran.corpus", it) }
    System.getenv("FUARAN_WIRE_FIXTURES")?.let { environment("FUARAN_WIRE_FIXTURES", it) }
}

// Maven Central — the artifact shape only; identity, licence and SCM are configured once in the
// root build. An empty javadoc jar satisfies Central's javadoc requirement (no Dokka here).
mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
}
