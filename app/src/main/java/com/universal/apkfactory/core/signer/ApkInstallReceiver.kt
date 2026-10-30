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

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.universal.apkfactory.ApkFactoryApplication
import com.universal.apkfactory.R

/**
 * PackageInstaller session status receiver (Module 4 — step 4).
 *
 * The session created by
 * [ApkAlignerAndSigner.installApk] commits with a PendingIntent targeting
 * this receiver. Android delivers the final status here:
 *
 *  * `STATUS_PENDING_USER_ACTION` — the confirmation activity Intent is
 *    forwarded so the user can approve the install (the normal path when
 *    installing from an "unknown sources" app),
 *  * `STATUS_SUCCESS` — a success notification is posted,
 *  * anything else — a failure notification with the platform's status
 *    message (e.g. `INSTALL_FAILED_UPDATE_INCOMPATIBLE`,
 *    `INSTALL_FAILED_VERSION_DOWNGRADE`) is posted.
 */
class ApkInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The confirmation activity comes embedded in the broadcast.
                @Suppress("DEPRECATION")
                val confirmIntent: Intent? =
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirmIntent)
                        Log.i(TAG, "Session $sessionId: user confirmation requested.")
                    } catch (error: Exception) {
                        Log.e(TAG, "Could not launch install confirmation", error)
                    }
                } else {
                    Log.w(TAG, "STATUS_PENDING_USER_ACTION without an EXTRA_INTENT.")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                postNotification(
                    context,
                    title = context.getString(R.string.install_success_title),
                    text = context.getString(
                        R.string.install_success_text,
                        intent.getStringExtra(EXTRA_APP_LABEL)
                            ?: context.getString(R.string.app_name)
                    ),
                )
                Log.i(TAG, "Session $sessionId: installed successfully.")
            }

            else -> {
                postNotification(
                    context,
                    title = context.getString(R.string.install_failure_title),
                    text = context.getString(
                        R.string.install_failure_text,
                        intent.getStringExtra(EXTRA_APP_LABEL)
                            ?: context.getString(R.string.app_name),
                        describeFailure(status, message)
                    ),
                )
                Log.e(
                    TAG,
                    "Session $sessionId failed: status=$status message=$message"
                )
            }
        }
    }

    private fun describeFailure(status: Int, message: String?): String {
        val label = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "aborted by the user"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "blocked (device policy?)"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "conflicts with an installed package"

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "incompatible with this device"

            PackageInstaller.STATUS_FAILURE_INVALID -> "invalid APK"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "insufficient storage"
            else -> "status $status"
        }
        return if (message.isNullOrBlank()) label else "$label ($message)"
    }

    private fun postNotification(context: Context, title: String, text: String) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
        val notification: Notification =
            Notification.Builder(context, ApkFactoryApplication.CHANNEL_INSTALLS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            // POST_NOTIFICATIONS was revoked — installation results are
            // still visible in the build log.
            Log.w(TAG, "Cannot post install notification (permission revoked).", error)
        }
    }

    companion object {
        private const val TAG = "ApkInstallReceiver"

        /** Must match the manifest intent-filter action. */
        const val ACTION_INSTALL_STATUS = "com.universal.apkfactory.INSTALL_STATUS"

        /** Our own extra correlating a result to its session. */
        const val EXTRA_SESSION_ID = "com.universal.apkfactory.extra.SESSION_ID"

        /** Optional extra: app label for friendly notifications. */
        const val EXTRA_APP_LABEL = "com.universal.apkfactory.extra.APP_LABEL"

        private const val NOTIFICATION_ID = 4101
    }
}
