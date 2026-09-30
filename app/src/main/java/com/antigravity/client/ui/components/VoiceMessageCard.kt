package com.antigravity.client.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.client.audio.AudioPlayer
import com.antigravity.client.domain.model.Attachment
import com.antigravity.client.ui.theme.*

@Composable
fun VoiceMessageCard(
    attachment: Attachment,
    audioPlayer: AudioPlayer,
    isUser: Boolean,
    modifier: Modifier = Modifier
) {
    val playbackState by audioPlayer.playbackState.collectAsState()
    val mediaUrl = attachment.localUri ?: attachment.remoteUrl ?: ""
    val isCurrentAudio = playbackState.urlOrPath == mediaUrl
    val isPlaying = isCurrentAudio && playbackState.isPlaying

    val currentMs = if (isCurrentAudio) playbackState.currentPositionMs else 0
    val totalMs = if (isCurrentAudio && playbackState.durationMs > 0) {
        playbackState.durationMs
    } else {
        (attachment.duration ?: 0) * 1000
    }

    val progress = if (totalMs > 0) (currentMs.toFloat() / totalMs).coerceIn(0f, 1f) else 0f
    var expandedTranscription by remember { mutableStateOf(false) }

    fun formatDuration(ms: Int): String {
        val totalSecs = ms / 1000
        val mins = totalSecs / 60
        val secs = totalSecs % 60
        return String.format("%02d:%02d", mins, secs)
    }

    val durationText = if (isPlaying || (isCurrentAudio && currentMs > 0)) {
        formatDuration(currentMs)
    } else {
        formatDuration(totalMs)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isUser) DarkSurfaceVariant.copy(alpha = 0.5f) else DarkSurfaceElevated)
            .padding(10.dp)
    ) {
        // Player Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play / Pause Circle Button
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(PrimaryBlue)
                    .clickable {
                        if (mediaUrl.isNotEmpty()) {
                            audioPlayer.play(mediaUrl)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) AppIcons.Pause else AppIcons.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Waveform / Progress Slider
            Column(modifier = Modifier.weight(1f)) {
                Slider(
                    value = progress,
                    onValueChange = { newProgress ->
                        if (isCurrentAudio && totalMs > 0) {
                            audioPlayer.seekTo((newProgress * totalMs).toInt())
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = PrimaryBlue,
                        activeTrackColor = PrimaryBlue,
                        inactiveTrackColor = DarkSurfaceVariant
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isPlaying) "Воспроизведение..." else "Голосовое сообщение",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                    Text(
                        text = durationText,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Transcription section (only shown if transcription exists)
        if (!attachment.transcription.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = DarkSurfaceVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedTranscription = !expandedTranscription }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (expandedTranscription) AppIcons.KeyboardArrowUp else AppIcons.KeyboardArrowDown,
                    contentDescription = "Toggle transcription",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Расшифровка",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            AnimatedVisibility(
                visible = expandedTranscription,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    color = DarkBackground.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Text(
                        text = attachment.transcription,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}
