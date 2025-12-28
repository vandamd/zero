package com.vandam.zero.camera

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DelayedLifecycleOwner(private val parent: LifecycleOwner) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private var pendingState: Lifecycle.State? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var delayJob: Job? = null

    var shouldDelayStop: () -> Boolean = { false }

    init {
        parent.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (shouldDelayStop()) {
                        Log.w("ZeroCamera", "Delaying lifecycle STOP due to pending captures")
                        pendingState = Lifecycle.State.CREATED
                        scheduleDelayedStop()
                    } else {
                        registry.handleLifecycleEvent(event)
                    }
                }
                else -> registry.handleLifecycleEvent(event)
            }
        })
    }

    private fun scheduleDelayedStop() {
        delayJob?.cancel()
        delayJob = coroutineScope.launch {
            var attempts = 0
            while (shouldDelayStop() && attempts < 50) {
                delay(100)
                attempts++
            }

            if (pendingState == Lifecycle.State.CREATED) {
                Log.d("ZeroCamera", "Proceeding with delayed lifecycle STOP")
                registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                pendingState = null
            }
        }
    }

    override val lifecycle: Lifecycle
        get() = registry
}

class CameraController {
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var context: Context? = null
    private var orientationEventListener: OrientationEventListener? = null
    private var currentRotation: Int = Surface.ROTATION_0
    private var currentOutputFormat: Int = ImageCapture.OUTPUT_FORMAT_JPEG
    private var lifecycleOwner: LifecycleOwner? = null
    private var delayedLifecycleOwner: DelayedLifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var availableFormats: List<Int> = emptyList()
    private var rebindJob: Job? = null
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var flashEnabled: Boolean = false
    private var lifecycleObserverAdded: Boolean = false

    private var pendingCaptureCount = 0
    private val captureLock = Object()

    fun createPreviewView(context: Context): PreviewView {
        this.context = context
        return PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    fun setInitialOutputFormat(format: Int) {
        currentOutputFormat = format
        Log.d(TAG, "Initial output format set to: $format")
    }

    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onFormatsAvailable: (List<Int>) -> Unit = {},
        onCameraReady: () -> Unit = {}
    ) {
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView

        if (delayedLifecycleOwner == null) {
            delayedLifecycleOwner = DelayedLifecycleOwner(lifecycleOwner).apply {
                shouldDelayStop = { hasPendingCaptures() }
            }
        }

        if (!lifecycleObserverAdded) {
            lifecycleObserverAdded = true
            lifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        orientationEventListener?.enable()
                        Log.d(TAG, "Orientation listener enabled (app resumed)")
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        orientationEventListener?.disable()
                        Log.d(TAG, "Orientation listener disabled (app paused)")
                    }
                    else -> {}
                }
            })
        }

        val context = previewView.context
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            val availableCameras = cameraProvider.availableCameraInfos.filter { cameraInfo ->
                cameraSelector.filter(listOf(cameraInfo)).isNotEmpty()
            }

            val cameraInfo = availableCameras.firstOrNull()
            if (cameraInfo == null) {
                Log.e(TAG, "No camera found matching selector")
                return@addListener
            }

            val capabilities = ImageCapture.getImageCaptureCapabilities(cameraInfo)
            val supportedFormats = capabilities.supportedOutputFormats.toList()

            Log.d(TAG, "Supported formats from device: $supportedFormats")
            Log.d(TAG, "OUTPUT_FORMAT_JPEG = ${ImageCapture.OUTPUT_FORMAT_JPEG}")
            Log.d(TAG, "OUTPUT_FORMAT_RAW = ${ImageCapture.OUTPUT_FORMAT_RAW}")

            val supportsRaw = supportedFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW)
            val supportsJpeg = supportedFormats.contains(ImageCapture.OUTPUT_FORMAT_JPEG)

            val workingFormats = mutableListOf<Int>()
            if (supportsRaw) workingFormats.add(ImageCapture.OUTPUT_FORMAT_RAW)
            if (supportsJpeg) workingFormats.add(ImageCapture.OUTPUT_FORMAT_JPEG)

            availableFormats = workingFormats

            Log.d(TAG, "Available formats: RAW=$supportsRaw, JPEG=$supportsJpeg")
            Log.d(TAG, "Working formats list: $workingFormats")

            onFormatsAvailable(workingFormats)

            currentRotation = previewView.display?.rotation ?: Surface.ROTATION_0

            val builder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetRotation(currentRotation)

            if (!workingFormats.contains(currentOutputFormat)) {
                currentOutputFormat = when {
                    supportsRaw -> ImageCapture.OUTPUT_FORMAT_RAW
                    supportsJpeg -> ImageCapture.OUTPUT_FORMAT_JPEG
                    else -> ImageCapture.OUTPUT_FORMAT_JPEG
                }
            }

            val formatName = if (currentOutputFormat == ImageCapture.OUTPUT_FORMAT_RAW) "RAW" else "JPEG"
            Log.d(TAG, "Using output format: $formatName")

            builder.setOutputFormat(currentOutputFormat)

            if (flashEnabled) {
                builder.setFlashMode(ImageCapture.FLASH_MODE_ON)
            } else {
                builder.setFlashMode(ImageCapture.FLASH_MODE_OFF)
            }

            imageCapture = builder.build()

            setupOrientationListener(context)

            try {
                cameraProvider.unbindAll()

                camera = cameraProvider.bindToLifecycle(
                    delayedLifecycleOwner!!,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                Log.d(TAG, "Camera bound successfully with rotation: $currentRotation, flash: $flashEnabled")

                onCameraReady()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    private fun setupOrientationListener(context: Context) {
        orientationEventListener?.disable()

        orientationEventListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) {
                    return
                }

                val rotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }

                if (rotation != currentRotation) {
                    currentRotation = rotation
                    imageCapture?.targetRotation = rotation
                    Log.d(TAG, "Rotation updated to: $rotation (orientation: ${orientation}°)")
                }
            }
        }

        // Enable immediately if the lifecycle is already resumed
        val currentState = lifecycleOwner?.lifecycle?.currentState
        if (currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true) {
            orientationEventListener?.enable()
            Log.d(TAG, "Orientation listener enabled (app already resumed)")
        } else {
            Log.d(TAG, "Orientation listener created (will be enabled on resume)")
        }
    }

    fun takePhoto(onComplete: () -> Unit = {}) {
        val imageCapture = imageCapture
        val context = context

        if (imageCapture == null) {
            Log.e(TAG, "takePhoto: imageCapture is null")
            onComplete()
            return
        }
        if (context == null) {
            Log.e(TAG, "takePhoto: context is null")
            onComplete()
            return
        }

        synchronized(captureLock) {
            pendingCaptureCount++
            Log.d(TAG, "takePhoto: Starting capture (pending: $pendingCaptureCount)")
        }

        val (fileExtension, mimeType) = when (currentOutputFormat) {
            ImageCapture.OUTPUT_FORMAT_RAW -> ".dng" to "image/x-adobe-dng"
            else -> ".jpg" to "image/jpeg"
        }

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis()) + fileExtension

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Zero")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = outputFileResults.savedUri
                    val formatName = if (currentOutputFormat == ImageCapture.OUTPUT_FORMAT_RAW) "RAW" else "JPEG"
                    Log.d(TAG, "$formatName photo saved: $uri")

                    synchronized(captureLock) {
                        pendingCaptureCount--
                        Log.d(TAG, "Capture completed (pending: $pendingCaptureCount)")
                    }
                    onComplete()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    postToast("Capture failed: ${exception.message}")

                    synchronized(captureLock) {
                        pendingCaptureCount--
                        Log.d(TAG, "Capture failed (pending: $pendingCaptureCount)")
                    }
                    onComplete()
                }
            }
        )
    }

    private fun postToast(message: String) {
        context?.let { ctx ->
            ContextCompat.getMainExecutor(ctx).execute {
                Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun onTapToFocus(x: Float, y: Float, width: Float, height: Float) {
        val cameraControl = camera?.cameraControl ?: return
        val factory = SurfaceOrientedMeteringPointFactory(width, height)
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        cameraControl.startFocusAndMetering(action)
    }

    fun triggerFocus() {
        val cameraControl = camera?.cameraControl ?: return
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(0.5f, 0.5f)
        val action = FocusMeteringAction.Builder(point).build()
        cameraControl.startFocusAndMetering(action)
    }

    fun setExposureCompensation(ev: Float) {
        val cameraControl = camera?.cameraControl ?: return
        val cameraInfo = camera?.cameraInfo ?: return

        val exposureState = cameraInfo.exposureState
        val range = exposureState.exposureCompensationRange
        val step = exposureState.exposureCompensationStep

        val index = (ev / step.toFloat()).toInt().coerceIn(range.lower, range.upper)

        cameraControl.setExposureCompensationIndex(index)
        Log.d(TAG, "Set exposure compensation: EV=$ev, index=$index")
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun setAutoExposure(enabled: Boolean) {
        val cameraControl = camera?.cameraControl ?: return
        val camera2Control = Camera2CameraControl.from(cameraControl)

        if (enabled) {
            val captureRequestOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                .clearCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY)
                .clearCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME)
                .build()
            camera2Control.setCaptureRequestOptions(captureRequestOptions)
            Log.d(TAG, "Auto exposure enabled")
        }
    }

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun setManualExposure(iso: Int, exposureTimeNs: Long) {
        val cameraControl = camera?.cameraControl ?: return
        val camera2Control = Camera2CameraControl.from(cameraControl)

        val captureRequestOptions = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
            .build()

        camera2Control.setCaptureRequestOptions(captureRequestOptions)
        Log.d(TAG, "Set manual exposure: ISO=$iso, shutter=${exposureTimeNs}ns")
    }

    fun setOutputFormat(format: Int) {
        if (!availableFormats.contains(format)) {
            Log.w(TAG, "Format $format not supported, ignoring")
            return
        }

        if (currentOutputFormat == format) {
            Log.d(TAG, "Already using format $format, skipping rebind")
            return
        }

        currentOutputFormat = format
        Log.d(TAG, "Output format will change to: $format")

        rebindJob?.cancel()

        rebindJob = coroutineScope.launch {
            delay(500)
            val owner = lifecycleOwner
            val preview = previewView
            if (owner != null && preview != null) {
                Log.d(TAG, "Rebinding camera with format $format")
                bindCamera(owner, preview)
            }
        }
    }

    fun setFlashEnabled(enabled: Boolean) {
        flashEnabled = enabled
        Log.d(TAG, "Flash ${if (enabled) "enabled" else "disabled"}")

        imageCapture?.flashMode = if (enabled) {
            ImageCapture.FLASH_MODE_ON
        } else {
            ImageCapture.FLASH_MODE_OFF
        }
    }

    fun hasPendingCaptures(): Boolean {
        synchronized(captureLock) {
            return pendingCaptureCount > 0
        }
    }

    fun shutdown() {
        orientationEventListener?.disable()
        orientationEventListener = null

        synchronized(captureLock) {
            if (pendingCaptureCount > 0) {
                Log.w(TAG, "Shutdown requested with $pendingCaptureCount pending captures")
            }
        }

        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ZeroCamera"
        private const val FILENAME_FORMAT = "'ZERO_'yyyyMMdd_HHmmss"
    }
}
