package com.vandam.zero

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
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

    private val _bwMode = MutableStateFlow(false)
    val bwMode: StateFlow<Boolean> = _bwMode

    private val _availableFormats = MutableStateFlow<List<Int>>(emptyList())
    val availableFormats: StateFlow<List<Int>> = _availableFormats

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing

    private val _capturedImageUri = MutableStateFlow<Uri?>(null)
    val capturedImageUri: StateFlow<Uri?> = _capturedImageUri

    private val _capturedImageBitmap = MutableStateFlow<Bitmap?>(null)
    val capturedImageBitmap: StateFlow<Bitmap?> = _capturedImageBitmap

    private val _capturedImageIsPortrait = MutableStateFlow(false)
    val capturedImageIsPortrait: StateFlow<Boolean> = _capturedImageIsPortrait

    private var appContext: Context? = null

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

    fun setCapturedImageUri(uri: Uri?) {
        _capturedImageUri.value = uri
    }

    fun clearCapturedImageUri() {
        _capturedImageUri.value = null
        _capturedImageBitmap.value = null
        _capturedImageIsPortrait.value = false
    }

    private fun extractDngThumbnail(uri: Uri): Bitmap? {
        val context = appContext ?: return null
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val exif = ExifInterface(inputStream)
            val thumbnail = exif.thumbnailBitmap
            if (thumbnail != null) {
                return thumbnail
            }
        }
        
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }
        
        val targetSize = 400
        val sampleSize = maxOf(1, minOf(options.outWidth, options.outHeight) / targetSize)
        
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, decodeOptions)
        }
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
        appContext = context.applicationContext
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
            _bwMode.value = p.getBoolean("bw_mode", false)
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
            putBoolean("bw_mode", _bwMode.value)
            apply()
        }
    }

    fun createPreviewView(context: Context): PreviewView {
        return cameraController.createPreviewView(context)
    }

    private val bwFormatCode = -1

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        // If BW mode is active, force JPEG for capture while keeping the format toggle state consistent
        if (_bwMode.value) {
            _outputFormat.value = 0
        }
        cameraController.setInitialOutputFormat(_outputFormat.value)
        cameraController.setFlashEnabled(_flashEnabled.value)
        cameraController.setBwMode(_bwMode.value)

        cameraController.bindCamera(
            lifecycleOwner,
            previewView,
            onFormatsAvailable = { formats ->
                val withBwOption = if (formats.contains(0)) formats + bwFormatCode else formats
                _availableFormats.value = withBwOption
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

        val currentOption = if (_bwMode.value) bwFormatCode else _outputFormat.value
        val currentIndex = formats.indexOf(currentOption).takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + 1) % formats.size
        val newOption = formats[nextIndex]

        if (newOption == bwFormatCode) {
            _bwMode.value = true
            _outputFormat.value = 0
            cameraController.setOutputFormat(0)
            cameraController.setBwMode(true)
        } else {
            _bwMode.value = false
            _outputFormat.value = newOption
            cameraController.setBwMode(false)
            cameraController.setOutputFormat(newOption)
        }
        saveSettings()
    }

    fun setOutputFormat(format: Int) {
        _outputFormat.value = format
        cameraController.setOutputFormat(format)
        saveSettings()
    }

    fun getFormatName(format: Int): String {
        return when (format) {
            0 -> "JPG"
            2 -> "RAW"
            bwFormatCode -> "BW"
            else -> "???"
        }
    }

    fun onShutterButtonPress() {
        if (cameraController.hasPendingCaptures()) {
            return
        }

        lastFocusTimestamp = System.currentTimeMillis()
        _isCapturing.value = true

        viewModelScope.launch {
            cameraController.takePhoto(
                onCaptureStarted = {
                    _isCapturing.value = false
                    _isSaving.value = true
                    _shutterFlash.value = true
                },
                onPreviewReady = { bitmap ->
                    if (bitmap != null) {
                        _capturedImageIsPortrait.value = bitmap.height > bitmap.width
                        _capturedImageBitmap.value = bitmap
                        if (_outputFormat.value != 2) {
                            _isSaving.value = false
                        }
                    }
                },
                onComplete = { uri ->
                    if (uri != null && _outputFormat.value == 2) {
                        val thumbnail = extractDngThumbnail(uri)
                        if (thumbnail != null) {
                            _capturedImageIsPortrait.value = thumbnail.height > thumbnail.width
                            _capturedImageBitmap.value = thumbnail
                        }
                        _isSaving.value = false
                    }
                }
            )
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
