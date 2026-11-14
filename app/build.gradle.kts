// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 The Droid Desk authors

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.droiddesk.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.droiddesk.companion"
        minSdk = 26          // Android 8.0: foreground-service start API used by the PC
        targetSdk = 36
        // Keep in sync with COMPANION_VERSION in pc/src/app.js
        versionCode = 24
        versionName = "0.24.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the local debug key for now. For public releases create a dedicated
            // keystore and keep it safe: updates must be signed with the same key.
            signingConfig = signingConfigs.getByName("debug")
        }
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

// The app's only library: Shizuku-API (MIT), for Privileged access through the Shizuku app
// (ShizukuAccess.kt). Its androidx.annotation dependency is compile-time annotations only and is
// left out, so the app still ships no AndroidX code.
dependencies {
    val shizuku = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku") { exclude(group = "androidx.annotation") }
    implementation("dev.rikka.shizuku:provider:$shizuku") { exclude(group = "androidx.annotation") }
}
