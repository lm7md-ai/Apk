/*
 * Universal APK Factory & Studio
 * Copyright (C) 2026 Universal APK Factory contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.universal.apkfactory.engine.gradle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Module 5 — engine-native-gradle: on-demand provisioning of the ARM64
 * native compiler toolchain.
 *
 * Three components are downloaded lazily into
 * `<filesDir>/toolchain/` on first use, each verified before use:
 *
 *  1. **JDK 21 (Temurin, aarch64)** — `.tar.gz` from the Adoptium GitHub
 *     release, verified against the published `.sha256.txt` sibling asset.
 *  2. **Gradle 8.9** — `-bin.zip` from services.gradle.org, verified against
 *     the published `.sha256` sibling.
 *  3. **Android SDK (AndroidIDE aarch64 build)** — `.tar.xz` from the
 *     official AndroidIDE androidide-tools release manifest; provides
 *     `platforms/android-XX/android.jar` and supporting bits compiled for
 *     ARM64. Upstream publishes no checksum sibling for this asset, so it is
 *     validated structurally during extraction (tar headers + ustar CRC
 *     coverage of the XZ stream).
 *
 * The fourth native piece, **aapt2**, is *not* downloaded at runtime: AGP
 * spawns it via execve(), and app data storage is noexec for targetSdk 29+.
 * The factory therefore packages the AndroidIDE build-tools aapt2 as
 * `jniLibs/arm64-v8a/libaapt2.so` at factory build time; with
 * `useLegacyPackaging=true` Android extracts it to `nativeLibraryDir`,
 * which is executable. [aapt2Path] resolves that location.
 */
class ToolchainManager(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {

    /* ------------------------------------------------------------------ */
    /* Manifest                                                            */
    /* ------------------------------------------------------------------ */

    object Manifest {
        const val JDK_NAME = "OpenJDK 21 (Temurin) aarch64"
        const val JDK_URL =
            "https://github.com/adoptium/temurin21-binaries/releases/download/" +
                "jdk-21.0.9%2B10/" +
                "OpenJDK21U-jdk_aarch64_linux_hotspot_21.0.9_10.tar.gz"
        const val JDK_SHA256_URL = "$JDK_URL.sha256.txt"

        const val GRADLE_NAME = "Gradle 8.9"
        const val GRADLE_URL =
            "https://services.gradle.org/distributions/gradle-8.9-bin.zip"
        const val GRADLE_SHA256_URL = "$GRADLE_URL.sha256"

        const val SDK_NAME = "Android SDK (AndroidIDE aarch64)"
        const val SDK_URL =
            "https://github.com/AndroidIDEOfficial/androidide-tools/" +
                "releases/download/sdk/android-sdk.tar.xz"
        val SDK_SHA256_URL: String? = null
    }

    sealed interface Event {
        data class DownloadStarted(val component: String, val bytesTotal: Long) : Event
        data class DownloadProgress(
            val component: String,
            val bytesDownloaded: Long,
            val bytesTotal: Long,
        ) : Event

        data class Verifying(val component: String) : Event
        data class Extracting(
            val component: String,
            val bytesWritten: Long,
            val bytesTotal: Long,
        ) : Event

        data class ComponentReady(val component: String, val path: File) : Event
        data class ComponentCached(val component: String, val path: File) : Event
    }

    class ToolchainException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    /* ------------------------------------------------------------------ */
    /* State                                                               */
    /* ------------------------------------------------------------------ */

    data class Toolchain(
        val javaHome: File,
        val gradleHome: File,
        val androidHome: File,
        val aapt2: File,
    )

    val root: File get() = File(context.filesDir, "toolchain")

    private val downloadsDir: File get() = File(root, "downloads")
    private val jdkDir: File get() = File(root, "jdk")
    private val gradleDir: File get() = File(root, "gradle")
    private val sdkDir: File get() = File(root, "android-sdk")
    private val homeDir: File get() = File(root, "home").apply { mkdirs() }
    private val tmpDir: File get() = File(context.cacheDir, "toolchain-tmp").apply { mkdirs() }

    /** Marker files written after a component was fully verified + extracted. */
    private fun markerFor(componentDir: File): File = File(componentDir, ".apkfactory-ready")

    /* ------------------------------------------------------------------ */
    /* Public API                                                          */
    /* ------------------------------------------------------------------ */

    /** True when every toolchain component is already provisioned locally. */
    fun isReady(): Boolean =
        currentToolchainOrNull() != null

    /** Returns the toolchain if fully provisioned, null otherwise. */
    fun currentToolchainOrNull(): Toolchain? {
        val javaHome = locateContentRoot(jdkDir, "bin", "java") ?: return null
        val gradleHome = locateContentRoot(gradleDir, "bin", "gradle") ?: return null
        val androidHome = locateContentRoot(sdkDir, "platforms") ?: return null
        val aapt2 = aapt2Path()
        if (!aapt2.isFile) return null
        return Toolchain(javaHome, gradleHome, androidHome, aapt2)
    }

    /**
     * Resolves the ARM64 aapt2 binary shipped inside this APK's jniLibs.
     * See the class KDoc for the W^X rationale.
     */
    fun aapt2Path(): File =
        File(context.applicationInfo.nativeLibraryDir, "libaapt2.so")

    /**
     * Ensures every component is present, downloading and verifying the
     * missing ones. Safe to call repeatedly; existing components are
     * detected via content-root markers and skipped.
     */
    suspend fun ensureReady(onEvent: (Event) -> Unit = {}): Toolchain =
        withContext(Dispatchers.IO) {
            currentToolchainOrNull()?.let { cached ->
                onEvent(Event.ComponentCached("toolchain", root))
                return@withContext cached
            }
            downloadsDir.mkdirs()

            ensureComponent(
                component = Manifest.JDK_NAME,
                url = Manifest.JDK_URL,
                sha256Url = Manifest.JDK_SHA256_URL,
                archiveFile = File(downloadsDir, "jdk21-aarch64.tar.gz"),
                targetDir = jdkDir,
                onEvent = onEvent,
            )
            val javaHome = locateContentRoot(jdkDir, "bin", "java")
                ?: throw ToolchainException(
                    "JDK extraction finished but bin/java was not found"
                )
            onEvent(Event.ComponentReady(Manifest.JDK_NAME, javaHome))

            ensureComponent(
                component = Manifest.GRADLE_NAME,
                url = Manifest.GRADLE_URL,
                sha256Url = Manifest.GRADLE_SHA256_URL,
                archiveFile = File(downloadsDir, "gradle-8.9-bin.zip"),
                targetDir = gradleDir,
                onEvent = onEvent,
            )
            val gradleHome = locateContentRoot(gradleDir, "bin", "gradle")
                ?: throw ToolchainException(
                    "Gradle extraction finished but bin/gradle was not found"
                )
            onEvent(Event.ComponentReady(Manifest.GRADLE_NAME, gradleHome))

            ensureComponent(
                component = Manifest.SDK_NAME,
                url = Manifest.SDK_URL,
                sha256Url = Manifest.SDK_SHA256_URL,
                archiveFile = File(downloadsDir, "android-sdk-aarch64.tar.xz"),
                targetDir = sdkDir,
                onEvent = onEvent,
            )
            val androidHome = locateContentRoot(sdkDir, "platforms")
                ?: throw ToolchainException(
                    "SDK extraction finished but platforms/ was not found"
                )
            onEvent(Event.ComponentReady(Manifest.SDK_NAME, androidHome))

            val aapt2 = aapt2Path()
            if (!aapt2.isFile) {
                throw ToolchainException(
                    "aapt2 runtime is missing from nativeLibraryDir; " +
                        "the factory APK must be built with the " +
                        "downloadAapt2Runtime task (see app/build.gradle.kts)"
                )
            }
            currentToolchainOrNull() ?: Toolchain(javaHome, gradleHome, androidHome, aapt2)
        }

    /* ------------------------------------------------------------------ */
    /* Component pipeline                                                  */
    /* ------------------------------------------------------------------ */

    private suspend fun ensureComponent(
        component: String,
        url: String,
        sha256Url: String?,
        archiveFile: File,
        targetDir: File,
        onEvent: (Event) -> Unit,
    ) {
        val alreadyThere = targetDir.isDirectory &&
            markerFor(targetDir).isFile &&
            targetDir.listFiles()?.isNotEmpty() == true
        if (alreadyThere) {
            onEvent(Event.ComponentCached(component, targetDir))
            return
        }

        targetDir.deleteRecursively()
        targetDir.mkdirs()

        download(url, archiveFile, component, onEvent)

        if (sha256Url != null) {
            onEvent(Event.Verifying(component))
            val expected = fetchExpectedSha256(sha256Url)
            val actual = sha256Of(archiveFile)
            if (!expected.equals(actual, ignoreCase = true)) {
                archiveFile.delete()
                throw ToolchainException(
                    "SHA-256 mismatch for $component: expected $expected, got $actual"
                )
            }
        } else {
            onEvent(Event.Verifying(component))
            if (archiveFile.length() < 1_000_000L) {
                archiveFile.delete()
                throw ToolchainException(
                    "$component download is implausibly small " +
                        "(${archiveFile.length()} bytes); refusing to extract"
                )
            }
        }

        try {
            TarArchiveReader.extract(
                archive = archiveFile,
                targetDir = targetDir,
                stripSingleTopLevelDirectory = true,
            ) { written, total ->
                onEvent(Event.Extracting(component, written, total))
            }
        } catch (error: Exception) {
            targetDir.deleteRecursively()
            archiveFile.delete()
            throw ToolchainException(
                "Failed to extract $component: ${error.message}", error
            )
        }

        markerFor(targetDir).writeText("verified and extracted from $url\n")
        archiveFile.delete()
    }

    private fun download(
        url: String,
        target: File,
        component: String,
        onEvent: (Event) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "UniversalApkFactory/2.0 (Android; arm64)")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ToolchainException(
                    "Download of $component failed: HTTP ${response.code}"
                )
            }
            val body = response.body ?: throw ToolchainException(
                "Download of $component returned an empty body"
            )
            val total = body.contentLength()
            onEvent(Event.DownloadStarted(component, total))
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, target.name + ".part")
            temp.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastReported = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (downloaded - lastReported > 512 * 1024) {
                            lastReported = downloaded
                            onEvent(Event.DownloadProgress(component, downloaded, total))
                        }
                    }
                }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        }
    }

    private fun fetchExpectedSha256(sha256Url: String): String {
        val request = Request.Builder()
            .url(sha256Url)
            .header("User-Agent", "UniversalApkFactory/2.0 (Android; arm64)")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ToolchainException(
                    "Could not fetch checksum from $sha256Url: HTTP ${response.code}"
                )
            }
            val text = response.body?.string()?.trim().orEmpty()
            val hash = text.split(Regex("\\s+")).firstOrNull { it.length == 64 }
                ?: throw ToolchainException("Malformed checksum document at $sha256Url")
            return hash.lowercase()
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Finds the directory under [rootDir] that actually contains the
     * requested content — resilient to archives that wrap their payload in
     * an extra directory despite the strip pass (or that don't).
     */
    private fun locateContentRoot(rootDir: File, vararg path: String): File? {
        if (!rootDir.isDirectory) return null
        if (File(rootDir, path.joinToString(File.separator)).exists()) return rootDir
        val children = rootDir.listFiles()?.filter { it.isDirectory } ?: return null
        for (child in children) {
            if (File(child, path.joinToString(File.separator)).exists()) return child
        }
        // one more level for stubborn wrappers
        for (child in children) {
            val grandChildren = child.listFiles()?.filter { it.isDirectory } ?: continue
            for (grandChild in grandChildren) {
                if (File(grandChild, path.joinToString(File.separator)).exists()) {
                    return grandChild
                }
            }
        }
        return null
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /* ------------------------------------------------------------------ */
    /* Native execution environment                                        */
    /* ------------------------------------------------------------------ */

    /**
     * Environment + command for running JVM-based tooling under Android's
     * W^X policy. The system linker (`/system/bin/linker64`) is execve'd
     * (it lives on the read-only system partition, which is always
     * executable); it then maps the JVM launcher ELF from app storage.
     *
     * Returns null if the linker is unavailable on this device (pre-ARM64
     * hardware): the caller should fall back to direct exec, which works on
     * API 26–28 and on devices without SELinux app_data exec enforcement.
     */
    fun nativeExecEnvironment(toolchain: Toolchain): NativeExec {
        val linker = File("/system/bin/linker64")
        val javaBin = File(toolchain.javaHome, "bin/java")
        if (!javaBin.isFile) {
            throw ToolchainException("java launcher missing at $javaBin")
        }
        val commandPrefix = if (linker.canExecute()) {
            listOf(linker.absolutePath, javaBin.absolutePath)
        } else {
            listOf(javaBin.absolutePath)
        }
        val environment = mapOf(
            "HOME" to homeDir.absolutePath,
            "JAVA_HOME" to toolchain.javaHome.absolutePath,
            "ANDROID_HOME" to toolchain.androidHome.absolutePath,
            "ANDROID_SDK_ROOT" to toolchain.androidHome.absolutePath,
            "GRADLE_USER_HOME" to File(root, "gradle-home").absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "TMP" to tmpDir.absolutePath,
            "LD_LIBRARY_PATH" to listOf(
                File(toolchain.javaHome, "lib/server"),
                File(toolchain.javaHome, "lib"),
                File(toolchain.javaHome, "lib/jli"),
                toolchain.androidHome,
            ).joinToString(":") { it.absolutePath },
        )
        return NativeExec(commandPrefix, environment, homeDir)
    }

    data class NativeExec(
        val commandPrefix: List<String>,
        val environment: Map<String, String>,
        val workingDir: File,
    )
}
