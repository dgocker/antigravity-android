package com.antigravity.client.ui.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antigravity.client.domain.model.Conversation
import com.antigravity.client.ui.components.ConnectionBadge
import com.antigravity.client.ui.components.RunStatusBadge
import com.antigravity.client.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(
    viewModel: ChatsViewModel,
    onOpenChat: (String) -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val availableModels by viewModel.availableModels.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    val isCreatingChat by viewModel.isCreatingChat.collectAsStateWithLifecycle()

    var showNewChatDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Chats", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(8.dp))
                        ConnectionBadge(status = connectionStatus)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextPrimary)
                    }
                    IconButton(onClick = onNavigateToFiles) {
                        Icon(AppIcons.Folder, contentDescription = "Files", tint = TextPrimary)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface,
                    titleContentColor = TextPrimary
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewChatDialog = true },
                containerColor = PrimaryBlue,
                contentColor = TextPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "New Chat")
            }
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.searchQuery.value = it },
                placeholder = { Text("Search chats...", color = TextMuted) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextMuted) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = TextMuted)
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryBlue,
                    unfocusedBorderColor = DarkSurfaceVariant,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface
                ),
                singleLine = true
            )

            // Error banner if any
            actionError?.let { err ->
                Surface(
                    color = ErrorRed.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = err, color = ErrorRed, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.clearActionError() }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = ErrorRed)
                        }
                    }
                }
            }

            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = AppIcons.ChatBubbleOutline,
                            contentDescription = null,
                            tint = TextMuted,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isEmpty()) "No conversations yet" else "No matching chats",
                            color = TextSecondary,
                            fontSize = 15.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(conversations, key = { it.id }) { chat ->
                        ChatItemCard(
                            conversation = chat,
                            onClick = { onOpenChat(chat.id) }
                        )
                    }
                }
            }
        }
    }

    if (showNewChatDialog) {
        NewChatDialog(
            defaultWorkspace = viewModel.tokenStore.defaultWorkspace,
            models = availableModels,
            isCreating = isCreatingChat,
            errorMessage = actionError,
            onDismiss = {
                if (!isCreatingChat) {
                    showNewChatDialog = false
                    viewModel.clearActionError()
                }
            },
            onConfirm = { workspace, message, model, effort, mode ->
                viewModel.createChat(workspace, message, model, effort, mode) { newChatId ->
                    showNewChatDialog = false
                    onOpenChat(newChatId)
                }
            }
        )
    }
}

@Composable
private fun ChatItemCard(
    conversation: Conversation,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.title.ifBlank { "Untitled Conversation" },
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                RunStatusBadge(status = conversation.status)
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = conversation.preview.ifBlank { "No messages" },
                color = TextSecondary,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.workspace.substringAfterLast('/'),
                    color = TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1
                )
                Text(
                    text = "${conversation.stepCount} steps • ${conversation.lastModified.take(16).replace('T', ' ')}",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewChatDialog(
    defaultWorkspace: String,
    models: List<com.antigravity.client.domain.model.ModelOption>,
    isCreating: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (workspace: String, message: String, model: String?, effort: String?, mode: String?) -> Unit
) {
    var workspace by remember { mutableStateOf(defaultWorkspace) }
    var message by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf(models.firstOrNull()?.id ?: "gemini-3.8-flash-high") }
    var selectedEffort by remember { mutableStateOf("high") }
    var selectedMode by remember { mutableStateOf("accept-edits") }

    AlertDialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        title = { Text("Start New Conversation", color = TextPrimary) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = workspace,
                    onValueChange = { workspace = it },
                    label = { Text("Workspace Path") },
                    singleLine = true,
                    enabled = !isCreating,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Initial Prompt / Goal") },
                    minLines = 3,
                    maxLines = 5,
                    enabled = !isCreating,
                    modifier = Modifier.fillMaxWidth()
                )

                // Effort selection
                Text("Reasoning Effort:", color = TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("low", "medium", "high").forEach { effort ->
                        FilterChip(
                            selected = selectedEffort == effort,
                            onClick = { if (!isCreating) selectedEffort = effort },
                            enabled = !isCreating,
                            label = { Text(effort.replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }

                // Mode selection
                Text("Agent Mode:", color = TextSecondary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("plan", "accept-edits").forEach { mode ->
                        FilterChip(
                            selected = selectedMode == mode,
                            onClick = { if (!isCreating) selectedMode = mode },
                            enabled = !isCreating,
                            label = { Text(if (mode == "plan") "Plan" else "Accept Edits") }
                        )
                    }
                }

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFEF5350),
                        fontSize = 13.sp
                    )
                }

                if (isCreating) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = PrimaryBlue
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Initializing Antigravity session...",
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (message.isNotBlank() && !isCreating) {
                        onConfirm(workspace, message, selectedModel, selectedEffort, selectedMode)
                    }
                },
                enabled = message.isNotBlank() && !isCreating,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Starting...")
                } else {
                    Text("Start")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isCreating
            ) {
                Text("Cancel", color = if (!isCreating) TextSecondary else TextMuted)
            }
        },
        containerColor = DarkSurface
    )
}
