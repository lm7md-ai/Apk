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
package com.universal.apkfactory

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.universal.apkfactory.core.signer.CryptoProviders

/**
 * Application entry point.
 *
 * Responsibilities:
 *  1. Registers the **isolated, uncrippled BouncyCastle provider**
 *     (`org.bouncycastle:bcpkix-jdk18on`). Android ships a stripped-down
 *     `BC` provider that cannot create PKCS12 keystores with modern
 *     algorithms and throws `KeyStoreException` / `NoSuchAlgorithmException`
 *     for several X.509 builders. The Android platform provider is removed
 *     and replaced with the full BouncyCastle implementation exactly once
 *     per process. All cryptographic engines in `core-signer` additionally
 *     reference the provider instance explicitly, so they are immune to
 *     provider ordering issues.
 *  2. Creates the notification channels used by the build progress
 *     notifications and the PackageInstaller result receiver.
 */
class ApkFactoryApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        installFullBouncyCastleProvider()
        createNotificationChannels()
    }

    /**
     * Replaces Android's crippled built-in `BC` security provider with the
     * complete `bcpkix-jdk18on` implementation.
     *
     * Idempotent: the single provider instance used by every engine is
     * captured in
     * [com.universal.apkfactory.core.signer.CryptoProviders.BOUNCY_CASTLE].
     */
    private fun installFullBouncyCastleProvider() {
        CryptoProviders.ensureInstalled()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val builds = NotificationChannel(
            CHANNEL_BUILDS,
            getString(R.string.notification_channel_builds),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_builds_desc)
            setShowBadge(false)
        }

        val installs = NotificationChannel(
            CHANNEL_INSTALLS,
            getString(R.string.notification_channel_installs),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_installs_desc)
        }

        manager.createNotificationChannel(builds)
        manager.createNotificationChannel(installs)
    }

    companion object {
        const val CHANNEL_BUILDS = "apkfactory.builds"
        const val CHANNEL_INSTALLS = "apkfactory.installs"
    }
}
