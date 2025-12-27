package com.vandam.zero.ui

import android.Manifest
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.vandam.zero.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vandam.zero.CameraViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.math.log2
import kotlin.math.abs
import kotlin.math.round


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(viewModel: CameraViewModel = viewModel()) {
    val permissions = mutableListOf(Manifest.permission.CAMERA).apply {
        if (Build.VERSION.SDK_INT <= 28) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    
    val permissionsState = rememberMultiplePermissionsState(
        permissions = permissions
    )

    LaunchedEffect(Unit) {
        permissionsState.launchMultiplePermissionRequest()
    }

    if (permissionsState.allPermissionsGranted) {
        CameraContent(viewModel)
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(text = "Camera permission required")
        }
    }
}

@Composable
fun CameraContent(viewModel: CameraViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val showFlash by viewModel.shutterFlash.collectAsState()
    val crosshairPosition by viewModel.crosshairPosition.collectAsState()
    val gridEnabled by viewModel.gridEnabled.collectAsState()
    val sliderMode by viewModel.sliderMode.collectAsState()
    val exposureMode by viewModel.exposureMode.collectAsState()
    val exposureValue by viewModel.exposureValue.collectAsState()
    val isoValue by viewModel.isoValue.collectAsState()
    val shutterSpeedNs by viewModel.shutterSpeedNs.collectAsState()
    val isFocusButtonHeld by viewModel.isFocusButtonHeld.collectAsState()
    val outputFormat by viewModel.outputFormat.collectAsState()
    val flashEnabled by viewModel.flashEnabled.collectAsState()
    
    val publicSans = FontFamily(
        Font(R.font.publicsans_variablefont_wght)
    )
    
    // Override density to disable font scaling - makes all sizes constant
    val density = LocalDensity.current
    val fixedDensity = Density(density.density, fontScale = 0.85f)

    CompositionLocalProvider(LocalDensity provides fixedDensity) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Left toolbar (fixed width)
        Column(
            modifier = Modifier
                .width(60.dp)
                .fillMaxHeight()
                .background(Color.Black),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top section - Mode-dependent controls
            Column(
                modifier = Modifier.padding(top = 16.dp)
            ) {
                // Auto mode: Show exposure compensation
                if (exposureMode == CameraViewModel.ExposureMode.AUTO) {
                    // Exposure icon and value group
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .width(60.dp)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleExposurePanel()
                            }
                            .padding(vertical = 0.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.exposure_stroke_rounded),
                            contentDescription = "Exposure compensation",
                            tint = if (sliderMode == CameraViewModel.SliderMode.EXPOSURE) Color.White.copy(alpha = 1f)
                            else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(32.dp)
                                .rotate(90f)
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        Text(
                            text = when {
                                exposureValue == 0f -> "0.0"
                                exposureValue > 0 -> "+${exposureValue}"
                                else -> "${exposureValue}"
                            },
                            color = if (sliderMode == CameraViewModel.SliderMode.EXPOSURE) Color.White.copy(alpha = 1f)
                                else Color.White.copy(alpha = 0.7f),
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 32.sp,
                                fontFamily = publicSans,
                                fontWeight = FontWeight.ExtraBold
                            ),
                            modifier = Modifier.rotate(90f)
                        )
                    }
                }
                
                // Manual mode: Show ISO and Shutter Speed
                if (exposureMode == CameraViewModel.ExposureMode.MANUAL) {
                    // ISO label and value combined
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(120.dp)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleIsoPanel()
                            },
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            text = "ISO ${isoValue}",
                            color = if (sliderMode == CameraViewModel.SliderMode.ISO) Color.White.copy(alpha = 1f)
                                else Color.White.copy(alpha = 0.7f),
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 32.sp,
                                fontFamily = publicSans,
                                fontWeight = FontWeight.ExtraBold
                            ),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(
                                        constraints.copy(
                                            minWidth = 0,
                                            maxWidth = Int.MAX_VALUE
                                        )
                                    )
                                    // Swap width and height for rotated layout
                                    layout(placeable.height, placeable.width) {
                                        placeable.place(
                                            x = 0,
                                            y = -placeable.height
                                        )
                                    }
                                }
                                .graphicsLayer(
                                    rotationZ = 90f,
                                    transformOrigin = TransformOrigin(0f, 1f)
                                )
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Shutter speed display
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(77.dp)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleShutterPanel()
                            },
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Text(
                            text = formatShutterSpeed(shutterSpeedNs),
                            color = if (sliderMode == CameraViewModel.SliderMode.SHUTTER) Color.White.copy(alpha = 1f)
                                else Color.White.copy(alpha = 0.7f),
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 32.sp,
                                fontFamily = publicSans,
                                fontWeight = FontWeight.ExtraBold
                            ),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(
                                        constraints.copy(
                                            minWidth = 0,
                                            maxWidth = Int.MAX_VALUE
                                        )
                                    )
                                    layout(placeable.height, placeable.width) {
                                        placeable.place(
                                            x = 0,
                                            y = -placeable.height
                                        )
                                    }
                                }
                                .graphicsLayer(
                                    rotationZ = 90f,
                                    transformOrigin = TransformOrigin(0f, 1f)
                                )
                        )
                    }
                }
            }

            // Bottom section - Mode toggle, Format toggle, and Grid toggle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Exposure mode toggle (A/M)
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.toggleExposureMode()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (exposureMode == CameraViewModel.ExposureMode.AUTO) "A" else "M",
                        color = Color.White,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 32.sp,
                            fontFamily = publicSans,
                            fontWeight = FontWeight.ExtraBold
                        ),
                        modifier = Modifier.rotate(90f)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Output format toggle (RAW/JPG)
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            viewModel.toggleOutputFormat()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = viewModel.getFormatName(outputFormat),
                        color = Color.White,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 32.sp,
                            fontFamily = publicSans,
                            fontWeight = FontWeight.ExtraBold
                        ),
                        modifier = Modifier.rotate(90f)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Flash toggle
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleFlash()
                    },
                    modifier = Modifier.size(60.dp)
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (flashEnabled) R.drawable.flash_stroke_rounded
                            else R.drawable.flash_off_stroke_rounded
                        ),
                        contentDescription = "Toggle flash",
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .size(32.dp)
                            .rotate(90f)
                    )
                }
                
                Spacer(modifier = Modifier.height(0.dp))
                
                // Grid toggle
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleGrid()
                    },
                    modifier = Modifier.size(60.dp)
                ) {
                    Icon(
                        painter = painterResource(
                            id = if (gridEnabled) R.drawable.grid_stroke_rounded
                            else R.drawable.grid_off_stroke_rounded
                        ),
                        contentDescription = "Toggle grid",
                        tint = Color.Unspecified,
                        modifier = Modifier
                            .size(32.dp)
                            .rotate(90f)
                    )
                }
            }
        }

        // Camera preview area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onSizeChanged { size ->
                    viewModel.setScreenDimensions(size.width.toFloat(), size.height.toFloat())
                }
        ) {
            // Camera preview
            AndroidView(
                factory = { ctx ->
                    viewModel.createPreviewView(ctx)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            viewModel.onTapToFocus(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                        }
                    },
                update = { previewView ->
                    viewModel.bindCamera(lifecycleOwner, previewView)
                }
            )

            // Focus crosshair indicator
            crosshairPosition?.let { (x, y) ->
                val density = LocalDensity.current
                val crosshairSizePx = with(density) { 80.dp.toPx() }

                Crosshair(
                    modifier = Modifier.offset(
                        x = with(density) { (x - crosshairSizePx / 2f).toDp() },
                        y = with(density) { (y - crosshairSizePx / 2f).toDp() }
                    )
                )
            }

            // Grid overlay (rule of thirds)
            if (gridEnabled) {
                GridOverlay()
            }

            // Slider (overlaid on top of viewfinder) - changes based on mode
            when (sliderMode) {
                CameraViewModel.SliderMode.EXPOSURE -> {
                    ExposureSlider(
                        value = exposureValue,
                        onValueChange = { viewModel.setExposureValue(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
                CameraViewModel.SliderMode.ISO -> {
                    IsoSlider(
                        value = isoValue,
                        onValueChange = { viewModel.setIsoValue(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
                CameraViewModel.SliderMode.SHUTTER -> {
                    ShutterSpeedSlider(
                        value = shutterSpeedNs,
                        onValueChange = { viewModel.setShutterSpeed(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    )
                }
                CameraViewModel.SliderMode.NONE -> {}
            }

            // Shutter flash effect
            androidx.compose.animation.AnimatedVisibility(
                visible = showFlash,
                enter = fadeIn(animationSpec = tween(50)),
                exit = fadeOut(animationSpec = tween(100))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }

            // Auto-reset flash after showing
            LaunchedEffect(showFlash) {
                if (showFlash) {
                    delay(50) // Flash duration
                    viewModel.resetShutterFlash()
                }
            }

            // Auto-hide crosshair after delay (only if not holding focus button)
            LaunchedEffect(crosshairPosition, isFocusButtonHeld) {
                if (crosshairPosition != null && !isFocusButtonHeld) {
                    delay(2000) // Show for 2 seconds
                    viewModel.hideCrosshair()
                }
            }
        }
    }
    }
}

@Composable
fun Crosshair(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier.size(80.dp)
    ) {
        val strokeWidth = 2f
        val notchLength = 10f // Length of the notch lines

        val left = 0f
        val top = 0f
        val right = size.width
        val bottom = size.height
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        // Top edge
        drawLine(
            color = Color.White,
            start = Offset(left, top),
            end = Offset(right, top),
            strokeWidth = strokeWidth
        )

        // Right edge
        drawLine(
            color = Color.White,
            start = Offset(right, top),
            end = Offset(right, bottom),
            strokeWidth = strokeWidth
        )

        // Bottom edge
        drawLine(
            color = Color.White,
            start = Offset(right, bottom),
            end = Offset(left, bottom),
            strokeWidth = strokeWidth
        )

        // Left edge
        drawLine(
            color = Color.White,
            start = Offset(left, bottom),
            end = Offset(left, top),
            strokeWidth = strokeWidth
        )

        // Top notch (vertical line pointing down)
        drawLine(
            color = Color.White,
            start = Offset(centerX, top),
            end = Offset(centerX, top + notchLength),
            strokeWidth = strokeWidth
        )

        // Bottom notch (vertical line pointing up)
        drawLine(
            color = Color.White,
            start = Offset(centerX, bottom),
            end = Offset(centerX, bottom - notchLength),
            strokeWidth = strokeWidth
        )

        // Left notch (horizontal line pointing right)
        drawLine(
            color = Color.White,
            start = Offset(left, centerY),
            end = Offset(left + notchLength, centerY),
            strokeWidth = strokeWidth
        )

        // Right notch (horizontal line pointing left)
        drawLine(
            color = Color.White,
            start = Offset(right, centerY),
            end = Offset(right - notchLength, centerY),
            strokeWidth = strokeWidth
        )
    }
}

@Composable
fun GridOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 3f
        val gridColor = Color.White

        // Rule of thirds - divide into 3x3 grid
        val thirdWidth = size.width / 3f
        val thirdHeight = size.height / 3f

        // Vertical lines (2 lines at 1/3 and 2/3)
        drawLine(
            color = gridColor,
            start = Offset(thirdWidth, 0f),
            end = Offset(thirdWidth, size.height),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = gridColor,
            start = Offset(thirdWidth * 2f, 0f),
            end = Offset(thirdWidth * 2f, size.height),
            strokeWidth = strokeWidth
        )

        // Horizontal lines (2 lines at 1/3 and 2/3)
        drawLine(
            color = gridColor,
            start = Offset(0f, thirdHeight),
            end = Offset(size.width, thirdHeight),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = gridColor,
            start = Offset(0f, thirdHeight * 2f),
            end = Offset(size.width, thirdHeight * 2f),
            strokeWidth = strokeWidth
        )
    }
}

@Composable
fun ExposureSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var lastSnappedValue by remember { mutableStateOf(value) }
    
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    // Convert drag position to value (-2 to +2)
                    val fraction = change.position.x / size.width
                    val rawValue = (-2f + (fraction * 4f)).coerceIn(-2f, 2f)
                    // Snap to nearest 0.5 increment
                    val snappedValue = (kotlin.math.round(rawValue * 2f) / 2f).coerceIn(-2f, 2f)
                    if (snappedValue != lastSnappedValue) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        lastSnappedValue = snappedValue
                        onValueChange(snappedValue)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    // Convert tap position to value (-2 to +2)
                    val fraction = offset.x / size.width
                    val rawValue = (-2f + (fraction * 4f)).coerceIn(-2f, 2f)
                    // Snap to nearest 0.5 increment
                    val snappedValue = (kotlin.math.round(rawValue * 2f) / 2f).coerceIn(-2f, 2f)
                    if (snappedValue != lastSnappedValue) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        lastSnappedValue = snappedValue
                        onValueChange(snappedValue)
                    }
                }
            }
    ) {
        val trackStrokeWidth = 3f
        val thumbStrokeWidth = 8f
        val centerY = size.height / 2f
        val notchLength = 16f
        val trackColor = Color.White

        // Main horizontal track line
        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = trackStrokeWidth
        )

        // Draw notches for each EV step (-2, -1.5, -1, -0.5, 0, 0.5, 1, 1.5, 2)
        val evSteps = listOf(-2f, -1.5f, -1f, -0.5f, 0f, 0.5f, 1f, 1.5f, 2f)
        evSteps.forEach { ev ->
            val normalizedValue = (ev + 2f) / 4f  // 0 to 1 (left to right)
            val notchX = normalizedValue * size.width

            // Longer notch for whole numbers, extra long for 0
            val length = when {
                ev == 0f -> notchLength * 2.2f
                ev % 1f == 0f -> notchLength * 1.8f
                else -> notchLength
            }

            drawLine(
                color = trackColor,
                start = Offset(notchX, centerY - length / 2f),
                end = Offset(notchX, centerY + length / 2f),
                strokeWidth = trackStrokeWidth
            )
        }

        // Calculate thumb position (left = -2, right = +2)
        val normalizedValue = (value + 2f) / 4f  // 0 to 1
        val thumbX = normalizedValue * size.width

        // Thumb indicator (vertical line) - white for grabbing
        drawLine(
            color = Color.White,
            start = Offset(thumbX, 0f),
            end = Offset(thumbX, size.height),
            strokeWidth = thumbStrokeWidth
        )
    }
}

@Composable
fun IsoSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var lastSnappedValue by remember { mutableStateOf(value) }
    
    // Logarithmic scale mapping
    fun positionToIso(position: Float): Int {
        // ISO = 100 * 2^(position * 5)
        // Range: 100 to 3200 (100, 200, 400, 800, 1600, 3200)
        val logValue = 100 * 2.0.pow((position * 5).toDouble())
        return logValue.toInt().coerceIn(100, 3200)
    }
    
    fun isoToPosition(iso: Int): Float {
        // position = log2(iso/100) / 5
        val position = log2(iso / 100.0) / 5.0
        return position.toFloat().coerceIn(0f, 1f)
    }
    
    fun snapToNearestIso(iso: Int): Int {
        val validIsos = listOf(100, 200, 400, 800, 1600, 3200)
        return validIsos.minByOrNull { abs(it - iso) } ?: 400
    }
    
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    // Convert drag position to ISO value using logarithmic scale
                    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    val rawValue = positionToIso(fraction)
                    val snappedValue = snapToNearestIso(rawValue)
                    if (snappedValue != lastSnappedValue) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        lastSnappedValue = snappedValue
                        onValueChange(snappedValue)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    // Convert tap position to ISO value using logarithmic scale
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    val rawValue = positionToIso(fraction)
                    val snappedValue = snapToNearestIso(rawValue)
                    if (snappedValue != lastSnappedValue) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        lastSnappedValue = snappedValue
                        onValueChange(snappedValue)
                    }
                }
            }
    ) {
        val trackStrokeWidth = 3f
        val thumbStrokeWidth = 8f
        val centerY = size.height / 2f
        val notchLength = 16f
        val trackColor = Color.White

        // Main horizontal track line
        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = trackStrokeWidth
        )

        // Draw notches for ISO steps (100, 200, 400, 800, 1600, 3200)
        val isoSteps = listOf(100, 200, 400, 800, 1600, 3200)
        isoSteps.forEach { iso ->
            val normalizedValue = isoToPosition(iso)
            val notchX = normalizedValue * size.width

            // Longer notch for common values (400, 800, 1600)
            val length = when (iso) {
                400 -> notchLength * 2.2f  // Extra long for 400 (standard/default)
                800, 1600 -> notchLength * 1.8f
                else -> notchLength
            }

            drawLine(
                color = trackColor,
                start = Offset(notchX, centerY - length / 2f),
                end = Offset(notchX, centerY + length / 2f),
                strokeWidth = trackStrokeWidth
            )
        }

        // Calculate thumb position using logarithmic scale
        val normalizedValue = isoToPosition(value)
        val thumbX = normalizedValue * size.width

        // Thumb indicator (vertical line) - white for grabbing
        drawLine(
            color = Color.White,
            start = Offset(thumbX, 0f),
            end = Offset(thumbX, size.height),
            strokeWidth = thumbStrokeWidth
        )
    }
}

// Helper function to format shutter speed for display
fun formatShutterSpeed(ns: Long): String {
    val seconds = ns / 1_000_000_000.0
    return when {
        seconds >= 1.0 -> "${seconds.toInt()}s"
        else -> "1/${(1.0 / seconds).toInt()}"
    }
}

@Composable
fun ShutterSpeedSlider(
    value: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var lastSnappedValue by remember { mutableStateOf(value) }
    
    // Shutter speeds in nanoseconds (1/1000s to 1s)
    // 1/1000, 1/500, 1/250, 1/125, 1/60, 1/30, 1/15, 1/8, 1/4, 1/2, 1s
    val shutterSpeeds = listOf(
        1_000_000L,      // 1/1000s
        2_000_000L,      // 1/500s
        4_000_000L,      // 1/250s
        8_000_000L,      // 1/125s
        16_666_666L,     // 1/60s
        33_333_333L,     // 1/30s
        66_666_666L,     // 1/15s
        125_000_000L,    // 1/8s
        250_000_000L,    // 1/4s
        500_000_000L,    // 1/2s
        1_000_000_000L   // 1s
    )
    
    fun positionToShutter(position: Float): Long {
        val index = (position * (shutterSpeeds.size - 1)).toInt().coerceIn(0, shutterSpeeds.size - 1)
        return shutterSpeeds[index]
    }
    
    fun shutterToPosition(shutter: Long): Float {
        val index = shutterSpeeds.indexOfFirst { it >= shutter }.takeIf { it >= 0 } ?: (shutterSpeeds.size - 1)
        return index.toFloat() / (shutterSpeeds.size - 1)
    }
    
    fun snapToNearestShutter(ns: Long): Long {
        return shutterSpeeds.minByOrNull { abs(it - ns) } ?: 16_666_666L
    }
    
    Canvas(
        modifier = modifier
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
            }
            .pointerInput(Unit) {
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
            }
    ) {
        val trackStrokeWidth = 3f
        val thumbStrokeWidth = 8f
        val centerY = size.height / 2f
        val notchLength = 16f
        val trackColor = Color.White

        // Main horizontal track line
        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = trackStrokeWidth
        )

        // Draw notches for shutter speed steps
        shutterSpeeds.forEachIndexed { index, speed ->
            val normalizedValue = index.toFloat() / (shutterSpeeds.size - 1)
            val notchX = normalizedValue * size.width

            // Longer notch for common values (1/60, 1/125)
            val length = when (speed) {
                16_666_666L -> notchLength * 2.2f  // 1/60s - common default
                8_000_000L, 33_333_333L -> notchLength * 1.8f  // 1/125s, 1/30s
                else -> notchLength
            }

            drawLine(
                color = trackColor,
                start = Offset(notchX, centerY - length / 2f),
                end = Offset(notchX, centerY + length / 2f),
                strokeWidth = trackStrokeWidth
            )
        }

        // Calculate thumb position
        val normalizedValue = shutterToPosition(value)
        val thumbX = normalizedValue * size.width

        // Thumb indicator (vertical line)
        drawLine(
            color = Color.White,
            start = Offset(thumbX, 0f),
            end = Offset(thumbX, size.height),
            strokeWidth = thumbStrokeWidth
        )
    }
}
