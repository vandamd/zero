package com.vandam.zero.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import com.vandam.zero.ui.theme.CameraColors
import com.vandam.zero.ui.theme.CameraDimens
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/**
 * A custom slider for exposure compensation adjustment.
 * Displays EV stops from -2 to +2 with 0.5 step increments.
 *
 * @param value Current exposure value (-2 to +2).
 * @param onValueChange Callback when the value changes.
 * @param modifier Modifier to be applied to the slider.
 */
@Composable
fun ExposureSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var lastSnappedValue by remember { mutableStateOf(value) }

    Canvas(
        modifier =
            modifier
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val fraction = change.position.x / size.width
                        val rawValue = (-2f + (fraction * 4f)).coerceIn(-2f, 2f)
                        val snappedValue = (kotlin.math.round(rawValue * 2f) / 2f).coerceIn(-2f, 2f)
                        if (snappedValue != lastSnappedValue) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            lastSnappedValue = snappedValue
                            onValueChange(snappedValue)
                        }
                    }
                }.pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val fraction = offset.x / size.width
                        val rawValue = (-2f + (fraction * 4f)).coerceIn(-2f, 2f)
                        val snappedValue = (kotlin.math.round(rawValue * 2f) / 2f).coerceIn(-2f, 2f)
                        if (snappedValue != lastSnappedValue) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            lastSnappedValue = snappedValue
                            onValueChange(snappedValue)
                        }
                    }
                },
    ) {
        val trackStrokeWidth = CameraDimens.sliderTrackStrokeWidth
        val thumbStrokeWidth = CameraDimens.sliderThumbStrokeWidth
        val centerY = size.height / 2f
        val notchLength = CameraDimens.sliderNotchLength
        val trackColor = CameraColors.overlay

        // Draw track line
        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = trackStrokeWidth,
        )

        // Draw EV step notches
        val evSteps = listOf(-2f, -1.5f, -1f, -0.5f, 0f, 0.5f, 1f, 1.5f, 2f)
        evSteps.forEach { ev ->
            val normalizedValue = (ev + 2f) / 4f
            val notchX = normalizedValue * size.width

            val length =
                when {
                    ev == 0f -> notchLength * 2.2f
                    ev % 1f == 0f -> notchLength * 1.8f
                    else -> notchLength
                }

            drawLine(
                color = trackColor,
                start = Offset(notchX, centerY - length / 2f),
                end = Offset(notchX, centerY + length / 2f),
                strokeWidth = trackStrokeWidth,
            )
        }

        // Draw thumb
        val normalizedValue = (value + 2f) / 4f
        val thumbX = normalizedValue * size.width

        drawLine(
            color = CameraColors.onSurface,
            start = Offset(thumbX, 0f),
            end = Offset(thumbX, size.height),
            strokeWidth = thumbStrokeWidth,
        )
    }
}

/**
 * A custom slider for ISO adjustment.
 * Displays ISO values on a logarithmic scale from 100 to 3200.
 *
 * @param value Current ISO value.
 * @param onValueChange Callback when the value changes.
 * @param modifier Modifier to be applied to the slider.
 */
@Composable
fun IsoSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var lastSnappedValue by remember { mutableStateOf(value) }

    Canvas(
        modifier =
            modifier
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        val rawValue = positionToIso(fraction)
                        val snappedValue = snapToNearestIso(rawValue)
                        if (snappedValue != lastSnappedValue) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            lastSnappedValue = snappedValue
                            onValueChange(snappedValue)
                        }
                    }
                }.pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        val rawValue = positionToIso(fraction)
                        val snappedValue = snapToNearestIso(rawValue)
                        if (snappedValue != lastSnappedValue) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            lastSnappedValue = snappedValue
                            onValueChange(snappedValue)
                        }
                    }
                },
    ) {
        val trackStrokeWidth = CameraDimens.sliderTrackStrokeWidth
        val thumbStrokeWidth = CameraDimens.sliderThumbStrokeWidth
        val centerY = size.height / 2f
        val notchLength = CameraDimens.sliderNotchLength
        val trackColor = CameraColors.overlay

        // Draw track line
        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = trackStrokeWidth,
        )

        // Draw ISO step notches
        ISO_STEPS.forEach { iso ->
            val normalizedValue = isoToPosition(iso)
            val notchX = normalizedValue * size.width

            val length =
                when (iso) {
                    400 -> notchLength * 2.2f
                    800, 1600 -> notchLength * 1.8f
                    else -> notchLength
                }

            drawLine(
                color = trackColor,
                start = Offset(notchX, centerY - length / 2f),
                end = Offset(notchX, centerY + length / 2f),
                strokeWidth = trackStrokeWidth,
            )
        }

        // Draw thumb
        val normalizedValue = isoToPosition(value)
        val thumbX = normalizedValue * size.width

        drawLine(
            color = CameraColors.onSurface,
            start = Offset(thumbX, 0f),
            end = Offset(thumbX, size.height),
            strokeWidth = thumbStrokeWidth,
        )
    }
}

/**
 * A custom slider for shutter speed adjustment.
 * Displays shutter speeds from 1/1000s to 1s.
 *
 * @param value Current shutter speed in nanoseconds.
 * @param onValueChange Callback when the value changes.
 * @param modifier Modifier to be applied to the slider.
 */
@Composable
fun ShutterSpeedSlider(
    value: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var lastSnappedValue by remember { mutableStateOf(value) }

    Canvas(
        modifier =
            modifier
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        val rawValue = positionToShutter(fraction)
                        val snappedValue = snapToNearestShutter(rawValue)
                        if (snappedValue != lastSnappedValue) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            lastSnappedValue = snappedValue
                            onValueChange(snappedValue)
                        }
                    }
                }.pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        val rawValue = positionToShutter(fraction)
                        val snappedValue = snapToNearestShutter(rawValue)
                        if (snappedValue != lastSnappedValue) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            lastSnappedValue = snappedValue
                            onValueChange(snappedValue)
                        }
                    }
                },
    ) {
        val trackStrokeWidth = CameraDimens.sliderTrackStrokeWidth
        val thumbStrokeWidth = CameraDimens.sliderThumbStrokeWidth
        val centerY = size.height / 2f
        val notchLength = CameraDimens.sliderNotchLength
        val trackColor = CameraColors.overlay

        // Draw track line
        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = trackStrokeWidth,
        )

        // Draw shutter speed notches
        SHUTTER_SPEEDS.forEachIndexed { index, speed ->
            val normalizedValue = index.toFloat() / (SHUTTER_SPEEDS.size - 1)
            val notchX = normalizedValue * size.width

            val length =
                when (speed) {
                    16_666_666L -> notchLength * 2.2f
                    8_000_000L, 33_333_333L -> notchLength * 1.8f
                    else -> notchLength
                }

            drawLine(
                color = trackColor,
                start = Offset(notchX, centerY - length / 2f),
                end = Offset(notchX, centerY + length / 2f),
                strokeWidth = trackStrokeWidth,
            )
        }

        // Draw thumb
        val normalizedValue = shutterToPosition(value)
        val thumbX = normalizedValue * size.width

        drawLine(
            color = CameraColors.onSurface,
            start = Offset(thumbX, 0f),
            end = Offset(thumbX, size.height),
            strokeWidth = thumbStrokeWidth,
        )
    }
}

// --- ISO Slider Utilities ---

private val ISO_STEPS = listOf(100, 200, 400, 800, 1600, 3200)

private fun positionToIso(position: Float): Int {
    val logValue = 100 * 2.0.pow((position * 5).toDouble())
    return logValue.toInt().coerceIn(100, 3200)
}

private fun isoToPosition(iso: Int): Float {
    val position = log2(iso / 100.0) / 5.0
    return position.toFloat().coerceIn(0f, 1f)
}

private fun snapToNearestIso(iso: Int): Int = ISO_STEPS.minByOrNull { abs(it - iso) } ?: 400

// --- Shutter Speed Slider Utilities ---

private val SHUTTER_SPEEDS =
    listOf(
        1_000_000L,
        2_000_000L,
        4_000_000L,
        8_000_000L,
        16_666_666L,
        33_333_333L,
        66_666_666L,
        125_000_000L,
        250_000_000L,
        500_000_000L,
        1_000_000_000L,
    )

private fun positionToShutter(position: Float): Long {
    val index = (position * (SHUTTER_SPEEDS.size - 1)).toInt().coerceIn(0, SHUTTER_SPEEDS.size - 1)
    return SHUTTER_SPEEDS[index]
}

private fun shutterToPosition(shutter: Long): Float {
    val index = SHUTTER_SPEEDS.indexOfFirst { it >= shutter }.takeIf { it >= 0 } ?: (SHUTTER_SPEEDS.size - 1)
    return index.toFloat() / (SHUTTER_SPEEDS.size - 1)
}

private fun snapToNearestShutter(ns: Long): Long = SHUTTER_SPEEDS.minByOrNull { abs(it - ns) } ?: 16_666_666L

/**
 * Formats a shutter speed value in nanoseconds to a human-readable string.
 *
 * @param ns Shutter speed in nanoseconds.
 * @return Formatted string like "1/60" or "1s".
 */
fun formatShutterSpeed(ns: Long): String {
    val seconds = ns / 1_000_000_000.0
    return when {
        seconds >= 1.0 -> "${seconds.toInt()}s"
        else -> "1/${(1.0 / seconds).toInt()}"
    }
}
