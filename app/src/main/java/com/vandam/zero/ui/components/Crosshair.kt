package com.vandam.zero.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.vandam.zero.ui.theme.CameraColors
import com.vandam.zero.ui.theme.CameraDimens

/**
 * A crosshair overlay that displays a rectangular focus indicator with notches.
 * Used to show the current focus point on the camera preview.
 *
 * @param modifier Modifier to be applied to the crosshair.
 */
@Composable
fun Crosshair(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier.size(CameraDimens.crosshairSize),
    ) {
        val strokeWidth = CameraDimens.crosshairStrokeWidth
        val notchLength = CameraDimens.crosshairNotchLength
        val color = CameraColors.overlay

        val left = 0f
        val top = 0f
        val right = size.width
        val bottom = size.height
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        // Draw rectangle border
        drawLine(
            color = color,
            start = Offset(left, top),
            end = Offset(right, top),
            strokeWidth = strokeWidth,
        )
        drawLine(
            color = color,
            start = Offset(right, top),
            end = Offset(right, bottom),
            strokeWidth = strokeWidth,
        )
        drawLine(
            color = color,
            start = Offset(right, bottom),
            end = Offset(left, bottom),
            strokeWidth = strokeWidth,
        )
        drawLine(
            color = color,
            start = Offset(left, bottom),
            end = Offset(left, top),
            strokeWidth = strokeWidth,
        )

        // Draw center notches
        drawLine(
            color = color,
            start = Offset(centerX, top),
            end = Offset(centerX, top + notchLength),
            strokeWidth = strokeWidth,
        )
        drawLine(
            color = color,
            start = Offset(centerX, bottom),
            end = Offset(centerX, bottom - notchLength),
            strokeWidth = strokeWidth,
        )
        drawLine(
            color = color,
            start = Offset(left, centerY),
            end = Offset(left + notchLength, centerY),
            strokeWidth = strokeWidth,
        )
        drawLine(
            color = color,
            start = Offset(right, centerY),
            end = Offset(right - notchLength, centerY),
            strokeWidth = strokeWidth,
        )
    }
}
