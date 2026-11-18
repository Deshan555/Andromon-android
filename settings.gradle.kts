// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // libsu only (root access); nothing else is taken from JitPack.
        maven("https://jitpack.io") { content { includeGroup("com.github.topjohnwu.libsu") } }
    }
}
rootProject.name = "AndromonCompanion"
include(":app")
