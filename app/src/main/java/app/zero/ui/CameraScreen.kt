package app.zero.ui

import android.Manifest
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import app.zero.CameraViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import kotlinx.coroutines.delay

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
    val showFlash by viewModel.shutterFlash.collectAsState()
    val crosshairPosition by viewModel.crosshairPosition.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
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

        // Shutter flash effect
        AnimatedVisibility(
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

        // Auto-hide crosshair after delay
        LaunchedEffect(crosshairPosition) {
            if (crosshairPosition != null) {
                delay(2000) // Show for 2 seconds
                viewModel.hideCrosshair()
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
