package com.antigravity.client.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.antigravity.client.ui.theme.*

private sealed class MarkdownElement {
    data class Header(val level: Int, val text: String) : MarkdownElement()
    data class CodeBlock(val language: String, val code: String) : MarkdownElement()
    data class Image(val alt: String, val url: String) : MarkdownElement()
    data class BulletItem(val text: String) : MarkdownElement()
    data class Paragraph(val text: String) : MarkdownElement()
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = TextPrimary,
    onLinkClick: ((String) -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null,
    resolveServerUrl: ((String) -> String)? = null
) {
    val context = LocalContext.current
    val elements = remember(markdown) { parseMarkdownElements(markdown) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        elements.forEach { element ->
            when (element) {
                is MarkdownElement.Header -> {
                    val fontSize = when (element.level) {
                        1 -> 18.sp
                        2 -> 16.sp
                        else -> 14.sp
                    }
                    Text(
                        text = element.text,
                        color = textColor,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        lineHeight = (fontSize.value + 6).sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }

                is MarkdownElement.CodeBlock -> {
                    CodeBlockCard(
                        language = element.language,
                        code = element.code
                    )
                }

                is MarkdownElement.Image -> {
                    val finalUrl = resolveServerUrl?.invoke(element.url) ?: element.url
                    InlineImageCard(
                        alt = element.alt,
                        url = finalUrl,
                        onClick = { onImageClick?.invoke(finalUrl) }
                    )
                }

                is MarkdownElement.BulletItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            color = PrimaryBlue,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp, start = 4.dp)
                        )
                        RichInlineText(
                            text = element.text,
                            textColor = textColor,
                            onLinkClick = { url ->
                                handleLinkClick(context, url, onLinkClick)
                            }
                        )
                    }
                }

                is MarkdownElement.Paragraph -> {
                    RichInlineText(
                        text = element.text,
                        textColor = textColor,
                        onLinkClick = { url ->
                            handleLinkClick(context, url, onLinkClick)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RichInlineText(
    text: String,
    textColor: Color,
    onLinkClick: (String) -> Unit
) {
    val annotatedString = remember(text, textColor) {
        buildAnnotatedMarkdown(text, textColor)
    }

    ClickableText(
        text = annotatedString,
        style = TextStyle(
            color = textColor,
            fontSize = 14.sp,
            lineHeight = 21.sp
        ),
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    onLinkClick(annotation.item)
                }
        }
    )
}

@Composable
private fun InlineImageCard(
    alt: String,
    url: String,
    onClick: () -> Unit
) {
    Surface(
        color = DarkSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 280.dp),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(url)
                        .crossfade(true)
                        .build(),
                    contentDescription = alt,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                )
            }
            if (alt.isNotBlank() && alt != "image") {
                Text(
                    text = alt,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun CodeBlockCard(
    language: String,
    code: String
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }

    Surface(
        color = CodeBg,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Header bar: Language tag + Copy button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkSurfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.ifBlank { "code" },
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace
                )
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(code))
                        copied = true
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Icon(
                        imageVector = if (copied) AppIcons.Check else AppIcons.ContentCopy,
                        contentDescription = "Copy code",
                        tint = if (copied) AccentGreen else PrimaryBlue,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (copied) "Copied!" else "Copy",
                        color = if (copied) AccentGreen else PrimaryBlue,
                        fontSize = 11.sp
                    )
                }
            }

            // Code content container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    text = code,
                    color = TextPrimary,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

private fun handleLinkClick(context: Context, url: String, customHandler: ((String) -> Unit)?) {
    if (customHandler != null) {
        customHandler(url)
        return
    }
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open link: $url", Toast.LENGTH_SHORT).show()
    }
}

private fun parseMarkdownElements(markdown: String): List<MarkdownElement> {
    val elements = mutableListOf<MarkdownElement>()
    val lines = markdown.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // 1. Code blocks: ```[lang] ... ```
        if (line.trimStart().startsWith("```")) {
            val lang = line.trimStart().removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            elements.add(MarkdownElement.CodeBlock(lang, codeLines.joinToString("\n")))
            i++
            continue
        }

        // 2. Images: ![alt](url)
        val imageRegex = Regex("""^!\[(.*?)\]\((.*?)\)$""")
        val imageMatch = imageRegex.find(line.trim())
        if (imageMatch != null) {
            elements.add(MarkdownElement.Image(imageMatch.groupValues[1], imageMatch.groupValues[2]))
            i++
            continue
        }

        // 3. Headers: #, ##, ###
        if (line.startsWith("#")) {
            val level = line.takeWhile { it == '#' }.length
            val headerText = line.drop(level).trim()
            elements.add(MarkdownElement.Header(level, headerText))
            i++
            continue
        }

        // 4. Bullet lists: - , * 
        if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ")) {
            val bulletText = line.trimStart().drop(2).trim()
            elements.add(MarkdownElement.BulletItem(bulletText))
            i++
            continue
        }

        // 5. Blank lines
        if (line.isBlank()) {
            i++
            continue
        }

        // 6. Regular paragraphs: combine consecutive non-empty lines
        val paragraphLines = mutableListOf<String>()
        while (i < lines.size &&
            lines[i].isNotBlank() &&
            !lines[i].trimStart().startsWith("```") &&
            !lines[i].startsWith("#") &&
            !lines[i].trimStart().startsWith("- ") &&
            !lines[i].trimStart().startsWith("* ") &&
            !imageRegex.matches(lines[i].trim())
        ) {
            paragraphLines.add(lines[i])
            i++
        }
        if (paragraphLines.isNotEmpty()) {
            elements.add(MarkdownElement.Paragraph(paragraphLines.joinToString("\n")))
        }
    }

    return elements
}

private fun buildAnnotatedMarkdown(raw: String, baseColor: Color): AnnotatedString {
    return buildAnnotatedString {
        // Tokenizer for: [label](url), `code`, **bold**, *italic*, bare URLs (https?://...)
        val pattern = Regex(
            """(\[(.*?)\]\((https?://[^\s)]+|file://[^\s)]+|/[^\s)]+)\))|(`([^`]+)`)|(\*\*([^*]+)\*\*)|(\*([^*]+)\*)|(https?://[^\s]+)"""
        )

        var lastIdx = 0
        pattern.findAll(raw).forEach { match ->
            val matchRange = match.range
            if (matchRange.first > lastIdx) {
                append(raw.substring(lastIdx, matchRange.first))
            }

            when {
                // [label](url)
                match.groups[1] != null -> {
                    val label = match.groups[2]?.value ?: ""
                    val url = match.groups[3]?.value ?: ""
                    val start = length
                    append(label)
                    addStyle(
                        SpanStyle(
                            color = PrimaryBlue,
                            fontWeight = FontWeight.Medium,
                            textDecoration = TextDecoration.Underline
                        ),
                        start,
                        length
                    )
                    addStringAnnotation(tag = "URL", annotation = url, start = start, end = length)
                }

                // `inline code`
                match.groups[4] != null -> {
                    val code = match.groups[5]?.value ?: ""
                    val start = length
                    append(" $code ")
                    addStyle(
                        SpanStyle(
                            color = WarningOrange,
                            background = CodeBg,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp
                        ),
                        start,
                        length
                    )
                }

                // **bold**
                match.groups[6] != null -> {
                    val boldText = match.groups[7]?.value ?: ""
                    val start = length
                    append(boldText)
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
                }

                // *italic*
                match.groups[8] != null -> {
                    val italicText = match.groups[9]?.value ?: ""
                    val start = length
                    append(italicText)
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, length)
                }

                // Bare URL
                match.groups[10] != null -> {
                    val url = match.groups[10]?.value ?: ""
                    val start = length
                    append(url)
                    addStyle(
                        SpanStyle(
                            color = PrimaryBlue,
                            textDecoration = TextDecoration.Underline
                        ),
                        start,
                        length
                    )
                    addStringAnnotation(tag = "URL", annotation = url, start = start, end = length)
                }
            }

            lastIdx = matchRange.last + 1
        }

        if (lastIdx < raw.length) {
            append(raw.substring(lastIdx))
        }
    }
}
