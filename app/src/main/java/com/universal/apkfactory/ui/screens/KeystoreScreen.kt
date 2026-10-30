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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.VpnKey
import com.universal.apkfactory.ui.components.FactoryCardHeader
import com.universal.apkfactory.ui.components.StatusPill
import com.universal.apkfactory.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.universal.apkfactory.MainViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Part 5 — Keystore Manager UI + GitHub PAT management.
 *
 * * Signing keystores: auto-generated per package by core-signer, listed
 *   here, plus manual import of existing BouncyCastle/JKS/PKCS12 stores.
 * * The GitHub Personal Access Token lives in EncryptedSharedPreferences
 *   and powers the cloud CI bridge. Classic tokens need the `repo`,
 *   `workflow` and `delete_repo` scopes.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun KeystoreScreen(
    state: MainViewModel.UiState,
    onSavePat: (String) -> Unit,
    onClearPat: () -> Unit,
    onImportKeystore: (android.net.Uri, String) -> Unit,
    onCreateKeystore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pat by rememberSaveable { mutableStateOf("") }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var pendingKeystoreUri by rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    var importPassword by rememberSaveable { mutableStateOf("") }
    var newPackageName by rememberSaveable { mutableStateOf("") }

    val keystorePicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingKeystoreUri = uri
            importPassword = ""
            showImportDialog = true
        }
    }

    if (showImportDialog && pendingKeystoreUri != null) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text(stringResource(R.string.keys_import)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.keys_import_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = importPassword,
                        onValueChange = { importPassword = it },
                        label = { Text(stringResource(R.string.label_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingKeystoreUri
                        if (uri != null && importPassword.isNotEmpty()) {
                            onImportKeystore(uri, importPassword)
                        }
                        showImportDialog = false
                        importPassword = ""
                        pendingKeystoreUri = null
                    },
                    enabled = importPassword.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.action_import))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------- GitHub PAT ----------
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FactoryCardHeader(
                    icon = Icons.Filled.VpnKey,
                    title = stringResource(R.string.pat_title),
                    subtitle = stringResource(R.string.pat_sub),
                    badgeContainer = MaterialTheme.colorScheme.tertiaryContainer,
                    badgeTint = MaterialTheme.colorScheme.onTertiaryContainer,
                    trailing = {
                        if (state.patPresent) {
                            StatusPill(
                                text = stringResource(R.string.pill_verified),
                                ok = true,
                            )
                        }
                    },
                )
                Text(
                    text = stringResource(R.string.pat_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.patPresent) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(
                                R.string.pat_signed_in,
                                state.patLogin ?: "unknown",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    OutlinedButton(onClick = onClearPat) {
                        Text(stringResource(R.string.pat_remove))
                    }
                } else {
                    OutlinedTextField(
                        value = pat,
                        onValueChange = { pat = it },
                        label = { Text("ghp_… or github_pat_…") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            onSavePat(pat)
                            pat = ""
                        },
                        enabled = pat.isNotBlank() && !state.busy,
                    ) {
                        Text(stringResource(R.string.pat_save))
                    }
                }
                state.patVerifyMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }

        // ---------- Signing keystores ----------
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FactoryCardHeader(
                    icon = Icons.Filled.Key,
                    title = stringResource(R.string.keys_title),
                    subtitle = stringResource(R.string.keys_sub, state.keystores.size),
                )
                Text(
                    text = stringResource(R.string.keys_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (state.keystores.isEmpty()) {
                    Text(
                        text = stringResource(R.string.keys_empty),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    state.keystores.forEach { record ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = record.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            Text(
                                text = stringResource(
                                    R.string.keys_record_meta,
                                    record.packageName,
                                    record.alias,
                                    formatDate(record.createdAt) + if (record.hasStoredPassword) {
                                        " • " + stringResource(R.string.keys_ready)
                                    } else {
                                        " • " + stringResource(R.string.keys_needs_password)
                                    },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { keystorePicker.launch("*/*") }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(stringResource(R.string.action_import))
                    }
                }

                // Manual creation for a future package
                OutlinedTextField(
                    value = newPackageName,
                    onValueChange = { newPackageName = it },
                    label = { Text(stringResource(R.string.label_package_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.keys_generate_desc)) },
                )
                OutlinedButton(
                    onClick = {
                        onCreateKeystore(newPackageName)
                        newPackageName = ""
                    },
                    enabled = newPackageName.isNotBlank() && !state.busy,
                ) {
                    Text(stringResource(R.string.keys_generate))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

private fun formatDate(timestamp: Long): String =
    synchronized(dateFormat) { dateFormat.format(Date(timestamp)) }
