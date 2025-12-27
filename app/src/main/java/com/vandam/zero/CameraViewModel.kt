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

    // Crosshair position (null = hidden, Pair = x,y coordinates in pixels)
    private val _crosshairPosition = MutableStateFlow<Pair<Float, Float>?>(null)
    val crosshairPosition: StateFlow<Pair<Float, Float>?> = _crosshairPosition
    
    // Last focus point - used to remember where user focused
    private var lastFocusPoint: Pair<Float, Float>? = null
    private var lastFocusTimestamp: Long = 0
    private val focusMemoryTimeoutMs: Long = 5000 // Reset to center after 5 seconds of inactivity
    
    // Track if focus button is currently held down
    private val _isFocusButtonHeld = MutableStateFlow(false)
    val isFocusButtonHeld: StateFlow<Boolean> = _isFocusButtonHeld

    // Grid overlay toggle - will be initialized from prefs
    private val _gridEnabled = MutableStateFlow(false)
    val gridEnabled: StateFlow<Boolean> = _gridEnabled
    
    // Flash toggle - will be initialized from prefs
    private val _flashEnabled = MutableStateFlow(false)
    val flashEnabled: StateFlow<Boolean> = _flashEnabled

    // Exposure mode (Auto vs Manual) - will be initialized from prefs
    private val _exposureMode = MutableStateFlow(ExposureMode.AUTO)
    val exposureMode: StateFlow<ExposureMode> = _exposureMode

    // Active slider mode
    private val _sliderMode = MutableStateFlow<SliderMode>(SliderMode.NONE)
    val sliderMode: StateFlow<SliderMode> = _sliderMode

    // Exposure compensation (Auto mode) - will be initialized from prefs
    private val _exposureValue = MutableStateFlow(0f)  // -2.0 to +2.0 EV
    val exposureValue: StateFlow<Float> = _exposureValue

    // ISO (Manual mode) - will be initialized from prefs
    private val _isoValue = MutableStateFlow(400)  // 100 to 3200
    val isoValue: StateFlow<Int> = _isoValue

    // Shutter speed in nanoseconds (Manual mode) - will be initialized from prefs
    // Common values: 1/1000s, 1/500s, 1/250s, 1/125s, 1/60s, 1/30s, 1/15s
    private val _shutterSpeedNs = MutableStateFlow(16_666_666L)  // 1/60s default
    val shutterSpeedNs: StateFlow<Long> = _shutterSpeedNs
    
    // Output format (RAW or JPEG) - will be initialized from prefs
    private val _outputFormat = MutableStateFlow(2)  // Start with RAW (format 2)
    val outputFormat: StateFlow<Int> = _outputFormat
    
    // Available formats on this device
    private val _availableFormats = MutableStateFlow<List<Int>>(emptyList())
    val availableFormats: StateFlow<List<Int>> = _availableFormats
    
    init {
        // Settings will be loaded synchronously before UI renders
    }

    // Store screen dimensions for center focus
    private var screenWidth: Float = 0f
    private var screenHeight: Float = 0f
    
    // Debounce for shutter button
    private var lastShotTimestamp: Long = 0

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
            // Re-enable auto exposure
            cameraController.setAutoExposure(true)
            // Re-apply exposure compensation
            cameraController.setExposureCompensation(_exposureValue.value)
        } else {
            // Apply current manual settings
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
            // Load all settings synchronously before UI renders
            _gridEnabled.value = p.getBoolean("grid_enabled", false)
            _flashEnabled.value = p.getBoolean("flash_enabled", false)
            _exposureMode.value = ExposureMode.valueOf(p.getString("exposure_mode", "AUTO") ?: "AUTO")
            _exposureValue.value = p.getFloat("exposure_value", 0f)
            _isoValue.value = p.getInt("iso_value", 400)
            _shutterSpeedNs.value = p.getLong("shutter_speed_ns", 16_666_666L)
            _outputFormat.value = p.getInt("output_format", 2) // Default to RAW
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
        // Set initial settings before binding so they're used during camera setup
        cameraController.setInitialOutputFormat(_outputFormat.value)
        cameraController.setFlashEnabled(_flashEnabled.value)
        
        cameraController.bindCamera(
            lifecycleOwner, 
            previewView,
            onFormatsAvailable = { formats ->
                // Receive available formats from camera controller
                _availableFormats.value = formats
            },
            onCameraReady = {
                // Apply exposure settings after camera is bound and ready
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
        // Debounce: prevent rapid-fire shots based on exposure settings
        val currentTime = System.currentTimeMillis()
        val minDelayMs = calculateMinimumShotDelay()
        
        if (currentTime - lastShotTimestamp < minDelayMs) {
            // Too soon, ignore this press
            return
        }
        lastShotTimestamp = currentTime
        
        // Taking a photo counts as activity - keep focus point alive
        lastFocusTimestamp = currentTime
        
        // Trigger flash immediately on button press
        _shutterFlash.value = true

        viewModelScope.launch {
            cameraController.takePhoto()
        }
    }
    
    private fun calculateMinimumShotDelay(): Long {
        return when (_exposureMode.value) {
            ExposureMode.MANUAL -> {
                // In manual mode, use shutter speed + 50% buffer
                val shutterSpeedMs = _shutterSpeedNs.value / 1_000_000
                (shutterSpeedMs * 1.5).toLong().coerceAtLeast(200) // At least 200ms
            }
            ExposureMode.AUTO -> {
                // In auto mode, use a reasonable default (camera decides exposure)
                // Allow faster shooting since we don't know the actual exposure time
                300L // 300ms minimum in auto mode
            }
        }
    }

    fun onFocusButtonPress() {
        _isFocusButtonHeld.value = true
        
        // Check if focus memory has expired
        val currentTime = System.currentTimeMillis()
        if (lastFocusPoint != null && currentTime - lastFocusTimestamp > focusMemoryTimeoutMs) {
            // Timeout expired, reset to center
            lastFocusPoint = null
        }
        
        // Use the last focus point if available, otherwise focus at center
        val (focusX, focusY) = if (lastFocusPoint != null) {
            lastFocusPoint!!
        } else if (screenWidth > 0 && screenHeight > 0) {
            val centerX = screenWidth / 2f
            val centerY = screenHeight / 2f
            // Remember center as the focus point for future half-presses
            lastFocusPoint = Pair(centerX, centerY)
            Pair(centerX, centerY)
        } else {
            return // Can't focus without dimensions
        }
        
        // Update timestamp to keep focus point alive
        lastFocusTimestamp = currentTime
        
        // Show crosshair while focus button is held
        showCrosshair(focusX, focusY)
        
        // Trigger focus at the remembered point
        cameraController.onTapToFocus(focusX, focusY, screenWidth, screenHeight)
    }

    fun onFocusButtonRelease() {
        _isFocusButtonHeld.value = false
        // Hide crosshair when focus button is released
        hideCrosshair()
    }

    fun onTapToFocus(x: Float, y: Float, width: Float, height: Float) {
        // Remember this focus point for later use (e.g., when half-pressing shutter)
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
