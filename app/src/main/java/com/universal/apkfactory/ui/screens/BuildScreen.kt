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
package com.universal.apkfactory.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.universal.apkfactory.MainViewModel
import com.universal.apkfactory.engine.cloud.GitHubCloudBuilder
import com.universal.apkfactory.engine.gradle.NativeGradleService
import com.universal.apkfactory.engine.gradle.ToolchainManager
import com.universal.apkfactory.ui.components.TerminalLogViewer

/**
 * Part 5 — the live build screen: phase banner, cloud/toolchain progress
 * cards, the Live Terminal Log Viewer and the result panel (install /
 * share / new build).
 */
@Composable
fun BuildScreen(
    state: MainViewModel.UiState,
    onInstall: () -> Unit,
    onShare: () -> Unit,
    onNewBuild: () -> Unit,
    onBackToConfig: () -> Unit,
    onCancelNative: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PhaseBanner(state, onCancelNative)

        state.cloudProgress?.let { progress ->
            CloudProgressCard(progress)
        }

        ToolchainProgressCard(state.toolchainEvent)

        NativeSummaryCard(state)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Terminal",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TerminalLogViewer(lines = state.logs)
        }

        state.resultApk?.let { apk ->
            ResultPanel(apk = apk, onInstall = onInstall, onShare = onShare)
        }

        if (state.phase == MainViewModel.BuildPhase.FAILED) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBackToConfig) {
                    Text("Adjust configuration")
                }
                OutlinedButton(onClick = onNewBuild) {
                    Text("Start over")
                }
            }
            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/* ---------------------------------------------------------------------- */
/* Phase banner                                                            */
/* ---------------------------------------------------------------------- */

@Composable
private fun PhaseBanner(state: MainViewModel.UiState, onCancelNative: () -> Unit) {
    val (icon, title, running) = when (state.phase) {
        MainViewModel.BuildPhase.IDLE -> Triple(
            Icons.Filled.Memory, "Ready", false
        )

        MainViewModel.BuildPhase.INGESTING -> Triple(
            Icons.Filled.Memory, "Importing project …", true
        )

        MainViewModel.BuildPhase.DETECTING -> Triple(
            Icons.Filled.Memory, "Detecting stack …", true
        )

        MainViewModel.BuildPhase.FAST_BUILDING -> Triple(
            Icons.Filled.Memory, "Patching shell, aligning & signing …", true
        )

        MainViewModel.BuildPhase.CLOUD_BUILDING -> Triple(
            Icons.Filled.CloudQueue, "Building on GitHub Actions …", true
        )

        MainViewModel.BuildPhase.NATIVE_BUILDING -> Triple(
            Icons.Filled.Memory, "Compiling on device (Gradle) …", true
        )

        MainViewModel.BuildPhase.FINALIZING -> Triple(
            Icons.Filled.Memory, "Sanitizing, aligning & signing …", true
        )

        MainViewModel.BuildPhase.DONE -> Triple(
            Icons.Filled.CheckCircle, "Build complete", false
        )

        MainViewModel.BuildPhase.FAILED -> Triple(
            Icons.Filled.ErrorOutline, "Build failed", false
        )
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (running) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (state.phase == MainViewModel.BuildPhase.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (running) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (state.phase == MainViewModel.BuildPhase.NATIVE_BUILDING) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onCancelNative) {
                    Text("Stop build")
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------- */
/* Cloud progress                                                          */
/* ---------------------------------------------------------------------- */

@Composable
private fun CloudProgressCard(progress: GitHubCloudBuilder.CloudProgress) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.CloudQueue,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = stageLabel(progress.stage),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${progress.elapsedSeconds}s",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = progress.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            progress.workflowStep?.let { step ->
                Text(
                    text = "Current step: $step",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (progress.uploadTotal > 0) {
                LinearProgressIndicator(
                    progress = { progress.uploaded.toFloat() / progress.uploadTotal },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            progress.runUrl?.let { url ->
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

private fun stageLabel(stage: GitHubCloudBuilder.CloudStage): String = when (stage) {
    GitHubCloudBuilder.CloudStage.PREPARING -> "Preparing cloud build"
    GitHubCloudBuilder.CloudStage.UPLOADING -> "Uploading project"
    GitHubCloudBuilder.CloudStage.QUEUED -> "Waiting for a runner"
    GitHubCloudBuilder.CloudStage.RUNNING -> "Compiling on GitHub"
    GitHubCloudBuilder.CloudStage.ARTIFACT -> "Downloading artifact"
    GitHubCloudBuilder.CloudStage.COMPLETE -> "Cloud build finished"
}

/* ---------------------------------------------------------------------- */
/* Toolchain download progress                                             */
/* ---------------------------------------------------------------------- */

@Composable
private fun ToolchainProgressCard(event: ToolchainManager.Event?) {
    val download = event as? ToolchainManager.Event.DownloadProgress
    val extract = event as? ToolchainManager.Event.Extracting
    if (download == null && extract == null) return

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Toolchain provisioning",
                style = MaterialTheme.typography.titleSmall,
            )
            if (download != null) {
                LinearProgressIndicator(
                    progress = {
                        if (download.bytesTotal > 0) {
                            download.bytesDownloaded.toFloat() / download.bytesTotal
                        } else {
                            0f
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Downloading ${download.component}: " +
                        "${download.bytesDownloaded / 1024} / " +
                        "${if (download.bytesTotal > 0) download.bytesTotal / 1024 else '?'} KiB",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (extract != null) {
                LinearProgressIndicator(
                    progress = {
                        if (extract.bytesTotal > 0) {
                            extract.bytesWritten.toFloat() / extract.bytesTotal
                        } else {
                            0f
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "Extracting ${extract.component} …",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/* ---------------------------------------------------------------------- */
/* Native build summary                                                    */
/* ---------------------------------------------------------------------- */

@Composable
private fun NativeSummaryCard(state: MainViewModel.UiState) {
    val running = state.nativeState as? NativeGradleService.NativeBuildState.Running
    val success = state.nativeState as? NativeGradleService.NativeBuildState.Success
    if (running == null && success == null) return

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "On-device Gradle",
                style = MaterialTheme.typography.titleSmall,
            )
            if (running != null) {
                Text(
                    text = "Tasks: ${running.tasks.joinToString(" ")}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                val seconds = (System.currentTimeMillis() - running.startedAtMillis) / 1000
                Text(
                    text = "Elapsed: ${seconds}s",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (success != null) {
                success.outputApks.forEach { apk ->
                    Text(
                        text = "• ${apk.name} (${apk.length() / 1024} KiB)",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------- */
/* Result panel                                                            */
/* ---------------------------------------------------------------------- */

@Composable
private fun ResultPanel(apk: java.io.File, onInstall: () -> Unit, onShare: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Column {
                    Text(
                        text = apk.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${apk.length() / 1024} KiB • signed (V1 + V2 + V3) • 4-byte aligned",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onInstall) {
                    Icon(
                        imageVector = Icons.Filled.InstallMobile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Install")
                }
                OutlinedButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share")
                }
            }
        }
    }
}
