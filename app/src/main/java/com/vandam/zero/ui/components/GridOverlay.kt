package com.vandam.zero.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.vandam.zero.ui.theme.CameraColors
import com.vandam.zero.ui.theme.CameraDimens

/**
 * A rule-of-thirds grid overlay for the camera preview.
 * Displays two vertical and two horizontal lines dividing the view into 9 equal sections.
 *
 * @param modifier Modifier to be applied to the grid overlay.
 */
@Composable
fun GridOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val strokeWidth = CameraDimens.gridStrokeWidth
        val gridColor = CameraColors.gridLine

        val thirdWidth = size.width / 3f
        val thirdHeight = size.height / 3f

        // Vertical lines
        drawLine(
            color = gridColor,
            start = Offset(thirdWidth, 0f),
            end = Offset(thirdWidth, size.height),
            strokeWidth = strokeWidth,
        )
        drawLine(
            color = gridColor,
            start = Offset(thirdWidth * 2f, 0f),
            end = Offset(thirdWidth * 2f, size.height),
            strokeWidth = strokeWidth,
        )

        // Horizontal lines
        drawLine(
            color = gridColor,
            start = Offset(0f, thirdHeight),
            end = Offset(size.width, thirdHeight),
            strokeWidth = strokeWidth,
        )
        drawLine(
            color = gridColor,
            start = Offset(0f, thirdHeight * 2f),
            end = Offset(size.width, thirdHeight * 2f),
            strokeWidth = strokeWidth,
        )
    }
}
