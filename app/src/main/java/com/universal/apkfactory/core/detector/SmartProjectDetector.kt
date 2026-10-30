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

import com.universal.apkfactory.core.ingestion.ZipRootNormalizer
import com.universal.apkfactory.core.model.BuildMode
import com.universal.apkfactory.core.model.DetectedProject
import com.universal.apkfactory.core.model.IngestedSource
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Smart Stack Detector & Hybrid Metadata Router (Module 2).
 *
 * Inspects the normalized `.zip` / `.apk` **central directory only** (no
 * payload is extracted to disk; single small config files are peeked with
 * bounded reads) and classifies the project into one of the six build modes.
 *
 * Classification matrix (applied in this exact precedence):
 *
 *  1. `AndroidManifest.xml` (binary) + `classes.dex`
 *       → [BuildMode.MODE_APK_EDITOR]
 *  2. `package.json` containing `react` / `vue` / `vite` / `next` /
 *     `capacitor` **without** a prebuilt `dist/` or `build/` folder
 *       → [BuildMode.MODE_CLOUD_WEB_OR_HYBRID]
 *  3. `index.html` at the (normalized) root — or a prebuilt bundle under
 *     `dist/` / `build/` — with no uncompiled Node framework
 *       → [BuildMode.MODE_FAST_WEB]
 *  4. `main.py`, `flet.json` or `requirements.txt`
 *       → [BuildMode.MODE_FAST_PYTHON]
 *  5. `pubspec.yaml`
 *       → [BuildMode.MODE_CLOUD_FLUTTER]  (checked before Gradle so Flutter
 *         projects, which also ship Gradle build scripts under `android/` —
 *         this mode wins)
 *  6. `build.gradle` / `build.gradle.kts` at root or one level deep
 *       → [BuildMode.MODE_NATIVE_GRADLE]
 *
 * Every decision appends human-readable evidence lines which the UI shows on
 * the pre-build configuration screen.
 */
object SmartProjectDetector {

    class DetectionException(message: String) : IOException(message)

    /** Node frameworks recognized for the cloud/hybrid classification. */
    private val NODE_FRAMEWORKS = listOf(
        "react", "react-native", "vue", "vite", "next", "capacitor", "nuxt", "svelte"
    )

    /** Gradle build files, matched at project root and one directory deep. */
    private val GRADLE_FILES = listOf("build.gradle", "build.gradle.kts")

    /** Python entry markers. */
    private val PYTHON_ENTRY_FILES = listOf("main.py", "app.py")
    private val PYTHON_MARKER_FILES = listOf("flet.json", "requirements.txt")

    private const val MAX_PEEK_BYTES: Long = 512L * 1024

    /* ================================================================== */
    /* Entry points                                                        */
    /* ================================================================== */

    fun detect(source: IngestedSource): DetectedProject = when (source) {
        is IngestedSource.ApkFile -> detectApk(source)
        is IngestedSource.ZipArchive -> detectZip(source)
        is IngestedSource.Folder -> detectFolder(source)
    }

    /* ================================================================== */
    /* MODE_APK_EDITOR                                                     */
    /* ================================================================== */

    private fun detectApk(source: IngestedSource.ApkFile): DetectedProject {
        val evidence = ArrayList<String>()
        ZipFile(source.workspaceFile).use { archive ->
            val hasBinaryManifest = archive.getEntry("AndroidManifest.xml") != null
            val hasDex = archive.entries().asSequence()
                .any { it.name.matches(Regex("classes\\d*\\.dex")) }
            evidence.add(
                "AndroidManifest.xml (binary): ${if (hasBinaryManifest) "found" else "missing"}"
            )
            evidence.add("classes.dex: ${if (hasDex) "found" else "missing"}")
            if (!hasBinaryManifest || !hasDex) {
                throw DetectionException(
                    "'${source.workspaceFile.name}' is not an installable APK " +
                        "(manifest=${hasBinaryManifest}, dex=${hasDex})."
                )
            }
        }
        return DetectedProject(
            source = source,
            mode = BuildMode.MODE_APK_EDITOR,
            evidence = evidence,
        )
    }

    /* ================================================================== */
    /* ZIP archives                                                        */
    /* ================================================================== */

    private fun detectZip(source: IngestedSource.ZipArchive): DetectedProject {
        val entries = ZipRootNormalizer.listNormalizedEntries(source.workspaceFile)
        return classify(source, entries) { path ->
            ZipRootNormalizer.peekNormalizedText(
                source.workspaceFile, path, MAX_PEEK_BYTES
            )
        }
    }

    /* ================================================================== */
    /* Folders                                                             */
    /* ================================================================== */

    private fun detectFolder(source: IngestedSource.Folder): DetectedProject {
        // Depth-limited walk (4 levels) mirroring the zip view.
        val root = source.workspaceFile
        val entries = ArrayList<String>()
        fun walk(dir: File, prefix: String, depth: Int) {
            if (depth > 4) return
            val files = dir.listFiles() ?: return
            for (file in files.sortedBy { it.name }) {
                val relative = prefix + file.name
                if (ZipRootNormalizer.isPayloadJunkEntry("$relative/x")) continue
                if (file.isDirectory) {
                    walk(file, "$relative/", depth + 1)
                } else {
                    entries.add(relative)
                }
            }
        }
        walk(root, "", 0)
        if (entries.isEmpty()) {
            throw DetectionException("Folder '${root.name}' contains no project files.")
        }
        return classify(source, entries) { path ->
            val file = File(root, path)
            if (file.isFile && file.length() <= MAX_PEEK_BYTES) {
                file.readText()
            } else {
                null
            }
        }
    }

    /* ================================================================== */
    /* Shared classification matrix                                        */
    /* ================================================================== */

    /**
     * @param entries normalized (wrapper-stripped) relative file paths.
     * @param peek function that reads a small file's UTF-8 content.
     */
    private fun classify(
        source: IngestedSource,
        entries: List<String>,
        peek: (String) -> String?,
    ): DetectedProject {
        val evidence = ArrayList<String>()
        val entrySet = entries.toSet()
        evidence.add("Scanned ${entries.size} project files (normalized root).")

        // -- 1. APK inside a zip? ------------------------------------------
        if ("AndroidManifest.xml" in entrySet &&
            entries.any { it.matches(Regex("classes\\d*\\.dex")) }
        ) {
            evidence.add("Binary AndroidManifest.xml + classes.dex inside archive.")
            return DetectedProject(
                source = source,
                mode = BuildMode.MODE_APK_EDITOR,
                evidence = evidence,
            )
        }

        // -- 2. Node frameworks without prebuilt output -------------------- 
        val packageJson = peek("package.json")
        val frameworks = packageJson?.let { detectNodeFrameworks(it) } ?: emptySet()
        val prebuiltWebRoot = firstPrebuiltWebRoot(entrySet)
        if (frameworks.isNotEmpty() && prebuiltWebRoot == null) {
            evidence.add("package.json frameworks: ${frameworks.joinToString(", ")}.")
            evidence.add("No prebuilt dist/ or build/ output — a Node build is required.")
            return DetectedProject(
                source = source,
                mode = BuildMode.MODE_CLOUD_WEB_OR_HYBRID,
                evidence = evidence,
                webRootDir = null,
                hasPrebuiltWebBundle = false,
                nodeFrameworks = frameworks,
                rootFiles = entrySet,
            )
        }

        // -- 3. Static web content ------------------------------------------
        val webRoot = resolveStaticWebRoot(entrySet)
        if (webRoot != null) {
            evidence.add("index.html found at web root '${webRoot.ifEmpty { "." }}'.")
            if (prebuiltWebRoot != null) {
                evidence.add("Serving the prebuilt '$prebuiltWebRoot' bundle.")
            }
            return DetectedProject(
                source = source,
                mode = BuildMode.MODE_FAST_WEB,
                evidence = evidence,
                webRootDir = webRoot,
                hasPrebuiltWebBundle = prebuiltWebRoot != null,
                nodeFrameworks = frameworks,
                rootFiles = entrySet,
            )
        }

        // -- 4. Python -------------------------------------------------------
        val pythonEntry = PYTHON_ENTRY_FILES.firstOrNull { it in entrySet }
        val pythonMarker = PYTHON_MARKER_FILES.firstOrNull { it in entrySet }
        if (pythonEntry != null || pythonMarker != null) {
            evidence.add(
                "Python markers: " +
                    (listOfNotNull(pythonEntry, pythonMarker).joinToString(", "))
            )
            return DetectedProject(
                source = source,
                mode = BuildMode.MODE_FAST_PYTHON,
                evidence = evidence,
                pythonEntry = pythonEntry ?: "main.py",
                rootFiles = entrySet,
            )
        }

        // -- 5. Flutter (before Gradle: Flutter ships build.gradle files) ---
        if ("pubspec.yaml" in entrySet) {
            evidence.add("pubspec.yaml found (Flutter project).")
            return DetectedProject(
                source = source,
                mode = BuildMode.MODE_CLOUD_FLUTTER,
                evidence = evidence,
                rootFiles = entrySet,
            )
        }

        // -- 6. Gradle -------------------------------------------------------
        val gradleFile = GRADLE_FILES.firstOrNull { it in entrySet }
            ?: entries.firstOrNull { relative ->
                GRADLE_FILES.any { name ->
                    relative.matches(Regex("[A-Za-z0-9_.-]+/$name"))
                }
            }?.substringAfterLast('/')
        if (gradleFile != null) {
            evidence.add("Gradle build script '$gradleFile' found.")
            return DetectedProject(
                source = source,
                mode = BuildMode.MODE_NATIVE_GRADLE,
                evidence = evidence,
                rootFiles = entrySet,
            )
        }

        throw DetectionException(
            buildString {
                append("Unable to detect a supported project stack. ")
                append("Supported: static web (index.html), Node web apps ")
                append("(react/vue/vite/next/capacitor), Python (main.py / ")
                append("requirements.txt / flet.json), Gradle Android projects, ")
                append("Flutter (pubspec.yaml) and existing APKs. ")
                append("Top-level files found: ")
                append(entries.take(8).joinToString(", "))
                if (entries.size > 8) append(", …")
            }
        )
    }

    /* ================================================================== */
    /* Node / web analysis                                                 */
    /* ================================================================== */

    /**
     * Matches framework dependencies (dependencies + devDependencies keys)
     * against the recognized list. Scoped packages map to their base
     * (`@capacitor/core` → `capacitor`).
     */
    private fun detectNodeFrameworks(packageJsonText: String): Set<String> {
        return try {
            val root = JSONObject(packageJsonText)
            val dependencyNames = HashSet<String>()
            for (section in listOf("dependencies", "devDependencies")) {
                val dependencies = root.optJSONObject(section) ?: continue
                for (key in dependencies.keys()) {
                    dependencyNames.add(key)
                }
            }
            NODE_FRAMEWORKS.filterTo(LinkedHashSet()) { framework ->
                dependencyNames.any { name ->
                    name == framework ||
                        name == "@$framework/core" ||
                        name == "@$framework/cli" ||
                        name.startsWith("$framework-") ||
                        name.startsWith("@$framework/")
                }
            }
        } catch (error: org.json.JSONException) {
            emptySet()
        }
    }

    /** Returns "dist" / "build" when a prebuilt bundle with index.html exists. */
    private fun firstPrebuiltWebRoot(entries: Set<String>): String? = when {
        "dist/index.html" in entries -> "dist"
        "build/index.html" in entries -> "build"
        else -> null
    }

    /**
     * Resolves the static web root:
     *  * `""` — when `index.html` sits at the normalized project root,
     *  * `"dist"` / `"build"` — when only a prebuilt bundle exists,
     *  * `null` — when there is no static web content at all.
     */
    private fun resolveStaticWebRoot(entries: Set<String>): String? {
        if ("index.html" in entries) return ""
        return firstPrebuiltWebRoot(entries)
    }
}
