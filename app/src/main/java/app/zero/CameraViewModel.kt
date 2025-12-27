package app.zero

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.zero.camera.CameraController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CameraViewModel : ViewModel() {
    private val cameraController = CameraController()

    private val _shutterFlash = MutableStateFlow(false)
    val shutterFlash: StateFlow<Boolean> = _shutterFlash

    // Crosshair position (null = hidden, Pair = x,y coordinates in pixels)
    private val _crosshairPosition = MutableStateFlow<Pair<Float, Float>?>(null)
    val crosshairPosition: StateFlow<Pair<Float, Float>?> = _crosshairPosition

    // Grid overlay toggle
    private val _gridEnabled = MutableStateFlow(false)
    val gridEnabled: StateFlow<Boolean> = _gridEnabled

    // Exposure compensation
    private val _exposureExpanded = MutableStateFlow(false)
    val exposureExpanded: StateFlow<Boolean> = _exposureExpanded

    private val _exposureValue = MutableStateFlow(0f)  // -2.0 to +2.0 EV
    val exposureValue: StateFlow<Float> = _exposureValue

    // Store screen dimensions for center focus
    private var screenWidth: Float = 0f
    private var screenHeight: Float = 0f

    fun resetShutterFlash() {
        _shutterFlash.value = false
    }

    fun showCrosshair(x: Float, y: Float) {
        _crosshairPosition.value = Pair(x, y)
    }

    fun hideCrosshair() {
        _crosshairPosition.value = null
    }

    fun setScreenDimensions(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
    }

    fun toggleGrid() {
        _gridEnabled.value = !_gridEnabled.value
    }

    fun toggleExposurePanel() {
        _exposureExpanded.value = !_exposureExpanded.value
    }

    fun setExposureValue(ev: Float) {
        _exposureValue.value = ev.coerceIn(-2f, 2f)
        cameraController.setExposureCompensation(ev)
    }

    fun closeAllPanels() {
        _exposureExpanded.value = false
    }

    fun createPreviewView(context: Context): PreviewView {
        return cameraController.createPreviewView(context)
    }

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraController.bindCamera(lifecycleOwner, previewView)
    }

    fun onShutterButtonPress() {
        // Trigger flash immediately on button press
        _shutterFlash.value = true

        viewModelScope.launch {
            cameraController.takePhoto()
        }
    }

    fun onFocusButtonPress() {
        // Focus at center and show crosshair there
        if (screenWidth > 0 && screenHeight > 0) {
            val centerX = screenWidth / 2f
            val centerY = screenHeight / 2f
            showCrosshair(centerX, centerY)
        }
        cameraController.triggerFocus()
    }

    fun onFocusButtonRelease() {
        // Optional: cancel focus or unlock
    }

    fun onTapToFocus(x: Float, y: Float, width: Float, height: Float) {
        showCrosshair(x, y)
        cameraController.onTapToFocus(x, y, width, height)
    }
    
    override fun onCleared() {
        super.onCleared()
        cameraController.shutdown()
    }
}
