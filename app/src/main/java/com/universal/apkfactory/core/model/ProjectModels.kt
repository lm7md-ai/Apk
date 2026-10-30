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
package com.universal.apkfactory.core.model

import java.io.File
import java.io.Serializable

/* =====================================================================================
 * Ingested sources (Module 1 output)
 * ===================================================================================== */

/** How a source entered the factory. */
enum class IngestionOrigin(val displayName: String) {
    SAF_PICKER("File picker"),
    SHARE_SHEET("Share sheet"),
    GITHUB_DOWNLOAD("GitHub"),
    FOLDER_PICKER("Folder picker"),
    UNKNOWN("Local");

    override fun toString(): String = displayName
}

/**
 * A source ingested into the factory workspace. Every channel (SAF picker,
 * Share Sheet, ACTION_VIEW, GitHub downloader, folder picker) funnels into
 * one of these shapes before detection runs.
 */
sealed interface IngestedSource {

    /** Human readable name used across the UI and in build logs. */
    val displayName: String

    /** Where this source came from. */
    val origin: IngestionOrigin

    /** Physical workspace file/directory backing this source. */
    val workspaceFile: File

    /** A `.zip` source archive (project sources, GitHub zipball, ...). */
    data class ZipArchive(
        override val workspaceFile: File,
        override val origin: IngestionOrigin,
        val githubSlug: String? = null,
        val githubBranch: String? = null,
    ) : IngestedSource {
        override val displayName: String =
            githubSlug?.let { slug -> "$slug${githubBranch?.let { b -> " @ $b" } ?: ""}" }
                ?: workspaceFile.nameWithoutExtension
    }

    /** An existing `.apk` (MODE_APK_EDITOR: modify / clone / re-sign). */
    data class ApkFile(
        override val workspaceFile: File,
        override val origin: IngestionOrigin,
    ) : IngestedSource {
        override val displayName: String = workspaceFile.nameWithoutExtension
    }

    /** A project folder copied (recursively) into the workspace. */
    data class Folder(
        override val workspaceFile: File,
        override val origin: IngestionOrigin,
    ) : IngestedSource {
        override val displayName: String = workspaceFile.name
    }
}

/* =====================================================================================
 * Stack classification (Module 2)
 * ===================================================================================== */

/**
 * The classification matrix of the Smart Stack Detector. The order of the
 * entries documents the precedence applied by
 * [com.universal.apkfactory.core.detector.SmartProjectDetector].
 */
enum class BuildMode(
    val key: String,
    val displayName: String,
    val summary: String,
) {
    /** `AndroidManifest.xml` (binary) + `classes.dex` found in the archive. */
    MODE_APK_EDITOR(
        "apk_editor",
        "APK Editor",
        "Modify, clone or re-sign an existing APK. No recompilation required."
    ),

    /** `index.html` (or a prebuilt `dist/` / `build/` bundle) without an uncompiled Node framework. */
    MODE_FAST_WEB(
        "fast_web",
        "Instant WebView App",
        "Static web content injected into the modern WebView shell and packaged in seconds."
    ),

    /** `package.json` with react/vue/vite/next/capacitor and no prebuilt output. */
    MODE_CLOUD_WEB_OR_HYBRID(
        "cloud_web_or_hybrid",
        "Node Web App (build required)",
        "React/Vue/Vite/Next/Capacitor project: run the Node build (cloud CI or on-device) first, then package."
    ),

    /** `main.py`, `flet.json` or `requirements.txt`. */
    MODE_FAST_PYTHON(
        "fast_python",
        "Instant Python App",
        "Python entry point injected into the Python 3.12 ARM64 host shell."
    ),

    /** `build.gradle` / `build.gradle.kts`. */
    MODE_NATIVE_GRADLE(
        "native_gradle",
        "Gradle Android Project",
        "Full Gradle build: on-device ARM64 toolchain or GitHub Actions cloud CI."
    ),

    /** `pubspec.yaml`. */
    MODE_CLOUD_FLUTTER(
        "cloud_flutter",
        "Flutter Project",
        "Flutter projects are built through a generated GitHub Actions workflow."
    );

    override fun toString(): String = displayName
}

/** Screen orientation preference written into both the patched manifest and `shell_config.json`. */
enum class AppOrientation(val manifestValue: String?, val displayName: String) {
    UNSPECIFIED(null, "Auto (system default)"),
    PORTRAIT("portrait", "Portrait"),
    LANDSCAPE("landscape", "Landscape");

    override fun toString(): String = displayName

    companion object {
        fun fromKey(raw: String?): AppOrientation = when (raw?.lowercase()?.trim()) {
            "portrait" -> PORTRAIT
            "landscape" -> LANDSCAPE
            else -> UNSPECIFIED
        }
    }
}

/**
 * A fully classified project: the normalized source plus the detected stack
 * and the evidence trail that led to the decision (surfaced in the UI so the
 * user can always see *why* a mode was chosen).
 */
data class DetectedProject(
    val source: IngestedSource,
    val mode: BuildMode,
    val evidence: List<String>,
    /** Web root inside the project ("" at root, or "dist"/"build"). `null` for non-web modes. */
    val webRootDir: String? = null,
    /**
     * HTML entry file (relative to the web root) to load at launch.
     * `null` → the standard `index.html`.
     */
    val webEntryFile: String? = null,
    /** Python entry file (relative path), when detected. */
    val pythonEntry: String? = null,
    /** True when a Node framework project already ships a prebuilt dist/build bundle. */
    val hasPrebuiltWebBundle: Boolean = false,
    /** Node frameworks matched inside package.json (react, vue, vite, next, capacitor). */
    val nodeFrameworks: Set<String> = emptySet(),
    /** All file paths (relative, normalized) present in the source root (depth-limited). */
    val rootFiles: Set<String> = emptySet(),
)

/* =====================================================================================
 * Build configuration (Module 2 → Pre-Build Configuration Screen)
 * ===================================================================================== */

/**
 * The editable build configuration passed to the Jetpack Compose pre-build
 * configuration screen. Populated by the Hybrid Config Parser from
 * `apkconfig.json`, or inferred from `manifest.json` (PWA), `package.json`,
 * `pubspec.yaml`, the binary `AndroidManifest.xml` (via ARSCLib), or the
 * ZIP filename as a last resort.
 */
data class ProjectBuildConfig(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val permissions: Set<String>,
    val orientation: AppOrientation = AppOrientation.UNSPECIFIED,
    val fullscreen: Boolean = false,
    /** True when the config came from an explicit `apkconfig.json`. */
    val fromExplicitConfig: Boolean = false,
    /** Optional note explaining where each field was inferred from. */
    val metadataOriginNote: String? = null,
) : Serializable {

    /** Validates the config before a build, returning human readable problems. */
    fun validate(): List<String> = buildList {
        if (appName.isBlank()) add("App name must not be empty.")
        if (!PACKAGE_NAME_REGEX.matches(packageName)) {
            add("Package name '$packageName' is not a valid Android package id.")
        }
        if (versionCode < 1) add("Version code must be >= 1.")
        if (versionName.isBlank()) add("Version name must not be empty.")
        if (permissions.any { !it.contains('.') }) {
            add("Permissions must be fully qualified (e.g. android.permission.INTERNET).")
        }
    }

    companion object {
        private val PACKAGE_NAME_REGEX =
            Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*){1,6}$")

        const val DEFAULT_PACKAGE_PREFIX = "com.apkfactory"

        /** Default configuration for web content with an unknown name. */
        fun fallback(displayName: String): ProjectBuildConfig {
            val appName = displayName.toPrettyAppName()
            return ProjectBuildConfig(
                appName = appName,
                packageName = derivePackageName(appName),
                versionName = "1.0",
                versionCode = 1,
                permissions = setOf(
                    android.Manifest.permission.INTERNET,
                    android.Manifest.permission.ACCESS_NETWORK_STATE
                )
            )
        }

        /**
         * Derives a valid, collision-resistant Android package id from an
         * app name: `My Cool-App 2` → `com.apkfactory.my.cool.app.x2`.
         */
        fun derivePackageName(appName: String): String {
            val segments = appName.lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
                .split(' ')
                .filter { it.isNotBlank() }
                .take(3)
                .map { segment -> if (segment[0].isDigit()) "x$segment" else segment }
            if (segments.isEmpty()) {
                return "$DEFAULT_PACKAGE_PREFIX.app"
            }
            return (listOf("com", "apkfactory") + segments).joinToString(".")
        }
    }
}

/**
 * `my-repo_main.zip` → `My Repo Main`: turns file-ish names into clean,
 * human readable app titles (used across every metadata fallback).
 */
fun String.toPrettyAppName(): String {
    val cleaned = substringBeforeLast('.')
        .replace(Regex("[-_+.]+"), " ")
        .trim()
    if (cleaned.isBlank()) return "My App"
    return cleaned.split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercaseChar() }
        }
}

/* =====================================================================================
 * Permission catalog (Pre-Build Configuration Screen checkboxes)
 * ===================================================================================== */

/** A user-selectable permission with a friendly label. */
data class PermissionInfo(
    val permission: String,
    val label: String,
    val description: String,
)

/**
 * The catalog of permissions offered as checkboxes in the hybrid pre-build
 * configuration screen. Non-catalog permissions found in an existing APK
 * (MODE_APK_EDITOR) are always preserved untouched.
 */
object PermissionCatalog {

    val INTERNET = PermissionInfo(
        android.Manifest.permission.INTERNET,
        "Internet",
        "Network access (required by WebView and Python shells)"
    )

    val ALL: List<PermissionInfo> = listOf(
        INTERNET,
        PermissionInfo(
            android.Manifest.permission.ACCESS_NETWORK_STATE,
            "Network state",
            "Detect online/offline connectivity"
        ),
        PermissionInfo(
            android.Manifest.permission.CAMERA,
            "Camera",
            "Camera capture and getUserMedia video"
        ),
        PermissionInfo(
            android.Manifest.permission.RECORD_AUDIO,
            "Microphone",
            "Audio recording and getUserMedia audio"
        ),
        PermissionInfo(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            "Precise location (GPS)",
            "High-accuracy device location"
        ),
        PermissionInfo(
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            "Approximate location",
            "City-level device location"
        ),
        PermissionInfo(
            android.Manifest.permission.POST_NOTIFICATIONS,
            "Notifications",
            "Show notifications (Android 13+)"
        ),
        PermissionInfo(
            android.Manifest.permission.READ_MEDIA_IMAGES,
            "Read media images",
            "Photo library access (Android 13+)"
        ),
        PermissionInfo(
            android.Manifest.permission.READ_MEDIA_VIDEO,
            "Read media video",
            "Video library access (Android 13+)"
        ),
        PermissionInfo(
            android.Manifest.permission.READ_MEDIA_AUDIO,
            "Read media audio",
            "Music library access (Android 13+)"
        ),
        PermissionInfo(
            android.Manifest.permission.VIBRATE,
            "Vibrate",
            "Haptic feedback from the JS bridge"
        ),
        PermissionInfo(
            android.Manifest.permission.WAKE_LOCK,
            "Keep awake",
            "Keep the CPU awake while running"
        ),
        PermissionInfo(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            "Bluetooth (nearby devices)",
            "Connect to BLE/Bluetooth devices (Android 12+)"
        ),
        PermissionInfo(
            android.Manifest.permission.NFC,
            "NFC",
            "Read NFC tags"
        ),
    )

    /** All fully-qualified permission names in the catalog. */
    val ALL_NAMES: Set<String> = ALL.map { it.permission }.toSet()

    /** Label lookup for build logs. */
    fun labelFor(permission: String): String =
        ALL.firstOrNull { it.permission == permission }?.label ?: permission
}

/* =====================================================================================
 * Build results
 * ===================================================================================== */

/** A single line of the live build log shown in the terminal-style viewer. */
data class BuildLogLine(
    val timestamp: Long = System.currentTimeMillis(),
    val level: Level,
    val message: String,
) {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    override fun toString(): String =
        "${level.name.first()}/ $message"
}
