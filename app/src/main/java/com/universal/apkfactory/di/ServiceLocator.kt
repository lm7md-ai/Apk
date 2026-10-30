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
package com.universal.apkfactory.di

import android.content.Context
import com.universal.apkfactory.ApkFactoryApplication
import com.universal.apkfactory.core.detector.SmartProjectDetector
import com.universal.apkfactory.core.ingestion.IngestionManager
import com.universal.apkfactory.core.signer.ApkAlignerAndSigner
import com.universal.apkfactory.core.signer.KeyStoreGenerator
import com.universal.apkfactory.engine.cloud.GitHubCloudBuilder
import com.universal.apkfactory.engine.gradle.ToolchainManager
import com.universal.apkfactory.pipeline.BuildOrchestrator

/**
 * Part 5 — dependency graph.
 *
 * A deliberate single-object service locator instead of a DI framework:
 * the graph is tiny, fully constructor-injectable and needs no scope
 * wizardry. Engines are stateless or own their state (keystores, secure
 * prefs, toolchain dirs), so a single instance per process is correct and
 * keeps the ViewModel constructor trivial (important for
 * `viewModelScope`-based collection across configuration changes).
 */
object ServiceLocator {

    @Volatile
    private var holder: Graph? = null

    private val initLock = Any()

    /** Lazily created, process-wide engine instances. */
    class Graph private constructor(val appContext: Context) {

        val application: ApkFactoryApplication
            get() = appContext as ApkFactoryApplication

        val okHttpClient get() = application.okHttpClient

        val ingestionManager: IngestionManager by lazy {
            IngestionManager(appContext)
        }

        /** SmartProjectDetector is a stateless singleton object. */
        val projectDetector: SmartProjectDetector get() = SmartProjectDetector

        val keyStoreGenerator: KeyStoreGenerator by lazy {
            KeyStoreGenerator(appContext)
        }

        val alignerAndSigner: ApkAlignerAndSigner by lazy {
            ApkAlignerAndSigner(appContext)
        }

        val toolchainManager: ToolchainManager by lazy {
            ToolchainManager(appContext, okHttpClient)
        }

        val cloudBuilder: GitHubCloudBuilder by lazy {
            GitHubCloudBuilder(appContext, okHttpClient)
        }

        val buildOrchestrator: BuildOrchestrator by lazy {
            BuildOrchestrator(appContext)
        }

        companion object {
            fun create(appContext: Context): Graph = Graph(appContext)
        }
    }

    fun graph(context: Context): Graph {
        holder?.let { return it }
        synchronized(initLock) {
            holder?.let { return it }
            val graph = Graph.create(context.applicationContext)
            holder = graph
            return graph
        }
    }
}
