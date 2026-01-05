package com.vandam.zero.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.padding
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

@Composable
fun IsoSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    isoRange: IntRange,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var lastSnappedValue by remember { mutableStateOf(value) }
    val isoSteps = remember(isoRange) { generateIsoSteps(isoRange) }

    Canvas(
        modifier =
            modifier
                .pointerInput(isoRange) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        val rawValue = positionToIso(fraction, isoRange)
                        val snappedValue = snapToNearestIso(rawValue, isoSteps)
                        if (snappedValue != lastSnappedValue) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            lastSnappedValue = snappedValue
                            onValueChange(snappedValue)
                        }
                    }
                }.pointerInput(isoRange) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        val rawValue = positionToIso(fraction, isoRange)
                        val snappedValue = snapToNearestIso(rawValue, isoSteps)
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
        isoSteps.forEach { iso ->
            val normalizedValue = isoToPosition(iso, isoRange)
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
        val normalizedValue = isoToPosition(value, isoRange)
        val thumbX = normalizedValue * size.width

        drawLine(
            color = CameraColors.onSurface,
            start = Offset(thumbX, 0f),
            end = Offset(thumbX, size.height),
            strokeWidth = thumbStrokeWidth,
        )
    }
}

@Composable
fun ShutterSpeedSlider(
    value: Long,
    onValueChange: (Long) -> Unit,
    shutterRange: LongRange,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var lastSnappedValue by remember { mutableStateOf(value) }
    val shutterSteps = remember(shutterRange) { generateShutterSteps(shutterRange) }

    Canvas(
        modifier =
            modifier
                .pointerInput(shutterRange) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        val rawValue = positionToShutter(fraction, shutterSteps)
                        val snappedValue = snapToNearestShutter(rawValue, shutterSteps)
                        if (snappedValue != lastSnappedValue) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            lastSnappedValue = snappedValue
                            onValueChange(snappedValue)
                        }
                    }
                }.pointerInput(shutterRange) {
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        val rawValue = positionToShutter(fraction, shutterSteps)
                        val snappedValue = snapToNearestShutter(rawValue, shutterSteps)
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
        shutterSteps.forEachIndexed { index, speed ->
            val normalizedValue = index.toFloat() / (shutterSteps.size - 1)
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
        val normalizedValue = shutterToPosition(value, shutterSteps)
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

private val ALL_ISO_STEPS = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200)

private fun generateIsoSteps(range: IntRange): List<Int> = ALL_ISO_STEPS.filter { it in range }

private fun positionToIso(
    position: Float,
    range: IntRange,
): Int {
    if (range.first >= range.last) return range.first
    val logMin = log2(range.first.toDouble())
    val logMax = log2(range.last.toDouble())
    val logValue = logMin + position * (logMax - logMin)
    return 2.0.pow(logValue).toInt().coerceIn(range.first, range.last)
}

private fun isoToPosition(
    iso: Int,
    range: IntRange,
): Float {
    if (range.first >= range.last) return 0.5f
    val logMin = log2(range.first.toDouble())
    val logMax = log2(range.last.toDouble())
    val logIso = log2(iso.toDouble())
    return ((logIso - logMin) / (logMax - logMin)).toFloat().coerceIn(0f, 1f)
}

private fun snapToNearestIso(
    iso: Int,
    steps: List<Int>,
): Int = steps.minByOrNull { abs(it - iso) } ?: iso

// --- Shutter Speed Slider Utilities ---

private val ALL_SHUTTER_SPEEDS =
    listOf(
        31_250L,
        62_500L,
        125_000L,
        250_000L,
        500_000L,
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

private fun generateShutterSteps(range: LongRange): List<Long> = ALL_SHUTTER_SPEEDS.filter { it in range }

private fun positionToShutter(
    position: Float,
    steps: List<Long>,
): Long {
    if (steps.isEmpty()) return 16_666_666L
    val index = (position * (steps.size - 1)).toInt().coerceIn(0, steps.size - 1)
    return steps[index]
}

private fun shutterToPosition(
    shutter: Long,
    steps: List<Long>,
): Float {
    if (steps.size <= 1) return 0.5f
    val index = steps.indexOfFirst { it >= shutter }.takeIf { it >= 0 } ?: (steps.size - 1)
    return index.toFloat() / (steps.size - 1)
}

private fun snapToNearestShutter(
    ns: Long,
    steps: List<Long>,
): Long = steps.minByOrNull { abs(it - ns) } ?: ns

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
