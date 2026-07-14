// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// The live Gradle module graph for the fuaran-kt build.
//
// Phase 545 scope: the full graph now participates.
//  - `fuaran-ui`       — the pure-JVM sealed model + decoder + interaction seam (542 / 545).
//  - `fuaran-renderer` — the Jetpack Compose renderer floor + tone bridge + interaction host (544 / 545).
//  - `fuaran-driver`   — the server-driven (SDUI) driver over the session seam (545); pure JVM.
//  - `fuaran-core`     — the Phase 543 JNI binding. WIRED IN at Phase 545 so the live-session
//    interaction round-trip can be exercised through Gradle. Its `:fuaran-core:test` runs a
//    JUnit round-trip that **cleanly skips** unless the desktop native shim is supplied via
//    `-Dfuaran.lib` (built by `dev-scripts/build-native-desktop.ps1`), so the graph stays green
//    on a box without the Rust toolchain. The Android AAR assembly stays in `run.ps1 -Package`.
//  - `samples`         — a minimal Android sample app driving a live tree end-to-end (545).
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

include("fuaran-ui", "fuaran-renderer", "fuaran-driver", "fuaran-core")
