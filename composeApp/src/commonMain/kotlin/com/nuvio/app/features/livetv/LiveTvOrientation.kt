package com.nuvio.app.features.livetv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State

/**
 * Orientation policy for the Live TV screen.
 *
 * [Sensor] lets the device drive rotation (so physically rotating the phone toggles fullscreen).
 * [ForceLandscape] / [ForcePortrait] are the button-driven overrides: they pin the app to one
 * orientation until the device physically catches up, at which point the screen hands control back
 * to [Sensor] (see [rememberPhysicalLandscape]). That coexistence is what makes rotate-to-fullscreen
 * and the fullscreen button both work.
 */
enum class LiveOrientationMode { Sensor, ForceLandscape, ForcePortrait }

/** Applies the requested orientation policy while this composable is in composition. */
@Composable
expect fun ApplyLiveOrientation(mode: LiveOrientationMode)

/**
 * Reactive PHYSICAL device orientation, independent of any app-level orientation lock.
 * true = device physically landscape, false = portrait, null = unknown/flat/face-up.
 */
@Composable
expect fun rememberPhysicalLandscape(): State<Boolean?>
