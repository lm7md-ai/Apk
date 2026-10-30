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
package com.universal.apkfactory.engine.cloud

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Module 6 — engine-cloud-bridge: compiles heavy stacks on GitHub's free
 * `ubuntu-latest` runners using the user's Personal Access Token.
 *
 * Flow (spec: "cloud CI bridge"):
 *
 *  1. PAT is stored in `EncryptedSharedPreferences` (hardware-backed
 *     AES-256-GCM master key, AES256-SIV value encryption).
 *  2. An **ephemeral private repo** `universal-apk-factory-runner` is created.
 *  3. The normalized project tree is pushed via the Git Data API (blobs ->
 *     trees -> commit -> ref) together with a generated
 *     `.github/workflows/universal_build.yml` tailored to the detected
 *     stack (pure web, Capacitor, Flutter, or Gradle).
 *  4. The Actions run is polled every 5 s; job steps are mapped to UI
 *     progress.
 *  5. On success the artifact zip is downloaded and extracted; for APK
 *     stacks the factory then sanitizes, 4-byte-aligns and V1/V2/V3-signs
 *     the binary locally (core-signer). Web stacks are wrapped into the
 *     WebView shell locally (engine-patcher).
 *  6. The ephemeral repo is deleted. (On failure it is kept so the user
 *     can inspect the run log from `runUrl`; it is deleted at the start of
 *     the next cloud build.)
 *
 * The PAT must be a classic token with the `repo`, `workflow` and
 * `delete_repo` scopes (fine-grained tokens are accepted if they grant
 * equivalent permissions, but cannot be detected via `X-OAuth-Scopes`).
 */
class GitHubCloudBuilder(
    context: Context,
    private val httpClient: OkHttpClient,
) {

    /* ------------------------------------------------------------------ */
    /* Public model                                                        */
    /* ------------------------------------------------------------------ */

    /** Which CI recipe to generate for the detected stack. */
    enum class CloudStack { WEB_NODE, CAPACITOR, FLUTTER, GRADLE }

    enum class CloudStage { PREPARING, UPLOADING, QUEUED, RUNNING, ARTIFACT, COMPLETE }

    data class CloudProgress(
        val stage: CloudStage,
        val message: String,
        val uploaded: Int = 0,
        val uploadTotal: Int = 0,
        val workflowStep: String? = null,
        val stepNumber: Int = 0,
        val stepTotal: Int = 0,
        val runUrl: String? = null,
        val elapsedSeconds: Int = 0,
    )

    data class CloudBuildResult(
        val artifactDir: File,
        val runUrl: String,
        /** Populated for APK stacks (CAPACITOR / FLUTTER / GRADLE). */
        val apkFiles: List<File>,
        /** Populated for WEB_NODE: the directory containing index.html. */
        val webRoot: File?,
    )

    data class TokenInfo(val login: String, val scopes: List<String>)

    class GitHubApiException(
        val httpCode: Int,
        message: String,
        val documentedUrl: String? = null,
    ) : IOException(message)

    class CloudBuildException(message: String, val runUrl: String? = null) : IOException(message)

    /* ------------------------------------------------------------------ */
    /* REST payload models (unknown fields tolerated)                      */
    /* ------------------------------------------------------------------ */

    @Serializable
    private data class RestUser(val login: String = "")

    @Serializable
    private data class RestRepo(
        val id: Long = 0,
        val name: String = "",
        @SerialName("full_name") val fullName: String = "",
        val private: Boolean = false,
        @SerialName("default_branch") val defaultBranch: String = "main",
    )

    @Serializable
    private data class RestBlobRef(val sha: String = "")

    @Serializable
    private data class RestTree(val sha: String = "")

    @Serializable
    private data class RestCommit(val sha: String = "")

    @Serializable
    private data class RestWorkflowRun(
        val id: Long = 0,
        val name: String = "",
        val status: String = "",
        val conclusion: String? = null,
        @SerialName("head_sha") val headSha: String = "",
        @SerialName("html_url") val htmlUrl: String = "",
    )

    @Serializable
    private data class RestWorkflowRuns(
        @SerialName("total_count") val totalCount: Int = 0,
        @SerialName("workflow_runs") val workflowRuns: List<RestWorkflowRun> = emptyList(),
    )

    @Serializable
    private data class RestStep(
        val name: String = "",
        val status: String = "",
        val conclusion: String? = null,
        val number: Int = 0,
    )

    @Serializable
    private data class RestJob(
        val id: Long = 0,
        val name: String = "",
        val status: String = "",
        val conclusion: String? = null,
        val steps: List<RestStep> = emptyList(),
    )

    @Serializable
    private data class RestJobs(
        @SerialName("total_count") val totalCount: Int = 0,
        val jobs: List<RestJob> = emptyList(),
    )

    @Serializable
    private data class RestArtifact(
        val id: Long = 0,
        val name: String = "",
        @SerialName("size_in_bytes") val sizeInBytes: Long = 0,
        val expired: Boolean = false,
        @SerialName("archive_download_url")
        val archiveDownloadUrl: String = "",
    )

    @Serializable
    private data class RestArtifacts(
        @SerialName("total_count") val totalCount: Int = 0,
        val artifacts: List<RestArtifact> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /* ------------------------------------------------------------------ */
    /* Token storage                                                       */
    /* ------------------------------------------------------------------ */

    private val securePrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "apkfactory_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun hasToken(): Boolean = !getToken().isNullOrBlank()

    fun getToken(): String? = securePrefs.getString(KEY_PAT, null)?.takeIf { it.isNotBlank() }

    fun saveToken(token: String) {
        securePrefs.edit().putString(KEY_PAT, token.trim()).apply()
    }

    fun clearToken() {
        securePrefs.edit().remove(KEY_PAT).apply()
    }

    /** Calls GET /user to verify the PAT; returns login + granted scopes. */
    suspend fun verifyToken(token: String): TokenInfo = withContext(Dispatchers.IO) {
        val response = api("GET", "https://api.github.com/user", token)
        if (!response.isSuccessful) {
            throw GitHubApiException(
                response.code,
                "GitHub rejected the token: HTTP ${response.code} ${response.message}",
            )
        }
        val user = json.decodeFromString(RestUser.serializer(), response.body!!.string())
        val scopes = response.header("X-OAuth-Scopes")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        response.close()
        if (user.login.isBlank()) {
            throw CloudBuildException("Token verification returned no login")
        }
        TokenInfo(user.login, scopes)
    }

    /* ------------------------------------------------------------------ */
    /* Entry point                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * Runs the whole cloud pipeline for the normalized project at
     * [projectDir]. Returns the extracted artifact and never leaves a repo
     * behind on success.
     */
    suspend fun buildInCloud(
        projectDir: File,
        stack: CloudStack,
        onLog: (String) -> Unit,
        onProgress: (CloudProgress) -> Unit,
    ): CloudBuildResult = withContext(Dispatchers.IO) {
        val token = getToken()
            ?: throw CloudBuildException(
                "No GitHub Personal Access Token configured — add one in the Keystore & Cloud screen"
            )
        val startedAt = System.currentTimeMillis()
        fun elapsed() =
            ((System.currentTimeMillis() - startedAt) / 1000).toInt()

        onProgress(CloudProgress(CloudStage.PREPARING, "Verifying GitHub access…"))
        val user = verifyToken(token)
        onLog("[cloud] Authenticated as ${user.login}")
        warnMissingScopes(user, onLog)

        val repoName = RUNNER_REPO_NAME
        val repoFull = "${user.login}/$repoName"

        onProgress(CloudProgress(CloudStage.PREPARING, "Preparing ephemeral runner repo…"))
        deleteRepoIfExists(token, repoFull, onLog)
        createRepo(token, repoName)
        onLog("[cloud] Created ephemeral private repo $repoFull")
        try {
            // ---------- collect files ----------
            onProgress(CloudProgress(CloudStage.UPLOADING, "Collecting project files…", 0, 1))
            val files = collectProjectFiles(projectDir)
            if (files.isEmpty()) {
                throw CloudBuildException("No uploadable files found in ${projectDir.name}")
            }
            val workflowYaml = WorkflowYaml.forStack(stack, projectDir)
            onLog(
                "[cloud] Uploading ${files.size} files " +
                    "(${files.sumOf { it.second.length() } / 1024} KiB) + workflow"
            )

            // ---------- blobs ----------
            val entries = ArrayList<TreeEntry>(files.size + 1)
            files.forEachIndexed { index, (relative, file) ->
                onProgress(
                    CloudProgress(
                        CloudStage.UPLOADING,
                        "Uploading project files…",
                        uploaded = index,
                        uploadTotal = files.size,
                        elapsedSeconds = elapsed(),
                    )
                )
                val sha = createBlob(token, repoFull, file)
                entries.add(TreeEntry(relative, sha, null))
            }
            entries.add(
                TreeEntry(".github/workflows/universal_build.yml", null, workflowYaml)
            )

            // ---------- tree (chunked) ----------
            onProgress(CloudProgress(CloudStage.UPLOADING, "Creating git tree…", files.size, files.size))
            val treeSha = createTreeChunked(token, repoFull, entries)

            // ---------- commit + ref ----------
            val commitSha = apiJson(
                "POST", "https://api.github.com/repos/$repoFull/git/commits", token,
                buildJsonObject {
                    put("message", "Universal APK Factory build request")
                    put("tree", treeSha)
                },
            ).let { json.decodeFromString(RestCommit.serializer(), it).sha }

            apiJson(
                "POST", "https://api.github.com/repos/$repoFull/git/refs", token,
                buildJsonObject {
                    put("ref", "refs/heads/main")
                    put("sha", commitSha)
                },
            )
            onLog("[cloud] Pushed commit $commitSha to main")

            // ---------- wait for the workflow run ----------
            onProgress(
                CloudProgress(
                    CloudStage.QUEUED,
                    "Waiting for a GitHub runner…",
                    uploaded = files.size,
                    uploadTotal = files.size,
                    elapsedSeconds = elapsed(),
                )
            )
            val run = awaitRun(token, repoFull, commitSha, onLog, onProgress, startedAt)
            val runUrl = run.htmlUrl
            onLog("[cloud] Run started: $runUrl")

            // ---------- poll until completion, streaming step progress ----------
            val finished = awaitCompletion(token, repoFull, run, onLog, onProgress, startedAt)
            if (finished.conclusion != "success") {
                throw CloudBuildException(
                    "GitHub Actions run ended with '${finished.conclusion}'", runUrl
                )
            }

            // ---------- artifact ----------
            onProgress(
                CloudProgress(
                    CloudStage.ARTIFACT,
                    "Downloading build artifact…",
                    elapsedSeconds = elapsed(),
                    runUrl = runUrl,
                )
            )
            val artifactDir = File(
                File(context.cacheDir, "cloud-artifacts"),
                System.currentTimeMillis().toString(),
            ).apply { mkdirs() }
            downloadAndExtractArtifact(token, repoFull, finished.id, artifactDir, onLog)

            val apkFiles = artifactDir.walkTopDown()
                .filter { it.isFile && it.extension == "apk" }
                .toList()
            val webRoot = artifactDir.walkTopDown()
                .filter { it.isFile && it.name == "index.html" }
                .map { it.parentFile!! }
                .firstOrNull()

            deleteRepoIfExists(token, repoFull, onLog)
            onProgress(
                CloudProgress(
                    CloudStage.COMPLETE,
                    "Cloud build finished",
                    elapsedSeconds = elapsed(),
                    runUrl = runUrl,
                )
            )
            onLog("[cloud] Ephemeral repo deleted; artifact extracted to ${artifactDir.name}")
            CloudBuildResult(artifactDir, runUrl, apkFiles, webRoot)
        } catch (error: Exception) {
            // Keep the repo only when a failed run might need manual inspection.
            if (error is CloudBuildException && error.runUrl != null) {
                onLog(
                    "[cloud] Keeping $repoFull for log inspection at ${error.runUrl} " +
                        "(it will be deleted by the next cloud build)"
                )
            } else {
                runCatching { deleteRepoIfExists(token, repoFull, onLog) }
            }
            throw error
        }
    }

    private fun warnMissingScopes(user: TokenInfo, onLog: (String) -> Unit) {
        if (user.scopes.isEmpty()) {
            onLog(
                "[cloud] Fine-grained token (no X-OAuth-Scopes header); assuming " +
                    "sufficient permissions"
            )
            return
        }
        for (required in listOf("repo", "workflow", "delete_repo")) {
            if (required !in user.scopes) {
                onLog(
                    "[cloud] WARNING: token is missing the '$required' scope — " +
                        "the cloud build may fail"
                )
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Project upload                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * A git tree entry: either references an uploaded blob ([sha]) or carries
     * inline [text] (the generated workflow file).
     */
    private data class TreeEntry(val path: String, val sha: String?, val text: String?)

    private val skipDirs = setOf(
        ".git", ".gradle", ".idea", ".npm", ".yarn", ".cache", ".dart_tool",
        "node_modules", "build", "dist", "out", "coverage", "Pods", "target",
        ".expo", ".output", ".nuxt", ".parcel-cache", "__pycache__", "venv",
        ".venv", "env", ".terraform",
    )

    private val skipFileNames = setOf(
        ".DS_Store", "local.properties", "desktop.ini", "thumbs.db"
    )

    private fun collectProjectFiles(projectDir: File): List<Pair<String, File>> {
        val result = ArrayList<Pair<String, File>>()
        var totalBytes = 0L

        fun walk(dir: File, prefix: String) {
            val children = dir.listFiles() ?: return
            for (child in children.sortedBy { it.name }) {
                if (result.size >= MAX_FILES) return
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) {
                    if (child.name == "buildSrc") {
                        walk(child, relative) // needed by Gradle builds
                        continue
                    }
                    if (child.name in skipDirs) continue
                    walk(child, relative)
                } else {
                    if (child.name in skipFileNames) continue
                    if (child.extension in setOf("log", "keystore", "jks", "tmp")) continue
                    if (child.length() > MAX_FILE_BYTES) {
                        continue
                    }
                    totalBytes += child.length()
                    if (totalBytes > MAX_TOTAL_BYTES) {
                        throw CloudBuildException(
                            "Project is larger than the ${MAX_TOTAL_BYTES / (1L shl 20)} MiB " +
                                "cloud upload limit"
                        )
                    }
                    result.add(relative to child)
                }
            }
        }

        walk(projectDir, "")
        return result
    }

    private fun createBlob(token: String, repoFull: String, file: File): String {
        val bytes = file.readBytes()
        val payload = buildJsonObject {
            put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("encoding", "base64")
        }
        val body = apiJson(
            "POST", "https://api.github.com/repos/$repoFull/git/blobs", token, payload
        )
        return json.decodeFromString(RestBlobRef.serializer(), body).sha
    }

    private fun createTreeChunked(
        token: String,
        repoFull: String,
        entries: List<TreeEntry>,
    ): String {
        var baseTree: String? = null
        for (chunk in entries.chunked(TREE_CHUNK_SIZE)) {
            val payload = buildJsonObject {
                baseTree?.let { put("base_tree", it) }
                put("tree", JsonArray(chunk.map { entry ->
                    buildJsonObject {
                        put("path", entry.path)
                        put(
                            "mode",
                            if (entry.path == "gradlew") "100755" else "100644"
                        )
                        put("type", "blob")
                        when {
                            entry.sha != null -> put("sha", entry.sha)
                            entry.text != null -> put("content", entry.text)
                            else -> put("content", "")
                        }
                    }
                }))
            }
            baseTree = json.decodeFromString(
                RestTree.serializer(),
                apiJson(
                    "POST", "https://api.github.com/repos/$repoFull/git/trees", token, payload
                ),
            ).sha
        }
        return baseTree ?: throw CloudBuildException("Refused to create an empty tree")
    }

    /* ------------------------------------------------------------------ */
    /* Run lifecycle                                                       */
    /* ------------------------------------------------------------------ */

    private suspend fun awaitRun(
        token: String,
        repoFull: String,
        commitSha: String,
        onLog: (String) -> Unit,
        onProgress: (CloudProgress) -> Unit,
        startedAt: Long,
    ): RestWorkflowRun {
        val deadline = System.currentTimeMillis() + RUN_APPEARANCE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val body = apiJson(
                "GET",
                "https://api.github.com/repos/$repoFull/actions/runs" +
                    "?head_sha=$commitSha&event=push&per_page=5",
                token,
            )
            val runs = json.decodeFromString(RestWorkflowRuns.serializer(), body)
            val match = runs.workflowRuns.firstOrNull { it.headSha == commitSha }
            if (match != null && match.id != 0L) return match
            delay(POLL_INTERVAL_MS)
            onProgress(
                CloudProgress(
                    CloudStage.QUEUED,
                    "Waiting for a GitHub runner…",
                    elapsedSeconds = elapsedSince(startedAt),
                )
            )
        }
        throw CloudBuildException(
            "No GitHub Actions run appeared for commit $commitSha within " +
                "${RUN_APPEARANCE_TIMEOUT_MS / 1000} s"
        )
    }

    private suspend fun awaitCompletion(
        token: String,
        repoFull: String,
        run: RestWorkflowRun,
        onLog: (String) -> Unit,
        onProgress: (CloudProgress) -> Unit,
        startedAt: Long,
    ): RestWorkflowRun {
        val deadline = System.currentTimeMillis() + RUN_COMPLETION_TIMEOUT_MS
        var current = run
        var lastStep: String? = null
        while (System.currentTimeMillis() < deadline) {
            val body = apiJson(
                "GET",
                "https://api.github.com/repos/$repoFull/actions/runs/${current.id}",
                token,
            )
            current = json.decodeFromString(RestWorkflowRun.serializer(), body)
            val step = reportJobSteps(token, repoFull, current)
            if (step != lastStep) {
                lastStep = step
                if (step != null) onLog("[cloud] $step")
            }
            if (current.status == "completed") return current
            onProgress(
                CloudProgress(
                    CloudStage.RUNNING,
                    step ?: "Compiling on GitHub runner (${current.status})",
                    workflowStep = step,
                    elapsedSeconds = elapsedSince(startedAt),
                    runUrl = current.htmlUrl,
                )
            )
            delay(POLL_INTERVAL_MS)
        }
        throw CloudBuildException("Run did not finish in time", run.htmlUrl)
    }

    /** Returns a short "3/7 Step name" progress line, or null. */
    private fun reportJobSteps(
        token: String,
        repoFull: String,
        run: RestWorkflowRun,
    ): String? {
        val body = apiJson(
            "GET",
            "https://api.github.com/repos/$repoFull/actions/runs/${run.id}/jobs?per_page=20",
            token,
        )
        val jobs = json.decodeFromString(RestJobs.serializer(), body)
        val job = jobs.jobs.firstOrNull() ?: return null
        val steps = job.steps
        if (steps.isEmpty()) return null
        val inProgress = steps.firstOrNull { it.status == "in_progress" }
        val completed = steps.count { it.status == "completed" }
        return when {
            inProgress != null ->
                "${completed + 1}/${steps.size} ${inProgress.name}"

            else -> "${completed}/${steps.size} waiting"
        }
    }

    private fun downloadAndExtractArtifact(
        token: String,
        repoFull: String,
        runId: Long,
        targetDir: File,
        onLog: (String) -> Unit,
    ) {
        val body = apiJson(
            "GET", "https://api.github.com/repos/$repoFull/actions/runs/$runId/artifacts", token
        )
        val artifacts = json.decodeFromString(RestArtifacts.serializer(), body)
        val artifact = artifacts.artifacts.firstOrNull { !it.expired }
            ?: throw CloudBuildException(
                "Run $runId completed but exposed no downloadable artifact"
            )

        val request = Request.Builder()
            .url(artifact.archiveDownloadUrl)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()
        val zipFile = File(targetDir, "artifact.zip")
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CloudBuildException(
                    "Artifact download failed: HTTP ${response.code}"
                )
            }
            response.body!!.byteStream().use { input ->
                zipFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        java.util.zip.ZipFile(zipFile).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                val out = File(targetDir, entry.name)
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        zipFile.delete()
        onLog(
            "[cloud] Artifact '${artifact.name}' extracted " +
                "(${artifact.sizeInBytes / 1024} KiB)"
        )
    }

    /* ------------------------------------------------------------------ */
    /* Repo lifecycle                                                      */
    /* ------------------------------------------------------------------ */

    private fun deleteRepoIfExists(
        token: String,
        repoFull: String,
        onLog: (String) -> Unit,
    ) {
        val response = api("DELETE", "https://api.github.com/repos/$repoFull", token)
        if (response.code == 204) {
            onLog("[cloud] Deleted previous ephemeral repo $repoFull")
        }
        response.close()
    }

    private fun createRepo(token: String, repoName: String): RestRepo {
        val payload = buildJsonObject {
            put("name", repoName)
            put("private", true)
            put("description", "Ephemeral build runner for Universal APK Factory")
            put("auto_init", false)
            put("has_issues", false)
            put("has_wiki", false)
            put("has_projects", false)
            put("delete_branch_on_merge", true)
        }
        return json.decodeFromString(
            RestRepo.serializer(),
            apiJson("POST", "https://api.github.com/user/repos", token, payload),
        )
    }

    /* ------------------------------------------------------------------ */
    /* HTTP core                                                           */
    /* ------------------------------------------------------------------ */

    private fun api(
        method: String,
        url: String,
        token: String,
        payload: kotlinx.serialization.json.JsonElement? = null,
    ): Response {
        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", USER_AGENT)
        when {
            payload != null -> builder.method(
                method, payload.toString().toRequestBody("application/json".toMediaType())
            )

            method == "POST" || method == "PUT" || method == "PATCH" ->
                builder.method(method, ByteArray(0).toRequestBody(null))

            else -> builder.method(method, null)
        }
        return httpClient.newCall(builder.build()).execute()
    }

    private fun apiJson(
        method: String,
        url: String,
        token: String,
        payload: kotlinx.serialization.json.JsonElement? = null,
    ): String {
        val response = api(method, url, token, payload)
        response.use {
            if (!it.isSuccessful) {
                val errorBody = runCatching { it.body?.string() }.getOrNull()
                val documented = it.header("documentation_url")
                throw GitHubApiException(
                    it.code,
                    "GitHub API $method $url -> HTTP ${it.code}: " +
                        (errorBody?.take(400) ?: it.message),
                    documented,
                )
            }
            return it.body!!.string()
        }
    }

    private fun elapsedSince(startedAt: Long): Int =
        ((System.currentTimeMillis() - startedAt) / 1000).toInt()

    /* ------------------------------------------------------------------ */
    /* Workflow YAML generation                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Generates `.github/workflows/universal_build.yml` for the detected
     * stack. Deliberately avoids GitHub Actions template expressions so
     * the YAML can live in Kotlin raw strings verbatim.
     */
    object WorkflowYaml {

        fun forStack(stack: CloudStack, projectDir: File): String = when (stack) {
            CloudStack.WEB_NODE -> webNode(detectWebOutputDir(projectDir))
            CloudStack.CAPACITOR -> capacitor()
            CloudStack.FLUTTER -> flutter()
            CloudStack.GRADLE -> gradle()
        }

        /** Vite defaults to dist, react-scripts to build; fall back to dist. */
        fun detectWebOutputDir(projectDir: File): String {
            val viteConfig = listOf(
                "vite.config.ts", "vite.config.js", "vite.config.mjs"
            ).firstOrNull { File(projectDir, it).isFile }
            if (viteConfig != null) return "dist"
            val packageJson = File(projectDir, "package.json")
            if (packageJson.isFile) {
                val scripts = packageJson.readText()
                if (scripts.contains("react-scripts") || scripts.contains("\"build\": \"next build\"")) {
                    return if (scripts.contains("next build")) "out" else "build"
                }
            }
            return "dist"
        }

        private fun webNode(outputDir: String) = """
            name: Universal Build
            on:
              push:
                branches: [main]
            permissions:
              contents: read
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - uses: actions/setup-node@v4
                    with:
                      node-version: 20
                  - name: Install dependencies
                    run: |
                      if [ -f package-lock.json ]; then
                        npm ci --no-audit --no-fund
                      else
                        npm install --no-audit --no-fund
                      fi
                  - name: Build web bundle
                    run: npm run build
                  - uses: actions/upload-artifact@v4
                    with:
                      name: web-bundle
                      path: $outputDir
                      if-no-files-found: error
        """.trimIndent() + "\n"

        private fun capacitor() = """
            name: Universal Build
            on:
              push:
                branches: [main]
            permissions:
              contents: read
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - uses: actions/setup-node@v4
                    with:
                      node-version: 20
                  - uses: actions/setup-java@v4
                    with:
                      distribution: temurin
                      java-version: 17
                  - name: Install JS dependencies
                    run: |
                      if [ -f package-lock.json ]; then
                        npm ci --no-audit --no-fund
                      else
                        npm install --no-audit --no-fund
                      fi
                  - name: Ensure Android platform
                    run: |
                      if [ ! -d android ]; then
                        npx cap add android
                      fi
                  - name: Sync web assets
                    run: npx cap sync android
                  - name: Build debug APK
                    run: |
                      cd android
                      if [ -f gradlew ]; then
                        chmod +x gradlew
                        ./gradlew assembleDebug --no-daemon
                      else
                        gradle assembleDebug --no-daemon
                      fi
                  - uses: actions/upload-artifact@v4
                    with:
                      name: apk
                      path: android/app/build/outputs/apk/**/*.apk
                      if-no-files-found: error
        """.trimIndent() + "\n"

        private fun flutter() = """
            name: Universal Build
            on:
              push:
                branches: [main]
            permissions:
              contents: read
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - uses: actions/setup-java@v4
                    with:
                      distribution: temurin
                      java-version: 17
                  - uses: subosito/flutter-action@v2
                    with:
                      channel: stable
                  - name: Disable Flutter analytics
                    run: flutter config --no-analytics
                  - name: Resolve packages
                    run: flutter pub get
                  - name: Build release APK
                    run: flutter build apk --release
                  - uses: actions/upload-artifact@v4
                    with:
                      name: apk
                      path: build/app/outputs/flutter-apk/*.apk
                      if-no-files-found: error
        """.trimIndent() + "\n"

        private fun gradle() = """
            name: Universal Build
            on:
              push:
                branches: [main]
            permissions:
              contents: read
            jobs:
              build:
                runs-on: ubuntu-latest
                steps:
                  - uses: actions/checkout@v4
                  - uses: actions/setup-java@v4
                    with:
                      distribution: temurin
                      java-version: 17
                  - name: Build debug APK
                    run: |
                      if [ -f gradlew ]; then
                        chmod +x gradlew
                        ./gradlew assembleDebug --no-daemon
                      else
                        gradle assembleDebug --no-daemon
                      fi
                  - uses: actions/upload-artifact@v4
                    with:
                      name: apk
                      path: '**/build/outputs/apk/**/*.apk'
                      if-no-files-found: error
        """.trimIndent() + "\n"
    }

    private companion object {
        const val KEY_PAT = "github_personal_access_token"
        const val RUNNER_REPO_NAME = "universal-apk-factory-runner"
        const val USER_AGENT = "UniversalApkFactory/2.0 (Android)"
        const val POLL_INTERVAL_MS = 5_000L
        const val RUN_APPEARANCE_TIMEOUT_MS = 120_000L
        const val RUN_COMPLETION_TIMEOUT_MS = 30 * 60_000L
        const val TREE_CHUNK_SIZE = 500
        const val MAX_FILES = 8_000
        const val MAX_FILE_BYTES = 20L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 150L * 1024 * 1024
    }
}
