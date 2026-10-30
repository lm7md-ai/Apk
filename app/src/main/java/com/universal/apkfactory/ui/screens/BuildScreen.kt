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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import com.universal.apkfactory.ui.theme.FactoryGradients
import com.universal.apkfactory.ui.theme.FactoryTheme
import androidx.compose.ui.unit.dp
import com.universal.apkfactory.MainViewModel
import com.universal.apkfactory.engine.cloud.GitHubCloudBuilder
import com.universal.apkfactory.engine.gradle.NativeGradleService
import com.universal.apkfactory.engine.gradle.ToolchainManager
import com.universal.apkfactory.ui.components.FailureDiagnosisCard
import com.universal.apkfactory.ui.components.TerminalLogViewer
import com.universal.apkfactory.R
import androidx.compose.ui.res.stringResource

/**
 * Part 5 — the live build screen: phase banner, cloud/toolchain progress
 * cards, the Live Terminal Log Viewer and the result panel (install /
 * share / new build).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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

        TerminalCard(lines = state.logs)

        state.resultApk?.let { apk ->
            ResultPanel(apk = apk, onInstall = onInstall, onShare = onShare)
        }

        if (state.phase == MainViewModel.BuildPhase.FAILED) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBackToConfig) {
                    Text(stringResource(R.string.build_adjust))
                }
                OutlinedButton(onClick = onNewBuild) {
                    Text(stringResource(R.string.build_restart))
                }
            }
            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Smart failure diagnosis + ready-to-send AI prompt.
            state.diagnosis?.let { diagnosis ->
                FailureDiagnosisCard(diagnosis = diagnosis)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/* ---------------------------------------------------------------------- */
/* Terminal card                                                           */
/* ---------------------------------------------------------------------- */

/**
 * The terminal wrapped in a card with a header row: terminal icon, live
 * line counter and a one-tap copy-all action.
 */
@Composable
private fun TerminalCard(lines: List<com.universal.apkfactory.core.model.BuildLogLine>) {
    val context = LocalContext.current
    var copied by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val clipboardLabel = stringResource(R.string.clipboard_label_build_log)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Terminal,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.build_log_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.build_log_lines, lines.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                enabled = lines.isNotEmpty(),
                onClick = {
                    val clipboard = context.getSystemService(
                        android.content.ClipboardManager::class.java
                    )
                    clipboard?.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            clipboardLabel,
                            lines.joinToString("\n") {
                                "[%s] %s".format(it.level, it.message)
                            },
                        )
                    )
                    copied = true
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
                Text(if (copied) "  " + stringResource(R.string.action_copied) else "  " + stringResource(R.string.action_copy))
            }
        }
        TerminalLogViewer(lines = lines)
    }
}

/* ---------------------------------------------------------------------- */
/* Phase banner                                                            */
/* ---------------------------------------------------------------------- */

@Composable
private fun PhaseBanner(state: MainViewModel.UiState, onCancelNative: () -> Unit) {
    val (icon, title, running) = when (state.phase) {
        MainViewModel.BuildPhase.IDLE -> Triple(
            Icons.Filled.Memory, stringResource(R.string.phase_ready), false
        )

        MainViewModel.BuildPhase.INGESTING -> Triple(
            Icons.Filled.Memory, stringResource(R.string.phase_ingesting), true
        )

        MainViewModel.BuildPhase.DETECTING -> Triple(
            Icons.Filled.Memory, stringResource(R.string.phase_detecting), true
        )

        MainViewModel.BuildPhase.FAST_BUILDING -> Triple(
            Icons.Filled.Memory, stringResource(R.string.phase_fast), true
        )

        MainViewModel.BuildPhase.CLOUD_BUILDING -> Triple(
            Icons.Filled.CloudQueue, stringResource(R.string.phase_cloud), true
        )

        MainViewModel.BuildPhase.NATIVE_BUILDING -> Triple(
            Icons.Filled.Memory, stringResource(R.string.phase_native), true
        )

        MainViewModel.BuildPhase.FINALIZING -> Triple(
            Icons.Filled.Memory, stringResource(R.string.phase_finalizing), true
        )

        MainViewModel.BuildPhase.DONE -> Triple(
            Icons.Filled.CheckCircle, stringResource(R.string.phase_done), false
        )

        MainViewModel.BuildPhase.FAILED -> Triple(
            Icons.Filled.ErrorOutline, stringResource(R.string.phase_failed), false
        )
    }

    val failed = state.phase == MainViewModel.BuildPhase.FAILED
    val done = state.phase == MainViewModel.BuildPhase.DONE
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900)),
        label = "pulseAlpha",
    )

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            when {
                                failed -> MaterialTheme.colorScheme.errorContainer
                                done -> FactoryTheme.ext.successContainer
                                else -> MaterialTheme.colorScheme.primaryContainer
                            },
                            RoundedCornerShape(14.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (running) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(22.dp)
                                .alpha(pulseAlpha),
                            strokeWidth = 2.5.dp,
                        )
                    } else {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = when {
                                failed -> MaterialTheme.colorScheme.onErrorContainer
                                done -> FactoryTheme.ext.onSuccessContainer
                                else -> MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (running) {
                Spacer(modifier = Modifier.height(14.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(pulseAlpha * 0.6f + 0.4f),
                )
            }
            if (state.phase == MainViewModel.BuildPhase.NATIVE_BUILDING) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onCancelNative) {
                    Text(stringResource(R.string.build_stop))
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
                    text = stringResource(R.string.cloud_current_step, step),
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

@Composable
private fun stageLabel(stage: GitHubCloudBuilder.CloudStage): String = when (stage) {
    GitHubCloudBuilder.CloudStage.PREPARING -> stringResource(R.string.cloud_stage_preparing)
    GitHubCloudBuilder.CloudStage.UPLOADING -> stringResource(R.string.cloud_stage_uploading)
    GitHubCloudBuilder.CloudStage.QUEUED -> stringResource(R.string.cloud_stage_queued)
    GitHubCloudBuilder.CloudStage.RUNNING -> stringResource(R.string.cloud_stage_running)
    GitHubCloudBuilder.CloudStage.ARTIFACT -> stringResource(R.string.cloud_stage_artifact)
    GitHubCloudBuilder.CloudStage.COMPLETE -> stringResource(R.string.cloud_stage_complete)
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
                text = stringResource(R.string.toolchain_provisioning),
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
                    text = stringResource(
                        R.string.toolchain_downloading,
                        download.component,
                        download.bytesDownloaded / 1024,
                        if (download.bytesTotal > 0) download.bytesTotal / 1024 else 0,
                    ),
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
                    text = stringResource(R.string.toolchain_extracting, extract.component),
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
                text = stringResource(R.string.native_summary_title),
                style = MaterialTheme.typography.titleSmall,
            )
            if (running != null) {
                Text(
                    text = stringResource(R.string.native_tasks, running.tasks.joinToString(" ")),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                val seconds = (System.currentTimeMillis() - running.startedAtMillis) / 1000
                Text(
                    text = stringResource(R.string.native_elapsed, seconds),
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
            containerColor = MaterialTheme.colorScheme.surface
        ),
    ) {
        Column {
            // Gradient success band.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(FactoryTheme.ext.successContainer)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = FactoryTheme.ext.onSuccessContainer,
                    modifier = Modifier.size(28.dp),
                )
                Column {
                    Text(
                        text = apk.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = FactoryTheme.ext.onSuccessContainer,
                    )
                    Text(
                        text = stringResource(
                            R.string.result_meta,
                            apk.length() / 1024.0 / 1024.0,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = FactoryTheme.ext.onSuccessContainer,
                    )
                }
            }
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onInstall,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.InstallMobile,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_install))
                }
                OutlinedButton(
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_share))
                }
            }
        }
    }
}
