package com.antigravity.client.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.antigravity.client.domain.model.Attachment
import com.antigravity.client.domain.model.AttachmentType
import com.antigravity.client.domain.model.AttachmentUploadState
import com.antigravity.client.ui.theme.*

@Composable
fun AttachmentPreviewBar(
    attachments: List<Attachment>,
    onRemoveAttachment: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (attachments.isEmpty()) return

    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(attachments, key = { it.id }) { att ->
            AttachmentPreviewChip(
                attachment = att,
                onRemove = { onRemoveAttachment(att.id) }
            )
        }
    }
}

@Composable
fun AttachmentPreviewChip(
    attachment: Attachment,
    onRemove: () -> Unit
) {
    val context = LocalContext.current
    val isImage = attachment.type == AttachmentType.IMAGE

    Box(
        modifier = Modifier
            .width(if (isImage) 72.dp else 140.dp)
            .height(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(DarkSurfaceVariant)
    ) {
        if (isImage && !attachment.localUri.isNullOrEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(attachment.localUri)
                    .crossfade(true)
                    .build(),
                contentDescription = attachment.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    imageVector = when (attachment.type) {
                        AttachmentType.AUDIO -> AppIcons.Mic
                        AttachmentType.VIDEO -> AppIcons.PlayArrow
                        else -> AppIcons.Description
                    },
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(20.dp)
                )

                Column {
                    Text(
                        text = attachment.fileName,
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${maxOf(1, attachment.size / 1024)} KB",
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            }
        }

        // Upload progress indicator overlay
        if (attachment.uploadState == AttachmentUploadState.UPLOADING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DarkBackground.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    progress = { attachment.uploadProgress },
                    modifier = Modifier.size(24.dp),
                    color = PrimaryBlue,
                    strokeWidth = 2.dp
                )
            }
        }

        // Remove button (top right)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(DarkBackground.copy(alpha = 0.8f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = AppIcons.Close,
                contentDescription = "Remove",
                tint = TextPrimary,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}
