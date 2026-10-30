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
package com.universal.apkfactory.engine.gradle

import org.tukaani.xz.XZInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/**
 * Streaming archive extractor for the on-device toolchain (Module 5).
 *
 * Supports the three archive formats the toolchain components ship in:
 *
 *  * `.tar.gz` — Adoptium OpenJDK distributions,
 *  * `.tar.xz` — AndroidIDE aarch64 SDK / build-tools / platform-tools,
 *  * `.zip`    — Gradle distributions.
 *
 * The reader understands POSIX ustar and GNU tar headers, follows GNU
 * long-name (`L`) entries, ignores pax extended headers (`x`/`g`), and can
 * strip a single redundant top-level directory (e.g. `jdk-21.0.9+10/`)
 * while never stripping well-known layout directories (`bin/`, `lib/`, …)
 * that are legitimately at an archive root.
 */
object TarArchiveReader {

    class ArchiveFormatException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    /** Well-known directory names that may legitimately sit at an archive root. */
    private val KNOWN_LAYOUT_DIRS: Set<String> = setOf(
        "bin", "lib", "lib64", "include", "jre", "legal", "conf", "docs",
        "modules", "licenses", "release", "top-level", "android-sdk",
        "android-sdk-home", "sdk", "platforms", "build-tools", "cmdline-tools",
        "platform-tools", "tools", "extras", "licenses", "sources", "patches"
    )

    /** Description of one archive member gathered during the listing pass. */
    data class ArchiveEntry(
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
    )

    /* ------------------------------------------------------------------ */
    /* Public API                                                          */
    /* ------------------------------------------------------------------ */

    /** Lists the file entries of a tar (gz/xz) or zip archive. */
    fun listEntries(archive: File): List<ArchiveEntry> = when {
        archive.name.endsWith(".zip") -> listZip(archive)
        else -> listTar(openTarStream(archive))
    }

    /**
     * Extracts [archive] into [targetDir].
     *
     * @param stripSingleTopLevelDirectory when true (default) and the archive
     *        contains exactly one top-level directory that is not a known
     *        layout directory, its contents are extracted directly into
     *        [targetDir] (mirrors the factory's ZipRootNormalizer rule).
     */
    fun extract(
        archive: File,
        targetDir: File,
        stripSingleTopLevelDirectory: Boolean = true,
        onProgress: (bytesWritten: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ) {
        val entries = listEntries(archive)
        val totalBytes = entries.filter { !it.isDirectory }.sumOf { it.size }
        var prefix = ""
        if (stripSingleTopLevelDirectory) {
            prefix = computeStripPrefix(entries.map { it.name }) ?: ""
        }
        when {
            archive.name.endsWith(".zip") ->
                extractZip(archive, targetDir, prefix, totalBytes, onProgress)

            else -> extractTar(
                openTarStream(archive), targetDir, prefix, totalBytes, onProgress
            )
        }
    }

    /* ------------------------------------------------------------------ */
    /* Format detection                                                    */
    /* ------------------------------------------------------------------ */

    private fun openTarStream(archive: File): InputStream =
        FileInputStream(archive).buffered().let { stream ->
            when {
                archive.name.endsWith(".xz") -> XZInputStream(stream)
                archive.name.endsWith(".gz") ||
                    archive.name.endsWith(".tgz") -> GZIPInputStream(stream)

                else -> stream
            }
        }

    /* ------------------------------------------------------------------ */
    /* Listing                                                             */
    /* ------------------------------------------------------------------ */

    private fun listZip(archive: File): List<ArchiveEntry> {
        ZipFile(archive).use { zip ->
            return zip.entries().asSequence()
                .map { entry ->
                    ArchiveEntry(entry.name, entry.size, entry.isDirectory)
                }
                .toList()
        }
    }

    private fun listTar(input: InputStream): List<ArchiveEntry> {
        val entries = ArrayList<ArchiveEntry>()
        input.use { stream ->
            var pendingLongName: String? = null
            while (true) {
                val header = readHeader(stream) ?: break
                val name = pendingLongName ?: parseHeaderName(header)
                pendingLongName = null
                val size = parseHeaderSize(header)
                val type = header[156].toInt() and 0xFF
                skipData(stream, size)
                when (type) {
                    'L'.code -> pendingLongName = readLongName(stream, size)
                    'x'.code, 'g'.code -> Unit // pax headers: content already skipped
                    else -> entries.add(
                        ArchiveEntry(
                            name = name,
                            size = size,
                            isDirectory = type == '5'.code || name.endsWith("/")
                        )
                    )
                }
            }
        }
        return entries
    }

    /* ------------------------------------------------------------------ */
    /* Extraction: zip                                                     */
    /* ------------------------------------------------------------------ */

    private fun extractZip(
        archive: File,
        targetDir: File,
        prefix: String,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        var written = 0L
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                var name = entry.name
                if (prefix.isNotEmpty()) {
                    if (!name.startsWith(prefix)) continue
                    name = name.removePrefix(prefix)
                }
                if (name.isEmpty()) continue
                val target = safeTarget(targetDir, name)
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(written, totalBytes)
                        }
                    }
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Extraction: tar                                                     */
    /* ------------------------------------------------------------------ */

    private fun extractTar(
        input: InputStream,
        targetDir: File,
        prefix: String,
        totalBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        var written = 0L
        input.use { stream ->
            var pendingLongName: String? = null
            while (true) {
                val header = readHeader(stream) ?: break
                var name = pendingLongName ?: parseHeaderName(header)
                pendingLongName = null
                val size = parseHeaderSize(header)
                val type = header[156].toInt() and 0xFF
                if (type == 'L'.code) {
                    pendingLongName = readLongName(stream, size)
                    continue
                }
                if (type == 'x'.code || type == 'g'.code) {
                    skipData(stream, size)
                    continue
                }
                if (prefix.isNotEmpty()) {
                    if (!name.startsWith(prefix)) {
                        skipData(stream, size)
                        continue
                    }
                    name = name.removePrefix(prefix)
                }
                val isDirectory = type == '5'.code || name.endsWith("/")
                if (name.isEmpty()) {
                    skipData(stream, size)
                    continue
                }
                val target = safeTarget(targetDir, name)
                if (isDirectory) {
                    target.mkdirs()
                    skipData(stream, size)
                    continue
                }
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { output ->
                    var remaining = size
                    val buffer = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val read = stream.read(
                            buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()
                        )
                        if (read < 0) throw EOFException("Unexpected end of archive")
                        output.write(buffer, 0, read)
                        remaining -= read
                        written += read
                        onProgress(written, totalBytes)
                    }
                }
                skipPadding(stream, size)
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Tar header parsing                                                  */
    /* ------------------------------------------------------------------ */

    private fun readHeader(stream: InputStream): ByteArray? {
        val header = ByteArray(512)
        var read = 0
        while (read < 512) {
            val n = stream.read(header, read, 512 - read)
            if (n < 0) {
                return if (read == 0) null else throw EOFException("Truncated tar header")
            }
            read += n
        }
        // A block of NULs marks the end of the archive.
        if (header.all { it == 0.toByte() }) return null
        return header
    }

    private fun parseHeaderName(header: ByteArray): String {
        // ustar: name[100] + prefix[155] at offset 345.
        val name = cString(header, 0, 100)
        val prefix = cString(header, 345, 155)
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun parseHeaderSize(header: ByteArray): Long {
        val raw = cString(header, 124, 12)
        if (raw.isBlank()) return 0L
        // GNU base-256 encoding for huge files.
        if (raw[0] == ' ' && raw.drop(1).all { it == ' ' }) return 0L
        return try {
            java.lang.Long.parseLong(raw.trim(), 8)
        } catch (error: NumberFormatException) {
            throw ArchiveFormatException("Bad tar size field: '$raw'")
        }
    }

    private fun readLongName(stream: InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        var read = 0
        while (read < bytes.size) {
            val n = stream.read(bytes, read, bytes.size - read)
            if (n < 0) throw EOFException("Truncated GNU long name")
            read += n
        }
        skipPadding(stream, size)
        return cString(bytes, 0, bytes.size)
    }

    private fun skipData(stream: InputStream, size: Long) {
        var remaining = size
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                if (stream.read() < 0) throw EOFException("Unexpected end of archive")
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
        skipPadding(stream, size)
    }

    private fun skipPadding(stream: InputStream, size: Long) {
        val padding = ((512 - (size % 512)) % 512).toInt()
        if (padding > 0) skipDataExactly(stream, padding)
    }

    private fun skipDataExactly(stream: InputStream, count: Int) {
        val sink = ByteArray(512)
        var skipped = 0
        while (skipped < count) {
            val n = stream.read(sink, 0, minOf(sink.size, count - skipped))
            if (n < 0) throw EOFException("Unexpected end of archive")
            skipped += n
        }
    }

    private fun cString(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val limit = minOf(offset + length, bytes.size)
        while (end < limit && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8).trim('\u0000')
    }

    /* ------------------------------------------------------------------ */
    /* Path safety                                                         */
    /* ------------------------------------------------------------------ */

    /** Rejects path traversal (`../`) and absolute targets. */
    private fun safeTarget(targetDir: File, entryName: String): File {
        val normalized = File(entryName).canonicalPath
        val root = targetDir.canonicalPath + File.separatorChar
        if (!normalized.startsWith(root)) {
            throw ArchiveFormatException("Archive entry escapes target dir: $entryName")
        }
        return File(normalized)
    }

    /**
     * Computes the redundant single top-level directory prefix, using the
     * same policy as `ZipRootNormalizer`: strip only when every file lives
     * inside exactly one top-level directory whose name is not a known
     * layout directory.
     */
    fun computeStripPrefix(entryNames: List<String>): String? {
        val files = entryNames.filter { !it.endsWith("/") }
        if (files.isEmpty()) return null
        val topLevel = files.mapTo(HashSet()) { it.substringBefore('/') }
        if (topLevel.size != 1) return null
        val topName = topLevel.first()
        if (!files.first().contains('/')) return null
        if (topName.lowercase() in KNOWN_LAYOUT_DIRS) return null
        return "$topName/"
    }
}
