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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Shield
import com.universal.apkfactory.ui.components.EvidenceBox
import com.universal.apkfactory.ui.components.FactoryCardHeader
import com.universal.apkfactory.ui.components.GradientButton
import com.universal.apkfactory.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
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
    onInstallPythonRuntime: (url: String, sha256: String) -> Unit,
    onClearPythonRuntime: () -> Unit,
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
                        text = stringResource(R.string.config_signals),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    EvidenceBox(lines = project.evidence)
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
                FactoryCardHeader(
                    icon = Icons.Filled.Badge,
                    title = stringResource(R.string.config_identity),
                    subtitle = stringResource(R.string.config_identity_sub),
                )
                OutlinedTextField(
                    value = config.appName,
                    onValueChange = { value ->
                        onUpdateConfig { it.copy(appName = value) }
                    },
                    label = { Text(stringResource(R.string.label_app_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = config.packageName,
                    onValueChange = { value ->
                        onUpdateConfig { it.copy(packageName = value) }
                    },
                    label = { Text(stringResource(R.string.label_package_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = config.versionName,
                        onValueChange = { value ->
                            onUpdateConfig { it.copy(versionName = value) }
                        },
                        label = { Text(stringResource(R.string.label_version_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = config.versionCode.toString(),
                        onValueChange = { value ->
                            val parsed = value.filter { it.isDigit() }.toIntOrNull() ?: 1
                            onUpdateConfig { it.copy(versionCode = parsed) }
                        },
                        label = { Text(stringResource(R.string.label_version_code)) },
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
                        Text(stringResource(R.string.config_icon_title), style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = state.iconFileName
                                ?: stringResource(R.string.config_icon_default),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { iconPicker.launch("image/*") }) {
                        Text(stringResource(R.string.action_replace))
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
                FactoryCardHeader(
                    icon = Icons.Filled.Palette,
                    title = stringResource(R.string.config_look),
                    subtitle = stringResource(R.string.config_look_sub),
                )

                val orientations = listOf(
                    AppOrientation.UNSPECIFIED to stringResource(R.string.orient_auto),
                    AppOrientation.PORTRAIT to stringResource(R.string.orient_portrait),
                    AppOrientation.LANDSCAPE to stringResource(R.string.orient_landscape),
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
                        Text(stringResource(R.string.config_fullscreen))
                        Text(
                            text = stringResource(R.string.config_fullscreen_sub),
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

        // ---------- Python runtime (MODE_FAST_PYTHON) ----------
        if (project.mode == BuildMode.MODE_FAST_PYTHON) {
            PythonRuntimeCard(
                state = state,
                onInstall = onInstallPythonRuntime,
                onClear = onClearPythonRuntime,
            )
        }

        // ---------- Permissions ----------
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FactoryCardHeader(
                    icon = Icons.Filled.Shield,
                    title = stringResource(R.string.config_permissions),
                    subtitle = stringResource(R.string.config_permissions_sub),
                )
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
                FactoryCardHeader(
                    icon = Icons.Filled.RocketLaunch,
                    title = stringResource(R.string.config_engine),
                )

                when (project.mode) {
                    BuildMode.MODE_APK_EDITOR,
                    BuildMode.MODE_FAST_WEB,
                    BuildMode.MODE_FAST_PYTHON,
                    -> Text(
                        text = stringResource(R.string.engine_fast_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    BuildMode.MODE_CLOUD_WEB_OR_HYBRID,
                    BuildMode.MODE_CLOUD_FLUTTER,
                    -> {
                        Text(
                            text = stringResource(R.string.engine_cloud_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!state.patPresent) {
                            AssistChip(
                                onClick = onOpenKeystores,
                                label = { Text(stringResource(R.string.engine_need_token)) },
                            )
                        }
                    }

                    BuildMode.MODE_NATIVE_GRADLE -> {
                        Text(
                            text = stringResource(R.string.engine_native_desc),
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
                                Text(stringResource(R.string.engine_on_device))
                            }
                            SegmentedButton(
                                selected = !state.nativeOnDevice,
                                onClick = { onToggleNativeOnDevice(false) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = 1, count = 2
                                ),
                            ) {
                                Text(stringResource(R.string.engine_cloud_ci))
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
                                label = { Text(stringResource(R.string.engine_need_token)) },
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

                GradientButton(
                    text = buttonLabelFor(project.mode, state.nativeOnDevice),
                    onClick = onStartBuild,
                    enabled = problems.isEmpty() && !state.busy,
                    icon = Icons.Filled.RocketLaunch,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/* ---------------------------------------------------------------------- */
/* Python runtime provisioning card                                       */
/* ---------------------------------------------------------------------- */

/**
 * Python runtime status card (MODE_FAST_PYTHON).
 *
 * Since v5 the factory APK bundles a CPython aarch64-android distribution
 * inside the python shell template — the card then presents the runtime as
 * ready out of the box. A custom distribution can still be downloaded,
 * checksum-verified and installed (advanced section); it is injected in
 * place of the bundled one at patch time.
 */
@Composable
private fun PythonRuntimeCard(
    state: MainViewModel.UiState,
    onInstall: (url: String, sha256: String) -> Unit,
    onClear: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    var sha256 by rememberSaveable { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    val info = state.pythonRuntime
    val bundled = state.pythonRuntimeBundled

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.python_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    if (bundled) R.string.python_bundled_desc else R.string.python_desc
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (info != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(
                            R.string.python_installed,
                            info.pythonVersion,
                            info.fileCount,
                            info.displaySize,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (bundled) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = "  " + stringResource(R.string.python_bundled_badge) + "  ",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.python_source, info.sourceUrl),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.python_custom_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onClear, enabled = !state.pythonRuntimeBusy) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("  " + stringResource(R.string.python_remove))
                    }
                }
                TextButton(
                    onClick = { showAdvanced = !showAdvanced },
                    enabled = !state.pythonRuntimeBusy,
                ) {
                    Text(
                        text = stringResource(
                            if (showAdvanced) R.string.python_advanced_hide
                            else R.string.python_advanced_show
                        )
                    )
                }
                if (showAdvanced) {
                    ManualRuntimeFields(
                        state = state,
                        url = url,
                        sha256 = sha256,
                        onUrlChange = { url = it },
                        onShaChange = { sha256 = it },
                        onInstall = onInstall,
                    )
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.python_missing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ManualRuntimeFields(
                    state = state,
                    url = url,
                    sha256 = sha256,
                    onUrlChange = { url = it },
                    onShaChange = { sha256 = it },
                    onInstall = onInstall,
                )
            }

            state.pythonRuntimeProgress?.let { progress ->
                Text(
                    text = progress,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** URL + SHA-256 entry + install button for a custom runtime. */
@Composable
private fun ManualRuntimeFields(
    state: MainViewModel.UiState,
    url: String,
    sha256: String,
    onUrlChange: (String) -> Unit,
    onShaChange: (String) -> Unit,
    onInstall: (url: String, sha256: String) -> Unit,
) {
    OutlinedTextField(
        value = url,
        onValueChange = onUrlChange,
        label = { Text(stringResource(R.string.label_runtime_url)) },
        singleLine = true,
        enabled = !state.pythonRuntimeBusy,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = sha256,
        onValueChange = onShaChange,
        label = { Text(stringResource(R.string.label_sha256)) },
        singleLine = true,
        enabled = !state.pythonRuntimeBusy,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onInstall(url, sha256) },
        enabled = !state.pythonRuntimeBusy &&
            url.startsWith("http") && !state.busy,
    ) {
        Icon(
            Icons.Filled.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
        Text("  " + stringResource(R.string.python_install))
    }
}

@Composable
private fun buttonLabelFor(mode: BuildMode, nativeOnDevice: Boolean): String = when (mode) {
    BuildMode.MODE_APK_EDITOR -> stringResource(R.string.btn_apk_editor)
    BuildMode.MODE_FAST_WEB -> stringResource(R.string.btn_fast_web)
    BuildMode.MODE_FAST_PYTHON -> stringResource(R.string.btn_fast_python)
    BuildMode.MODE_CLOUD_WEB_OR_HYBRID -> stringResource(R.string.btn_cloud)
    BuildMode.MODE_CLOUD_FLUTTER -> stringResource(R.string.btn_flutter)
    BuildMode.MODE_NATIVE_GRADLE ->
        if (nativeOnDevice) stringResource(R.string.btn_native_device)
        else stringResource(R.string.btn_cloud)
}
