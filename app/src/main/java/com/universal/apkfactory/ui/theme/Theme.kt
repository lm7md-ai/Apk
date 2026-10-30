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

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ============================================================================
 * Design system — "Amber Forge" v2
 * ============================================================================
 *
 * A comprehensive visual identity for the factory:
 *
 *  * **Palette** — industrial amber on deep graphite (dark, "the factory
 *    floor") and warm paper (light). Deliberately NOT dynamic-color based:
 *    the brand stays consistent on every device, which reads as a more
 *    designed, more premium product.
 *  * **Extended colors** — semantic success / warning / info tokens that
 *    Material 3 has no slot for, delivered through [LocalExtendedColors].
 *  * **Gradients** — signature brand brushes used by hero headers and
 *    primary actions ([FactoryGradients]).
 *  * **Dimens** — a single spacing scale so every screen breathes evenly.
 *  * **Shapes & typography** — generous radii and a confident type scale.
 */

/* ---------------------------------------------------------------------- */
/* Palette                                                                */
/* ---------------------------------------------------------------------- */

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFC24D),
    onPrimary = Color(0xFF241A00),
    primaryContainer = Color(0xFF5C4300),
    onPrimaryContainer = Color(0xFFFFE0A8),
    secondary = Color(0xFF54DAC4),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFFB2FFF2),
    tertiary = Color(0xFFAAB8FF),
    onTertiary = Color(0xFF14255C),
    tertiaryContainer = Color(0xFF2F3E7A),
    onTertiaryContainer = Color(0xFFDEE3FF),
    background = Color(0xFF0E1014),
    onBackground = Color(0xFFEDEBE4),
    surface = Color(0xFF16191F),
    onSurface = Color(0xFFEDEBE4),
    surfaceVariant = Color(0xFF1E222A),
    onSurfaceVariant = Color(0xFFC4C8D0),
    surfaceTint = Color(0xFFFFC24D),
    inverseSurface = Color(0xFFEDEBE4),
    inverseOnSurface = Color(0xFF2E313A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8E939E),
    outlineVariant = Color(0xFF3A3F49),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5B00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDF9E),
    onPrimaryContainer = Color(0xFF2B1D00),
    secondary = Color(0xFF00695E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF74F7EB),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF4858C9),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDEE3FF),
    onTertiaryContainer = Color(0xFF001159),
    background = Color(0xFFFAF7F0),
    onBackground = Color(0xFF1C1B16),
    surface = Color(0xFFFFFCF5),
    onSurface = Color(0xFF1C1B16),
    surfaceVariant = Color(0xFFEEE9DD),
    onSurfaceVariant = Color(0xFF48454E),
    surfaceTint = Color(0xFF8A5B00),
    inverseSurface = Color(0xFF31302A),
    inverseOnSurface = Color(0xFFF3F0E8),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF787571),
    outlineVariant = Color(0xFFC8C5BC),
)

/* ---------------------------------------------------------------------- */
/* Extended semantic colors                                               */
/* ---------------------------------------------------------------------- */

/** Semantic colors Material 3 has no dedicated slot for. */
class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
)

private val DarkExtended = ExtendedColors(
    success = Color(0xFF7ADB97),
    onSuccess = Color(0xFF003919),
    successContainer = Color(0xFF005224),
    onSuccessContainer = Color(0xFFB8F5B1),
    warning = Color(0xFFFFCB6B),
    onWarning = Color(0xFF3F2E00),
    warningContainer = Color(0xFF5C4300),
    onWarningContainer = Color(0xFFFFE0A8),
    info = Color(0xFFA0C8FF),
    onInfo = Color(0xFF003063),
    infoContainer = Color(0xFF1E4A80),
    onInfoContainer = Color(0xFFD6E7FF),
)

private val LightExtended = ExtendedColors(
    success = Color(0xFF1E7A45),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFA6F5B9),
    onSuccessContainer = Color(0xFF002110),
    warning = Color(0xFF8A5B00),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDF9E),
    onWarningContainer = Color(0xFF2B1D00),
    info = Color(0xFF2E5FA3),
    onInfo = Color(0xFFFFFFFF),
    infoContainer = Color(0xFFD6E7FF),
    onInfoContainer = Color(0xFF001A37),
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

/** Access the extended palette: `FactoryTheme.ext.success`. */
object FactoryTheme {
    val ext: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}

/* ---------------------------------------------------------------------- */
/* Signature gradients                                                    */
/* ---------------------------------------------------------------------- */

object FactoryGradients {
    /** Primary brand sweep — hero headers, primary actions. */
    val Amber = Brush.linearGradient(listOf(Color(0xFFFFC24D), Color(0xFFFF8F00)))

    /** Cool accent for cloud / sync surfaces. */
    val Teal = Brush.linearGradient(listOf(Color(0xFF54DAC4), Color(0xFF00897B)))

    /** Nightly backdrop for terminal-adjacent surfaces. */
    val Graphite = Brush.linearGradient(listOf(Color(0xFF16191F), Color(0xFF0E1014)))

    /** Error accent. */
    val Ember = Brush.linearGradient(listOf(Color(0xFFFF7B72), Color(0xFFC62828)))
}

/* ---------------------------------------------------------------------- */
/* Spacing scale                                                          */
/* ---------------------------------------------------------------------- */

object Dimens {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Horizontal padding for every scrollable screen. */
    val screenH = 20.dp
}

/* ---------------------------------------------------------------------- */
/* Typography & shapes                                                    */
/* ---------------------------------------------------------------------- */

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 0.3.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.3.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Monospace text style for the Live Terminal Log Viewer. */
val TerminalTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.Normal,
)

/* ---------------------------------------------------------------------- */
/* Theme                                                                  */
/* ---------------------------------------------------------------------- */

@Composable
fun UniversalFactoryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalExtendedColors provides if (darkTheme) DarkExtended else LightExtended,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
