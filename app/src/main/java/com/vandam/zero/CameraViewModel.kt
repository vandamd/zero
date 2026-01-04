package com.vandam.zero

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vandam.zero.camera.CameraController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CameraViewModel : ViewModel() {
    private var cameraController: CameraController? = null
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

    private val _previewEnabled = MutableStateFlow(true)
    val previewEnabled: StateFlow<Boolean> = _previewEnabled

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage

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

    private val _outputFormat = MutableStateFlow(CameraController.OUTPUT_FORMAT_JPEG)
    val outputFormat: StateFlow<Int> = _outputFormat

    private val _bwMode = MutableStateFlow(false)
    val bwMode: StateFlow<Boolean> = _bwMode

    // Color mode: false = RGB, true = BW (separate from format selection)
    private val _colorMode = MutableStateFlow(false)
    val colorMode: StateFlow<Boolean> = _colorMode

    private val _availableFormats = MutableStateFlow<List<Int>>(emptyList())
    val availableFormats: StateFlow<List<Int>> = _availableFormats

    private val _isFastMode = MutableStateFlow(false)
    val isFastMode: StateFlow<Boolean> = _isFastMode

    private val _cameraHidden = MutableStateFlow(false)
    val cameraHidden: StateFlow<Boolean> = _cameraHidden

    private val _redTextMode = MutableStateFlow(false)
    val redTextMode: StateFlow<Boolean> = _redTextMode

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

    // Benchmark data: (shutterLatencyMs, saveLatencyMs)
    private val _lastBenchmark = MutableStateFlow<Pair<Long, Long>?>(null)
    val lastBenchmark: StateFlow<Pair<Long, Long>?> = _lastBenchmark

    private var appContext: Context? = null

    private var screenWidth: Float = 0f
    private var screenHeight: Float = 0f

    private var priorPreview: Boolean = true
    private var priorFormat: Int = 0

    // Orientation listener
    private var orientationEventListener: OrientationEventListener? = null
    private var currentRotation: Int = Surface.ROTATION_0

    enum class ExposureMode {
        AUTO,
        MANUAL,
    }

    enum class SliderMode {
        NONE,
        EXPOSURE,
        ISO,
        SHUTTER,
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

        val options =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }

        val targetSize = 400
        val sampleSize = maxOf(1, minOf(options.outWidth, options.outHeight) / targetSize)

        val decodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
        return context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, decodeOptions)
        }
    }

    fun showCrosshair(
        x: Float,
        y: Float,
    ) {
        _crosshairPosition.value = Pair(x, y)
    }

    fun hideCrosshair() {
        _crosshairPosition.value = null
    }

    fun setScreenDimensions(
        width: Float,
        height: Float,
    ) {
        screenWidth = width
        screenHeight = height
    }

    fun toggleGrid() {
        _gridEnabled.value = !_gridEnabled.value
        saveSettings()
    }

    fun togglePreview() {
        _previewEnabled.value = !_previewEnabled.value
        _toastMessage.value = if (_previewEnabled.value) "PREVIEW ON" else "PREVIEW OFF"
        saveSettings()
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }

    fun clearBenchmark() {
        _lastBenchmark.value = null
    }

    fun toggleFlash() {
        // Flash not available in HF mode (uses ZSL buffer)
        if (_isFastMode.value) return

        _flashEnabled.value = !_flashEnabled.value
        cameraController?.setFlashEnabled(_flashEnabled.value)
        saveSettings()
    }

    fun toggleCameraHidden() {
        _cameraHidden.value = !_cameraHidden.value
        _toastMessage.value = if (_cameraHidden.value) "VIEWFINDER OFF" else "VIEWFINDER ON"
    }

    fun toggleRedTextMode(): Boolean {
        val context = appContext ?: return false

        // Red mode only available when device display is NOT in grayscale mode
        val isGrayscale = isDisplayGrayscale(context)
        if (isGrayscale) return false

        _redTextMode.value = !_redTextMode.value
        _toastMessage.value = if (_redTextMode.value) "RED MODE ON" else "RED MODE OFF"
        saveSettings()
        return true
    }

    private fun isDisplayGrayscale(context: Context): Boolean =
        try {
            val daltonizerEnabled =
                android.provider.Settings.Secure.getInt(
                    context.contentResolver,
                    "accessibility_display_daltonizer_enabled",
                    0,
                )
            val daltonizerMode =
                android.provider.Settings.Secure.getInt(
                    context.contentResolver,
                    "accessibility_display_daltonizer",
                    -1,
                )
            // Daltonizer mode 0 = grayscale
            daltonizerEnabled == 1 && daltonizerMode == 0
        } catch (e: Exception) {
            false
        }

    fun toggleExposurePanel() {
        _sliderMode.value =
            if (_sliderMode.value == SliderMode.EXPOSURE) {
                SliderMode.NONE
            } else {
                SliderMode.EXPOSURE
            }
    }

    fun toggleIsoPanel() {
        _sliderMode.value =
            if (_sliderMode.value == SliderMode.ISO) {
                SliderMode.NONE
            } else {
                SliderMode.ISO
            }
    }

    fun toggleShutterPanel() {
        _sliderMode.value =
            if (_sliderMode.value == SliderMode.SHUTTER) {
                SliderMode.NONE
            } else {
                SliderMode.SHUTTER
            }
    }

    fun toggleExposureMode() {
        val newMode =
            if (_exposureMode.value == ExposureMode.AUTO) {
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
            cameraController?.setAutoExposure(true, _exposureValue.value)
        } else {
            cameraController?.setManualExposure(_isoValue.value, _shutterSpeedNs.value)
        }
        saveSettings()
    }

    fun setExposureValue(ev: Float) {
        _exposureValue.value = ev.coerceIn(-2f, 2f)
        cameraController?.setExposureCompensation(ev)
        saveSettings()
    }

    fun setIsoValue(iso: Int) {
        _isoValue.value = iso.coerceIn(100, 3200)
        cameraController?.setManualExposure(iso, _shutterSpeedNs.value)
        saveSettings()
    }

    fun setShutterSpeed(ns: Long) {
        _shutterSpeedNs.value = ns
        cameraController?.setManualExposure(_isoValue.value, ns)
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
            _previewEnabled.value = p.getBoolean("preview_enabled", true)
            _flashEnabled.value = p.getBoolean("flash_enabled", false)
            _exposureMode.value = ExposureMode.valueOf(p.getString("exposure_mode", "AUTO") ?: "AUTO")
            _exposureValue.value = p.getFloat("exposure_value", 0f)
            _isoValue.value = p.getInt("iso_value", 400)
            _shutterSpeedNs.value = p.getLong("shutter_speed_ns", 16_666_666L)
            _outputFormat.value = p.getInt("output_format", CameraController.OUTPUT_FORMAT_JPEG)
            _bwMode.value = p.getBoolean("bw_mode", false)
            _colorMode.value = p.getBoolean("color_mode_bw", false)
            _isFastMode.value = p.getBoolean("fast_mode", false)
            _redTextMode.value = p.getBoolean("red_text_mode", false)
        }
    }

    private fun saveSettings() {
        prefs?.edit()?.apply {
            putBoolean("grid_enabled", _gridEnabled.value)
            putBoolean("preview_enabled", _previewEnabled.value)
            putBoolean("flash_enabled", _flashEnabled.value)
            putString("exposure_mode", _exposureMode.value.name)
            putFloat("exposure_value", _exposureValue.value)
            putInt("iso_value", _isoValue.value)
            putLong("shutter_speed_ns", _shutterSpeedNs.value)
            putInt("output_format", _outputFormat.value)
            putBoolean("bw_mode", _bwMode.value)
            putBoolean("color_mode_bw", _colorMode.value)
            putBoolean("fast_mode", _isFastMode.value)
            putBoolean("red_text_mode", _redTextMode.value)
            apply()
        }
    }

    fun createPreviewView(context: Context): TextureView {
        if (cameraController == null) {
            cameraController = CameraController(context)
        }
        return cameraController!!.createPreviewView(context)
    }

    private val hfFormatCode = -2

    fun bindCamera(textureView: TextureView) {
        val context = appContext ?: return

        if (cameraController == null) {
            cameraController = CameraController(context)
        }

        setupOrientationListener(context)

        if (_isFastMode.value) {
            _outputFormat.value = CameraController.OUTPUT_FORMAT_JPEG
        }
        // Apply color mode (BW) for non-RAW formats
        if (_outputFormat.value != CameraController.OUTPUT_FORMAT_RAW) {
            _bwMode.value = _colorMode.value
        } else {
            _bwMode.value = false
        }

        cameraController?.setInitialOutputFormat(_outputFormat.value)
        cameraController?.setFlashEnabled(_flashEnabled.value)
        cameraController?.setBwMode(_bwMode.value)
        cameraController?.setFastMode(_isFastMode.value)

        cameraController?.bindCamera(
            textureView,
            onFormatsAvailable = { formats ->
                // Order: JPG, HF, RAW (color mode is separate)
                val orderedFormats =
                    if (formats.contains(CameraController.OUTPUT_FORMAT_JPEG)) {
                        val hasRaw = formats.contains(CameraController.OUTPUT_FORMAT_RAW)
                        if (hasRaw) {
                            listOf(CameraController.OUTPUT_FORMAT_JPEG, hfFormatCode, CameraController.OUTPUT_FORMAT_RAW)
                        } else {
                            listOf(CameraController.OUTPUT_FORMAT_JPEG, hfFormatCode)
                        }
                    } else {
                        formats
                    }
                _availableFormats.value = orderedFormats
            },
            onCameraReady = {
                if (_exposureMode.value == ExposureMode.AUTO) {
                    cameraController?.setAutoExposure(true, _exposureValue.value)
                } else {
                    cameraController?.setManualExposure(_isoValue.value, _shutterSpeedNs.value)
                }
            },
        )
    }

    private fun setupOrientationListener(context: Context) {
        orientationEventListener?.disable()

        orientationEventListener =
            object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) {
                        return
                    }

                    val rotation =
                        when (orientation) {
                            in 45..134 -> Surface.ROTATION_270
                            in 135..224 -> Surface.ROTATION_180
                            in 225..314 -> Surface.ROTATION_90
                            else -> Surface.ROTATION_0
                        }

                    if (rotation != currentRotation) {
                        currentRotation = rotation
                        cameraController?.setRotation(rotation)
                        Log.d("CameraViewModel", "Rotation updated to: $rotation (orientation: $orientation°)")
                    }
                }
            }

        orientationEventListener?.enable()
    }

    fun toggleOutputFormat() {
        // Don't allow mode changes while capturing or saving
        if (_isCapturing.value || _isSaving.value) return

        val formats = _availableFormats.value
        if (formats.isEmpty()) return

        val currentOption =
            when {
                _isFastMode.value -> hfFormatCode
                else -> _outputFormat.value
            }
        val currentIndex = formats.indexOf(currentOption).takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + 1) % formats.size
        val newOption = formats[nextIndex]

        when (newOption) {
            hfFormatCode -> {
                priorPreview = _previewEnabled.value
                priorFormat = _outputFormat.value

                _isFastMode.value = true
                _outputFormat.value = CameraController.OUTPUT_FORMAT_JPEG
                // Apply color mode for non-RAW
                _bwMode.value = _colorMode.value
                // Flash not compatible with ZSL capture
                _flashEnabled.value = false

                cameraController?.setFastMode(true)
                cameraController?.setBwMode(_bwMode.value)
                cameraController?.setFlashEnabled(false)
                cameraController?.setOutputFormat(CameraController.OUTPUT_FORMAT_JPEG)
            }

            CameraController.OUTPUT_FORMAT_RAW -> {
                if (_isFastMode.value) {
                    _isFastMode.value = false
                    _previewEnabled.value = priorPreview
                    cameraController?.setFastMode(false)
                }
                // RAW mode: always RGB, but don't change colorMode preference
                _bwMode.value = false
                _outputFormat.value = CameraController.OUTPUT_FORMAT_RAW
                cameraController?.setBwMode(false)
                cameraController?.setOutputFormat(CameraController.OUTPUT_FORMAT_RAW)
            }

            else -> {
                if (_isFastMode.value) {
                    _isFastMode.value = false
                    _previewEnabled.value = priorPreview
                    cameraController?.setFastMode(false)
                }
                // Apply saved color mode preference for JPG
                _bwMode.value = _colorMode.value
                _outputFormat.value = newOption
                cameraController?.setBwMode(_bwMode.value)
                cameraController?.setOutputFormat(newOption)
            }
        }
        saveSettings()
    }

    fun setOutputFormat(format: Int) {
        _outputFormat.value = format
        cameraController?.setOutputFormat(format)
        saveSettings()
    }

    fun toggleColorMode() {
        // Don't allow mode changes while capturing or saving
        if (_isCapturing.value || _isSaving.value) return
        // Don't allow toggle when in RAW mode (it's always RGB)
        if (_outputFormat.value == CameraController.OUTPUT_FORMAT_RAW) return

        _colorMode.value = !_colorMode.value
        _bwMode.value = _colorMode.value
        cameraController?.setBwMode(_bwMode.value)
        saveSettings()
    }

    fun isRawMode(): Boolean = _outputFormat.value == CameraController.OUTPUT_FORMAT_RAW

    fun getFormatName(
        format: Int,
        fastMode: Boolean = false,
    ): String {
        if (fastMode) return "HF"
        return when (format) {
            CameraController.OUTPUT_FORMAT_JPEG -> "JPG"
            CameraController.OUTPUT_FORMAT_RAW -> "RAW"
            hfFormatCode -> "HF"
            else -> "???"
        }
    }

    fun onShutterButtonPress() {
        val controller = cameraController ?: return

        if (controller.hasPendingCaptures()) {
            return
        }

        lastFocusTimestamp = System.currentTimeMillis()
        _isCapturing.value = true

        // In fast mode, call directly without viewModelScope.launch to reduce latency
        if (_isFastMode.value) {
            controller.takePhoto(
                onCaptureStarted = {
                    _isCapturing.value = false
                    _isSaving.value = true
                    _shutterFlash.value = true
                },
                onPreviewReady = { bitmap ->
                    if (bitmap != null && _previewEnabled.value) {
                        _capturedImageIsPortrait.value = bitmap.height > bitmap.width
                        _capturedImageBitmap.value = bitmap
                    }
                    _isSaving.value = false
                },
                onComplete = { _ -> },
                onBenchmark = { shutterMs, saveMs ->
                    _lastBenchmark.value = Pair(shutterMs, saveMs)
                },
            )
            return
        }

        viewModelScope.launch {
            controller.takePhoto(
                onCaptureStarted = {
                    _isCapturing.value = false
                    _isSaving.value = true
                    _shutterFlash.value = true
                },
                onPreviewReady = { bitmap ->
                    if (bitmap != null && _previewEnabled.value) {
                        _capturedImageIsPortrait.value = bitmap.height > bitmap.width
                        _capturedImageBitmap.value = bitmap
                    }
                    if (_outputFormat.value != CameraController.OUTPUT_FORMAT_RAW) {
                        _isSaving.value = false
                    }
                },
                onComplete = { uri ->
                    if (uri != null && _outputFormat.value == CameraController.OUTPUT_FORMAT_RAW) {
                        if (_previewEnabled.value) {
                            val thumbnail = extractDngThumbnail(uri)
                            if (thumbnail != null) {
                                _capturedImageIsPortrait.value = thumbnail.height > thumbnail.width
                                _capturedImageBitmap.value = thumbnail
                            }
                        }
                        _isSaving.value = false
                    }
                },
                onBenchmark = { shutterMs, saveMs ->
                    _lastBenchmark.value = Pair(shutterMs, saveMs)
                },
            )
        }
    }

    fun onFocusButtonPress() {
        if (_isFastMode.value) return

        _isFocusButtonHeld.value = true

        val currentTime = System.currentTimeMillis()
        if (lastFocusPoint != null && currentTime - lastFocusTimestamp > focusMemoryTimeoutMs) {
            lastFocusPoint = null
        }

        val (focusX, focusY) =
            if (lastFocusPoint != null) {
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
        cameraController?.onTapToFocus(focusX, focusY, screenWidth, screenHeight)
    }

    fun onFocusButtonRelease() {
        _isFocusButtonHeld.value = false
        hideCrosshair()
    }

    fun onTapToFocus(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        if (_isFastMode.value) return
        lastFocusPoint = Pair(x, y)
        lastFocusTimestamp = System.currentTimeMillis()
        showCrosshair(x, y)
        cameraController?.onTapToFocus(x, y, width, height)
    }

    fun toggleFastMode() {
        // Don't allow mode changes while capturing or saving
        if (_isCapturing.value || _isSaving.value) return

        val enable = !_isFastMode.value

        if (enable) {
            priorPreview = _previewEnabled.value
            priorFormat = _outputFormat.value

            _isFastMode.value = true
            _bwMode.value = false
            _outputFormat.value = CameraController.OUTPUT_FORMAT_JPEG

            cameraController?.setFastMode(true)
            cameraController?.setBwMode(false)
            cameraController?.setOutputFormat(CameraController.OUTPUT_FORMAT_JPEG)
        } else {
            _isFastMode.value = false
            _previewEnabled.value = priorPreview
            _outputFormat.value = priorFormat

            cameraController?.setFastMode(false)
            cameraController?.setOutputFormat(priorFormat)
        }

        saveSettings()
    }

    fun hasPendingCaptures(): Boolean = cameraController?.hasPendingCaptures() ?: false

    fun onPause() {
        orientationEventListener?.disable()
        cameraController?.shutdown()
        cameraController = null
    }

    fun onResume(textureView: TextureView?) {
        val context = appContext ?: return
        val tv = textureView ?: return

        if (cameraController == null) {
            cameraController = CameraController(context)
        }

        setupOrientationListener(context)
        bindCamera(tv)
    }

    override fun onCleared() {
        super.onCleared()
        orientationEventListener?.disable()
        orientationEventListener = null
        cameraController?.shutdown()
    }
}
