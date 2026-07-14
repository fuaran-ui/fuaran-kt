// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// The live Gradle module graph for the fuaran-kt build.
//
// Phase 544 scope: only `fuaran-ui` (the pure-JVM sealed model + decoder, Phase 542) and
// `fuaran-renderer` (the Jetpack Compose renderer floor) participate. `fuaran-core` — the
// Phase 543 JNI binding — is DELIBERATELY EXCLUDED here: it needs the cross-built native
// `.so`/`.dll` (cargo-ndk + NDK), which the render-only Phase 544 does not depend on (544 is
// render-over-static-tree, exercising the decoder alone — see the phase's "not dependent on
// 543" note). `fuaran-core` keeps its own scaffold and the `run.ps1` desktop-JNI leg; it is
// simply not wired into this Gradle graph. Re-add `include("fuaran-core")` once its Android
// AAR assembly is driven from Gradle.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "fuaran-kt"

include("fuaran-ui", "fuaran-renderer")
