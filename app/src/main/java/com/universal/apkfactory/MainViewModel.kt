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
package com.universal.apkfactory

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.universal.apkfactory.core.detector.HybridConfigParser
import com.universal.apkfactory.core.ingestion.IngestionManager
import com.universal.apkfactory.core.diag.BuildAutoRepairEngine
import com.universal.apkfactory.core.diag.BuildFailureAnalyzer
import com.universal.apkfactory.core.python.PythonRuntimeManager
import com.universal.apkfactory.core.model.BuildLogLine
import com.universal.apkfactory.core.model.BuildMode
import com.universal.apkfactory.core.model.DetectedProject
import com.universal.apkfactory.core.model.IngestedSource
import com.universal.apkfactory.core.model.IngestionOrigin
import com.universal.apkfactory.core.model.PermissionCatalog
import com.universal.apkfactory.core.model.ProjectBuildConfig
import com.universal.apkfactory.core.signer.ApkAlignerAndSigner
import com.universal.apkfactory.di.ServiceLocator
import com.universal.apkfactory.engine.cloud.GitHubCloudBuilder
import com.universal.apkfactory.engine.gradle.NativeGradleService
import com.universal.apkfactory.engine.gradle.ToolchainManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Part 5 — the single ViewModel behind every screen.
 *
 * Owns the whole factory state machine:
 *
 * ```
 * HOME --ingest--> CONFIG --start build--> BUILD --done--> (result panel)
 *   |                 |                        |
 *   +-- keystores/PAT-+                        +--reset--> HOME
 * ```
 *
 * All engine calls run in `viewModelScope`; log lines, cloud progress and
 * native-build state all funnel into one immutable [UiState], so the UI is
 * a pure function of the state.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    enum class Screen { HOME, CONFIG, BUILD, KEYSTORES }

    enum class BuildPhase {
        IDLE, INGESTING, DETECTING, FAST_BUILDING, CLOUD_BUILDING,
        NATIVE_BUILDING, FINALIZING, DONE, FAILED,
    }

    data class UiState(
        val screen: Screen = Screen.HOME,
        val phase: BuildPhase = BuildPhase.IDLE,
        val busy: Boolean = false,
        val busyMessage: String? = null,
        val project: DetectedProject? = null,
        val config: ProjectBuildConfig? = null,
        val logs: List<BuildLogLine> = emptyList(),
        val cloudProgress: GitHubCloudBuilder.CloudProgress? = null,
        val nativeState: NativeGradleService.NativeBuildState =
            NativeGradleService.NativeBuildState.Idle,
        val toolchainEvent: ToolchainManager.Event? = null,
        val toolchainReady: Boolean = false,
        val resultApk: File? = null,
        val error: String? = null,
        val iconFileName: String? = null,
        /** MODE_NATIVE_GRADLE only: compile locally vs GitHub Actions. */
        val nativeOnDevice: Boolean = true,
        val keystores: List<KeyStoreRecordUi> = emptyList(),
        val patPresent: Boolean = false,
        val patLogin: String? = null,
        val patVerifyMessage: String? = null,
        /** Diagnosis of the last failed build (null when none). */
        val diagnosis: BuildFailureAnalyzer.FailureDiagnosis? = null,
        /**
         * Everything the auto-repair engine fixed so far this build (v6):
         * pre-flight fixes plus failure-driven repairs before each retry.
         * Rendered as an amber card on the Build screen and embedded into
         * the AI prompt.
         */
        val repairEvents: List<BuildAutoRepairEngine.RepairEvent> = emptyList(),
        /** Provisioned CPython runtime, when installed. */
        val pythonRuntime: PythonRuntimeManager.RuntimeInfo? = null,
        /**
         * True when [pythonRuntime] describes the CPython distribution
         * bundled inside the shell template asset (not a user-installed
         * one) — MODE_FAST_PYTHON is ready out of the box.
         */
        val pythonRuntimeBundled: Boolean = false,
        val pythonRuntimeBusy: Boolean = false,
        val pythonRuntimeProgress: String? = null,
    )

    data class KeyStoreRecordUi(
        val fileName: String,
        val packageName: String,
        val alias: String,
        val createdAt: Long,
        val hasStoredPassword: Boolean,
        val certificateSubject: String,
    )

    private val graph = ServiceLocator.graph(application)
    private val ingestion: IngestionManager get() = graph.ingestionManager

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Decoded launcher-icon override (kept out of UiState — it's heavy). */
    var iconBitmap: Bitmap? = null
        private set

    private var buildJob: Job? = null
    private var finalizeNativeJob: Job? = null

    /**
     * v6.3 — uncapped log of EVERYTHING (the rendered [UiState.logs] list is
     * capped at [MAX_LOG_LINES] entries). Feeds the full build report
     * (copy / share) and the persisted `last-build-report.txt`.
     */
    private val fullLog = StringBuilder()

    /** Guards [fullLog] (appended from IO coroutines and the main thread). */
    private val fullLogLock = Any()

    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    private val historyTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)

    /** On-device Gradle builds need roughly 3 GiB of free internal storage. */
    private val minNativeFreeBytes: Long = 3L * 1024 * 1024 * 1024

    init {
        _state.update { it.copy(patPresent = graph.cloudBuilder.hasToken()) }
        refreshKeystores()
        refreshToolchainReady()

        // Mirror the native compiler service into UiState.
        refreshPythonRuntime()
        viewModelScope.launch {
            NativeGradleService.state.collect { native ->
                val previous = _state.value.nativeState
                _state.update { it.copy(nativeState = native) }
                if (native is NativeGradleService.NativeBuildState.Success &&
                    previous !is NativeGradleService.NativeBuildState.Success &&
                    _state.value.phase == BuildPhase.NATIVE_BUILDING
                ) {
                    finalizeNativeOutputs(native)
                }
                if (native is NativeGradleService.NativeBuildState.Failed &&
                    _state.value.phase == BuildPhase.NATIVE_BUILDING
                ) {
                    _state.update {
                        it.copy(
                            phase = BuildPhase.FAILED,
                            busy = false,
                            error = native.message,
                            diagnosis = failureDiagnosisOf(null, native.message),
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            NativeGradleService.outputLines.collect { line ->
                appendLog(line)
            }
        }
        viewModelScope.launch {
            NativeGradleService.toolchainEvents.collect { event ->
                _state.update { it.copy(toolchainEvent = event) }
            }
        }
    }

    /* ================================================================== */
    /* Logging                                                             */
    /* ================================================================== */

    private fun appendLog(message: String) {
        val level = when {
            message.contains("ERROR", ignoreCase = true) ||
                message.contains("FAILED", ignoreCase = true) -> BuildLogLine.Level.ERROR

            message.contains("WARN", ignoreCase = true) ||
                message.contains("WARNING", ignoreCase = true) -> BuildLogLine.Level.WARN

            else -> BuildLogLine.Level.INFO
        }
        synchronized(fullLogLock) {
            fullLog.append("[${logTimeFormat.format(Date())}] [${level.name}] $message\n")
        }
        _state.update { current ->
            val logs = current.logs + BuildLogLine(level = level, message = message)
            current.copy(logs = if (logs.size > MAX_LOG_LINES) {
                logs.subList(logs.size - MAX_LOG_LINES, logs.size)
            } else {
                logs
            })
        }
    }

    private fun onLog(message: String) = appendLog(message)

    /* ================================================================== */
    /* Intent handling (Share Sheet / ACTION_VIEW)                         */
    /* ================================================================== */

    fun handleIntent(intent: Intent?) {
        intent ?: return
        when (val payload = ingestion.describeIntentPayload(intent)) {
            IngestionManager.IntentPayload.Unhandled -> Unit

            is IngestionManager.IntentPayload.SharedText -> {
                val text = payload.text.trim()
                if (text.startsWith("http", ignoreCase = true) ||
                    Regex("^[\\w.-]+/[\\w.-]+(@[\\w./-]+)?$").matches(text)
                ) {
                    ingestGithub(text)
                } else {
                    _state.update {
                        it.copy(
                            error = "Shared text is not a GitHub repository URL: " +
                                text.take(80)
                        )
                    }
                }
            }

            is IngestionManager.IntentPayload.SharedFile ->
                ingestUri(payload.uri, payload.origin)

            is IngestionManager.IntentPayload.ViewedFile ->
                ingestUri(payload.uri, payload.origin)
        }
    }

    /* ================================================================== */
    /* Ingestion                                                           */
    /* ================================================================== */

    fun ingestUri(uri: Uri, origin: IngestionOrigin) {
        if (_state.value.busy) return
        buildJob = viewModelScope.launch {
            clearFullLog()
            _state.update {
                it.copy(
                    busy = true,
                    busyMessage = "Importing …",
                    phase = BuildPhase.INGESTING,
                    error = null,
                    diagnosis = null,
                    repairEvents = emptyList(),
                    logs = emptyList(),
                    resultApk = null,
                )
            }
            try {
                val source = ingestion.ingestContentUri(uri, origin) { bytes ->
                    _state.update { it.copy(busyMessage = "Importing … ${bytes / 1024} KiB") }
                }
                detectAndConfigure(source)
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        phase = BuildPhase.FAILED,
                        error = error.message,
                        diagnosis = failureDiagnosisOf(error),
                    )
                }
                appendLog("ERROR: ${error.message}")
            } finally {
                _state.update { it.copy(busy = false, busyMessage = null) }
            }
        }
    }

    fun ingestFolder(treeUri: Uri) {
        if (_state.value.busy) return
        buildJob = viewModelScope.launch {
            clearFullLog()
            _state.update {
                it.copy(
                    busy = true,
                    busyMessage = "Importing folder …",
                    phase = BuildPhase.INGESTING,
                    error = null,
                    diagnosis = null,
                    repairEvents = emptyList(),
                    logs = emptyList(),
                    resultApk = null,
                )
            }
            try {
                val source = ingestion.ingestTreeUri(treeUri) { copied, total ->
                    _state.update {
                        it.copy(busyMessage = "Importing folder … $copied/$total files")
                    }
                }
                detectAndConfigure(source)
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        phase = BuildPhase.FAILED,
                        error = error.message,
                        diagnosis = failureDiagnosisOf(error),
                    )
                }
                appendLog("ERROR: ${error.message}")
            } finally {
                _state.update { it.copy(busy = false, busyMessage = null) }
            }
        }
    }

    fun ingestGithub(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || _state.value.busy) return
        buildJob = viewModelScope.launch {
            clearFullLog()
            _state.update {
                it.copy(
                    busy = true,
                    busyMessage = "Downloading repository …",
                    phase = BuildPhase.INGESTING,
                    error = null,
                    diagnosis = null,
                    repairEvents = emptyList(),
                    logs = emptyList(),
                    resultApk = null,
                )
            }
            try {
                val source = ingestion.ingestGitHubRepository(trimmed) { bytes, total ->
                    val totalText = if (total > 0) " of ${total / 1024} KiB" else ""
                    _state.update {
                        it.copy(busyMessage = "Downloading … ${bytes / 1024} KiB$totalText")
                    }
                }
                detectAndConfigure(source)
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        phase = BuildPhase.FAILED,
                        error = error.message,
                        diagnosis = failureDiagnosisOf(error),
                    )
                }
                appendLog("ERROR: ${error.message}")
            } finally {
                _state.update { it.copy(busy = false, busyMessage = null) }
            }
        }
    }

    private fun detectAndConfigure(source: IngestedSource) {
        _state.update { it.copy(busyMessage = "Detecting project type …", phase = BuildPhase.DETECTING) }
        val detected = graph.projectDetector.detect(source)
        appendLog("Detected: ${detected.mode} (score evidence: ${detected.evidence.size} signals)")
        detected.evidence.forEach { appendLog("  • $it") }
        val config = HybridConfigParser.parse(detected)
        appendLog(
            "Configuration: ${config.appName} / ${config.packageName} " +
                "v${config.versionName} (${config.versionCode})"
        )
        _state.update {
            it.copy(
                project = detected,
                config = config,
                screen = Screen.CONFIG,
                phase = BuildPhase.IDLE,
                iconFileName = null,
            )
        }
        iconBitmap = null
    }

    /* ================================================================== */
    /* Configuration editing                                               */
    /* ================================================================== */

    fun updateConfig(transform: (ProjectBuildConfig) -> ProjectBuildConfig) {
        _state.update { current ->
            current.config?.let { config -> current.copy(config = transform(config)) }
                ?: current
        }
    }

    fun setIcon(uri: Uri) {
        runCatching {
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
            input?.use { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) {
                    sample *= 2
                }
                val decoded = getApplication<Application>().contentResolver
                    .openInputStream(uri)?.use { reopen ->
                        BitmapFactory.decodeStream(
                            reopen, null,
                            BitmapFactory.Options().apply { inSampleSize = sample }
                        )
                    }
                if (decoded != null) {
                    iconBitmap = decoded
                    _state.update {
                        it.copy(iconFileName = uri.lastPathSegment ?: "custom icon")
                    }
                    appendLog("Launcher icon replaced (${decoded.width}x${decoded.height})")
                }
            }
        }.onFailure { error ->
            _state.update { it.copy(error = "Could not decode icon: ${error.message}") }
        }
    }

    fun togglePermission(permission: String) {
        updateConfig { config ->
            val permissions = config.permissions.toMutableSet()
            if (!permissions.add(permission)) permissions.remove(permission)
            config.copy(permissions = permissions)
        }
    }

    fun setNativeOnDevice(onDevice: Boolean) {
        _state.update { it.copy(nativeOnDevice = onDevice) }
    }

    /**
     * Builds the diagnosis for the failure that just occurred, from the
     * current mode, phase, error and captured terminal log.
     */
    /**
     * v6 auto-repair decision point after a failed build attempt.
     *
     * When the failure matches a repair rule and attempts remain: applies
     * the plan (mutated project/config into UiState, backoff delay, logged
     * events) and returns it so the caller honors
     * [BuildAutoRepairEngine.RepairPlan.dropIcon] and retries. Returns
     * `null` when the caller must NOT retry — no rule matched or attempts
     * are exhausted — with the state already switched to FAILED carrying
     * the diagnosis + AI prompt (which embeds the repair history).
     */
    private suspend fun repairOrFail(
        error: Exception,
        attempt: Int,
    ): BuildAutoRepairEngine.RepairPlan? {
        val project = _state.value.project
        val config = _state.value.config
        if (project == null || config == null) {
            failBuild(error)
            return null
        }
        val plan = BuildAutoRepairEngine.analyzeFailure(
            attempt = attempt,
            project = project,
            config = config,
            error = error,
            logTail = _state.value.logs.takeLast(60).map { it.message },
            workDir = File(graph.appContext.cacheDir, "auto-repair"),
        )
        if (plan == null || attempt >= BuildAutoRepairEngine.MAX_ATTEMPTS) {
            appendLog(
                if (plan == null) {
                    "🔧 auto-repair: no automatic fix is possible for this failure."
                } else {
                    "🔧 auto-repair: repair attempts exhausted " +
                        "(${BuildAutoRepairEngine.MAX_ATTEMPTS})."
                }
            )
            failBuild(error)
            return null
        }
        if (plan.cleanupBuildCache) {
            appendLog("🔧 auto-repair: clearing temporary build files …")
            withContext(Dispatchers.IO) {
                runCatching {
                    IngestionManager.Workspace.build(graph.appContext).deleteRecursively()
                    IngestionManager.Workspace.temp(graph.appContext).deleteRecursively()
                    File(graph.appContext.cacheDir, "auto-repair").deleteRecursively()
                }
            }
        }
        if (plan.retryDelayMs > 0) delay(plan.retryDelayMs)
        plan.events.forEach { event ->
            appendLog("🔧 auto-repair (attempt $attempt failed): $event")
        }
        _state.update { state ->
            state.copy(
                project = plan.project,
                config = plan.config,
                repairEvents = state.repairEvents + plan.events,
            )
        }
        return plan
    }

    /** Switches to FAILED with the diagnosis + AI prompt (incl. repair history). */
    private fun failBuild(error: Exception) {
        appendLog("ERROR: ${error.message}")
        _state.update {
            it.copy(
                phase = BuildPhase.FAILED,
                busy = false,
                error = error.message,
                diagnosis = failureDiagnosisOf(error),
            )
        }
        appendLog(
            "📋 Full report saved to files/last-build-report.txt — use " +
                "'Copy full report' / 'Share' below."
        )
        persistBuildRecord("FAILED: ${error.message?.take(140) ?: "unknown error"}")
    }

    /** v6.3 — resets the uncapped log buffer (new ingestion / new build). */
    private fun clearFullLog() {
        synchronized(fullLogLock) { fullLog.setLength(0) }
    }

    /**
     * v6.3 — the complete record of everything that happened: outcome, project
     * and config, every auto-repair, the full diagnosis + ready AI prompt and
     * the UNCAPPED terminal log (the on-screen viewer only keeps the last
     * [MAX_LOG_LINES] lines). Powers the on-screen copy / share buttons.
     */
    fun composeFullReport(): String {
        val s = _state.value
        return buildString {
            appendLine("═══════════════════════════════════════════════════════════")
            appendLine(" Universal APK Factory v6.3 — FULL BUILD REPORT")
            appendLine(" ${historyTimeFormat.format(Date())}")
            appendLine("═══════════════════════════════════════════════════════════")
            appendLine()
            appendLine("── Outcome ──")
            appendLine("Phase : ${s.phase}")
            appendLine("Error : ${s.error ?: "none"}")
            appendLine()
            appendLine("── Project ──")
            appendLine("Source: ${s.project?.source?.displayName ?: "none"}")
            appendLine("Mode  : ${s.project?.mode ?: "n/a"}")
            s.config?.let { c ->
                appendLine("App   : ${c.appName}")
                appendLine("Id    : ${c.packageName}")
                appendLine("Ver   : ${c.versionName} (${c.versionCode})")
                appendLine("Perms : ${c.permissions.sorted().joinToString(", ")}")
                appendLine("Orient: ${c.orientation} | fullscreen=${c.fullscreen}")
                c.metadataOriginNote?.let { appendLine("Origin: $it") }
            }
            appendLine()
            appendLine("── Auto-repairs applied (${s.repairEvents.size}) ──")
            if (s.repairEvents.isEmpty()) appendLine("(none)") else
                s.repairEvents.forEach { appendLine("• $it") }
            appendLine()
            s.diagnosis?.let { d ->
                appendLine("── Diagnosis ──")
                appendLine("Type  : ${d.category}")
                appendLine("Title : ${d.title}")
                appendLine("Cause : ${d.probableCause}")
                appendLine("Fix   :")
                d.fixSteps.forEach { appendLine("  • $it") }
                d.runUrl?.let { appendLine("Run   : $it") }
                appendLine()
                appendLine("── Ready AI prompt ──")
                appendLine(d.aiPrompt)
                appendLine()
            }
            appendLine("── Full terminal log (uncapped) ──")
            val log = synchronized(fullLogLock) { fullLog.toString() }
            append(if (log.isBlank()) "(empty)\n" else log)
            appendLine()
            appendLine("══════════════════ end of report ══════════════════")
        }
    }

    /**
     * v6.3 — nothing is ever lost: every finished build (success or failure)
     * writes `files/last-build-report.txt` (the full report) and appends a
     * one-line summary to `files/build-history.log`.
     */
    private fun persistBuildRecord(outcome: String) {
        val report = composeFullReport()
        val mode = _state.value.project?.mode?.toString() ?: "n/a"
        val config = _state.value.config
        val line = "${historyTimeFormat.format(Date())} | $mode | " +
            "${config?.packageName ?: "?"} ${config?.versionName ?: "?"} " +
            "(${config?.versionCode ?: "?"}) | $outcome\n"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                File(graph.appContext.filesDir, "last-build-report.txt")
                    .writeText(report)
                File(graph.appContext.filesDir, "build-history.log")
                    .appendText(line)
            } catch (_: Exception) {
                // Reporting must never break the app.
            }
        }
    }

    private fun failureDiagnosisOf(
        error: Throwable?,
        messageOverride: String? = null,
    ): BuildFailureAnalyzer.FailureDiagnosis =
        BuildFailureAnalyzer.analyze(
            mode = _state.value.project?.mode,
            phaseName = _state.value.phase.name,
            error = error ?: messageOverride?.let { RuntimeException(it) },
            logs = _state.value.logs,
            repairHistory = _state.value.repairEvents.map { it.toString() },
        )

    /* ------------------------------------------------------------------ */
    /* Python runtime provisioning (MODE_FAST_PYTHON)                      */
    /* ------------------------------------------------------------------ */

    fun installPythonRuntime(url: String, sha256: String) {
        if (_state.value.pythonRuntimeBusy) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    pythonRuntimeBusy = true,
                    pythonRuntimeProgress = "Starting …",
                    error = null,
                )
            }
            try {
                val info = graph.pythonRuntimeManager.install(
                    url = url.trim(),
                    expectedSha256 = sha256.trim().ifBlank { null },
                ) { stage, fraction ->
                    val pct = fraction?.let { " (${(it * 100).toInt()}%)" } ?: ""
                    _state.update {
                        it.copy(pythonRuntimeProgress = stage + pct)
                    }
                }
                _state.update {
                    it.copy(
                        pythonRuntime = info,
                        pythonRuntimeBundled = false,
                        pythonRuntimeBusy = false,
                        pythonRuntimeProgress = null,
                    )
                }
                appendLog(
                    "Python ${info.pythonVersion} runtime installed — " +
                        "${info.fileCount} files, ${info.displaySize}. " +
                        "It overrides the bundled runtime in future builds."
                )
            } catch (error: Exception) {
                appendLog("ERROR: ${error.message}")
                _state.update {
                    it.copy(
                        pythonRuntimeBusy = false,
                        pythonRuntimeProgress = null,
                        error = error.message,
                    )
                }
            }
        }
    }

    fun clearPythonRuntime() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.pythonRuntimeManager.clear() }
            appendLog("Custom Python runtime removed — falling back to the bundled one.")
            refreshPythonRuntime()
        }
    }

    /**
     * Refreshes the Python runtime status: a user-installed distro wins;
     * otherwise the runtime bundled inside the shell template (v5+) is
     * reported so the UI can present MODE_FAST_PYTHON as ready out of the
     * box. The bundled probe touches the shell APK on disk, so it runs on
     * Dispatchers.IO.
     */
    private fun refreshPythonRuntime() {
        viewModelScope.launch {
            val installed = withContext(Dispatchers.IO) { graph.pythonRuntimeManager.info() }
            if (installed != null) {
                _state.update {
                    it.copy(pythonRuntime = installed, pythonRuntimeBundled = false)
                }
                return@launch
            }
            val bundled = withContext(Dispatchers.IO) {
                graph.pythonRuntimeManager.bundledRuntimeInfo()
            }
            _state.update {
                it.copy(pythonRuntime = bundled, pythonRuntimeBundled = bundled != null)
            }
        }
    }

    fun dismissError() {
        // v6.3 — never wipe the failure surface (error + diagnosis + ready AI
        // prompt) while the build is FAILED. The global snackbar used to call
        // this right after a build failure, making the prompt card appear for
        // a few seconds and then vanish. Transient errors on other phases
        // still dismiss normally.
        _state.update {
            if (it.phase == BuildPhase.FAILED) it else it.copy(error = null, diagnosis = null)
        }
    }

    /* ================================================================== */
    /* Build launching                                                     */
    /* ================================================================== */

    /** One-click build: routes by mode and the user's engine choice. */
    fun startBuild() {
        val rawProject = _state.value.project ?: return
        val rawConfig = _state.value.config ?: return
        if (_state.value.busy || _state.value.phase == BuildPhase.NATIVE_BUILDING) return
        clearFullLog()

        // v6 — pre-flight auto-repair: fix what is fixable (invalid package
        // id, broken version fields, missing entry files) before the first
        // attempt instead of failing the build on it.
        val preflight = BuildAutoRepairEngine.preflight(rawProject, rawConfig)
        preflight.events.forEach { event ->
            appendLog("🔧 auto-repair (pre-flight): $event")
        }
        val project = preflight.project
        val config = preflight.config
        _state.update {
            it.copy(
                project = project,
                config = config,
                repairEvents = preflight.events,
            )
        }

        if (config.validate().isNotEmpty()) {
            _state.update { it.copy(error = config.validate().joinToString("; ")) }
            return
        }
        when (project.mode) {
            BuildMode.MODE_APK_EDITOR,
            BuildMode.MODE_FAST_WEB,
            BuildMode.MODE_FAST_PYTHON,
            -> startFastBuild()

            BuildMode.MODE_CLOUD_WEB_OR_HYBRID,
            BuildMode.MODE_CLOUD_FLUTTER,
            -> startCloudBuild()

            BuildMode.MODE_NATIVE_GRADLE ->
                if (_state.value.nativeOnDevice) startNativeBuild() else startCloudBuild()
        }
    }

    private fun startFastBuild() {
        buildJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    screen = Screen.BUILD,
                    phase = BuildPhase.FAST_BUILDING,
                    busy = true,
                    error = null,
                    diagnosis = null,
                    resultApk = null,
                )
            }
            var attempt = 1
            var dropIcon = false
            while (true) {
                try {
                    val project = _state.value.project ?: return@launch
                    val config = _state.value.config ?: return@launch
                    if (attempt > 1) {
                        appendLog(
                            "🔁 Retrying the fast build " +
                                "(attempt $attempt/${BuildAutoRepairEngine.MAX_ATTEMPTS}) …"
                        )
                    }
                    val apk = graph.buildOrchestrator.runFastBuild(
                        detected = project,
                        config = config,
                        iconBitmap = if (dropIcon) null else iconBitmap,
                        onLog = ::onLog,
                    )
                    _state.update {
                        it.copy(phase = BuildPhase.DONE, busy = false, resultApk = apk)
                    }
                    persistBuildRecord("SUCCESS: ${apk.name} (${apk.length()} bytes)")
                    return@launch
                } catch (error: Exception) {
                    val repaired = repairOrFail(error, attempt) ?: return@launch
                    dropIcon = dropIcon || repaired.dropIcon
                    attempt++
                }
            }
        }
    }

    private fun startCloudBuild() {
        val project = _state.value.project ?: return
        val config = _state.value.config ?: return
        if (!graph.cloudBuilder.hasToken()) {
            _state.update {
                it.copy(
                    error = "A GitHub Personal Access Token is required for cloud builds — " +
                        "add one in Keystores & Cloud",
                )
            }
            return
        }
        buildJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    screen = Screen.BUILD,
                    phase = BuildPhase.CLOUD_BUILDING,
                    busy = true,
                    error = null,
                    diagnosis = null,
                    resultApk = null,
                )
            }
            var attempt = 1
            var dropIcon = false
            while (true) {
                try {
                    val currentProject = _state.value.project ?: project
                    val currentConfig = _state.value.config ?: config
                    if (attempt > 1) {
                        appendLog(
                            "🔁 Retrying the cloud build " +
                                "(attempt $attempt/${BuildAutoRepairEngine.MAX_ATTEMPTS}) …"
                        )
                    }
                    val apk = graph.buildOrchestrator.runCloudBuild(
                        detected = currentProject,
                        config = currentConfig,
                        iconBitmap = if (dropIcon) null else iconBitmap,
                        onLog = ::onLog,
                    ) { progress ->
                        _state.update { it.copy(cloudProgress = progress) }
                    }
                    _state.update {
                        it.copy(phase = BuildPhase.DONE, busy = false, resultApk = apk)
                    }
                    persistBuildRecord("SUCCESS: ${apk.name} (${apk.length()} bytes)")
                    return@launch
                } catch (error: Exception) {
                    val repaired = repairOrFail(error, attempt) ?: return@launch
                    dropIcon = dropIcon || repaired.dropIcon
                    attempt++
                }
            }
        }
    }

    private fun startNativeBuild() {
        val project = _state.value.project ?: return
        val config = _state.value.config ?: return
        buildJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    screen = Screen.BUILD,
                    phase = BuildPhase.NATIVE_BUILDING,
                    busy = true,
                    error = null,
                    diagnosis = null,
                    resultApk = null,
                    cloudProgress = null,
                )
            }
            // Pre-flight: JDK + Gradle + SDK + outputs need ~3 GiB free.
            val stats = android.os.StatFs(graph.appContext.filesDir.absolutePath)
            val freeGiB = stats.availableBytes / (1L shl 30)
            if (stats.availableBytes < minNativeFreeBytes) {
                val message = "Not enough free storage for an on-device build: " +
                    "$freeGiB GiB available, ${minNativeFreeBytes / (1L shl 30)} GiB " +
                    "required. Free up space or switch to the cloud build."
                appendLog("ERROR: $message")
                _state.update {
                    it.copy(
                        phase = BuildPhase.FAILED,
                        busy = false,
                        error = message,
                        diagnosis = failureDiagnosisOf(RuntimeException(message)),
                    )
                }
                return@launch
            }
            try {
                val projectDir = graph.buildOrchestrator.prepareNormalizedProjectDir(
                    project, ::onLog
                )
                NativeGradleService.resetState()
                graph.buildOrchestrator.startNativeBuild(projectDir)
                // From here the service drives the phase via its state flow.
            } catch (error: Exception) {
                appendLog("ERROR: ${error.message}")
                _state.update {
                    it.copy(
                        phase = BuildPhase.FAILED,
                        busy = false,
                        error = error.message,
                        diagnosis = failureDiagnosisOf(error),
                    )
                }
            }
        }
    }

    private fun finalizeNativeOutputs(success: NativeGradleService.NativeBuildState.Success) {
        val config = _state.value.config ?: return
        if (finalizeNativeJob?.isActive == true) return
        finalizeNativeJob = viewModelScope.launch {
            _state.update { it.copy(phase = BuildPhase.FINALIZING) }
            try {
                val apk = success.outputApks.firstOrNull()
                    ?: throw IllegalStateException(
                        "Gradle finished but produced no APK outputs"
                    )
                val signed = graph.buildOrchestrator.finalizeExternalApk(apk, config, ::onLog)
                _state.update {
                    it.copy(phase = BuildPhase.DONE, busy = false, resultApk = signed)
                }
            } catch (error: Exception) {
                appendLog("ERROR: ${error.message}")
                _state.update {
                    it.copy(
                        phase = BuildPhase.FAILED,
                        busy = false,
                        error = error.message,
                        diagnosis = failureDiagnosisOf(error),
                    )
                }
            }
        }
    }

    fun cancelNativeBuild() {
        val intent = Intent(getApplication(), NativeGradleService::class.java)
            .setAction(NativeGradleService.ACTION_CANCEL)
        getApplication<Application>().startService(intent)
        _state.update {
            it.copy(phase = BuildPhase.FAILED, busy = false, error = "Build cancelled")
        }
    }

    /* ================================================================== */
    /* Result actions                                                      */
    /* ================================================================== */

    fun installResult() {
        val apk = _state.value.resultApk ?: return
        viewModelScope.launch {
            val result = graph.alignerAndSigner.installApk(apk, ::onLog)
            when (result) {
                is ApkAlignerAndSigner.InstallResult.SessionCommitted ->
                    appendLog("Install session ${result.sessionId} committed — watch notifications")

                is ApkAlignerAndSigner.InstallResult.HandedToSystemInstaller ->
                    appendLog("Handed ${apk.name} to the system installer")

                is ApkAlignerAndSigner.InstallResult.Failed ->
                    _state.update { it.copy(error = "Install failed: ${result.reason}") }
            }
        }
    }

    fun reset() {
        buildJob?.cancel()
        finalizeNativeJob?.cancel()
        iconBitmap = null
        _state.update {
            UiState(
                patPresent = it.patPresent,
                patLogin = it.patLogin,
                keystores = it.keystores,
                toolchainReady = it.toolchainReady,
                toolchainEvent = null,
            )
        }
        refreshToolchainReady()
    }

    /* ================================================================== */
    /* Toolchain                                                           */
    /* ================================================================== */

    fun refreshToolchainReady() {
        viewModelScope.launch {
            val ready = runCatching { graph.toolchainManager.isReady() }.getOrDefault(false)
            _state.update { it.copy(toolchainReady = ready) }
        }
    }

    /** Pre-downloads the on-device compiler toolchain from the Home screen. */
    fun prepareToolchain() {
        if (_state.value.busy) return
        buildJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, busyMessage = "Provisioning toolchain …") }
            try {
                graph.toolchainManager.ensureReady { event ->
                    _state.update { it.copy(toolchainEvent = event) }
                }
                _state.update { it.copy(toolchainReady = true) }
                appendLog("Toolchain ready: JDK 21 + Gradle 8.9 + Android SDK (aarch64)")
            } catch (error: Exception) {
                appendLog("ERROR: ${error.message}")
                _state.update { it.copy(error = error.message) }
            } finally {
                _state.update { it.copy(busy = false, busyMessage = null) }
            }
        }
    }

    /* ================================================================== */
    /* Keystores + PAT                                                     */
    /* ================================================================== */

    fun refreshKeystores() {
        viewModelScope.launch {
            val records = runCatching { graph.keyStoreGenerator.listKeyStores() }
                .getOrDefault(emptyList())
            _state.update {
                it.copy(
                    keystores = records.map { record ->
                        KeyStoreRecordUi(
                            fileName = record.file.name,
                            packageName = record.packageName,
                            alias = record.alias,
                            createdAt = record.createdAt,
                            hasStoredPassword = record.hasStoredPassword,
                            certificateSubject = record.certificateSubject,
                        )
                    }
                )
            }
        }
    }

    fun importKeystore(uri: Uri, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyMessage = "Importing keystore …") }
            try {
                val app = getApplication<Application>()
                val staging = File(app.cacheDir, "import-${System.currentTimeMillis()}.keystore")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    staging.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Cannot open the selected keystore")
                val identity = graph.keyStoreGenerator.importKeyStore(
                    source = staging,
                    password = password.toCharArray(),
                )
                appendLog(
                    "Imported keystore for ${identity.packageName} " +
                        "(alias ${identity.alias})"
                )
                staging.delete()
                refreshKeystores()
            } catch (error: Exception) {
                appendLog("ERROR: keystore import failed: ${error.message}")
                _state.update { it.copy(error = error.message) }
            } finally {
                _state.update { it.copy(busy = false, busyMessage = null) }
            }
        }
    }

    fun createKeystoreFor(packageName: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyMessage = "Generating keystore …") }
            try {
                val identity = graph.keyStoreGenerator.getOrCreateKeyStore(packageName.trim())
                appendLog("Keystore ready for ${identity.packageName}")
                refreshKeystores()
            } catch (error: Exception) {
                appendLog("ERROR: ${error.message}")
                _state.update { it.copy(error = error.message) }
            } finally {
                _state.update { it.copy(busy = false, busyMessage = null) }
            }
        }
    }

    fun savePat(token: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, busyMessage = "Verifying token …", patVerifyMessage = null) }
            try {
                graph.cloudBuilder.saveToken(token)
                val info = graph.cloudBuilder.verifyToken(token)
                _state.update {
                    it.copy(
                        patPresent = true,
                        patLogin = info.login,
                        patVerifyMessage = "Verified as @${info.login}" +
                            if (info.scopes.isEmpty()) "" else " (scopes: ${info.scopes.joinToString(", ")})",
                    )
                }
                appendLog("GitHub token verified for @${info.login}")
            } catch (error: Exception) {
                graph.cloudBuilder.clearToken()
                _state.update {
                    it.copy(
                        patPresent = false,
                        patLogin = null,
                        patVerifyMessage = "Token rejected: ${error.message}",
                    )
                }
            } finally {
                _state.update { it.copy(busy = false, busyMessage = null) }
            }
        }
    }

    fun clearPat() {
        graph.cloudBuilder.clearToken()
        _state.update {
            it.copy(
                patPresent = false,
                patLogin = null,
                patVerifyMessage = "Token removed from this device",
            )
        }
    }

    fun openKeystores() {
        refreshKeystores()
        _state.update { it.copy(screen = Screen.KEYSTORES) }
    }

    fun navigate(screen: Screen) {
        if (screen == Screen.HOME && _state.value.phase == BuildPhase.NATIVE_BUILDING) {
            // Leaving the build screen mid-build keeps the service running;
            // state continues to stream into the log viewer when returning.
        }
        _state.update { it.copy(screen = screen) }
    }

    companion object {
        private const val MAX_LOG_LINES = 4000
    }
}
