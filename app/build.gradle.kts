/*
 * Universal APK Factory & Studio — application module.
 *
 * 100% Kotlin. Jetpack Compose Material 3 UI. arm64-v8a, minSdk 26,
 * targetSdk 35.
 *
 * Binary engineering stack (all open-source, exact coordinates):
 *   - io.github.reandroid:ARSCLib          — ApkModule / AndroidManifestBlock / ResTable
 *   - com.github.iyxan23:zipalign-java     — 4-byte + 16 KiB .so zip alignment (JitPack)
 *   - com.android.tools.build:apksig       — V1/V2/V3 APK signing
 *   - org.bouncycastle:bcpkix-jdk18on      — isolated, uncrippled BouncyCastle (PKCS12)
 *   - com.squareup.okhttp3:okhttp          — GitHub zipball / REST downloads
 */
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.universal.apkfactory"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.universal.apkfactory"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0.0"

        // This factory targets arm64-v8a devices only (the on-device native
        // toolchain — OpenJDK aarch64, aapt2-arm64 — is 64-bit ARM).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Android 10+ W^X: native runners shipped as jniLibs/arm64-v8a/lib*.so
        // must be extracted to disk so they can be executed via linker64.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                // BouncyCastle ships as multi-release JARs; Android dexes the
                // Java 8 baseline classes, so the 9+ variants are excluded to
                // avoid duplicate class files.
                "META-INF/versions/**",
                // Strip jar signatures that would clash inside the final APK.
                "META-INF/*.SF",
                "META-INF/*.RSA",
                "META-INF/*.DSA"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // ---- Jetpack Compose (Material 3) ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // ---- Async / IO ----
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // ---- APK binary engineering stack ----
    implementation(libs.arsclib)
    implementation(libs.zipalign.java)
    implementation(libs.apksig)
    implementation(libs.bouncycastle.bcpkix)

    // ---- Shell support ----
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.documentfile)
}

/*
 * =====================================================================================
 * SHELL PACKAGING TASK ("The Missing Shell Problem")
 * =====================================================================================
 *
 * The zero-Gradle instant patcher (engine-fast-patcher) does not build shell
 * APKs at runtime. Instead, this project compiles two ultra-lightweight
 * shell applications at *build* time and packages them as assets of the
 * factory:
 *
 *   assets/shells/webview_modern_shell.apk   (from :shell-webview)
 *   assets/shells/python_runtime_shell.apk   (from :shell-python)
 *
 * At patch time the shell APK is loaded through ARSCLib's ApkModule, its
 * AndroidManifestBlock is mutated (package id, version, label, permissions,
 * provider authorities), its launcher icons are replaced, and the user's
 * payload is stream-injected under assets/www/ or assets/python_app/.
 *
 * Ordering: :app preBuild depends on the packaging tasks below, which depend
 * on assembleRelease of the respective shell module — so the shell APKs are
 * always freshly compiled and copied into this module's assets *before* the
 * asset merge task runs.
 */
val webviewShellTask = ":shell-webview:packageWebviewShell"
val pythonShellTask = ":shell-python:packagePythonShell"

tasks.named("preBuild") {
    dependsOn(webviewShellTask, pythonShellTask)
}

// Convenience: `./gradlew packageShells` re-exports both shell APKs.
tasks.register("packageShells") {
    group = "apkfactory"
    description = "Compiles and packages both shell APKs into :app assets."
    dependsOn(webviewShellTask, pythonShellTask)
}
