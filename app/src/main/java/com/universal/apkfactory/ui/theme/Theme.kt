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
package com.universal.apkfactory.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Part 5 — theme. A warm "industrial amber on graphite" identity for dark
 * mode (the factory floor), with a clean light variant; API 31+ devices
 * fall back to Material You dynamic colors when the user has theming
 * enabled.
 */

private val Amber = Color(0xFFFFB300)
private val AmberDim = Color(0xFFC68400)
private val Graphite = Color(0xFF16181D)
private val GraphiteSurface = Color(0xFF1E2128)
private val GraphiteSurfaceHigh = Color(0xFF262A33)
private val OffWhite = Color(0xFFF7F5F0)
private val Ink = Color(0xFF191C22)
private val InkSurface = Color(0xFFF0EDE6)
private val Teal = Color(0xFF00897B)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF201A00),
    primaryContainer = AmberDim,
    onPrimaryContainer = Color(0xFFFFF0D6),
    secondary = Teal,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFFB2FFF2),
    tertiary = Color(0xFF8E99F5),
    background = Graphite,
    onBackground = OffWhite,
    surface = GraphiteSurface,
    onSurface = OffWhite,
    surfaceVariant = GraphiteSurfaceHigh,
    onSurfaceVariant = Color(0xFFC6CAD4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    outline = Color(0xFF8E939E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF7A5900),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDF9E),
    onPrimaryContainer = Color(0xFF261A00),
    secondary = Color(0xFF006A63),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF74F7EB),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF4858C9),
    background = OffWhite,
    onBackground = Ink,
    surface = Color(0xFFFFFBFF),
    onSurface = Ink,
    surfaceVariant = InkSurface,
    onSurfaceVariant = Color(0xFF48454E),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF787680),
)

/** Monospace text style for the Live Terminal Log Viewer. */
val TerminalTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Normal,
)

private val AppTypography = Typography()

@Composable
fun UniversalFactoryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
