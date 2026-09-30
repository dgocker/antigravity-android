package com.antigravity.client.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.client.domain.model.ToolCall
import com.antigravity.client.ui.theme.*

@Composable
fun ToolCallCard(
    toolCall: ToolCall,
    result: String? = null,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var copiedResult by remember { mutableStateOf(false) }

    LaunchedEffect(copiedResult) {
        if (copiedResult) {
            kotlinx.coroutines.delay(2000)
            copiedResult = false
        }
    }

    val (icon, iconColor) = getToolIconAndColor(toolCall.name)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = toolCall.name,
                        tint = iconColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = toolCall.name,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                    // If target file or command is in args, show inline summary
                    val targetSummary = (toolCall.args["TargetFile"] as? String)
                        ?: (toolCall.args["AbsolutePath"] as? String)
                        ?: (toolCall.args["CommandLine"] as? String)
                        ?: (toolCall.args["query"] as? String)

                    if (!targetSummary.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val cleanSummary = if (targetSummary.contains('/')) {
                            targetSummary.substringAfterLast('/')
                        } else {
                            targetSummary
                        }
                        Text(
                            text = cleanSummary,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!result.isNullOrBlank()) {
                        Text(
                            text = "Done",
                            color = AccentGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    Icon(
                        imageVector = if (expanded) AppIcons.KeyboardArrowUp else AppIcons.KeyboardArrowDown,
                        contentDescription = "Toggle Tool",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CodeBg)
                        .padding(12.dp)
                ) {
                    // Parameters / Arguments
                    Text(
                        text = "Parameters",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (toolCall.args.isEmpty()) {
                        Text(
                            text = "No parameters",
                            color = TextMuted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        toolCall.args.forEach { (k, v) ->
                            Text(
                                text = "$k: $v",
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }

                    // Result output with Copy action
                    if (!result.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Execution Result",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(result))
                                    copiedResult = true
                                    Toast.makeText(context, "Result copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                modifier = Modifier.height(24.dp)
                            ) {
                                Icon(
                                    imageVector = if (copiedResult) AppIcons.Check else AppIcons.ContentCopy,
                                    contentDescription = "Copy result",
                                    tint = if (copiedResult) AccentGreen else PrimaryBlue,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (copiedResult) "Copied!" else "Copy",
                                    color = if (copiedResult) AccentGreen else PrimaryBlue,
                                    fontSize = 10.sp
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(DarkSurfaceVariant)
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp)
                        ) {
                            Text(
                                text = result.trim(),
                                color = TextSecondary,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun getToolIconAndColor(toolName: String): Pair<ImageVector, Color> {
    return when {
        toolName.contains("command", ignoreCase = true) -> AppIcons.Terminal to AccentGreen
        toolName.contains("view", ignoreCase = true) || toolName.contains("read", ignoreCase = true) -> AppIcons.Description to PrimaryBlue
        toolName.contains("write", ignoreCase = true) || toolName.contains("replace", ignoreCase = true) || toolName.contains("edit", ignoreCase = true) -> AppIcons.Edit to WarningOrange
        toolName.contains("search", ignoreCase = true) -> AppIcons.Search to PrimaryBlue
        toolName.contains("image", ignoreCase = true) -> AppIcons.Image to AccentPurple
        else -> AppIcons.Build to PrimaryBlue
    }
}
