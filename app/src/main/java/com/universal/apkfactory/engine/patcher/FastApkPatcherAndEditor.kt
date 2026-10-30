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
package com.universal.apkfactory.engine.patcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.reandroid.apk.ApkModule
import com.reandroid.app.AndroidManifest
import com.reandroid.archive.Archive
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.FileInputSource
import com.reandroid.archive.InputSource
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.chunk.xml.ResXmlAttribute
import com.universal.apkfactory.core.ingestion.ZipRootNormalizer
import com.universal.apkfactory.core.model.AppOrientation
import com.universal.apkfactory.core.model.IngestedSource
import com.universal.apkfactory.core.model.PermissionCatalog
import com.universal.apkfactory.core.model.ProjectBuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Zero-Gradle Instant Patcher, Shell Generator & APK Editor
 * (Module 3 — engine-fast-patcher).
 *
 * Pipeline (all through ARSCLib's `ApkModule`):
 *
 *  1. Load `webview_modern_shell.apk`, `python_runtime_shell.apk`, or the
 *     user's input `.apk` (MODE_APK_EDITOR) via [ApkModule.loadApkFile].
 *  2. Mutate the [AndroidManifestBlock]:
 *     * `packageName` (and every `<provider android:authorities>` rewritten
 *       so cloned apps do not collide with the originals),
 *     * `versionCode`, `versionName`, `android:label`,
 *     * `<uses-permission>` elements added/removed from the user selection,
 *     * `android:screenOrientation` on the launcher activity.
 *  3. Replace the launcher icons in `res/mipmap-*` (legacy, round and both
 *     adaptive layers) with the user icon scaled to the standard DPI grid.
 *  4. Stream-inject the normalized payload zip under `assets/www/` or
 *     `assets/python_app/` plus `assets/shell_config.json`.
 *  5. Strip stale META-INF signatures and write the unaligned APK with
 *     `resources.arsc` and the native `lib` `.so` files stored **uncompressed**
 *     (`Archive.STORED`) so API 30+ devices accept it.
 *
 * The output of this engine is unaligned & unsigned; core-signer
 * (`ApkAlignerAndSigner`) performs the 4-byte alignment and V1/V2/V3
 * signing.
 */
class FastApkPatcherAndEditor(
    private val context: Context,
    /** Provisioned CPython runtime (optional; injected into PYTHON shells). */
    private val pythonRuntime: com.universal.apkfactory.core.python.PythonRuntimeManager? = null,
) {

    /* ================================================================== */
    /* Public API                                                          */
    /* ================================================================== */

    /** The shell templates shipped inside the factory's assets. */
    enum class ShellKind(
        val assetName: String,
        val payloadDir: String,
        val forcedPermissions: Set<String>,
    ) {
        WEBVIEW(
            assetName = "webview_modern_shell.apk",
            payloadDir = "assets/www/",
            forcedPermissions = setOf(android.Manifest.permission.INTERNET),
        ),
        PYTHON(
            assetName = "python_runtime_shell.apk",
            payloadDir = "assets/python_app/",
            forcedPermissions = setOf(android.Manifest.permission.INTERNET),
        ),
    }

    /** What the patcher operates on. */
    sealed interface PatchTarget {
        /** A bundled shell template (MODE_FAST_WEB / MODE_FAST_PYTHON). */
        data class Shell(val kind: ShellKind) : PatchTarget

        /** The user's existing APK (MODE_APK_EDITOR: modify/clone/re-sign). */
        data class ExistingApk(val apkFile: File) : PatchTarget
    }

    /** Everything the UI needs to know about a completed patch pass. */
    data class PatchOutcome(
        val outputApk: File,
        val packageName: String,
        val previousPackageName: String?,
        val versionName: String,
        val versionCode: Int,
        val permissionsAdded: List<String>,
        val permissionsRemoved: List<String>,
        val providerAuthoritiesRewritten: List<String>,
        val iconFilesReplaced: Int,
        val payloadFilesInjected: Int,
        val payloadBytes: Long,
    )

    class PatcherException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    /**
     * Executes the full patch pipeline.
     *
     * @param target the shell template or the APK being edited.
     * @param config the (user-confirmed) build configuration.
     * @param payload the normalized project source to inject; `null` for
     *        MODE_APK_EDITOR.
     * @param output destination for the unaligned, unsigned APK.
     * @param detected optional detector output; its `webRootDir` /
     *        `pythonEntry` feed the shell start configuration.
     * @param iconBitmap optional replacement launcher icon.
     */
    suspend fun patch(
        target: PatchTarget,
        config: ProjectBuildConfig,
        payload: IngestedSource?,
        output: File,
        detected: com.universal.apkfactory.core.model.DetectedProject? = null,
        iconBitmap: Bitmap? = null,
        onLog: (String) -> Unit = {},
    ): PatchOutcome = withContext(Dispatchers.IO) {
        val problems = config.validate()
        require(problems.isEmpty()) {
            "Invalid build configuration: ${problems.joinToString(" ")}"
        }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val baseApk = when (target) {
            is PatchTarget.Shell -> extractShellApk(target.kind, onLog)
            is PatchTarget.ExistingApk -> target.apkFile
        }
        val forcedPermissions = when (target) {
            is PatchTarget.Shell -> target.kind.forcedPermissions
            is PatchTarget.ExistingApk -> emptySet()
        }

        onLog("Loading ${baseApk.name} (${baseApk.length() / 1024} KiB) via ApkModule …")
        val module = ApkModule.loadApkFile(baseApk)
        try {
            val manifest = module.androidManifest
                ?: throw PatcherException(
                    "The base APK has no AndroidManifest.xml — cannot patch."
                )

            // ---- 2. Manifest mutation -----------------------------------
            val previousPackage = manifest.packageName
            val (added, removed) = syncPermissions(
                manifest, config, target, forcedPermissions, onLog
            )
            val authorities = mutateManifest(
                manifest, config, previousPackage, target, onLog
            )

            // ---- 3. Launcher icons --------------------------------------
            val iconsReplaced = if (iconBitmap != null) {
                replaceLauncherIcons(module, iconBitmap, onLog)
            } else {
                0
            }

            // ---- 4. Payload injection + shell config ---------------------
            var injectedFiles = 0
            var injectedBytes = 0L
            if (target is PatchTarget.Shell) {
                writeShellConfig(module, target, config, detected, payload, onLog)
                if (target.kind == ShellKind.PYTHON) {
                    val runtime = injectPythonRuntime(module, onLog)
                    injectedFiles += runtime.first
                    injectedBytes += runtime.second
                }
                when (payload) {
                    is IngestedSource.ZipArchive -> {
                        ZipFile(payload.workspaceFile).use { archive ->
                            val injected = injectZipPayload(
                                module, archive, payload.workspaceFile,
                                target.kind.payloadDir, onLog
                            )
                            injectedFiles += injected.first
                            injectedBytes += injected.second
                            // The write MUST happen while the archive is open:
                            // payload sources stream lazily from it.
                            finalizeWrite(module, output, onLog)
                        }
                    }

                    is IngestedSource.Folder -> {
                        val injected = injectFolderPayload(
                            module, payload.workspaceFile,
                            target.kind.payloadDir, onLog
                        )
                        injectedFiles += injected.first
                        injectedBytes += injected.second
                        finalizeWrite(module, output, onLog)
                    }

                    else -> {
                        onLog("No payload to inject (shell-only build).")
                        finalizeWrite(module, output, onLog)
                    }
                }
            } else {
                finalizeWrite(module, output, onLog)
            }

            PatchOutcome(
                outputApk = output,
                packageName = config.packageName,
                previousPackageName = previousPackage,
                versionName = config.versionName,
                versionCode = config.versionCode,
                permissionsAdded = added,
                permissionsRemoved = removed,
                providerAuthoritiesRewritten = authorities,
                iconFilesReplaced = iconsReplaced,
                payloadFilesInjected = injectedFiles,
                payloadBytes = injectedBytes,
            )
        } finally {
            try {
                module.close()
            } catch (error: IOException) {
                // Closing best-effort; the module is discardable.
            }
        }
    }

    /* ================================================================== */
    /* Shell extraction                                                    */
    /* ================================================================== */

    /**
     * Copies a bundled shell APK out of the factory's assets into the cache
     * so ARSCLib can memory-map it. Always refreshed — cache may survive app
     * updates and hold an outdated template.
     */
    fun extractShellApk(kind: ShellKind, onLog: (String) -> Unit = {}): File {
        val destination = File(context.cacheDir, "shells/${kind.assetName}")
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()
        try {
            context.assets.open("shells/${kind.assetName}").use { input ->
                destination.outputStream().buffered().use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
        } catch (error: Exception) {
            throw PatcherException(
                "Shell template '${kind.assetName}' is missing from the app " +
                    "assets. Run `./gradlew :app:assembleDebug` so the " +
                    "packageWebviewShell / packagePythonShell tasks populate " +
                    "app/src/main/assets/shells/.",
                error
            )
        }
        onLog("Extracted shell template ${kind.assetName} (${destination.length() / 1024} KiB).")
        return destination
    }

    /* ================================================================== */
    /* Manifest mutation                                                   */
    /* ================================================================== */

    private fun mutateManifest(
        manifest: AndroidManifestBlock,
        config: ProjectBuildConfig,
        previousPackage: String?,
        target: PatchTarget,
        onLog: (String) -> Unit,
    ): List<String> {
        // Package name (clone) — safe even when unchanged.
        if (config.packageName != previousPackage) {
            manifest.setPackageName(config.packageName)
            onLog("Package: $previousPackage → ${config.packageName}")
        }

        // Version + label.
        manifest.setVersionCode(config.versionCode)
        manifest.setVersionName(config.versionName)
        if (config.appName.isNotBlank()) {
            manifest.setApplicationLabel(config.appName)
            onLog("Label set to \"${config.appName}\"; version ${config.versionName} (${config.versionCode}).")
        }

        // Provider authorities (install-conflict prevention when cloning).
        val rewritten = rewriteProviderAuthorities(
            manifest, previousPackage, config.packageName, onLog
        )

        // Launcher activity orientation.
        if (config.orientation != AppOrientation.UNSPECIFIED) {
            applyOrientation(manifest, config.orientation, onLog)
        }

        return rewritten
    }

    /**
     * Syncs `<uses-permission>` elements with the user selection.
     *
     * In MODE_APK_EDITOR, permissions that are NOT part of the factory's
     * catalog (custom/vendor permissions declared by the original app) are
     * always preserved — the checkboxes only govern catalog entries.
     */
    private fun syncPermissions(
        manifest: AndroidManifestBlock,
        config: ProjectBuildConfig,
        target: PatchTarget,
        forcedPermissions: Set<String>,
        onLog: (String) -> Unit,
    ): Pair<List<String>, List<String>> {
        val existing = LinkedHashSet(manifest.getUsesPermissions())
        val catalog = PermissionCatalog.ALL_NAMES

        val preservedCustom = if (target is PatchTarget.ExistingApk) {
            existing.filterTo(LinkedHashSet()) { it !in catalog }
        } else {
            emptySet<String>()
        }

        val desired = buildSet {
            addAll(config.permissions)
            addAll(preservedCustom)
            addAll(forcedPermissions)
        }

        val toRemove = existing - desired
        val toAdd = desired - existing

        for (permission in toRemove) {
            if (removeUsesPermission(manifest, permission)) {
                onLog("Removed permission: ${PermissionCatalog.labelFor(permission)}")
            }
        }
        for (permission in toAdd) {
            manifest.addUsesPermission(permission)
            onLog("Added permission: ${PermissionCatalog.labelFor(permission)}")
        }
        return toAdd.toList() to toRemove.toList()
    }

    private fun removeUsesPermission(
        manifest: AndroidManifestBlock,
        permission: String,
    ): Boolean {
        val element = manifest.getUsesPermission(permission) ?: return false
        val manifestElement = manifest.manifestElement ?: return false
        return manifestElement.removeElementsIf { candidate ->
            candidate === element ||
                (candidate.getName(false) == AndroidManifest.TAG_uses_permission &&
                    AndroidManifestBlock.getAndroidNameValue(candidate) == permission)
        }
    }

    /**
     * Rewrites `<provider android:authorities>` values after a package
     * rename so cloned apps can be installed next to their originals.
     * Non-package authorities are namespaced under the new package id.
     */
    private fun rewriteProviderAuthorities(
        manifest: AndroidManifestBlock,
        previousPackage: String?,
        newPackage: String,
        onLog: (String) -> Unit,
    ): List<String> {
        if (previousPackage == null || previousPackage == newPackage) return emptyList()
        val rewritten = ArrayList<String>()
        val providers = manifest.listApplicationElementsByTag(AndroidManifest.TAG_provider)
        for (provider in providers) {
            val attribute = provider
                .searchAttributeByName(AndroidManifest.NAME_authorities) ?: continue
            val current = attribute.getValueAsString() ?: continue
            val updated = current.split(';').joinToString(";") { rawAuthority ->
                val authority = rawAuthority.trim()
                when {
                    authority.isEmpty() -> authority
                    authority == previousPackage -> newPackage
                    authority.startsWith("$previousPackage.") ->
                        newPackage + authority.removePrefix(previousPackage)

                    authority.contains(previousPackage) ->
                        authority.replace(previousPackage, newPackage)

                    else -> "$newPackage.$authority"
                }
            }
            if (updated != current) {
                attribute.setValueAsString(updated)
                rewritten.add("$current → $updated")
                onLog("Provider authority rewritten: $current → $updated")
            }
        }
        return rewritten
    }

    private fun applyOrientation(
        manifest: AndroidManifestBlock,
        orientation: AppOrientation,
        onLog: (String) -> Unit,
    ) {
        val value = orientation.manifestValue ?: return
        val activity = manifest.getMainActivity()
            ?: manifest.listActivities().firstOrNull()
        if (activity == null) {
            onLog("WARN: no activity found — screenOrientation not patched.")
            return
        }
        val attribute: ResXmlAttribute = activity.getOrCreateAndroidAttribute(
            AndroidManifest.NAME_screenOrientation,
            AndroidManifest.ID_screenOrientation,
        )
        attribute.setValueAsString(value)
        onLog("Launcher activity screenOrientation=$value.")
    }

    /* ================================================================== */
    /* Launcher icon replacement                                           */
    /* ================================================================== */

    /** DPI scale factors relative to mdpi (1x). */
    private val densityScales = mapOf(
        "mdpi" to 1.0f, "hdpi" to 1.5f, "xhdpi" to 2.0f,
        "xxhdpi" to 3.0f, "xxxhdpi" to 4.0f,
    )

    /** Base icon sizes (dp) on the launcher grid. */
    private val LEGACY_ICON_DP = 48
    private val ADAPTIVE_LAYER_DP = 108
    /** Visible safe-zone ratio of an adaptive icon (66/108). */
    private val ADAPTIVE_SAFE_ZONE = 66f / 108f

    private val iconEntryPattern = Regex(
        "^res/(?:mipmap|drawable)-([a-z]+)(?:-v\\d+)?/" +
            "(ic_launcher|ic_launcher_round|ic_launcher_foreground|ic_launcher_background)" +
            "\\.(png|webp|jpg)$"
    )

    /**
     * Replaces every raster launcher icon entry in `res/mipmap-*` /
     * `res/drawable-*` with renderings of [icon] scaled to the standard DPI
     * grid:
     *
     *  * `ic_launcher`          → 48dp grid, rounded-square legacy icon,
     *  * `ic_launcher_round`    → 48dp grid, circular legacy icon,
     *  * `ic_launcher_foreground` → 108dp grid, transparent adaptive layer
     *    with the icon center-cropped into the 66dp safe zone,
     *  * `ic_launcher_background` → 108dp grid, solid layer tinted with the
     *    icon's average color.
     *
     * Entries keep their original names/paths so `resources.arsc` stays
     * valid; BitmapFactory decodes by content, so a PNG payload under a
     * `.webp` name still renders.
     *
     * @return number of icon files replaced.
     */
    private fun replaceLauncherIcons(
        module: ApkModule,
        icon: Bitmap,
        onLog: (String) -> Unit,
    ): Int {
        val sources = module.zipEntryMap.toArray()
        var replaced = 0
        val averageColor = averageColorOf(icon)
        for (source in sources) {
            val name = source.getAlias()
            val match = iconEntryPattern.find(name) ?: continue
            val (density, iconName, _) = match.destructured
            val scale = densityScales[density]
            if (scale == null) {
                onLog("Skipping icon with unknown density '$density': $name")
                continue
            }
            val replacement: ByteArray = when (iconName) {
                "ic_launcher" -> renderLegacyIcon(
                    icon, (LEGACY_ICON_DP * scale).toInt().coerceAtLeast(16), round = false
                )

                "ic_launcher_round" -> renderLegacyIcon(
                    icon, (LEGACY_ICON_DP * scale).toInt().coerceAtLeast(16), round = true
                )

                "ic_launcher_foreground" -> renderAdaptiveForeground(
                    icon, (ADAPTIVE_LAYER_DP * scale).toInt().coerceAtLeast(32)
                )

                "ic_launcher_background" -> renderAdaptiveBackground(
                    averageColor, (ADAPTIVE_LAYER_DP * scale).toInt().coerceAtLeast(32)
                )

                else -> continue
            }
            // ByteInputSource replaces the map entry under the same name —
            // resources.arsc keeps pointing at the identical path. Raster
            // icons may be deflated; only arsc/.so have storage requirements.
            module.zipEntryMap.add(ByteInputSource(replacement, name))
            replaced++
        }
        if (replaced == 0) {
            onLog(
                "WARN: no raster launcher icons found — the app likely uses " +
                    "pure adaptive/vector icons; the original icon was kept."
            )
        } else {
            onLog("Replaced $replaced launcher icon file(s) across all densities.")
        }
        return replaced
    }

    private fun renderLegacyIcon(source: Bitmap, sizePx: Int, round: Boolean): ByteArray {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val clip = Path()
        if (round) {
            canvas.clipPath(
                clip.apply { addCircle(sizePx / 2f, sizePx / 2f, sizePx / 2f, Path.Direction.CW) }
            )
        } else {
            val radius = sizePx * 0.20f
            canvas.clipPath(
                clip.apply { addRoundRect(RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat()), radius, radius, Path.Direction.CW) }
            )
        }
        drawCenterCropped(canvas, source, sizePx, sizePx)
        return bitmap.encodePng()
    }

    private fun renderAdaptiveForeground(source: Bitmap, sizePx: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val safePx = (sizePx * ADAPTIVE_SAFE_ZONE).toInt().coerceAtLeast(8)
        val offsetX = (sizePx - safePx) / 2
        val offsetY = (sizePx - safePx) / 2
        drawCenterCropped(
            canvas, source, safePx, safePx, offsetX.toFloat(), offsetY.toFloat()
        )
        return bitmap.encodePng()
    }

    private fun renderAdaptiveBackground(color: Int, sizePx: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap.encodePng()
    }

    private fun drawCenterCropped(
        canvas: Canvas,
        source: Bitmap,
        width: Int,
        height: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val scale = maxOf(
            width.toFloat() / source.width,
            height.toFloat() / source.height
        )
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val left = offsetX + (width - drawWidth) / 2f
        val top = offsetY + (height - drawHeight) / 2f
        canvas.drawBitmap(source, null, RectF(left, top, left + drawWidth, top + drawHeight), paint)
    }

    /** Average color of the source bitmap (downsampled), slightly darkened. */
    private fun averageColorOf(source: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(source, 16, 16, true)
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0
        for (y in 0 until small.height) {
            for (x in 0 until small.width) {
                val pixel = small.getPixel(x, y)
                red += Color.red(pixel)
                green += Color.green(pixel)
                blue += Color.blue(pixel)
                count++
            }
        }
        if (count == 0) return Color.parseColor("#0B1B2B")
        val r = (red / count).toInt().coerceIn(0, 255)
        val g = (green / count).toInt().coerceIn(0, 255)
        val b = (blue / count).toInt().coerceIn(0, 255)
        // Darken slightly so white foreground glyphs stay legible.
        return Color.rgb(r * 4 / 5, g * 4 / 5, b * 4 / 5)
    }

    private fun Bitmap.encodePng(): ByteArray {
        val stream = ByteArrayOutputStream(16 * 1024)
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    /* ================================================================== */
    /* Payload injection                                                   */
    /* ================================================================== */

    /**
     * Lazily streams a zip entry from the (kept-open) payload archive into
     * the APK under a different entry name. Nothing is buffered in memory,
     * so multi-hundred-megabyte payloads inject without OOM.
     */
    private class ZipEntryInputSource(
        private val archive: ZipFile,
        private val entryName: String,
        outputName: String,
    ) : InputSource(outputName) {

        private val entry: ZipEntry by lazy {
            archive.getEntry(entryName)
                ?: throw IOException("Payload entry vanished: $entryName")
        }

        override fun openStream(): InputStream = archive.getInputStream(entry)

        override fun getLength(): Long = entry.size

        override fun getCrc(): Long = entry.crc
    }

    private fun injectZipPayload(
        module: ApkModule,
        archive: ZipFile,
        payloadZip: File,
        payloadDir: String,
        onLog: (String) -> Unit,
    ): Pair<Int, Long> {
        val entries = ZipRootNormalizer.listPayloadEntries(payloadZip)
        onLog(
            "Injecting ${entries.size} file(s) " +
                "(${entries.sumOf { it.size } / 1024} KiB) into $payloadDir …"
        )
        var bytes = 0L
        for (entry in entries) {
            val outputName = payloadDir + entry.normalizedPath
            val source = ZipEntryInputSource(archive, entry.rawName, outputName).apply {
                setMethod(Archive.DEFLATED)
            }
            module.zipEntryMap.add(source)
            bytes += entry.size
        }
        return entries.size to bytes
    }

    /**
     * Injects the provisioned CPython distribution under
     * `assets/python_runtime/` (the tree the Python shell extracts on first
     * launch and runs via /system/bin/linker64).
     *
     * Since v5 the shell template itself carries a bundled runtime, so this
     * is only needed when the user provisioned a *custom* distribution —
     * which fully replaces the bundled tree (same-name entries are replaced
     * by `add()`, and any stale files from a different Python version are
     * dropped explicitly via `removeDir`).
     */
    private fun injectPythonRuntime(
        module: ApkModule,
        onLog: (String) -> Unit,
    ): Pair<Int, Long> {
        val runtimeRoot = pythonRuntime?.runtimeContentRoot()
        if (runtimeRoot == null) {
            val bundled = pythonRuntime?.bundledRuntimeInfo()
            onLog(
                if (bundled != null) {
                    "Using the CPython ${bundled.pythonVersion} runtime bundled " +
                        "inside the shell template (${bundled.fileCount} files, " +
                        "${bundled.displaySize}) — no custom runtime installed."
                } else {
                    "Python runtime not provisioned — the generated app will show " +
                        "a setup notice. Provision one from the Python Runtime card " +
                        "to enable MODE_FAST_PYTHON fully."
                }
            )
            return 0 to 0L
        }
        module.zipEntryMap.removeDir("assets/$PYTHON_RUNTIME_ASSET_DIR")
        val files = runtimeRoot.walkTopDown()
            .filter { it.isFile }
            .filter { it.relativeTo(runtimeRoot).invariantSeparatorsPath != "meta.json" }
            .toList()
        var bytes = 0L
        for (file in files) {
            val relative = file.relativeTo(runtimeRoot).invariantSeparatorsPath
            val source = FileInputSource(
                file, "assets/$PYTHON_RUNTIME_ASSET_DIR/$relative"
            ).apply {
                setMethod(Archive.DEFLATED)
            }
            module.zipEntryMap.add(source)
            bytes += file.length()
        }
        onLog(
            "Injected custom Python runtime: ${files.size} files, " +
                "${bytes / (1L shl 20)} MiB → assets/$PYTHON_RUNTIME_ASSET_DIR/ " +
                "(replaces the bundled one)."
        )
        return files.size to bytes
    }

    private fun injectFolderPayload(
        module: ApkModule,
        root: File,
        payloadDir: String,
        onLog: (String) -> Unit,
    ): Pair<Int, Long> {
        val files = root.walkTopDown()
            .filter { it.isFile }
            .filter { !ZipRootNormalizer.isPayloadJunkEntry(it.relativeTo(root).invariantSeparatorsPath) }
            .toList()
        onLog("Injecting ${files.size} file(s) from folder into $payloadDir …")
        var bytes = 0L
        for (file in files) {
            val relative = file.relativeTo(root).invariantSeparatorsPath
            val source = FileInputSource(file, payloadDir + relative).apply {
                setMethod(Archive.DEFLATED)
            }
            module.zipEntryMap.add(source)
            bytes += file.length()
        }
        return files.size to bytes
    }

    /** Writes `assets/shell_config.json`, consumed by the shell activities. */
    private fun writeShellConfig(
        module: ApkModule,
        target: PatchTarget.Shell,
        config: ProjectBuildConfig,
        detected: com.universal.apkfactory.core.model.DetectedProject?,
        payload: IngestedSource?,
        onLog: (String) -> Unit,
    ) {
        val payloadDir = target.kind.payloadDir
        val shellConfig = JSONObject().apply {
            put("appName", config.appName)
            put("packageName", config.packageName)
            put("orientation", config.orientation.name.lowercase())
            put("fullscreen", config.fullscreen)
            put("builtAt", System.currentTimeMillis())
            put("factoryVersion", "2.0")
            when (target.kind) {
                ShellKind.WEBVIEW -> {
                    // webRoot "" → www/index.html; webRoot "dist" → www/dist/index.html
                    val webRoot = detected?.webRootDir
                        ?: detectWebRootFor(payload)
                        ?: ""
                    val entryFile = detected?.webEntryFile ?: "index.html"
                    put(
                        "startPath",
                        payloadDir.removePrefix("assets/") + webRoot +
                            (if (webRoot.isEmpty()) "" else "/") + entryFile
                    )
                }

                ShellKind.PYTHON -> {
                    put("pythonEntry", detected?.pythonEntry ?: "main.py")
                }
            }
        }
        module.zipEntryMap.add(
            ByteInputSource(
                shellConfig.toString(2).toByteArray(Charsets.UTF_8),
                "assets/shell_config.json"
            )
        )
        onLog("shell_config.json: $shellConfig")
    }

    /**
     * Fallback web-root detection (root, `dist/` or `build/`) used when the
     * detector output was not handed to [patch].
     */
    private fun detectWebRootFor(payload: IngestedSource?): String? {
        if (payload == null) return null
        val entries = when (payload) {
            is IngestedSource.ZipArchive ->
                ZipRootNormalizer.listNormalizedEntries(payload.workspaceFile)

            is IngestedSource.Folder ->
                payload.workspaceFile.walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(payload.workspaceFile).invariantSeparatorsPath }
                    .take(10_000)
                    .toList()

            else -> emptyList()
        }
        return when {
            "index.html" in entries -> ""
            "dist/index.html" in entries -> "dist"
            "build/index.html" in entries -> "build"
            else -> null
        }
    }

    /* ================================================================== */
    /* Final write                                                         */
    /* ================================================================== */

    /**
     * Applies the storage rules and writes the unaligned APK:
     *
     *  * `resources.arsc` and native `lib` `.so` files → `Archive.STORED` (API 30+
     *    rejects compressed/unaligned tables and uncompressed-libs),
     *  * stale META-INF signature entries are removed (the core-signer
     *    engine re-signs the artifact),
     *  * the APK v2/v3 signing block is dropped from the base.
     *
     * Storage rules are registered in the module's `UncompressedFiles` list
     * (not only via `InputSource.setUncompressed`) because
     * `ApkModule.createApkFileWriter` re-applies that list to every source
     * right before writing — path registration is the authoritative rule.
     */
    private fun finalizeWrite(
        module: ApkModule,
        output: File,
        onLog: (String) -> Unit,
    ) {
        val uncompressedFiles = module.uncompressedFiles

        // 5a. resources.arsc must be STORED (API 30+ hard requirement).
        uncompressedFiles.addPath("resources.arsc")
        module.zipEntryMap.getInputSource("resources.arsc")?.setUncompressed(true)

        // 5b. Native libraries must be STORED as well (targetSdk 28+ when
        // extractNativeLibs=false; zipalign then 4/16 KiB-aligns them).
        var storedLibs = 0
        for (source in module.zipEntryMap.toArray()) {
            val name = source.getAlias()
            if (name.startsWith("lib/") && name.endsWith(".so")) {
                uncompressedFiles.addPath(name)
                source.setUncompressed(true)
                storedLibs++
            }
        }
        if (storedLibs > 0) {
            onLog("Marked $storedLibs native lib(s) as STORED.")
        }

        // Re-apply the rules so every entry's method is deterministic.
        uncompressedFiles.apply(module.zipEntryMap)

        // 5c. Drop stale signature files; the signer re-creates them.
        module.zipEntryMap.removeIf { source ->
            val name = source.getAlias()
            name == "META-INF/MANIFEST.MF" ||
                (name.startsWith("META-INF/") &&
                    (name.endsWith(".SF") || name.endsWith(".RSA") ||
                        name.endsWith(".DSA") || name.endsWith(".EC")))
        }

        // 5d. Never carry over an APK Signature Scheme v2/v3 block.
        module.setApkSignatureBlock(null)

        onLog("Writing unaligned APK → ${output.name} …")
        module.writeApk(output)
        onLog("Unaligned APK written: ${output.length() / 1024} KiB.")
    }

    /* ================================================================== */
    /* Utilities                                                           */
    /* ================================================================== */

    /** Decodes a user-selected icon from disk with a sane memory bound. */
    fun decodeIconFile(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw PatcherException("The selected icon is not a decodable image.")
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw PatcherException("Failed to decode the selected icon.")
    }

    companion object {
        /** Asset dir the Python shell extracts on first launch. */
        const val PYTHON_RUNTIME_ASSET_DIR = "python_runtime"
    }
}
