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

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import java.util.Locale

/**
 * In-app language selection ("system" / "en" / "ar"), persisted in
 * SharedPreferences and applied by wrapping the Activity base context
 * (see MainActivity.attachBaseUrlContext). RTL mirroring follows the
 * chosen locale automatically (supportsRtl=true).
 */
object AppLocale {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val ARABIC = "ar"

    private const val PREFS = "factory_prefs"
    private const val KEY = "app_language"

    fun current(context: Context): String =
        prefs(context).getString(KEY, SYSTEM) ?: SYSTEM

    fun set(context: Context, tag: String) {
        prefs(context).edit().putString(KEY, tag).apply()
    }

    /** Wraps the context with the chosen locale (no-op for "system"). */
    fun wrap(context: Context): Context {
        val tag = current(context)
        if (tag == SYSTEM) return context
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    /** Finds the hosting Activity from any nested Compose context. */
    fun findActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
