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
package com.universal.apkfactory.core.ingestion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.universal.apkfactory.core.model.IngestedSource
import com.universal.apkfactory.core.model.IngestionOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Multi-Source Ingestion manager (Module 1).
 *
 * Every ingestion channel funnels through this class and lands in the
 * factory workspace (`files/workspace/`):
 *
 *  * SAF file picker / Share Sheet / ACTION_VIEW streams → single file copy
 *    with central-directory validation (zip vs. apk is decided by content,
 *    not by file extension).
 *  * SAF folder picker → recursive DocumentFile copy.
 *  * GitHub repository URL → [GitHubRepoDownloader] zipball streaming.
 *
 * All methods are main-safe (they confine blocking IO to Dispatchers.IO).
 */
class IngestionManager(private val context: Context) {

    /* ------------------------------------------------------------------ */
    /* Workspace layout                                                    */
    /* ------------------------------------------------------------------ */

    object Workspace {
        fun root(context: Context): File =
            File(context.filesDir, "workspace").apply { mkdirs() }

        fun ingest(context: Context): File =
            File(root(context), "ingest").apply { mkdirs() }

        fun build(context: Context): File =
            File(root(context), "build").apply { mkdirs() }

        fun out(context: Context): File =
            File(root(context), "out").apply { mkdirs() }

        fun keystores(context: Context): File =
            File(context.filesDir, "keys").apply { mkdirs() }

        fun temp(context: Context): File =
            File(context.cacheDir, "build").apply { mkdirs() }
    }

    class IngestionException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    /* ------------------------------------------------------------------ */
    /* Intent dispatch (Share Sheet + ACTION_VIEW)                         */
    /* ------------------------------------------------------------------ */

    /**
     * Inspects an incoming [Intent] (ACTION_SEND / ACTION_VIEW) and decides
     * which ingestion path applies.
     */
    fun describeIntentPayload(intent: Intent): IntentPayload {
        val action = intent.action ?: return IntentPayload.Unhandled
        return when (action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                val sharedTitle = intent.getStringExtra(Intent.EXTRA_TITLE)
                when {
                    stream != null ->
                        IntentPayload.SharedFile(stream, sharedTitle, IngestionOrigin.SHARE_SHEET)

                    !text.isNullOrBlank() ->
                        IntentPayload.SharedText(text)

                    else -> IntentPayload.Unhandled
                }
            }

            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return IntentPayload.Unhandled
                IntentPayload.ViewedFile(uri, IngestionOrigin.SHARE_SHEET)
            }

            else -> IntentPayload.Unhandled
        }
    }

    /** What an incoming intent carries. */
    sealed interface IntentPayload {
        /** No ingestible content in the intent. */
        object Unhandled : IntentPayload

        /** A text share — possibly a GitHub repository URL. */
        data class SharedText(val text: String) : IntentPayload

        /** A stream share (zip / apk). */
        data class SharedFile(
            val uri: Uri,
            val title: String?,
            val origin: IngestionOrigin,
        ) : IntentPayload

        /** An ACTION_VIEW content/file URI (zip / apk). */
        data class ViewedFile(val uri: Uri, val origin: IngestionOrigin) : IntentPayload
    }

    /* ------------------------------------------------------------------ */
    /* Content URI ingestion                                               */
    /* ------------------------------------------------------------------ */

    /**
     * Copies a SAF/Share/VIEW content URI into the workspace and classifies
     * it as a zip archive or an APK by inspecting the archive's central
     * directory (never trusting the file extension alone).
     */
    suspend fun ingestContentUri(
        uri: Uri,
        origin: IngestionOrigin,
        displayNameHint: String? = null,
        onProgress: (bytes: Long) -> Unit = {},
    ): IngestedSource = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = displayNameHint
            ?: queryDisplayName(uri)
            ?: uri.lastPathSegment
            ?: "shared-${System.currentTimeMillis()}"
        val safeName = sanitizeFileName(displayName)

        val staging = File(Workspace.temp(context), "stage-${System.currentTimeMillis()}-$safeName")
        staging.parentFile?.mkdirs()
        try {
            val input = resolver.openInputStream(uri)
                ?: throw IngestionException("Cannot open '$displayName': the provider refused access.")
            input.use { stream ->
                java.io.FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied)
                    }
                    if (copied == 0L) {
                        throw IngestionException("'$displayName' is empty.")
                    }
                }
            }
            classifyArchive(staging, safeName, origin)
        } catch (error: Exception) {
            staging.delete()
            throw error
        }
    }

    /**
     * Classifies a downloaded/copied archive file as APK or ZIP and moves it
     * to its final workspace location.
     */
    fun classifyArchive(
        stagedFile: File,
        displayName: String,
        origin: IngestionOrigin,
    ): IngestedSource {
        val isApk = looksLikeAndroidApk(stagedFile)
        val extension = if (isApk) ".apk" else ".zip"
        val baseName = sanitizeFileName(displayName).substringBeforeLast('.')
        val finalName = baseName.ifBlank { "project-${System.currentTimeMillis()}" }
        val destination = File(Workspace.ingest(context), "$finalName$extension")
        if (destination.exists()) destination.delete()
        if (!stagedFile.renameTo(destination)) {
            stagedFile.copyTo(destination, overwrite = true)
            stagedFile.delete()
        }
        return if (isApk) {
            IngestedSource.ApkFile(destination, origin)
        } else {
            IngestedSource.ZipArchive(destination, origin)
        }
    }

    /** True when the archive contains a binary `AndroidManifest.xml` + dex. */
    private fun looksLikeAndroidApk(file: File): Boolean = try {
        ZipFile(file).use { archive ->
            val hasManifest = archive.getEntry("AndroidManifest.xml") != null
            val hasDex = archive.entries().asSequence()
                .any { entry -> !entry.isDirectory && entry.name.matches(Regex("classes\\d*\\.dex")) }
            hasManifest && hasDex
        }
    } catch (error: IOException) {
        // Not a zip at all — let the caller surface a proper message.
        false
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    } catch (error: Exception) {
        null
    }

    /* ------------------------------------------------------------------ */
    /* Folder ingestion                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Recursively copies a SAF document tree into the workspace.
     * Returns a folder-backed [IngestedSource].
     */
    suspend fun ingestTreeUri(
        treeUri: Uri,
        origin: IngestionOrigin = IngestionOrigin.FOLDER_PICKER,
        onProgress: (filesCopied: Int, totalFiles: Int) -> Unit = { _, _ -> },
    ): IngestedSource.Folder = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IngestionException("The selected folder is no longer available.")
        if (!root.isDirectory) {
            throw IngestionException("The selected document is not a folder.")
        }

        // First pass: enumerate files (for a stable progress denominator).
        val allFiles = ArrayList<Pair<DocumentFile, String>>() // document -> relative path
        fun walk(dir: DocumentFile, relativePath: String) {
            for (child in dir.listFiles()) {
                val childName = child.name ?: continue
                if (ZipRootNormalizer.isPayloadJunkEntry("$relativePath$childName/x")) {
                    continue
                }
                if (child.isDirectory) {
                    walk(child, "$relativePath$childName/")
                } else if (child.isFile) {
                    allFiles.add(child to "$relativePath$childName")
                }
            }
        }
        walk(root, "")
        if (allFiles.isEmpty()) {
            throw IngestionException("The selected folder contains no files.")
        }

        // Second pass: copy.
        val destinationRoot = File(
            Workspace.ingest(context),
            "folder-${System.currentTimeMillis()}-${sanitizeFileName(root.name ?: "project")}"
        )
        destinationRoot.mkdirs()
        var copied = 0
        for ((document, relativePath) in allFiles) {
            val target = File(destinationRoot, relativePath)
            target.parentFile?.mkdirs()
            val input = context.contentResolver.openInputStream(document.uri)
                ?: throw IngestionException("Cannot read '$relativePath'.")
            input.use { stream ->
                java.io.FileOutputStream(target).use { output ->
                    stream.copyTo(output, 64 * 1024)
                }
            }
            copied++
            onProgress(copied, allFiles.size)
        }
        IngestedSource.Folder(destinationRoot, origin)
    }

    /* ------------------------------------------------------------------ */
    /* GitHub ingestion                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Downloads a GitHub repository zipball. The archive is stored in the
     * workspace; the redundant `{repo}-{branch}/` wrapper directory is
     * handled later by [ZipRootNormalizer].
     */
    suspend fun ingestGitHubRepository(
        repositoryUrl: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): IngestedSource.ZipArchive = withContext(Dispatchers.IO) {
        val downloader = GitHubRepoDownloader()
        val staging = File(
            Workspace.temp(context),
            "github-${System.currentTimeMillis()}.zip"
        )
        val resolved = downloader.downloadFromUrl(repositoryUrl, staging, onProgress)
        val finalName = sanitizeFileName(
            "${resolved.owner}-${resolved.repository}-${resolved.ref}.zip"
        )
        val destination = File(Workspace.ingest(context), finalName)
        if (destination.exists()) destination.delete()
        if (!staging.renameTo(destination)) {
            staging.copyTo(destination, overwrite = true)
            staging.delete()
        }
        IngestedSource.ZipArchive(
            workspaceFile = destination,
            origin = IngestionOrigin.GITHUB_DOWNLOAD,
            githubSlug = resolved.slug,
            githubBranch = resolved.ref,
        )
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                             */
    /* ------------------------------------------------------------------ */

    companion object {
        fun sanitizeFileName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                .replace(' ', '_')
                .trim('_', '.')
                .take(120)
                .ifBlank { "project" }
    }
}
