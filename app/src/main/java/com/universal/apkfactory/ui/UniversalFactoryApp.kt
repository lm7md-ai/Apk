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
package com.universal.apkfactory.ui

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.universal.apkfactory.MainViewModel
import com.universal.apkfactory.ui.screens.BuildScreen
import com.universal.apkfactory.ui.theme.FactoryGradients
import com.universal.apkfactory.ui.screens.ConfigScreen
import com.universal.apkfactory.ui.screens.HomeScreen
import com.universal.apkfactory.ui.screens.KeystoreScreen
import java.io.File

/**
 * Part 5 — root composable: navigation shell (Scaffold + TopAppBar),
 * result sharing and the global error snackbar.
 *
 * Navigation is a plain `when` over [MainViewModel.Screen] — a deliberate
 * choice for a linear 4-screen flow (no back stack, no nav-graph ceremony).
 * Document pickers live inside the screens that launch them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UniversalFactoryApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        val message = state.error
        if (!message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Long,
            )
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    if (state.screen == MainViewModel.Screen.HOME) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .background(
                                        FactoryGradients.Amber,
                                        RoundedCornerShape(9.dp),
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("⚡", style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                text = screenTitle(state.screen),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    } else {
                        Text(
                            text = screenTitle(state.screen),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
                navigationIcon = {
                    if (state.screen != MainViewModel.Screen.HOME) {
                        IconButton(onClick = {
                            viewModel.navigate(backTarget(state.screen))
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = androidx.compose.ui.res.stringResource(
                                    com.universal.apkfactory.R.string.action_back
                                ),
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val screenModifier = Modifier.padding(padding)

        AnimatedContent(
            targetState = state.screen,
            transitionSpec = {
                (fadeIn() + slideInHorizontally { it / 8 }) togetherWith
                    (fadeOut() + slideOutHorizontally { -it / 8 })
            },
            label = "screen",
        ) { screen ->
            when (screen) {
            MainViewModel.Screen.HOME -> HomeScreen(
                state = state,
                onSubmitGithubUrl = { viewModel.ingestGithub(it) },
                onSubmitUri = { uri, origin -> viewModel.ingestUri(uri, origin) },
                onIngestFolder = { viewModel.ingestFolder(it) },
                onOpenKeystores = { viewModel.openKeystores() },
                onPrepareToolchain = { viewModel.prepareToolchain() },
                modifier = screenModifier,
            )

            MainViewModel.Screen.CONFIG -> {
                val project = state.project
                val config = state.config
                if (project != null && config != null) {
                    ConfigScreen(
                        state = state,
                        project = project,
                        config = config,
                        onUpdateConfig = { viewModel.updateConfig(it) },
                        onTogglePermission = { viewModel.togglePermission(it) },
                        onSetIcon = { viewModel.setIcon(it) },
                        onToggleNativeOnDevice = { viewModel.setNativeOnDevice(it) },
                        onStartBuild = { viewModel.startBuild() },
                        onOpenKeystores = { viewModel.openKeystores() },
                        onInstallPythonRuntime = { url, sha ->
                            viewModel.installPythonRuntime(url, sha)
                        },
                        onClearPythonRuntime = { viewModel.clearPythonRuntime() },
                        modifier = screenModifier,
                    )
                }
            }

            MainViewModel.Screen.BUILD -> BuildScreen(
                state = state,
                onInstall = { viewModel.installResult() },
                onShare = { state.resultApk?.let { apk -> shareApk(context, apk) } },
                onNewBuild = { viewModel.reset() },
                onBackToConfig = { viewModel.navigate(MainViewModel.Screen.CONFIG) },
                onCancelNative = { viewModel.cancelNativeBuild() },
                modifier = screenModifier,
            )

            MainViewModel.Screen.KEYSTORES -> KeystoreScreen(
                state = state,
                onSavePat = { viewModel.savePat(it) },
                onClearPat = { viewModel.clearPat() },
                onImportKeystore = { uri, password ->
                    viewModel.importKeystore(uri, password)
                },
                onCreateKeystore = { viewModel.createKeystoreFor(it) },
                modifier = screenModifier,
            )
        }
        }
    }
}

/* ---------------------------------------------------------------------- */
/* Helpers                                                                */
/* ---------------------------------------------------------------------- */

@androidx.compose.runtime.Composable
private fun screenTitle(screen: MainViewModel.Screen): String = when (screen) {
    MainViewModel.Screen.HOME -> androidx.compose.ui.res.stringResource(
        com.universal.apkfactory.R.string.screen_home
    )
    MainViewModel.Screen.CONFIG -> androidx.compose.ui.res.stringResource(
        com.universal.apkfactory.R.string.screen_config
    )
    MainViewModel.Screen.BUILD -> androidx.compose.ui.res.stringResource(
        com.universal.apkfactory.R.string.screen_build
    )
    MainViewModel.Screen.KEYSTORES -> androidx.compose.ui.res.stringResource(
        com.universal.apkfactory.R.string.screen_keystores
    )
}

private fun backTarget(screen: MainViewModel.Screen): MainViewModel.Screen = when (screen) {
    MainViewModel.Screen.CONFIG -> MainViewModel.Screen.HOME
    MainViewModel.Screen.BUILD -> MainViewModel.Screen.CONFIG
    MainViewModel.Screen.KEYSTORES -> MainViewModel.Screen.HOME
    MainViewModel.Screen.HOME -> MainViewModel.Screen.HOME
}

/** Shares the signed APK through the FileProvider (`workspace/out`). */
private fun shareApk(context: Context, apk: File) {
    val uri = runCatching {
        FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            apk,
        )
    }.getOrNull() ?: return

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/vnd.android.package-archive"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "Share ${apk.name}").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
}
