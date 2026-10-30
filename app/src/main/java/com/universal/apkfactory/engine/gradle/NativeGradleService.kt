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

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.universal.apkfactory.ApkFactoryApplication
import com.universal.apkfactory.MainActivity
import com.universal.apkfactory.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference

/**
 * Module 5 — engine-native-gradle: foreground service that compiles an
 * imported Gradle project **entirely on the device** using the ARM64
 * toolchain provisioned by [ToolchainManager].
 *
 * Why a foreground service: a full AGP build routinely takes several minutes
 * and must survive the activity being backgrounded; Android 14+ requires
 * `foregroundServiceType="specialUse"` (declared in the manifest with the
 * subtype property) plus a visible notification for exactly this scenario.
 *
 * Gradle is NOT launched through `bin/gradle` (a shell script) and not
 * through `java -jar`. The distribution's launcher JAR is invoked directly:
 *
 * ```
 * /system/bin/linker64 $JAVA_HOME/bin/java \
 *     -classpath $GRADLE_HOME/lib/gradle-launcher-<ver>.jar \
 *     org.gradle.launcher.daemon.bootstrap.GradleDaemon \
 *     --no-daemon --console=plain <tasks>
 * ```
 *
 * `linker64` is execve'd from the read-only system partition (always
 * executable under the W^X policy that makes app storage noexec for
 * targetSdk 29+) and maps the JVM launcher ELF itself. `--no-daemon` keeps
 * the build inside this service's process tree so its lifetime is bounded
 * by the service, and `--console=plain` produces line-oriented output that
 * is streamed to the UI via [outputLines].
 */
class NativeGradleService : Service() {

    companion object {
        const val ACTION_START = "com.universal.apkfactory.action.START_NATIVE_BUILD"
        const val ACTION_CANCEL = "com.universal.apkfactory.action.CANCEL_NATIVE_BUILD"
        const val EXTRA_PROJECT_DIR = "projectDir"
        const val EXTRA_TASKS = "tasks"

        private const val NOTIFICATION_ID = 4201

        /** Aggregated build output, mirrored to the Live Terminal Log Viewer. */
        private val _outputLines = MutableSharedFlow<String>(
            replay = 4096,
            extraBufferCapacity = 256,
        )

        val outputLines: SharedFlow<String> = _outputLines.asSharedFlow()

        /** Build lifecycle state; survives service destruction. */
        private val _state = MutableStateFlow<NativeBuildState>(NativeBuildState.Idle)

        val state: StateFlow<NativeBuildState> = _state.asStateFlow()

        /** Last toolchain provisioning event, for the UI download progress bar. */
        val toolchainEvents = MutableStateFlow<ToolchainManager.Event?>(null)

        fun emitLine(line: String) {
            _outputLines.tryEmit(line)
        }

        fun resetState() {
            _state.value = NativeBuildState.Idle
        }
    }

    sealed interface NativeBuildState {
        data object Idle : NativeBuildState
        data object ProvisioningToolchain : NativeBuildState
        data class Running(
            val projectDir: String,
            val tasks: List<String>,
            val startedAtMillis: Long,
        ) : NativeBuildState

        data class Success(
            val exitCode: Int,
            val outputApks: List<File>,
        ) : NativeBuildState

        data class Failed(
            val exitCode: Int,
            val message: String,
        ) : NativeBuildState
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningProcess = AtomicReference<Process?>(null)
    private var buildJob: kotlinx.coroutines.Job? = null

    /* ------------------------------------------------------------------ */
    /* Lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                runningProcess.get()?.destroyForcibly()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val projectDir = intent.getStringExtra(EXTRA_PROJECT_DIR)
                @Suppress("DEPRECATION")
                val tasks = intent.getStringArrayListExtra(EXTRA_TASKS) ?: arrayListOf()
                if (projectDir.isNullOrEmpty()) {
                    _state.value = NativeBuildState.Failed(
                        exitCode = -1,
                        message = "Native build requested without a project directory",
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInForeground()
                if (buildJob?.isActive == true) {
                    emitLine("[native] A build is already running; ignoring extra start.")
                    return START_NOT_STICKY
                }
                buildJob = serviceScope.launch {
                    runBuild(File(projectDir), tasks.toList())
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runningProcess.get()?.destroyForcibly()
        serviceScope.cancel()
        if (_state.value is NativeBuildState.ProvisioningToolchain ||
            _state.value is NativeBuildState.Running
        ) {
            _state.value = NativeBuildState.Failed(
                exitCode = -1,
                message = "Compiler service was destroyed before the build finished",
            )
        }
        super.onDestroy()
    }

    /* ------------------------------------------------------------------ */
    /* Foreground notification                                             */
    /* ------------------------------------------------------------------ */

    private fun startInForeground() {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, NativeGradleService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(
            this, ApkFactoryApplication.CHANNEL_BUILDS
        )
            .setSmallIcon(R.drawable.ic_stat_factory)
            .setContentTitle(getString(R.string.native_build_notification_title))
            .setContentText(getString(R.string.native_build_notification_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(contentIntent)
            .addAction(
                R.drawable.ic_stat_factory,
                getString(R.string.native_build_action_cancel),
                cancelIntent,
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(
            this, ApkFactoryApplication.CHANNEL_BUILDS
        )
            .setSmallIcon(R.drawable.ic_stat_factory)
            .setContentTitle(getString(R.string.native_build_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(contentIntent)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    /* ------------------------------------------------------------------ */
    /* Build execution                                                     */
    /* ------------------------------------------------------------------ */

    private suspend fun runBuild(projectDir: File, tasks: List<String>) {
        emitLine("[native] On-device Gradle build started for ${projectDir.name}")
        try {
            val toolchainManager = ToolchainManager(
                this,
                (application as ApkFactoryApplication).okHttpClient,
            )
            _state.value = NativeBuildState.ProvisioningToolchain
            emitLine("[native] Checking toolchain (JDK 21 aarch64 / Gradle 8.9 / Android SDK)…")
            val toolchain = toolchainManager.ensureReady { event ->
                toolchainEvents.value = event
                when (event) {
                    is ToolchainManager.Event.DownloadStarted ->
                        emitLine(
                            "[native] Downloading ${event.component} " +
                                "(${formatBytes(event.bytesTotal)})"
                        )

                    is ToolchainManager.Event.DownloadProgress -> Unit // shown in UI bar

                    is ToolchainManager.Event.Verifying ->
                        emitLine("[native] Verifying ${event.component} checksum…")

                    is ToolchainManager.Event.Extracting -> Unit

                    is ToolchainManager.Event.ComponentReady ->
                        emitLine("[native] ${event.component} ready at ${event.path.name}")

                    is ToolchainManager.Event.ComponentCached ->
                        emitLine("[native] ${event.component} already provisioned")
                }
            }
            emitLine(
                "[native] JAVA_HOME = ${toolchain.javaHome.absolutePath}\n" +
                    "[native] ANDROID_HOME = ${toolchain.androidHome.absolutePath}\n" +
                    "[native] aapt2 = ${toolchain.aapt2.absolutePath}"
            )

            val launcherJar = findGradleLauncherJar(toolchain.gradleHome)
                ?: throw ToolchainManager.ToolchainException(
                    "gradle-launcher jar not found under ${toolchain.gradleHome}/lib"
                )
            emitLine("[native] Launcher: ${launcherJar.name}")

            val native = toolchainManager.nativeExecEnvironment(toolchain)
            val requestedTasks = tasks.ifEmpty { listOf("assembleDebug") }
            _state.value = NativeBuildState.Running(
                projectDir = projectDir.absolutePath,
                tasks = requestedTasks,
                startedAtMillis = System.currentTimeMillis(),
            )

            val command = native.commandPrefix +
                listOf(
                    "-Xms128m",
                    "-Xmx1536m",
                    "-Dorg.gradle.appname=apkfactory-gradle",
                    "-Dfile.encoding=UTF-8",
                    "-classpath",
                    launcherJar.absolutePath,
                    "org.gradle.launcher.daemon.bootstrap.GradleDaemon",
                    "--no-daemon",
                    "--console=plain",
                    "--stacktrace",
                    "-Pandroid.aapt2FromMavenOverride=${toolchain.aapt2.absolutePath}",
                ) + requestedTasks

            emitLine("[native] \$ " + command.joinToString(" ") { shortForm(it) })

            val processBuilder = ProcessBuilder(command).apply {
                directory(native.workingDir)
                redirectErrorStream(true)
                environment().clear()
                native.environment.forEach { (key, value) -> environment()[key] = value }
                // Android's ProcessBuilder inherits the app's PATH only if set;
                // linker64 is referenced by absolute path so PATH is irrelevant.
                environment()["PATH"] = "/system/bin:/system/xbin"
                environment()["LANG"] = "C.UTF-8"
                environment()["LC_ALL"] = "C.UTF-8"
            }

            val process = processBuilder.start()
            runningProcess.set(process)

            val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    emitLine(line)
                    updateNotification(line.take(120))
                }
            } finally {
                runningProcess.set(null)
            }

            val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)
            if (exitCode == 0) {
                val outputs = locateOutputApks(projectDir)
                emitLine(
                    "[native] BUILD SUCCESSFUL — ${outputs.size} APK(s): " +
                        outputs.joinToString(", ") { it.name }
                )
                _state.value = NativeBuildState.Success(exitCode, outputs)
            } else {
                emitLine("[native] BUILD FAILED (exit code $exitCode)")
                _state.value = NativeBuildState.Failed(
                    exitCode = exitCode,
                    message = "Gradle exited with code $exitCode — see the terminal log above",
                )
            }
        } catch (error: Exception) {
            emitLine("[native] ERROR: ${error.message}")
            _state.value = NativeBuildState.Failed(
                exitCode = -1,
                message = error.message ?: error.javaClass.simpleName,
            )
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun findGradleLauncherJar(gradleHome: File): File? {
        val libDir = File(gradleHome, "lib")
        val jars = libDir.listFiles { file ->
            file.isFile &&
                file.name.startsWith("gradle-launcher-") &&
                file.name.endsWith(".jar")
        }
        return jars?.maxByOrNull { it.name }
    }

    private fun locateOutputApks(projectDir: File): List<File> {
        val results = ArrayList<File>()
        fun scan(dir: File) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (child.isDirectory) {
                    scan(child)
                } else if (child.name.endsWith(".apk")) {
                    results.add(child)
                }
            }
        }
        // Only look inside module build output directories.
        val candidates = ArrayList<File>()
        val projectBuild = File(projectDir, "build/outputs/apk")
        if (projectBuild.isDirectory) candidates.add(projectBuild)
        projectDir.listFiles()?.filter { it.isDirectory && it.name != "build" }?.forEach { module ->
            val moduleOutputs = File(module, "build/outputs/apk")
            if (moduleOutputs.isDirectory) candidates.add(moduleOutputs)
        }
        candidates.forEach { scan(it) }
        return results.sortedBy { it.length() }
    }

    private fun shortForm(token: String): String = when {
        token.length > 72 -> "…" + token.takeLast(70)
        else -> token
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "unknown size"
        bytes >= 1L shl 30 -> String.format("%.1f GiB", bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> String.format("%.1f MiB", bytes.toDouble() / (1L shl 20))
        else -> String.format("%.0f KiB", bytes.toDouble() / (1L shl 10))
    }
}
