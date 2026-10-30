package com.universal.apkfactory.core.python

import android.content.Context
import com.universal.apkfactory.core.ingestion.ZipRootNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * ============================================================================
 * PythonRuntimeManager — provisions the CPython aarch64-android runtime
 * ============================================================================
 *
 * MODE_FAST_PYTHON wraps the user's project into the Python host shell, which
 * needs a CPython 3 distribution (`bin/python3.x` + `lib/libpython3.x.so`,
 * aarch64-android, AndroidIDE/Termux style) bundled under
 * `assets/python_runtime/`. Since v5 the factory APK *ships with one*: CI
 * provisions a CPython aarch64-android tree (plus a tiny NDK-compiled
 * `Py_BytesMain` launcher at `bin/python3.x`) into the `:shell-python`
 * template, so MODE_FAST_PYTHON works out of the box — see
 * [bundledRuntimeInfo]. On top of that, this manager lets the user provision
 * a custom distribution at runtime which then *overrides* the bundle:
 *
 *  1. `install(url, sha256)` — streams the archive over OkHttp while
 *     computing the SHA-256 digest, rejects checksum mismatches, then
 *     extracts it into `filesDir/python-runtime-distro`.
 *  2. Extraction speaks the **packaged-symlink convention** used by the
 *     Python shell: a tar symlink entry becomes a small UTF-8 *text file*
 *     whose content is the link target (e.g. `bin/python3` → `python3.12`).
 *     The shell resolves these at first launch (see `followPackagedSymlink`
 *     in PythonHostShellActivity).
 *  3. `validate()` — the layout must contain a python binary under `bin/`
 *     and `lib/libpython3*.so`; the exact version is parsed from the name.
 *  4. At patch time [FastApkPatcherAndEditor] injects the whole tree under
 *     `assets/python_runtime/` of the generated APK.
 */
class PythonRuntimeManager(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {

    /* ====================================================================== */
    /* Public model                                                           */
    /* ====================================================================== */

    /** Installed-runtime descriptor persisted next to the distro. */
    data class RuntimeInfo(
        val pythonVersion: String,
        val pythonBinaryPath: String,
        val fileCount: Int,
        val totalBytes: Long,
        val installedAt: Long,
        val sourceUrl: String,
    ) {
        val displaySize: String
            get() = if (totalBytes >= 1L shl 30) {
                String.format("%.1f GiB", totalBytes / 1024.0 / 1024.0 / 1024.0)
            } else {
                String.format("%.1f MiB", totalBytes / 1024.0 / 1024.0)
            }
    }

    class RuntimeException(message: String, cause: Throwable? = null) :
        java.io.IOException(message, cause)

    /* ====================================================================== */
    /* Installation                                                           */
    /* ====================================================================== */

    /**
     * Downloads and installs a CPython aarch64-android distribution.
     *
     * @param url direct https URL of a `.tar.gz`, `.tgz` or `.zip` archive.
     * @param expectedSha256 hex SHA-256 of the archive (recommended; the
     *   download is rejected when it does not match).
     * @param onProgress receives a human-readable stage plus an optional
     *   0..1 fraction (null when indeterminate).
     */
    suspend fun install(
        url: String,
        expectedSha256: String?,
        onProgress: (stage: String, fraction: Float?) -> Unit = { _, _ -> },
    ): RuntimeInfo = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw RuntimeException("The runtime URL must start with http(s)://")
        }
        onProgress("Contacting $url …", null)

        val archive = File(context.cacheDir, "python-runtime-download.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        val response = try {
            httpClient.newCall(Request.Builder().url(url).build()).execute()
        } catch (error: Exception) {
            throw RuntimeException("Could not reach the runtime URL: ${error.message}", error)
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException("Download failed with HTTP ${resp.code}")
            }
            val body = resp.body ?: throw RuntimeException("Empty response body")
            val total = body.contentLength()
            if (total in 1 until MIN_ARCHIVE_BYTES) {
                throw RuntimeException(
                    "The archive is only $total bytes — this is not a CPython distribution."
                )
            }
            if (total > MAX_ARCHIVE_BYTES) {
                throw RuntimeException(
                    "The archive is larger than ${MAX_ARCHIVE_BYTES / (1L shl 20)} MiB."
                )
            }

            onProgress("Downloading runtime …", 0f)
            var read = 0L
            archive.outputStream().buffered().use { output ->
                body.byteStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        if (read > MAX_ARCHIVE_BYTES) {
                            throw RuntimeException("Download exceeded the size cap.")
                        }
                        if (total > 0) onProgress(
                            "Downloading runtime … ${read / (1L shl 20)} MiB",
                            read.toFloat() / total,
                        )
                    }
                }
            }
            if (read < MIN_ARCHIVE_BYTES) {
                throw RuntimeException(
                    "Downloaded only $read bytes — truncated or invalid archive."
                )
            }
        }

        // ---- checksum verification -------------------------------------
        if (!expectedSha256.isNullOrBlank()) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val wanted = expectedSha256.trim().lowercase().removePrefix("sha256:")
            if (actual != wanted) {
                archive.delete()
                throw RuntimeException(
                    "SHA-256 mismatch!\nExpected: $wanted\nActual:   $actual\n" +
                        "The archive was deleted — verify the URL and hash."
                )
            }
        }

        // ---- extraction --------------------------------------------------
        val distroDir = File(context.filesDir, DISTRO_DIR)
        distroDir.deleteRecursively()
        distroDir.mkdirs()
        onProgress("Extracting …", null)
        try {
            val looksZip = archive.name.endsWith(".zip") ||
                (url.substringBefore('?').endsWith(".zip"))
            if (looksZip) {
                extractZip(archive, distroDir)
            } else {
                extractTar(archive, distroDir) { done, totalFiles ->
                    onProgress("Extracting … ($done/$totalFiles files)", null)
                }
            }
        } finally {
            archive.delete()
        }

        // ---- validation + meta ------------------------------------------
        val info = validate(distroDir, url)
        writeMeta(info)
        onProgress("Python ${info.pythonVersion} ready ✓", null)
        info
    }

    /**
     * Validates the extracted layout and returns the runtime descriptor.
     * Strips a single wrapper directory (`cpython-3.12-aarch64/…`) when the
     * `bin/` + `lib/` pair sits one level down.
     */
    fun validate(root: File, sourceUrl: String = "manual"): RuntimeInfo {
        var dir = root
        if (!File(dir, "bin").isDirectory || !File(dir, "lib").isDirectory) {
            val wrapper = dir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            val nested = wrapper.firstOrNull {
                File(it, "bin").isDirectory && File(it, "lib").isDirectory
            }
            if (nested != null) {
                dir = nested
            } else {
                throw RuntimeException(
                    "The archive does not look like a CPython distribution — " +
                        "expected bin/python3.x and lib/libpython3.x.so."
                )
            }
        }

        val binDir = File(dir, "bin")
        val pythonBin = binDir.listFiles()
            ?.filter { it.name.startsWith("python3") }
            ?.firstOrNull { it.length() > SYMLINK_TEXT_LIMIT }
            ?: throw RuntimeException("No python3 binary found under bin/.")

        val libDir = File(dir, "lib")
        val libPython = libDir.listFiles()
            ?.firstOrNull { it.isFile && it.name.startsWith("libpython3") && it.name.endsWith(".so") }
            ?: throw RuntimeException("lib/libpython3.x.so not found in the runtime.")

        val version = Regex("python(3\\.\\d+)").find(pythonBin.name)
            ?.groupValues?.get(1)
            ?: "3.x"

        val files = dir.walkTopDown().filter { it.isFile }.toList()
        return RuntimeInfo(
            pythonVersion = version,
            pythonBinaryPath = pythonBin.relativeTo(root).invariantSeparatorsPath,
            fileCount = files.size,
            totalBytes = files.sumOf { it.length() },
            installedAt = System.currentTimeMillis(),
            sourceUrl = sourceUrl,
        )
    }

    /* ====================================================================== */
    /* State                                                                  */
    /* ====================================================================== */

    fun isInstalled(): Boolean = info() != null

    /** The validated runtime root, or `null` when not installed. */
    fun runtimeRoot(): File? {
        val info = info() ?: return null
        val root = File(context.filesDir, DISTRO_DIR)
        val binary = File(root, info.pythonBinaryPath)
        return if (binary.isFile) root else null
    }

    fun info(): RuntimeInfo? {
        val meta = File(context.filesDir, "$DISTRO_DIR/meta.json")
        if (!meta.isFile) return null
        return try {
            val json = JSONObject(meta.readText())
            RuntimeInfo(
                pythonVersion = json.optString("pythonVersion"),
                pythonBinaryPath = json.optString("pythonBinaryPath"),
                fileCount = json.optInt("fileCount"),
                totalBytes = json.optLong("totalBytes"),
                installedAt = json.optLong("installedAt"),
                sourceUrl = json.optString("sourceUrl"),
            )
        } catch (error: Exception) {
            null
        }
    }

    fun clear() {
        File(context.filesDir, DISTRO_DIR).deleteRecursively()
    }

    /* ====================================================================== */
    /* Bundled runtime (inside the python shell template asset)               */
    /* ====================================================================== */

    /**
     * Describes the CPython distribution that ships *inside* the factory APK:
     * `assets/shells/python_runtime_shell.apk` carries a full runtime tree
     * under `assets/python_runtime/` (provisioned at factory build time by
     * CI). MODE_FAST_PYTHON works out of the box with this bundle; a custom
     * runtime installed via [install] simply overrides it at patch time.
     *
     * The probe result is cached in SharedPreferences keyed by the factory's
     * `versionCode`, so the (relatively costly) shell-APK inspection runs at
     * most once per app version. Runs disk IO — call from a background
     * dispatcher.
     */
    fun bundledRuntimeInfo(): RuntimeInfo? {
        val versionCode = packageVersionCode()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(PREF_BUNDLED_PROBE, null)
        if (cached != null) {
            try {
                val json = JSONObject(cached)
                if (json.optLong("versionCode") == versionCode) {
                    val info = json.optJSONObject("info") ?: return null
                    return decodeRuntimeInfo(info)
                }
            } catch (error: Exception) {
                // Corrupt cache — fall through and re-probe.
            }
        }
        val probed = probeBundledShellRuntime()
        val payload = JSONObject().apply {
            put("versionCode", versionCode)
            probed?.let { put("info", encodeRuntimeInfo(it)) }
        }
        prefs.edit().putString(PREF_BUNDLED_PROBE, payload.toString()).apply()
        return probed
    }

    /** True when the bundled shell template carries a runtime tree. */
    fun hasBundledRuntime(): Boolean = bundledRuntimeInfo() != null

    /**
     * Opens the shell template APK (copying it out of the packaged assets on
     * first probe) and validates the `assets/python_runtime/` layout the same
     * way [validate] does for an extracted distro.
     */
    private fun probeBundledShellRuntime(): RuntimeInfo? {
        val shellApk = File(context.cacheDir, "shells/$SHELL_ASSET_NAME")
        if (!shellApk.isFile) {
            try {
                context.assets.open("shells/$SHELL_ASSET_NAME").use { input ->
                    shellApk.parentFile?.mkdirs()
                    val tmp = File(shellApk.parentFile, "$SHELL_ASSET_NAME.probe.tmp")
                    tmp.outputStream().buffered().use { output ->
                        input.copyTo(output, 64 * 1024)
                    }
                    if (!tmp.renameTo(shellApk)) tmp.delete()
                }
            } catch (error: Exception) {
                return null // no shell asset (unexpected) — treat as absent
            }
        }
        if (!shellApk.isFile) return null
        val prefix = "assets/$BUNDLED_ASSET_DIR/"
        return try {
            ZipFile(shellApk).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(prefix) }
                    .toList()
                if (entries.isEmpty()) return@use null
                val binEntry = entries
                    .filter { BIN_BINARY_REGEX.containsMatchIn(it.name) }
                    .filter { it.size > SYMLINK_TEXT_LIMIT }
                    .maxByOrNull { it.size }
                val libEntry = entries.firstOrNull { LIB_PYTHON_REGEX.containsMatchIn(it.name) }
                if (binEntry == null || libEntry == null) return@use null
                val version = Regex("python(3\\.\\d+)").find(binEntry.name)
                    ?.groupValues?.get(1) ?: "3.x"
                RuntimeInfo(
                    pythonVersion = version,
                    pythonBinaryPath = binEntry.name.removePrefix(prefix),
                    fileCount = entries.size,
                    totalBytes = entries.sumOf { if (it.size > 0) it.size else 0L },
                    installedAt = 0L,
                    sourceUrl = "bundled:$SHELL_ASSET_NAME",
                )
            }
        } catch (error: Exception) {
            null
        }
    }

    private fun packageVersionCode(): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    } catch (error: Exception) {
        0L
    }

    private fun encodeRuntimeInfo(info: RuntimeInfo): JSONObject = JSONObject().apply {
        put("pythonVersion", info.pythonVersion)
        put("pythonBinaryPath", info.pythonBinaryPath)
        put("fileCount", info.fileCount)
        put("totalBytes", info.totalBytes)
        put("installedAt", info.installedAt)
        put("sourceUrl", info.sourceUrl)
    }

    private fun decodeRuntimeInfo(json: JSONObject): RuntimeInfo = RuntimeInfo(
        pythonVersion = json.optString("pythonVersion"),
        pythonBinaryPath = json.optString("pythonBinaryPath"),
        fileCount = json.optInt("fileCount"),
        totalBytes = json.optLong("totalBytes"),
        installedAt = json.optLong("installedAt"),
        sourceUrl = json.optString("sourceUrl"),
    )

    /**
     * The directory that directly contains `bin/` + `lib/` (identical to
     * [runtimeRoot] except when the archive had a single wrapper folder).
     * This is the tree the patcher injects under `assets/python_runtime/`.
     */
    fun runtimeContentRoot(): File? {
        val root = runtimeRoot() ?: return null
        val info = info() ?: return null
        val binDir = File(root, info.pythonBinaryPath).parentFile ?: return null
        return binDir.parentFile?.takeIf {
            File(it, "lib").isDirectory && File(it, "bin").isDirectory
        }
    }

    /* ====================================================================== */
    /* Extractors                                                             */
    /* ====================================================================== */

    /** Zip path: entries are copied verbatim (a prepared runtime may already
     *  use the text-symlink convention). */
    private fun extractZip(archive: File, targetDir: File) {
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory }
                .filter { !ZipRootNormalizer.isPayloadJunkEntry(it.name) }
                .toList()
            for (entry in entries) {
                val safe = safeTarget(targetDir, entry.name) ?: continue
                safe.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    safe.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /**
     * Minimal tar reader with symlink preservation. Regular entries are
     * written as files; symlink / hardlink entries become small text files
     * containing the link target (the packaged-symlink convention). GNU
     * long names ('L') and the ustar prefix field are supported.
     */
    private fun extractTar(
        archive: File,
        targetDir: File,
        onFile: (done: Int, total: Int) -> Unit,
    ) {
        val input: InputStream = FileInputStream(archive).buffered().let { raw ->
            val magic = ByteArray(2)
            raw.mark(2)
            val n = raw.read(magic)
            raw.reset()
            if (n == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) {
                GZIPInputStream(raw, 64 * 1024)
            } else {
                raw
            }
        }
        var pendingLongName: String? = null
        var done = 0
        // First pass to count regular files for progress reporting.
        val total = countTarRegularFiles(archive)
        input.use { stream ->
            val header = ByteArray(512)
            while (true) {
                if (!readFully(stream, header)) break
                if (header.all { it == 0.toByte() }) break // end-of-archive
                val rawName = (pendingLongName ?: parseHeaderName(header))
                pendingLongName = null
                val size = parseHeaderSize(header)
                val typeFlag = header[156].toInt() and 0xFF
                val linkName = cString(header, 157, 100)

                when (typeFlag) {
                    'L'.code -> { // GNU long name — applies to the next entry
                        if (size > MAX_LONG_NAME_BYTES) {
                            skipData(stream, size)
                        } else {
                            val longBytes = ByteArray(size.toInt())
                            readFully(stream, longBytes)
                            pendingLongName = cString(longBytes, 0, longBytes.size)
                        }
                        skipPadding(stream, size)
                        continue
                    }
                    '5'.code -> { // directory
                        val dir = safeTarget(targetDir, rawName)
                        dir?.mkdirs()
                        skipPadding(stream, size)
                        continue
                    }
                    '0'.code, 0 -> { // regular file
                        val target = safeTarget(targetDir, rawName)
                        if (target != null) {
                            target.parentFile?.mkdirs()
                            var left = size
                            target.outputStream().buffered().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (left > 0) {
                                    val chunk = minOf(buffer.size.toLong(), left).toInt()
                                    readFully(stream, buffer, chunk)
                                    out.write(buffer, 0, chunk)
                                    left -= chunk
                                }
                            }
                        } else {
                            skipData(stream, size)
                        }
                        skipPadding(stream, size)
                        done++
                        onFile(done, total)
                    }
                    '1'.code, '2'.code -> { // hardlink / symlink → text file
                        val target = safeTarget(targetDir, rawName)
                        if (target != null) {
                            target.parentFile?.mkdirs()
                            target.writeText(linkName)
                        }
                        skipPadding(stream, size)
                    }
                    else -> { // char/block/fifo/etc — skip payload
                        skipData(stream, size)
                    }
                }
            }
        }
    }

    /** Counts regular tar entries (bounded single pass, no extraction). */
    private fun countTarRegularFiles(archive: File): Int {
        return try {
            val input: InputStream = FileInputStream(archive).buffered().let { raw ->
                val magic = ByteArray(2)
                raw.mark(2)
                val n = raw.read(magic)
                raw.reset()
                if (n == 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte()) {
                    GZIPInputStream(raw, 64 * 1024)
                } else {
                    raw
                }
            }
            input.use { stream ->
                val header = ByteArray(512)
                var count = 0
                while (true) {
                    if (!readFully(stream, header)) break
                    if (header.all { it == 0.toByte() }) break
                    val size = parseHeaderSize(header)
                    val typeFlag = header[156].toInt() and 0xFF
                    if (typeFlag == 'L'.code) {
                        skipData(stream, size)
                        skipPadding(stream, size)
                        continue
                    }
                    if (typeFlag == '0'.code || typeFlag == 0 || typeFlag == '2'.code) count++
                    skipData(stream, size)
                    skipPadding(stream, size)
                }
                count
            }
        } catch (error: Exception) {
            0
        }
    }

    /* ---------------------------------------------------------------------- */
    /* Low-level tar helpers                                                  */
    /* ---------------------------------------------------------------------- */

    private fun parseHeaderSize(header: ByteArray): Long {
        val sizeField = cString(header, 124, 12).trim(' ')
        return if (sizeField.isEmpty()) 0L else sizeField.toLong(8)
    }

    /** Parses the tar name field (0..100) plus the ustar prefix (345..500). */
    private fun parseHeaderName(header: ByteArray): String {
        val name = cString(header, 0, 100)
        val prefix = cString(header, 345, 155)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun cString(bytes: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { bytes[it] == 0.toByte() }
            ?: (offset + length)
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    private fun readFully(stream: InputStream, buffer: ByteArray, count: Int = buffer.size): Boolean {
        var read = 0
        while (read < count) {
            val n = stream.read(buffer, read, count - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun skipData(stream: InputStream, size: Long) {
        var left = size
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped <= 0) {
                if (stream.read() < 0) return
                left--
            } else {
                left -= skipped
            }
        }
    }

    private fun skipPadding(stream: InputStream, size: Long) {
        val rem = size % 512
        if (rem != 0L) skipData(stream, 512 - rem)
    }

    /** Zip-slip guard: resolves the entry inside [targetDir] or `null`. */
    private fun safeTarget(targetDir: File, entryName: String): File? {
        val clean = entryName.replace('\\', '/').trimStart('/')
        if (clean.isEmpty() || clean.contains("..")) return null
        val target = File(targetDir, clean)
        if (!target.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
            return null
        }
        return target
    }

    private fun writeMeta(info: RuntimeInfo) {
        val root = File(context.filesDir, DISTRO_DIR)
        if (!root.isDirectory) root.mkdirs()
        val meta = File(root, "meta.json")
        meta.writeText(
            JSONObject().apply {
                put("pythonVersion", info.pythonVersion)
                put("pythonBinaryPath", info.pythonBinaryPath)
                put("fileCount", info.fileCount)
                put("totalBytes", info.totalBytes)
                put("installedAt", info.installedAt)
                put("sourceUrl", info.sourceUrl)
            }.toString(2)
        )
    }

    companion object {
        const val DISTRO_DIR = "python-runtime-distro"

        /** Shell template asset that carries the bundled runtime tree. */
        const val SHELL_ASSET_NAME = "python_runtime_shell.apk"

        /** Asset directory of the runtime inside the shell template APK. */
        const val BUNDLED_ASSET_DIR = "python_runtime"

        private const val PREFS_NAME = "factory_prefs"
        private const val PREF_BUNDLED_PROBE = "bundled_python_runtime_probe"

        /** `assets/python_runtime/bin/python3.x` inside the shell template. */
        private val BIN_BINARY_REGEX = Regex("bin/python3(\\.\\d+)?$")

        /** `assets/python_runtime/lib/libpython3.x.so` inside the shell. */
        private val LIB_PYTHON_REGEX = Regex("lib/libpython3(\\.\\d+)?\\.so$")

        /** Files at or below this size are treated as packaged symlinks. */
        const val SYMLINK_TEXT_LIMIT = 256L

        private const val MIN_ARCHIVE_BYTES = 5L * 1024 * 1024      // 5 MiB
        private const val MAX_ARCHIVE_BYTES = 400L * 1024 * 1024    // 400 MiB
        private const val MAX_LONG_NAME_BYTES = 64L * 1024          // 64 KiB
    }
}
