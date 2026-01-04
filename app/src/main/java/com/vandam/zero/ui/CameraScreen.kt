package com.vandam.zero.ui

import android.Manifest
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import coil.compose.rememberAsyncImagePainter
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Density
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.TextureView
import androidx.compose.ui.viewinterop.AndroidView
import com.vandam.zero.R
import com.vandam.zero.BuildConfig
import com.vandam.zero.camera.GrayscaleConverter
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vandam.zero.CameraViewModel
import com.vandam.zero.camera.CameraController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.math.log2
import kotlin.math.abs
import kotlin.math.round

fun Modifier.rotateVertically(clockwise: Boolean = true): Modifier {
    val rotateBy = if (clockwise) 90f else -90f

    return layout { measurable, constraints ->
        val swappedConstraints = constraints.copy(
            minWidth = constraints.minHeight,
            maxWidth = constraints.maxHeight,
            minHeight = constraints.minWidth,
            maxHeight = constraints.maxWidth
        )

        val placeable = measurable.measure(swappedConstraints)

        layout(placeable.height, placeable.width) {
            placeable.placeWithLayer(
                x = -(placeable.width / 2) + (placeable.height / 2),
                y = -(placeable.height / 2) + (placeable.width / 2)
            ) {
                rotationZ = rotateBy
            }
        }
    }
}
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun cameraScreen(viewModel: CameraViewModel = viewModel()) {
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

    val publicSans = FontFamily(
        Font(R.font.publicsans_variablefont_wght)
    )

    if (permissionsState.allPermissionsGranted) {
        cameraContent(viewModel)
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Camera permission is required to use this app.",
                color = Color.White,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = TextStyle(
                    fontSize = 32.sp,
                    fontFamily = publicSans,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@Composable
fun cameraContent(viewModel: CameraViewModel) {
    val context = LocalContext.current
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
    val previewEnabled by viewModel.previewEnabled.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()
    val bwMode by viewModel.bwMode.collectAsState()
    val colorMode by viewModel.colorMode.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val capturedImageUri by viewModel.capturedImageUri.collectAsState()
    val capturedImageBitmap by viewModel.capturedImageBitmap.collectAsState()
    val capturedImageIsPortrait by viewModel.capturedImageIsPortrait.collectAsState()
    val isFast by viewModel.isFastMode.collectAsState()
    val cameraHidden by viewModel.cameraHidden.collectAsState()
    val publicSans = FontFamily( Font(R.font.publicsans_variablefont_wght))
    val density = LocalDensity.current
    val fixedDensity = Density(density.density, fontScale = 0.85f)
    val exposureText = if (exposureValue == 0f) "0.0" else "%+.1f".format(exposureValue)
    val isBusy = isCapturing || isSaving
    val isRawMode = outputFormat == CameraController.OUTPUT_FORMAT_RAW

    val toolbarTextStyle = TextStyle(
        fontSize = 32.sp,
        fontFamily = publicSans,
        fontWeight = FontWeight.ExtraBold,
    )

    CompositionLocalProvider(LocalDensity provides fixedDensity) {
        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .background(Color.Black)
                    .width(60.dp)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (exposureMode == CameraViewModel.ExposureMode.AUTO) {
                    // Auto: Exposure Compensation
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.toggleExposurePanel()
                                    },
                                    onLongPress = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.resetExposureToDefault()
                                    }
                                )
                            }
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

                        Spacer(modifier = Modifier.height(4.dp))

                        Box(
                            modifier = Modifier .rotateVertically(clockwise = true),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = exposureText,
                                style = toolbarTextStyle,
                                color = if (sliderMode == CameraViewModel.SliderMode.EXPOSURE) Color.White.copy(alpha = 1f)
                                    else Color.White.copy(alpha = 0.7f),
                            )
                        }
                    }
                }

                // Manual: ISO and Shutter Speed
                if (exposureMode == CameraViewModel.ExposureMode.MANUAL) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .rotateVertically(clockwise = true)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.toggleIsoPanel()
                                        },
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.resetIsoToDefault()
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$isoValue",
                                style = toolbarTextStyle,
                                color = if (sliderMode == CameraViewModel.SliderMode.ISO) Color.White.copy(alpha = 1f)
                                    else Color.White.copy(alpha = 0.7f),
                            )
                        }

                        Box(
                            modifier = Modifier
                                .rotateVertically(clockwise = true)
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.toggleShutterPanel()
                                        },
                                        onLongPress = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.resetShutterSpeedToDefault()
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = formatShutterSpeed(shutterSpeedNs),
                                style = toolbarTextStyle,
                                color = if (sliderMode == CameraViewModel.SliderMode.SHUTTER) Color.White.copy(alpha = 1f)
                                    else Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .rotateVertically(clockwise = true)
                            .clickable(enabled = !isBusy) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleExposureMode()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (exposureMode == CameraViewModel.ExposureMode.AUTO) "A" else "M",
                            style = toolbarTextStyle,
                            color = Color.White
                        )
                    }

                    Box(
                        modifier = Modifier
                            .rotateVertically(clockwise = true)
                            .clickable(enabled = !isBusy && !isRawMode) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleColorMode()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isRawMode) "RGB" else if (colorMode) "BW" else "RGB",
                            color = if (isRawMode) Color.White.copy(alpha = 0.3f) else Color.White,
                            style = toolbarTextStyle
                        )
                    }

                    if (!BuildConfig.MONOCHROME_MODE) {
                        Box(
                            modifier = Modifier
                                .rotateVertically(clockwise = true)
                                .pointerInput(isBusy) {
                                    detectTapGestures(
                                        onTap = {
                                            if (isBusy) return@detectTapGestures
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.toggleOutputFormat()
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val label = viewModel.getFormatName(outputFormat, fastMode = isFast)
                            Text(
                                text = label,
                                color = Color.White,
                                style = toolbarTextStyle
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .rotateVertically(clockwise = true)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.toggleFlash()
                                    },
                                    onLongPress = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.toggleCameraHidden()
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (flashEnabled) R.drawable.flash_stroke_rounded
                                else R.drawable.flash_off_stroke_rounded
                            ),
                            contentDescription = "Toggle flash",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .rotateVertically(clockwise = true)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.toggleGrid()
                                    },
                                    onLongPress = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.togglePreview()
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(
                                id = if (gridEnabled) R.drawable.grid_stroke_rounded
                                else R.drawable.grid_off_stroke_rounded
                            ),
                            contentDescription = "Toggle grid",
                            tint = Color.Unspecified,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .background(Color.Black)
                    .onSizeChanged { size ->
                        viewModel.setScreenDimensions(size.width.toFloat(), size.height.toFloat())
                    }
            ) {
                var textureViewRef by remember { mutableStateOf<TextureView?>(null) }

                // Handle lifecycle for camera pause/resume
                val lifecycleOwner = LocalLifecycleOwner.current
                androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_PAUSE -> {
                                viewModel.onPause()
                            }
                            Lifecycle.Event.ON_RESUME -> {
                                textureViewRef?.let { viewModel.onResume(it) }
                            }
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                AndroidView(
                    factory = { ctx ->
                        viewModel.createPreviewView(ctx).also { textureViewRef = it }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (showFlash || cameraHidden) 0f else 1f }
                        .pointerInput(cameraHidden) {
                            if (!cameraHidden) {
                                detectTapGestures { offset ->
                                    viewModel.onTapToFocus(offset.x, offset.y, size.width.toFloat(), size.height.toFloat())
                                }
                            }
                        },
                    update = { textureView ->
                        viewModel.bindCamera(textureView)
                    }
                )

                if (!showFlash && !cameraHidden) {
                    crosshairPosition?.let { (x, y) ->
                        val density = LocalDensity.current
                        val crosshairSizePx = with(density) { 80.dp.toPx() }

                        crosshair(
                            modifier = Modifier.offset(
                                x = with(density) { (x - crosshairSizePx / 2f).toDp() },
                                y = with(density) { (y - crosshairSizePx / 2f).toDp() }
                            )
                        )
                    }

                    if (gridEnabled) {
                        gridOverlay()
                    }
                }

                when (sliderMode) {
                    CameraViewModel.SliderMode.EXPOSURE -> {
                        exposureSlider(
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
                        isoSlider(
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
                        shutterSpeedSlider(
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

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(60.dp)
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Box() {
                        toastMessage?.let { message ->
                            Text(
                                text = message,
                                color = Color.White,
                                style = toolbarTextStyle,
                                modifier = Modifier.rotateVertically(clockwise = true)
                            )

                            LaunchedEffect(message) {
                                delay(2000)
                                viewModel.clearToastMessage()
                            }
                        }
                    }

                    Text(
                        text = when {
                            isCapturing -> "HOLD"
                            isSaving -> "SAVING"
                            else -> "READY"
                        },
                        color = Color.White,
                        style = toolbarTextStyle,
                        modifier = Modifier.rotateVertically(clockwise = true)
                    )
                }

                LaunchedEffect(showFlash) {
                    if (showFlash) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        delay(50)
                        viewModel.resetShutterFlash()
                    }
                }

                if (previewEnabled) {
                    capturedImageBitmap?.let { bitmap ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(bottom = 24.dp),
                            contentAlignment = Alignment.BottomStart
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Captured photo",
                                modifier = Modifier
                                    .size(200.dp)
                                    .then(
                                        if (!capturedImageIsPortrait) Modifier.rotate(90f) else Modifier
                                    ),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                        }

                        LaunchedEffect(bitmap) {
                            delay(800)
                            viewModel.clearCapturedImageUri()
                        }
                    }
                }

                LaunchedEffect(crosshairPosition, isFocusButtonHeld) {
                    if (crosshairPosition != null && !isFocusButtonHeld) {
                        delay(2000)
                        viewModel.hideCrosshair()
                    }
                }
            }
        }
    }
}

@Composable
fun crosshair(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier.size(80.dp)
    ) {
        val strokeWidth = 2f
        val notchLength = 10f

        val left = 0f
        val top = 0f
        val right = size.width
        val bottom = size.height
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        drawLine(
            color = Color.White,
            start = Offset(left, top),
            end = Offset(right, top),
            strokeWidth = strokeWidth
        )

        drawLine(
            color = Color.White,
            start = Offset(right, top),
            end = Offset(right, bottom),
            strokeWidth = strokeWidth
        )

        drawLine(
            color = Color.White,
            start = Offset(right, bottom),
            end = Offset(left, bottom),
            strokeWidth = strokeWidth
        )

        drawLine(
            color = Color.White,
            start = Offset(left, bottom),
            end = Offset(left, top),
            strokeWidth = strokeWidth
        )

        drawLine(
            color = Color.White,
            start = Offset(centerX, top),
            end = Offset(centerX, top + notchLength),
            strokeWidth = strokeWidth
        )

        drawLine(
            color = Color.White,
            start = Offset(centerX, bottom),
            end = Offset(centerX, bottom - notchLength),
            strokeWidth = strokeWidth
        )

        drawLine(
            color = Color.White,
            start = Offset(left, centerY),
            end = Offset(left + notchLength, centerY),
            strokeWidth = strokeWidth
        )

        drawLine(
            color = Color.White,
            start = Offset(right, centerY),
            end = Offset(right - notchLength, centerY),
            strokeWidth = strokeWidth
        )
    }
}

@Composable
fun gridOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 3f
        val gridColor = Color.White

        val thirdWidth = size.width / 3f
        val thirdHeight = size.height / 3f

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
fun exposureSlider(
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
                    val fraction = change.position.x / size.width
                    val rawValue = (-2f + (fraction * 4f)).coerceIn(-2f, 2f)
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
                    val fraction = offset.x / size.width
                    val rawValue = (-2f + (fraction * 4f)).coerceIn(-2f, 2f)
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

        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = trackStrokeWidth
        )

        val evSteps = listOf(-2f, -1.5f, -1f, -0.5f, 0f, 0.5f, 1f, 1.5f, 2f)
        evSteps.forEach { ev ->
            val normalizedValue = (ev + 2f) / 4f
            val notchX = normalizedValue * size.width

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

        val normalizedValue = (value + 2f) / 4f
        val thumbX = normalizedValue * size.width

        drawLine(
            color = Color.White,
            start = Offset(thumbX, 0f),
            end = Offset(thumbX, size.height),
            strokeWidth = thumbStrokeWidth
        )
    }
}

@Composable
fun isoSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var lastSnappedValue by remember { mutableStateOf(value) }

    fun positionToIso(position: Float): Int {
        val logValue = 100 * 2.0.pow((position * 5).toDouble())
        return logValue.toInt().coerceIn(100, 3200)
    }

    fun isoToPosition(iso: Int): Float {
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

        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = trackStrokeWidth
        )

        val isoSteps = listOf(100, 200, 400, 800, 1600, 3200)
        isoSteps.forEach { iso ->
            val normalizedValue = isoToPosition(iso)
            val notchX = normalizedValue * size.width

            val length = when (iso) {
                400 -> notchLength * 2.2f
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

        val normalizedValue = isoToPosition(value)
        val thumbX = normalizedValue * size.width

        drawLine(
            color = Color.White,
            start = Offset(thumbX, 0f),
            end = Offset(thumbX, size.height),
            strokeWidth = thumbStrokeWidth
        )
    }
}

fun formatShutterSpeed(ns: Long): String {
    val seconds = ns / 1_000_000_000.0
    return when {
        seconds >= 1.0 -> "${seconds.toInt()}s"
        else -> "1/${(1.0 / seconds).toInt()}"
    }
}

@Composable
fun shutterSpeedSlider(
    value: Long,
    onValueChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    var lastSnappedValue by remember { mutableStateOf(value) }

    val shutterSpeeds = listOf(
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
        1_000_000_000L
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

        drawLine(
            color = trackColor,
            start = Offset(0f, centerY),
            end = Offset(size.width, centerY),
            strokeWidth = trackStrokeWidth
        )

        shutterSpeeds.forEachIndexed { index, speed ->
            val normalizedValue = index.toFloat() / (shutterSpeeds.size - 1)
            val notchX = normalizedValue * size.width

            val length = when (speed) {
                16_666_666L -> notchLength * 2.2f
                8_000_000L, 33_333_333L -> notchLength * 1.8f
                else -> notchLength
            }

            drawLine(
                color = trackColor,
                start = Offset(notchX, centerY - length / 2f),
                end = Offset(notchX, centerY + length / 2f),
                strokeWidth = trackStrokeWidth
            )
        }

        val normalizedValue = shutterToPosition(value)
        val thumbX = normalizedValue * size.width

        drawLine(
            color = Color.White,
            start = Offset(thumbX, 0f),
            end = Offset(thumbX, size.height),
            strokeWidth = thumbStrokeWidth
        )
    }
}
