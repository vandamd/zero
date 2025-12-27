package app.zero.ui

import android.Manifest
import android.os.Build
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import app.zero.CameraViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

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
}
