package app.zero

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zero.camera.CameraController
import kotlinx.coroutines.launch

class CameraViewModel : ViewModel() {
    private val cameraController = CameraController()

    fun createPreviewView(context: Context): PreviewView {
        return cameraController.createPreviewView(context)
    }

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraController.bindCamera(lifecycleOwner, previewView)
    }

    fun onShutterButtonPress() {
        viewModelScope.launch {
            cameraController.takePhoto()
        }
    }

    fun onFocusButtonPress() {
        cameraController.triggerFocus()
    }

    fun onFocusButtonRelease() {
        // Optional: cancel focus or unlock
    }

    fun onTapToFocus(x: Float, y: Float, width: Float, height: Float) {
        cameraController.onTapToFocus(x, y, width, height)
    }
    
    override fun onCleared() {
        super.onCleared()
        cameraController.shutdown()
    }
}
