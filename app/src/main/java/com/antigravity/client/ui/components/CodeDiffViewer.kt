package com.antigravity.client.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.client.domain.model.CodeDiff
import com.antigravity.client.ui.theme.*

@Composable
fun CodeDiffViewer(
    diff: CodeDiff,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = true
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val context = LocalContext.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = CodeBg),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DiffHeaderBg)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    val actionColor = when (diff.action.lowercase()) {
                        "create" -> SuccessGreen
                        "overwrite" -> WarningOrange
                        else -> PrimaryBlue
                    }
                    Surface(
                        color = actionColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = diff.action.uppercase(),
                            color = actionColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = diff.file.substringAfterLast('/'),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            val contentToCopy = diff.replacementContent ?: diff.targetContent ?: ""
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Diff Code", contentToCopy))
                            Toast.makeText(context, "Copied code", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.ContentCopy,
                            contentDescription = "Copy code",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Toggle diff",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Expanded Diff Content
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    if (!diff.instruction.isNullOrBlank()) {
                        Text(
                            text = diff.instruction,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    val scrollState = rememberScrollState()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(scrollState)
                    ) {
                        // Removed / target content
                        if (!diff.targetContent.isNullOrBlank()) {
                            val targetLines = diff.targetContent.lines()
                            targetLines.forEachIndexed { i, line ->
                                val lineNum = diff.startLine?.let { it + i }?.toString() ?: "-"
                                DiffLine(
                                    prefix = "-",
                                    lineNumber = lineNum,
                                    text = line,
                                    bgColor = DiffRemoveBg,
                                    textColor = DiffRemoveText
                                )
                            }
                        }

                        // Added / replacement content
                        if (!diff.replacementContent.isNullOrBlank()) {
                            val replacementLines = diff.replacementContent.lines()
                            replacementLines.forEachIndexed { i, line ->
                                val lineNum = diff.startLine?.let { it + i }?.toString() ?: "+"
                                DiffLine(
                                    prefix = "+",
                                    lineNumber = lineNum,
                                    text = line,
                                    bgColor = DiffAddBg,
                                    textColor = DiffAddText
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLine(
    prefix: String,
    lineNumber: String,
    text: String,
    bgColor: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(vertical = 1.dp)
    ) {
        Text(
            text = lineNumber.padStart(4),
            color = LineNumberColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.width(36.dp)
        )
        Text(
            text = "$prefix ",
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = text,
            color = textColor,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp
        )
    }
}
