package com.nuvio.app.features.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import platform.Foundation.NSNotificationCenter
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIDeviceOrientationDidChangeNotification

// Mirrors the notification contract in iosApp/OrientationLockCoordinator.swift.
private const val LOCK_LANDSCAPE = "NuvioPlayerLockLandscape"
private const val LOCK_PORTRAIT = "NuvioPlayerLockPortrait"
private const val UNLOCK_ORIENTATION = "NuvioPlayerUnlockOrientation"

@Composable
actual fun ApplyLiveOrientation(mode: LiveOrientationMode) {
    DisposableEffect(mode) {
        val name = when (mode) {
            LiveOrientationMode.Sensor -> UNLOCK_ORIENTATION
            LiveOrientationMode.ForceLandscape -> LOCK_LANDSCAPE
            LiveOrientationMode.ForcePortrait -> LOCK_PORTRAIT
        }
        NSNotificationCenter.defaultCenter.postNotificationName(name, null)
        onDispose {
            // Leaving any forced mode returns the app to free rotation (matches the VOD player).
            NSNotificationCenter.defaultCenter.postNotificationName(UNLOCK_ORIENTATION, null)
        }
    }
}

@Composable
actual fun rememberPhysicalLandscape(): State<Boolean?> {
    val state = remember { mutableStateOf<Boolean?>(null) }
    DisposableEffect(Unit) {
        val device = UIDevice.currentDevice
        device.beginGeneratingDeviceOrientationNotifications()
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIDeviceOrientationDidChangeNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            state.value = when (device.orientation) {
                UIDeviceOrientation.UIDeviceOrientationLandscapeLeft,
                UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> true
                UIDeviceOrientation.UIDeviceOrientationPortrait,
                UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown -> false
                else -> state.value
            }
        }
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
            device.endGeneratingDeviceOrientationNotifications()
        }
    }
    return state
}
