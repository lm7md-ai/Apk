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

import android.content.Context
import com.universal.apkfactory.core.ingestion.IngestionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Per-Package Persistent Keystore manager (Module 4 — core-signer).
 *
 * Automatically generates and stores a unique `PKCS12` keystore under
 * `keys/{packageName}.p12` containing a 2048-bit RSA key pair and an X.509v3
 * self-signed certificate, built with the **isolated BouncyCastle provider**
 * (`bcpkix-jdk18on`). Rebuilding the same project later reuses its keystore,
 * so the new APK upgrades cleanly over the installed one instead of failing
 * with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
 *
 * Keystore passwords are random per-keystore and persisted next to the
 * keystore (`keys/{packageName}.pass`) — Android's sandboxed app storage
 * keeps both private to this app; the password is shown in the UI so users
 * can export/back up their signing identity.
 *
 * Also supports importing user-provided `.jks` / `.p12` keystores; imported
 * identities are normalized to PKCS12 on disk.
 */
class KeyStoreGenerator(private val context: Context) {

    /* ------------------------------------------------------------------ */
    /* Identity model                                                      */
    /* ------------------------------------------------------------------ */

    /** A fully resolved signing identity. */
    data class SigningIdentity(
        val keystoreFile: File,
        val packageName: String,
        val alias: String,
        val password: CharArray,
        val privateKey: PrivateKey,
        val certificates: List<X509Certificate>,
    ) {
        override fun equals(other: Any?): Boolean = other is SigningIdentity &&
            other.keystoreFile == keystoreFile &&
            other.alias == alias &&
            other.password.contentEquals(password)

        override fun hashCode(): Int = keystoreFile.hashCode() * 31 + alias.hashCode()
    }

    /** Listing record (no key material loaded). */
    data class KeyStoreRecord(
        val file: File,
        val packageName: String,
        val alias: String,
        val createdAt: Long,
        val hasStoredPassword: Boolean,
        val certificateSubject: String,
    )

    class KeyStoreException(message: String, cause: Throwable? = null) :
        IOException(message, cause)

    /* ------------------------------------------------------------------ */
    /* Creation                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Returns the signing identity for [packageName], creating the keystore
     * on first use. This is the entry point used by the build pipeline.
     */
    fun getOrCreateKeyStore(packageName: String): SigningIdentity {
        val keystoreFile = keystoreFileFor(packageName)
        if (keystoreFile.isFile) {
            return loadIdentity(keystoreFile, packageName, readStoredPassword(packageName))
        }
        return createKeyStore(keystoreFile, packageName)
    }

    /**
     * Generates a fresh PKCS12 keystore with a 2048-bit RSA key and an
     * X.509v3 self-signed certificate (SHA256withRSA), valid 30 years.
     */
    fun createKeyStore(
        keystoreFile: File,
        packageName: String,
        alias: String = DEFAULT_ALIAS,
        password: CharArray = generatePassword(),
    ): SigningIdentity {
        val bc = CryptoProviders.BOUNCY_CASTLE
        try {
            // --- RSA 2048 key pair (BouncyCastle, explicit provider) -------
            val keyPairGenerator = KeyPairGenerator.getInstance("RSA", bc)
            keyPairGenerator.initialize(KEY_SIZE_BITS, SecureRandom())
            val keyPair: KeyPair = keyPairGenerator.generateKeyPair()

            // --- X.509v3 self-signed certificate ---------------------------
            val subject = buildX500Name(packageName)
            val now = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            val notAfter = now + TimeUnit.DAYS.toMillis(VALIDITY_DAYS)
            val serial = BigInteger(SERIAL_BITS, SecureRandom()).abs()

            val certificateBuilder = JcaX509v3CertificateBuilder(
                subject,
                serial,
                Date(now),
                Date(notAfter),
                subject,
                keyPair.public
            )
            val extensionUtils = JcaX509ExtensionUtils()
            certificateBuilder.addExtension(
                Extension.basicConstraints, true, BasicConstraints(false)
            )
            certificateBuilder.addExtension(
                Extension.keyUsage, true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
            )
            certificateBuilder.addExtension(
                Extension.extendedKeyUsage, false,
                ExtendedKeyUsage(KeyPurposeId.anyExtendedKeyUsage)
            )
            certificateBuilder.addExtension(
                Extension.subjectKeyIdentifier, false,
                extensionUtils.createSubjectKeyIdentifier(keyPair.public)
            )

            val signer = JcaContentSignerBuilder(SIGNATURE_ALGORITHM)
                .setProvider(bc)
                .build(keyPair.private)
            val certificate = JcaX509CertificateConverter()
                .setProvider(bc)
                .getCertificate(certificateBuilder.build(signer))

            // --- PKCS12 keystore (isolated provider!) ----------------------
            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE_PKCS12, bc)
            keyStore.load(null, null)
            keyStore.setKeyEntry(alias, keyPair.private, password, arrayOf(certificate))

            keystoreFile.parentFile?.mkdirs()
            FileOutputStream(keystoreFile).use { output ->
                keyStore.store(output, password)
            }
            writeStoredPassword(packageName, password)

            return SigningIdentity(
                keystoreFile = keystoreFile,
                packageName = packageName,
                alias = alias,
                password = password,
                privateKey = keyPair.private,
                certificates = listOf(certificate),
            )
        } catch (error: Exception) {
            keystoreFile.delete()
            throw KeyStoreException(
                "Failed to generate the signing keystore for '$packageName': " +
                    error.message,
                error
            )
        }
    }

    /* ------------------------------------------------------------------ */
    /* Import                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Imports a user keystore (`.jks` or `.p12`), verifies it holds a
     * private key, and persists it under `keys/` **normalized to PKCS12**.
     *
     * @param targetPackageName the package the keystore will sign for;
     *        defaults to the source file's base name.
     */
    fun importKeyStore(
        source: File,
        password: CharArray,
        targetPackageName: String? = null,
        alias: String? = null,
    ): SigningIdentity {
        val bc = CryptoProviders.BOUNCY_CASTLE
        val loaded = loadAnyKeyStore(source, password)
        val keyStore = loaded.first
        val resolvedAlias = alias
            ?: keyStore.aliases().asSequence().firstOrNull { keyStore.isKeyEntry(it) }
            ?: throw KeyStoreException(
                "The keystore '${source.name}' contains no private key entry."
            )
        val privateKey = keyStore.getKey(resolvedAlias, password) as? PrivateKey
            ?: throw KeyStoreException("Alias '$resolvedAlias' has no private key.")
        val chain = keyStore.getCertificateChain(resolvedAlias)
            ?.mapNotNull { it as? X509Certificate }
            ?.takeIf { it.isNotEmpty() }
            ?: throw KeyStoreException(
                "Alias '$resolvedAlias' has no certificate chain."
            )

        val packageName = targetPackageName?.takeIf { it.isNotBlank() }
            ?: source.nameWithoutExtension
        val destination = keystoreFileFor(packageName)
        if (destination.isFile) destination.delete()

        // Normalize to PKCS12 for a uniform on-disk format.
        val normalized = KeyStore.getInstance(KEYSTORE_TYPE_PKCS12, bc)
        normalized.load(null, null)
        normalized.setKeyEntry(resolvedAlias, privateKey, password, chain.toTypedArray())
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { output ->
            normalized.store(output, password)
        }
        writeStoredPassword(packageName, password)

        return SigningIdentity(
            keystoreFile = destination,
            packageName = packageName,
            alias = resolvedAlias,
            password = password,
            privateKey = privateKey,
            certificates = chain,
        )
    }

    /** Tries PKCS12 first, then JKS — both through the isolated provider. */
    private fun loadAnyKeyStore(source: File, password: CharArray): Pair<KeyStore, String> {
        val bc = CryptoProviders.BOUNCY_CASTLE
        val attempts = listOf(KEYSTORE_TYPE_PKCS12, KEYSTORE_TYPE_JKS)
        var lastError: Exception? = null
        for (type in attempts) {
            try {
                val keyStore = KeyStore.getInstance(type, bc)
                FileInputStream(source).use { input ->
                    keyStore.load(input, password)
                }
                return keyStore to type
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw KeyStoreException(
            "Not a readable .p12/.jks keystore (wrong password or format): " +
                "${lastError?.message}",
            lastError
        )
    }

    /* ------------------------------------------------------------------ */
    /* Loading / listing                                                   */
    /* ------------------------------------------------------------------ */

    /** Loads a keystore from disk into a usable [SigningIdentity]. */
    fun loadIdentity(
        keystoreFile: File,
        packageName: String,
        password: CharArray,
    ): SigningIdentity {
        val bc = CryptoProviders.BOUNCY_CASTLE
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE_PKCS12, bc)
            FileInputStream(keystoreFile).use { input ->
                keyStore.load(input, password)
            }
            val alias = keyStore.aliases().asSequence()
                .firstOrNull { keyStore.isKeyEntry(it) }
                ?: throw KeyStoreException(
                    "Keystore '${keystoreFile.name}' holds no private key."
                )
            val privateKey = keyStore.getKey(alias, password) as? PrivateKey
                ?: throw KeyStoreException("Entry '$alias' is not a private key.")
            val chain = keyStore.getCertificateChain(alias)
                ?.mapNotNull { it as? X509Certificate }
                ?.takeIf { it.isNotEmpty() }
                ?: throw KeyStoreException("Entry '$alias' has no certificates.")

            return SigningIdentity(
                keystoreFile = keystoreFile,
                packageName = packageName,
                alias = alias,
                password = password,
                privateKey = privateKey,
                certificates = chain,
            )
        } catch (error: KeyStoreException) {
            throw error
        } catch (error: Exception) {
            throw KeyStoreException(
                "Failed to open keystore '${keystoreFile.name}': ${error.message}",
                error
            )
        }
    }

    /** Lists all managed keystores under `keys/` with light metadata. */
    fun listKeyStores(): List<KeyStoreRecord> {
        val dir = keysDir()
        val records = ArrayList<KeyStoreRecord>()
        val files = dir.listFiles { file -> file.isFile && file.extension == "p12" }
            ?: return emptyList()
        for (file in files.sortedBy { it.lastModified() }) {
            val packageName = file.nameWithoutExtension
            val passwordFile = passwordFileFor(packageName)
            val password = if (passwordFile.isFile) {
                runCatching { readStoredPassword(packageName) }.getOrNull()
            } else {
                null
            }
            val alias: String
            val subject: String
            if (password != null) {
                try {
                    val identity = loadIdentity(file, packageName, password)
                    alias = identity.alias
                    subject = identity.certificates.first()
                        .subjectX500Principal.name
                        .take(80)
                } catch (error: Exception) {
                    alias = "?"
                    subject = "unreadable: ${error.message?.take(48)}"
                }
            } else {
                alias = "?"
                subject = "password protected (imported)"
            }
            records.add(
                KeyStoreRecord(
                    file = file,
                    packageName = packageName,
                    alias = alias,
                    createdAt = file.lastModified(),
                    hasStoredPassword = passwordFile.isFile,
                    certificateSubject = subject,
                )
            )
        }
        return records
    }

    /* ------------------------------------------------------------------ */
    /* Storage paths                                                       */
    /* ------------------------------------------------------------------ */

    fun keysDir(): File = IngestionManager.Workspace.keystores(context)

    /** `keys/{packageName}.p12` (spec-defined layout). */
    fun keystoreFileFor(packageName: String): File =
        File(keysDir(), "$packageName.p12")

    private fun passwordFileFor(packageName: String): File =
        File(keysDir(), "$packageName.pass")

    private fun readStoredPassword(packageName: String): CharArray {
        val file = passwordFileFor(packageName)
        if (!file.isFile) {
            throw KeyStoreException(
                "The password companion file for '$packageName' is missing. " +
                    "Re-create the keystore or import it again."
            )
        }
        return file.readText().trim().toCharArray()
    }

    private fun writeStoredPassword(packageName: String, password: CharArray) {
        passwordFileFor(packageName).writeText(String(password))
    }

    /* ------------------------------------------------------------------ */
    /* Cert building helpers                                               */
    /* ------------------------------------------------------------------ */

    private fun buildX500Name(packageName: String): X500Name {
        val builder = org.bouncycastle.asn1.x500.X500NameBuilder(BCStyle.INSTANCE)
        builder.addRDN(BCStyle.CN, "Universal APK Factory")
        builder.addRDN(BCStyle.OU, "On-Device Builds")
        builder.addRDN(BCStyle.O, packageName)
        return builder.build()
    }

    private fun generatePassword(): CharArray {
        val alphabet = (
            "abcdefghijklmnopqrstuvwxyz" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" +
                "0123456789"
            ).toCharArray()
        val random = SecureRandom()
        return CharArray(PASSWORD_LENGTH) { alphabet[random.nextInt(alphabet.size)] }
    }

    companion object {
        const val DEFAULT_ALIAS = "apkfactory"
        const val KEYSTORE_TYPE_PKCS12 = "PKCS12"
        const val KEYSTORE_TYPE_JKS = "JKS"

        const val KEY_SIZE_BITS = 2048
        const val SIGNATURE_ALGORITHM = "SHA256withRSA"
        const val VALIDITY_DAYS = 30L * 365L
        const val SERIAL_BITS = 160
        const val PASSWORD_LENGTH = 32
    }
}
