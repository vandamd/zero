package com.vandam.zero.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import com.vandam.zero.BuildConfig
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class CameraController(
    private val context: Context,
) {
    companion object {
        private const val TAG = "CameraController"
        private const val FILENAME_FORMAT = "'ZERO_'yyyyMMdd_HHmmss"

        private const val HYPERFOCAL_DIOPTERS = 0.45f
        private const val CAMERA_OPEN_TIMEOUT_MS = 2500L
        private const val PREVIEW_TRANSFORM_FRAME_RETRIES = 3
        private const val INITIAL_PREVIEW_SESSION_REFRESH_DELAY_MS = 250L
        private const val PREVIEW_READY_FRAME_UPDATES = 2
        private const val PREVIEW_READY_TIMEOUT_MS = 1000L

        private const val THUMBNAIL_MAX_DIMENSION = 256
        private const val JPEG_QUALITY = 95

        const val OUTPUT_FORMAT_JPEG = 0
        const val OUTPUT_FORMAT_RAW = 2
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraId: String? = null
    private var cameraCharacteristics: CameraCharacteristics? = null

    private var rawImageReader: ImageReader? = null
    private var zslImageReader: ImageReader? = null
    private var thumbnailImageReader: ImageReader? = null

    private var zslEnabled: Boolean = false

    @Volatile private var latestZslImage: Image? = null

    @Volatile private var latestZslRotation: Int = Surface.ROTATION_0
    private val zslLock = Object()

    private var pendingRawImage: Image? = null
    private var pendingThumbnailImage: Image? = null
    private var rawCaptureTimeoutJob: kotlinx.coroutines.Job? = null
    private val rawCaptureLock = Object()
    private val rawCaptureTimeoutMs = 3000L

    @Volatile private var pendingVanillaJpegCapture: Boolean = false

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraOpenCloseLock = Semaphore(1)
    private val cameraManager by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var textureView: TextureView? = null
    private var previewSurface: Surface? = null
    private var previewSize: Size? = null

    @Volatile private var isOpeningCamera: Boolean = false
    private var previewTransformFramesRemaining: Int = 0
    private var didRefreshInitialPreviewSession: Boolean = false
    private var pendingReadySession: CameraCaptureSession? = null
    private var previewFramesUntilReady: Int = 0
    private var previewReadyTimeoutJob: kotlinx.coroutines.Job? = null

    private var captureMode: CaptureMode = CaptureMode.PHOTO
    private var currentOutputFormat: Int = OUTPUT_FORMAT_JPEG
    private var flashEnabled: Boolean = false
    private var videoTorchEnabled: Boolean = false
    private var bwMode: Boolean = false
    private var fastMode: Boolean = false
    private var monoFlavor: Boolean = BuildConfig.MONOCHROME_MODE
    private var oisEnabled: Boolean = true
    private var videoPreset: VideoPreset = VideoPreset.FHD30
    private var supportedVideoPresets: List<VideoPreset> = emptyList()
    private val videoFpsRanges = mutableMapOf<VideoPreset, Range<Int>>()
    private var supportsVideoStabilization: Boolean = false
    private var mediaRecorder: MediaRecorder? = null
    private var recorderSurface: Surface? = null
    private var captureSessionIncludesRecorderSurface: Boolean = false
    private var videoOutputUri: Uri? = null
    private var videoOutputFileDescriptor: ParcelFileDescriptor? = null
    private var pendingVideoRecordingStart: Boolean = false
    private var isVideoRecording: Boolean = false
    private var onVideoRecordingStartedCallback: (() -> Unit)? = null
    private var onVideoRecordingErrorCallback: ((String) -> Unit)? = null

    private var autoExposure: Boolean = true
    private var exposureCompensation: Int = 0
    private var manualIso: Int = 400
    private var manualExposureTimeNs: Long = 16_666_666L

    private var isoRange: android.util.Range<Int>? = null
    private var exposureTimeRange: android.util.Range<Long>? = null
    private var exposureCompensationRange: android.util.Range<Int>? = null
    private var exposureCompensationStep: Float = 1f
    private var maxAfRegions: Int = 0
    private var maxAeRegions: Int = 0
    private var sensorOrientation: Int = 0
    private var supportsRaw: Boolean = false

    private var currentRotation: Int = Surface.ROTATION_0
    private var capturedRotation: Int = Surface.ROTATION_0

    private var captureStartTimestamp: Long = 0
    private var shutterTimestamp: Long = 0

    private var pendingCaptureCount = 0
    private val captureLock = Object()

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val conversionExecutor = Executors.newFixedThreadPool(2)
    private val sessionExecutor = Executors.newSingleThreadExecutor()

    private var onCameraReadyCallback: (() -> Unit)? = null
    private var onVideoStopReadyCallback: (() -> Unit)? = null
    private var onFormatsAvailableCallback: ((List<Int>) -> Unit)? = null
    private var onVideoPresetsAvailableCallback: ((List<VideoPreset>) -> Unit)? = null

    private val identityTonemapCurve =
        TonemapCurve(
            floatArrayOf(0f, 0f, 1f, 1f),
            floatArrayOf(0f, 0f, 1f, 1f),
            floatArrayOf(0f, 0f, 1f, 1f),
        )

    private var previewRequestBuilder: CaptureRequest.Builder? = null

    fun getIsoRange(): IntRange? = isoRange?.let { it.lower..it.upper }

    fun getExposureTimeRange(): LongRange? = exposureTimeRange?.let { it.lower..it.upper }

    fun getSupportedVideoPresets(): List<VideoPreset> = supportedVideoPresets

    fun createPreviewView(context: Context): TextureView =
        TextureView(context).also { tv ->
            textureView = tv
            tv.surfaceTextureListener = surfaceTextureListener
        }

    private val surfaceTextureListener =
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                Log.e(TAG, "Surface texture available: ${width}x$height")
                schedulePreviewTransform()
                openCameraIfNeeded()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                Log.e(TAG, "Surface texture size changed: ${width}x$height")
                updatePreviewSize(width, height)
                schedulePreviewTransform()
                if (cameraDevice != null && captureSession != null) {
                    recreateCaptureSession()
                }
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.e(TAG, "Surface texture destroyed")
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                if (previewTransformFramesRemaining > 0) {
                    applyPreviewTransform()
                    previewTransformFramesRemaining--
                }
                handlePreviewFrameUpdated()
            }
        }

    fun setInitialOutputFormat(format: Int) {
        currentOutputFormat = format
        Log.e(TAG, "Initial output format set to: ${getFormatName(format)}")
    }

    fun bindCamera(
        textureView: TextureView,
        onFormatsAvailable: (List<Int>) -> Unit = {},
        onVideoPresetsAvailable: (List<VideoPreset>) -> Unit = {},
        onCameraReady: (() -> Unit)? = null,
    ) {
        this.textureView = textureView
        this.onCameraReadyCallback = onCameraReady
        this.onVideoStopReadyCallback = null
        this.onFormatsAvailableCallback = onFormatsAvailable
        this.onVideoPresetsAvailableCallback = onVideoPresetsAvailable
        textureView.surfaceTextureListener = surfaceTextureListener

        if (textureView.isAvailable) {
            openCameraIfNeeded()
        }
    }

    private fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("Camera2Thread").also { it.start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    private fun openCameraIfNeeded() {
        if (cameraDevice != null || isOpeningCamera) return

        openCamera()
    }

    @Suppress("MissingPermission")
    private fun openCamera() {
        if (cameraDevice != null || isOpeningCamera) return

        isOpeningCamera = true
        startBackgroundThread()

        cameraId =
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }

        if (cameraId == null) {
            Log.e(TAG, "No back camera found")
            isOpeningCamera = false
            return
        }

        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId!!)
        val chars = cameraCharacteristics!!

        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        exposureTimeRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)

        val aeCompRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        exposureCompensationRange = aeCompRange
        val aeCompStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        exposureCompensationStep = aeCompStep?.toFloat() ?: 1f

        maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0

        Log.e(TAG, "Camera capabilities - ISO: $isoRange, Exposure: $exposureTimeRange")
        Log.e(TAG, "AF regions: $maxAfRegions, AE regions: $maxAeRegions, Sensor orientation: $sensorOrientation")

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
        supportsRaw = rawSizes != null && rawSizes.isNotEmpty() && !BuildConfig.MONOCHROME_MODE
        supportsVideoStabilization =
            chars
                .get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

        Log.e(TAG, "RAW supported: $supportsRaw")

        val formats = mutableListOf<Int>()
        formats.add(OUTPUT_FORMAT_JPEG)
        if (supportsRaw) {
            formats.add(OUTPUT_FORMAT_RAW)
        }
        onFormatsAvailableCallback?.invoke(formats)
        updateSupportedVideoPresets(chars, map)
        onVideoPresetsAvailableCallback?.invoke(supportedVideoPresets)

        setupImageReaders(chars, map)

        if (!cameraOpenCloseLock.tryAcquire(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            isOpeningCamera = false
            throw RuntimeException("Timeout waiting to open camera")
        }

        try {
            cameraManager.openCamera(cameraId!!, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            isOpeningCamera = false
            cameraOpenCloseLock.release()
        }
    }

    private fun setupImageReaders(
        chars: CameraCharacteristics,
        map: android.hardware.camera2.params.StreamConfigurationMap?,
    ) {
        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)

        if (supportsRaw) {
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            val rawSize = rawSizes?.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
            Log.e(TAG, "RAW capture size: ${rawSize.width}x${rawSize.height}")

            rawImageReader =
                ImageReader
                    .newInstance(
                        rawSize.width,
                        rawSize.height,
                        ImageFormat.RAW_SENSOR,
                        2,
                    ).apply {
                        setOnImageAvailableListener({ reader ->
                            val image = reader.acquireLatestImage()
                            if (image != null) {
                                handleRawImageAvailable(image)
                            }
                        }, backgroundHandler)
                    }

            val previewYuvSize = choosePreviewYuvSize(yuvSizes, rawSize)
            Log.e(TAG, "RAW preview YUV size: ${previewYuvSize.width}x${previewYuvSize.height}")

            thumbnailImageReader =
                ImageReader
                    .newInstance(
                        previewYuvSize.width,
                        previewYuvSize.height,
                        ImageFormat.YUV_420_888,
                        2,
                    ).apply {
                        setOnImageAvailableListener({ reader ->
                            val image = reader.acquireLatestImage()
                            if (image != null) {
                                handleThumbnailImageAvailable(image)
                            }
                        }, backgroundHandler)
                    }
        }
        val yuvSize = yuvSizes?.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
        Log.e(TAG, "YUV capture size: ${yuvSize.width}x${yuvSize.height}")

        zslImageReader =
            ImageReader
                .newInstance(
                    yuvSize.width,
                    yuvSize.height,
                    ImageFormat.YUV_420_888,
                    2,
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        if (zslEnabled) {
                            synchronized(zslLock) {
                                latestZslImage?.close()
                                latestZslImage = reader.acquireLatestImage()
                                latestZslRotation = currentRotation
                            }
                        } else if (pendingVanillaJpegCapture) {
                            pendingVanillaJpegCapture = false
                            val image = reader.acquireLatestImage()
                            if (image != null) {
                                handleVanillaYuvCapture(image)
                            }
                        } else {
                            reader.acquireLatestImage()?.close()
                        }
                    }, backgroundHandler)
                }

        updatePreviewSize()
    }

    private fun updatePreviewSize(
        targetWidth: Int = textureView?.width ?: 1080,
        targetHeight: Int = textureView?.height ?: 1920,
    ) {
        val map = cameraCharacteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val displaySizes = map?.getOutputSizes(SurfaceTexture::class.java)
        previewSize = chooseOptimalPreviewSize(displaySizes, targetWidth, targetHeight)
        Log.e(TAG, "Preview size: ${previewSize?.width}x${previewSize?.height}")
    }

    private fun schedulePreviewTransform() {
        previewTransformFramesRemaining = PREVIEW_TRANSFORM_FRAME_RETRIES
        applyPreviewTransform()
    }

    private fun applyPreviewTransform() {
        val view = textureView ?: return
        view.post {
            if (textureView !== view) return@post
            applyPreviewTransform(view)
        }
    }

    private fun applyPreviewTransform(view: TextureView) {
        val preview = previewSize ?: return
        val chars = cameraCharacteristics ?: return
        val viewWidth = view.width
        val viewHeight = view.height
        if (viewWidth <= 0 || viewHeight <= 0) return

        val displayRotation = view.display?.rotation ?: Surface.ROTATION_0
        val displayRotationDegrees = displayRotation * 90
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val isRotationRequired = computeRelativeRotation(chars, displayRotationDegrees) % 180 != 0

        val scaleX: Float
        val scaleY: Float
        if (sensorOrientation == 0) {
            scaleX =
                if (isRotationRequired) {
                    viewWidth.toFloat() / preview.width
                } else {
                    viewWidth.toFloat() / preview.height
                }
            scaleY =
                if (isRotationRequired) {
                    viewHeight.toFloat() / preview.height
                } else {
                    viewHeight.toFloat() / preview.width
                }
        } else {
            scaleX =
                if (isRotationRequired) {
                    viewWidth.toFloat() / preview.height
                } else {
                    viewWidth.toFloat() / preview.width
                }
            scaleY =
                if (isRotationRequired) {
                    viewHeight.toFloat() / preview.width
                } else {
                    viewHeight.toFloat() / preview.height
                }
        }

        val finalScale = maxOf(scaleX, scaleY)
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val matrix = Matrix()
        if (isRotationRequired) {
            matrix.setScale(
                finalScale / scaleX,
                finalScale / scaleY,
                centerX,
                centerY,
            )
        } else {
            matrix.setScale(
                viewHeight / viewWidth.toFloat() / scaleY * finalScale,
                viewWidth / viewHeight.toFloat() / scaleX * finalScale,
                centerX,
                centerY,
            )
        }
        matrix.postRotate(-displayRotationDegrees.toFloat(), centerX, centerY)

        view.setTransform(matrix)
    }

    private fun computeRelativeRotation(
        chars: CameraCharacteristics,
        deviceOrientationDegrees: Int,
    ): Int {
        val sensorOrientationDegrees = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val sign =
            if (chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                1
            } else {
                -1
            }
        return (sensorOrientationDegrees - deviceOrientationDegrees * sign + 360) % 360
    }

    private fun choosePreviewYuvSize(
        yuvSizes: Array<Size>?,
        rawSize: Size,
    ): Size {
        if (yuvSizes == null || yuvSizes.isEmpty()) return Size(1280, 960)

        val rawAspect = rawSize.width.toDouble() / rawSize.height
        val tolerance = 0.1
        val targetWidth = 1280

        val suitable =
            yuvSizes
                .filter { size ->
                    val aspect = size.width.toDouble() / size.height
                    kotlin.math.abs(aspect - rawAspect) < tolerance
                }.minByOrNull { kotlin.math.abs(it.width - targetWidth) }

        return suitable
            ?: yuvSizes.minByOrNull { kotlin.math.abs(it.width - targetWidth) }
            ?: Size(1280, 960)
    }

    private fun chooseOptimalPreviewSize(
        choices: Array<Size>?,
        targetWidth: Int,
        targetHeight: Int,
    ): Size {
        if (choices == null || choices.isEmpty()) return Size(1440, 1080)

        val targetRatio = 4.0 / 3.0
        val tolerance = 0.1
        val idealWidth = 1440

        val suitable =
            choices
                .filter { size ->
                    val ratio = size.width.toDouble() / size.height.toDouble()
                    kotlin.math.abs(ratio - targetRatio) < tolerance && size.width <= idealWidth
                }.sortedByDescending { it.width * it.height }

        return suitable.firstOrNull() ?: choices.maxByOrNull { it.width * it.height } ?: Size(1440, 1080)
    }

    private fun updateSupportedVideoPresets(
        chars: CameraCharacteristics,
        map: android.hardware.camera2.params.StreamConfigurationMap?,
    ) {
        val recorderSizes = map?.getOutputSizes(MediaRecorder::class.java)?.toList().orEmpty()
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val preferredPresets = listOf(VideoPreset.FHD30, VideoPreset.FHD24)

        videoFpsRanges.clear()
        supportedVideoPresets =
            preferredPresets.filter { preset ->
                val hasSize = recorderSizes.any { it.width == preset.width && it.height == preset.height }
                val fpsRange = chooseVideoFpsRange(preset, fpsRanges)
                if (hasSize && fpsRange != null) {
                    videoFpsRanges[preset] = fpsRange
                    true
                } else {
                    false
                }
            }

        if (supportedVideoPresets.isNotEmpty() && videoPreset !in supportedVideoPresets) {
            videoPreset = supportedVideoPresets.first()
        }

        Log.e(TAG, "Supported video presets: $supportedVideoPresets")
    }

    private fun chooseVideoFpsRange(
        preset: VideoPreset,
        ranges: List<Range<Int>>,
    ): Range<Int>? =
        ranges
            .filter { range -> range.lower <= preset.fps && range.upper >= preset.fps }
            .sortedWith(
                compareBy<Range<Int>>(
                    { if (it.lower == preset.fps && it.upper == preset.fps) 0 else 1 },
                    { if (it.upper == preset.fps) 0 else 1 },
                    { it.upper - it.lower },
                    { kotlin.math.abs(it.upper - preset.fps) },
                    { kotlin.math.abs(it.lower - preset.fps) },
                ),
            ).firstOrNull()

    private val stateCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                isOpeningCamera = false
                cameraOpenCloseLock.release()
                createCaptureSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                isOpeningCamera = false
                cameraOpenCloseLock.release()
                camera.close()
                cameraDevice = null
            }

            override fun onError(
                camera: CameraDevice,
                error: Int,
            ) {
                isOpeningCamera = false
                cameraOpenCloseLock.release()
                camera.close()
                cameraDevice = null
                Log.e(TAG, "Camera error: $error")
            }
        }

    private fun shouldConfigureRecorderSurface(): Boolean = captureMode == CaptureMode.VIDEO && recorderSurface != null

    private fun shouldTargetRecorderSurface(): Boolean =
        shouldConfigureRecorderSurface() && (pendingVideoRecordingStart || isVideoRecording)

    private fun ensureRecorderSurface(): Surface? {
        recorderSurface?.let { return it }

        val surface =
            try {
                MediaCodec.createPersistentInputSurface()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create persistent recorder surface", e)
                return null
            }

        if (!preparePersistentRecorderSurface(surface)) {
            runCatching { surface.release() }
            return null
        }

        recorderSurface = surface
        return surface
    }

    private fun preparePersistentRecorderSurface(surface: Surface): Boolean {
        val outputFile =
            try {
                File.createTempFile("zero_recorder_surface", ".mp4", context.cacheDir)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create recorder surface temp file", e)
                return false
            }

        val recorder = createMediaRecorder()

        return try {
            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputFile.absolutePath)
                setVideoEncodingBitRate(getVideoEncodingBitRate(videoPreset))
                setVideoFrameRate(videoPreset.fps)
                setVideoSize(videoPreset.width, videoPreset.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setInputSurface(surface)
                prepare()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare persistent recorder surface", e)
            false
        } finally {
            runCatching { recorder.release() }
            runCatching { outputFile.delete() }
        }
    }

    private fun releaseRecorderSurface() {
        runCatching {
            recorderSurface?.release()
        }
        recorderSurface = null
        captureSessionIncludesRecorderSurface = false
    }

    private fun recreateCaptureSession() {
        Log.e(TAG, "RECREATE: closing existing session=$captureSession")
        previewReadyTimeoutJob?.cancel()
        previewReadyTimeoutJob = null
        pendingReadySession = null
        previewFramesUntilReady = 0
        previewRequestBuilder = null
        captureSession?.close()
        captureSession = null
        captureSessionIncludesRecorderSurface = false
        Log.e(TAG, "RECREATE: creating new session")
        createCaptureSession()
    }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val texture = textureView?.surfaceTexture ?: return
        val preview = previewSize ?: return

        texture.setDefaultBufferSize(preview.width, preview.height)
        schedulePreviewTransform()
        previewSurface?.release()
        previewSurface = Surface(texture)
        val recorderSurfaceForSession =
            if (captureMode == CaptureMode.VIDEO) {
                ensureRecorderSurface()
            } else {
                null
            }

        val surfaces = mutableListOf<Surface>()
        surfaces.add(previewSurface!!)
        if (captureMode == CaptureMode.PHOTO) {
            zslImageReader?.surface?.let { surfaces.add(it) }
            rawImageReader?.surface?.let { surfaces.add(it) }
            thumbnailImageReader?.surface?.let { surfaces.add(it) }

            if (fastMode) {
                Log.e(TAG, "Session config: Preview + YUV + RAW + Thumbnail [fast mode]")
            } else {
                Log.e(TAG, "Session config: Preview + YUV + RAW + Thumbnail [normal mode]")
            }
        } else if (recorderSurfaceForSession != null) {
            surfaces.add(recorderSurfaceForSession)
            Log.e(TAG, "Session config: Preview + Recorder [video]")
        } else {
            Log.e(TAG, "Session config: Preview only [video idle]")
        }

        Log.e(
            TAG,
            "CREATE SESSION: surfaces=${surfaces.size}, recorderSurface=$recorderSurface, pendingStart=$pendingVideoRecordingStart",
        )

        try {
            val stateCallback =
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.e(TAG, "onConfigured: device=$cameraDevice, session=$session")
                        if (cameraDevice == null) return
                        captureSession = session
                        captureSessionIncludesRecorderSurface = recorderSurfaceForSession != null
                        if (!startPreview()) {
                            if (pendingVideoRecordingStart) {
                                pendingVideoRecordingStart = false
                                releasePreparedVideoRecorder(deleteOutput = true)
                                onVideoRecordingErrorCallback?.invoke("START FAIL")
                                onVideoRecordingErrorCallback = null
                                onVideoRecordingStartedCallback = null
                            }
                            notifyVideoStopReady()
                            return
                        }

                        applyGrayscaleFilterToPreview()
                        if (!scheduleInitialPreviewSessionRefresh(session)) {
                            notifyConfiguredSessionReadyAfterPreviewFrame(session)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "onConfigureFailed: Capture session configuration failed")
                        captureSessionIncludesRecorderSurface = false
                        if (pendingVideoRecordingStart) {
                            pendingVideoRecordingStart = false
                            releasePreparedVideoRecorder(deleteOutput = true)
                            onVideoRecordingErrorCallback?.invoke("SESSION FAIL")
                            onVideoRecordingErrorCallback = null
                            onVideoRecordingStartedCallback = null
                        }
                        notifyVideoStopReady()
                        postToast("Camera configuration failed")
                    }
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = surfaces.map { OutputConfiguration(it) }
                val sessionConfig =
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputConfigs,
                        sessionExecutor,
                        stateCallback,
                    )
                camera.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(surfaces, stateCallback, backgroundHandler)
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error creating capture session", e)
        }
    }

    private fun notifyConfiguredSessionReady() {
        previewReadyTimeoutJob?.cancel()
        previewReadyTimeoutJob = null
        pendingReadySession = null
        previewFramesUntilReady = 0

        coroutineScope.launch(Dispatchers.Main) {
            onCameraReadyCallback?.invoke()
            onVideoStopReadyCallback?.invoke()
            onVideoStopReadyCallback = null
        }
    }

    private fun notifyConfiguredSessionReadyAfterPreviewFrame(session: CameraCaptureSession) {
        pendingReadySession = session
        previewFramesUntilReady = PREVIEW_READY_FRAME_UPDATES
        previewReadyTimeoutJob?.cancel()
        previewReadyTimeoutJob =
            coroutineScope.launch {
                delay(PREVIEW_READY_TIMEOUT_MS)
                if (pendingReadySession === session && captureSession === session) {
                    Log.e(TAG, "Preview ready fallback after waiting for fresh frame")
                    notifyConfiguredSessionReady()
                }
            }
    }

    private fun handlePreviewFrameUpdated() {
        val session = pendingReadySession ?: return
        if (captureSession !== session) return

        applyPreviewTransform()
        previewFramesUntilReady--
        if (previewFramesUntilReady <= 0) {
            notifyConfiguredSessionReady()
        }
    }

    private fun scheduleInitialPreviewSessionRefresh(configuredSession: CameraCaptureSession): Boolean {
        if (didRefreshInitialPreviewSession || pendingVideoRecordingStart || isVideoRecording) return false

        didRefreshInitialPreviewSession = true
        coroutineScope.launch {
            delay(INITIAL_PREVIEW_SESSION_REFRESH_DELAY_MS)
            if (captureSession !== configuredSession) return@launch
            if (cameraDevice == null || textureView?.isAvailable != true) return@launch
            if (pendingVideoRecordingStart || isVideoRecording) {
                notifyConfiguredSessionReady()
                return@launch
            }

            Log.e(TAG, "Refreshing initial preview session after first layout/frame pass")
            updatePreviewSize()
            schedulePreviewTransform()
            recreateCaptureSession()
        }
        return true
    }

    private fun startPreview(): Boolean {
        val camera = cameraDevice ?: return false
        val session = captureSession ?: return false
        val surface = previewSurface ?: return false

        try {
            if (captureMode == CaptureMode.VIDEO) {
                val template =
                    if (shouldTargetRecorderSurface()) {
                        CameraDevice.TEMPLATE_RECORD
                    } else {
                        CameraDevice.TEMPLATE_PREVIEW
                    }
                previewRequestBuilder =
                    camera.createCaptureRequest(template).apply {
                        addTarget(surface)
                        recorderSurface?.takeIf { shouldTargetRecorderSurface() }?.let { addTarget(it) }
                        applyCommonSettings(this)
                    }
                zslEnabled = false
                Log.e(TAG, "Preview started [video]")
            } else if (fastMode && zslImageReader != null) {
                previewRequestBuilder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG).apply {
                        addTarget(surface)
                        addTarget(zslImageReader!!.surface)
                        applyCommonSettings(this)
                    }
                zslEnabled = true
                Log.e(TAG, "Preview started with ZSL [fast mode]")
            } else {
                previewRequestBuilder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        applyCommonSettings(this)
                    }
                zslEnabled = false
                Log.e(TAG, "Preview started [normal mode]")
            }

            session.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)

            if (captureMode == CaptureMode.VIDEO && pendingVideoRecordingStart && shouldTargetRecorderSurface()) {
                startPreparedVideoRecording()
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error starting preview", e)
            return false
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Capture session already closed while starting preview", e)
            return false
        }

        return true
    }

    /**
     * Applies common capture settings based on current mode.
     */
    private fun applyCommonSettings(builder: CaptureRequest.Builder) {
        if (captureMode == CaptureMode.VIDEO) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, getActiveVideoFpsRange())

            if (autoExposure) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.set(
                    CaptureRequest.SENSOR_SENSITIVITY,
                    manualIso.coerceIn(
                        isoRange?.lower ?: 100,
                        isoRange?.upper ?: 1600,
                    ),
                )
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, getActiveVideoExposureTimeNs())
                builder.set(CaptureRequest.SENSOR_FRAME_DURATION, videoPreset.frameDurationNs)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)
                }
            }

            builder.set(
                CaptureRequest.FLASH_MODE,
                if (videoTorchEnabled) {
                    CaptureRequest.FLASH_MODE_TORCH
                } else {
                    CaptureRequest.FLASH_MODE_OFF
                },
            )
            builder.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (supportsVideoStabilization) {
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
                } else {
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                },
            )
        } else if (fastMode) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, HYPERFOCAL_DIOPTERS)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        if (captureMode == CaptureMode.PHOTO && autoExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
        } else if (captureMode == CaptureMode.PHOTO) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)

            val maxSensorIso = isoRange?.upper ?: 1600
            val (sensorIso, boost) =
                if (manualIso <= maxSensorIso) {
                    manualIso to 100
                } else {
                    val requiredBoost = (manualIso * 100) / maxSensorIso
                    maxSensorIso to requiredBoost.coerceAtMost(3199)
                }

            builder.set(CaptureRequest.SENSOR_SENSITIVITY, sensorIso)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manualExposureTimeNs)
            builder.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, boost)
        }

        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
        builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        builder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
        builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_FAST)
        builder.set(CaptureRequest.CONTROL_EFFECT_MODE, CaptureRequest.CONTROL_EFFECT_MODE_OFF)

        // Minimize tonemapping: use linear identity curve everywhere
        builder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
        builder.set(CaptureRequest.TONEMAP_CURVE, identityTonemapCurve)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
        }

        // Keep AWB on but otherwise avoid color processing
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)

        builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF)

        val oisMode =
            if (captureMode == CaptureMode.VIDEO || oisEnabled) {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
            } else {
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
            }
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, oisMode)

        if (captureMode == CaptureMode.PHOTO) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
        }
    }

    private fun getActiveVideoFpsRange(): Range<Int> = videoFpsRanges[videoPreset] ?: Range(videoPreset.fps, videoPreset.fps)

    private fun getActiveVideoExposureTimeNs(): Long {
        val minExposure = exposureTimeRange?.lower ?: 1_000_000L
        val maxExposure = minOf(exposureTimeRange?.upper ?: videoPreset.frameDurationNs, videoPreset.frameDurationNs)
        return manualExposureTimeNs.coerceIn(minExposure, maxExposure)
    }

    /**
     * Updates preview with current settings (call after changing exposure/focus settings).
     */
    private fun updatePreview() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        try {
            applyCommonSettings(builder)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error updating preview", e)
        }
    }

    private fun stopActiveRepeatingRequest() {
        val session = captureSession ?: return

        try {
            session.stopRepeating()
            session.abortCaptures()
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error stopping active capture request", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Capture session already closed while stopping active request", e)
        }
    }

    private fun notifyVideoStopReady() {
        val callback = onVideoStopReadyCallback ?: return
        onVideoStopReadyCallback = null
        coroutineScope.launch(Dispatchers.Main) {
            callback()
        }
    }

    fun setCaptureMode(mode: CaptureMode) {
        if (captureMode == mode) return

        if (isVideoRecording) {
            Log.w(TAG, "Ignoring mode switch while recording video")
            return
        }

        if (mode != CaptureMode.VIDEO) {
            releasePreparedVideoRecorder(deleteOutput = true)
        }

        captureMode = mode
        previewRequestBuilder = null
        zslEnabled = false

        if (cameraDevice != null) {
            recreateCaptureSession()
        }
    }

    fun setVideoPreset(preset: VideoPreset) {
        if (isVideoRecording || pendingVideoRecordingStart) return

        if (supportedVideoPresets.isNotEmpty() && preset !in supportedVideoPresets) {
            Log.w(TAG, "Ignoring unsupported video preset: $preset")
            return
        }

        if (videoPreset == preset) return

        videoPreset = preset
        Log.e(TAG, "Video preset changed to: ${preset.label}")

        if (captureMode == CaptureMode.VIDEO) {
            updatePreview()
        }
    }

    fun setVideoTorchEnabled(enabled: Boolean) {
        if (videoTorchEnabled == enabled) return
        videoTorchEnabled = enabled
        Log.e(TAG, "Video torch ${if (enabled) "enabled" else "disabled"}")
        if (captureMode == CaptureMode.VIDEO) {
            updatePreview()
        }
    }

    fun isRecordingVideo(): Boolean = isVideoRecording

    fun startVideoRecording(
        onStarted: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        if (captureMode != CaptureMode.VIDEO || isVideoRecording || pendingVideoRecordingStart) {
            onError("REC FAIL")
            return
        }

        if (supportedVideoPresets.isNotEmpty() && videoPreset !in supportedVideoPresets) {
            videoPreset = supportedVideoPresets.first()
        }

        if (!prepareMediaRecorderForRecording()) {
            onError("PREP FAIL")
            return
        }

        onVideoRecordingStartedCallback = onStarted
        onVideoRecordingErrorCallback = onError
        pendingVideoRecordingStart = true
        if (captureSession == null || !captureSessionIncludesRecorderSurface || !startPreview()) {
            recreateCaptureSession()
        }
    }

    fun stopVideoRecording(
        onComplete: (Uri?) -> Unit = {},
        onReady: () -> Unit = {},
    ) {
        val wasRecording = isVideoRecording
        pendingVideoRecordingStart = false
        isVideoRecording = false
        onVideoRecordingStartedCallback = null
        onVideoRecordingErrorCallback = null
        onVideoStopReadyCallback = onReady

        coroutineScope.launch(Dispatchers.Main) { onComplete(null) }

        val recorder = mediaRecorder
        val outputUri = videoOutputUri

        coroutineScope.launch(Dispatchers.IO) {
            var savedUri = outputUri
            var previewRestarted = false

            stopActiveRepeatingRequest()

            if (recorder != null && wasRecording) {
                try {
                    recorder.stop()
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Failed to stop recorder cleanly", e)
                    savedUri = null
                }
            } else {
                savedUri = null
            }
            releasePreparedVideoRecorder(deleteOutput = savedUri == null)

            if (cameraDevice != null && captureMode == CaptureMode.VIDEO && captureSessionIncludesRecorderSurface) {
                previewRestarted = startPreview()
            }

            savedUri?.let { finalizePendingVideo(it) }

            if (cameraDevice != null && captureMode == CaptureMode.VIDEO) {
                if (captureSessionIncludesRecorderSurface && previewRestarted) {
                    notifyVideoStopReady()
                } else {
                    recreateCaptureSession()
                }
            } else {
                notifyVideoStopReady()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }

    private fun prepareMediaRecorderForRecording(): Boolean {
        releasePreparedVideoRecorder(deleteOutput = true)

        val inputSurface = ensureRecorderSurface() ?: return false
        val outputUri = createPendingVideoUri() ?: return false
        val outputDescriptor =
            context.contentResolver.openFileDescriptor(outputUri, "rw") ?: run {
                deletePendingVideo(outputUri)
                return false
            }

        val recorder = createMediaRecorder()

        return try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(outputDescriptor.fileDescriptor)
                setVideoEncodingBitRate(getVideoEncodingBitRate(videoPreset))
                setVideoFrameRate(videoPreset.fps)
                setVideoSize(videoPreset.width, videoPreset.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(48_000)
                setAudioChannels(1)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setInputSurface(inputSurface)
                setOrientationHint(0)
                prepare()
            }

            mediaRecorder = recorder
            videoOutputUri = outputUri
            videoOutputFileDescriptor = outputDescriptor
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare MediaRecorder", e)
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            outputDescriptor.close()
            deletePendingVideo(outputUri)
            false
        }
    }

    private fun getVideoEncodingBitRate(preset: VideoPreset): Int =
        when (preset) {
            VideoPreset.FHD24 -> 12_000_000
            VideoPreset.FHD30 -> 14_000_000
        }

    private fun createPendingVideoUri(): Uri? {
        val name =
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis()) + ".mp4"

        val contentValues =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Zero")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }

        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
    }

    private fun startPreparedVideoRecording() {
        val recorder =
            mediaRecorder ?: run {
                pendingVideoRecordingStart = false
                onVideoRecordingErrorCallback?.invoke("REC FAIL")
                onVideoRecordingErrorCallback = null
                onVideoRecordingStartedCallback = null
                return
            }

        try {
            recorder.start()
            pendingVideoRecordingStart = false
            isVideoRecording = true
            onVideoRecordingStartedCallback?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start video recording", e)
            pendingVideoRecordingStart = false
            isVideoRecording = false
            releasePreparedVideoRecorder(deleteOutput = true)
            onVideoRecordingErrorCallback?.invoke("START FAIL")

            if (cameraDevice != null && captureMode == CaptureMode.VIDEO) {
                recreateCaptureSession()
            }
        } finally {
            onVideoRecordingStartedCallback = null
            onVideoRecordingErrorCallback = null
        }
    }

    private fun stopVideoRecordingInternal(): Uri? {
        val recorder = mediaRecorder
        val outputUri = videoOutputUri
        val wasRecording = isVideoRecording

        pendingVideoRecordingStart = false
        isVideoRecording = false
        onVideoRecordingStartedCallback = null
        onVideoRecordingErrorCallback = null

        if (recorder == null) {
            releasePreparedVideoRecorder(deleteOutput = true)
            return null
        }

        var savedUri = outputUri

        try {
            stopActiveRepeatingRequest()
            if (wasRecording) {
                recorder.stop()
            } else {
                savedUri = null
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Failed to stop video recording cleanly", e)
            savedUri = null
        } finally {
            releasePreparedVideoRecorder(deleteOutput = savedUri == null)
        }

        savedUri?.let { finalizePendingVideo(it) }
        return savedUri
    }

    private fun finalizePendingVideo(uri: Uri) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            val contentValues =
                ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
            context.contentResolver.update(uri, contentValues, null, null)
        }
    }

    private fun deletePendingVideo(uri: Uri) {
        runCatching {
            context.contentResolver.delete(uri, null, null)
        }.onFailure { error ->
            Log.e(TAG, "Failed to delete pending video", error)
        }
    }

    private fun releasePreparedVideoRecorder(deleteOutput: Boolean) {
        runCatching {
            mediaRecorder?.reset()
        }
        runCatching {
            mediaRecorder?.release()
        }
        runCatching {
            videoOutputFileDescriptor?.close()
        }

        if (deleteOutput) {
            videoOutputUri?.let { deletePendingVideo(it) }
        }

        mediaRecorder = null
        videoOutputFileDescriptor = null
        videoOutputUri = null
    }

    // ===================
    // CAPTURE CALLBACKS
    // ===================

    private var onCaptureStartedCallback: (() -> Unit)? = null
    private var onPreviewReadyCallback: ((Bitmap?) -> Unit)? = null
    private var onCompleteCallback: ((Uri?) -> Unit)? = null
    private var onBenchmarkCallback: ((Long, Long) -> Unit)? = null

    /**
     * Takes a photo with the current settings.
     */
    fun takePhoto(
        onCaptureStarted: () -> Unit = {},
        onPreviewReady: (Bitmap?) -> Unit = {},
        onComplete: (Uri?) -> Unit = {},
        onBenchmark: (shutterMs: Long, saveMs: Long) -> Unit = { _, _ -> },
    ) {
        if (captureMode != CaptureMode.PHOTO) {
            Log.w(TAG, "Ignoring photo capture while in video mode")
            onComplete(null)
            return
        }

        val camera =
            cameraDevice ?: run {
                Log.e(TAG, "takePhoto: camera not ready")
                onComplete(null)
                return
            }
        val session =
            captureSession ?: run {
                Log.e(TAG, "takePhoto: session not ready")
                onComplete(null)
                return
            }

        synchronized(captureLock) {
            pendingCaptureCount++
            Log.e(TAG, "takePhoto: Starting capture (pending: $pendingCaptureCount)")
        }

        onCaptureStartedCallback = onCaptureStarted
        onPreviewReadyCallback = onPreviewReady
        onCompleteCallback = onComplete
        onBenchmarkCallback = onBenchmark

        captureStartTimestamp = System.currentTimeMillis()
        capturedRotation = currentRotation

        if (currentOutputFormat == OUTPUT_FORMAT_JPEG) {
            if (fastMode && zslEnabled) {
                takeZslPhoto()
            } else {
                takeVanillaJpegPhoto()
            }
            return
        }

        val targetSurface = rawImageReader!!.surface

        // If flash is enabled in auto exposure mode, run precapture sequence first
        if (flashEnabled && autoExposure) {
            runPrecaptureSequence {
                captureRawWithCurrentSettings(camera, session, targetSurface, onComplete)
            }
        } else {
            captureRawWithCurrentSettings(camera, session, targetSurface, onComplete)
        }
    }

    /**
     * Captures RAW with current settings (called after precapture if needed).
     */
    private fun captureRawWithCurrentSettings(
        camera: CameraDevice,
        session: CameraCaptureSession,
        targetSurface: Surface,
        onComplete: (Uri?) -> Unit,
    ) {
        synchronized(rawCaptureLock) {
            pendingRawImage?.close()
            pendingRawImage = null
            pendingThumbnailImage?.close()
            pendingThumbnailImage = null
        }

        try {
            val captureBuilder =
                camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(targetSurface)
                    thumbnailImageReader?.surface?.let { addTarget(it) }
                    applyCommonSettings(this)

                    // Override flash settings for capture
                    if (flashEnabled) {
                        if (autoExposure) {
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                        }
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                    }

                    // Copy focus regions from preview if set (for tap-to-focus consistency)
                    previewRequestBuilder?.let { preview ->
                        preview.get(CaptureRequest.CONTROL_AF_REGIONS)?.let { regions ->
                            set(CaptureRequest.CONTROL_AF_REGIONS, regions)
                        }
                        preview.get(CaptureRequest.CONTROL_AE_REGIONS)?.let { regions ->
                            set(CaptureRequest.CONTROL_AE_REGIONS, regions)
                        }
                    }
                }

            Log.d(
                TAG,
                "Taking RAW photo (autoExposure=$autoExposure, flash=$flashEnabled, ISO=$manualIso, shutter=${manualExposureTimeNs}ns)",
            )
            session.capture(captureBuilder.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error taking photo", e)
            synchronized(captureLock) { pendingCaptureCount-- }
            onComplete(null)
        }
    }

    private fun takeVanillaJpegPhoto() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val yuvSurface = zslImageReader?.surface ?: return

        // If flash is enabled in auto exposure mode, run precapture sequence first
        if (flashEnabled && autoExposure) {
            runPrecaptureSequence {
                captureJpegWithCurrentSettings(camera, session, yuvSurface)
            }
        } else {
            captureJpegWithCurrentSettings(camera, session, yuvSurface)
        }
    }

    /**
     * Runs AE precapture sequence for flash metering, then executes the callback.
     */
    private fun runPrecaptureSequence(onReady: () -> Unit) {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            // Set flash mode for precapture metering
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)

            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        // Reset trigger and restore preview settings
                        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
                        applyCommonSettings(builder)
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)

                        // Check AE state
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        Log.e(TAG, "Precapture AE state: $aeState")

                        // Proceed with capture (flash will fire)
                        onReady()
                    }
                },
                backgroundHandler,
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error running precapture sequence", e)
            // Fall back to direct capture
            onReady()
        }
    }

    private fun captureJpegWithCurrentSettings(
        camera: CameraDevice,
        session: CameraCaptureSession,
        yuvSurface: Surface,
    ) {
        try {
            val captureBuilder =
                camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(yuvSurface)

                    // Apply the same settings used in preview for consistency
                    applyCommonSettings(this)

                    // Override flash settings for capture
                    if (flashEnabled) {
                        if (autoExposure) {
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                        }
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                    }

                    // Copy focus regions from preview if set (for tap-to-focus consistency)
                    previewRequestBuilder?.let { preview ->
                        preview.get(CaptureRequest.CONTROL_AF_REGIONS)?.let { regions ->
                            set(CaptureRequest.CONTROL_AF_REGIONS, regions)
                        }
                        preview.get(CaptureRequest.CONTROL_AE_REGIONS)?.let { regions ->
                            set(CaptureRequest.CONTROL_AE_REGIONS, regions)
                        }
                    }
                }

            Log.d(
                TAG,
                "Taking JPEG photo via YUV (autoExposure=$autoExposure, flash=$flashEnabled, ISO=$manualIso, shutter=${manualExposureTimeNs}ns)",
            )
            pendingVanillaJpegCapture = true
            session.capture(captureBuilder.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error taking JPEG photo", e)
            pendingVanillaJpegCapture = false
            synchronized(captureLock) { pendingCaptureCount-- }
            onCompleteCallback?.invoke(null)
        }
    }

    private val captureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureStarted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                timestamp: Long,
                frameNumber: Long,
            ) {
                shutterTimestamp = System.currentTimeMillis()
                coroutineScope.launch(Dispatchers.Main) {
                    onCaptureStartedCallback?.invoke()
                }
            }

            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                lastCaptureResult = result

                // Log actual capture settings from result
                val actualIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                val actualExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val actualAeMode = result.get(CaptureResult.CONTROL_AE_MODE)
                val actualAwbMode = result.get(CaptureResult.CONTROL_AWB_MODE)
                val actualAfMode = result.get(CaptureResult.CONTROL_AF_MODE)
                val actualTonemap = result.get(CaptureResult.TONEMAP_MODE)
                val actualNoiseReduction = result.get(CaptureResult.NOISE_REDUCTION_MODE)
                val actualEdge = result.get(CaptureResult.EDGE_MODE)
                val actualColorCorrection = result.get(CaptureResult.COLOR_CORRECTION_MODE)

                Log.d(
                    TAG,
                    "Capture result: ISO=$actualIso, exposure=${actualExposure}ns, " +
                        "AE=$actualAeMode, AWB=$actualAwbMode, AF=$actualAfMode",
                )
                Log.d(
                    TAG,
                    "Capture processing: tonemap=$actualTonemap, NR=$actualNoiseReduction, " +
                        "edge=$actualEdge, colorCorrection=$actualColorCorrection",
                )
            }

            override fun onCaptureFailed(
                session: CameraCaptureSession,
                request: CaptureRequest,
                failure: CaptureFailure,
            ) {
                Log.e(TAG, "Capture failed: ${failure.reason}")
                synchronized(captureLock) { pendingCaptureCount-- }
                coroutineScope.launch(Dispatchers.Main) {
                    onPreviewReadyCallback?.invoke(null)
                    onCompleteCallback?.invoke(null)
                }
            }
        }

    // Store capture result for DNG metadata
    private var lastCaptureResult: TotalCaptureResult? = null

    private fun takeZslPhoto() {
        val image: Image?
        val imageRotation: Int

        synchronized(zslLock) {
            image = latestZslImage
            imageRotation = latestZslRotation
            latestZslImage = null
        }

        if (image == null) {
            Log.w(TAG, "ZSL: No image available in buffer, waiting...")
            coroutineScope.launch(Dispatchers.IO) {
                delay(100)
                var retryImage: Image? = null
                var retryRotation: Int = currentRotation
                synchronized(zslLock) {
                    retryImage = latestZslImage
                    retryRotation = latestZslRotation
                    latestZslImage = null
                }
                if (retryImage != null) {
                    processZslCapture(retryImage!!, retryRotation)
                } else {
                    Log.e(TAG, "ZSL: Still no image after retry")
                    synchronized(captureLock) { pendingCaptureCount-- }
                    withContext(Dispatchers.Main) {
                        onCompleteCallback?.invoke(null)
                    }
                }
            }
            return
        }

        processZslCapture(image, imageRotation)
    }

    private fun processZslCapture(
        image: Image,
        imageRotation: Int,
    ) {
        capturedRotation = imageRotation
        Log.e(TAG, "ZSL capture using imageRotation=$imageRotation (currentRotation=$currentRotation)")
        shutterTimestamp = System.currentTimeMillis()
        val bufferGrabLatency = shutterTimestamp - captureStartTimestamp

        coroutineScope.launch(Dispatchers.Main) {
            onCaptureStartedCallback?.invoke()
        }

        coroutineScope.launch(Dispatchers.IO) {
            val conversionStart = System.currentTimeMillis()
            val bytes = yuvToJpeg(image, grayscale = bwMode || monoFlavor)
            val conversionTime = System.currentTimeMillis() - conversionStart
            image.close()

            if (bytes == null) {
                Log.e(TAG, "ZSL: Failed to convert YUV to JPEG")
                synchronized(captureLock) { pendingCaptureCount-- }
                withContext(Dispatchers.Main) {
                    onCompleteCallback?.invoke(null)
                }
                return@launch
            }

            val previewBitmap = extractPreviewBitmap(bytes)

            withContext(Dispatchers.Main) {
                onPreviewReadyCallback?.invoke(previewBitmap)
            }

            val saveStart = System.currentTimeMillis()
            val uri = saveJpegToStorage(bytes, writeExifOrientation = true)
            val saveTime = System.currentTimeMillis() - saveStart

            val totalLatency = System.currentTimeMillis() - captureStartTimestamp
            Log.d(
                TAG,
                "Benchmark [HF]: shutter=${bufferGrabLatency}ms, convert=${conversionTime}ms, save=${saveTime}ms, total=${totalLatency}ms",
            )

            synchronized(captureLock) {
                pendingCaptureCount--
            }

            withContext(Dispatchers.Main) {
                onBenchmarkCallback?.invoke(bufferGrabLatency, totalLatency - bufferGrabLatency)
                onCompleteCallback?.invoke(uri)
            }
        }
    }

    private fun handleVanillaYuvCapture(image: Image) {
        coroutineScope.launch(Dispatchers.IO) {
            val conversionStart = System.currentTimeMillis()
            val bytes = yuvToJpeg(image, grayscale = bwMode || monoFlavor)
            val conversionTime = System.currentTimeMillis() - conversionStart
            image.close()

            if (bytes == null) {
                Log.e(TAG, "Vanilla JPEG: Failed to convert YUV to JPEG")
                synchronized(captureLock) { pendingCaptureCount-- }
                withContext(Dispatchers.Main) {
                    onPreviewReadyCallback?.invoke(null)
                    onCompleteCallback?.invoke(null)
                }
                return@launch
            }

            val previewBitmap = extractPreviewBitmap(bytes)

            withContext(Dispatchers.Main) {
                onPreviewReadyCallback?.invoke(previewBitmap)
            }

            val saveStart = System.currentTimeMillis()
            val uri = saveJpegToStorage(bytes, writeExifOrientation = true)
            val saveTime = System.currentTimeMillis() - saveStart

            val saveCompleteTime = System.currentTimeMillis()
            val shutterLatency = shutterTimestamp - captureStartTimestamp
            val processLatency = saveCompleteTime - shutterTimestamp
            val totalLatency = saveCompleteTime - captureStartTimestamp
            val modeName = if (bwMode || monoFlavor) "BW" else "JPG"
            Log.d(
                TAG,
                "Benchmark [$modeName]: shutter=${shutterLatency}ms, convert=${conversionTime}ms, save=${saveTime}ms, total=${totalLatency}ms",
            )

            synchronized(captureLock) {
                pendingCaptureCount--
            }

            withContext(Dispatchers.Main) {
                onBenchmarkCallback?.invoke(shutterLatency, processLatency)
                onCompleteCallback?.invoke(uri)
            }
        }
    }

    private fun yuvToJpeg(
        image: Image,
        grayscale: Boolean = false,
    ): ByteArray? =
        try {
            val yuvBytes = yuv420ToNv21(image, grayscale)
            val yuvImage =
                android.graphics.YuvImage(
                    yuvBytes,
                    android.graphics.ImageFormat.NV21,
                    image.width,
                    image.height,
                    null,
                )

            val outputStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, image.width, image.height),
                JPEG_QUALITY,
                outputStream,
            )
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "YUV to JPEG conversion failed", e)
            null
        }

    private fun yuv420ToNv21(
        image: Image,
        grayscale: Boolean = false,
    ): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        if (grayscale) {
            copyYPlane(yPlane.buffer, nv21, width, height, yRowStride)
            java.util.Arrays.fill(nv21, ySize, nv21.size, 128.toByte())
            return nv21
        }

        val yFuture =
            conversionExecutor.submit {
                copyYPlane(yPlane.buffer.duplicate(), nv21, width, height, yRowStride)
            }

        val uvFuture =
            conversionExecutor.submit {
                copyUvPlanes(
                    uPlane.buffer.duplicate(),
                    vPlane.buffer.duplicate(),
                    nv21,
                    width,
                    height,
                    ySize,
                    uvSize,
                    uvRowStride,
                    uvPixelStride,
                )
            }

        yFuture.get()
        uvFuture.get()

        return nv21
    }

    private fun copyYPlane(
        yBuffer: java.nio.ByteBuffer,
        nv21: ByteArray,
        width: Int,
        height: Int,
        yRowStride: Int,
    ) {
        val ySize = width * height
        yBuffer.rewind()
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }
    }

    private fun copyUvPlanes(
        uBuffer: java.nio.ByteBuffer,
        vBuffer: java.nio.ByteBuffer,
        nv21: ByteArray,
        width: Int,
        height: Int,
        ySize: Int,
        uvSize: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
    ) {
        val uvHeight = height / 2
        val vCapacity = vBuffer.capacity()

        if (uvPixelStride == 2 && uvRowStride == width) {
            vBuffer.rewind()
            val bulkCopySize = minOf(vCapacity, uvSize)
            vBuffer.get(nv21, ySize, bulkCopySize)

            if (bulkCopySize < uvSize) {
                uBuffer.position(bulkCopySize - 1)
                nv21[ySize + uvSize - 1] = uBuffer.get()
            }
        } else if (uvPixelStride == 2) {
            vBuffer.rewind()
            for (row in 0 until uvHeight) {
                val srcOffset = row * uvRowStride
                val dstOffset = ySize + row * width
                val bytesToCopy = minOf(width, vCapacity - srcOffset)
                if (bytesToCopy > 0) {
                    vBuffer.position(srcOffset)
                    vBuffer.get(nv21, dstOffset, bytesToCopy)
                }
            }
            if (uvSize > 0 && vCapacity < uvSize) {
                uBuffer.position(vCapacity - 1)
                nv21[ySize + uvSize - 1] = uBuffer.get()
            }
        } else {
            val uvWidth = width / 2
            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = row * uvRowStride + col
                    nv21[ySize + row * width + col * 2] = vBuffer.get(uvIndex)
                    nv21[ySize + row * width + col * 2 + 1] = uBuffer.get(uvIndex)
                }
            }
        }
    }

    private fun handleRawImageAvailable(image: Image) {
        synchronized(rawCaptureLock) {
            pendingRawImage = image
            checkAndProcessRawCapture()
        }
    }

    private fun handleThumbnailImageAvailable(image: Image) {
        synchronized(rawCaptureLock) {
            pendingThumbnailImage = image
            checkAndProcessRawCapture()
        }
    }

    private fun checkAndProcessRawCapture() {
        val rawImage =
            pendingRawImage ?: run {
                scheduleRawCaptureTimeout()
                return
            }
        val thumbnailImage =
            pendingThumbnailImage ?: run {
                scheduleRawCaptureTimeout()
                return
            }

        rawCaptureTimeoutJob?.cancel()
        rawCaptureTimeoutJob = null
        pendingRawImage = null
        pendingThumbnailImage = null

        processRawWithThumbnail(rawImage, thumbnailImage)
    }

    private fun scheduleRawCaptureTimeout() {
        if (rawCaptureTimeoutJob?.isActive == true) return

        rawCaptureTimeoutJob =
            coroutineScope.launch(Dispatchers.IO) {
                delay(rawCaptureTimeoutMs)
                synchronized(rawCaptureLock) {
                    if (pendingRawImage != null || pendingThumbnailImage != null) {
                        Log.w(TAG, "RAW capture timeout - cleaning up orphaned images")
                        pendingRawImage?.close()
                        pendingRawImage = null
                        pendingThumbnailImage?.close()
                        pendingThumbnailImage = null
                    }
                }
            }
    }

    private fun processRawWithThumbnail(
        rawImage: Image,
        previewYuvImage: Image,
    ) {
        coroutineScope.launch(Dispatchers.IO) {
            val previewBitmap = extractPreviewFromYuv(previewYuvImage)
            previewYuvImage.close()

            withContext(Dispatchers.Main) {
                onPreviewReadyCallback?.invoke(previewBitmap)
            }

            var attempts = 0
            while (lastCaptureResult == null && attempts < 50) {
                delay(10)
                attempts++
            }

            if (lastCaptureResult == null) {
                Log.e(TAG, "Timeout waiting for capture result for DNG")
                rawImage.close()
                synchronized(captureLock) { pendingCaptureCount-- }
                withContext(Dispatchers.Main) {
                    onCompleteCallback?.invoke(null)
                }
                return@launch
            }

            val uri = saveDngToStorage(rawImage, previewBitmap)
            rawImage.close()

            val saveCompleteTime = System.currentTimeMillis()
            val shutterLatency = shutterTimestamp - captureStartTimestamp
            val saveLatency = saveCompleteTime - shutterTimestamp
            val totalLatency = saveCompleteTime - captureStartTimestamp
            Log.e(TAG, "Benchmark [RAW]: shutter=${shutterLatency}ms, save=${saveLatency}ms, total=${totalLatency}ms")

            synchronized(captureLock) {
                pendingCaptureCount--
            }

            withContext(Dispatchers.Main) {
                onBenchmarkCallback?.invoke(shutterLatency, saveLatency)
                onCompleteCallback?.invoke(uri)
            }
        }
    }

    private fun extractPreviewFromYuv(yuvImage: Image): Bitmap? {
        val yuvBytes = yuv420ToNv21(yuvImage)
        val yuvImageWrapper =
            android.graphics.YuvImage(
                yuvBytes,
                android.graphics.ImageFormat.NV21,
                yuvImage.width,
                yuvImage.height,
                null,
            )

        val outputStream = ByteArrayOutputStream()
        yuvImageWrapper.compressToJpeg(
            android.graphics.Rect(0, 0, yuvImage.width, yuvImage.height),
            90,
            outputStream,
        )
        val jpegBytes = outputStream.toByteArray()

        var bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

        if ((bwMode || monoFlavor) && bitmap != null) {
            bitmap = GrayscaleConverter.toGrayscale(bitmap, recycleSource = true)
        }

        return bitmap
    }

    private fun extractPreviewBitmap(jpegBytes: ByteArray): Bitmap? {
        val options =
            BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
        BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, options)

        val targetSize = 400
        val sampleSize = maxOf(1, minOf(options.outWidth, options.outHeight) / targetSize)

        val decodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }

        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions) ?: return null

        return if (bwMode || monoFlavor) {
            GrayscaleConverter.toGrayscale(bitmap, recycleSource = true)
        } else {
            bitmap
        }
    }

    private fun saveJpegToStorage(
        jpegBytes: ByteArray,
        writeExifOrientation: Boolean = false,
    ): Uri? {
        val name =
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis()) + ".jpg"

        val contentValues =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Zero")
                }
            }

        val uri =
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues,
            )

        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(jpegBytes)
            }

            if (writeExifOrientation) {
                try {
                    context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                        val exif = android.media.ExifInterface(pfd.fileDescriptor)
                        exif.setAttribute(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            getExifOrientation().toString(),
                        )
                        exif.saveAttributes()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write EXIF orientation", e)
                }
            }
        }

        return uri
    }

    private fun saveDngToStorage(
        rawImage: Image,
        previewBitmap: Bitmap?,
    ): Uri? {
        val chars = cameraCharacteristics ?: return null
        val captureResult = lastCaptureResult ?: return null

        val name =
            SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis()) + ".dng"

        val contentValues =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Zero")
                }
            }

        val uri =
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues,
            )

        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val dngCreator = DngCreator(chars, captureResult)
                    val exifOrientation = getExifOrientation()
                    Log.e(TAG, "DNG orientation: exifOrientation=$exifOrientation")
                    dngCreator.setOrientation(exifOrientation)

                    if (previewBitmap != null) {
                        val thumbnailBitmap = scaleBitmapForThumbnail(previewBitmap)
                        dngCreator.setThumbnail(thumbnailBitmap)
                        Log.e(TAG, "Embedded thumbnail: ${thumbnailBitmap.width}x${thumbnailBitmap.height}")
                        if (thumbnailBitmap !== previewBitmap) {
                            thumbnailBitmap.recycle()
                        }
                    }

                    dngCreator.writeImage(outputStream, rawImage)
                    dngCreator.close()
                }
                Log.e(TAG, "DNG saved successfully: $uri")
            } catch (e: Exception) {
                Log.e(TAG, "Error writing DNG", e)
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (de: Exception) {
                    Log.e(TAG, "Error deleting failed DNG file", de)
                }
                return null
            }
        }

        return uri
    }

    private fun scaleBitmapForThumbnail(bitmap: Bitmap): Bitmap {
        val maxDim = THUMBNAIL_MAX_DIMENSION
        if (bitmap.width <= maxDim && bitmap.height <= maxDim) return bitmap

        val scale =
            if (bitmap.width >= bitmap.height) {
                maxDim.toFloat() / bitmap.width
            } else {
                maxDim.toFloat() / bitmap.height
            }

        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun getJpegOrientation(): Int {
        val deviceDegrees =
            when (capturedRotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

        val jpegOrientation = (sensorOrientation - deviceDegrees + 360) % 360
        Log.e(TAG, "JPEG orientation: sensor=$sensorOrientation, device=$deviceDegrees, result=$jpegOrientation")
        return jpegOrientation
    }

    private fun getExifOrientation(): Int {
        val jpegOrientation = getJpegOrientation()
        return when (jpegOrientation) {
            0 -> android.media.ExifInterface.ORIENTATION_NORMAL
            90 -> android.media.ExifInterface.ORIENTATION_ROTATE_90
            180 -> android.media.ExifInterface.ORIENTATION_ROTATE_180
            270 -> android.media.ExifInterface.ORIENTATION_ROTATE_270
            else -> android.media.ExifInterface.ORIENTATION_NORMAL
        }
    }

    fun setRotation(rotation: Int) {
        currentRotation = rotation
    }

    fun setFlashEnabled(enabled: Boolean) {
        flashEnabled = enabled
        Log.e(TAG, "Flash ${if (enabled) "enabled" else "disabled"}")
        if (captureMode == CaptureMode.PHOTO) {
            refreshFlashState()
        }
    }

    fun setOisEnabled(enabled: Boolean) {
        if (oisEnabled == enabled) return
        oisEnabled = enabled
        Log.e(TAG, "OIS ${if (enabled) "enabled" else "disabled"}")
        updatePreview()
    }

    private fun refreshFlashState() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return

        applyCommonSettings(builder)
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)

        try {
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error refreshing flash state", e)
        }
    }

    fun setBwMode(enabled: Boolean) {
        if (bwMode == enabled) return
        bwMode = enabled
        Log.e(TAG, "BW mode set to: $enabled")
        applyGrayscaleFilterToPreview()
    }

    fun setFastMode(enabled: Boolean) {
        if (captureMode == CaptureMode.VIDEO) return
        if (fastMode == enabled) return

        if (!enabled) {
            synchronized(zslLock) {
                latestZslImage?.close()
                latestZslImage = null
                latestZslRotation = currentRotation
            }
            zslEnabled = false
        }

        fastMode = enabled
        Log.e(TAG, "Fast (hyperfocal) mode set to: $enabled - recreating session")

        captureSession?.close()
        captureSession = null
        createCaptureSession()
    }

    fun setOutputFormat(format: Int) {
        if (currentOutputFormat == format) return

        if (format == OUTPUT_FORMAT_RAW && !supportsRaw) {
            Log.w(TAG, "RAW not supported, ignoring format change")
            return
        }

        currentOutputFormat = format
        Log.e(TAG, "Output format changed to: ${getFormatName(format)}")
    }

    fun setAutoExposure(
        enabled: Boolean,
        ev: Float? = null,
    ) {
        autoExposure = enabled
        if (ev != null) {
            val step = exposureCompensationStep
            exposureCompensation =
                (ev / step).toInt().coerceIn(
                    exposureCompensationRange?.lower ?: -12,
                    exposureCompensationRange?.upper ?: 12,
                )
        }
        Log.e(TAG, "Auto exposure: $enabled, EC index: $exposureCompensation")
        updatePreview()
    }

    fun setExposureCompensation(ev: Float) {
        val step = exposureCompensationStep
        exposureCompensation =
            (ev / step).toInt().coerceIn(
                exposureCompensationRange?.lower ?: -12,
                exposureCompensationRange?.upper ?: 12,
            )
        Log.e(TAG, "Exposure compensation: $ev EV (index: $exposureCompensation)")
        if (autoExposure) {
            updatePreview()
        }
    }

    fun setManualExposure(
        iso: Int,
        exposureTimeNs: Long,
    ) {
        autoExposure = false
        if (captureMode == CaptureMode.VIDEO) {
            manualIso =
                iso.coerceIn(
                    isoRange?.lower ?: 100,
                    isoRange?.upper ?: 1600,
                )
            manualExposureTimeNs = getClampedVideoExposure(exposureTimeNs)
        } else {
            val maxEffectiveIso = (isoRange?.upper ?: 1600) * 32
            manualIso =
                iso.coerceIn(
                    isoRange?.lower ?: 100,
                    maxEffectiveIso,
                )
            manualExposureTimeNs =
                exposureTimeNs.coerceIn(
                    exposureTimeRange?.lower ?: 1000000L,
                    exposureTimeRange?.upper ?: 1000000000L,
                )
        }
        Log.e(TAG, "Manual exposure: ISO=$manualIso, shutter=${manualExposureTimeNs}ns")
        updatePreview()
    }

    private fun getClampedVideoExposure(exposureTimeNs: Long): Long {
        val minExposure = exposureTimeRange?.lower ?: 1_000_000L
        val maxExposure = minOf(exposureTimeRange?.upper ?: videoPreset.frameDurationNs, videoPreset.frameDurationNs)
        return exposureTimeNs.coerceIn(minExposure, maxExposure)
    }

    /**
     * Tap to focus at normalized coordinates.
     */
    fun onTapToFocus(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        if (fastMode) {
            Log.e(TAG, "Tap to focus ignored in hyperfocal mode")
            return
        }

        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        if (maxAfRegions <= 0) {
            Log.e(TAG, "AF regions not supported")
            if (captureMode == CaptureMode.PHOTO) {
                triggerAutofocus()
            }
            return
        }

        val chars = cameraCharacteristics ?: return
        val sensorRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return

        val focusSize = minOf(sensorRect.width(), sensorRect.height()) / 20
        val normalizedX = x / width
        val normalizedY = y / height

        val sensorX: Int
        val sensorY: Int
        when (sensorOrientation) {
            90 -> {
                sensorX = (normalizedY * sensorRect.width()).toInt()
                sensorY = ((1 - normalizedX) * sensorRect.height()).toInt()
            }

            270 -> {
                sensorX = ((1 - normalizedY) * sensorRect.width()).toInt()
                sensorY = (normalizedX * sensorRect.height()).toInt()
            }

            180 -> {
                sensorX = ((1 - normalizedX) * sensorRect.width()).toInt()
                sensorY = ((1 - normalizedY) * sensorRect.height()).toInt()
            }

            else -> {
                sensorX = (normalizedX * sensorRect.width()).toInt()
                sensorY = (normalizedY * sensorRect.height()).toInt()
            }
        }

        val left = (sensorX - focusSize).coerceIn(0, sensorRect.width() - 1)
        val top = (sensorY - focusSize).coerceIn(0, sensorRect.height() - 1)
        val right = (sensorX + focusSize).coerceIn(1, sensorRect.width())
        val bottom = (sensorY + focusSize).coerceIn(1, sensorRect.height())

        val focusRegion =
            MeteringRectangle(
                android.graphics.Rect(left, top, right, bottom),
                MeteringRectangle.METERING_WEIGHT_MAX,
            )

        try {
            applyCommonSettings(builder)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRegion))
            if (maxAeRegions > 0) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRegion))
            }
            if (captureMode == CaptureMode.VIDEO) {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            } else {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

                session.capture(builder.build(), null, backgroundHandler)

                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            }

            Log.e(TAG, "Tap to focus at ($x, $y) -> sensor ($sensorX, $sensorY)")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error setting focus region", e)
        }
    }

    fun triggerFocus() {
        if (captureMode == CaptureMode.VIDEO) {
            Log.e(TAG, "Trigger focus ignored in video mode")
            return
        }

        if (fastMode) {
            Log.e(TAG, "Trigger focus ignored in hyperfocal mode")
            return
        }
        triggerAutofocus()
    }

    private fun triggerAutofocus() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            applyCommonSettings(builder)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, backgroundHandler)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error triggering autofocus", e)
        }
    }

    fun setCenterSpotMetering(
        enable: Boolean,
        tempEv: Float? = null,
    ) {
        if (captureMode == CaptureMode.VIDEO) {
            return
        }

        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return
        val characteristics = cameraCharacteristics ?: return

        val maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        if (maxAeRegions == 0) {
            Log.e(TAG, "Device doesn't support AE regions")
            return
        }

        try {
            if (enable) {
                val sensorRect =
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                        ?: return

                val spotSize = minOf(sensorRect.width(), sensorRect.height()) / 40
                val centerX = sensorRect.width() / 2
                val centerY = sensorRect.height() / 2

                val left = centerX - spotSize
                val top = centerY - spotSize
                val right = centerX + spotSize
                val bottom = centerY + spotSize

                val meteringRegion =
                    MeteringRectangle(
                        android.graphics.Rect(left, top, right, bottom),
                        MeteringRectangle.METERING_WEIGHT_MAX,
                    )

                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(meteringRegion))
                Log.e(TAG, "Center spot metering enabled (${spotSize * 2}px region)")
            } else {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, null)
                Log.e(TAG, "Center spot metering disabled")
            }

            if (tempEv != null && autoExposure) {
                val step = exposureCompensationStep
                val tempEc =
                    (tempEv / step).toInt().coerceIn(
                        exposureCompensationRange?.lower ?: -12,
                        exposureCompensationRange?.upper ?: 12,
                    )
                builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, tempEc)
                Log.e(TAG, "Temporary EV set to $tempEv (index: $tempEc)")
            }

            session.capture(builder.build(), null, backgroundHandler)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error setting center spot metering", e)
        }
    }

    // ===================
    // PREVIEW GRAYSCALE
    // ===================

    private fun applyGrayscaleFilterToPreview() {
        val tv = textureView ?: return
        val shouldApply = monoFlavor || (captureMode == CaptureMode.PHOTO && bwMode)

        tv.post {
            if (!shouldApply) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    tv.setRenderEffect(null)
                } else {
                    tv.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                }
                Log.e(TAG, "Cleared grayscale filter from preview")
                return@post
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val colorMatrix =
                    android.graphics.ColorMatrix(
                        floatArrayOf(
                            GrayscaleConverter.R_WEIGHT,
                            GrayscaleConverter.G_WEIGHT,
                            GrayscaleConverter.B_WEIGHT,
                            0f,
                            0f,
                            GrayscaleConverter.R_WEIGHT,
                            GrayscaleConverter.G_WEIGHT,
                            GrayscaleConverter.B_WEIGHT,
                            0f,
                            0f,
                            GrayscaleConverter.R_WEIGHT,
                            GrayscaleConverter.G_WEIGHT,
                            GrayscaleConverter.B_WEIGHT,
                            0f,
                            0f,
                            0f,
                            0f,
                            0f,
                            1f,
                            0f,
                        ),
                    )
                val effect =
                    android.graphics.RenderEffect.createColorFilterEffect(
                        android.graphics.ColorMatrixColorFilter(colorMatrix),
                    )
                tv.setRenderEffect(effect)
                Log.e(TAG, "Applied grayscale RenderEffect to preview")
            } else {
                val paint =
                    android.graphics.Paint().apply {
                        colorFilter = GrayscaleConverter.getColorFilter()
                    }
                tv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
                Log.e(TAG, "Applied grayscale filter to preview (legacy)")
            }
        }
    }

    fun hasPendingCaptures(): Boolean {
        synchronized(captureLock) {
            return pendingCaptureCount > 0 || pendingVideoRecordingStart
        }
    }

    fun shutdown() {
        try {
            cameraOpenCloseLock.acquire()

            stopVideoRecordingInternal()
            previewReadyTimeoutJob?.cancel()
            previewReadyTimeoutJob = null
            pendingReadySession = null
            previewFramesUntilReady = 0

            synchronized(zslLock) {
                latestZslImage?.close()
                latestZslImage = null
                latestZslRotation = currentRotation
            }
            zslEnabled = false

            synchronized(rawCaptureLock) {
                pendingRawImage?.close()
                pendingRawImage = null
                pendingThumbnailImage?.close()
                pendingThumbnailImage = null
            }

            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            rawImageReader?.close()
            rawImageReader = null
            thumbnailImageReader?.close()
            thumbnailImageReader = null
            zslImageReader?.close()
            zslImageReader = null
            previewSurface?.release()
            previewSurface = null
            releaseRecorderSurface()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error during shutdown", e)
        } finally {
            cameraOpenCloseLock.release()
        }

        stopBackgroundThread()
        coroutineScope.cancel()
        conversionExecutor.shutdownNow()
        sessionExecutor.shutdownNow()
        try {
            if (!conversionExecutor.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Conversion executor did not terminate in time")
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while awaiting executor termination")
        }
        try {
            if (!sessionExecutor.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Session executor did not terminate in time")
            }
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while awaiting session executor termination")
        }

        synchronized(captureLock) {
            if (pendingCaptureCount > 0) {
                Log.w(TAG, "Shutdown with $pendingCaptureCount pending captures")
            }
        }

        Log.e(TAG, "Camera shutdown complete")
    }

    private fun postToast(message: String) {
        coroutineScope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFormatName(format: Int): String =
        when (format) {
            OUTPUT_FORMAT_JPEG -> "JPEG"
            OUTPUT_FORMAT_RAW -> "RAW"
            else -> "UNKNOWN"
        }
}
