// SPDX-License-Identifier: Apache-2.0
// Copyright Diametrical Ltd.
//
// Forward-looking Gradle scaffold. The reference dev box has no Gradle binary, so the
// live build path is `run.ps1` (kotlinc/javac/java directly); these `*.gradle.kts` files
// declare the intended module graph for a future Gradle wrapper. See CLAUDE.md.
rootProject.name = "fuaran-kt"

include("fuaran-ui", "fuaran-core")
