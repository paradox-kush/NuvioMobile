package com.nuvio.app.features.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.ui.unit.IntSize

internal object PlayerPictureInPictureManager {
    private data class SessionState(
        val isActive: Boolean = false,
        val isPlaying: Boolean = false,
        val playerSize: IntSize = IntSize.Zero,
    )

    private var sessionState = SessionState()

    fun updateSession(
        activity: Activity,
        isActive: Boolean,
        isPlaying: Boolean,
        playerSize: IntSize,
    ) {
        sessionState = SessionState(
            isActive = isActive,
            isPlaying = isPlaying,
            playerSize = playerSize,
        )
        applyPictureInPictureParams(activity)
    }

    fun clearSession(activity: Activity) {
        sessionState = SessionState()
        applyPictureInPictureParams(activity)
    }

    fun onUserLeaveHint(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return false
        }
        return enterIfEligible(activity)
    }

    private fun applyPictureInPictureParams(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        activity.setPictureInPictureParams(buildParams())
    }

    private fun enterIfEligible(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!sessionState.isActive || !sessionState.isPlaying) return false
        if (activity.isFinishing) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && activity.isInPictureInPictureMode) return false
        return activity.enterPictureInPictureMode(buildParams())
    }

    private fun buildParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        buildAspectRatio(sessionState.playerSize)?.let(builder::setAspectRatio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(sessionState.isActive && sessionState.isPlaying)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun buildAspectRatio(playerSize: IntSize): Rational? {
        if (playerSize.width <= 0 || playerSize.height <= 0) return null

        val width = playerSize.width.coerceAtLeast(1)
        val height = playerSize.height.coerceAtLeast(1)
        val ratio = width.toDouble() / height.toDouble()

        return when {
            ratio > MaxPictureInPictureAspectRatio -> Rational(239, 100)
            ratio < MinPictureInPictureAspectRatio -> Rational(100, 239)
            else -> Rational(width, height)
        }
    }
}

private const val MaxPictureInPictureAspectRatio = 2.39
private const val MinPictureInPictureAspectRatio = 1.0 / MaxPictureInPictureAspectRatio