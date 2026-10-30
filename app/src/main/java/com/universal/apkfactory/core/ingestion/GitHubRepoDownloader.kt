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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GitHub Repository Downloader (Module 1, ingestion channel 3).
 *
 * Parses `https://github.com/{owner}/{repo}` URLs — including
 * `/tree/{branch}`, `/tree/{tag}`, `/releases/tag/{tag}` and
 * `/archive/refs/...` variants — resolves the repository's default branch
 * through the GitHub REST API when the URL does not name a ref, and streams
 * the zipball from `codeload.github.com` into the workspace cache with
 * byte-level progress reporting.
 *
 * The zipball is downloaded through a raw TCP stream (OkHttp) and is never
 * loaded into memory; it lands directly on disk.
 */
class GitHubRepoDownloader(
    private val client: OkHttpClient = defaultClient(),
) {

    /** A parsed GitHub repository reference. */
    data class RepoRef(
        val owner: String,
        val repository: String,
        /** Branch or tag; `null` means "use the repository default branch". */
        val ref: String? = null,
        /** True when [ref] is known to be a tag (release). */
        val refIsTag: Boolean = false,
    ) {
        val slug: String get() = "$owner/$repository"
    }

    /** Progress callback: bytes downloaded so far and total (-1 when unknown). */
    fun interface ProgressListener {
        fun onProgress(bytesDownloaded: Long, totalBytes: Long)
    }

    class GitHubDownloadException(
        message: String,
        /** HTTP status of the failing response; 0 when not an HTTP error. */
        val responseCode: Int = 0,
        cause: Throwable? = null,
    ) : IOException(message, cause)

    /* ------------------------------------------------------------------ */
    /* URL parsing                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * Parses a GitHub URL into a [RepoRef].
     *
     * Accepted shapes:
     * ```
     * https://github.com/{owner}/{repo}
     * https://github.com/{owner}/{repo}/tree/{branch}
     * https://github.com/{owner}/{repo}/tree/{branch}/{...subpath}
     * https://github.com/{owner}/{repo}/releases/tag/{tag}
     * https://github.com/{owner}/{repo}/archive/refs/heads/{branch}.zip
     * https://github.com/{owner}/{repo}/archive/refs/tags/{tag}.zip
     * https://github.com/{owner}/{repo}.git
     * github.com/{owner}/{repo}
     * {owner}/{repo}          (shorthand)
     * {owner}/{repo}@{branch} (shorthand with ref)
     * ```
     *
     * URLs pointing at hosts other than github.com are rejected.
     *
     * @return the parsed reference, or `null` when [input] does not reference
     *         a GitHub repository.
     */
    fun parseRepositoryUrl(input: String): RepoRef? {
        val cleaned = input.trim()
            .substringBefore('#')
            .substringBefore('?')
            .trim()
        if (cleaned.isEmpty()) return null

        // --- Extract host (when a scheme is present) ---------------------
        var host = ""
        var path = cleaned
        val schemeIndex = cleaned.indexOf("://")
        if (schemeIndex >= 0) {
            val afterScheme = cleaned.substring(schemeIndex + 3)
            host = afterScheme.substringBefore('/').lowercase()
            path = afterScheme.substringAfter('/', missingDelimiterValue = "")
        } else {
            val bare = cleaned.lowercase()
            when {
                bare.startsWith("github.com/") -> {
                    host = "github.com"
                    path = cleaned.substring("github.com/".length)
                }

                bare.startsWith("www.github.com/") -> {
                    host = "github.com"
                    path = cleaned.substring("www.github.com/".length)
                }

                bare.startsWith("m.github.com/") -> {
                    host = "github.com"
                    path = cleaned.substring("m.github.com/".length)
                }
            }
        }

        val segments = path.split('/')
            .map { segment -> segment.trim() }
            .filter { segment -> segment.isNotEmpty() }
        if (segments.isEmpty()) return null

        // --- Shorthand `{owner}/{repo}` / `{owner}/{repo}@{ref}` ----------
        val isGitHubHost = host == "github.com" || host == "www.github.com"
        if (!isGitHubHost) {
            if (host.isNotEmpty() || segments.size != 2) return null
            val owner = segments[0]
            val repoPart = segments[1].removeSuffix(".git")
            val atRef = repoPart.substringAfter('@', missingDelimiterValue = "")
            val repository = repoPart.substringBefore('@')
            if (!isValidSegment(owner) || !isValidSegment(repository)) return null
            return if (atRef.isNotEmpty()) {
                RepoRef(owner, repository, ref = atRef)
            } else {
                RepoRef(owner, repository)
            }
        }

        // --- Full github.com URLs ----------------------------------------
        if (segments.size < 2) return null
        val owner = segments[0]
        val repository = segments[1].removeSuffix(".git")
        if (!isValidSegment(owner) || !isValidSegment(repository)) return null

        return when {
            // /tree/{ref} or /tree/{ref}/{subpath}
            segments.size >= 4 && segments[2] == "tree" ->
                RepoRef(owner, repository, ref = segments[3])

            // /releases/tag/{tag}
            segments.size >= 5 && segments[2] == "releases" && segments[3] == "tag" ->
                RepoRef(owner, repository, ref = segments[4], refIsTag = true)

            // /archive/refs/heads/{branch}.zip or /archive/refs/tags/{tag}.zip
            segments.size >= 6 && segments[2] == "archive" && segments[3] == "refs" ->
                parseArchiveRef(segments)?.copy(owner = owner, repository = repository)
                    ?: RepoRef(owner, repository)

            else -> RepoRef(owner, repository)
        }
    }

    /**
     * `segments` = `{owner}/{repo}/archive/refs/{heads|tags}/{ref...}.zip`.
     * Slashes inside the ref (e.g. `feature/x`) are preserved.
     */
    private fun parseArchiveRef(segments: List<String>): RepoRef? {
        val kind = segments[4]
        val rawRef = segments.drop(5).joinToString("/")
        val ref = rawRef.removeSuffix(".zip").removeSuffix(".tar.gz")
        if (ref.isEmpty()) return null
        return when (kind) {
            "heads" -> RepoRef("", "", ref = ref)
            "tags" -> RepoRef("", "", ref = ref, refIsTag = true)
            else -> null
        }
    }

    private fun isValidSegment(segment: String): Boolean =
        segment.isNotEmpty() && !segment.contains(' ') && segment.length <= 100

    /* ------------------------------------------------------------------ */
    /* Download                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Downloads the repository zipball into [destination].
     *
     * When [ref] is `null`, the repository's default branch is resolved via
     * `https://api.github.com/repos/{owner}/{repo}`. The archive itself is
     * streamed from `codeload.github.com` (no API rate limit, no
     * authentication required for public repositories).
     *
     * @return the fully resolved [RepoRef] (with the default branch filled
     *         in) for logging/UI purposes.
     */
    suspend fun downloadZipball(
        ref: RepoRef,
        destination: File,
        onProgress: ProgressListener = ProgressListener { _, _ -> },
    ): RepoRef = withContext(Dispatchers.IO) {
        val resolved = if (ref.ref != null) ref else resolveDefaultBranch(ref)
        destination.parentFile?.mkdirs()
        val candidates: List<String> = if (resolved.refIsTag) {
            listOf(codeloadTagUrl(resolved), codeloadBranchUrl(resolved))
        } else {
            listOf(codeloadBranchUrl(resolved), codeloadTagUrl(resolved))
        }

        var lastError: IOException? = null
        for (url in candidates) {
            try {
                downloadTo(url, destination, onProgress)
                return@withContext resolved
            } catch (error: GitHubDownloadException) {
                lastError = error
                if (error.responseCode != 404) throw error
                // Fall through: maybe the ref is a tag (or vice versa).
            }
        }
        throw GitHubDownloadException(
            "Repository ${resolved.slug} not reachable for ref " +
                "'${resolved.ref}' (checked branch and tag endpoints).",
            0,
            lastError
        )
    }

    /** Convenience overload accepting a raw URL. */
    suspend fun downloadFromUrl(
        repositoryUrl: String,
        destination: File,
        onProgress: ProgressListener = ProgressListener { _, _ -> },
    ): RepoRef {
        val ref = parseRepositoryUrl(repositoryUrl)
            ?: throw GitHubDownloadException(
                "'$repositoryUrl' is not a recognizable GitHub repository URL."
            )
        return downloadZipball(ref, destination, onProgress)
    }

    /* ------------------------------------------------------------------ */
    /* Internals                                                           */
    /* ------------------------------------------------------------------ */

    @Throws(IOException::class)
    private suspend fun resolveDefaultBranch(ref: RepoRef): RepoRef {
        val request = Request.Builder()
            .url("https://api.github.com/repos/${ref.owner}/${ref.repository}")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                404 -> throw GitHubDownloadException(
                    "GitHub repository '${ref.slug}' was not found. " +
                        "Is it private? Private repositories need a " +
                        "token in Settings."
                )

                403 -> throw GitHubDownloadException(
                    "GitHub API rate limit reached (unauthenticated " +
                        "requests are limited per IP). Try again in a " +
                        "few minutes or specify a branch directly."
                )

                else -> if (!response.isSuccessful) {
                    throw GitHubDownloadException(
                        "GitHub API error HTTP ${response.code} while " +
                            "resolving '${ref.slug}'."
                    )
                }
            }
            val body = response.body?.string()
                ?: throw GitHubDownloadException("Empty GitHub API response.")
            val defaultBranch = JSONObject(body).optString("default_branch")
            if (defaultBranch.isBlank()) {
                throw GitHubDownloadException(
                    "GitHub API returned no default branch for '${ref.slug}'."
                )
            }
            return ref.copy(ref = defaultBranch)
        }
    }

    private fun codeloadBranchUrl(ref: RepoRef): String =
        "https://codeload.github.com/${ref.owner}/${ref.repository}/zip/refs/heads/${ref.ref}"

    private fun codeloadTagUrl(ref: RepoRef): String =
        "https://codeload.github.com/${ref.owner}/${ref.repository}/zip/refs/tags/${ref.ref}"

    @Throws(IOException::class)
    private fun downloadTo(
        url: String,
        destination: File,
        onProgress: ProgressListener,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw GitHubDownloadException(
                    "Download failed with HTTP ${response.code} for $url",
                    response.code,
                )
            }
            val body = response.body
                ?: throw GitHubDownloadException("Empty response body from $url")
            val totalBytes = body.contentLength()
            var downloaded = 0L
            val tempFile = File(destination.parentFile, destination.name + ".part")
            try {
                body.byteStream().buffered(64 * 1024).use { input ->
                    FileOutputStream(tempFile).buffered(64 * 1024).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded > totalBytes && totalBytes != -1L) {
                                throw GitHubDownloadException(
                                    "Download stream exceeded the announced size; aborting."
                                )
                            }
                            onProgress.onProgress(downloaded, totalBytes)
                        }
                    }
                }
                if (downloaded == 0L) {
                    throw GitHubDownloadException("Downloaded archive is empty: $url")
                }
                if (totalBytes != -1L && downloaded != totalBytes) {
                    throw GitHubDownloadException(
                        "Download truncated ($downloaded of $totalBytes bytes)."
                    )
                }
                if (!tempFile.renameTo(destination)) {
                    tempFile.copyTo(destination, overwrite = true)
                    tempFile.delete()
                }
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }
    }

    companion object {
        private const val USER_AGENT =
            "UniversalApkFactory/2.0 (Android; +https://github.com/lm7hd-ai/Apk)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
