package com.vandam.zero.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vandam.zero.R

/**
 * Typography definitions for the camera UI.
 */
object CameraTypography {
    val publicSans =
        FontFamily(
            Font(R.font.publicsans_variablefont_wght),
        )

    val toolbarText =
        TextStyle(
            fontSize = 32.sp,
            fontFamily = publicSans,
            fontWeight = FontWeight.ExtraBold,
        )

    val permissionText =
        TextStyle(
            fontSize = 32.sp,
            fontFamily = publicSans,
            fontWeight = FontWeight.Bold,
        )
}

/**
 * Color definitions for the camera UI.
 */
object CameraColors {
    val background = Color.Black
    val surface = Color.Black
    val onSurface = Color.White
    val onSurfaceVariant = Color.White.copy(alpha = 0.7f)
    val onSurfaceDisabled = Color.White.copy(alpha = 0.3f)
    val overlay = Color.White
    val gridLine = Color.White
}

/**
 * Dimension constants for the camera UI.
 */
object CameraDimens {
    // Toolbar
    val toolbarWidth = 60.dp
    val toolbarIconSize = 32.dp
    val toolbarVerticalPadding = 8.dp
    val toolbarItemSpacing = 16.dp

    // Crosshair
    val crosshairSize = 80.dp
    val crosshairStrokeWidth = 2f
    val crosshairNotchLength = 10f

    // Grid
    val gridStrokeWidth = 3f

    // Sliders
    val sliderHeight = 60.dp
    val sliderHorizontalPadding = 24.dp
    val sliderVerticalPadding = 12.dp
    val sliderTrackStrokeWidth = 3f
    val sliderThumbStrokeWidth = 8f
    val sliderNotchLength = 16f

    // Preview
    val previewThumbnailSize = 200.dp
    val previewBottomPadding = 24.dp

    // General
    val screenPadding = 24.dp
    val statusPanelWidth = 60.dp
    val statusPanelVerticalPadding = 16.dp
}

/**
 * Timing constants for animations and delays.
 */
object CameraTiming {
    const val TOAST_DISPLAY_DURATION_MS = 2000L
    const val CROSSHAIR_HIDE_DELAY_MS = 2000L
    const val SHUTTER_FLASH_DURATION_MS = 50L
    const val PREVIEW_DISPLAY_DURATION_MS = 800L
}
