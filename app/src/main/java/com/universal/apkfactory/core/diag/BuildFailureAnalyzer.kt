package com.universal.apkfactory.core.diag

import com.universal.apkfactory.core.model.BuildLogLine
import com.universal.apkfactory.core.model.BuildMode

/**
 * ============================================================================
 * BuildFailureAnalyzer — intelligent build-failure diagnosis engine
 * ============================================================================
 *
 * When any build path (fast WebView / fast Python / cloud CI / on-device
 * Gradle / APK editor) fails, this engine:
 *
 *  1. Scans the terminal log tail plus the thrown error message for
 *     well-known failure signatures (network, GitHub auth, disk space,
 *     toolchain download, signing, payload corruption …).
 *  2. Classifies the failure into a [FailureCategory].
 *  3. Produces a human-readable diagnosis: probable cause + concrete
 *     recovery steps, shown on the Build screen.
 *  4. Renders a self-contained, ready-to-send AI support prompt (in Arabic,
 *     with verbatim English log excerpts) that the user can copy or share
 *     to any AI assistant for a deeper fix.
 *
 * The analyzer is deliberately dependency-free (pure Kotlin + the core
 * models) so it can never itself become the cause of a build failure.
 */
object BuildFailureAnalyzer {

    /* ====================================================================== */
    /* Public model                                                           */
    /* ====================================================================== */

    /** High-level failure classification shown as a chip in the UI. */
    enum class FailureCategory(
        val displayName: String,
        val chipEmoji: String,
    ) {
        GITHUB_AUTH("GitHub credentials", "🔑"),
        NETWORK("Connectivity", "📡"),
        DISK_SPACE("Storage space", "💾"),
        PYTHON_RUNTIME("Python runtime", "🐍"),
        TOOLCHAIN("Build toolchain", "🛠"),
        CLOUD_RUN("Cloud CI run", "☁"),
        DETECTION("Project detection", "🔍"),
        PAYLOAD_INVALID("Invalid input file", "📦"),
        SIGNING("Signing / keystore", "✍"),
        ICON_ASSET("Icon / asset", "🖼"),
        MEMORY("Out of memory", "🧠"),
        GRADLE_COMPILE("Gradle compilation", "⚙"),
        UNKNOWN("Unclassified", "❓"),
    }

    /** The full diagnosis attached to a failed build. */
    data class FailureDiagnosis(
        val category: FailureCategory,
        /** One-line headline, e.g. "GitHub rejected the access token". */
        val title: String,
        /** Why this most likely happened. */
        val probableCause: String,
        /** Concrete recovery steps the user can follow in order. */
        val fixSteps: List<String>,
        /** Best-effort cloud run URL when the failure was on GitHub Actions. */
        val runUrl: String?,
        /** Ready-to-send AI prompt (Arabic framing, verbatim logs). */
        val aiPrompt: String,
    )

    /* ====================================================================== */
    /* Entry point                                                            */
    /* ====================================================================== */

    /**
     * Analyzes a failed build.
     *
     * @param mode the detected build mode, or `null` when the failure happened
     *   before / during detection (e.g. a corrupt zip during ingestion).
     * @param phaseName name of the UI phase that failed (e.g. `CLOUD_BUILDING`).
     * @param error the exception thrown, if any.
     * @param logs the full terminal log captured so far.
     * @param repairHistory everything the auto-repair engine already tried
     *   (v6) — embedded in the AI prompt so the assistant does not suggest
     *   what was already attempted.
     */
    fun analyze(
        mode: BuildMode?,
        phaseName: String,
        error: Throwable?,
        logs: List<BuildLogLine>,
        repairHistory: List<String> = emptyList(),
    ): FailureDiagnosis {
        val errorText = error?.message.orEmpty()
        val logTail = logs.takeLast(LOG_TAIL_LINES).joinToString("\n") { it.message }
        val hay = "$errorText\n$logTail"

        val rule = RULES.firstOrNull { it.pattern.containsMatchIn(hay) } ?: UNKNOWN_RULE
        val runUrl = RUN_URL_REGEX.find(hay)?.value

        val evidence = extractEvidence(logs)
        val prompt = buildAiPrompt(
            mode = mode,
            phaseName = phaseName,
            error = error,
            evidence = evidence,
            rule = rule,
            runUrl = runUrl,
            repairHistory = repairHistory,
        )

        return FailureDiagnosis(
            category = rule.category,
            title = rule.title,
            probableCause = rule.cause,
            fixSteps = rule.fixSteps,
            runUrl = runUrl,
            aiPrompt = prompt,
        )
    }

    /* ====================================================================== */
    /* Rule table                                                             */
    /* ====================================================================== */

    private class Rule(
        val category: FailureCategory,
        val title: String,
        val cause: String,
        val fixSteps: List<String>,
        val pattern: Regex,
    )

    /** Order matters: the first matching rule wins. */
    private val RULES = listOf(
        Rule(
            category = FailureCategory.GITHUB_AUTH,
            title = "GitHub rejected the credentials",
            cause = "The Personal Access Token (PAT) is missing, expired, revoked, " +
                "or lacks the required scopes (repo / workflow).",
            fixSteps = listOf(
                "Open «Keystores & Cloud» and re-enter the token.",
                "Create a fresh PAT with the `repo` and `workflow` scopes.",
                "Press «Verify» — it must show your GitHub login before building.",
                "If the account has 2FA / SAML, generate a fine-grained token.",
            ),
            pattern = Regex(
                "Bad credentials|status.?code.?401|HTTP 401|401 Unauthorized|" +
                    "requires a GitHub Personal Access Token|token (is )?" +
                    "(missing|invalid|expired|revoked)|403 Forbidden|not authorized",
                RegexOption.IGNORE_CASE,
            ),
        ),
        Rule(
            category = FailureCategory.DISK_SPACE,
            title = "Not enough storage space on the device",
            cause = "The on-device toolchain (JDK + Gradle + SDK ≈ 1 GB) or the " +
                "build outputs need more free space than currently available. " +
                "On-device Gradle builds need ~3 GB free in total.",
            fixSteps = listOf(
                "Free at least 3 GB of internal storage and retry.",
                "Move photos / videos / large downloads off the device.",
                "Clear the app cache (Android Settings → Apps).",
                "Alternatively use the cloud build path instead of on-device.",
            ),
            pattern = Regex(
                "ENOSPC|No space left|insufficient space|not enough (free )?space|" +
                    "needs? at least \\d+ (MB|GB) free|low on space",
                RegexOption.IGNORE_CASE,
            ),
        ),
        Rule(
            category = FailureCategory.PYTHON_RUNTIME,
            title = "The Python runtime is not provisioned",
            cause = "MODE_FAST_PYTHON needs a CPython 3 aarch64-android " +
                "distribution (bin/python3.x + lib/libpython3.x.so) bundled " +
                "into the shell. None is currently installed.",
            fixSteps = listOf(
                "Open the Python Runtime card on the configuration screen.",
                "Paste the URL of a CPython aarch64-android tar.gz/zip " +
                    "(AndroidIDE / Termux style) plus its SHA-256.",
                "Install it, then rebuild — it is injected automatically.",
                "The generated app shows a clear notice until a runtime is bundled.",
            ),
            pattern = Regex(
                "CPython|python.?runtime|missing runtime|interpreter (is )?missing|" +
                    "No python interpreter",
                RegexOption.IGNORE_CASE,
            ),
        ),
        Rule(
            category = FailureCategory.TOOLCHAIN,
            title = "Build toolchain problem",
            cause = "Downloading or running the on-device toolchain " +
                "(JDK 21 / Gradle / Android SDK / aapt2) failed — a checksum " +
                "mismatch, a broken archive, or an unsupported archive layout.",
            fixSteps = listOf(
                "Retry — mirrors occasionally serve truncated downloads.",
                "Check connectivity, then use «Prepare toolchain» on the home " +
                    "screen to re-download each component.",
                "Free storage and retry (the toolchain needs ~1 GB).",
                "If it persists, switch to the cloud build path.",
            ),
            pattern = Regex(
                "toolchain|checksum|mismatch|sha-?256.*(fail|invalid)|" +
                    "JDK|gradle distribution|sdkmanager|aapt2|ArchiveFormatException|" +
                    "unzip|tar\\.gz|distribution .* failed",
                RegexOption.IGNORE_CASE,
            ),
        ),
        Rule(
            category = FailureCategory.CLOUD_RUN,
            title = "The GitHub Actions run failed",
            cause = "The generated workflow ran on GitHub but a step inside it " +
                "failed (dependencies, compilation, or artifact upload). " +
                "The run log has the exact step.",
            fixSteps = listOf(
                "Open the run URL shown below and read the red step.",
                "Typical causes: a dependency that needs a newer Node/SDK, " +
                    "missing native build tools, or a private registry.",
                "Fix the project and rebuild — each attempt creates a fresh run.",
                "Send the AI prompt below together with the run log for a fix.",
            ),
            pattern = Regex(
                "Actions run|run \\d+ (failed|failure)|conclusion[=: ]+failure|" +
                    "workflow .*fail|artifact .*not (found|available)|run URL|" +
                    "CloudBuildException",
                RegexOption.IGNORE_CASE,
            ),
        ),
        Rule(
            category = FailureCategory.NETWORK,
            title = "Network connectivity problem",
            cause = "A required download (repository, toolchain, artifact or API " +
                "call) could not reach its host — DNS, timeout, TLS or offline mode.",
            fixSteps = listOf(
                "Check that Wi-Fi / mobile data is actually on.",
                "Disable VPN / private DNS temporarily and retry.",
                "Retry after a minute — transient DNS failures are common.",
                "For GitHub downloads, ensure github.com is reachable.",
            ),
            pattern = Regex(
                "Unable to resolve host|UnknownHost|ETIMEDOUT|SocketTimeout|" +
                    "timed? ?out|ECONNREFUSED|ENETUNREACH|SSLException|" +
                    "SSLHandshake|network|offline|connection (reset|refused|closed)",
                RegexOption.IGNORE_CASE,
            ),
        ),
        Rule(
            category = FailureCategory.DETECTION,
            title = "Could not detect a supported project stack",
            cause = "The archive/folder does not match any supported stack: " +
                "static web (index.html), Node web apps, Python, Gradle " +
                "Android, Flutter, or an existing APK.",
            fixSteps = listOf(
                "Make sure the zip contains the project at its top level " +
                    "(not a doubled folder).",
                "For web apps, ensure an index.html (or dist/index.html) exists.",
                "For Python, add main.py or requirements.txt at the root.",
                "Check the detection signals listed on the configuration screen.",
            ),
            pattern = Regex(
                "Unable to detect a supported project|DetectionException|" +
                    "not a supported (stack|project)",
                RegexOption.IGNORE_CASE,
            ),
        ),
        Rule(
            category = FailureCategory.PAYLOAD_INVALID,
            title = "The input file is invalid or corrupted",
            cause = "The zip/archive could not be opened, is empty, or claims " +
                "to be an APK without a binary manifest and classes.dex.",
            fixSteps = listOf(
                "Re-download or re-export the zip and check it opens elsewhere.",
                "Zip the project contents (not a corrupt/partial download).",
                "For APK editing, provide an original installable APK file.",
                "Avoid password-protected or non-standard archives.",
            ),
            pattern = Regex(
                "not an installable APK|ZipException|malformed|corrupt(ed)?|" +
                    "no entries|empty (archive|zip|payload)|not a zip|" +
                    "unsupported compression|unexpected end",
                RegexOption.IGNORE_CASE,
            ),
        ),
        Rule(
            category = FailureCategory.SIGNING,
            title = "Signing or alignment failure",
            cause = "The V1/V2/V3 signing or 4-byte alignment step failed — " +
                "usually a keystore problem (wrong password, unreadable key) " +
                "or an APK layout the signer cannot process.",
            fixSteps = listOf(
                "Open «Keystores & Cloud» and verify the keystore entry.",
                "Re-enter the keystore password (it may have changed).",
                "Delete and regenerate the keystore for this package name " +
                    "(previous installs signed with the old key must be " +
                    "uninstalled first).",
                "Retry — transient I/O errors during alignment do occur.",
            ),
            pattern = Regex(
                "Signing failed|keystore|KeyStoreException|UnrecoverableKey|" +
                    "signature|align|zipalign|SigningFailedException",
                RegexOption.IGNORE_CASE,
            ),
        ),
        Rule(
            category = FailureCategory.ICON_ASSET,
            title = "Icon or asset processing failure",
            cause = "The selected launcher icon could not be decoded or " +
                "re-rendered into the shell's DPI grid.",
            fixSteps = listOf(
                "Use a square PNG (≥ 512×512) without transparency tricks.",
                "Re-select the icon on the configuration screen.",
                "Or build without a custom icon (the default one is used).",
            ),
            pattern = Regex(
                "icon|Bitmap|decode.*image|could not decode|drawable",
                RegexOption.IGNORE_CASE,
            ),
        ),
        Rule(
            category = FailureCategory.MEMORY,
            title = "Out of memory during the build",
            cause = "The device ran out of memory while patching or compiling — " +
                "large payloads, huge icons, or a background-heavy phone.",
            fixSteps = listOf(
                "Close other apps and retry.",
                "Use the cloud build path for very large projects.",
                "Free RAM (restart the device) and retry.",
            ),
            pattern = Regex(
                "OutOfMemoryError|out of memory|Cannot allocate|OOM",
                RegexOption.IGNORE_CASE,
            ),
        ),
        Rule(
            category = FailureCategory.GRADLE_COMPILE,
            title = "Gradle compilation failed",
            cause = "The on-device Gradle build compiled the project sources " +
                "and the compiler reported errors (dependencies, syntax, " +
                "or SDK mismatches).",
            fixSteps = listOf(
                "Read the first `e:` / `error:` lines in the terminal log — " +
                    "they name the file and line.",
                "Fix the reported source or dependency and rebuild.",
                "Ensure the project's compileSdk is ≤ the installed SDK 34.",
                "Use the AI prompt below and include those first errors.",
            ),
            pattern = Regex(
                "Compilation failed|compileDebug|e: file:|error: |" +
                    "FAILED: Build failed|Gradle build failed|BUILD FAILED",
                RegexOption.IGNORE_CASE,
            ),
        ),
    )

    private val UNKNOWN_RULE = Rule(
        category = FailureCategory.UNKNOWN,
        title = "Build failed for an unrecognized reason",
        cause = "No known signature matched the log. The raw error and the " +
            "terminal log tail below carry the details.",
        fixSteps = listOf(
            "Read the last ERROR lines in the terminal log.",
            "Retry once — some failures are transient.",
            "Send the AI prompt below for a deeper analysis.",
        ),
        pattern = Regex("a^"), // never matches
    )

    /* ====================================================================== */
    /* Evidence extraction                                                    */
    /* ====================================================================== */

    /** Picks the log lines most useful for diagnosis (errors first, capped). */
    private fun extractEvidence(logs: List<BuildLogLine>): List<String> {
        val errors = logs.filter { it.level == BuildLogLine.Level.ERROR }
            .map { it.message }
        val warnings = logs.filter { it.level == BuildLogLine.Level.WARN }
            .map { it.message }
        val tail = logs.takeLast(LOG_TAIL_LINES).map { it.message }
        return (errors + warnings + tail)
            .distinct()
            .take(MAX_EVIDENCE_LINES)
            .map { line -> if (line.length > MAX_LINE_LENGTH) line.take(MAX_LINE_LENGTH) + "…" else line }
    }

    /* ====================================================================== */
    /* AI prompt rendering                                                    */
    /* ====================================================================== */

    private fun buildAiPrompt(
        mode: BuildMode?,
        phaseName: String,
        error: Throwable?,
        evidence: List<String>,
        rule: Rule,
        runUrl: String?,
        repairHistory: List<String> = emptyList(),
    ): String = buildString {
        appendLine("## 🐞 مشكلة في بناء تطبيق — أحتاج مساعدتك في حلها")
        appendLine()
        appendLine("أستخدم تطبيق **Universal APK Factory** على هاتف أندرويد لتحويل " +
            "مشروعي إلى تطبيق APK قابل للتثبيت، وفشل البناء. إليك كل التفاصيل:")
        appendLine()
        // v6.3 — lead with the classified error type so any assistant (or the
        // user) grasps the failure class before reading anything else.
        appendLine("**نوع الخطأ: ${rule.category} — ${rule.title}**")
        appendLine()
        appendLine("### 1) السياق")
        appendLine("- مسار البناء: **${mode?.displayName ?: "لم يُحدد بعد (فشل مبكر)"}**")
        if (mode != null) appendLine("- وصف المسار: ${mode.summary}")
        appendLine("- المرحلة التي فشل عندها: `$phaseName`")
        appendLine("- إصدار التطبيق المُصنِّع: 6.3 (arm64-v8a، أندرويد 8.0+)")
        appendLine()
        appendLine("### 2) رسالة الخطأ الأصلية")
        appendLine("```")
        appendLine((error?.message ?: "لا توجد رسالة خطأ صريحة").take(1500))
        appendLine("```")
        if (error != null) {
            appendLine("- نوع الاستثناء: `${error.javaClass.name}`")
        }
        appendLine()
        appendLine("### 3) التشخيص المبدئي من التطبيق")
        appendLine("- التصنيف: **${rule.category.displayName}** — ${rule.title}")
        appendLine("- السبب المرجح: ${rule.cause}")
        if (runUrl != null) {
            appendLine("- سجل التشغيل السحابي (GitHub Actions): $runUrl")
        }
        appendLine()
        if (repairHistory.isNotEmpty()) {
            appendLine("### 4) ما حاول التطبيق إصلاحه تلقائياً (وفشل)")
            appendLine("جرت هذه المحاولات قبل الوصول إليك — **لا تقترحها مجدداً**:")
            repairHistory.forEach { attempt ->
                appendLine("- $attempt")
            }
            appendLine()
        }
        appendLine("### ${if (repairHistory.isEmpty()) 4 else 5}) مقتطف من سجل البناء (حرفي، كما ظهر)")
        appendLine("```text")
        if (evidence.isEmpty()) {
            appendLine("(السجل فارغ)")
        } else {
            evidence.forEach { appendLine(it) }
        }
        appendLine("```")
        appendLine()
        appendLine("### ${if (repairHistory.isEmpty()) 5 else 6}) المطلوب منك")
        appendLine("1. حدّد **السبب الجذري** للفشل بدقة.")
        appendLine("2. أعطني **خطوات إصلاح عملية مرتبة** أستطيع تنفيذها من هاتفي.")
        appendLine("3. إن كان الخلل في مشروعي (لا في التطبيق المُصنِّع)، اذكر الملف " +
            "والسطر وما يجب تغييره بالضبط.")
        appendLine("4. إن كان الخلل في التطبيق المُصنِّع نفسه، اقترح كود Kotlin " +
            "مصححاً وواضحاً.")
        appendLine("5. أخبرني إن كان هناك حل بديل مؤقت.")
        appendLine()
        appendLine("شكراً مقدماً 🙏")
    }

    /* Regex for GitHub Actions run URLs inside error/log text. */
    private val RUN_URL_REGEX =
        Regex("https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/actions/runs/\\d+")

    private const val LOG_TAIL_LINES = 400
    private const val MAX_EVIDENCE_LINES = 60
    private const val MAX_LINE_LENGTH = 500
}
