package com.antigravity.client.ui.code

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.client.AntigravityApp
import com.antigravity.client.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeViewerScreen(
    filePath: String,
    onQuoteToChat: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val fileRepo = AntigravityApp.instance.fileRepository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var content by remember { mutableStateOf<String?>(null) }
    var isBinary by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(filePath) {
        isLoading = true
        errorMessage = null
        try {
            val resp = fileRepo.getFileContent(filePath)
            if (resp.isBinary) {
                isBinary = true
            } else {
                content = resp.content ?: ""
            }
        } catch (e: Exception) {
            errorMessage = "Failed to load file: ${e.localizedMessage ?: e.message}"
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = filePath.substringAfterLast('/'),
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = filePath,
                            color = TextMuted,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    if (content != null) {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("File content", content))
                            Toast.makeText(context, "Copied file content", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(AppIcons.ContentCopy, contentDescription = "Copy", tint = TextPrimary)
                        }
                        IconButton(onClick = {
                            content?.let { onQuoteToChat(it) }
                        }) {
                            Icon(AppIcons.FormatQuote, contentDescription = "Ask Agent", tint = PrimaryBlue)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = CodeBg
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    color = PrimaryBlue,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (errorMessage != null) {
                Surface(
                    color = ErrorRed.copy(alpha = 0.2f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.Center)
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = ErrorRed,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else if (isBinary) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = AppIcons.HideImage,
                        contentDescription = null,
                        tint = TextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Binary file content omitted",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
            } else {
                val lines = remember(content) { content?.lines() ?: emptyList() }
                val horizontalScrollState = rememberScrollState()

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(horizontalScrollState)
                        .padding(vertical = 8.dp)
                ) {
                    itemsIndexed(lines) { index, line ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = (index + 1).toString().padStart(4),
                                color = LineNumberColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.width(36.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = line,
                                color = TextPrimary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
