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
package com.universal.apkfactory.core.ingestion

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Nested Root-Folder Normalizer (Module 1, crucial edge case).
 *
 * GitHub zipballs and user-created archives almost always wrap the project
 * inside one or more redundant single-child parent directories:
 *
 * ```
 * my-repo-main/            <- wrapper added by GitHub
 * my-repo-main/index.html
 * ```
 *
 * or even nested:
 *
 * ```
 * project/
 * project/app/
 * project/app/index.html   <- double wrapper
 * ```
 *
 * [detectRootPrefix] scans the archive's central directory (via
 * [java.util.zip.ZipFile] — no payload decompression, only the entry table)
 * and returns the redundant wrapper prefix, e.g. `"my-repo-main/"`. Detection
 * and injection then operate on the *true* project root.
 *
 * Heuristics (deterministic and documented):
 *  1. Directory entries and well-known junk (`__MACOSX/`, `.git/`,
 *     `node_modules/`, `.DS_Store`, ...) are ignored during analysis.
 *  2. A top-level directory is stripped only when **every** remaining file
 *     entry lives inside it. The rule is applied repeatedly, so nested
 *     wrappers (`a/b/index.html`) are stripped layer by layer.
 *  3. A directory is **never** stripped when its name is a well-known
 *     project-root directory name (`app`, `src`, `dist`, `build`, `res`,
 *     ...). Archives created from *inside* a project often contain nothing
 *     but `app/` or `dist/` — and those directories are the project root.
 */
object ZipRootNormalizer {

    /**
     * Directory names that are legitimate project roots. When the archive's
     * only top-level directory carries one of these names, stripping it
     * would corrupt the project layout, so the normalizer leaves it intact.
     */
    private val KNOWN_ROOT_DIR_NAMES: Set<String> = setOf(
        "app", "src", "main", "gradle", "build", "dist", "out", "assets",
        "res", "www", "public", "static", "lib", "libs", "android", "ios",
        "web", "electron", "java", "kotlin", "python", "bin", "vendor",
        "node_modules", "test", "tests", "doc", "docs", "example",
        "examples", "scripts"
    )

    /**
     * Entries that carry no value inside an app payload or a build workspace
     * and are skipped both during wrapper analysis and during
     * re-write/injection.
     */
    fun isPayloadJunkEntry(entryName: String): Boolean {
        if (entryName.endsWith("/")) return true // directory entry
        val segments = entryName.split('/')
        for (segment in segments) {
            when (segment) {
                "__MACOSX", ".git", ".idea", ".gradle", ".settings",
                "__pycache__", "node_modules", ".dart_tool", ".terraform",
                ".svn", ".hg" -> return true
            }
        }
        val file = segments.last()
        return file == ".DS_Store" || file == "Thumbs.db" ||
            file == "desktop.ini" || file == "__MACOSX"
    }

    /**
     * Pure wrapper-prefix computation over a list of entry names.
     *
     * @return the redundant prefix ending in `/` (e.g. `"my-repo-main/"`,
     *         `"a/b/"`), or `null` when there is no redundant wrapper.
     */
    fun computeRootPrefix(fileEntries: Collection<String>): String? {
        val files = fileEntries
            .filter { !it.endsWith("/") && !isPayloadJunkEntry(it) }
        if (files.isEmpty()) return null

        var prefix = ""
        var remaining = files
        while (true) {
            val topLevelNames = remaining.mapTo(HashSet()) { it.substringBefore('/') }
            if (topLevelNames.size != 1) {
                // Multiple roots (or files at the root) — no wrapper to strip.
                return prefix.ifEmpty { null }
            }
            val topName = topLevelNames.first()
            val firstEntry = remaining.first()
            if (!firstEntry.contains('/')) {
                // `topName` is itself a file at the current root — done.
                return prefix.ifEmpty { null }
            }
            if (topName.lowercase() in KNOWN_ROOT_DIR_NAMES) {
                // e.g. an archive containing only `dist/` or `app/`: the
                // directory IS the project root; do not strip.
                return prefix.ifEmpty { null }
            }
            prefix += "$topName/"
            remaining = remaining.map { it.substringAfter('/') }
            if (remaining.any { it.isEmpty() }) {
                // Defensive: a directory entry slipped through — abort.
                return prefix.removeSuffix("$topName/").ifEmpty { null }
            }
        }
    }

    /** Reads the central directory of [zip] and detects the wrapper prefix. */
    fun detectRootPrefix(zip: File): String? {
        ZipFile(zip).use { archive ->
            return computeRootPrefix(archive.entries().asSequence().map { it.name }.toList())
        }
    }

    /** A raw zip entry mapped onto the normalized project namespace. */
    data class NormalizedEntry(
        /** Entry name as stored inside the archive. */
        val rawName: String,
        /** Entry name with the redundant wrapper prefix removed. */
        val normalizedPath: String,
        val size: Long,
        val compressedSize: Long,
    )

    /**
     * Lists all *payload* entries (junk filtered, prefix stripped, central
     * directory only — nothing is extracted to disk).
     */
    fun listPayloadEntries(zip: File): List<NormalizedEntry> {
        val prefix = detectRootPrefix(zip) ?: ""
        val result = ArrayList<NormalizedEntry>()
        ZipFile(zip).use { archive ->
            for (entry in archive.entries()) {
                if (entry.isDirectory) continue
                if (isPayloadJunkEntry(entry.name)) continue
                val normalized = if (prefix.isNotEmpty()) {
                    if (!entry.name.startsWith(prefix)) continue
                    entry.name.removePrefix(prefix)
                } else {
                    entry.name
                }
                if (normalized.isEmpty()) continue
                result.add(
                    NormalizedEntry(
                        rawName = entry.name,
                        normalizedPath = normalized,
                        size = entry.size,
                        compressedSize = entry.compressedSize,
                    )
                )
            }
        }
        return result
    }

    /**
     * Convenience view for detectors: normalized paths only.
     */
    fun listNormalizedEntries(zip: File): List<String> =
        listPayloadEntries(zip).map { it.normalizedPath }

    /**
     * Reads a single (small) text entry out of the archive without
     * extracting anything else. [normalizedPath] is compared against the
     * normalized namespace.
     *
     * @return the entry content decoded as UTF-8, or `null` when absent.
     */
    fun peekNormalizedText(
        zip: File,
        normalizedPath: String,
        maxBytes: Long = 512L * 1024,
    ): String? {
        val prefix = detectRootPrefix(zip) ?: ""
        ZipFile(zip).use { archive ->
            val entry = archive.getEntry(prefix + normalizedPath)
                ?: archive.getEntry(normalizedPath)
                ?: return null
            if (entry.size > maxBytes) return null
            archive.getInputStream(entry).buffered().use { stream ->
                val bytes = stream.readBytes()
                return String(bytes, Charsets.UTF_8)
            }
        }
    }

    /**
     * Streams [sourceZip] into [destination] with the redundant wrapper
     * prefix stripped and junk entries dropped. Compression methods are
     * preserved (stored entries stay stored, with their size/CRC carried
     * over so the output remains a valid zip without a re-crc pass).
     */
    @Throws(IOException::class)
    fun writeNormalizedZip(sourceZip: File, destination: File) {
        val prefix = detectRootPrefix(sourceZip) ?: ""
        ZipFile(sourceZip).use { archive ->
            destination.parentFile?.mkdirs()
            ZipOutputStream(
                FileOutputStream(destination).buffered()
            ).use { output ->
                for (entry in archive.entries()) {
                    if (entry.isDirectory) continue
                    if (isPayloadJunkEntry(entry.name)) continue
                    val mappedName = if (prefix.isNotEmpty()) {
                        if (!entry.name.startsWith(prefix)) continue
                        entry.name.removePrefix(prefix)
                    } else {
                        entry.name
                    }
                    if (mappedName.isEmpty()) continue
                    val mappedEntry = ZipEntry(mappedName).apply {
                        time = entry.time
                        if (entry.method == ZipEntry.STORED) {
                            method = ZipEntry.STORED
                            size = entry.size
                            crc = entry.crc
                        } else {
                            method = ZipEntry.DEFLATED
                        }
                    }
                    output.putNextEntry(mappedEntry)
                    archive.getInputStream(entry).use { stream ->
                        stream.copyTo(output, bufferSize = 64 * 1024)
                    }
                    output.closeEntry()
                }
            }
        }
    }
}
