package com.vandam.zero.ui

import android.Manifest
import android.os.Build
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.vandam.zero.BuildConfig
import com.vandam.zero.CameraViewModel
import com.vandam.zero.R
import com.vandam.zero.camera.CameraController
import com.vandam.zero.ui.components.Crosshair
import com.vandam.zero.ui.components.ExposureSlider
import com.vandam.zero.ui.components.GridOverlay
import com.vandam.zero.ui.components.IsoSlider
import com.vandam.zero.ui.components.ShutterSpeedSlider
import com.vandam.zero.ui.components.formatShutterSpeed
import com.vandam.zero.ui.components.rotateVertically
import com.vandam.zero.ui.theme.CameraColors
import com.vandam.zero.ui.theme.CameraDimens
import com.vandam.zero.ui.theme.CameraTiming
import com.vandam.zero.ui.theme.CameraTypography
import kotlinx.coroutines.delay

/**
 * Main camera screen composable that handles permissions and displays the camera UI.
 *
 * @param viewModel The CameraViewModel instance.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(viewModel: CameraViewModel = viewModel()) {
    val permissions =
        mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= 28) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

    val permissionsState = rememberMultiplePermissionsState(permissions = permissions)

    LaunchedEffect(Unit) {
        permissionsState.launchMultiplePermissionRequest()
    }

    if (permissionsState.allPermissionsGranted) {
        CameraContent(viewModel)
    } else {
        PermissionDeniedContent()
    }
}

/**
 * Content displayed when camera permission is not granted.
 */
@Composable
private fun PermissionDeniedContent() {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(CameraColors.background)
                .padding(CameraDimens.screenPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Camera permission is required to use this app.",
            color = CameraColors.onSurface,
            textAlign = TextAlign.Center,
            style = CameraTypography.permissionText,
        )
    }
}

/**
 * Main camera content composable containing the camera preview and controls.
 *
 * @param viewModel The CameraViewModel instance.
 */
@Composable
private fun CameraContent(viewModel: CameraViewModel) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val fixedDensity = Density(density.density, fontScale = 0.85f)

    // Collect all state from ViewModel
    val uiState = rememberCameraUiState(viewModel)

    CompositionLocalProvider(LocalDensity provides fixedDensity) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left toolbar
            LeftToolbar(
                uiState = uiState,
                viewModel = viewModel,
                haptic = haptic,
            )

            // Camera preview area
            CameraPreviewArea(
                uiState = uiState,
                viewModel = viewModel,
                haptic = haptic,
            )
        }
    }
}

/**
 * Data class holding all camera UI state.
 */
private data class CameraUiState(
    val showFlash: Boolean,
    val crosshairPosition: Pair<Float, Float>?,
    val gridEnabled: Boolean,
    val sliderMode: CameraViewModel.SliderMode,
    val exposureMode: CameraViewModel.ExposureMode,
    val exposureValue: Float,
    val isoValue: Int,
    val shutterSpeedNs: Long,
    val isFocusButtonHeld: Boolean,
    val outputFormat: Int,
    val flashEnabled: Boolean,
    val previewEnabled: Boolean,
    val toastMessage: String?,
    val colorMode: Boolean,
    val isCapturing: Boolean,
    val isSaving: Boolean,
    val capturedImageBitmap: android.graphics.Bitmap?,
    val capturedImageIsPortrait: Boolean,
    val isFastMode: Boolean,
    val cameraHidden: Boolean,
) {
    val isBusy: Boolean get() = isCapturing || isSaving
    val isRawMode: Boolean get() = outputFormat == CameraController.OUTPUT_FORMAT_RAW
    val exposureText: String get() = if (exposureValue == 0f) "0.0" else "%+.1f".format(exposureValue)
}

/**
 * Collects all camera UI state from the ViewModel.
 */
@Composable
private fun rememberCameraUiState(viewModel: CameraViewModel): CameraUiState {
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
    val colorMode by viewModel.colorMode.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val capturedImageBitmap by viewModel.capturedImageBitmap.collectAsState()
    val capturedImageIsPortrait by viewModel.capturedImageIsPortrait.collectAsState()
    val isFastMode by viewModel.isFastMode.collectAsState()
    val cameraHidden by viewModel.cameraHidden.collectAsState()

    return CameraUiState(
        showFlash = showFlash,
        crosshairPosition = crosshairPosition,
        gridEnabled = gridEnabled,
        sliderMode = sliderMode,
        exposureMode = exposureMode,
        exposureValue = exposureValue,
        isoValue = isoValue,
        shutterSpeedNs = shutterSpeedNs,
        isFocusButtonHeld = isFocusButtonHeld,
        outputFormat = outputFormat,
        flashEnabled = flashEnabled,
        previewEnabled = previewEnabled,
        toastMessage = toastMessage,
        colorMode = colorMode,
        isCapturing = isCapturing,
        isSaving = isSaving,
        capturedImageBitmap = capturedImageBitmap,
        capturedImageIsPortrait = capturedImageIsPortrait,
        isFastMode = isFastMode,
        cameraHidden = cameraHidden,
    )
}

/**
 * Left toolbar containing exposure controls and settings buttons.
 */
@Composable
private fun LeftToolbar(
    uiState: CameraUiState,
    viewModel: CameraViewModel,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    Column(
        modifier =
            Modifier
                .background(CameraColors.background)
                .width(CameraDimens.toolbarWidth)
                .fillMaxHeight()
                .padding(vertical = CameraDimens.toolbarVerticalPadding),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Exposure controls (Auto or Manual)
        when (uiState.exposureMode) {
            CameraViewModel.ExposureMode.AUTO -> {
                AutoExposureControls(
                    exposureText = uiState.exposureText,
                    isActive = uiState.sliderMode == CameraViewModel.SliderMode.EXPOSURE,
                    onTap = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.toggleExposurePanel()
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.resetExposureToDefault()
                    },
                )
            }

            CameraViewModel.ExposureMode.MANUAL -> {
                ManualExposureControls(
                    isoValue = uiState.isoValue,
                    shutterSpeedNs = uiState.shutterSpeedNs,
                    sliderMode = uiState.sliderMode,
                    viewModel = viewModel,
                    haptic = haptic,
                )
            }
        }

        // Settings buttons
        SettingsButtons(
            uiState = uiState,
            viewModel = viewModel,
            haptic = haptic,
        )
    }
}

/**
 * Auto exposure compensation controls.
 */
@Composable
private fun AutoExposureControls(
    exposureText: String,
    isActive: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val textColor = if (isActive) CameraColors.onSurface else CameraColors.onSurfaceVariant

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
            Modifier.pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                )
            },
    ) {
        Icon(
            painter = painterResource(id = R.drawable.exposure_stroke_rounded),
            contentDescription = "Exposure compensation",
            tint = textColor,
            modifier =
                Modifier
                    .size(CameraDimens.toolbarIconSize)
                    .rotate(90f),
        )

        Spacer(modifier = Modifier.height(4.dp))

        Box(
            modifier = Modifier.rotateVertically(clockwise = true),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = exposureText,
                style = CameraTypography.toolbarText,
                color = textColor,
            )
        }
    }
}

/**
 * Manual exposure controls (ISO and Shutter Speed).
 */
@Composable
private fun ManualExposureControls(
    isoValue: Int,
    shutterSpeedNs: Long,
    sliderMode: CameraViewModel.SliderMode,
    viewModel: CameraViewModel,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CameraDimens.toolbarItemSpacing),
    ) {
        // ISO control
        Box(
            modifier =
                Modifier
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
                            },
                        )
                    },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$isoValue",
                style = CameraTypography.toolbarText,
                color =
                    if (sliderMode == CameraViewModel.SliderMode.ISO) {
                        CameraColors.onSurface
                    } else {
                        CameraColors.onSurfaceVariant
                    },
            )
        }

        // Shutter speed control
        Box(
            modifier =
                Modifier
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
                            },
                        )
                    },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = formatShutterSpeed(shutterSpeedNs),
                style = CameraTypography.toolbarText,
                color =
                    if (sliderMode == CameraViewModel.SliderMode.SHUTTER) {
                        CameraColors.onSurface
                    } else {
                        CameraColors.onSurfaceVariant
                    },
            )
        }
    }
}

/**
 * Settings buttons (exposure mode, color mode, format, flash, grid).
 */
@Composable
private fun SettingsButtons(
    uiState: CameraUiState,
    viewModel: CameraViewModel,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CameraDimens.toolbarItemSpacing),
    ) {
        // Auto/Manual toggle
        ToolbarTextButton(
            text = if (uiState.exposureMode == CameraViewModel.ExposureMode.AUTO) "A" else "M",
            enabled = !uiState.isBusy,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.toggleExposureMode()
            },
        )

        // Color mode toggle (BW/RGB)
        ToolbarTextButton(
            text =
                if (uiState.isRawMode) {
                    "RGB"
                } else if (uiState.colorMode) {
                    "BW"
                } else {
                    "RGB"
                },
            enabled = !uiState.isBusy && !uiState.isRawMode,
            alpha = if (uiState.isRawMode) 0.3f else 1f,
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.toggleColorMode()
            },
        )

        // Output format toggle
        if (!BuildConfig.MONOCHROME_MODE) {
            ToolbarTextButton(
                text = viewModel.getFormatName(uiState.outputFormat, fastMode = uiState.isFastMode),
                enabled = !uiState.isBusy,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.toggleOutputFormat()
                },
            )
        }

        // Flash toggle (disabled in HF mode - uses ZSL buffer)
        ToolbarIconButton(
            iconRes =
                if (uiState.flashEnabled) {
                    R.drawable.flash_stroke_rounded
                } else {
                    R.drawable.flash_off_stroke_rounded
                },
            contentDescription = "Toggle flash",
            onTap = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.toggleFlash()
            },
            onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.toggleCameraHidden()
            },
            enabled = !uiState.isFastMode,
        )

        // Grid toggle
        ToolbarIconButton(
            iconRes =
                if (uiState.gridEnabled) {
                    R.drawable.grid_stroke_rounded
                } else {
                    R.drawable.grid_off_stroke_rounded
                },
            contentDescription = "Toggle grid",
            onTap = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.toggleGrid()
            },
            onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.togglePreview()
            },
        )
    }
}

/**
 * Toolbar text button with rotation.
 */
@Composable
private fun ToolbarTextButton(
    text: String,
    enabled: Boolean = true,
    alpha: Float = 1f,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .rotateVertically(clockwise = true)
                .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = CameraTypography.toolbarText,
            color = CameraColors.onSurface.copy(alpha = alpha),
        )
    }
}

/**
 * Toolbar icon button with rotation and gesture support.
 */
@Composable
private fun ToolbarIconButton(
    iconRes: Int,
    contentDescription: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier =
            Modifier
                .rotateVertically(clockwise = true)
                .alpha(if (enabled) 1f else 0.3f)
                .pointerInput(enabled) {
                    if (enabled) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onLongPress = { onLongPress() },
                        )
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = androidx.compose.ui.graphics.Color.Unspecified,
            modifier = Modifier.size(CameraDimens.toolbarIconSize),
        )
    }
}

/**
 * Camera preview area containing the preview, overlays, and sliders.
 */
@Composable
private fun CameraPreviewArea(
    uiState: CameraUiState,
    viewModel: CameraViewModel,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
) {
    Box(
        modifier =
            Modifier
                .fillMaxHeight()
                .background(CameraColors.background)
                .onSizeChanged { size ->
                    viewModel.setScreenDimensions(size.width.toFloat(), size.height.toFloat())
                },
    ) {
        // Camera TextureView
        CameraTextureView(
            uiState = uiState,
            viewModel = viewModel,
        )

        // Overlays (crosshair, grid)
        if (!uiState.showFlash && !uiState.cameraHidden) {
            CrosshairOverlay(crosshairPosition = uiState.crosshairPosition)

            if (uiState.gridEnabled) {
                GridOverlay()
            }
        }

        // Slider controls
        SliderPanel(
            sliderMode = uiState.sliderMode,
            exposureValue = uiState.exposureValue,
            isoValue = uiState.isoValue,
            shutterSpeedNs = uiState.shutterSpeedNs,
            viewModel = viewModel,
        )

        // Right status panel
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            StatusPanel(
                uiState = uiState,
                viewModel = viewModel,
            )
        }

        // Shutter flash effect
        ShutterFlashEffect(
            showFlash = uiState.showFlash,
            haptic = haptic,
            onFlashComplete = { viewModel.resetShutterFlash() },
        )

        // Captured image preview
        if (uiState.previewEnabled) {
            CapturedImagePreview(
                bitmap = uiState.capturedImageBitmap,
                isPortrait = uiState.capturedImageIsPortrait,
                onPreviewTimeout = { viewModel.clearCapturedImageUri() },
            )
        }

        // Auto-hide crosshair
        CrosshairAutoHide(
            crosshairPosition = uiState.crosshairPosition,
            isFocusButtonHeld = uiState.isFocusButtonHeld,
            onHide = { viewModel.hideCrosshair() },
        )
    }
}

/**
 * Camera preview TextureView with lifecycle handling.
 */
@Composable
private fun CameraTextureView(
    uiState: CameraUiState,
    viewModel: CameraViewModel,
) {
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
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
        modifier =
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (uiState.showFlash || uiState.cameraHidden) 0f else 1f }
                .pointerInput(uiState.cameraHidden) {
                    if (!uiState.cameraHidden) {
                        detectTapGestures { offset ->
                            viewModel.onTapToFocus(
                                offset.x,
                                offset.y,
                                size.width.toFloat(),
                                size.height.toFloat(),
                            )
                        }
                    }
                },
        update = { textureView ->
            viewModel.bindCamera(textureView)
        },
    )
}

/**
 * Crosshair focus indicator overlay.
 */
@Composable
private fun CrosshairOverlay(crosshairPosition: Pair<Float, Float>?) {
    crosshairPosition?.let { (x, y) ->
        val density = LocalDensity.current
        val crosshairSizePx = with(density) { CameraDimens.crosshairSize.toPx() }

        Crosshair(
            modifier =
                Modifier.offset(
                    x = with(density) { (x - crosshairSizePx / 2f).toDp() },
                    y = with(density) { (y - crosshairSizePx / 2f).toDp() },
                ),
        )
    }
}

/**
 * Slider panel for exposure, ISO, or shutter speed adjustment.
 */
@Composable
private fun SliderPanel(
    sliderMode: CameraViewModel.SliderMode,
    exposureValue: Float,
    isoValue: Int,
    shutterSpeedNs: Long,
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
) {
    val sliderModifier =
        modifier
            .fillMaxWidth()
            .height(CameraDimens.sliderHeight)
            .padding(
                horizontal = CameraDimens.sliderHorizontalPadding,
                vertical = CameraDimens.sliderVerticalPadding,
            )

    when (sliderMode) {
        CameraViewModel.SliderMode.EXPOSURE -> {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                ExposureSlider(
                    value = exposureValue,
                    onValueChange = { viewModel.setExposureValue(it) },
                    modifier = sliderModifier,
                )
            }
        }

        CameraViewModel.SliderMode.ISO -> {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                IsoSlider(
                    value = isoValue,
                    onValueChange = { viewModel.setIsoValue(it) },
                    modifier = sliderModifier,
                )
            }
        }

        CameraViewModel.SliderMode.SHUTTER -> {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                ShutterSpeedSlider(
                    value = shutterSpeedNs,
                    onValueChange = { viewModel.setShutterSpeed(it) },
                    modifier = sliderModifier,
                )
            }
        }

        CameraViewModel.SliderMode.NONE -> { /* No slider shown */ }
    }
}

/**
 * Right status panel showing toast messages and capture status.
 */
@Composable
private fun StatusPanel(
    uiState: CameraUiState,
    viewModel: CameraViewModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxHeight()
                .width(CameraDimens.statusPanelWidth)
                .padding(vertical = CameraDimens.statusPanelVerticalPadding),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Toast message
        Box {
            uiState.toastMessage?.let { message ->
                Text(
                    text = message,
                    color = CameraColors.onSurface,
                    style = CameraTypography.toolbarText,
                    modifier = Modifier.rotateVertically(clockwise = true),
                )

                LaunchedEffect(message) {
                    delay(CameraTiming.TOAST_DISPLAY_DURATION_MS)
                    viewModel.clearToastMessage()
                }
            }
        }

        // Capture status
        Text(
            text =
                when {
                    uiState.isCapturing -> "HOLD"
                    uiState.isSaving -> "SAVING"
                    else -> "READY"
                },
            color = CameraColors.onSurface,
            style = CameraTypography.toolbarText,
            modifier = Modifier.rotateVertically(clockwise = true),
        )
    }
}

/**
 * Shutter flash visual effect.
 */
@Composable
private fun ShutterFlashEffect(
    showFlash: Boolean,
    haptic: androidx.compose.ui.hapticfeedback.HapticFeedback,
    onFlashComplete: () -> Unit,
) {
    LaunchedEffect(showFlash) {
        if (showFlash) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            delay(CameraTiming.SHUTTER_FLASH_DURATION_MS)
            onFlashComplete()
        }
    }
}

/**
 * Captured image preview thumbnail.
 */
@Composable
private fun CapturedImagePreview(
    bitmap: android.graphics.Bitmap?,
    isPortrait: Boolean,
    onPreviewTimeout: () -> Unit,
) {
    bitmap?.let {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(bottom = CameraDimens.previewBottomPadding),
            contentAlignment = Alignment.BottomStart,
        ) {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Captured photo",
                modifier =
                    Modifier
                        .size(CameraDimens.previewThumbnailSize)
                        .then(if (!isPortrait) Modifier.rotate(90f) else Modifier),
                contentScale = ContentScale.Fit,
            )
        }

        LaunchedEffect(bitmap) {
            delay(CameraTiming.PREVIEW_DISPLAY_DURATION_MS)
            onPreviewTimeout()
        }
    }
}

/**
 * Auto-hide crosshair after timeout.
 */
@Composable
private fun CrosshairAutoHide(
    crosshairPosition: Pair<Float, Float>?,
    isFocusButtonHeld: Boolean,
    onHide: () -> Unit,
) {
    LaunchedEffect(crosshairPosition, isFocusButtonHeld) {
        if (crosshairPosition != null && !isFocusButtonHeld) {
            delay(CameraTiming.CROSSHAIR_HIDE_DELAY_MS)
            onHide()
        }
    }
}
