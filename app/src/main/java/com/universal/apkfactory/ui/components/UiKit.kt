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
package com.universal.apkfactory.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.universal.apkfactory.ui.theme.FactoryGradients
import com.universal.apkfactory.ui.theme.FactoryTheme

/**
 * ============================================================================
 * UiKit — the factory's shared visual vocabulary
 * ============================================================================
 *
 * Every screen draws its cards, headers, pills and call-to-action buttons
 * from this kit so the whole app shares one coherent, designed language:
 * icon badges in tinted rounded squares, pill-shaped statuses and one
 * signature amber gradient for primary actions.
 */

/* ---------------------------------------------------------------------- */
/* Icon badge                                                             */
/* ---------------------------------------------------------------------- */

/**
 * The signature visual atom: a rounded-square badge with a tinted icon.
 * Used for card headers, list rows and step markers.
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    tint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Int = 40,
) {
    Box(
        modifier = modifier
            .size(size.dp)
            .background(container, RoundedCornerShape((size / 4).dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size((size * 0.55).dp),
        )
    }
}

/* ---------------------------------------------------------------------- */
/* Card header                                                            */
/* ---------------------------------------------------------------------- */

/** Standard card header: icon badge + title (+ optional subtitle). */
@Composable
fun FactoryCardHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    badgeContainer: Color = MaterialTheme.colorScheme.primaryContainer,
    badgeTint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(
            icon = icon,
            container = badgeContainer,
            tint = badgeTint,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

/* ---------------------------------------------------------------------- */
/* Status pill                                                            */
/* ---------------------------------------------------------------------- */

private enum class PillTone { OK, WARN, BAD, NEUTRAL }

/**
 * Compact status pill: colored dot + uppercase label, for readiness
 * indicators (toolchain, token, runtime …).
 */
@Composable
fun StatusPill(
    text: String,
    ok: Boolean,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
) {
    val tone = when {
        ok -> PillTone.OK
        warning -> PillTone.WARN
        else -> PillTone.BAD
    }
    val ext = FactoryTheme.ext
    val (dot, container, label) = when (tone) {
        PillTone.OK -> Triple(ext.success, ext.successContainer, ext.onSuccessContainer)
        PillTone.WARN -> Triple(ext.warning, ext.warningContainer, ext.onWarningContainer)
        PillTone.BAD -> Triple(
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
        PillTone.NEUTRAL -> Triple(
            MaterialTheme.colorScheme.outline,
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .background(dot, CircleShape),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = label,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/* ---------------------------------------------------------------------- */
/* Gradient primary button                                                */
/* ---------------------------------------------------------------------- */

/**
 * The single loudest action on a screen: a full-width amber-gradient
 * button with dark text (readable on both themes).
 */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val brush = if (enabled) FactoryGradients.Amber else Brush.linearGradient(
        listOf(Color(0xFF6E6E68), Color(0xFF57554F))
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(brush, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = if (enabled) Color(0xFF241A00) else Color(0xFFEDEBE4),
        ),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Amber-gradient background modifier helper for custom containers. */
fun Modifier.amberGradient(): Modifier = background(
    FactoryGradients.Amber,
    RoundedCornerShape(16.dp),
)

/* ---------------------------------------------------------------------- */
/* Hero header                                                            */
/* ---------------------------------------------------------------------- */

/**
 * The home screen brand block: gradient logo badge, product name and
 * tagline.
 */
@Composable
fun HeroHeader(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(FactoryGradients.Amber, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "⚡",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Column {
            Text(
                text = "Universal APK Factory",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = androidx.compose.ui.res.stringResource(
                    com.universal.apkfactory.R.string.tagline
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ---------------------------------------------------------------------- */
/* Numbered step row                                                      */
/* ---------------------------------------------------------------------- */

/** A numbered onboarding step (used in the home "how it works" card). */
@Composable
fun StepRow(
    number: Int,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    MaterialTheme.colorScheme.secondaryContainer,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ---------------------------------------------------------------------- */
/* Info banner                                                            */
/* ---------------------------------------------------------------------- */

/** Soft tinted banner for contextual hints (info / success / warning). */
@Composable
fun InfoBanner(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: BannerTone = BannerTone.INFO,
) {
    val ext = FactoryTheme.ext
    val (container, onContainer) = when (tone) {
        BannerTone.INFO -> ext.infoContainer to ext.onInfoContainer
        BannerTone.SUCCESS -> ext.successContainer to ext.onSuccessContainer
        BannerTone.WARNING -> ext.warningContainer to ext.onWarningContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = onContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer,
            )
        }
    }
}

enum class BannerTone { INFO, SUCCESS, WARNING }

/* ---------------------------------------------------------------------- */
/* Monospace evidence box                                                 */
/* ---------------------------------------------------------------------- */

/** Dark, terminal-like box for detection signals / evidence lists. */
@Composable
fun EvidenceBox(
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0B0D10),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            lines.take(8).forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFB7C2D0),
                )
            }
            if (lines.size > 8) {
                Text(
                    text = "… ${lines.size - 8} more",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF5C6672),
                )
            }
        }
    }
}
