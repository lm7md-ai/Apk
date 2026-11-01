package com.universal.apkfactory.core.detector

import com.universal.apkfactory.core.model.BuildMode
import com.universal.apkfactory.core.model.DetectedProject

/**
 * =====================================================================================
 * PermissionInferenceEngine — automatic permission detection (v6.2)
 * =====================================================================================
 *
 * "Upload your app, get the right permissions" — instead of asking the user
 * to tick permission checkboxes manually, the factory scans the uploaded
 * source code and enables exactly the Android permissions that code actually
 * uses, on top of the shell baseline.
 *
 *  * **Web projects** (single-page sites, PWAs, prebuilt Node bundles): the
 *    HTML/JS is scanned for browser API signatures — `navigator.geolocation`,
 *    `getUserMedia`, web notifications, `navigator.vibrate`,
 *    `navigator.bluetooth`, screen wake lock.
 *  * **Python projects**: import statements (and `requirements.txt`) are
 *    scanned for modules that imply hardware access — `cv2`/`picamera`
 *    (camera), `pyaudio`/`sounddevice`/`speech_recognition` (microphone),
 *    `bleak`/`bluetooth` (Bluetooth).
 *  * **APK editing**: nothing to infer — the uploaded APK keeps its own
 *    permission set (handled by [HybridConfigParser] via ARSCLib).
 *
 * INTERNET / ACCESS_NETWORK_STATE are part of the shell baseline for web and
 * python apps regardless of inference (the shells themselves need them), so
 * the engine only reports *extras*.
 *
 * The result feeds the editable configuration screen: auto-detected
 * permissions arrive pre-checked, and the user can still adjust any of them
 * before building.
 *
 * Deliberately dependency-free (pure Kotlin) and heavily bounded: a maximum
 * number of files, a maximum total scan budget and vendor/junk filtering
 * keep even large zips fast (each `peek` may re-open the archive).
 */
object PermissionInferenceEngine {

    /** One inferred permission and the human-readable reason for it. */
    data class Finding(
        val permission: String,
        val reason: String,
    )

    /**
     * @param permissions the complete permission set to install
     *   (shell baseline + inferred extras).
     * @param findings the inferred extras with reasons, for the config
     *   screen note and the terminal log.
     */
    data class Result(
        val permissions: Set<String>,
        val findings: List<Finding>,
    )

    /* ================================================================== */
    /* Entry point                                                         */
    /* ================================================================== */

    /**
     * Infers the permissions the uploaded project needs.
     *
     * @param detected the classified project (mode + file list).
     * @param peek reads a project file's text content by normalized
     *   relative path (`null` when absent or too large). For APK sources
     *   this always returns `null` — the engine short-circuits anyway.
     */
    fun infer(
        detected: DetectedProject,
        peek: (String) -> String?,
    ): Result {
        val baseline = baselinePermissionsFor(detected.mode)
        if (!modeIsInferable(detected.mode)) {
            return Result(permissions = baseline, findings = emptyList())
        }

        val findings = LinkedHashMap<String, Finding>() // permission -> first finding
        fun grant(permission: String, reason: String) {
            if (permission !in baseline) findings.getOrPut(permission) {
                Finding(permission = permission, reason = reason)
            }
        }

        when (detected.mode) {
            BuildMode.MODE_FAST_PYTHON -> scanPython(detected, peek, ::grant)
            else -> scanWeb(detected, peek, ::grant) // web + cloud-web/hybrid
        }

        return Result(
            permissions = baseline + findings.keys,
            findings = findings.values.toList(),
        )
    }

    /* ================================================================== */
    /* Web scanning                                                        */
    /* ================================================================== */

    /**
     * Signature → permissions. Matched against lower-cased file content, so
     * minified bundles are covered too.
     */
    private val WEB_SIGNATURES: List<Triple<Regex, Array<String>, String>> = listOf(
        Triple(
            Regex("navigator\\.geolocation|geolocation\\.getcurrentposition"),
            arrayOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
            ),
            "uses the browser Geolocation API",
        ),
        Triple(
            Regex("getusermedia"),
            arrayOf(
                "android.permission.CAMERA",
                "android.permission.RECORD_AUDIO",
            ),
            "uses getUserMedia (camera / microphone)",
        ),
        Triple(
            Regex("speechrecognition"),
            arrayOf("android.permission.RECORD_AUDIO"),
            "uses the Speech Recognition API",
        ),
        Triple(
            Regex(
                "notification\\.requestpermission|new notification\\(|" +
                    "shownotification\\(|serviceworker"
            ),
            arrayOf("android.permission.POST_NOTIFICATIONS"),
            "shows web notifications",
        ),
        Triple(
            Regex("navigator\\.vibrate"),
            arrayOf("android.permission.VIBRATE"),
            "uses the Vibration API",
        ),
        Triple(
            Regex("navigator\\.bluetooth"),
            arrayOf("android.permission.BLUETOOTH_CONNECT"),
            "uses the Web Bluetooth API",
        ),
        Triple(
            Regex("navigator\\.wakelock|requestwakelock"),
            arrayOf("android.permission.WAKE_LOCK"),
            "requests a screen wake lock",
        ),
    )

    private val WEB_EXTENSIONS = listOf(
        "html", "htm", "js", "mjs", "cjs", "jsx", "ts", "tsx", "vue", "svelte",
    )

    private fun scanWeb(
        detected: DetectedProject,
        peek: (String) -> String?,
        grant: (String, String) -> Unit,
    ): Int {
        val root = detected.webRootDir.orEmpty()
        val prefix = if (root.isEmpty()) "" else "$root/"

        val candidates = detected.rootFiles
            .asSequence()
            .filter { it.startsWith(prefix) }                 // inside the web root
            .filter { it.substringAfterLast('/') in WEB_EXTENSIONS || it.endsWith(".html") }
            .filter { !isJunkPath(it) }
            .sortedWith(compareBy({ it.length }, { it }))     // entry files first
            .toList()

        // index.html is always the first candidate when present.
        val ordered = candidates.sortedByDescending { it == "${prefix}index.html" }
        return scanFiles(ordered, peek) { content, path ->
            val lower = content.lowercase()
            for ((signature, permissions, reason) in WEB_SIGNATURES) {
                if (signature.containsMatchIn(lower)) {
                    for (permission in permissions) {
                        grant(permission, "$reason — found in ${shortName(path)}")
                    }
                }
            }
        }
    }

    /* ================================================================== */
    /* Python scanning                                                     */
    /* ================================================================== */

    /** Top-level import module → permission. */
    private val PYTHON_MODULES: Map<String, Pair<Array<String>, String>> = mapOf(
        "cv2" to Pair(
            arrayOf("android.permission.CAMERA"),
            "imports OpenCV (cv2)",
        ),
        "picamera" to Pair(
            arrayOf("android.permission.CAMERA"),
            "imports picamera",
        ),
        "pyaudio" to Pair(
            arrayOf("android.permission.RECORD_AUDIO"),
            "imports pyaudio",
        ),
        "sounddevice" to Pair(
            arrayOf("android.permission.RECORD_AUDIO"),
            "imports sounddevice",
        ),
        "speech_recognition" to Pair(
            arrayOf("android.permission.RECORD_AUDIO"),
            "imports speech_recognition",
        ),
        "bleak" to Pair(
            arrayOf("android.permission.BLUETOOTH_CONNECT"),
            "imports bleak (Bluetooth)",
        ),
        "bluetooth" to Pair(
            arrayOf("android.permission.BLUETOOTH_CONNECT"),
            "imports bluetooth",
        ),
    )

    /** requirements.txt package name → permission (normalized, lowercase). */
    private val PYTHON_REQUIREMENTS: Map<String, Pair<Array<String>, String>> = mapOf(
        "opencv-python" to Pair(arrayOf("android.permission.CAMERA"), "depends on opencv-python"),
        "opencv-contrib-python" to Pair(
            arrayOf("android.permission.CAMERA"),
            "depends on opencv-contrib-python",
        ),
        "opencv-python-headless" to Pair(
            arrayOf("android.permission.CAMERA"),
            "depends on opencv-python-headless",
        ),
        "picamera" to Pair(arrayOf("android.permission.CAMERA"), "depends on picamera"),
        "pyaudio" to Pair(arrayOf("android.permission.RECORD_AUDIO"), "depends on pyaudio"),
        "sounddevice" to Pair(arrayOf("android.permission.RECORD_AUDIO"), "depends on sounddevice"),
        "speechrecognition" to Pair(
            arrayOf("android.permission.RECORD_AUDIO"),
            "depends on SpeechRecognition",
        ),
        "bleak" to Pair(arrayOf("android.permission.BLUETOOTH_CONNECT"), "depends on bleak"),
        "pybluez" to Pair(arrayOf("android.permission.BLUETOOTH_CONNECT"), "depends on pybluez"),
    )

    private val PYTHON_IMPORT_REGEX =
        Regex("(?m)^\\s*(?:import|from)\\s+([a-zA-Z_][a-zA-Z0-9_]*)")

    private fun scanPython(
        detected: DetectedProject,
        peek: (String) -> String?,
        grant: (String, String) -> Unit,
    ): Int {
        val pyFiles = detected.rootFiles
            .asSequence()
            .filter { it.endsWith(".py", ignoreCase = true) }
            .filter { !isJunkPath(it) }
            .sortedWith(compareBy({ it.length }, { it }))
            .toList()

        var scanned = 0

        // 1. requirements.txt — the most reliable dependency signal.
        val requirements = detected.rootFiles
            .filter { it.substringAfterLast('/') == "requirements.txt" }
            .sortedBy { it.length }
        for (path in requirements) {
            val content = peek(path) ?: continue
            scanned++
            for (line in content.lineSequence()) {
                val pkg = line.trim()
                    .removePrefix("-e ")
                    .substringBefore('#')
                    .trim()
                    .substringBefore('<')
                    .substringBefore('>')
                    .substringBefore('=')
                    .substringBefore('[')
                    .trim()
                    .lowercase()
                PYTHON_REQUIREMENTS[pkg]?.let { (permissions, reason) ->
                    for (permission in permissions) {
                        grant(permission, "$reason — listed in ${shortName(path)}")
                    }
                }
            }
        }

        // 2. Import statements in the python sources.
        scanned += scanFiles(pyFiles, peek) { content, path ->
            for (match in PYTHON_IMPORT_REGEX.findAll(content)) {
                val module = match.groupValues[1]
                PYTHON_MODULES[module]?.let { (permissions, reason) ->
                    for (permission in permissions) {
                        grant(permission, "$reason — imported in ${shortName(path)}")
                    }
                }
            }
        }
        return scanned
    }

    /* ================================================================== */
    /* Shared helpers                                                      */
    /* ================================================================== */

    /** Hard caps so even huge uploads stay fast. */
    private const val MAX_FILES = 60
    private const val MAX_TOTAL_CHARS = 6_000_000L

    /**
     * Reads at most [MAX_FILES] files within a total budget of
     * [MAX_TOTAL_CHARS] characters, running [onContent] for each.
     */
    private fun scanFiles(
        files: List<String>,
        peek: (String) -> String?,
        onContent: (String, String) -> Unit,
    ): Int {
        var budget = MAX_TOTAL_CHARS
        var scanned = 0
        for (path in files) {
            if (scanned >= MAX_FILES || budget <= 0) break
            val content = peek(path) ?: continue
            scanned++
            budget -= content.length
            onContent(content, path)
        }
        return scanned
    }

    /** Vendor / build-output / test noise that would only cause false hits. */
    private fun isJunkPath(path: String): Boolean {
        val lower = path.lowercase()
        return lower.contains("node_modules/") ||
            lower.contains("/vendor/") ||
            lower.contains("/__tests__/") ||
            lower.contains("/test/") ||
            lower.contains("/tests/") ||
            lower.endsWith(".spec.js") ||
            lower.endsWith(".test.js") ||
            lower.endsWith(".spec.ts") ||
            lower.endsWith(".test.ts") ||
            lower.endsWith(".min.css") ||
            lower.endsWith(".map")
    }

    /** Last path segment, for readable findings ("main.py", not "a/b/c/main.py"). */
    private fun shortName(path: String): String = path.substringAfterLast('/')

    private fun modeIsInferable(mode: BuildMode): Boolean = when (mode) {
        BuildMode.MODE_FAST_WEB,
        BuildMode.MODE_CLOUD_WEB_OR_HYBRID,
        BuildMode.MODE_FAST_PYTHON -> true

        BuildMode.MODE_APK_EDITOR,      // the APK keeps its own permissions
        BuildMode.MODE_NATIVE_GRADLE,   // native project owns its manifest
        BuildMode.MODE_CLOUD_FLUTTER -> false
    }

    /** Mirrors HybridConfigParser's shell baseline (INTERNET & co). */
    private fun baselinePermissionsFor(mode: BuildMode): Set<String> = when (mode) {
        BuildMode.MODE_FAST_WEB,
        BuildMode.MODE_CLOUD_WEB_OR_HYBRID,
        BuildMode.MODE_FAST_PYTHON,
        BuildMode.MODE_CLOUD_FLUTTER -> setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
        )

        BuildMode.MODE_APK_EDITOR,
        BuildMode.MODE_NATIVE_GRADLE -> setOf("android.permission.INTERNET")
    }
}
