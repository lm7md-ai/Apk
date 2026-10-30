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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.universal.apkfactory.R
import com.universal.apkfactory.ui.AppLocale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.universal.apkfactory.MainViewModel
import com.universal.apkfactory.engine.gradle.ToolchainManager
import com.universal.apkfactory.ui.components.FactoryCardHeader
import com.universal.apkfactory.ui.components.HeroHeader
import com.universal.apkfactory.ui.components.IconBadge
import com.universal.apkfactory.ui.components.InfoBanner
import com.universal.apkfactory.ui.components.StepRow
import com.universal.apkfactory.ui.components.StatusPill
import com.universal.apkfactory.ui.theme.Dimens

/**
 * Dashboard — the ingestion hub, redesigned around the Amber Forge system:
 * a hero brand header, a focused GitHub import card, two large source
 * tiles, a live readiness strip and a compact "how it works" guide.
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
            .padding(horizontal = Dimens.screenH, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroHeader()

        // ---------- Busy / provisioning card ----------
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
                            text = state.busyMessage ?: stringResource(R.string.busy_working),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
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

        // ---------- Readiness strip ----------
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusPill(
                text = if (state.toolchainReady) {
                    stringResource(R.string.pill_toolchain)
                } else {
                    stringResource(R.string.pill_no_toolchain)
                },
                ok = state.toolchainReady,
                modifier = Modifier.weight(1f, fill = false),
            )
            StatusPill(
                text = if (state.patPresent) {
                    "@${state.patLogin ?: "github"}"
                } else {
                    stringResource(R.string.pill_no_token)
                },
                ok = state.patPresent,
                modifier = Modifier.weight(1f, fill = false),
            )
            StatusPill(
                text = if (state.pythonRuntime != null) {
                    stringResource(
                        R.string.pill_python_version,
                        state.pythonRuntime.pythonVersion,
                    )
                } else {
                    stringResource(R.string.pill_no_python)
                },
                ok = state.pythonRuntime != null,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        // ---------- GitHub import ----------
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FactoryCardHeader(
                    icon = Icons.Filled.Cloud,
                    title = stringResource(R.string.home_github_title),
                    subtitle = stringResource(R.string.home_github_hint),
                    badgeContainer = MaterialTheme.colorScheme.tertiaryContainer,
                    badgeTint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                OutlinedTextField(
                    value = githubUrl,
                    onValueChange = { githubUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("https://github.com/owner/repo") },
                )
                Button(
                    onClick = { onSubmitGithubUrl(githubUrl) },
                    enabled = githubUrl.isNotBlank() && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.home_fetch_detect))
                }
            }
        }

        // ---------- Local source tiles ----------
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SourceTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.FolderZip,
                title = stringResource(R.string.home_zip_title),
                subtitle = stringResource(R.string.home_zip_sub),
                enabled = !state.busy,
                onClick = { archivePicker.launch("*/*") },
            )
            SourceTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.CreateNewFolder,
                title = stringResource(R.string.home_folder_title),
                subtitle = stringResource(R.string.home_folder_sub),
                enabled = !state.busy,
                onClick = { folderPicker.launch(null) },
            )
        }

        InfoBanner(
            text = stringResource(R.string.home_share_hint),
            icon = Icons.Filled.Share,
        )

        // ---------- Toolchain ----------
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FactoryCardHeader(
                    icon = Icons.Filled.Memory,
                    title = stringResource(R.string.home_toolchain_title),
                    subtitle = if (state.toolchainReady) {
                        stringResource(R.string.home_toolchain_ready)
                    } else {
                        stringResource(R.string.home_toolchain_missing)
                    },
                    trailing = {
                        TextButton(
                            onClick = onPrepareToolchain,
                            enabled = !state.busy,
                        ) {
                            Text(
                                if (state.toolchainReady) {
                                    stringResource(R.string.action_verify)
                                } else {
                                    stringResource(R.string.action_download)
                                }
                            )
                        }
                    },
                )
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
                IconBadge(
                    icon = Icons.Filled.Key,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_keystores_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = if (state.patPresent) {
                            stringResource(
                                R.string.home_keystores_count_token,
                                state.keystores.size,
                                state.patLogin ?: "github",
                            )
                        } else {
                            stringResource(
                                R.string.home_keystores_count_no_token,
                                state.keystores.size,
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // ---------- Language ----------
        val context = LocalContext.current
        var language by androidx.compose.runtime.rememberSaveable {
            androidx.compose.runtime.mutableStateOf(AppLocale.current(context))
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FactoryCardHeader(
                    icon = Icons.Filled.Language,
                    title = stringResource(R.string.settings_language),
                    subtitle = stringResource(R.string.settings_language_sub),
                    badgeContainer = MaterialTheme.colorScheme.tertiaryContainer,
                    badgeTint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AppLocale.SYSTEM to R.string.lang_system,
                        AppLocale.ENGLISH to R.string.lang_english,
                        AppLocale.ARABIC to R.string.lang_arabic,
                    ).forEach { (tag, labelRes) ->
                        FilterChip(
                            selected = language == tag,
                            onClick = {
                                language = tag
                                AppLocale.set(context, tag)
                                AppLocale.findActivity(context)?.recreate()
                            },
                            label = { Text(stringResource(labelRes)) },
                        )
                    }
                }
            }
        }

        // ---------- How it works ----------
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FactoryCardHeader(
                    icon = Icons.Filled.PrecisionManufacturing,
                    title = stringResource(R.string.home_how_title),
                    badgeContainer = MaterialTheme.colorScheme.surfaceVariant,
                    badgeTint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                StepRow(
                    number = 1,
                    title = stringResource(R.string.home_step1_title),
                    description = stringResource(R.string.home_step1_desc),
                )
                StepRow(
                    number = 2,
                    title = stringResource(R.string.home_step2_title),
                    description = stringResource(R.string.home_step2_desc),
                )
                StepRow(
                    number = 3,
                    title = stringResource(R.string.home_step3_title),
                    description = stringResource(R.string.home_step3_desc),
                )
            }
        }

        Spacer(modifier = Modifier.height(Dimens.xl))
    }
}

/* ---------------------------------------------------------------------- */
/* Source tile                                                            */
/* ---------------------------------------------------------------------- */

@Composable
private fun SourceTile(
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconBadge(icon = icon)
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
