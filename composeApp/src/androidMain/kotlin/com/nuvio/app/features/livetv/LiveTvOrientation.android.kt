package com.nuvio.app.features.livetv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.OrientationEventListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun ApplyLiveOrientation(mode: LiveOrientationMode) {
    val activity = LocalContext.current.findLiveActivity() ?: return
    DisposableEffect(activity, mode) {
        val previous = activity.requestedOrientation
        activity.requestedOrientation = when (mode) {
            // SENSOR (not USER) so rotate-to-fullscreen works even when the user's auto-rotate is
            // off — deliberately entering a video screen implies wanting rotation there.
            LiveOrientationMode.Sensor -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            LiveOrientationMode.ForceLandscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            LiveOrientationMode.ForcePortrait -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
        onDispose { activity.requestedOrientation = previous }
    }
}

@Composable
actual fun rememberPhysicalLandscape(): State<Boolean?> {
    val context = LocalContext.current
    val state = remember { mutableStateOf<Boolean?>(null) }
    DisposableEffect(context) {
        // Reads the raw accelerometer angle, so it reports the true device orientation even while
        // the window is pinned by ForceLandscape/ForcePortrait.
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                when {
                    orientation in 60..120 || orientation in 240..300 -> state.value = true
                    orientation < 30 || orientation > 330 || orientation in 150..210 -> state.value = false
                    else -> Unit // near-diagonal: keep the previous reading
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
    return state
}

private tailrec fun Context.findLiveActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findLiveActivity()
        else -> null
    }
