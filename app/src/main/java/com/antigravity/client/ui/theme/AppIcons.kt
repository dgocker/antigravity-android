package com.antigravity.client.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight, high-performance icon set using material-icons-core and native Vector paths.
 * Avoids the ~35MB material-icons-extended dependency which stalls low-RAM environments.
 */
object AppIcons {
    // Core Material icons
    val ArrowBack = Icons.AutoMirrored.Filled.ArrowBack
    val ArrowForward = Icons.AutoMirrored.Filled.ArrowForward
    val ChevronRight = Icons.AutoMirrored.Filled.KeyboardArrowRight
    val ExitToApp = Icons.AutoMirrored.Filled.ExitToApp
    val Send = Icons.AutoMirrored.Filled.Send
    val List = Icons.AutoMirrored.Filled.List

    val Add = Icons.Default.Add
    val Build = Icons.Default.Build
    val Check = Icons.Default.Check
    val Clear = Icons.Default.Clear
    val Close = Icons.Default.Close
    val Delete = Icons.Default.Delete
    val DeleteOutline = Icons.Default.Delete
    val Edit = Icons.Default.Edit
    val Face = Icons.Default.Face
    val Home = Icons.Default.Home
    val Info = Icons.Default.Info
    val KeyboardArrowDown = Icons.Default.KeyboardArrowDown
    val KeyboardArrowUp = Icons.Default.KeyboardArrowUp
    val Lock = Icons.Default.Lock
    val Menu = Icons.Default.Menu
    val MoreVert = Icons.Default.MoreVert
    val Phone = Icons.Default.Phone
    val Place = Icons.Default.Place
    val PlayArrow = Icons.Default.PlayArrow
    val Refresh = Icons.Default.Refresh
    val Search = Icons.Default.Search
    val Settings = Icons.Default.Settings
    val Share = Icons.Default.Share
    val Star = Icons.Default.Star
    val Warning = Icons.Default.Warning

    // Mappings & Aliases
    val VpnKey = Icons.Default.Lock
    val PhoneAndroid = Icons.Default.Phone
    val SmartToy = Icons.Default.Face
    val Psychology = Icons.Default.Star
    val Cloud = Icons.Default.Place
    val Login = Icons.AutoMirrored.Filled.ExitToApp
    val HideImage = Icons.Default.Warning

    // Custom crisp Vector Paths
    val Stop: ImageVector = ImageVector.Builder(
        name = "Stop",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(6f, 6f)
            horizontalLineToRelative(12f)
            verticalLineToRelative(12f)
            horizontalLineTo(6f)
            close()
        }
    }.build()

    val ContentCopy: ImageVector = ImageVector.Builder(
        name = "ContentCopy",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(12f)
            verticalLineTo(1f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
            verticalLineToRelative(14f)
            curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
            horizontalLineToRelative(11f)
            curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
            verticalLineTo(7f)
            curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineToRelative(11f)
            verticalLineTo(21f)
            close()
        }
    }.build()

    val ArrowUpward: ImageVector = ImageVector.Builder(
        name = "ArrowUpward",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(4f, 12f)
            lineToRelative(1.41f, 1.41f)
            lineTo(11f, 7.83f)
            verticalLineTo(20f)
            horizontalLineToRelative(2f)
            verticalLineTo(7.83f)
            lineToRelative(5.58f, 5.59f)
            lineTo(20f, 12f)
            lineToRelative(-8f, -8f)
            lineToRelative(-8f, 8f)
            close()
        }
    }.build()

    val Folder: ImageVector = ImageVector.Builder(
        name = "Folder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(10f, 4f)
            horizontalLineTo(4f)
            curveTo(2.9f, 4f, 2.01f, 4.9f, 2.01f, 6f)
            lineTo(2f, 18f)
            curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
            horizontalLineToRelative(16f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            verticalLineTo(8f)
            curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
            horizontalLineToRelative(-8f)
            lineToRelative(-2f, -2f)
            close()
        }
    }.build()

    val Description: ImageVector = ImageVector.Builder(
        name = "Description",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(14f, 2f)
            horizontalLineTo(6f)
            curveTo(4.9f, 2f, 4.01f, 2.9f, 4.01f, 4f)
            lineTo(4f, 20f)
            curveTo(4f, 21.1f, 4.89f, 22f, 5.99f, 22f)
            horizontalLineTo(18f)
            curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
            verticalLineTo(8f)
            lineToRelative(-6f, -6f)
            close()
            moveTo(16f, 18f)
            horizontalLineTo(8f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(8f)
            verticalLineTo(18f)
            close()
            moveTo(16f, 14f)
            horizontalLineTo(8f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(8f)
            verticalLineTo(14f)
            close()
            moveTo(13f, 9f)
            verticalLineTo(3.5f)
            lineTo(18.5f, 9f)
            horizontalLineTo(13f)
            close()
        }
    }.build()

    val Terminal: ImageVector = ImageVector.Builder(
        name = "Terminal",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(20f, 4f)
            horizontalLineTo(4f)
            curveTo(2.89f, 4f, 2f, 4.9f, 2f, 6f)
            verticalLineToRelative(12f)
            curveTo(2f, 19.1f, 2.89f, 20f, 4f, 20f)
            horizontalLineToRelative(16f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            verticalLineTo(6f)
            curveTo(22f, 4.9f, 21.1f, 4f, 20f, 4f)
            close()
            moveTo(20f, 18f)
            horizontalLineTo(4f)
            verticalLineTo(8f)
            horizontalLineToRelative(16f)
            verticalLineTo(18f)
            close()
            moveTo(12f, 17f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-6f)
            verticalLineTo(17f)
            close()
            moveTo(7.5f, 17f)
            lineToRelative(1.41f, -1.41f)
            lineTo(6.33f, 13f)
            lineToRelative(2.58f, -2.59f)
            lineTo(7.5f, 9f)
            lineToRelative(-4f, 4f)
            lineTo(7.5f, 17f)
            close()
        }
    }.build()

    val FormatQuote: ImageVector = ImageVector.Builder(
        name = "FormatQuote",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(6f, 17f)
            horizontalLineToRelative(3f)
            lineToRelative(2f, -4f)
            verticalLineTo(7f)
            horizontalLineTo(5f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(3f)
            close()
            moveTo(14f, 17f)
            horizontalLineToRelative(3f)
            lineToRelative(2f, -4f)
            verticalLineTo(7f)
            horizontalLineToRelative(-6f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(3f)
            close()
        }
    }.build()

    val ChatBubbleOutline: ImageVector = ImageVector.Builder(
        name = "ChatBubbleOutline",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(20f, 2f)
            horizontalLineTo(4f)
            curveTo(2.9f, 2f, 2f, 2.9f, 2f, 4f)
            verticalLineToRelative(18f)
            lineToRelative(4f, -4f)
            horizontalLineToRelative(14f)
            curveTo(21.1f, 18f, 22f, 17.1f, 22f, 16f)
            verticalLineTo(4f)
            curveTo(22f, 2.9f, 21.1f, 2f, 20f, 2f)
            close()
            moveTo(20f, 16f)
            horizontalLineTo(6f)
            lineToRelative(-2f, 2f)
            verticalLineTo(4f)
            horizontalLineToRelative(16f)
            verticalLineTo(16f)
            close()
        }
    }.build()

    val Visibility: ImageVector = ImageVector.Builder(
        name = "Visibility",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 4.5f)
            curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
            curveToRelative(1.73f, 4.39f, 6f, 7.5f, 11f, 7.5f)
            reflectiveCurveToRelative(9.27f, -3.11f, 11f, -7.5f)
            curveToRelative(-1.73f, -4.39f, -6f, -7.5f, -11f, -7.5f)
            close()
            moveTo(12f, 17f)
            curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
            reflectiveCurveToRelative(2.24f, -5f, 5f, -5f)
            reflectiveCurveToRelative(5f, 2.24f, 5f, 5f)
            reflectiveCurveToRelative(-2.24f, 5f, -5f, 5f)
            close()
            moveTo(12f, 9f)
            curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
            reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
            reflectiveCurveToRelative(3f, -1.34f, 3f, -3f)
            reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
            close()
        }
    }.build()

    val VisibilityOff: ImageVector = ImageVector.Builder(
        name = "VisibilityOff",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 7f)
            curveToRelative(2.76f, 0f, 5f, 2.24f, 5f, 5f)
            curveToRelative(0f, 0.65f, -0.13f, 1.26f, -0.36f, 1.83f)
            lineToRelative(2.92f, 2.92f)
            curveToRelative(1.51f, -1.26f, 2.7f, -2.89f, 3.44f, -4.75f)
            curveToRelative(-1.73f, -4.39f, -6f, -7.5f, -11f, -7.5f)
            curveToRelative(-1.4f, 0f, -2.74f, 0.25f, -3.98f, 0.7f)
            lineToRelative(2.16f, 2.16f)
            curveTo(10.74f, 7.13f, 11.35f, 7f, 12f, 7f)
            close()
            moveTo(2f, 4.27f)
            lineToRelative(2.28f, 2.28f)
            lineToRelative(0.46f, 0.46f)
            curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1f, 12f)
            curveToRelative(1.73f, 4.39f, 6f, 7.5f, 11f, 7.5f)
            curveToRelative(1.55f, 0f, 3.03f, -0.3f, 4.38f, -0.84f)
            lineToRelative(0.42f, 0.42f)
            lineTo(19.73f, 22f)
            lineTo(21f, 20.73f)
            lineTo(3.27f, 3f)
            lineTo(2f, 4.27f)
            close()
        }
    }.build()

    val Download: ImageVector = ImageVector.Builder(
        name = "Download",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(19f, 9f)
            horizontalLineToRelative(-4f)
            verticalLineTo(3f)
            horizontalLineTo(9f)
            verticalLineToRelative(6f)
            horizontalLineTo(5f)
            lineToRelative(7f, 7f)
            lineToRelative(7f, -7f)
            close()
            moveTo(5f, 18f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(14f)
            verticalLineToRelative(-2f)
            horizontalLineTo(5f)
            close()
        }
    }.build()

    val Image: ImageVector = ImageVector.Builder(
        name = "Image",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(21f, 19f)
            verticalLineTo(5f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            horizontalLineTo(5f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            close()
            moveTo(8.5f, 13.5f)
            lineToRelative(2.5f, 3.01f)
            lineToRelative(3.5f, -4.51f)
            lineToRelative(4.5f, 6f)
            horizontalLineTo(5f)
            lineToRelative(3.5f, -4.5f)
            close()
        }
    }.build()

    val Mic: ImageVector = ImageVector.Builder(
        name = "Mic",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 14f)
            curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
            lineTo(15f, 5f)
            curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
            curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
            lineTo(9f, 11f)
            curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
            close()
            moveTo(17.3f, 11f)
            curveTo(17.3f, 14f, 14.76f, 16.1f, 12f, 16.1f)
            curveTo(9.24f, 16.1f, 6.7f, 14f, 6.7f, 11f)
            lineTo(5f, 11f)
            curveTo(5f, 14.41f, 7.72f, 17.23f, 11f, 17.72f)
            lineTo(11f, 21f)
            lineTo(13f, 21f)
            lineTo(13f, 17.72f)
            curveTo(16.28f, 17.23f, 19f, 14.41f, 19f, 11f)
            lineTo(17.3f, 11f)
            close()
        }
    }.build()

    val AttachFile: ImageVector = ImageVector.Builder(
        name = "AttachFile",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(16.5f, 6f)
            lineTo(16.5f, 17.5f)
            curveTo(16.5f, 19.43f, 14.93f, 21f, 13f, 21f)
            curveTo(11.07f, 21f, 9.5f, 19.43f, 9.5f, 17.5f)
            lineTo(9.5f, 5.5f)
            curveTo(9.5f, 4.12f, 10.62f, 3f, 12f, 3f)
            curveTo(13.38f, 3f, 14.5f, 4.12f, 14.5f, 5.5f)
            lineTo(14.5f, 15.5f)
            curveTo(14.5f, 16.05f, 14.05f, 16.5f, 13.5f, 16.5f)
            curveTo(12.95f, 16.5f, 12.5f, 16.05f, 12.5f, 15.5f)
            lineTo(12.5f, 6f)
            lineTo(11f, 6f)
            lineTo(11f, 15.5f)
            curveTo(11f, 16.88f, 12.12f, 18f, 13.5f, 18f)
            curveTo(14.88f, 18f, 16f, 16.88f, 16f, 15.5f)
            lineTo(16f, 5.5f)
            curveTo(16f, 3.29f, 14.21f, 1.5f, 12f, 1.5f)
            curveTo(9.79f, 1.5f, 8f, 3.29f, 8f, 5.5f)
            lineTo(8f, 17.5f)
            curveTo(8f, 20.26f, 10.24f, 22.5f, 13f, 22.5f)
            curveTo(15.76f, 22.5f, 18f, 20.26f, 18f, 17.5f)
            lineTo(18f, 6f)
            lineTo(16.5f, 6f)
            close()
        }
    }.build()

    val Pause: ImageVector = ImageVector.Builder(
        name = "Pause",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(6f, 19f)
            horizontalLineTo(10f)
            verticalLineTo(5f)
            horizontalLineTo(6f)
            verticalLineTo(19f)
            close()
            moveTo(14f, 5f)
            verticalLineTo(19f)
            horizontalLineTo(18f)
            verticalLineTo(5f)
            horizontalLineTo(14f)
            close()
        }
    }.build()

    val CameraAlt: ImageVector = ImageVector.Builder(
        name = "CameraAlt",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(9f, 2f)
            lineTo(7.17f, 4f)
            horizontalLineTo(4f)
            curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
            verticalLineTo(18f)
            curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
            horizontalLineTo(20f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            verticalLineTo(6f)
            curveTo(22f, 4.9f, 21.1f, 4f, 20f, 4f)
            horizontalLineTo(16.83f)
            lineTo(15f, 2f)
            horizontalLineTo(9f)
            close()
            moveTo(12f, 17f)
            curveTo(9.24f, 17f, 7f, 14.76f, 7f, 12f)
            curveTo(7f, 9.24f, 9.24f, 7f, 12f, 7f)
            curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
            curveTo(17f, 14.76f, 14.76f, 17f, 12f, 17f)
            close()
            moveTo(12f, 9f)
            curveTo(10.34f, 9f, 9f, 10.34f, 9f, 12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
            curveTo(15f, 10.34f, 13.66f, 9f, 12f, 9f)
            close()
        }
    }.build()
}
