package com.vandam.zero

import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.vandam.zero.ui.CameraScreen

class MainActivity : ComponentActivity() {
    private val viewModel: CameraViewModel by viewModels()
    private var wasGrayscaleEnabled = false
    private var previousDaltonizerMode = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.initialize(this)
        logColorFilterSettings()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CameraScreen(viewModel)
                }
            }
        }
    }

    override fun onPause() {
        if (viewModel.hasPendingCaptures()) {
            Log.w("ZeroLifecycle", "Activity pausing with pending captures!")
        }
        super.onPause()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d("ZeroKeys", "KeyDown: $keyCode")
        return when (keyCode) {
            KeyEvent.KEYCODE_CAMERA -> {
                if (event?.repeatCount == 0) {
                    Log.d("ZeroKeys", "Camera Button Pressed")
                    viewModel.onShutterButtonPress()
                }
                true
            }
            KeyEvent.KEYCODE_FOCUS -> {
                if (event?.repeatCount == 0) {
                    Log.d("ZeroKeys", "Focus Button Pressed")
                    viewModel.onFocusButtonPress()
                }
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d("ZeroKeys", "KeyUp: $keyCode")
        return when (keyCode) {
            KeyEvent.KEYCODE_FOCUS -> {
                viewModel.onFocusButtonRelease()
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    private fun logColorFilterSettings() {
        val daltonizerEnabled = Settings.Secure.getInt(
            contentResolver, "accessibility_display_daltonizer_enabled", 0
        )
        val daltonizerMode = Settings.Secure.getInt(
            contentResolver, "accessibility_display_daltonizer", 0
        )
        val inversionEnabled = Settings.Secure.getInt(
            contentResolver, "accessibility_display_inversion_enabled", 0
        )
        val nightDisplayEnabled = Settings.Secure.getInt(
            contentResolver, "night_display_activated", 0
        )

        val modeName = when (daltonizerMode) {
            0 -> "Disabled"
            11 -> "Grayscale"
            12 -> "Protanomaly (red-weak)"
            13 -> "Deuteranomaly (green-weak)"
            14 -> "Tritanomaly (blue-weak)"
            else -> "Unknown ($daltonizerMode)"
        }

        Log.d("ZeroColorFilter", "=== Color Filter Settings ===")
        Log.d("ZeroColorFilter", "Daltonizer enabled: ${daltonizerEnabled == 1}")
        Log.d("ZeroColorFilter", "Daltonizer mode: $modeName")
        Log.d("ZeroColorFilter", "Color inversion: ${inversionEnabled == 1}")
        Log.d("ZeroColorFilter", "Night display: ${nightDisplayEnabled == 1}")
    }

    private fun disableGrayscale() {
        val daltonizerEnabled = Settings.Secure.getInt(
            contentResolver, "accessibility_display_daltonizer_enabled", 0
        )
        val daltonizerMode = Settings.Secure.getInt(
            contentResolver, "accessibility_display_daltonizer", 0
        )

        // Store if daltonizer was enabled at all (any mode)
        wasGrayscaleEnabled = daltonizerEnabled == 1
        previousDaltonizerMode = daltonizerMode

        if (wasGrayscaleEnabled) {
            try {
                Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer_enabled", 0)
                Log.d("ZeroColorFilter", "Daltonizer disabled on app start (was mode: $daltonizerMode)")
            } catch (e: SecurityException) {
                Log.e("ZeroColorFilter", "No permission to change settings. Run: adb shell pm grant com.vandam.zero android.permission.WRITE_SECURE_SETTINGS")
            }
        } else {
            Log.d("ZeroColorFilter", "Daltonizer was not enabled, nothing to disable")
        }
    }

    private fun restoreGrayscale() {
        if (wasGrayscaleEnabled) {
            try {
                Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer", previousDaltonizerMode)
                Settings.Secure.putInt(contentResolver, "accessibility_display_daltonizer_enabled", 1)
                Log.d("ZeroColorFilter", "Daltonizer restored on app exit (mode: $previousDaltonizerMode)")
            } catch (e: SecurityException) {
                Log.e("ZeroColorFilter", "No permission to restore settings")
            }
        }
    }

    override fun onStop() {
        restoreGrayscale()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        disableGrayscale()
    }
}
