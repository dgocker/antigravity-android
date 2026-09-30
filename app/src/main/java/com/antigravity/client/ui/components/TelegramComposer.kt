package com.antigravity.client.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.antigravity.client.audio.AudioRecorder
import com.antigravity.client.audio.AudioRecordingResult
import com.antigravity.client.domain.model.Attachment
import com.antigravity.client.ui.theme.*

@Composable
fun TelegramComposer(
    text: String,
    onTextChange: (String) -> Unit,
    attachments: List<Attachment>,
    onRemoveAttachment: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceRecorded: (AudioRecordingResult) -> Unit,
    onPickCamera: () -> Unit,
    onPickGallery: () -> Unit,
    onPickFile: () -> Unit,
    isRunning: Boolean,
    onCancelRun: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showPickerSheet by remember { mutableStateOf(false) }

    // Audio recording state
    val audioRecorder = remember { AudioRecorder(context) }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var recordingDurationSecs by remember { mutableIntStateOf(0) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var liveTranscription by remember { mutableStateOf<String?>(null) }
    var permissionDeniedMessage by remember { mutableStateOf<String?>(null) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            try {
                isRecordingVoice = true
                recordingDurationSecs = 0
                dragOffset = 0f
                liveTranscription = null
                audioRecorder.startRecording(
                    onDurationTick = { recordingDurationSecs = it },
                    onTranscription = { liveTranscription = it }
                )
            } catch (e: Exception) {
                isRecordingVoice = false
                permissionDeniedMessage = "Не удалось начать запись: ${e.message}"
            }
        } else {
            permissionDeniedMessage = "Для записи голосовых сообщений требуется разрешение на доступ к микрофону"
        }
    }

    fun startVoiceRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            try {
                isRecordingVoice = true
                recordingDurationSecs = 0
                dragOffset = 0f
                liveTranscription = null
                audioRecorder.startRecording(
                    onDurationTick = { recordingDurationSecs = it },
                    onTranscription = { liveTranscription = it }
                )
            } catch (e: Exception) {
                isRecordingVoice = false
                permissionDeniedMessage = "Ошибка записи: ${e.message}"
            }
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun finishVoiceRecording(send: Boolean) {
        if (!isRecordingVoice) return
        isRecordingVoice = false
        if (send) {
            val result = audioRecorder.stopRecording()
            if (result != null) {
                val finalResult = if (liveTranscription != null && result.transcription == null) {
                    result.copy(transcription = liveTranscription)
                } else result
                onVoiceRecorded(finalResult)
            }
        } else {
            audioRecorder.cancelRecording()
        }
        dragOffset = 0f
        recordingDurationSecs = 0
        liveTranscription = null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(DarkSurface)
    ) {
        // Attachments Preview Strip
        AttachmentPreviewBar(
            attachments = attachments,
            onRemoveAttachment = onRemoveAttachment
        )

        // Main Composer Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            if (isRecordingVoice) {
                // Voice Recording State UX: "← Отмена       🔴 00:07       Отпустите для отправки"
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(DarkBackground)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (dragOffset < -150f) {
                                        finishVoiceRecording(send = false)
                                    } else {
                                        dragOffset = 0f
                                    }
                                },
                                onDragCancel = {
                                    finishVoiceRecording(send = false)
                                },
                                onHorizontalDrag = { _, dragAmount ->
                                    dragOffset = (dragOffset + dragAmount).coerceAtMost(0f)
                                    if (dragOffset < -200f) {
                                        finishVoiceRecording(send = false)
                                    }
                                }
                            )
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Cancel button / swipe indicator
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { finishVoiceRecording(send = false) }
                    ) {
                        Icon(
                            imageVector = AppIcons.ArrowBack,
                            contentDescription = "Cancel",
                            tint = ErrorRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Отмена",
                            color = ErrorRed,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Recording indicator & Timer
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(ErrorRed)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val mins = recordingDurationSecs / 60
                        val secs = recordingDurationSecs % 60
                        Text(
                            text = String.format("%02d:%02d", mins, secs),
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = if (dragOffset < -50f) "Отпустите для отмены" else "Свайп влево для отмены",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Stop & Send button for voice
                IconButton(
                    onClick = { finishVoiceRecording(send = true) },
                    modifier = Modifier
                        .size(44.dp)
                        .background(PrimaryBlue, shape = CircleShape)
                ) {
                    Icon(
                        imageVector = AppIcons.ArrowUpward,
                        contentDescription = "Send voice",
                        tint = TextPrimary
                    )
                }

            } else {
                // Normal Input State

                // Attach Button (📎)
                IconButton(
                    onClick = { showPickerSheet = true },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.AttachFile,
                        contentDescription = "Attach",
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Text Input Field
                val placeholderText = if (attachments.isNotEmpty()) "Добавить подпись..." else "Написать сообщение..."
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text(placeholderText, color = TextMuted, fontSize = 14.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    shape = RoundedCornerShape(22.dp),
                    minLines = 1,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryBlue,
                        unfocusedBorderColor = DarkSurfaceVariant,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = DarkBackground,
                        unfocusedContainerColor = DarkBackground
                    )
                )

                // Right Button: Send (➤), Stop (■ if running), or Mic (🎤)
                val hasContent = text.isNotBlank() || attachments.isNotEmpty()

                if (hasContent) {
                    IconButton(
                        onClick = onSend,
                        modifier = Modifier
                            .size(44.dp)
                            .background(PrimaryBlue, shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = AppIcons.ArrowUpward,
                            contentDescription = "Send",
                            tint = TextPrimary
                        )
                    }
                } else if (isRunning) {
                    IconButton(
                        onClick = onCancelRun,
                        modifier = Modifier
                            .size(44.dp)
                            .background(ErrorRed, shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = AppIcons.Stop,
                            contentDescription = "Cancel",
                            tint = TextPrimary
                        )
                    }
                } else {
                    // Microphone button (🎤)
                    IconButton(
                        onClick = { startVoiceRecording() },
                        modifier = Modifier
                            .size(44.dp)
                            .background(DarkSurfaceVariant, shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = AppIcons.Mic,
                            contentDescription = "Record voice",
                            tint = PrimaryBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // Permission denied warning dialog
        if (permissionDeniedMessage != null) {
            AlertDialog(
                onDismissRequest = { permissionDeniedMessage = null },
                title = { Text("Требуется разрешение", color = TextPrimary) },
                text = { Text(permissionDeniedMessage ?: "", color = TextSecondary) },
                confirmButton = {
                    TextButton(onClick = { permissionDeniedMessage = null }) {
                        Text("Понятно", color = PrimaryBlue)
                    }
                },
                containerColor = DarkSurface
            )
        }

        // Bottom Sheet for Attachments
        if (showPickerSheet) {
            AttachmentPickerBottomSheet(
                onDismiss = { showPickerSheet = false },
                onPickCamera = onPickCamera,
                onPickGallery = onPickGallery,
                onPickFile = onPickFile
            )
        }
    }
}
