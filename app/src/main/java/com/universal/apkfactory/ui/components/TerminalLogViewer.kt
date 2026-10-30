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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.universal.apkfactory.core.model.BuildLogLine
import com.universal.apkfactory.ui.theme.TerminalTextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Part 5 — Live Terminal Log Viewer.
 *
 * A scrolling, ANSI-terminal-styled monospace view of the build output.
 * Auto-scrolls while the user is pinned to the bottom and stops fighting
 * the user as soon as they scroll up to inspect a line.
 */
@Composable
fun TerminalLogViewer(
    lines: List<BuildLogLine>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val pinnedToBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= listState.layoutInfo.totalItemsCount - 2
        }
    }

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && pinnedToBottom) {
            listState.animateScrollToItem(lines.size - 1)
        }
    }

    val terminalBackground = Color(0xFF0B0D10)
    val terminalBorder = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(320.dp),
        shape = RoundedCornerShape(12.dp),
        color = terminalBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, terminalBorder),
    ) {
        if (lines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Text(
                    text = "waiting for output…",
                    style = TerminalTextStyle,
                    color = Color(0xFF5C6672),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 12.dp, vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                items(lines) { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = formatTimestamp(line.timestamp),
                            style = TerminalTextStyle,
                            color = Color(0xFF5C6672),
                        )
                        Text(
                            text = line.message,
                            style = TerminalTextStyle,
                            color = colorForLevel(line.level),
                        )
                    }
                }
            }
        }
    }
}

private val timestampFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun formatTimestamp(timestamp: Long): String =
    synchronized(timestampFormat) { timestampFormat.format(Date(timestamp)) }

private fun colorForLevel(level: BuildLogLine.Level): Color = when (level) {
    BuildLogLine.Level.DEBUG -> Color(0xFF8BE9FD)
    BuildLogLine.Level.INFO -> Color(0xFFD6E2F0)
    BuildLogLine.Level.WARN -> Color(0xFFFFCB6B)
    BuildLogLine.Level.ERROR -> Color(0xFFFF7B72)
}
