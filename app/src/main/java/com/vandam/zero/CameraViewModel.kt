package com.vandam.zero

import android.content.Context
import android.content.SharedPreferences
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vandam.zero.camera.CameraController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CameraViewModel : ViewModel() {
    private val cameraController = CameraController()
    private var prefs: SharedPreferences? = null

    private val _shutterFlash = MutableStateFlow(false)
    val shutterFlash: StateFlow<Boolean> = _shutterFlash

    private val _crosshairPosition = MutableStateFlow<Pair<Float, Float>?>(null)
    val crosshairPosition: StateFlow<Pair<Float, Float>?> = _crosshairPosition

    private var lastFocusPoint: Pair<Float, Float>? = null
    private var lastFocusTimestamp: Long = 0
    private val focusMemoryTimeoutMs: Long = 3000

    private val _isFocusButtonHeld = MutableStateFlow(false)
    val isFocusButtonHeld: StateFlow<Boolean> = _isFocusButtonHeld

    private val _gridEnabled = MutableStateFlow(false)
    val gridEnabled: StateFlow<Boolean> = _gridEnabled

    private val _flashEnabled = MutableStateFlow(false)
    val flashEnabled: StateFlow<Boolean> = _flashEnabled

    private val _exposureMode = MutableStateFlow(ExposureMode.AUTO)
    val exposureMode: StateFlow<ExposureMode> = _exposureMode

    private val _sliderMode = MutableStateFlow<SliderMode>(SliderMode.NONE)
    val sliderMode: StateFlow<SliderMode> = _sliderMode

    private val _exposureValue = MutableStateFlow(0f)
    val exposureValue: StateFlow<Float> = _exposureValue

    private val _isoValue = MutableStateFlow(400)
    val isoValue: StateFlow<Int> = _isoValue

    private val _shutterSpeedNs = MutableStateFlow(16_666_666L)
    val shutterSpeedNs: StateFlow<Long> = _shutterSpeedNs

    private val _outputFormat = MutableStateFlow(2)
    val outputFormat: StateFlow<Int> = _outputFormat

    private val _availableFormats = MutableStateFlow<List<Int>>(emptyList())
    val availableFormats: StateFlow<List<Int>> = _availableFormats

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

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
        saveSettings()
    }

    fun toggleFlash() {
        _flashEnabled.value = !_flashEnabled.value
        cameraController.setFlashEnabled(_flashEnabled.value)
        saveSettings()
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

    fun toggleExposureMode() {
        val newMode = if (_exposureMode.value == ExposureMode.AUTO) {
            ExposureMode.MANUAL
        } else {
            ExposureMode.AUTO
        }
        setExposureMode(newMode)
    }

    fun setExposureMode(mode: ExposureMode) {
        _exposureMode.value = mode
        _sliderMode.value = SliderMode.NONE

        if (mode == ExposureMode.AUTO) {
            cameraController.setAutoExposure(true)
            cameraController.setExposureCompensation(_exposureValue.value)
        } else {
            cameraController.setManualExposure(_isoValue.value, _shutterSpeedNs.value)
        }
        saveSettings()
    }

    fun setExposureValue(ev: Float) {
        _exposureValue.value = ev.coerceIn(-2f, 2f)
        cameraController.setExposureCompensation(ev)
        saveSettings()
    }

    fun setIsoValue(iso: Int) {
        _isoValue.value = iso.coerceIn(100, 3200)
        cameraController.setManualExposure(iso, _shutterSpeedNs.value)
        saveSettings()
    }

    fun setShutterSpeed(ns: Long) {
        _shutterSpeedNs.value = ns
        cameraController.setManualExposure(_isoValue.value, ns)
        saveSettings()
    }

    fun resetExposureToDefault() {
        setExposureValue(0f)
    }

    fun resetIsoToDefault() {
        setIsoValue(400)
    }

    fun resetShutterSpeedToDefault() {
        setShutterSpeed(16_666_666L)
    }

    fun closeAllPanels() {
        _sliderMode.value = SliderMode.NONE
    }

    fun initialize(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences("camera_settings", Context.MODE_PRIVATE)
            loadSettings()
        }
    }

    private fun loadSettings() {
        prefs?.let { p ->
            _gridEnabled.value = p.getBoolean("grid_enabled", false)
            _flashEnabled.value = p.getBoolean("flash_enabled", false)
            _exposureMode.value = ExposureMode.valueOf(p.getString("exposure_mode", "AUTO") ?: "AUTO")
            _exposureValue.value = p.getFloat("exposure_value", 0f)
            _isoValue.value = p.getInt("iso_value", 400)
            _shutterSpeedNs.value = p.getLong("shutter_speed_ns", 16_666_666L)
            _outputFormat.value = p.getInt("output_format", 2)
        }
    }

    private fun saveSettings() {
        prefs?.edit()?.apply {
            putBoolean("grid_enabled", _gridEnabled.value)
            putBoolean("flash_enabled", _flashEnabled.value)
            putString("exposure_mode", _exposureMode.value.name)
            putFloat("exposure_value", _exposureValue.value)
            putInt("iso_value", _isoValue.value)
            putLong("shutter_speed_ns", _shutterSpeedNs.value)
            putInt("output_format", _outputFormat.value)
            apply()
        }
    }

    fun createPreviewView(context: Context): PreviewView {
        return cameraController.createPreviewView(context)
    }

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        cameraController.setInitialOutputFormat(_outputFormat.value)
        cameraController.setFlashEnabled(_flashEnabled.value)

        cameraController.bindCamera(
            lifecycleOwner,
            previewView,
            onFormatsAvailable = { formats ->
                _availableFormats.value = formats
            },
            onCameraReady = {
                if (_exposureMode.value == ExposureMode.AUTO) {
                    cameraController.setAutoExposure(true)
                    cameraController.setExposureCompensation(_exposureValue.value)
                } else {
                    cameraController.setManualExposure(_isoValue.value, _shutterSpeedNs.value)
                }
            }
        )
    }

    fun toggleOutputFormat() {
        val formats = _availableFormats.value
        if (formats.isEmpty()) return

        val currentIndex = formats.indexOf(_outputFormat.value)
        val nextIndex = (currentIndex + 1) % formats.size
        val newFormat = formats[nextIndex]

        _outputFormat.value = newFormat
        cameraController.setOutputFormat(newFormat)
        saveSettings()
    }

    fun getFormatName(format: Int): String {
        return when (format) {
            0 -> "JPG"
            2 -> "RAW"
            else -> "???"
        }
    }

    fun onShutterButtonPress() {
        if (cameraController.hasPendingCaptures()) {
            return
        }

        lastFocusTimestamp = System.currentTimeMillis()
        _shutterFlash.value = true
        _isSaving.value = true

        viewModelScope.launch {
            cameraController.takePhoto {
                _isSaving.value = false
            }
        }
    }

    fun onFocusButtonPress() {
        _isFocusButtonHeld.value = true

        val currentTime = System.currentTimeMillis()
        if (lastFocusPoint != null && currentTime - lastFocusTimestamp > focusMemoryTimeoutMs) {
            lastFocusPoint = null
        }

        val (focusX, focusY) = if (lastFocusPoint != null) {
            lastFocusPoint!!
        } else if (screenWidth > 0 && screenHeight > 0) {
            val centerX = screenWidth / 2f
            val centerY = screenHeight / 2f
            lastFocusPoint = Pair(centerX, centerY)
            Pair(centerX, centerY)
        } else {
            return
        }

        lastFocusTimestamp = currentTime
        showCrosshair(focusX, focusY)
        cameraController.onTapToFocus(focusX, focusY, screenWidth, screenHeight)
    }

    fun onFocusButtonRelease() {
        _isFocusButtonHeld.value = false
        hideCrosshair()
    }

    fun onTapToFocus(x: Float, y: Float, width: Float, height: Float) {
        lastFocusPoint = Pair(x, y)
        lastFocusTimestamp = System.currentTimeMillis()
        showCrosshair(x, y)
        cameraController.onTapToFocus(x, y, width, height)
    }

    fun hasPendingCaptures(): Boolean {
        return cameraController.hasPendingCaptures()
    }

    override fun onCleared() {
        super.onCleared()
        cameraController.shutdown()
    }
}
