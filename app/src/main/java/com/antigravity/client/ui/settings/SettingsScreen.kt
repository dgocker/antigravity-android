package com.antigravity.client.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.antigravity.client.ui.components.ConnectionBadge
import com.antigravity.client.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onDisconnected: () -> Unit
) {
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val deviceTokens by viewModel.deviceTokens.collectAsStateWithLifecycle()
    val newGeneratedToken by viewModel.newGeneratedToken.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showCreateTokenDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Error banner
            errorMessage?.let { err ->
                Surface(
                    color = ErrorRed.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = err, color = ErrorRed, modifier = Modifier.padding(12.dp))
                }
            }

            // Connection Section
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Connection", fontWeight = FontWeight.Bold, color = TextPrimary)
                        ConnectionBadge(status = connectionStatus)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Server URL", color = TextSecondary, fontSize = 12.sp)
                    Text(
                        text = viewModel.tokenStore.serverUrl,
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Active Token", color = TextSecondary, fontSize = 12.sp)
                    val raw = viewModel.tokenStore.getToken() ?: ""
                    val masked = if (raw.length > 8) raw.take(4) + "••••••••" + raw.takeLast(4) else "••••••••"
                    Text(text = masked, color = TextMuted, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Device Tokens Management
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Device Tokens", fontWeight = FontWeight.Bold, color = TextPrimary)
                        TextButton(onClick = { showCreateTokenDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("New Token")
                        }
                    }

                    if (deviceTokens.isEmpty()) {
                        Text("No other device tokens found.", color = TextMuted, fontSize = 13.sp)
                    } else {
                        deviceTokens.forEach { t ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(t.deviceName, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = if (t.revokedAt != null) "Revoked" else "Active • ${t.createdAt.take(10)}",
                                        color = if (t.revokedAt != null) ErrorRed else SuccessGreen,
                                        fontSize = 11.sp
                                    )
                                }
                                if (t.revokedAt == null) {
                                    IconButton(onClick = { viewModel.revokeToken(t.id) }) {
                                        Icon(AppIcons.DeleteOutline, contentDescription = "Revoke", tint = ErrorRed)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Agent Defaults
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Agent Preferences", fontWeight = FontWeight.Bold, color = TextPrimary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Default Reasoning Effort: ${viewModel.tokenStore.selectedEffort.uppercase()}", color = TextSecondary, fontSize = 13.sp)
                    Text("Default Agent Mode: ${viewModel.tokenStore.selectedMode}", color = TextSecondary, fontSize = 13.sp)
                }
            }

            // Disconnect Button
            Button(
                onClick = { viewModel.disconnect(onDisconnected) },
                colors = ButtonDefaults.buttonColors(containerColor = ErrorRed.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(AppIcons.ExitToApp, contentDescription = null, tint = ErrorRed)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Disconnect & Clear Credentials", color = ErrorRed, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showCreateTokenDialog) {
        var newDeviceName by remember { mutableStateOf("Android Tablet") }
        AlertDialog(
            onDismissRequest = { showCreateTokenDialog = false },
            title = { Text("Generate Device Token") },
            text = {
                OutlinedTextField(
                    value = newDeviceName,
                    onValueChange = { newDeviceName = it },
                    label = { Text("Device Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    showCreateTokenDialog = false
                    viewModel.createDeviceToken(newDeviceName)
                }) {
                    Text("Generate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateTokenDialog = false }) {
                    Text("Cancel")
                }
            },
            containerColor = DarkSurface
        )
    }

    // New generated token dialog
    newGeneratedToken?.let { token ->
        AlertDialog(
            onDismissRequest = { viewModel.newGeneratedToken.value = null },
            title = { Text("New Token Generated") },
            text = {
                Column {
                    Text("Copy this token now. It will not be shown again:", color = TextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = CodeBg,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = token,
                            color = PrimaryBlue,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Device Token", token))
                    Toast.makeText(context, "Token copied to clipboard", Toast.LENGTH_SHORT).show()
                    viewModel.newGeneratedToken.value = null
                }) {
                    Text("Copy & Close")
                }
            },
            containerColor = DarkSurface
        )
    }
}
