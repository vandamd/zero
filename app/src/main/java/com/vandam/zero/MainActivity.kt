package com.vandam.zero

import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.initialize(this)

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
}
