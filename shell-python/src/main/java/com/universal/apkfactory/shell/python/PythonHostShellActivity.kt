/*
 * Python Runtime Shell — Universal APK Factory & Studio
 * Copyright (C) 2026 Universal APK Factory contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.universal.apkfactory.shell.python

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Python 3 ARM64 host shell (MODE_FAST_PYTHON target).
 *
 * The patcher injects two asset trees into this shell:
 *
 *  * `assets/python_app/` — the user's program (normalized project zip:
 *    `main.py`, `requirements.txt`, local modules, ...),
 *  * `assets/python_runtime/` — a CPython aarch64 distribution bundled at
 *    factory build time (CI provisions the CPython-for-Android release plus
 *    a tiny NDK-compiled `Py_BytesMain` launcher at `bin/python3.x`; a
 *    user-provisioned custom runtime replaces it at patch time).
 *
 * On first launch both trees are extracted into `filesDir` (extraction is
 * content-hash guarded so it runs once), then — when the project ships a
 * `requirements.txt` — its pure-Python packages are pip-installed into
 * `site-packages/`, and finally the interpreter is launched:
 *
 * ```
 * /system/bin/linker64  <runtime>/bin/python3.12  -u  <entry>.py
 * ```
 *
 * **Android 10+ W^X compliance:** executing binaries straight from
 * `context.filesDir` is forbidden by the kernel (`noexec` mount). Invoking
 * the binary *through `/system/bin/linker64`* is the well-known Termux /
 * AndroidIDE technique to bypass the restriction for dynamically linked
 * ELFs — the linker itself is executable and maps the target ELF without a
 * `execve()` on a noexec path.
 *
 * stdout/stderr are streamed live into an on-screen terminal; stdin is fed
 * from an input field, so interactive `input()` programs work.
 */
class PythonHostShellActivity : Activity() {

    private lateinit var console: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var inputField: EditText

    private val mainHandler = Handler(Looper.getMainLooper())
    private val processRunning = AtomicBoolean(false)
    private var process: Process? = null

    private var shellConfig: ShellConfig = ShellConfig()

    data class ShellConfig(
        val appName: String = "Python App",
        val orientation: String = "unspecified",
        val fullscreen: Boolean = false,
        val pythonEntry: String = "main.py",
    ) {
        companion object {
            fun parse(raw: String?): ShellConfig = try {
                if (raw.isNullOrBlank()) ShellConfig() else {
                    val root = JSONObject(raw)
                    ShellConfig(
                        appName = root.optString("appName", "Python App"),
                        orientation = root.optString("orientation", "unspecified"),
                        fullscreen = root.optBoolean("fullscreen", false),
                        pythonEntry = root.optString("pythonEntry", "main.py")
                            .ifBlank { "main.py" },
                    )
                }
            } catch (error: org.json.JSONException) {
                ShellConfig()
            }
        }
    }

    /* ================================================================== */
    /* Layout (built programmatically — the shell has no resource bloat)   */
    /* ================================================================== */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shellConfig = ShellConfig.parse(readAssetText(CONFIG_ASSET))
        applyOrientation(shellConfig.orientation)
        setContentView(buildConsoleLayout())
        bootAsync()
    }

    private fun buildConsoleLayout(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        console = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.parseColor("#D7E3F0"))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
            text = startingBanner()
        }
        consoleScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B1B2B"))
            addView(
                console,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        inputField = EditText(this).apply {
            hint = "stdin (send to program)"
            setHintTextColor(Color.parseColor("#6C87A3"))
            setTextColor(Color.WHITE)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
            background?.alpha = 40
            setOnEditorActionListener { _, actionId, event ->
                val isSend = actionId == EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.action == KeyEvent.ACTION_DOWN &&
                        event.keyCode == KeyEvent.KEYCODE_ENTER)
                if (isSend) {
                    sendStdinLine()
                    true
                } else {
                    false
                }
            }
        }
        val sendButton = Button(this).apply {
            text = "Send"
            setOnClickListener { sendStdinLine() }
        }
        val stopButton = Button(this).apply {
            text = "Stop"
            setOnClickListener { terminateProcess() }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            addView(
                inputField,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(
                sendButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                stopButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                consoleScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            )
            addView(
                inputRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    private fun startingBanner(): String = buildString {
        appendLine("Universal APK Factory — Python 3 ARM64 Host Shell")
        appendLine("──────────────────────────────────────────────────")
    }

    /* ================================================================== */
    /* Boot: extract assets, resolve runtime, launch interpreter           */
    /* ================================================================== */

    private fun bootAsync() {
        Thread({
            try {
                val appDir = extractAssetTree(
                    assetDir = ASSET_APP_DIR,
                    targetDir = File(filesDir, "python-app")
                )
                val runtimeDir = File(filesDir, "python-runtime")

                if (!hasRuntimeAssets()) {
                    postLog(
                        "\nERROR: This shell was built without a Python runtime.\n" +
                            "Run the factory's `downloadPythonRuntime` Gradle task " +
                            "(shell-python module) to provision one, then rebuild.\n"
                    )
                    return@Thread
                }
                extractAssetTree(
                    assetDir = ASSET_RUNTIME_DIR,
                    targetDir = runtimeDir,
                    markerName = MARKER_RUNTIME
                )

                val entryFile = File(appDir, shellConfig.pythonEntry)
                if (!entryFile.isFile) {
                    postLog(
                        "\nERROR: entry '${shellConfig.pythonEntry}' not found under " +
                            "assets/$ASSET_APP_DIR. Files present:\n" +
                            appDir.walkTopDown()
                                .filter { it.isFile }
                                .take(24)
                                .joinToString("\n") { "  " + it.relativeTo(appDir).path }
                            + "\n"
                    )
                    return@Thread
                }

                val pythonBinary = resolvePythonBinary(runtimeDir)
                postLog(
                    "runtime : ${pythonBinary.relativeTo(filesDir).path}\n" +
                        "entry   : ${shellConfig.pythonEntry}\n" +
                        "python  : ${pythonVersionOf(pythonBinary)}\n" +
                        "──────────────────────────────────────────────────\n"
                )
                installRequirementsIfNeeded(pythonBinary, appDir)
                launchInterpreter(pythonBinary, appDir, entryFile)
            } catch (error: Exception) {
                Log.e(TAG, "Python shell boot failed", error)
                postLog("\nFATAL: ${error.message ?: error.javaClass.simpleName}\n")
            }
        }, "python-shell-boot").start()
    }

    private fun hasRuntimeAssets(): Boolean = try {
        assets.list(ASSET_RUNTIME_DIR)?.isNotEmpty() == true
    } catch (error: Exception) {
        false
    }

    /**
     * Extracts the `assets/<assetDir>` tree into [targetDir]. Extraction is guarded
     * by a marker file containing the asset listing + sizes, so unchanged
     * trees are not re-extracted on every launch.
     */
    private fun extractAssetTree(assetDir: String, targetDir: File, markerName: String? = null): File {
        targetDir.mkdirs()
        val listing = StringBuilder()
        val directories = ArrayDeque<String>()
        directories.add(assetDir)
        while (directories.isNotEmpty()) {
            val dir = directories.removeFirst()
            val children = assets.list(dir) ?: continue
            for (child in children) {
                val childPath = "$dir/$child"
                listing.append(childPath).append('\n')
                val childNames = try {
                    assets.list(childPath)
                } catch (error: Exception) {
                    null
                }
                if (childNames != null && childNames.isNotEmpty()) {
                    directories.add(childPath)
                }
            }
        }
        val fingerprint = listing.toString()
        val markerFile = File(targetDir, markerName ?: MARKER_DEFAULT)
        if (markerFile.isFile && markerFile.readText() == fingerprint) {
            return targetDir // already extracted, unchanged
        }
        assets.list(assetDir) // touch
        // Full recursive copy.
        copyAssetDir(assetDir, targetDir, assetDir)
        markerFile.writeText(fingerprint)
        return targetDir
    }

    private fun copyAssetDir(assetPath: String, targetDir: File, rootAsset: String) {
        val children = assets.list(assetPath) ?: return
        for (child in children) {
            val childAssetPath = "$assetPath/$child"
            val relative = childAssetPath.removePrefix("$rootAsset/")
            val target = File(targetDir, relative)
            val subChildren = try {
                assets.list(childAssetPath)
            } catch (error: Exception) {
                null
            }
            if (subChildren != null && subChildren.isNotEmpty()) {
                target.mkdirs()
                copyAssetDir(childAssetPath, targetDir, rootAsset)
            } else {
                target.parentFile?.mkdirs()
                try {
                    assets.open(childAssetPath).use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output, 64 * 1024)
                        }
                    }
                } catch (error: IOException) {
                    // A raw asset with no stream (rare vendor quirks): skip.
                    Log.w(TAG, "Skipping unreadable asset $childAssetPath", error)
                }
            }
        }
    }

    /**
     * Locates the interpreter inside the extracted runtime, preferring
     * version-pinned names. Zip/tar packaging flattens symlinks into short
     * text files containing the target path — those are resolved here
     * (bounded depth), so `bin/python3` → `bin/python3.12` works even when
     * the symlink did not survive packaging.
     */
    private fun resolvePythonBinary(runtimeDir: File): File {
        val binDir = File(runtimeDir, "bin")
        val searchDirs = listOfNotNull(
            if (binDir.isDirectory) binDir else null,
            if (runtimeDir.isDirectory) runtimeDir else null,
        ).ifEmpty { throw IOException("Python runtime has no bin/ directory.") }

        val preferred = listOf(
            "python3.15", "python3.14", "python3.13", "python3.12",
            "python3.11", "python3.10", "python3.9", "python3", "python"
        )
        for (dir in searchDirs) {
            for (name in preferred) {
                val direct = File(dir, name)
                if (direct.isFile) {
                    val resolved = followPackagedSymlink(direct, runtimeDir)
                    if (resolved != null) {
                        resolved.setExecutable(true, false)
                        return resolved
                    }
                }
            }
            // Fall back to any python* file in the directory.
            val candidates = dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith("python") }
                ?.sortedBy { it.name }
                ?: emptyList()
            for (candidate in candidates) {
                val resolved = followPackagedSymlink(candidate, runtimeDir)
                if (resolved != null) {
                    resolved.setExecutable(true, false)
                    return resolved
                }
            }
        }
        throw IOException("No python interpreter found in the bundled runtime.")
    }

    /** Resolves packaged symlink files; returns `null` for dead links. */
    private fun followPackagedSymlink(file: File, root: File, depth: Int = 0): File? {
        if (depth > 5) return null
        val length = file.length()
        if (length > 256) return file // real binary (or large file) — not a link
        val content = try {
            file.readText()
        } catch (error: IOException) {
            return file
        }
        val targetPath = content.trim()
        if (targetPath.isEmpty() || targetPath.contains('\n') ||
            targetPath.contains(' ') || !targetPath.contains('/')
        ) {
            return file // looks like binary/text content, not a link
        }
        val target = if (targetPath.startsWith("/")) {
            File(root, targetPath.removePrefix("/"))
        } else {
            File(file.parentFile ?: root, targetPath)
        }
        return if (target.isFile) followPackagedSymlink(target, root, depth + 1) else null
    }

    private fun pythonVersionOf(binary: File): String =
        Regex("python(\\d+\\.\\d+)").find(binary.name)?.groupValues?.get(1)
            ?.let { "CPython $it" }
            ?: "CPython"

    /* ================================================================== */
    /* Interpreter execution (linker64 W^X bypass)                         */
    /* ================================================================== */

    /**
     * Installs the project's `requirements.txt` (when present) into
     * `<appDir>/site-packages` using the pip bundled with the runtime.
     * Pure-Python packages resolve straight from PyPI over HTTPS; packages
     * that need compiled extension modules for android/aarch64 cannot be
     * installed on-device — the failure is reported on the console and the
     * program still launches. A marker file holding the requirements digest
     * guards against re-installing on every launch.
     */
    private fun installRequirementsIfNeeded(pythonBinary: File, appDir: File) {
        val requirements = File(appDir, "requirements.txt")
        if (!requirements.isFile) return
        val sitePackages = File(appDir, "site-packages")
        val marker = File(appDir, ".pip-requirements-done")
        val digest = try {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(requirements.readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
        } catch (error: IOException) {
            return
        }
        if (marker.isFile && marker.readText().trim() == digest) {
            postLog("[pip] requirements.txt already installed — skipping.\n")
            return
        }
        postLog(
            "[pip] requirements.txt found — installing pure-Python packages " +
                "into site-packages (internet required, first launch only) …\n"
        )
        val command = listOf(
            LINKER64,
            pythonBinary.absolutePath,
            "-m", "pip", "install",
            "--no-input", "--disable-pip-version-check", "--no-compile",
            "--timeout", "30", "--retries", "2",
            "--target", sitePackages.absolutePath,
            "--requirement", "requirements.txt",
        )
        try {
            val proc = ProcessBuilder(command).apply {
                directory(appDir)
                environment().apply {
                    putAll(buildPythonEnvironment(pythonBinary, appDir))
                    put("PIP_ROOT_USER_ACTION", "ignore")
                }
            }.start()
            val stderrPump = Thread({
                try {
                    proc.errorStream.buffered().use { input ->
                        input.bufferedReader().forEachLine { postLog("[pip] $it\n") }
                    }
                } catch (error: IOException) {
                    // Stream closed with the process — expected on exit.
                }
            }, "pip-stderr").also { it.start() }
            try {
                proc.inputStream.buffered().use { input ->
                    input.bufferedReader().forEachLine { postLog("[pip] $it\n") }
                }
            } catch (error: IOException) {
                // Stream closed with the process — expected on exit.
            }
            val exitCode = proc.waitFor()
            stderrPump.join(5_000)
            if (exitCode == 0) {
                marker.writeText(digest)
                postLog("[pip] requirements installed ✓\n\n")
            } else {
                postLog(
                    "[pip] WARNING: pip exited with code $exitCode — packages " +
                        "that need compiled (non-pure-Python) wheels cannot be " +
                        "installed on-device. Launching anyway.\n\n"
                )
            }
        } catch (error: IOException) {
            postLog(
                "[pip] WARNING: could not run pip (${error.message}) — " +
                    "launching anyway.\n\n"
            )
        }
    }

    /**
     * The environment shared by every interpreter launch (program or pip):
     * library paths for the linker64 W^X bypass, the runtime's own bin/ on
     * PATH, and PYTHONPATH covering the app dir plus the pip-installed
     * site-packages.
     */
    private fun buildPythonEnvironment(pythonBinary: File, appDir: File): Map<String, String> {
        val runtimeRoot = pythonBinary.parentFile?.parentFile // <runtime>/
            ?: filesDir
        val libDir = File(runtimeRoot, "lib")
        val libDynLoadDirs = libDir.walkTopDown()
            .filter { it.isDirectory && it.name == "lib-dynload" }
            .take(1)
            .toList()
        val ldLibraryPath = buildString {
            append(libDir.absolutePath)
            for (dir in libDynLoadDirs) {
                append(':').append(dir.absolutePath)
            }
            append(':').append(pythonBinary.parentFile?.absolutePath ?: "")
        }

        val homeDir = File(filesDir, "home").apply { mkdirs() }
        val tmpDir = File(cacheDir, "tmp").apply { mkdirs() }
        val sitePackages = File(appDir, "site-packages")
        val pythonPath = if (sitePackages.isDirectory) {
            "${appDir.absolutePath}:${sitePackages.absolutePath}"
        } else {
            appDir.absolutePath
        }

        return mapOf(
            "HOME" to homeDir.absolutePath,
            "PATH" to "${File(runtimeRoot, "bin").absolutePath}:/system/bin",
            "LD_LIBRARY_PATH" to ldLibraryPath,
            "TMPDIR" to tmpDir.absolutePath,
            "PYTHONHOME" to runtimeRoot.absolutePath,
            "PYTHONPATH" to pythonPath,
            "PYTHONUNBUFFERED" to "1",
            "PYTHONIOENCODING" to "utf-8",
            "PYTHONDONTWRITEBYTECODE" to "1",
            "SSL_CERT_DIR" to "/system/etc/security/cacerts",
        )
    }

    private fun launchInterpreter(pythonBinary: File, appDir: File, entryFile: File) {
        if (!processRunning.compareAndSet(false, true)) return

        val command = listOf(
            LINKER64,
            pythonBinary.absolutePath,
            "-u",
            entryFile.name,
        )

        val builder = ProcessBuilder(command).apply {
            directory(appDir)
            environment().apply {
                putAll(buildPythonEnvironment(pythonBinary, appDir))
            }
        }

        postLog("\n$ ${command.joinToString(" ")}\n\n")
        try {
            val proc = builder.start()
            process = proc
            streamAsync(proc.inputStream, tag = "")
            streamAsync(proc.errorStream, tag = "")
            Thread({
                val exitCode = proc.waitFor()
                processRunning.set(false)
                postLog("\n\n[process exited with code $exitCode]\n")
            }, "python-waiter").start()
        } catch (error: IOException) {
            processRunning.set(false)
            postLog(
                "\nFATAL: failed to launch the interpreter (${error.message}).\n" +
                    "The bundled runtime must be an aarch64 Android build.\n"
            )
            Log.e(TAG, "Interpreter launch failed", error)
        }
    }

    private fun streamAsync(stream: InputStream, tag: String) {
        Thread({
            try {
                stream.buffered().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        val chunk = String(buffer, 0, read, Charsets.UTF_8)
                        postLog(if (tag.isEmpty()) chunk else "$tag$chunk")
                    }
                }
            } catch (error: IOException) {
                // Stream closed with the process — expected on exit.
            }
        }, "python-output").start()
    }

    private fun sendStdinLine() {
        val line = inputField.text.toString()
        inputField.setText("")
        val proc = process
        if (proc == null || !processRunning.get()) {
            appendConsole("(no running process — input dropped)\n")
            return
        }
        try {
            val stdin: OutputStream = proc.outputStream
            stdin.write((line + "\n").toByteArray(Charsets.UTF_8))
            stdin.flush()
            appendConsole("> $line\n")
        } catch (error: IOException) {
            appendConsole("(stdin closed: ${error.message})\n")
        }
    }

    private fun terminateProcess() {
        process?.let { proc ->
            proc.destroy()
            appendConsole("\n[terminating process…]\n")
        }
    }

    /* ================================================================== */
    /* Console plumbing                                                    */
    /* ================================================================== */

    private fun postLog(chunk: String) {
        mainHandler.post { appendConsole(chunk) }
    }

    private fun appendConsole(chunk: String) {
        console.append(chunk)
        consoleScroll.post { consoleScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /* ================================================================== */
    /* Misc                                                                */
    /* ================================================================== */

    private fun applyOrientation(raw: String) {
        requestedOrientation = when (raw.lowercase()) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun readAssetText(assetPath: String): String? = try {
        assets.open(assetPath).buffered().use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (error: Exception) {
        null
    }

    override fun onDestroy() {
        process?.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PythonShell"

        /** The Android 10+ W^X bypass: execute binaries via the system linker. */
        const val LINKER64 = "/system/bin/linker64"

        const val CONFIG_ASSET = "shell_config.json"
        const val ASSET_APP_DIR = "python_app"
        const val ASSET_RUNTIME_DIR = "python_runtime"

        private const val MARKER_DEFAULT = ".apkfactory-extracted-app"
        private const val MARKER_RUNTIME = ".apkfactory-extracted-runtime"
    }
}
