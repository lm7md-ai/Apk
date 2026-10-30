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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.universal.apkfactory.MainViewModel
import com.universal.apkfactory.engine.gradle.ToolchainManager

/**
 * Part 5 — Dashboard: the ingestion hub.
 *
 * Every factory session starts here: pick an archive, pick a folder, paste
 * a GitHub URL (or share one from any app), pre-provision the on-device
 * compiler toolchain, or manage keystores and the cloud PAT.
 */
@Composable
fun HomeScreen(
    state: MainViewModel.UiState,
    onSubmitGithubUrl: (String) -> Unit,
    onSubmitUri: (android.net.Uri, com.universal.apkfactory.core.model.IngestionOrigin) -> Unit,
    onIngestFolder: (android.net.Uri) -> Unit,
    onOpenKeystores: () -> Unit,
    onPrepareToolchain: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var githubUrl by rememberSaveable { mutableStateOf("") }

    val archivePicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            onSubmitUri(it, com.universal.apkfactory.core.model.IngestionOrigin.SAF_PICKER)
        }
    }
    val folderPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { onIngestFolder(it) } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column {
            Text(
                text = "Universal APK Factory",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Turn anything into an installable, signed Android app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.busy) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            text = state.busyMessage ?: "Working …",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    when (val event = state.toolchainEvent) {
                        is ToolchainManager.Event.DownloadProgress -> {
                            LinearProgressIndicator(
                                progress = {
                                    if (event.bytesTotal > 0) {
                                        event.bytesDownloaded.toFloat() / event.bytesTotal
                                    } else {
                                        0f
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "${event.bytesDownloaded / 1024} / " +
                                    "${if (event.bytesTotal > 0) event.bytesTotal / 1024 else '?'} KiB",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }

                        else -> Unit
                    }
                }
            }
        }

        // ---------- GitHub URL ----------
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "From a GitHub repository",
                    style = MaterialTheme.typography.titleMedium,
                )
                OutlinedTextField(
                    value = githubUrl,
                    onValueChange = { githubUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("https://github.com/owner/repo") },
                    supportingText = {
                        Text("Branch, tag and subfolder URLs are supported")
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onSubmitGithubUrl(githubUrl) },
                        enabled = githubUrl.isNotBlank() && !state.busy,
                    ) {
                        Text("Fetch & detect")
                    }
                }
            }
        }

        // ---------- Local sources ----------
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SourceCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.FolderZip,
                title = "ZIP / APK",
                subtitle = "Archive from your device",
                enabled = !state.busy,
                onClick = { archivePicker.launch("*/*") },
            )
            SourceCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.CreateNewFolder,
                title = "Folder",
                subtitle = "Pick a project directory",
                enabled = !state.busy,
                onClick = { folderPicker.launch(null) },
            )
        }

        Text(
            text = "… or use the Android Share Sheet to send archives and GitHub links here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---------- Toolchain ----------
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "On-device compiler",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (state.toolchainReady) {
                                "JDK 21 + Gradle 8.9 + Android SDK ready"
                            } else {
                                "Not downloaded yet (about 500 MiB, one time)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = onPrepareToolchain,
                        enabled = !state.busy,
                    ) {
                        Text(if (state.toolchainReady) "Verify" else "Download")
                    }
                }
            }
        }

        // ---------- Keystores + cloud ----------
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenKeystores,
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Keystores & Cloud",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "${state.keystores.size} keystores • " +
                            if (state.patPresent) {
                                "GitHub token present (@${state.patLogin})"
                            } else {
                                "no GitHub token yet"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SourceCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ElevatedCard(modifier = modifier, onClick = onClick, enabled = enabled) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
