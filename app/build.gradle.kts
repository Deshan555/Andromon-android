// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Andromon authors

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.andromon.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.andromon.companion"
        minSdk = 26          // Android 8.0: foreground-service start API used by the PC
        targetSdk = 36
        // Keep in sync with COMPANION_VERSION in pc/src/app.js
        versionCode = 29
        versionName = "0.29.0"
    }

    // Release signing. The release workflow (.github/workflows/android.yml), or anyone building by
    // hand, passes a keystore through these environment variables. Without them the release build
    // is signed with the local debug key, as before. Updates only install over a build signed with
    // the same key, so keep the keystore safe.
    // DD_*: the same variables under the app's old name (Droid Desk), still accepted
    val releaseKeystore = (System.getenv("ANDROMON_KEYSTORE_FILE") ?: System.getenv("DD_KEYSTORE_FILE"))
    signingConfigs {
        if (!releaseKeystore.isNullOrBlank()) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = (System.getenv("ANDROMON_KEYSTORE_PASSWORD") ?: System.getenv("DD_KEYSTORE_PASSWORD"))
            keyAlias = (System.getenv("ANDROMON_KEY_ALIAS") ?: System.getenv("DD_KEY_ALIAS"))
            keyPassword = (System.getenv("ANDROMON_KEY_PASSWORD") ?: System.getenv("DD_KEY_PASSWORD"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // Lint fails the build on errors. The problems that existed when the check was added are listed
    // in lint-baseline.xml and don't count; fix one and run `./gradlew updateLintBaseline` to drop it.
    lint {
        baseline = file("lint-baseline.xml")
    }

    // Privileged access › Shizuku: the UserService interface (IPrivilegedService.aidl).
    buildFeatures { aidl = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// Libraries. All are plain Java/Android code under licences that fit GPL-3.0; none needs AndroidX
// (the androidx.annotation dependencies are compile-time annotations only and are left out), so
// the release build still ships no AndroidX code. The licences are listed in Settings › About.
dependencies {
    // Privileged access through the Shizuku app (ShizukuAccess.kt). MIT.
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku") { exclude(group = "androidx.annotation") }
    implementation("dev.rikka.shizuku:provider:$shizuku") { exclude(group = "androidx.annotation") }
    // Privileged access with root: one root shell, asked for once (RamManager.kt). Apache 2.0.
    implementation("com.github.topjohnwu.libsu:core:6.0.0") { exclude(group = "androidx.annotation") }
    // Send › Scan code: reads the QR code Andromon shows (QrScanActivity.kt). Apache 2.0.
    implementation("com.google.zxing:core:3.5.3")
    // Storage: make and open zip archives, also password-protected ones (Archives.kt). Apache 2.0.
    implementation("net.lingala.zip4j:zip4j:2.11.5")
}
