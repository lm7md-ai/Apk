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
package com.universal.apkfactory.pipeline

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.universal.apkfactory.core.ingestion.IngestionManager
import com.universal.apkfactory.core.ingestion.ZipRootNormalizer
import com.universal.apkfactory.core.model.BuildMode
import com.universal.apkfactory.core.model.DetectedProject
import com.universal.apkfactory.core.model.IngestedSource
import com.universal.apkfactory.core.model.IngestionOrigin
import com.universal.apkfactory.core.model.ProjectBuildConfig
import com.universal.apkfactory.core.signer.ApkAlignerAndSigner
import com.universal.apkfactory.engine.cloud.GitHubCloudBuilder
import com.universal.apkfactory.engine.gradle.NativeGradleService
import com.universal.apkfactory.engine.gradle.ToolchainManager
import com.universal.apkfactory.engine.patcher.FastApkPatcherAndEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Part 5 — pipeline: the single entry point the ViewModel talks to.
 *
 * Routes a [DetectedProject] to the right execution engine:
 *
 *  * **Fast on-device patching** (`runFastBuild`) for MODE_FAST_WEB,
 *    MODE_FAST_PYTHON and MODE_APK_EDITOR — shell injection via
 *    engine-patcher, then 4-byte alignment + V1/V2/V3 signing via
 *    core-signer. Everything happens locally in seconds.
 *  * **Cloud CI** (`runCloudBuild`) for MODE_CLOUD_WEB_OR_HYBRID,
 *    MODE_CLOUD_FLUTTER and (optionally) MODE_NATIVE_GRADLE — the project
 *    is pushed to an ephemeral GitHub runner repo; the artifact comes back
 *    and is finished locally (web bundle -> WebView shell, APK ->
 *    sanitize + align + sign).
 *  * **On-device native compilation** (`startNativeBuild`) for
 *    MODE_NATIVE_GRADLE — hands the project to [NativeGradleService],
 *    which compiles it with the ARM64 toolchain; the ViewModel observes
 *    the service state and calls [finalizeExternalApk] on success.
 */
class BuildOrchestrator(
    private val context: Context,
    okHttpClient: okhttp3.OkHttpClient? = null,
) {

    class BuildFailedException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    private val appContext: Context = context.applicationContext
    private val client: okhttp3.OkHttpClient = okHttpClient
        ?: (appContext as com.universal.apkfactory.ApkFactoryApplication).okHttpClient

    private val pythonRuntime: com.universal.apkfactory.core.python.PythonRuntimeManager by lazy {
        com.universal.apkfactory.core.python.PythonRuntimeManager(appContext, client)
    }

    private val patcherEngine: FastApkPatcherAndEditor by lazy {
        FastApkPatcherAndEditor(appContext, pythonRuntime)
    }

    private val signerEngine: ApkAlignerAndSigner by lazy {
        ApkAlignerAndSigner(appContext)
    }

    private val cloudBridge: GitHubCloudBuilder by lazy {
        GitHubCloudBuilder(appContext, client)
    }

    /* ------------------------------------------------------------------ */
    /* Fast on-device path (shells + APK editor)                           */
    /* ------------------------------------------------------------------ */

    /**
     * Runs the instant build: shell patch (or APK edit) followed by
     * alignment and signing. Returns the installable APK in
     * `Workspace/out`.
     */
    suspend fun runFastBuild(
        detected: DetectedProject,
        config: ProjectBuildConfig,
        iconBitmap: android.graphics.Bitmap? = null,
        onLog: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val target = when (detected.mode) {
            BuildMode.MODE_APK_EDITOR ->
                FastApkPatcherAndEditor.PatchTarget.ExistingApk(
                    detected.source.workspaceFile
                )

            BuildMode.MODE_FAST_WEB ->
                FastApkPatcherAndEditor.PatchTarget.Shell(
                    FastApkPatcherAndEditor.ShellKind.WEBVIEW
                )

            BuildMode.MODE_FAST_PYTHON ->
                FastApkPatcherAndEditor.PatchTarget.Shell(
                    FastApkPatcherAndEditor.ShellKind.PYTHON
                )

            else -> throw BuildFailedException(
                "${detected.mode} projects need a compiler — use the cloud or " +
                    "on-device native build instead"
            )
        }
        val payload: IngestedSource? =
            if (detected.mode == BuildMode.MODE_APK_EDITOR) null else detected.source

        val buildDir = IngestionManager.Workspace.build(appContext)
        val unaligned = File(buildDir, "patched-${System.currentTimeMillis()}.apk")
        onLog("Stage 1/2: patching ${detected.source.displayName} …")
        val outcome = patcherEngine.patch(
            target = target,
            config = config,
            payload = payload,
            output = unaligned,
            detected = detected,
            iconBitmap = iconBitmap,
            onLog = onLog,
        )
        onLog("Stage 2/2: aligning (4-byte) and signing (V1 + V2 + V3) …")
        val signed = signerEngine.alignAndSign(
            unalignedApk = unaligned,
            packageName = outcome.packageName,
            onLog = onLog,
        )
        onLog("Installable APK ready: ${signed.absolutePath}")
        signed
    }

    /* ------------------------------------------------------------------ */
    /* Cloud CI path                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Builds on GitHub Actions and finishes the artifact locally.
     * Returns the installable APK in `Workspace/out`.
     */
    suspend fun runCloudBuild(
        detected: DetectedProject,
        config: ProjectBuildConfig,
        iconBitmap: android.graphics.Bitmap? = null,
        onLog: (String) -> Unit = {},
        onProgress: (GitHubCloudBuilder.CloudProgress) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val stack = cloudStackFor(detected)
        onLog("[cloud] Stack: $stack — preparing the upload tree …")
        val projectDir = prepareNormalizedProjectDir(detected, onLog)

        val result = cloudBridge.buildInCloud(projectDir, stack, onLog, onProgress)

        when {
            result.webRoot != null -> {
                onLog("[cloud] Web bundle received — wrapping into the WebView shell …")
                val buildDir = IngestionManager.Workspace.build(appContext)
                val unaligned = File(buildDir, "cloud-web-${System.currentTimeMillis()}.apk")
                // The artifact root already contains index.html; make the
                // patcher treat the payload root as the web root.
                val detectedForShell = detected.copy(
                    webRootDir = null,
                    hasPrebuiltWebBundle = true,
                )
                val payload = IngestedSource.Folder(
                    result.webRoot,
                    IngestionOrigin.GITHUB_DOWNLOAD,
                )
                val outcome = patcherEngine.patch(
                    target = FastApkPatcherAndEditor.PatchTarget.Shell(
                        FastApkPatcherAndEditor.ShellKind.WEBVIEW
                    ),
                    config = config,
                    payload = payload,
                    output = unaligned,
                    detected = detectedForShell,
                    iconBitmap = iconBitmap,
                    onLog = onLog,
                )
                signerEngine.alignAndSign(
                    unalignedApk = unaligned,
                    packageName = outcome.packageName,
                    onLog = onLog,
                )
            }

            result.apkFiles.isNotEmpty() -> {
                val apk = result.apkFiles.maxByOrNull { it.length() }
                    ?: throw BuildFailedException("Cloud artifact contained no APK")
                onLog(
                    "[cloud] APK received (${apk.length() / 1024} KiB) — " +
                        "sanitizing, aligning and signing locally …"
                )
                finalizeExternalApk(apk, config, onLog)
            }

            else -> throw BuildFailedException(
                "The cloud artifact contained neither an APK nor a web bundle"
            )
        }
    }

    /** Maps the detected mode to a CI recipe. */
    fun cloudStackFor(detected: DetectedProject): GitHubCloudBuilder.CloudStack =
        when (detected.mode) {
            BuildMode.MODE_CLOUD_WEB_OR_HYBRID ->
                if (detected.nodeFrameworks.any { it.equals("capacitor", ignoreCase = true) }) {
                    GitHubCloudBuilder.CloudStack.CAPACITOR
                } else {
                    GitHubCloudBuilder.CloudStack.WEB_NODE
                }

            BuildMode.MODE_CLOUD_FLUTTER -> GitHubCloudBuilder.CloudStack.FLUTTER
            BuildMode.MODE_NATIVE_GRADLE -> GitHubCloudBuilder.CloudStack.GRADLE
            else -> throw BuildFailedException(
                "${detected.mode} projects are built instantly on-device; " +
                    "cloud CI is not applicable"
            )
        }

    /* ------------------------------------------------------------------ */
    /* On-device native path                                               */
    /* ------------------------------------------------------------------ */

    /**
     * Materializes the project sources into a plain directory the native
     * toolchain (or the cloud uploader) can consume: zip archives are
     * extracted with their redundant wrapper directory stripped, folders
     * are used in place.
     */
    suspend fun prepareNormalizedProjectDir(
        detected: DetectedProject,
        onLog: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        when (val source = detected.source) {
            is IngestedSource.Folder -> source.workspaceFile

            is IngestedSource.ZipArchive -> {
                val dir = File(
                    IngestionManager.Workspace.build(appContext),
                    "src-${System.currentTimeMillis()}-${dirSafe(detected.source.displayName)}",
                )
                dir.mkdirs()
                val prefix = ZipRootNormalizer.detectRootPrefix(source.workspaceFile).orEmpty()
                ZipFile(source.workspaceFile).use { zip ->
                    for (entry in zip.entries()) {
                        if (entry.isDirectory) continue
                        var name = entry.name
                        if (prefix.isNotEmpty()) {
                            if (!name.startsWith(prefix)) continue
                            name = name.removePrefix(prefix)
                        }
                        if (name.isEmpty() || ZipRootNormalizer.isPayloadJunkEntry(name)) {
                            continue
                        }
                        val target = File(dir, name)
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output ->
                                input.copyTo(output, 64 * 1024)
                            }
                        }
                    }
                }
                onLog("Extracted ${detected.source.displayName} into ${dir.name}")
                dir
            }

            is IngestedSource.ApkFile -> throw BuildFailedException(
                "An APK cannot be compiled from source; use the APK Editor path instead"
            )
        }
    }

    /** Starts the on-device Gradle build in [NativeGradleService]. */
    fun startNativeBuild(projectDir: File, tasks: List<String> = listOf("assembleDebug")) {
        NativeGradleService.resetState()
        val intent = Intent(context, NativeGradleService::class.java)
            .setAction(NativeGradleService.ACTION_START)
            .putExtra(NativeGradleService.EXTRA_PROJECT_DIR, projectDir.absolutePath)
            .putStringArrayListExtra(NativeGradleService.EXTRA_TASKS, ArrayList(tasks))
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Turns an externally produced APK (on-device Gradle output or cloud
     * artifact) into an installable one: strips stale JAR signatures,
     * 4-byte-aligns and signs with the persistent per-package keystore.
     */
    suspend fun finalizeExternalApk(
        apk: File,
        config: ProjectBuildConfig,
        onLog: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val sanitized = File(
            IngestionManager.Workspace.build(appContext),
            apk.nameWithoutExtension + ".sanitized.apk",
        )
        signerEngine.stripV1SignatureEntries(apk, sanitized, onLog)
        signerEngine.alignAndSign(
            unalignedApk = sanitized,
            packageName = config.packageName,
            onLog = onLog,
        )
    }

    /* ------------------------------------------------------------------ */
    /* Shared helpers                                                      */
    /* ------------------------------------------------------------------ */

    private fun dirSafe(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48).ifBlank { "project" }
}
