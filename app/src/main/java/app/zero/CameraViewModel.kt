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

    // Exposure mode (Auto vs Manual)
    private val _exposureMode = MutableStateFlow(ExposureMode.AUTO)
    val exposureMode: StateFlow<ExposureMode> = _exposureMode

    // Active slider mode
    private val _sliderMode = MutableStateFlow<SliderMode>(SliderMode.NONE)
    val sliderMode: StateFlow<SliderMode> = _sliderMode

    // Exposure compensation (Auto mode)
    private val _exposureValue = MutableStateFlow(0f)  // -2.0 to +2.0 EV
    val exposureValue: StateFlow<Float> = _exposureValue

    // ISO (Manual mode)
    private val _isoValue = MutableStateFlow(400)  // 100 to 3200
    val isoValue: StateFlow<Int> = _isoValue

    // Shutter speed in nanoseconds (Manual mode)
    // Common values: 1/1000s, 1/500s, 1/250s, 1/125s, 1/60s, 1/30s, 1/15s
    private val _shutterSpeedNs = MutableStateFlow(16_666_666L)  // 1/60s default
    val shutterSpeedNs: StateFlow<Long> = _shutterSpeedNs

    // Store screen dimensions for center focus
    private var screenWidth: Float = 0f
    private var screenHeight: Float = 0f

    enum class ExposureMode {
        AUTO, MANUAL
    }

    enum class SliderMode {
        NONE, EXPOSURE, ISO, SHUTTER
    }

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
        _sliderMode.value = if (_sliderMode.value == SliderMode.EXPOSURE) {
            SliderMode.NONE
        } else {
            SliderMode.EXPOSURE
        }
    }

    fun toggleIsoPanel() {
        _sliderMode.value = if (_sliderMode.value == SliderMode.ISO) {
            SliderMode.NONE
        } else {
            SliderMode.ISO
        }
    }

    fun toggleShutterPanel() {
        _sliderMode.value = if (_sliderMode.value == SliderMode.SHUTTER) {
            SliderMode.NONE
        } else {
            SliderMode.SHUTTER
        }
    }

    fun setExposureMode(mode: ExposureMode) {
        _exposureMode.value = mode
        _sliderMode.value = SliderMode.NONE
        
        if (mode == ExposureMode.AUTO) {
            // Re-enable auto exposure
            cameraController.setAutoExposure(true)
            // Re-apply exposure compensation
            cameraController.setExposureCompensation(_exposureValue.value)
        } else {
            // Apply current manual settings
            cameraController.setManualExposure(_isoValue.value, _shutterSpeedNs.value)
        }
    }

    fun setExposureValue(ev: Float) {
        _exposureValue.value = ev.coerceIn(-2f, 2f)
        cameraController.setExposureCompensation(ev)
    }

    fun setIsoValue(iso: Int) {
        _isoValue.value = iso.coerceIn(100, 3200)
        cameraController.setManualExposure(iso, _shutterSpeedNs.value)
    }

    fun setShutterSpeed(ns: Long) {
        _shutterSpeedNs.value = ns
        cameraController.setManualExposure(_isoValue.value, ns)
    }

    fun closeAllPanels() {
        _sliderMode.value = SliderMode.NONE
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
