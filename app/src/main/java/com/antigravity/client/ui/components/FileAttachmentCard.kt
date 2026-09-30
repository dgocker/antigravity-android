package com.antigravity.client.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.client.domain.model.FileAttachment
import com.antigravity.client.ui.theme.*

@Composable
fun FileAttachmentCard(
    file: FileAttachment,
    onDownload: () -> Unit,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        color = DarkSurfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    color = PrimaryBlue.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (file.isImage) AppIcons.Image else AppIcons.Description,
                            contentDescription = null,
                            tint = if (file.isImage) AccentPurple else PrimaryBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = file.path,
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!file.action.isNullOrBlank()) {
                        Text(
                            text = file.action,
                            color = AccentGreen,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onDownload,
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = PrimaryBlue.copy(alpha = 0.2f),
                    contentColor = PrimaryBlue
                )
            ) {
                Icon(
                    imageVector = AppIcons.Download,
                    contentDescription = "Download file",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun ImageAttachmentCard(
    file: FileAttachment,
    imageUrl: String,
    onPreviewImage: (String) -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = DarkSurfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 320.dp)
                    .background(DarkSurfaceElevated)
                    .clickable { onPreviewImage(imageUrl) },
                contentAlignment = Alignment.Center
            ) {
                coil.compose.AsyncImage(
                    model = imageUrl,
                    contentDescription = file.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.name,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!file.action.isNullOrBlank()) {
                        Text(
                            text = file.action,
                            color = AccentPurple,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = PrimaryBlue.copy(alpha = 0.2f),
                        contentColor = PrimaryBlue
                    )
                ) {
                    Icon(
                        imageVector = AppIcons.Download,
                        contentDescription = "Download image",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

