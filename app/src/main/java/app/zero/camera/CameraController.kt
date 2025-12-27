package app.zero.camera

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
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
import androidx.lifecycle.LifecycleOwner
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraController {
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var context: Context? = null

    fun createPreviewView(context: Context): PreviewView {
        this.context = context
        return PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    fun bindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
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

            // Get camera info to check capabilities
            val availableCameras = cameraProvider.availableCameraInfos.filter { cameraInfo ->
                cameraSelector.filter(listOf(cameraInfo)).isNotEmpty()
            }

            val cameraInfo = availableCameras.firstOrNull()
            if (cameraInfo == null) {
                Log.e(TAG, "No camera found matching selector")
                return@addListener
            }

            // Check RAW capabilities
            val capabilities = ImageCapture.getImageCaptureCapabilities(cameraInfo)
            val supportsRaw = capabilities.supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW)

            Log.d(TAG, "Camera supports RAW: $supportsRaw")
            Log.d(TAG, "Supported formats: ${capabilities.supportedOutputFormats}")

            val builder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

            // Set output format to RAW if supported
            if (supportsRaw) {
                builder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW)
                Log.d(TAG, "RAW output format enabled")
            } else {
                Log.w(TAG, "RAW format not supported, falling back to JPEG")
            }

            imageCapture = builder.build()

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(context))
    }

    fun takePhoto() {
        val imageCapture = imageCapture
        val context = context

        if (imageCapture == null) {
            Log.e(TAG, "takePhoto: imageCapture is null")
            return
        }
        if (context == null) {
            Log.e(TAG, "takePhoto: context is null")
            return
        }

        Log.d(TAG, "takePhoto: Starting RAW capture")

        // Prepare file name
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis()) + ".dng"

        // Create ContentValues for MediaStore
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Zero")
            }
        }

        // Create output options
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        // Take photo with file-based API (CameraX handles DNG creation internally)
        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val uri = outputFileResults.savedUri
                    Log.d(TAG, "RAW photo saved: $uri")
                    postToast("RAW photo saved")
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "RAW Capture failed", exception)
                    postToast("Capture failed: ${exception.message}")
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
        // Trigger center focus if needed
        val cameraControl = camera?.cameraControl ?: return
        val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(0.5f, 0.5f)
        val action = FocusMeteringAction.Builder(point).build()
        cameraControl.startFocusAndMetering(action)
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "ZeroCamera"
        private const val FILENAME_FORMAT = "'ZERO_'yyyyMMdd_HHmmss"
    }
}
