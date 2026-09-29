package com.antigravity.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.client.domain.model.ConnectionStatus
import com.antigravity.client.ui.theme.*

@Composable
fun ConnectionBadge(status: ConnectionStatus, modifier: Modifier = Modifier) {
    val (color, text) = when (status) {
        ConnectionStatus.LIVE -> SuccessGreen to "Live"
        ConnectionStatus.CONNECTED -> SuccessGreen to "Connected"
        ConnectionStatus.REPLAYING -> WarningOrange to "Replaying"
        ConnectionStatus.CONNECTING -> PrimaryBlue to "Connecting"
        ConnectionStatus.RECONNECTING -> WarningOrange to "Reconnecting"
        ConnectionStatus.DISCONNECTED -> TextMuted to "Offline"
        ConnectionStatus.ERROR -> ErrorRed to "Error"
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                color = color,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun RunStatusBadge(status: String, modifier: Modifier = Modifier) {
    val clean = status.replace("CASCADE_RUN_STATUS_", "").lowercase()
    val (color, label) = when {
        clean.contains("running") -> PrimaryBlue to "Running"
        clean.contains("idle") || clean.contains("completed") -> SuccessGreen to "Idle"
        clean.contains("queued") -> WarningOrange to "Queued"
        clean.contains("cancelled") -> TextMuted to "Cancelled"
        clean.contains("error") -> ErrorRed to "Error"
        else -> TextSecondary to clean.replaceFirstChar { it.uppercase() }
    }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
