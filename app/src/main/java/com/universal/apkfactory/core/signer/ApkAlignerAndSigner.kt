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
package com.universal.apkfactory.core.signer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.android.apksig.ApkSigner
import com.iyxan23.zipalignjava.InvalidZipException
import com.iyxan23.zipalignjava.ZipAlign
import com.universal.apkfactory.core.ingestion.IngestionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Cryptographic Alignment & Signing Engine (Module 4 — core-signer).
 *
 * Execution pipeline:
 *
 *  1. **4-byte ZipAlign** — `com.github.iyxan23:zipalign-java`
 *     (`ZipAlign.alignZip`) guarantees every uncompressed entry (the
 *     `resources.arsc` and the native `lib` `.so` entries forced to `STORED`
 *     by the
 *     patcher) starts on a 4-byte boundary; `.so` files additionally land on
 *     16 KiB boundaries, as required for `targetSdk 35` uncompressed-libs
 *     loading.
 *  2. **Signing** — `com.android.tools.build:apksig` with the full
 *     V1 (JAR) + V2 + V3 signature schemes enabled, using the per-package
 *     persistent keystore from [KeyStoreGenerator] or a user-imported one.
 *     Signature-block insertion happens after the central directory and does
 *     not disturb entry alignment.
 *  3. **Installation** — the PackageInstaller Session API commits the signed
 *     APK with a status PendingIntent; a FileProvider + ACTION_VIEW fallback
 *     covers devices where the session API is unavailable or blocked.
 */
class ApkAlignerAndSigner(private val context: Context) {

    class SigningFailedException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    /* ================================================================== */
    /* Full pipeline                                                       */
    /* ================================================================== */

    /**
     * Convenience pipeline: align(unaligned) → sign(aligned) → [signed].
     *
     * @return the final signed APK.
     */
    suspend fun alignAndSign(
        unalignedApk: File,
        packageName: String,
        onLog: (String) -> Unit = {},
        identity: com.universal.apkfactory.core.signer.KeyStoreGenerator.SigningIdentity? = null,
    ): File = withContext(Dispatchers.IO) {
        val aligned = File(unalignedApk.parentFile, unalignedApk.nameWithoutExtension + ".aligned.apk")
        alignApk(unalignedApk, aligned, onLog)

        val signed = File(
            IngestionManager.Workspace.out(context),
            unalignedApk.nameWithoutExtension.removeSuffix(".unaligned") + ".signed.apk"
        )
        signApk(aligned, signed, identity ?: keyStoreGenerator()
            .getOrCreateKeyStore(packageName), onLog)

        aligned.delete()
        signed
    }

    /* ================================================================== */
    /* Step 1: zipalign (com.github.iyxan23:zipalign-java)                 */
    /* ================================================================== */

    /**
     * Aligns the uncompressed entries of [unaligned] to 4-byte boundaries
     * (`.so` files to 16 KiB) and writes the result to [aligned].
     */
    fun alignApk(unaligned: File, aligned: File, onLog: (String) -> Unit = {}): File {
        if (aligned.exists()) aligned.delete()
        aligned.parentFile?.mkdirs()
        onLog("zipalign (4-byte + 16 KiB .so): ${unaligned.name} → ${aligned.name} …")
        try {
            RandomAccessFile(unaligned, "r").use { input ->
                FileOutputStream(aligned).buffered().use { output: OutputStream ->
                    ZipAlign.alignZip(input, output)
                }
            }
        } catch (error: InvalidZipException) {
            aligned.delete()
            throw SigningFailedException(
                "zipalign rejected '${unaligned.name}' as an invalid zip: ${error.message}",
                error
            )
        } catch (error: IOException) {
            aligned.delete()
            throw SigningFailedException("zipalign failed: ${error.message}", error)
        }
        onLog("Aligned APK: ${aligned.length() / 1024} KiB.")
        return aligned
    }

    /* ================================================================== */
    /* Step 2: apksig (V1 + V2 + V3)                                       */
    /* ================================================================== */

    /**
     * Signs [input] with the V1, V2 and V3 schemes and writes [output].
     *
     * V1 keeps Android 6–10 (and jarsigner-based tooling) happy; V2 covers
     * Android 7+ with full-content integrity; V3 enables key rotation
     * metadata. All three are produced in a single pass by apksig.
     */
    fun signApk(
        input: File,
        output: File,
        identity: KeyStoreGenerator.SigningIdentity,
        onLog: (String) -> Unit = {},
    ): File {
        if (output.exists()) output.delete()
        output.parentFile?.mkdirs()

        val signerConfig = ApkSigner.SignerConfig.Builder(
            "UniversalApkFactory",
            identity.privateKey,
            identity.certificates
        ).build()

        onLog(
            "Signing with ${identity.keystoreFile.name} " +
                "(${identity.certificates.first().sigAlgName}) — V1+V2+V3 …"
        )
        try {
            val signer = ApkSigner.Builder(listOf(signerConfig))
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setMinSdkVersion(MIN_SDK)
                .setCreatedBy("Universal APK Factory 2.0")
                .build()
            signer.sign(input, output)
        } catch (error: Exception) {
            output.delete()
            throw SigningFailedException(
                "Signing failed for '${input.name}': ${error.message}",
                error
            )
        }
        onLog("Signed APK: ${output.name} (${output.length() / 1024} KiB).")
        return output
    }

    /* ================================================================== */
    /* Step 3: installation                                                */
    /* ================================================================== */

    /** Result of an installation attempt. */
    sealed interface InstallResult {
        /** Session committed; final status arrives via [ApkInstallReceiver]. */
        data class SessionCommitted(val sessionId: Int, val apkFile: File) : InstallResult

        /** Handed to the system installer via FileProvider. */
        data class HandedToSystemInstaller(val apkFile: File) : InstallResult

        data class Failed(val reason: String) : InstallResult
    }

    /**
     * Installs the signed APK through the PackageInstaller Session API.
     * On API 26+ this opens the system install confirmation; the outcome is
     * broadcast to [ApkInstallReceiver].
     *
     * When the session API cannot be used (security policy on some OEM
     * builds, or "install unknown apps" denied for this factory), the APK is
     * handed to the system installer through a FileProvider URI instead.
     */
    suspend fun installApk(
        apkFile: File,
        onLog: (String) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!apkFile.isFile || apkFile.length() == 0L) {
            return@withContext InstallResult.Failed("APK file is missing or empty.")
        }

        val packageInstaller = context.packageManager.packageInstaller
        return@withContext try {
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)
            try {
                apkFile.inputStream().use { input ->
                    session.openWrite("base", 0, apkFile.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                session.commit(statusIntent(sessionId))
                onLog("PackageInstaller session $sessionId committed — awaiting confirmation.")
                InstallResult.SessionCommitted(sessionId, apkFile)
            } finally {
                session.close()
            }
        } catch (error: Exception) {
            onLog(
                "PackageInstaller unavailable (${error.message}); " +
                    "falling back to the system installer."
            )
            installViaFileProvider(apkFile, onLog)
        }
    }

    /** FileProvider + ACTION_VIEW fallback installation path. */
    private fun installViaFileProvider(
        apkFile: File,
        onLog: (String) -> Unit,
    ): InstallResult = try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Note: resolveActivity() is unreliable under Android 11+ package
        // visibility; just try and catch the (rare) absence.
        context.startActivity(intent)
        onLog("Handed '${apkFile.name}' to the system installer.")
        InstallResult.HandedToSystemInstaller(apkFile)
    } catch (error: android.content.ActivityNotFoundException) {
        InstallResult.Failed("No activity handles ACTION_VIEW for APKs on this device.")
    } catch (error: Exception) {
        Log.e(TAG, "FileProvider install failed", error)
        InstallResult.Failed("Installation hand-off failed: ${error.message}")
    }

    /**
     * The status PendingIntent target. PackageInstaller fills in the status
     * extras, so the PendingIntent must be mutable on API 31+.
     */
    private fun statusIntent(sessionId: Int): PendingIntent {
        val intent = Intent(ApkInstallReceiver.ACTION_INSTALL_STATUS)
            .setPackage(context.packageName)
            .putExtra(ApkInstallReceiver.EXTRA_SESSION_ID, sessionId)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    private fun keyStoreGenerator(): KeyStoreGenerator = KeyStoreGenerator(context)

    companion object {
        private const val TAG = "ApkAlignerAndSigner"
        private const val MIN_SDK = 26
    }
}
