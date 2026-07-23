package com.nuvio.app.features.livetv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.iptv.XtreamProgram
import com.nuvio.app.features.player.EnterImmersivePlayerMode
import com.nuvio.app.features.player.PlatformPlayerSurface
import com.nuvio.app.features.player.PlayerEngineController
import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import com.nuvio.app.features.player.PlayerResizeMode
import com.nuvio.app.features.trakt.TraktPlatformClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dedicated Live TV experience. Portrait shows a docked 16:9 player over an EPG timeline guide;
 * landscape (via rotation or the fullscreen button) fills the screen with the video. Channel taps
 * in the guide switch playback in place — no re-navigation.
 */
@Composable
fun LiveTvScreen(
    initialContentId: String,
    initialTitle: String,
    initialLogo: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors

    var currentContentId by remember { mutableStateOf(initialContentId) }
    var currentTitle by remember { mutableStateOf(initialTitle) }
    var currentLogo by remember { mutableStateOf(initialLogo) }

    var source by remember { mutableStateOf<LiveChannelSource?>(null) }
    var resolveError by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var snapshot by remember { mutableStateOf(PlayerPlaybackSnapshot()) }
    var controller by remember { mutableStateOf<PlayerEngineController?>(null) }
    var retryTick by remember { mutableStateOf(0) }

    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var channels by remember { mutableStateOf<List<LiveGuideChannel>>(emptyList()) }
    val programmes = remember { mutableStateMapOf<String, List<XtreamProgram>>() }
    val requestedProgrammes = remember { mutableSetOf<String>() }

    var nowMs by remember { mutableStateOf(TraktPlatformClock.nowEpochMs()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = TraktPlatformClock.nowEpochMs()
            delay(30_000)
        }
    }

    // Resolve (or re-resolve on channel switch / retry) the playable source.
    LaunchedEffect(currentContentId, retryTick) {
        source = null
        resolveError = false
        playbackError = null
        val resolved = LiveTvData.resolveSource(currentContentId, currentTitle, currentLogo)
        if (resolved == null) resolveError = true else source = resolved
    }
    val onRetry: () -> Unit = {
        if (playbackError != null && source != null) controller?.retry() else retryTick++
    }

    // Guide channel column (once, from the launch channel's account).
    LaunchedEffect(initialContentId) {
        channels = LiveTvData.guideChannels(initialContentId)
    }

    // Load programmes for any channel that asks (lazy, cached, de-duped).
    val onNeedProgrammes: (String) -> Unit = { contentId ->
        if (requestedProgrammes.add(contentId)) {
            scope.launch {
                val list = LiveTvData.programmes(contentId)
                if (list.isNotEmpty()) programmes[contentId] = list
            }
        }
    }
    // Always keep the current channel's programmes warm for the now-bar.
    LaunchedEffect(currentContentId) { onNeedProgrammes(currentContentId) }

    fun switchTo(channel: LiveGuideChannel) {
        if (channel.contentId == currentContentId) return
        currentContentId = channel.contentId
        currentTitle = channel.name
        currentLogo = channel.logo
    }

    // ---- Orientation / fullscreen state ----
    val physicalLandscape by rememberPhysicalLandscape()
    var manualOrientation by remember { mutableStateOf<Boolean?>(null) } // true=landscape,false=portrait,null=follow
    // Hand control back to the sensor once the device physically agrees with a forced rotation.
    LaunchedEffect(physicalLandscape, manualOrientation) {
        val manual = manualOrientation
        val physical = physicalLandscape
        if (manual != null && physical != null && physical == manual) {
            manualOrientation = null
        }
    }
    val orientationMode = when (manualOrientation) {
        true -> LiveOrientationMode.ForceLandscape
        false -> LiveOrientationMode.ForcePortrait
        null -> LiveOrientationMode.Sensor
    }
    ApplyLiveOrientation(orientationMode)

    val nowNext = remember(programmes[currentContentId], nowMs) {
        nowNextOf(programmes[currentContentId], nowMs)
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(colors.surface),
    ) {
        val fullscreen = maxWidth > maxHeight

        // Back in fullscreen exits fullscreen instead of leaving the screen.
        PlatformBackHandler(enabled = fullscreen) { manualOrientation = false }

        if (fullscreen) {
            EnterImmersivePlayerMode(keepScreenAwake = snapshot.isPlaying || snapshot.isLoading)
            FullscreenLiveLayout(
                title = currentTitle,
                source = source,
                resolveError = resolveError,
                playbackError = playbackError,
                snapshot = snapshot,
                colors = colors,
                onControllerReady = { controller = it },
                onSnapshot = { snapshot = it },
                onError = { playbackError = it },
                onPlayPause = { if (snapshot.isPlaying) controller?.pause() else controller?.play() },
                onRetry = onRetry,
                onExitFullscreen = { manualOrientation = false },
                onBack = onBack,
            )
        } else {
            DockedLiveLayout(
                title = currentTitle,
                logo = currentLogo,
                nowNext = nowNext,
                source = source,
                resolveError = resolveError,
                playbackError = playbackError,
                snapshot = snapshot,
                channels = channels,
                currentContentId = currentContentId,
                nowMs = nowMs,
                programmesOf = { programmes[it] },
                onNeedProgrammes = onNeedProgrammes,
                onSelectChannel = ::switchTo,
                colors = colors,
                onControllerReady = { controller = it },
                onSnapshot = { snapshot = it },
                onError = { playbackError = it },
                onPlayPause = { if (snapshot.isPlaying) controller?.pause() else controller?.play() },
                onRetry = onRetry,
                onEnterFullscreen = { manualOrientation = true },
                onBack = onBack,
            )
        }
    }
}

/** now + next titles for the current channel, for the docked now-bar. */
private data class NowNext(val now: XtreamProgram?, val next: XtreamProgram?)

private fun nowNextOf(list: List<XtreamProgram>?, nowMs: Long): NowNext {
    if (list.isNullOrEmpty()) return NowNext(null, null)
    val sorted = list.sortedBy { it.startMs }
    val nowIdx = sorted.indexOfFirst { nowMs in it.startMs until it.endMs }
        .takeIf { it >= 0 }
        ?: sorted.indexOfFirst { it.startMs > nowMs }.takeIf { it >= 0 }?.let { it - 1 }
        ?: 0
    return NowNext(sorted.getOrNull(nowIdx), sorted.getOrNull(nowIdx + 1))
}

// ---------------------------------------------------------------------------------------------
// Docked (portrait) layout
// ---------------------------------------------------------------------------------------------

@Composable
private fun DockedLiveLayout(
    title: String,
    logo: String?,
    nowNext: NowNext,
    source: LiveChannelSource?,
    resolveError: Boolean,
    playbackError: String?,
    snapshot: PlayerPlaybackSnapshot,
    channels: List<LiveGuideChannel>,
    currentContentId: String,
    nowMs: Long,
    programmesOf: (String) -> List<XtreamProgram>?,
    onNeedProgrammes: (String) -> Unit,
    onSelectChannel: (LiveGuideChannel) -> Unit,
    colors: com.nuvio.app.core.ui.NuvioColorTokens,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
    onPlayPause: () -> Unit,
    onRetry: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            LivePlayerSurface(
                source = source,
                onControllerReady = onControllerReady,
                onSnapshot = onSnapshot,
                onError = onError,
            )
            DockedPlayerOverlay(
                title = title,
                snapshot = snapshot,
                resolveError = resolveError,
                playbackError = playbackError,
                accent = colors.accent,
                danger = colors.danger,
                onPlayPause = onPlayPause,
                onRetry = onRetry,
                onEnterFullscreen = onEnterFullscreen,
                onBack = onBack,
            )
        }

        NowBar(
            logo = logo,
            title = title,
            nowNext = nowNext,
            nowMs = nowMs,
            colors = colors,
        )

        LiveGuideGrid(
            channels = channels,
            currentContentId = currentContentId,
            nowMs = nowMs,
            programmesOf = programmesOf,
            onNeedProgrammes = onNeedProgrammes,
            onSelectChannel = onSelectChannel,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun NowBar(
    logo: String?,
    title: String,
    nowNext: NowNext,
    nowMs: Long,
    colors: com.nuvio.app.core.ui.NuvioColorTokens,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceElevated)
            .padding(horizontal = NuvioTokens.Space.s12, vertical = NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                .background(colors.surfaceCard),
            contentAlignment = Alignment.Center,
        ) {
            if (!logo.isNullOrBlank()) {
                AsyncImage(
                    model = logo,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize().padding(3.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = nowNext.now?.title ?: title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = buildString {
                nowNext.now?.let {
                    append(liveClockLabel(it.startMs))
                    append(" – ")
                    append(liveClockLabel(it.endMs))
                }
                nowNext.next?.let {
                    if (isNotEmpty()) append("   ")
                    append("Next: ")
                    append(it.title)
                }
            }.ifBlank { "No programme information" }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DockedPlayerOverlay(
    title: String,
    snapshot: PlayerPlaybackSnapshot,
    resolveError: Boolean,
    playbackError: String?,
    accent: Color,
    danger: Color,
    onPlayPause: () -> Unit,
    onRetry: () -> Unit,
    onEnterFullscreen: () -> Unit,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Top row: back + LIVE badge.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(NuvioTokens.Space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(NuvioTokens.Space.s8))
            LiveBadge(danger)
            Spacer(Modifier.weight(1f))
            OverlayIconButton(Icons.Filled.Fullscreen, "Fullscreen", onEnterFullscreen)
        }

        // Center: loading / error / play-pause.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                resolveError || playbackError != null -> ErrorPill(danger, onRetry)
                snapshot.isLoading && !snapshot.isPlaying -> CircularProgressIndicator(color = accent)
                else -> OverlayIconButton(
                    if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "Play/pause",
                    onPlayPause,
                    big = true,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Fullscreen (landscape) layout
// ---------------------------------------------------------------------------------------------

@Composable
private fun FullscreenLiveLayout(
    title: String,
    source: LiveChannelSource?,
    resolveError: Boolean,
    playbackError: String?,
    snapshot: PlayerPlaybackSnapshot,
    colors: com.nuvio.app.core.ui.NuvioColorTokens,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
    onPlayPause: () -> Unit,
    onRetry: () -> Unit,
    onExitFullscreen: () -> Unit,
    onBack: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    LaunchedEffect(controlsVisible, snapshot.isPlaying) {
        if (controlsVisible && snapshot.isPlaying) {
            delay(3500)
            controlsVisible = false
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { controlsVisible = !controlsVisible },
    ) {
        LivePlayerSurface(
            source = source,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
        when {
            resolveError || playbackError != null ->
                Box(Modifier.fillMaxSize(), Alignment.Center) { ErrorPill(colors.danger, onRetry) }
            snapshot.isLoading && !snapshot.isPlaying ->
                Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f))) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(NuvioTokens.Space.s12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OverlayIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
                    Spacer(Modifier.width(NuvioTokens.Space.s12))
                    LiveBadge(colors.danger)
                    Spacer(Modifier.width(NuvioTokens.Space.s12))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!(resolveError || playbackError != null) && !(snapshot.isLoading && !snapshot.isPlaying)) {
                    OverlayIconButton(
                        if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        "Play/pause",
                        onPlayPause,
                        big = true,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(NuvioTokens.Space.s16),
                ) {
                    OverlayIconButton(Icons.Filled.FullscreenExit, "Exit fullscreen", onExitFullscreen)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------------------------

@Composable
private fun LivePlayerSurface(
    source: LiveChannelSource?,
    onControllerReady: (PlayerEngineController) -> Unit,
    onSnapshot: (PlayerPlaybackSnapshot) -> Unit,
    onError: (String?) -> Unit,
) {
    val current = source ?: return
    // Key by url so a channel switch cleanly re-initialises the engine.
    androidx.compose.runtime.key(current.url) {
        PlatformPlayerSurface(
            sourceUrl = current.url,
            sourceHeaders = current.headers,
            streamType = "live",
            modifier = Modifier.fillMaxSize(),
            playWhenReady = true,
            resizeMode = PlayerResizeMode.Fit,
            useNativeController = false,
            onControllerReady = onControllerReady,
            onSnapshot = onSnapshot,
            onError = onError,
        )
    }
}

@Composable
private fun LiveBadge(danger: Color) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
            .background(danger)
            .padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.White))
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ErrorPill(danger: Color, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(NuvioTokens.Radius.md))
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(onClick = onRetry)
            .padding(horizontal = NuvioTokens.Space.s16, vertical = NuvioTokens.Space.s10),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
    ) {
        Icon(Icons.Filled.Refresh, contentDescription = null, tint = danger)
        Text(
            text = "Can't play this channel · Tap to retry",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun OverlayIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    big: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(if (big) 64.dp else 40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(if (big) 36.dp else 22.dp),
        )
    }
}
