package com.universal.apkfactory.core.diag

import com.universal.apkfactory.core.model.BuildMode
import com.universal.apkfactory.core.model.DetectedProject
import com.universal.apkfactory.core.model.IngestedSource
import com.universal.apkfactory.core.model.ProjectBuildConfig
import java.io.File
import java.util.zip.ZipFile

/**
 * ============================================================================
 * BuildAutoRepairEngine — self-healing layer for the build pipeline (v6)
 * ============================================================================
 *
 * The factory never gives up on the first error. This engine runs in two
 * places:
 *
 *  1. **Pre-flight** ([preflight]) — before the first attempt: silently
 *     fixes *fixable* build inputs (invalid package id, broken version
 *     fields, missing web/Python entry files) and reports every change.
 *  2. **Failure-driven** ([analyzeFailure]) — after a failed attempt:
 *     matches the error against repair rules (icon rendering failure,
 *     an APK wrapped inside a zip, transient network errors) and returns a
 *     [RepairPlan] with mutated inputs; the ViewModel re-runs the build
 *     automatically, up to [MAX_ATTEMPTS] attempts.
 *
 * When no repair is possible (or the retries are exhausted) the pipeline
 * falls back to the existing failure surface: a clear message, the smart
 * [BuildFailureAnalyzer] diagnosis and the ready-to-send AI prompt — now
 * enriched with the history of everything auto-repair already tried.
 *
 * Deliberately dependency-free (pure Kotlin + java.io/zip) so it can never
 * itself become the cause of a build failure.
 */
object BuildAutoRepairEngine {

    /* ====================================================================== */
    /* Public model                                                           */
    /* ====================================================================== */

    /** One repair that was applied (or will be applied on retry). */
    data class RepairEvent(
        /** What was wrong, e.g. "Package name 'my-app' is not valid". */
        val problem: String,
        /** What the engine did about it, e.g. "rewrote it to com.apkfactory.my.app". */
        val action: String,
    ) {
        override fun toString(): String = "$problem → $action"
    }

    /**
     * The repaired build inputs for the next attempt. All fields default to
     * "unchanged".
     */
    data class RepairPlan(
        val project: DetectedProject,
        val config: ProjectBuildConfig,
        /** Drop the user's launcher icon and rebuild with the shell default. */
        val dropIcon: Boolean = false,
        /** Pause before the retry (transient-network backoff). */
        val retryDelayMs: Long = 0L,
        val events: List<RepairEvent> = emptyList(),
    )

    /** First attempt + up to two repair-driven retries. */
    const val MAX_ATTEMPTS = 3

    /* ====================================================================== */
    /* Pre-flight: fix what is fixable before the first attempt               */
    /* ====================================================================== */

    /**
     * Repairs fixable configuration / detection problems before the build
     * starts. Always returns a plan (possibly with no events) so callers can
     * use it unconditionally.
     */
    fun preflight(project: DetectedProject, config: ProjectBuildConfig): RepairPlan {
        val events = mutableListOf<RepairEvent>()
        var cfg = config

        // ---- application name -------------------------------------------
        if (cfg.appName.isBlank()) {
            val fallbackName = project.source.displayName
                .substringBeforeLast('.')
                .replace(Regex("[-_]+"), " ")
                .trim()
                .ifBlank { "My App" }
            cfg = cfg.copy(appName = fallbackName)
            events += RepairEvent(
                problem = "App name was empty",
                action = "set it to \"$fallbackName\"",
            )
        }

        // ---- package id ---------------------------------------------------
        if (!PACKAGE_NAME_REGEX.matches(cfg.packageName)) {
            val fixed = ProjectBuildConfig.derivePackageName(cfg.appName)
            if (PACKAGE_NAME_REGEX.matches(fixed) && fixed != cfg.packageName) {
                cfg = cfg.copy(packageName = fixed)
                events += RepairEvent(
                    problem = "Package name '${config.packageName}' is not a valid Android id",
                    action = "rewrote it to $fixed",
                )
            }
        }

        // ---- version fields ------------------------------------------------
        if (cfg.versionCode < 1) {
            cfg = cfg.copy(versionCode = 1)
            events += RepairEvent(
                problem = "Version code ${config.versionCode} is invalid",
                action = "reset it to 1",
            )
        }
        if (cfg.versionName.isBlank()) {
            cfg = cfg.copy(versionName = "1.0")
            events += RepairEvent(
                problem = "Version name was empty",
                action = "set it to \"1.0\"",
            )
        }

        // ---- entry files ----------------------------------------------------
        var prj = project
        when (prj.mode) {
            BuildMode.MODE_FAST_PYTHON -> {
                val entry = prj.pythonEntry ?: "main.py"
                if (!prj.rootFiles.contains(entry)) {
                    val candidate = bestPythonEntry(prj.rootFiles)
                    if (candidate != null && candidate != entry) {
                        prj = prj.copy(pythonEntry = candidate)
                        events += RepairEvent(
                            problem = "Python entry '$entry' not found in the project",
                            action = "switched the entry to '$candidate'",
                        )
                    }
                }
            }

            BuildMode.MODE_FAST_WEB -> {
                val root = prj.webRootDir.orEmpty()
                val prefix = if (root.isEmpty()) "" else "$root/"
                val entry = prj.webEntryFile ?: "index.html"
                if (!prj.rootFiles.contains(prefix + entry)) {
                    val candidate = bestWebEntry(prj.rootFiles, prefix)
                    if (candidate != null && candidate != entry) {
                        prj = prj.copy(webEntryFile = candidate)
                        events += RepairEvent(
                            problem = "Web entry '$prefix$entry' not found in the project",
                            action = "switched the entry to '$prefix$candidate'",
                        )
                    }
                }
            }

            else -> Unit
        }

        return RepairPlan(project = prj, config = cfg, events = events)
    }

    /* ====================================================================== */
    /* Failure-driven repair rules                                            */
    /* ====================================================================== */

    /**
     * Matches a failed attempt against the repair rules. Returns `null` when
     * nothing can be repaired automatically — the caller should then surface
     * the diagnosis + AI prompt.
     *
     * @param attempt 1-based number of the attempt that just failed.
     * @param logTail the last lines of the terminal log (best-effort).
     * @param workDir scratch dir for repairs that produce files.
     */
    fun analyzeFailure(
        attempt: Int,
        project: DetectedProject,
        config: ProjectBuildConfig,
        error: Throwable?,
        logTail: List<String>,
        workDir: File,
    ): RepairPlan? {
        val errorText = error?.message.orEmpty()
        val hay = errorText + "\n" + logTail.takeLast(LOG_TAIL_LINES).joinToString("\n")

        // ---- rule 1: launcher icon rendering failed → drop the icon -------
        // The renderer throws generic messages ("width and height must be > 0")
        // so the signal is the error text + exception class name, never the
        // (noisy) log tail.
        val iconSignal = errorText + " " + (error?.javaClass?.simpleName ?: "")
        if (ICON_FAILURE_REGEX.containsMatchIn(iconSignal)) {
            return RepairPlan(
                project = project,
                config = config,
                dropIcon = true,
                events = listOf(
                    RepairEvent(
                        problem = "The custom launcher icon could not be rendered",
                        action = "dropping it and rebuilding with the default icon",
                    )
                ),
            )
        }

        // ---- rule 2: MODE_APK_EDITOR input is a zip that wraps the APK ----
        if (project.mode == BuildMode.MODE_APK_EDITOR &&
            NESTED_APK_REGEX.containsMatchIn(hay)
        ) {
            unwrapNestedApk(project, workDir)?.let { unwrapped ->
                return RepairPlan(
                    project = unwrapped,
                    config = config,
                    events = listOf(
                        RepairEvent(
                            problem = "The input is an archive, not a raw APK",
                            action = "extracted '${unwrapped.source.displayName}.apk' " +
                                "from inside it and rebuilding with that",
                        )
                    ),
                )
            }
        }

        // ---- rule 3: transient network failure → backoff and retry --------
        if (NETWORK_RETRY_REGEX.containsMatchIn(hay)) {
            return RepairPlan(
                project = project,
                config = config,
                retryDelayMs = 5_000L,
                events = listOf(
                    RepairEvent(
                        problem = "Transient network failure during the build",
                        action = "waiting 5 s and retrying automatically",
                    )
                ),
            )
        }

        return null
    }

    /* ====================================================================== */
    /* Repair helpers                                                         */
    /* ====================================================================== */

    /**
     * A zip the user picked for MODE_APK_EDITOR often contains the real APK.
     * Extracts the largest `*.apk` entry into [workDir] and returns the
     * project re-pointed at it; `null` when the input is not a zip or holds
     * no APK.
     */
    private fun unwrapNestedApk(project: DetectedProject, workDir: File): DetectedProject? {
        val input = project.source.workspaceFile
        if (!input.isFile) return null
        val zip = try {
            ZipFile(input)
        } catch (error: Exception) {
            return null
        }
        zip.use { archive ->
            val apkEntries = archive.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                .toList()
            val best = apkEntries.maxByOrNull { it.size } ?: return null
            workDir.mkdirs()
            val target = File(workDir, "unwrapped-${System.currentTimeMillis()}.apk")
            try {
                archive.getInputStream(best).use { input2 ->
                    target.outputStream().use { output ->
                        input2.copyTo(output, 64 * 1024)
                    }
                }
            } catch (error: Exception) {
                target.delete()
                return null
            }
            if (target.length() < MIN_APK_BYTES) {
                target.delete()
                return null
            }
            return project.copy(
                source = IngestedSource.ApkFile(
                    workspaceFile = target,
                    origin = project.source.origin,
                )
            )
        }
    }

    /** Best Python entry candidate from the project's file list. */
    private fun bestPythonEntry(rootFiles: Set<String>): String? {
        val pyFiles = rootFiles.filter { it.endsWith(".py", ignoreCase = true) }
        if (pyFiles.isEmpty()) return null
        for (preferred in PREFERRED_PYTHON_ENTRIES) {
            pyFiles
                .filter { it.endsWith("/$preferred", ignoreCase = true) || it.equals(preferred, true) }
                .minByOrNull { it.length }
                ?.let { return it }
        }
        return pyFiles.minByOrNull { it.length }
    }

    /** Best HTML entry candidate (relative to the web root). */
    private fun bestWebEntry(rootFiles: Set<String>, prefix: String): String? {
        val htmlFiles = rootFiles.filter {
            it.endsWith(".html", ignoreCase = true) || it.endsWith(".htm", ignoreCase = true)
        }
        if (htmlFiles.isEmpty()) return null
        for (preferred in PREFERRED_WEB_ENTRIES) {
            htmlFiles
                .filter { it.equals(prefix + preferred, ignoreCase = true) }
                .minByOrNull { it.length }
                ?.let { return it.removePrefix(prefix) }
        }
        // Any html directly inside the web root, then anywhere.
        htmlFiles.filter { it.startsWith(prefix) && !it.removePrefix(prefix).contains('/') }
            .minByOrNull { it.length }
            ?.let { return it.removePrefix(prefix) }
        return htmlFiles.minByOrNull { it.length }?.removePrefix(prefix)
    }

    /* ====================================================================== */
    /* Signatures                                                             */
    /* ====================================================================== */

    /** Mirrors `ProjectBuildConfig`'s private package-id rule. */
    private val PACKAGE_NAME_REGEX =
        Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*){1,6}$")

    /** Icon rendering/decoding problems (matched on the error signal only). */
    private val ICON_FAILURE_REGEX = Regex(
        "icon|bitmap|drawable|mipmap|width and height|must be > 0",
        RegexOption.IGNORE_CASE,
    )

    /** "The input is not a raw APK" signatures. */
    private val NESTED_APK_REGEX = Regex(
        "not an installable APK|ZipException|malformed|corrupt|no entries|" +
            "empty (archive|zip|payload)|not a zip|unexpected end",
        RegexOption.IGNORE_CASE,
    )

    /** Transient network errors worth one automatic retry. */
    private val NETWORK_RETRY_REGEX = Regex(
        "Unable to resolve host|UnknownHost|ETIMEDOUT|SocketTimeout|timed? ?out|" +
            "ECONNREFUSED|connection (reset|refused|closed)|SSLException|SSLHandshake",
        RegexOption.IGNORE_CASE,
    )

    private val PREFERRED_PYTHON_ENTRIES = listOf(
        "main.py", "app.py", "run.py", "__main__.py", "start.py",
        "server.py", "bot.py", "cli.py", "program.py", "script.py",
    )

    private val PREFERRED_WEB_ENTRIES = listOf(
        "index.html", "index.htm", "default.html", "default.htm",
        "home.html", "home.htm", "main.html", "main.htm", "app.html",
    )

    private const val LOG_TAIL_LINES = 60

    /** A real APK is never smaller than this. */
    private const val MIN_APK_BYTES = 50_000L
}
