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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.universal.apkfactory.MainViewModel
import com.universal.apkfactory.core.model.AppOrientation
import com.universal.apkfactory.core.model.BuildMode
import com.universal.apkfactory.core.model.PermissionCatalog
import com.universal.apkfactory.core.model.ProjectBuildConfig

/**
 * Part 5 — Hybrid Pre-Build Configuration Sheet.
 *
 * Pre-populated from the detector + HybridConfigParser (package.json /
 * pubspec.yaml / PWA manifest / APK metadata), then fully editable:
 * identity, versioning, orientation, fullscreen, permissions and the
 * launcher icon. The bottom section adapts to the detected build mode and
 * offers the Instant / Cloud / On-device engine choice.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(
    state: MainViewModel.UiState,
    project: com.universal.apkfactory.core.model.DetectedProject,
    config: ProjectBuildConfig,
    onUpdateConfig: ((ProjectBuildConfig) -> ProjectBuildConfig) -> Unit,
    onTogglePermission: (String) -> Unit,
    onSetIcon: (android.net.Uri) -> Unit,
    onToggleNativeOnDevice: (Boolean) -> Unit,
    onStartBuild: () -> Unit,
    onOpenKeystores: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val problems = config.validate()

    val iconPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(onSetIcon) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------- Detected stack ----------
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = project.mode.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = project.mode.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Source: ${project.source.displayName} " +
                        "(${project.source.origin})",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
                if (project.evidence.isNotEmpty()) {
                    Text(
                        text = "Detection signals",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    project.evidence.take(8).forEach { signal ->
                        Text(
                            text = "• $signal",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                config.metadataOriginNote?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        // ---------- Identity ----------
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("App identity", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = config.appName,
                    onValueChange = { value ->
                        onUpdateConfig { it.copy(appName = value) }
                    },
                    label = { Text("App name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = config.packageName,
                    onValueChange = { value ->
                        onUpdateConfig { it.copy(packageName = value) }
                    },
                    label = { Text("Package name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = config.versionName,
                        onValueChange = { value ->
                            onUpdateConfig { it.copy(versionName = value) }
                        },
                        label = { Text("Version name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = config.versionCode.toString(),
                        onValueChange = { value ->
                            val parsed = value.filter { it.isDigit() }.toIntOrNull() ?: 1
                            onUpdateConfig { it.copy(versionCode = parsed) }
                        },
                        label = { Text("Version code") },
                        singleLine = true,
                        modifier = Modifier.weight(0.6f),
                    )
                }

                // Launcher icon
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Launcher icon", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = state.iconFileName
                                ?: "Using the default shell icon",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { iconPicker.launch("image/*") }) {
                        Text("Replace")
                    }
                }
            }
        }

        // ---------- Look & feel ----------
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Look & feel", style = MaterialTheme.typography.titleMedium)

                val orientations = listOf(
                    AppOrientation.UNSPECIFIED to "Auto",
                    AppOrientation.PORTRAIT to "Portrait",
                    AppOrientation.LANDSCAPE to "Landscape",
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    orientations.forEachIndexed { index, (orientation, label) ->
                        SegmentedButton(
                            selected = config.orientation == orientation,
                            onClick = {
                                onUpdateConfig { it.copy(orientation = orientation) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index, count = orientations.size
                            ),
                        ) {
                            Text(label)
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Fullscreen / immersive")
                        Text(
                            text = "Hide the status and navigation bars",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = config.fullscreen,
                        onCheckedChange = { value ->
                            onUpdateConfig { it.copy(fullscreen = value) }
                        },
                    )
                }
            }
        }

        // ---------- Permissions ----------
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Permissions", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "INTERNET is always included for web and python shells.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PermissionCatalog.ALL.forEach { info ->
                        FilterChip(
                            selected = info.permission in config.permissions,
                            onClick = { onTogglePermission(info.permission) },
                            label = { Text(info.label) },
                        )
                    }
                }
            }
        }

        // ---------- Engine choice + launch ----------
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Build engine", style = MaterialTheme.typography.titleMedium)

                when (project.mode) {
                    BuildMode.MODE_APK_EDITOR,
                    BuildMode.MODE_FAST_WEB,
                    BuildMode.MODE_FAST_PYTHON,
                    -> Text(
                        text = "Instant on-device packaging: shell patch + 4-byte align + " +
                            "V1/V2/V3 signing. No compiler needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    BuildMode.MODE_CLOUD_WEB_OR_HYBRID,
                    BuildMode.MODE_CLOUD_FLUTTER,
                    -> {
                        Text(
                            text = "The Node/Flutter toolchain runs on a free GitHub Actions " +
                                "runner; the result is finished and signed on this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!state.patPresent) {
                            AssistChip(
                                onClick = onOpenKeystores,
                                label = { Text("Add your GitHub token to continue") },
                            )
                        }
                    }

                    BuildMode.MODE_NATIVE_GRADLE -> {
                        Text(
                            text = "Compile with the ARM64 Gradle toolchain on this device, " +
                                "or on a GitHub Actions runner.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = state.nativeOnDevice,
                                onClick = { onToggleNativeOnDevice(true) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = 0, count = 2
                                ),
                            ) {
                                Text("On device")
                            }
                            SegmentedButton(
                                selected = !state.nativeOnDevice,
                                onClick = { onToggleNativeOnDevice(false) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = 1, count = 2
                                ),
                            ) {
                                Text("Cloud CI")
                            }
                        }
                        if (state.nativeOnDevice && !state.toolchainReady) {
                            Text(
                                text = "The compiler toolchain (~500 MiB) will be downloaded " +
                                    "on first use.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (!state.nativeOnDevice && !state.patPresent) {
                            AssistChip(
                                onClick = onOpenKeystores,
                                label = { Text("Add your GitHub token to continue") },
                            )
                        }
                    }
                }

                if (problems.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            problems.forEach { problem ->
                                Text(
                                    text = "• $problem",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = onStartBuild,
                    enabled = problems.isEmpty() && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(buttonLabelFor(project.mode, state.nativeOnDevice))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private fun buttonLabelFor(mode: BuildMode, nativeOnDevice: Boolean): String = when (mode) {
    BuildMode.MODE_APK_EDITOR -> "Patch & re-sign APK"
    BuildMode.MODE_FAST_WEB -> "Build instant WebView APK"
    BuildMode.MODE_FAST_PYTHON -> "Build instant Python APK"
    BuildMode.MODE_CLOUD_WEB_OR_HYBRID -> "Build on GitHub Actions"
    BuildMode.MODE_CLOUD_FLUTTER -> "Build Flutter on GitHub Actions"
    BuildMode.MODE_NATIVE_GRADLE ->
        if (nativeOnDevice) "Compile on device" else "Build on GitHub Actions"
}
