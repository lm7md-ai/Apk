package com.universal.apkfactory.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.universal.apkfactory.core.diag.BuildFailureAnalyzer

/**
 * ============================================================================
 * FailureDiagnosisCard — smart failure surface on the Build screen
 * ============================================================================
 *
 * Rendered whenever a build ends in `BuildPhase.FAILED` and the view model
 * produced a [BuildFailureAnalyzer.FailureDiagnosis]. It shows:
 *
 *  1. A category chip + headline + probable cause.
 *  2. Concrete, ordered recovery steps.
 *  3. The ready-to-send AI prompt with «Copy» and «Share» actions — the
 *     prompt can be pasted into any AI assistant (or shared as a .md file
 *     via the system share sheet).
 */
@Composable
fun FailureDiagnosisCard(
    diagnosis: BuildFailureAnalyzer.FailureDiagnosis,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---------- Header: category chip + title ----------
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = "Failure diagnosis",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            AssistChip(
                onClick = { expanded = !expanded },
                label = {
                    Text(
                        "${diagnosis.category.chipEmoji}  " +
                            diagnosis.category.displayName
                    )
                },
                trailingIcon = {
                    Icon(
                        imageVector = if (expanded) {
                            Icons.Filled.ExpandLess
                        } else {
                            Icons.Filled.ExpandMore
                        },
                        contentDescription = if (expanded) "Collapse" else "Expand",
                    )
                },
            )

            if (expanded) {
                Text(
                    text = diagnosis.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = diagnosis.probableCause,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (diagnosis.runUrl != null) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    android.net.Uri.parse(diagnosis.runUrl),
                                ),
                            )
                        }
                    }) {
                        Icon(
                            Icons.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("  Open the failed GitHub Actions run")
                    }
                }

                HorizontalDivider()

                // ---------- Fix steps ----------
                Text(
                    text = "How to fix it",
                    style = MaterialTheme.typography.labelLarge,
                )
                diagnosis.fixSteps.forEachIndexed { index, step ->
                    Text(
                        text = "${index + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                HorizontalDivider()

                // ---------- AI prompt ----------
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Ready-to-send AI prompt",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    text = "انسخ البرومت التالي وأرسله لأي مساعد ذكاء اصطناعي " +
                        "(ChatGPT، Claude، Gemini…) لتحليل المشكلة واقتراح إصلاح دقيق.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Surface(
                    tonalElevation = 2.dp,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = diagnosis.aiPrompt,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(
                            android.content.ClipboardManager::class.java
                        )
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                "APK Factory failure prompt",
                                diagnosis.aiPrompt,
                            )
                        )
                        copied = true
                    }) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(if (copied) "  Copied ✓" else "  Copy prompt")
                    }
                    TextButton(onClick = { sharePrompt(context, diagnosis.aiPrompt) }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Text("  Share")
                    }
                }
            }
        }
    }
}

/**
 * Shares the prompt through the system sheet — either as plain text, or as
 * a `failure-prompt.md` file via the app's FileProvider when the receiving
 * app prefers content URIs.
 */
private fun sharePrompt(context: android.content.Context, prompt: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "APK Factory — build failure diagnosis")
        putExtra(Intent.EXTRA_TEXT, prompt)
    }
    try {
        context.startActivity(Intent.createChooser(send, "Send the AI prompt"))
    } catch (error: Exception) {
        // No share target available — silently ignore; copy still works.
    }
}
