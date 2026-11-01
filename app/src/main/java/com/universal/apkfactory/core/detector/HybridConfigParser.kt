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
package com.universal.apkfactory.core.detector

import com.reandroid.apk.ApkModule
import com.universal.apkfactory.core.ingestion.ZipRootNormalizer
import com.universal.apkfactory.core.model.AppOrientation
import com.universal.apkfactory.core.model.BuildMode
import com.universal.apkfactory.core.model.DetectedProject
import com.universal.apkfactory.core.model.PermissionCatalog
import com.universal.apkfactory.core.model.ProjectBuildConfig
import com.universal.apkfactory.core.model.toPrettyAppName
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Hybrid Config Parser (Module 2).
 *
 * Builds the [ProjectBuildConfig] handed to the Jetpack Compose pre-build
 * configuration screen. Resolution order:
 *
 *  1. `apkconfig.json` inside the project root (authoritative when present):
 *     ```
 *     {
 *       "appName": "My App",
 *       "packageName": "com.example.myapp",
 *       "versionCode": 7,
 *       "versionName": "2.1.0",
 *       "permissions": ["android.permission.INTERNET", "android.permission.CAMERA"],
 *       "orientation": "portrait",
 *       "fullscreen": true
 *     }
 *     ```
 *  2. `manifest.json` (PWA): `name` / `short_name`, `orientation`.
 *  3. `package.json`: `name`, `version`.
 *  4. `pubspec.yaml`: `name`, `version` (`1.2.3+42`).
 *  5. Binary `AndroidManifest.xml` (via ARSCLib `ApkModule`) for APKs:
 *     package, versionCode/versionName, application label and the existing
 *     permission set (versionCode bumped by 1 so re-signs install cleanly).
 *  6. Fallback: the project display name / ZIP filename.
 */
object HybridConfigParser {

    private const val CONFIG_FILE = "apkconfig.json"

    /**
     * Produces the initial, editable configuration for a detected project.
     *
     * @param detected output of [SmartProjectDetector.detect].
     * @param iconFallbackName used as the very last appName fallback.
     */
    fun parse(detected: DetectedProject): ProjectBuildConfig {
        val peek: (String) -> String? = when (val source = detected.source) {
            is com.universal.apkfactory.core.model.IngestedSource.ZipArchive -> { path ->
                ZipRootNormalizer.peekNormalizedText(source.workspaceFile, path)
            }

            is com.universal.apkfactory.core.model.IngestedSource.Folder -> { path ->
                val file = File(source.workspaceFile, path)
                if (file.isFile && file.length() <= 512L * 1024) file.readText() else null
            }

            is com.universal.apkfactory.core.model.IngestedSource.ApkFile -> { _ -> null }
        }

        // v6.2 — automatic permissions: scan the uploaded sources for the
        // browser APIs / Python modules the app actually uses and enable the
        // matching Android permissions automatically (shell baseline + extras).
        // Lazy: when apkconfig.json specifies `permissions` explicitly, the
        // scan is never run — the user's list stays authoritative.
        val permissionScan: PermissionInferenceEngine.Result by lazy {
            PermissionInferenceEngine.infer(detected, peek)
        }

        // ---- 1. apkconfig.json (authoritative) --------------------------
        peek(CONFIG_FILE)?.let { raw ->
            runCatching { parseApkConfig(JSONObject(raw), detected, permissionScan) }.getOrNull()
                ?.let { return it }
        }

        val displayName = detected.source.displayName

        // ---- 5. APK metadata via ARSCLib ---------------------------------
        if (detected.source is com.universal.apkfactory.core.model.IngestedSource.ApkFile) {
            return parseApkMetadata(detected.source.workspaceFile, displayName)
        }

        // ---- 2. PWA manifest.json ----------------------------------------
        peek("manifest.json")?.let { raw ->
            runCatching { parsePwaManifest(JSONObject(raw), displayName, permissionScan) }.getOrNull()
                ?.let { return it }
        }

        // ---- 3. package.json ----------------------------------------------
        peek("package.json")?.let { raw ->
            runCatching { parsePackageJson(JSONObject(raw), displayName, permissionScan) }.getOrNull()
                ?.let { return it }
        }

        // ---- 4. pubspec.yaml ------------------------------------------------
        peek("pubspec.yaml")?.let { raw ->
            parsePubspec(raw, displayName, permissionScan)?.let { return it }
        }

        // ---- 6. filename fallback --------------------------------------------
        return filenameFallback(detected, displayName, permissionScan)
    }

    /* ==================================================================== */
    /* apkconfig.json                                                        */
    /* ==================================================================== */

    private fun parseApkConfig(
        root: JSONObject,
        detected: DetectedProject,
        permissionScan: PermissionInferenceEngine.Result,
    ): ProjectBuildConfig {
        val appName = root.optString("appName").trim()
            .ifEmpty { detected.source.displayName.toPrettyAppName() }
        val packageName = root.optString("packageName").trim()
            .ifEmpty { ProjectBuildConfig.derivePackageName(appName) }
        val versionName = root.optString("versionName").trim().ifEmpty { "1.0" }
        val versionCode = if (root.has("versionCode") && root.opt("versionCode") != null) {
            root.optInt("versionCode", 1)
        } else {
            1
        }
        val permissions = LinkedHashSet<String>()
        val permissionsValue = root.opt("permissions")
        when (permissionsValue) {
            is JSONArray -> for (i in 0 until permissionsValue.length()) {
                permissions.add(permissionsValue.optString(i))
            }

            is String -> permissionsValue.split(',').forEach {
                val value = it.trim()
                if (value.isNotEmpty()) permissions.add(value)
            }

            null -> {}
        }
        // v6.3.1 — the note must only claim auto-detected permissions when
        // they were actually applied; an explicit apkconfig list is authoritative.
        val usedAutoPermissions = permissions.isEmpty()
        if (usedAutoPermissions) {
            permissions.addAll(permissionScan.permissions)
        }
        val orientation = AppOrientation.fromKey(root.optString("orientation"))
        val fullscreen = root.optBoolean("fullscreen", false)

        return ProjectBuildConfig(
            appName = appName,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            permissions = permissions,
            orientation = orientation,
            fullscreen = fullscreen,
            fromExplicitConfig = true,
            metadataOriginNote = "Configuration loaded from apkconfig.json." +
                if (usedAutoPermissions) permissionNote(permissionScan) else "",
        )
    }

    /* ==================================================================== */
    /* PWA manifest.json                                                     */
    /* ==================================================================== */

    private fun parsePwaManifest(
        root: JSONObject,
        displayName: String,
        permissionScan: PermissionInferenceEngine.Result,
    ): ProjectBuildConfig {
        val appName = root.optString("name").trim()
            .ifEmpty { root.optString("short_name").trim() }
            .ifEmpty { displayName.toPrettyAppName() }
        return ProjectBuildConfig(
            appName = appName,
            packageName = ProjectBuildConfig.derivePackageName(appName),
            versionName = "1.0",
            versionCode = 1,
            permissions = permissionScan.permissions,
            orientation = pwaOrientation(root.optString("orientation")),
            metadataOriginNote = "Metadata inferred from PWA manifest.json." +
                permissionNote(permissionScan),
        )
    }

    private fun pwaOrientation(raw: String): AppOrientation = when (raw.lowercase(Locale.US)) {
        "portrait" -> AppOrientation.PORTRAIT
        "landscape" -> AppOrientation.LANDSCAPE
        else -> AppOrientation.UNSPECIFIED
    }

    /* ==================================================================== */
    /* package.json                                                          */
    /* ==================================================================== */

    private fun parsePackageJson(
        root: JSONObject,
        displayName: String,
        permissionScan: PermissionInferenceEngine.Result,
    ): ProjectBuildConfig {
        val rawName = root.optString("name").trim()
        val appName = if (rawName.startsWith("@")) {
            rawName.removePrefix("@").substringAfter('/').toPrettyAppName()
        } else {
            rawName.toPrettyAppName()
        }.ifEmpty { displayName.toPrettyAppName() }

        val versionName = root.optString("version").trim().ifEmpty { "1.0" }
        return ProjectBuildConfig(
            appName = appName,
            packageName = ProjectBuildConfig.derivePackageName(appName),
            versionName = versionName,
            versionCode = 1,
            permissions = permissionScan.permissions,
            metadataOriginNote = "Metadata inferred from package.json." +
                permissionNote(permissionScan),
        )
    }

    /* ==================================================================== */
    /* pubspec.yaml (minimal flat key parser — no YAML dependency needed)    */
    /* ==================================================================== */

    private fun parsePubspec(
        raw: String,
        displayName: String,
        permissionScan: PermissionInferenceEngine.Result,
    ): ProjectBuildConfig? {
        val map = HashMap<String, String>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            if (line.startsWith(' ') || line.startsWith('-')) continue // nested content
            val index = trimmed.indexOf(':')
            if (index <= 0) continue
            val key = trimmed.substring(0, index).trim()
            val value = trimmed.substring(index + 1).trim().trim('"', '\'')
            if (key.isNotEmpty()) map[key] = value
        }
        val rawName = map["name"] ?: return null
        if (rawName.isBlank()) return null
        val appName = rawName.toPrettyAppName().ifEmpty { displayName.toPrettyAppName() }

        var versionName = "1.0"
        var versionCode = 1
        map["version"]?.let { version ->
            val parts = version.split('+')
            if (parts.isNotEmpty() && parts[0].isNotBlank()) versionName = parts[0]
            if (parts.size > 1) {
                versionCode = parts[1].trim().toIntOrNull()?.coerceAtLeast(1) ?: 1
            }
        }
        return ProjectBuildConfig(
            appName = appName,
            packageName = ProjectBuildConfig.derivePackageName(appName),
            versionName = versionName,
            versionCode = versionCode,
            permissions = permissionScan.permissions,
            metadataOriginNote = "Metadata inferred from pubspec.yaml." +
                permissionNote(permissionScan),
        )
    }

    /* ==================================================================== */
    /* APK metadata via ARSCLib                                              */
    /* ==================================================================== */

    private fun parseApkMetadata(apkFile: File, displayName: String): ProjectBuildConfig {
        return try {
            ApkModule.loadApkFile(apkFile).use { module ->
                val manifest = module.androidManifest
                val existingPackage = manifest.packageName ?: ""
                val existingLabel = manifest.applicationLabelString?.trim()
                val existingVersionName = manifest.versionName?.trim()
                val existingVersionCode = manifest.versionCode ?: 1
                val permissions = manifest.getUsesPermissions().toCollection(LinkedHashSet())

                val appName = existingLabel
                    ?.takeIf { it.isNotBlank() }
                    ?: displayName.toPrettyAppName()

                ProjectBuildConfig(
                    appName = appName,
                    packageName = existingPackage.ifBlank {
                        ProjectBuildConfig.derivePackageName(appName)
                    },
                    versionName = existingVersionName?.takeIf { it.isNotBlank() } ?: "1.0",
                    // Bumped by one so a modified/re-signed clone installs as an
                    // upgrade instead of failing with INSTALL_FAILED_VERSION_DOWNGRADE.
                    versionCode = existingVersionCode + 1,
                    permissions = permissions.ifEmpty {
                        defaultPermissionsFor(BuildMode.MODE_APK_EDITOR)
                    },
                    metadataOriginNote = "Metadata decoded from the binary " +
                        "AndroidManifest.xml via ARSCLib (versionCode bumped).",
                )
            }
        } catch (error: Exception) {
            val synthetic = DetectedProject(
                source = com.universal.apkfactory.core.model.IngestedSource.ApkFile(
                    apkFile,
                    com.universal.apkfactory.core.model.IngestionOrigin.UNKNOWN
                ),
                mode = BuildMode.MODE_APK_EDITOR,
                evidence = emptyList(),
            )
            // MODE_APK_EDITOR infers nothing (the APK's own permission set is
            // authoritative) — this yields the plain INTERNET baseline.
            filenameFallback(
                synthetic,
                displayName,
                PermissionInferenceEngine.infer(synthetic) { null },
            )
        }
    }

    /* ==================================================================== */
    /* Fallbacks                                                             */
    /* ==================================================================== */

    private fun filenameFallback(
        detected: DetectedProject,
        displayName: String,
        permissionScan: PermissionInferenceEngine.Result,
    ): ProjectBuildConfig {
        val appName = displayName.toPrettyAppName()
        return ProjectBuildConfig(
            appName = appName,
            packageName = ProjectBuildConfig.derivePackageName(appName),
            versionName = "1.0",
            versionCode = 1,
            permissions = permissionScan.permissions,
            metadataOriginNote = "No config file found — defaults derived from " +
                "the project name." + permissionNote(permissionScan),
        )
    }

    /**
     * Human-readable suffix for [ProjectBuildConfig.metadataOriginNote] listing
     * what the auto-permission scan enabled (empty when nothing was inferred).
     */
    private fun permissionNote(scan: PermissionInferenceEngine.Result): String {
        if (scan.findings.isEmpty()) return ""
        return " Auto-detected permissions: " +
            scan.findings.joinToString(", ") {
                "${it.permission.substringAfterLast('.')} — ${it.reason.substringBefore(" —")}"
            } + "."
    }

    private fun defaultPermissionsFor(mode: BuildMode): Set<String> = when (mode) {
        BuildMode.MODE_FAST_WEB,
        BuildMode.MODE_CLOUD_WEB_OR_HYBRID,
        BuildMode.MODE_FAST_PYTHON,
        BuildMode.MODE_CLOUD_FLUTTER -> setOf(
            PermissionCatalog.INTERNET.permission,
            "android.permission.ACCESS_NETWORK_STATE"
        )

        BuildMode.MODE_APK_EDITOR,
        BuildMode.MODE_NATIVE_GRADLE -> setOf(PermissionCatalog.INTERNET.permission)
    }
}
