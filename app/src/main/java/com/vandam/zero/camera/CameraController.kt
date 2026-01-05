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
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import com.vandam.zero.BuildConfig
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
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

        const val OUTPUT_FORMAT_JPEG = 0
        const val OUTPUT_FORMAT_RAW = 2
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraId: String? = null
    private var cameraCharacteristics: CameraCharacteristics? = null

    private var jpegImageReader: ImageReader? = null
    private var rawImageReader: ImageReader? = null
    private var zslImageReader: ImageReader? = null

    private var zslEnabled: Boolean = false

    @Volatile private var latestZslImage: Image? = null
    private val zslLock = Object()

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cameraOpenCloseLock = Semaphore(1)

    private var textureView: TextureView? = null
    private var previewSurface: Surface? = null
    private var previewSize: Size? = null

    private var currentOutputFormat: Int = OUTPUT_FORMAT_JPEG
    private var flashEnabled: Boolean = false
    private var bwMode: Boolean = false
    private var fastMode: Boolean = false
    private var monoFlavor: Boolean = BuildConfig.MONOCHROME_MODE
    private var oisEnabled: Boolean = true

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

    private var captureStartTimestamp: Long = 0
    private var shutterTimestamp: Long = 0

    private var pendingCaptureCount = 0
    private val captureLock = Object()

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var onCameraReadyCallback: (() -> Unit)? = null
    private var onFormatsAvailableCallback: ((List<Int>) -> Unit)? = null

    private val identityTonemapCurve =
        TonemapCurve(
            floatArrayOf(0f, 0f, 1f, 1f),
            floatArrayOf(0f, 0f, 1f, 1f),
            floatArrayOf(0f, 0f, 1f, 1f),
        )

    private var previewRequestBuilder: CaptureRequest.Builder? = null

    fun getIsoRange(): IntRange? = isoRange?.let { it.lower..it.upper }

    fun getExposureTimeRange(): LongRange? = exposureTimeRange?.let { it.lower..it.upper }

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
                Log.d(TAG, "Surface texture available: ${width}x$height")
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int,
            ) {
                Log.d(TAG, "Surface texture size changed: ${width}x$height")
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                Log.d(TAG, "Surface texture destroyed")
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            }
        }

    fun setInitialOutputFormat(format: Int) {
        currentOutputFormat = format
        Log.d(TAG, "Initial output format set to: ${getFormatName(format)}")
    }

    fun bindCamera(
        textureView: TextureView,
        onFormatsAvailable: (List<Int>) -> Unit = {},
        onCameraReady: (() -> Unit)? = null,
    ) {
        this.textureView = textureView
        this.onCameraReadyCallback = onCameraReady
        this.onFormatsAvailableCallback = onFormatsAvailable

        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
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

    @Suppress("MissingPermission")
    private fun openCamera() {
        startBackgroundThread()

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        cameraId =
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }

        if (cameraId == null) {
            Log.e(TAG, "No back camera found")
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

        Log.d(TAG, "Camera capabilities - ISO: $isoRange, Exposure: $exposureTimeRange")
        Log.d(TAG, "AF regions: $maxAfRegions, AE regions: $maxAeRegions, Sensor orientation: $sensorOrientation")

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
        supportsRaw = rawSizes != null && rawSizes.isNotEmpty() && !BuildConfig.MONOCHROME_MODE

        Log.d(TAG, "RAW supported: $supportsRaw")

        val formats = mutableListOf<Int>()
        formats.add(OUTPUT_FORMAT_JPEG)
        if (supportsRaw) {
            formats.add(OUTPUT_FORMAT_RAW)
        }
        onFormatsAvailableCallback?.invoke(formats)

        setupImageReaders(chars, map)

        if (!cameraOpenCloseLock.tryAcquire(CAMERA_OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw RuntimeException("Timeout waiting to open camera")
        }

        try {
            cameraManager.openCamera(cameraId!!, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening camera", e)
            cameraOpenCloseLock.release()
        }
    }

    private fun setupImageReaders(
        chars: CameraCharacteristics,
        map: android.hardware.camera2.params.StreamConfigurationMap?,
    ) {
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)
        val jpegSize = jpegSizes?.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
        Log.d(TAG, "JPEG capture size: ${jpegSize.width}x${jpegSize.height}")

        jpegImageReader =
            ImageReader
                .newInstance(
                    jpegSize.width,
                    jpegSize.height,
                    ImageFormat.JPEG,
                    2,
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            handleJpegCapture(image)
                        }
                    }, backgroundHandler)
                }

        if (supportsRaw) {
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            val rawSize = rawSizes?.maxByOrNull { it.width * it.height } ?: Size(4000, 3000)
            Log.d(TAG, "RAW capture size: ${rawSize.width}x${rawSize.height}")

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
                                handleRawCapture(image)
                            }
                        }, backgroundHandler)
                    }
        }

        val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)
        val zslSize = yuvSizes?.maxByOrNull { it.width * it.height } ?: jpegSize
        Log.d(TAG, "ZSL (YUV) capture size: ${zslSize.width}x${zslSize.height}")

        zslImageReader =
            ImageReader
                .newInstance(
                    zslSize.width,
                    zslSize.height,
                    ImageFormat.YUV_420_888,
                    2,
                ).apply {
                    setOnImageAvailableListener({ reader ->
                        if (zslEnabled) {
                            synchronized(zslLock) {
                                latestZslImage?.close()
                                latestZslImage = reader.acquireLatestImage()
                            }
                        } else {
                            reader.acquireLatestImage()?.close()
                        }
                    }, backgroundHandler)
                }

        val displaySizes = map?.getOutputSizes(SurfaceTexture::class.java)
        previewSize = chooseOptimalPreviewSize(displaySizes, textureView?.width ?: 1080, textureView?.height ?: 1920)
        Log.d(TAG, "Preview size: ${previewSize?.width}x${previewSize?.height}")
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
                    Math.abs(ratio - targetRatio) < tolerance && size.width <= idealWidth
                }.sortedByDescending { it.width * it.height }

        return suitable.firstOrNull() ?: choices.maxByOrNull { it.width * it.height } ?: Size(1440, 1080)
    }

    private val stateCallback =
        object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraOpenCloseLock.release()
                cameraDevice = camera
                createCaptureSession()
            }

            override fun onDisconnected(camera: CameraDevice) {
                cameraOpenCloseLock.release()
                camera.close()
                cameraDevice = null
            }

            override fun onError(
                camera: CameraDevice,
                error: Int,
            ) {
                cameraOpenCloseLock.release()
                camera.close()
                cameraDevice = null
                Log.e(TAG, "Camera error: $error")
            }
        }

    private fun createCaptureSession() {
        val camera = cameraDevice ?: return
        val texture = textureView?.surfaceTexture ?: return
        val preview = previewSize ?: return

        texture.setDefaultBufferSize(preview.width, preview.height)
        previewSurface = Surface(texture)

        val surfaces = mutableListOf<Surface>()
        surfaces.add(previewSurface!!)
        jpegImageReader?.surface?.let { surfaces.add(it) }
        rawImageReader?.surface?.let { surfaces.add(it) }

        if (fastMode) {
            zslImageReader?.surface?.let { surfaces.add(it) }
            Log.d(TAG, "Session config: Preview + JPEG + RAW + ZSL(YUV) [fast mode]")
        } else {
            Log.d(TAG, "Session config: Preview + JPEG + RAW [normal mode]")
        }

        try {
            val stateCallback =
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cameraDevice == null) return
                        captureSession = session
                        startPreview()

                        applyGrayscaleFilterToPreview()

                        coroutineScope.launch(Dispatchers.Main) {
                            onCameraReadyCallback?.invoke()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        postToast("Camera configuration failed")
                    }
                }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfigs = surfaces.map { OutputConfiguration(it) }
                val sessionConfig =
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputConfigs,
                        Executors.newSingleThreadExecutor(),
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

    private fun startPreview() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val surface = previewSurface ?: return

        try {
            if (fastMode && zslImageReader != null) {
                previewRequestBuilder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG).apply {
                        addTarget(surface)
                        addTarget(zslImageReader!!.surface)
                        applyCommonSettings(this)
                    }
                zslEnabled = true
                Log.d(TAG, "Preview started with ZSL [fast mode]")
            } else {
                previewRequestBuilder =
                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        applyCommonSettings(this)
                    }
                zslEnabled = false
                Log.d(TAG, "Preview started [normal mode]")
            }

            session.setRepeatingRequest(previewRequestBuilder!!.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error starting preview", e)
        }
    }

    /**
     * Applies common capture settings based on current mode.
     */
    private fun applyCommonSettings(builder: CaptureRequest.Builder) {
        if (fastMode) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, HYPERFOCAL_DIOPTERS)
        } else {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        if (autoExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation)
        } else {
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

        val oisMode = if (oisEnabled) CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON else CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, oisMode)
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
            Log.d(TAG, "takePhoto: Starting capture (pending: $pendingCaptureCount)")
        }

        onCaptureStartedCallback = onCaptureStarted
        onPreviewReadyCallback = onPreviewReady
        onCompleteCallback = onComplete
        onBenchmarkCallback = onBenchmark

        captureStartTimestamp = System.currentTimeMillis()

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
        try {
            val captureBuilder =
                camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(targetSurface)
                    applyCommonSettings(this)

                    // Override flash settings for capture
                    if (flashEnabled) {
                        if (autoExposure) {
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                        }
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                    }

                    // Disable lens shading compensation for minimal processing
                    set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)

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

    /**
     * JPEG capture - applies the same settings used in preview to ensure consistency.
     */
    private fun takeVanillaJpegPhoto() {
        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val jpegSurface = jpegImageReader?.surface ?: return

        // If flash is enabled in auto exposure mode, run precapture sequence first
        if (flashEnabled && autoExposure) {
            runPrecaptureSequence {
                captureJpegWithCurrentSettings(camera, session, jpegSurface)
            }
        } else {
            captureJpegWithCurrentSettings(camera, session, jpegSurface)
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
                        // Reset trigger
                        builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)

                        // Check AE state - wait for converged or flash required
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        Log.d(TAG, "Precapture AE state: $aeState")

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

    /**
     * Captures JPEG with current settings (called after precapture if needed).
     */
    private fun captureJpegWithCurrentSettings(
        camera: CameraDevice,
        session: CameraCaptureSession,
        jpegSurface: Surface,
    ) {
        try {
            val captureBuilder =
                camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(jpegSurface)

                    set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation())

                    // Apply the same settings used in preview for consistency
                    applyCommonSettings(this)

                    // Override flash settings for capture
                    if (flashEnabled) {
                        if (autoExposure) {
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                        }
                        set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE)
                    }

                    // Disable lens shading compensation for minimal processing
                    set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)

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
                "Taking JPEG photo (autoExposure=$autoExposure, flash=$flashEnabled, ISO=$manualIso, shutter=${manualExposureTimeNs}ns)",
            )
            session.capture(captureBuilder.build(), captureCallback, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error taking JPEG photo", e)
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

    /**
     * ZSL capture - grabs the latest YUV frame and converts to JPEG.
     * This provides near-instant shutter response.
     */
    private fun takeZslPhoto() {
        val image: Image?

        synchronized(zslLock) {
            image = latestZslImage
            latestZslImage = null
        }

        if (image == null) {
            Log.w(TAG, "ZSL: No image available in buffer, waiting...")
            coroutineScope.launch(Dispatchers.IO) {
                delay(100)
                val retryImage =
                    synchronized(zslLock) {
                        val img = latestZslImage
                        latestZslImage = null
                        img
                    }
                if (retryImage != null) {
                    processZslCapture(retryImage)
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

        processZslCapture(image)
    }

    private fun processZslCapture(image: Image) {
        shutterTimestamp = System.currentTimeMillis()
        val bufferGrabLatency = shutterTimestamp - captureStartTimestamp

        coroutineScope.launch(Dispatchers.Main) {
            onCaptureStartedCallback?.invoke()
        }

        coroutineScope.launch(Dispatchers.IO) {
            val conversionStart = System.currentTimeMillis()
            val bytes = yuvToJpeg(image)
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

            val finalBytes =
                if (bwMode || monoFlavor) {
                    convertToGrayscaleJpeg(bytes)
                } else {
                    bytes
                }

            val saveStart = System.currentTimeMillis()
            val uri = saveJpegToStorage(finalBytes, writeExifOrientation = true)
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

    /**
     * Convert YUV_420_888 image to JPEG bytes.
     */
    private fun yuvToJpeg(image: Image): ByteArray? =
        try {
            val yuvBytes = yuv420ToNv21(image)
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
                100,
                outputStream,
            )
            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "YUV to JPEG conversion failed", e)
            null
        }

    /**
     * Convert YUV_420_888 to NV21 format for YuvImage.
     */
    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21 = ByteArray(ySize + uvSize)

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val yRowStride = image.planes[0].rowStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride

        var pos = 0
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }

        val uvHeight = height / 2
        val uvWidth = width / 2

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uvIndex = row * uvRowStride + col * uvPixelStride
                nv21[ySize + row * width + col * 2] = vBuffer.get(uvIndex)
                nv21[ySize + row * width + col * 2 + 1] = uBuffer.get(uvIndex)
            }
        }

        return nv21
    }

    private fun handleJpegCapture(image: Image) {
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        image.close()

        coroutineScope.launch(Dispatchers.IO) {
            val previewBitmap = extractPreviewBitmap(bytes)

            withContext(Dispatchers.Main) {
                onPreviewReadyCallback?.invoke(previewBitmap)
            }

            val finalBytes =
                if (bwMode || monoFlavor) {
                    convertToGrayscaleJpeg(bytes)
                } else {
                    bytes
                }

            val uri = saveJpegToStorage(finalBytes)

            val saveCompleteTime = System.currentTimeMillis()
            val shutterLatency = shutterTimestamp - captureStartTimestamp
            val saveLatency = saveCompleteTime - shutterTimestamp
            val totalLatency = saveCompleteTime - captureStartTimestamp
            val modeName = if (bwMode) "BW" else "JPG"
            Log.d(TAG, "Benchmark [$modeName]: shutter=${shutterLatency}ms, save=${saveLatency}ms, total=${totalLatency}ms")

            synchronized(captureLock) {
                pendingCaptureCount--
            }

            withContext(Dispatchers.Main) {
                onBenchmarkCallback?.invoke(shutterLatency, saveLatency)
                onCompleteCallback?.invoke(uri)
            }
        }
    }

    private fun handleRawCapture(image: Image) {
        coroutineScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                onPreviewReadyCallback?.invoke(null)
            }

            var attempts = 0
            while (lastCaptureResult == null && attempts < 50) {
                delay(10)
                attempts++
            }

            if (lastCaptureResult == null) {
                Log.e(TAG, "Timeout waiting for capture result for DNG")
                image.close()
                synchronized(captureLock) { pendingCaptureCount-- }
                withContext(Dispatchers.Main) {
                    onCompleteCallback?.invoke(null)
                }
                return@launch
            }

            val uri = saveDngToStorage(image)
            image.close()

            val saveCompleteTime = System.currentTimeMillis()
            val shutterLatency = shutterTimestamp - captureStartTimestamp
            val saveLatency = saveCompleteTime - shutterTimestamp
            val totalLatency = saveCompleteTime - captureStartTimestamp
            Log.d(TAG, "Benchmark [RAW]: shutter=${shutterLatency}ms, save=${saveLatency}ms, total=${totalLatency}ms")

            synchronized(captureLock) {
                pendingCaptureCount--
            }

            withContext(Dispatchers.Main) {
                onBenchmarkCallback?.invoke(shutterLatency, saveLatency)
                onCompleteCallback?.invoke(uri)
            }
        }
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

    private fun convertToGrayscaleJpeg(jpegBytes: ByteArray): ByteArray {
        return try {
            val decodeOptions =
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            val original = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, decodeOptions) ?: return jpegBytes

            val grayscale = GrayscaleConverter.toGrayscale(original, recycleSource = true)

            val outputStream = ByteArrayOutputStream()
            val success = grayscale.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            grayscale.recycle()

            if (success && outputStream.size() > 0) {
                outputStream.toByteArray()
            } else {
                jpegBytes
            }
        } catch (e: Exception) {
            Log.e(TAG, "Grayscale conversion failed", e)
            jpegBytes
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

    private fun saveDngToStorage(image: Image): Uri? {
        val chars =
            cameraCharacteristics ?: run {
                Log.e(TAG, "saveDngToStorage: cameraCharacteristics is null")
                return null
            }

        val captureResult =
            lastCaptureResult ?: run {
                Log.e(TAG, "saveDngToStorage: lastCaptureResult is null")
                return null
            }

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
                    dngCreator.setOrientation(getExifOrientation())
                    dngCreator.writeImage(outputStream, image)
                    dngCreator.close()
                }
                Log.d(TAG, "DNG saved successfully: $uri")
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

    private fun getJpegOrientation(): Int {
        val deviceDegrees =
            when (currentRotation) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

        val jpegOrientation = (sensorOrientation - deviceDegrees + 360) % 360
        Log.d(TAG, "JPEG orientation: sensor=$sensorOrientation, device=$deviceDegrees, result=$jpegOrientation")
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
        Log.d(TAG, "Flash ${if (enabled) "enabled" else "disabled"}")
        refreshFlashState()
    }

    fun setOisEnabled(enabled: Boolean) {
        if (oisEnabled == enabled) return
        oisEnabled = enabled
        Log.d(TAG, "OIS ${if (enabled) "enabled" else "disabled"}")
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
        Log.d(TAG, "BW mode set to: $enabled")
        applyGrayscaleFilterToPreview()
    }

    fun setFastMode(enabled: Boolean) {
        if (fastMode == enabled) return

        if (!enabled) {
            synchronized(zslLock) {
                latestZslImage?.close()
                latestZslImage = null
            }
            zslEnabled = false
        }

        fastMode = enabled
        Log.d(TAG, "Fast (hyperfocal) mode set to: $enabled - recreating session")

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
        Log.d(TAG, "Output format changed to: ${getFormatName(format)}")
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
        Log.d(TAG, "Auto exposure: $enabled, EC index: $exposureCompensation")
        updatePreview()
    }

    fun setExposureCompensation(ev: Float) {
        val step = exposureCompensationStep
        exposureCompensation =
            (ev / step).toInt().coerceIn(
                exposureCompensationRange?.lower ?: -12,
                exposureCompensationRange?.upper ?: 12,
            )
        Log.d(TAG, "Exposure compensation: $ev EV (index: $exposureCompensation)")
        if (autoExposure) {
            updatePreview()
        }
    }

    fun setManualExposure(
        iso: Int,
        exposureTimeNs: Long,
    ) {
        autoExposure = false
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
        Log.d(TAG, "Manual exposure: ISO=$manualIso, shutter=${manualExposureTimeNs}ns")
        updatePreview()
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
            Log.d(TAG, "Tap to focus ignored in hyperfocal mode")
            return
        }

        val camera = cameraDevice ?: return
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        if (maxAfRegions <= 0) {
            Log.d(TAG, "AF regions not supported")
            triggerAutofocus()
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
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(focusRegion))
            if (maxAeRegions > 0) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(focusRegion))
            }
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)

            session.capture(builder.build(), null, backgroundHandler)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)

            Log.d(TAG, "Tap to focus at ($x, $y) -> sensor ($sensorX, $sensorY)")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error setting focus region", e)
        }
    }

    fun triggerFocus() {
        if (fastMode) {
            Log.d(TAG, "Trigger focus ignored in hyperfocal mode")
            return
        }
        triggerAutofocus()
    }

    private fun triggerAutofocus() {
        val session = captureSession ?: return
        val builder = previewRequestBuilder ?: return

        try {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session.capture(builder.build(), null, backgroundHandler)

            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Error triggering autofocus", e)
        }
    }

    // ===================
    // PREVIEW GRAYSCALE
    // ===================

    private fun applyGrayscaleFilterToPreview() {
        val tv = textureView ?: return
        val shouldApply = bwMode || monoFlavor

        tv.post {
            if (!shouldApply) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    tv.setRenderEffect(null)
                } else {
                    tv.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
                }
                Log.d(TAG, "Cleared grayscale filter from preview")
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
                Log.d(TAG, "Applied grayscale RenderEffect to preview")
            } else {
                val paint =
                    android.graphics.Paint().apply {
                        colorFilter = GrayscaleConverter.getColorFilter()
                    }
                tv.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, paint)
                Log.d(TAG, "Applied grayscale filter to preview (legacy)")
            }
        }
    }

    fun hasPendingCaptures(): Boolean {
        synchronized(captureLock) {
            return pendingCaptureCount > 0
        }
    }

    fun shutdown() {
        try {
            cameraOpenCloseLock.acquire()

            synchronized(zslLock) {
                latestZslImage?.close()
                latestZslImage = null
            }
            zslEnabled = false

            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
            jpegImageReader?.close()
            jpegImageReader = null
            rawImageReader?.close()
            rawImageReader = null
            zslImageReader?.close()
            zslImageReader = null
            previewSurface?.release()
            previewSurface = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error during shutdown", e)
        } finally {
            cameraOpenCloseLock.release()
        }

        stopBackgroundThread()
        coroutineScope.cancel()

        synchronized(captureLock) {
            if (pendingCaptureCount > 0) {
                Log.w(TAG, "Shutdown with $pendingCaptureCount pending captures")
            }
        }

        Log.d(TAG, "Camera shutdown complete")
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
