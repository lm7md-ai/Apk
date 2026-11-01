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
import java.io.FileOutputStream
import java.net.URI

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
        versionCode = 10
        versionName = "6.3.1"

        // This factory targets arm64-v8a devices only (the on-device native
        // toolchain — OpenJDK aarch64, aapt2-arm64 — is 64-bit ARM).
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Shared CI debug identity, committed at keystores/factory-debug.keystore
        // (a PUBLIC debug key by convention: store/key password "android", alias
        // "apkfactory" — the same practice as AOSP's well-known debug key; it is
        // NOT release signing material). Without this, every machine — including
        // each GitHub runner — signs with its own ~/.android/debug.keystore, and
        // GitHub rotates that key between runs, so two builds of the same app
        // end up with different certificates and Android then refuses to update
        // one over the other ("App not installed"). Pinning one shared debug
        // identity makes every future build install cleanly over the previous.
        create("factoryDebug") {
            storeFile = rootProject.file("keystores/factory-debug.keystore")
            storePassword = "android"
            keyAlias = "apkfactory"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("factoryDebug")
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

    // ---- Native toolchain + secure storage ----
    implementation(libs.xz)
    implementation(libs.androidx.security.crypto)
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

/*
 * =====================================================================================
 * AAPT2 ARM64 RUNTIME PROVISIONING (W^X-compliant)
 * =====================================================================================
 *
 * AGP spawns aapt2 as an external process during Gradle builds. On Android 10+
 * (targetSdk 29+) executing binaries from app data storage is forbidden
 * (noexec), so the on-device native compiler (engine-native-gradle) cannot use
 * a downloaded aapt2. Instead, the arm64 aapt2 from the AndroidIDE build-tools
 * release is packaged HERE at factory build time as
 * jniLibs/arm64-v8a/libaapt2.so — with useLegacyPackaging=true it is extracted
 * to nativeLibraryDir at install time, which IS executable.
 * NativeGradleService passes it to Gradle via
 * -Pandroid.aapt2FromMavenOverride=<nativeLibraryDir>/libaapt2.so.
 */
val aapt2Output = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libaapt2.so").asFile

val aapt2Sources = listOf(
    // AndroidIDE build-tools 34.0.4 aarch64 (official manifest.json coordinates).
    "https://github.com/AndroidIDEOfficial/androidide-tools/releases/download/v34.0.4/build-tools-34.0.4-aarch64.tar.xz",
)
val aapt2UrlProperty = providers.gradleProperty("apkfactoryAapt2Url")

tasks.register("downloadAapt2Runtime") {
    group = "apkfactory"
    description = "Downloads the arm64 aapt2 binary and packages it as jniLibs/arm64-v8a/libaapt2.so."
    outputs.file(aapt2Output)

    doLast {
        if (aapt2Output.isFile && aapt2Output.length() > 1_000_000L) {
            logger.lifecycle("aapt2 runtime already provisioned (${aapt2Output.length() / 1024} KiB).")
            return@doLast
        }
        val url = aapt2UrlProperty.orNull ?: aapt2Sources.first()
        val archive = File(
            layout.buildDirectory.dir("aapt2-runtime").get().asFile,
            "download.tar.xz"
        ).also { it.parentFile.mkdirs() }
        logger.lifecycle("Downloading arm64 aapt2 from $url …")
        val connection = URI(url).toURL().openConnection()
        connection.connectTimeout = 30_000
        connection.readTimeout = 300_000
        connection.setRequestProperty("User-Agent", "UniversalApkFactory/2.0")
        connection.getInputStream().use { input ->
            FileOutputStream(archive).use { output -> input.copyTo(output) }
        }

        // Minimal tar reader: extract only the entry named "aapt2".
        org.tukaani.xz.XZInputStream(archive.inputStream().buffered()).use { xz ->
            val header = ByteArray(512)
            var found = false
            while (!found) {
                var read = 0
                while (read < 512) {
                    val n = xz.read(header, read, 512 - read)
                    if (n < 0) throw GradleException("aapt2 entry not found in $url")
                    read += n
                }
                val name = String(header, 0, 100).trimEnd('\u0000')
                if (name.isEmpty()) break
                val sizeText = String(header, 124, 12).trim('\u0000', ' ')
                val size = if (sizeText.isEmpty()) 0L else sizeText.toLong(8)
                val type = header[156]
                if (type == '0'.code.toByte() && name.substringAfterLast('/') == "aapt2") {
                    aapt2Output.parentFile.mkdirs()
                    FileOutputStream(aapt2Output).use { output ->
                        var remaining = size
                        val buffer = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val n = xz.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            remaining -= n
                        }
                    }
                    found = true
                } else {
                    var remaining = size
                    val buffer = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val n = xz.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (n < 0) break
                        remaining -= n
                    }
                }
                // consume padding
                val padding = ((512 - (size % 512)) % 512).toInt()
                if (padding > 0 && size > 0) {
                    var skipped = 0
                    val sink = ByteArray(512)
                    while (skipped < padding) {
                        val n = xz.read(sink, 0, padding - skipped)
                        if (n < 0) break
                        skipped += n
                    }
                }
            }
            if (!found) throw GradleException("aapt2 entry not found in $url")
        }
        archive.delete()
        logger.lifecycle("aapt2 runtime provisioned: ${aapt2Output.relativeTo(rootDir)} (${aapt2Output.length() / 1024} KiB)")
    }
}

tasks.named("preBuild") {
    dependsOn(webviewShellTask, pythonShellTask, "downloadAapt2Runtime")
}

// Convenience: `./gradlew packageShells` re-exports both shell APKs.
tasks.register("packageShells") {
    group = "apkfactory"
    description = "Compiles and packages both shell APKs into :app assets."
    dependsOn(webviewShellTask, pythonShellTask)
}
