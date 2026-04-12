package com.vandam.zero

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vandam.zero.camera.CameraController
import com.vandam.zero.camera.CaptureMode
import com.vandam.zero.camera.VideoPreset
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private val _captureMode = MutableStateFlow(CaptureMode.PHOTO)
    val captureMode: StateFlow<CaptureMode> = _captureMode

    private val _flashEnabled = MutableStateFlow(false)
    val flashEnabled: StateFlow<Boolean> = _flashEnabled

    private val _videoPreset = MutableStateFlow(VideoPreset.FHD30)
    val videoPreset: StateFlow<VideoPreset> = _videoPreset

    private val _availableVideoPresets = MutableStateFlow<List<VideoPreset>>(emptyList())
    val availableVideoPresets: StateFlow<List<VideoPreset>> = _availableVideoPresets

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

    private val _isoRange = MutableStateFlow(100..3200)
    val isoRange: StateFlow<IntRange> = _isoRange

    private var nativeIsoRange: IntRange = 100..1600
    private var nativeShutterRange: LongRange = 1_000_000L..1_000_000_000L

    private val _shutterRange = MutableStateFlow(1_000_000L..1_000_000_000L)
    val shutterRange: StateFlow<LongRange> = _shutterRange

    private val _outputFormat = MutableStateFlow(CameraController.OUTPUT_FORMAT_JPEG)
    val outputFormat: StateFlow<Int> = _outputFormat

    private val _bwMode = MutableStateFlow(false)
    val bwMode: StateFlow<Boolean> = _bwMode

    private val _colorMode = MutableStateFlow(false)
    val colorMode: StateFlow<Boolean> = _colorMode

    private val _availableFormats = MutableStateFlow<List<Int>>(emptyList())
    val availableFormats: StateFlow<List<Int>> = _availableFormats

    private val _isFastMode = MutableStateFlow(false)
    val isFastMode: StateFlow<Boolean> = _isFastMode

    private val _cameraHidden = MutableStateFlow(false)
    val cameraHidden: StateFlow<Boolean> = _cameraHidden

    private val _viewfinderReady = MutableStateFlow(false)
    val viewfinderReady: StateFlow<Boolean> = _viewfinderReady

    private val _redTextMode = MutableStateFlow(false)
    val redTextMode: StateFlow<Boolean> = _redTextMode

    private val _oisEnabled = MutableStateFlow(true)
    val oisEnabled: StateFlow<Boolean> = _oisEnabled

    private val _uiHidden = MutableStateFlow(false)
    val uiHidden: StateFlow<Boolean> = _uiHidden

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing

    private val _isMetering = MutableStateFlow(false)
    val isMetering: StateFlow<Boolean> = _isMetering

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs

    private val _capturedImageUri = MutableStateFlow<Uri?>(null)
    val capturedImageUri: StateFlow<Uri?> = _capturedImageUri

    private val _capturedImageBitmap = MutableStateFlow<Bitmap?>(null)
    val capturedImageBitmap: StateFlow<Bitmap?> = _capturedImageBitmap

    private val _capturedImageIsPortrait = MutableStateFlow(false)
    val capturedImageIsPortrait: StateFlow<Boolean> = _capturedImageIsPortrait

    private val _lastBenchmark = MutableStateFlow<Pair<Long, Long>?>(null)
    val lastBenchmark: StateFlow<Pair<Long, Long>?> = _lastBenchmark

    private var appContext: Context? = null

    private var screenWidth: Float = 0f
    private var screenHeight: Float = 0f

    private var priorPreview: Boolean = true
    private var priorFormat: Int = 0
    private var priorFlash: Boolean = false

    private var photoExposureModeState: ExposureMode = ExposureMode.AUTO
    private var photoExposureValueState: Float = 0f
    private var photoIsoValueState: Int = 400
    private var photoShutterSpeedState: Long = 16_666_666L
    private var photoFlashEnabledState: Boolean = false

    private var videoExposureModeState: ExposureMode = ExposureMode.AUTO
    private var videoExposureValueState: Float = 0f
    private var videoIsoValueState: Int = 400
    private var videoShutterSpeedState: Long = VideoPreset.FHD30.defaultShutterSpeedNs
    private var videoTorchEnabledState: Boolean = false

    private var recordingTimerJob: Job? = null
    private var recordingStartElapsedMs: Long = 0L

    private var orientationEventListener: OrientationEventListener? = null
    private var currentRotation: Int = Surface.ROTATION_0
    private var lastOrientationDegrees: Int = 0

    companion object {
        private const val TAG = "CameraViewModel"
        private const val ORIENTATION_HYSTERESIS_DEGREES = 10
    }

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

    private fun isVideoMode(): Boolean = _captureMode.value == CaptureMode.VIDEO

    private fun persistActiveModeSettings() {
        if (isVideoMode()) {
            videoExposureModeState = _exposureMode.value
            videoExposureValueState = _exposureValue.value
            videoIsoValueState = _isoValue.value
            videoShutterSpeedState = _shutterSpeedNs.value
            videoTorchEnabledState = _flashEnabled.value
        } else {
            photoExposureModeState = _exposureMode.value
            photoExposureValueState = _exposureValue.value
            photoIsoValueState = _isoValue.value
            photoShutterSpeedState = _shutterSpeedNs.value
            photoFlashEnabledState = _flashEnabled.value
        }
    }

    private fun restoreModeSettings(mode: CaptureMode) {
        if (mode == CaptureMode.VIDEO) {
            _exposureMode.value = videoExposureModeState
            _exposureValue.value = videoExposureValueState
            _isoValue.value = videoIsoValueState
            _shutterSpeedNs.value = videoShutterSpeedState
            _flashEnabled.value = videoTorchEnabledState
        } else {
            _exposureMode.value = photoExposureModeState
            _exposureValue.value = photoExposureValueState
            _isoValue.value = photoIsoValueState
            _shutterSpeedNs.value = photoShutterSpeedState
            _flashEnabled.value = photoFlashEnabledState
        }
    }

    private fun updatePhotoIsoRangeForFormat() {
        val isRaw = _outputFormat.value == CameraController.OUTPUT_FORMAT_RAW
        _isoRange.value =
            if (isRaw) {
                nativeIsoRange
            } else {
                nativeIsoRange.first..(nativeIsoRange.last * 32)
            }
        _isoValue.value = _isoValue.value.coerceIn(_isoRange.value.first, _isoRange.value.last)
        photoIsoValueState = _isoValue.value
        _shutterRange.value = nativeShutterRange
        _shutterSpeedNs.value = _shutterSpeedNs.value.coerceIn(_shutterRange.value.first, _shutterRange.value.last)
        photoShutterSpeedState = _shutterSpeedNs.value
    }

    private fun getVideoShutterUpperBound(preset: VideoPreset = _videoPreset.value): Long =
        minOf(nativeShutterRange.last, preset.frameDurationNs)

    private fun updateVideoRanges() {
        _isoRange.value = nativeIsoRange
        _isoValue.value = _isoValue.value.coerceIn(_isoRange.value.first, _isoRange.value.last)
        videoIsoValueState = _isoValue.value

        val upperBound = getVideoShutterUpperBound()
        _shutterRange.value = nativeShutterRange.first..upperBound
        _shutterSpeedNs.value = _shutterSpeedNs.value.coerceIn(_shutterRange.value.first, _shutterRange.value.last)
        videoShutterSpeedState = _shutterSpeedNs.value
    }

    private fun updateRangesForCurrentMode() {
        if (isVideoMode()) {
            updateVideoRanges()
        } else {
            updatePhotoIsoRangeForFormat()
        }
    }

    private fun applyExposureStateToController() {
        if (_exposureMode.value == ExposureMode.AUTO) {
            cameraController?.setAutoExposure(true, _exposureValue.value)
        } else {
            cameraController?.setManualExposure(_isoValue.value, _shutterSpeedNs.value)
        }
    }

    private fun applyModeSettingsToController() {
        val controller = cameraController ?: return

        controller.setCaptureMode(_captureMode.value)
        if (isVideoMode()) {
            controller.setVideoPreset(_videoPreset.value)
            controller.setVideoTorchEnabled(_flashEnabled.value)
        } else {
            controller.setFlashEnabled(_flashEnabled.value)
            controller.setBwMode(_bwMode.value)
            controller.setFastMode(_isFastMode.value)
            controller.setOutputFormat(_outputFormat.value)
        }

        applyExposureStateToController()
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingStartElapsedMs = SystemClock.elapsedRealtime()
        _recordingElapsedMs.value = 0L
        recordingTimerJob =
            viewModelScope.launch {
                while (true) {
                    _recordingElapsedMs.value = SystemClock.elapsedRealtime() - recordingStartElapsedMs
                    delay(250L)
                }
            }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        recordingStartElapsedMs = 0L
        _recordingElapsedMs.value = 0L
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

    fun toggleCaptureMode() {
        if (_isCapturing.value || _isSaving.value || _isRecording.value) return

        if (!isVideoMode() && _availableVideoPresets.value.isEmpty()) {
            _toastMessage.value = "NO VIDEO"
            return
        }

        persistActiveModeSettings()
        _isMetering.value = false
        _captureMode.value =
            if (isVideoMode()) {
                CaptureMode.PHOTO
            } else {
                CaptureMode.VIDEO
            }
        restoreModeSettings(_captureMode.value)
        updateRangesForCurrentMode()
        closeAllPanels()
        hideCrosshair()
        applyModeSettingsToController()
        _toastMessage.value = if (isVideoMode()) "VIDEO" else "PHOTO"
        saveSettings()
    }

    fun togglePreview() {
        _previewEnabled.value = !_previewEnabled.value
        _toastMessage.value = if (_previewEnabled.value) "PREVIEW ON" else "PREVIEW OFF"
        saveSettings()
    }

    fun toggleVideoPreset() {
        if (!isVideoMode()) return
        if (_isRecording.value || _isCapturing.value || _isSaving.value) return

        val presets = _availableVideoPresets.value
        if (presets.isEmpty()) return

        val currentIndex = presets.indexOf(_videoPreset.value).takeIf { it >= 0 } ?: 0
        val nextPreset = presets[(currentIndex + 1) % presets.size]

        _videoPreset.value = nextPreset
        videoShutterSpeedState = videoShutterSpeedState.coerceAtMost(nextPreset.frameDurationNs)
        if (isVideoMode()) {
            _shutterSpeedNs.value = _shutterSpeedNs.value.coerceAtMost(nextPreset.frameDurationNs)
        }
        updateVideoRanges()
        cameraController?.setVideoPreset(nextPreset)
        applyExposureStateToController()
        saveSettings()
    }

    fun clearToastMessage() {
        _toastMessage.value = null
    }

    fun clearBenchmark() {
        _lastBenchmark.value = null
    }

    fun toggleFlash() {
        _flashEnabled.value = !_flashEnabled.value
        if (isVideoMode()) {
            videoTorchEnabledState = _flashEnabled.value
            cameraController?.setVideoTorchEnabled(_flashEnabled.value)
        } else {
            if (_isFastMode.value) {
                _flashEnabled.value = photoFlashEnabledState
                return
            }
            photoFlashEnabledState = _flashEnabled.value
            cameraController?.setFlashEnabled(_flashEnabled.value)
        }
        saveSettings()
    }

    fun toggleCameraHidden() {
        _cameraHidden.value = !_cameraHidden.value
        _toastMessage.value = if (_cameraHidden.value) "VIEWFINDER OFF" else "VIEWFINDER ON"
        saveSettings()
    }

    fun toggleRedTextMode(): Boolean {
        if (isVideoMode()) return false
        val context = appContext ?: return false

        val isGrayscale = isDisplayGrayscale(context)
        if (isGrayscale) return false

        _redTextMode.value = !_redTextMode.value
        _toastMessage.value = if (_redTextMode.value) "RED MODE ON" else "RED MODE OFF"
        saveSettings()
        return true
    }

    fun toggleOis() {
        if (isVideoMode()) return
        _oisEnabled.value = !_oisEnabled.value
        cameraController?.setOisEnabled(_oisEnabled.value)
        _toastMessage.value = if (_oisEnabled.value) "OIS ON" else "OIS OFF"
        saveSettings()
    }

    fun toggleUiHidden() {
        _uiHidden.value = !_uiHidden.value
        if (_uiHidden.value) {
            _cameraHidden.value = false
        }
        _toastMessage.value = if (_uiHidden.value) "UI OFF" else "UI ON"
        saveSettings()
    }

    private fun isDisplayGrayscale(context: Context): Boolean {
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
        return daltonizerEnabled == 1 && daltonizerMode == 0
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
        persistActiveModeSettings()
        applyExposureStateToController()
        saveSettings()
    }

    fun setExposureValue(ev: Float) {
        _exposureValue.value = ev.coerceIn(-2f, 2f)
        persistActiveModeSettings()
        cameraController?.setExposureCompensation(_exposureValue.value)
        saveSettings()
    }

    private var savedEvForMetering: Float = 0f

    fun onMeterButtonPress() {
        if (isVideoMode()) return
        if (_exposureMode.value != ExposureMode.AUTO) return
        if (_isMetering.value) return

        val currentEv = _exposureValue.value
        if (currentEv == 0f) return

        _isMetering.value = true
        savedEvForMetering = currentEv
        cameraController?.setCenterSpotMetering(true, tempEv = 0f)
    }

    fun onMeterButtonRelease() {
        if (isVideoMode()) return
        if (!_isMetering.value) return

        val evToRestore = savedEvForMetering
        _isMetering.value = false
        cameraController?.setCenterSpotMetering(false, tempEv = evToRestore)
    }

    fun setIsoValue(iso: Int) {
        val range = _isoRange.value
        _isoValue.value = iso.coerceIn(range.first, range.last)
        persistActiveModeSettings()
        cameraController?.setManualExposure(_isoValue.value, _shutterSpeedNs.value)
        saveSettings()
    }

    fun setShutterSpeed(ns: Long) {
        val range = _shutterRange.value
        _shutterSpeedNs.value = ns.coerceIn(range.first, range.last)
        persistActiveModeSettings()
        cameraController?.setManualExposure(_isoValue.value, _shutterSpeedNs.value)
        saveSettings()
    }

    fun resetExposureToDefault() {
        setExposureValue(0f)
    }

    fun resetIsoToDefault() {
        setIsoValue(400)
    }

    fun resetShutterSpeedToDefault() {
        setShutterSpeed(if (isVideoMode()) _videoPreset.value.defaultShutterSpeedNs else 16_666_666L)
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
            _captureMode.value = CaptureMode.valueOf(p.getString("capture_mode", CaptureMode.PHOTO.name) ?: CaptureMode.PHOTO.name)
            photoFlashEnabledState = p.getBoolean("flash_enabled", false)
            photoExposureModeState = ExposureMode.valueOf(p.getString("exposure_mode", "AUTO") ?: "AUTO")
            photoExposureValueState = p.getFloat("exposure_value", 0f)
            photoIsoValueState = p.getInt("iso_value", 400)
            photoShutterSpeedState = p.getLong("shutter_speed_ns", 16_666_666L)
            videoTorchEnabledState = p.getBoolean("video_torch_enabled", false)
            videoExposureModeState = ExposureMode.valueOf(p.getString("video_exposure_mode", "AUTO") ?: "AUTO")
            videoExposureValueState = p.getFloat("video_exposure_value", 0f)
            videoIsoValueState = p.getInt("video_iso_value", 400)
            videoShutterSpeedState =
                p.getLong(
                    "video_shutter_speed_ns",
                    VideoPreset.FHD30.defaultShutterSpeedNs,
                )
            val savedVideoPresetName = p.getString("video_preset", VideoPreset.FHD30.name) ?: VideoPreset.FHD30.name
            _videoPreset.value =
                runCatching { VideoPreset.valueOf(savedVideoPresetName) }.getOrDefault(VideoPreset.FHD30)
            _outputFormat.value = p.getInt("output_format", CameraController.OUTPUT_FORMAT_JPEG)
            _bwMode.value = p.getBoolean("bw_mode", false)
            _colorMode.value = p.getBoolean("color_mode_bw", false)
            _isFastMode.value = p.getBoolean("fast_mode", false)
            _redTextMode.value = p.getBoolean("red_text_mode", false)
            _oisEnabled.value = p.getBoolean("ois_enabled", true)
            _uiHidden.value = p.getBoolean("ui_hidden", false)
            _cameraHidden.value = p.getBoolean("camera_hidden", false)
            restoreModeSettings(_captureMode.value)
            if (_uiHidden.value) {
                _toastMessage.value = "UI OFF"
            }
            if (_cameraHidden.value) {
                _toastMessage.value = "VIEWFINDER OFF"
            }
        }
    }

    private fun saveSettings() {
        persistActiveModeSettings()
        prefs?.edit()?.apply {
            putBoolean("grid_enabled", _gridEnabled.value)
            putBoolean("preview_enabled", _previewEnabled.value)
            putString("capture_mode", _captureMode.value.name)
            putBoolean("flash_enabled", photoFlashEnabledState)
            putString("exposure_mode", photoExposureModeState.name)
            putFloat("exposure_value", photoExposureValueState)
            putInt("iso_value", photoIsoValueState)
            putLong("shutter_speed_ns", photoShutterSpeedState)
            putBoolean("video_torch_enabled", videoTorchEnabledState)
            putString("video_exposure_mode", videoExposureModeState.name)
            putFloat("video_exposure_value", videoExposureValueState)
            putInt("video_iso_value", videoIsoValueState)
            putLong("video_shutter_speed_ns", videoShutterSpeedState)
            putString("video_preset", _videoPreset.value.name)
            putInt("output_format", _outputFormat.value)
            putBoolean("bw_mode", _bwMode.value)
            putBoolean("color_mode_bw", _colorMode.value)
            putBoolean("fast_mode", _isFastMode.value)
            putBoolean("red_text_mode", _redTextMode.value)
            putBoolean("ois_enabled", _oisEnabled.value)
            putBoolean("ui_hidden", _uiHidden.value)
            putBoolean("camera_hidden", _cameraHidden.value)
            apply()
        }
    }

    fun createPreviewView(context: Context): TextureView {
        if (cameraController == null) {
            cameraController = CameraController(context)
        }
        _viewfinderReady.value = false
        return cameraController!!.createPreviewView(context)
    }

    private val hfFormatCode = -2

    fun bindCamera(textureView: TextureView) {
        val context = appContext ?: return

        if (cameraController == null) {
            cameraController = CameraController(context)
            _viewfinderReady.value = false
        }

        setupOrientationListener(context)

        if (!isVideoMode() && _isFastMode.value) {
            _outputFormat.value = CameraController.OUTPUT_FORMAT_JPEG
        }

        if (_outputFormat.value != CameraController.OUTPUT_FORMAT_RAW) {
            _bwMode.value = _colorMode.value
        } else {
            _bwMode.value = false
        }

        cameraController?.setInitialOutputFormat(_outputFormat.value)
        cameraController?.setCaptureMode(_captureMode.value)
        cameraController?.setFlashEnabled(photoFlashEnabledState)
        cameraController?.setVideoPreset(_videoPreset.value)
        cameraController?.setVideoTorchEnabled(videoTorchEnabledState)
        cameraController?.setBwMode(_bwMode.value)
        cameraController?.setFastMode(_isFastMode.value)
        cameraController?.setOisEnabled(_oisEnabled.value)

        cameraController?.bindCamera(
            textureView,
            onFormatsAvailable = { formats ->
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
            onVideoPresetsAvailable = { presets ->
                _availableVideoPresets.value = presets
                if (presets.isEmpty() && isVideoMode()) {
                    _captureMode.value = CaptureMode.PHOTO
                    restoreModeSettings(CaptureMode.PHOTO)
                    _toastMessage.value = "PHOTO"
                    applyModeSettingsToController()
                    saveSettings()
                } else if (presets.isNotEmpty() && _videoPreset.value !in presets) {
                    _videoPreset.value = presets.firstOrNull { it.fps == _videoPreset.value.fps } ?: presets.first()
                    videoShutterSpeedState = videoShutterSpeedState.coerceAtMost(_videoPreset.value.frameDurationNs)
                    if (isVideoMode()) {
                        _shutterSpeedNs.value = videoShutterSpeedState
                        updateVideoRanges()
                        cameraController?.setVideoPreset(_videoPreset.value)
                        applyExposureStateToController()
                    }
                    saveSettings()
                }
            },
            onCameraReady = {
                cameraController?.getIsoRange()?.let { sensorRange ->
                    nativeIsoRange = sensorRange
                }
                cameraController?.getExposureTimeRange()?.let { nativeShutterRange = it }
                updateRangesForCurrentMode()
                applyModeSettingsToController()
                _viewfinderReady.value = true
            },
        )
    }

    private fun setupOrientationListener(context: Context) {
        orientationEventListener?.disable()

        val initialRotation =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { context.display?.rotation }.getOrNull()
            } else {
                @Suppress("DEPRECATION")
                (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation
            }

        initialRotation?.let { rotation ->
            currentRotation = rotation
            cameraController?.setRotation(rotation)
            Log.d(TAG, "Rotation initialized to: $rotation")
        }

        orientationEventListener =
            object : OrientationEventListener(context) {
                override fun onOrientationChanged(orientation: Int) {
                    if (orientation == ORIENTATION_UNKNOWN) {
                        return
                    }

                    lastOrientationDegrees = orientation

                    val rotation = calculateRotationWithHysteresis(orientation, currentRotation)

                    if (rotation != currentRotation) {
                        currentRotation = rotation
                        cameraController?.setRotation(rotation)
                        Log.d(TAG, "Rotation updated to: $rotation (orientation: $orientation°)")
                    }
                }
            }

        orientationEventListener?.enable()
    }

    private fun calculateRotationWithHysteresis(
        orientation: Int,
        currentRotation: Int,
    ): Int {
        val h = ORIENTATION_HYSTERESIS_DEGREES

        val distanceFromCurrent =
            when (currentRotation) {
                Surface.ROTATION_0 -> minOf(orientation, 360 - orientation)
                Surface.ROTATION_270 -> kotlin.math.abs(orientation - 90)
                Surface.ROTATION_180 -> kotlin.math.abs(orientation - 180)
                Surface.ROTATION_90 -> kotlin.math.abs(orientation - 270)
                else -> 90
            }

        if (distanceFromCurrent <= 45 + h) {
            return currentRotation
        }

        return when {
            orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
            orientation in 45 until 135 -> Surface.ROTATION_270
            orientation in 135 until 225 -> Surface.ROTATION_180
            else -> Surface.ROTATION_90
        }
    }

    fun toggleOutputFormat() {
        if (isVideoMode()) return
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
                priorFlash = _flashEnabled.value

                _isFastMode.value = true
                _outputFormat.value = CameraController.OUTPUT_FORMAT_JPEG
                _bwMode.value = _colorMode.value
                _flashEnabled.value = false
                photoFlashEnabledState = false
                updatePhotoIsoRangeForFormat()

                cameraController?.setFastMode(true)
                cameraController?.setBwMode(_bwMode.value)
                cameraController?.setFlashEnabled(false)
                cameraController?.setOutputFormat(CameraController.OUTPUT_FORMAT_JPEG)
            }

            CameraController.OUTPUT_FORMAT_RAW -> {
                if (_isFastMode.value) {
                    _isFastMode.value = false
                    _previewEnabled.value = priorPreview
                    _flashEnabled.value = priorFlash
                    cameraController?.setFastMode(false)
                    cameraController?.setFlashEnabled(priorFlash)
                }

                _bwMode.value = false
                _outputFormat.value = CameraController.OUTPUT_FORMAT_RAW
                updatePhotoIsoRangeForFormat()
                cameraController?.setBwMode(false)
                cameraController?.setOutputFormat(CameraController.OUTPUT_FORMAT_RAW)
            }

            else -> {
                if (_isFastMode.value) {
                    _isFastMode.value = false
                    _previewEnabled.value = priorPreview
                    _flashEnabled.value = priorFlash
                    cameraController?.setFastMode(false)
                    cameraController?.setFlashEnabled(priorFlash)
                }

                _bwMode.value = _colorMode.value
                _outputFormat.value = newOption
                updatePhotoIsoRangeForFormat()
                cameraController?.setBwMode(_bwMode.value)
                cameraController?.setOutputFormat(newOption)
            }
        }
        saveSettings()
    }

    fun setOutputFormat(format: Int) {
        if (isVideoMode()) return
        _outputFormat.value = format
        cameraController?.setOutputFormat(format)
        saveSettings()
    }

    fun toggleColorMode() {
        if (isVideoMode()) return
        if (_isCapturing.value || _isSaving.value) return
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

        if (isVideoMode()) {
            closeAllPanels()

            if (_isRecording.value) {
                _isSaving.value = true
                controller.stopVideoRecording(
                    onComplete = {
                        _isRecording.value = false
                        stopRecordingTimer()
                    },
                    onReady = {
                        _isSaving.value = false
                    },
                )
            } else {
                _isCapturing.value = true
                controller.startVideoRecording(
                    onStarted = {
                        _isCapturing.value = false
                        _isRecording.value = true
                        startRecordingTimer()
                    },
                    onError = { reason ->
                        _isCapturing.value = false
                        _isSaving.value = false
                        _isRecording.value = false
                        stopRecordingTimer()
                        _toastMessage.value = reason
                    },
                )
            }
            return
        }

        if (controller.hasPendingCaptures() || _isMetering.value) {
            return
        }

        lastFocusTimestamp = System.currentTimeMillis()
        _isCapturing.value = true

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
                    if (_outputFormat.value == CameraController.OUTPUT_FORMAT_RAW) {
                        if (uri != null && _previewEnabled.value && _capturedImageBitmap.value == null) {
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
        if (isVideoMode()) {
            _isFocusButtonHeld.value = false
            hideCrosshair()
            return
        }
        if (_isFastMode.value) return
        if (_isMetering.value) return

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
        if (!isVideoMode() && _isFastMode.value) return
        if (_isMetering.value) return
        lastFocusPoint = Pair(x, y)
        lastFocusTimestamp = System.currentTimeMillis()
        showCrosshair(x, y)
        cameraController?.onTapToFocus(x, y, width, height)
    }

    fun toggleFastMode() {
        if (isVideoMode()) return
        if (_isCapturing.value || _isSaving.value) return

        val enable = !_isFastMode.value

        if (enable) {
            priorPreview = _previewEnabled.value
            priorFormat = _outputFormat.value

            _isFastMode.value = true
            _bwMode.value = false
            _outputFormat.value = CameraController.OUTPUT_FORMAT_JPEG
            photoFlashEnabledState = _flashEnabled.value
            _flashEnabled.value = false

            cameraController?.setFastMode(true)
            cameraController?.setBwMode(false)
            cameraController?.setFlashEnabled(false)
            cameraController?.setOutputFormat(CameraController.OUTPUT_FORMAT_JPEG)
        } else {
            _isFastMode.value = false
            _previewEnabled.value = priorPreview
            _outputFormat.value = priorFormat
            _flashEnabled.value = photoFlashEnabledState

            cameraController?.setFastMode(false)
            cameraController?.setFlashEnabled(_flashEnabled.value)
            cameraController?.setOutputFormat(priorFormat)
        }

        updatePhotoIsoRangeForFormat()
        saveSettings()
    }

    fun hasPendingCaptures(): Boolean = cameraController?.hasPendingCaptures() ?: false

    fun onPause() {
        orientationEventListener?.disable()
        _viewfinderReady.value = false
        _isCapturing.value = false
        _isSaving.value = false
        _isRecording.value = false
        stopRecordingTimer()
        cameraController?.shutdown()
        cameraController = null
    }

    fun onResume(textureView: TextureView?) {
        val context = appContext ?: return
        val tv = textureView ?: return

        if (cameraController == null) {
            cameraController = CameraController(context)
            _viewfinderReady.value = false
        }

        setupOrientationListener(context)
        bindCamera(tv)
    }

    override fun onCleared() {
        super.onCleared()
        orientationEventListener?.disable()
        orientationEventListener = null
        stopRecordingTimer()
        cameraController?.shutdown()
    }
}
