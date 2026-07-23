package com.nuvio.app.features.livetv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.iptv.XtreamProgram

private val CHANNEL_COL_WIDTH = 76.dp
private val ROW_HEIGHT = 64.dp
private val MINUTE_WIDTH = 3.5.dp
private const val WINDOW_HOURS = 5
private const val SLOT_MINUTES = 30

/**
 * TiviMate-style EPG timeline: a pinned channel column on the left, programme blocks laid out across
 * a shared horizontally-scrolling time axis, and a red now-line. Tapping any channel row switches
 * playback in place. Programme windows load lazily as rows scroll into view.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveGuideGrid(
    channels: List<LiveGuideChannel>,
    currentContentId: String,
    nowMs: Long,
    programmesOf: (String) -> List<XtreamProgram>?,
    onNeedProgrammes: (String) -> Unit,
    onSelectChannel: (LiveGuideChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.nuvio.colors
    // Window starts at the previous SLOT boundary so "now" sits a little in from the left edge.
    val windowStartMs = remember(nowMs) {
        val slotMs = SLOT_MINUTES * 60_000L
        (nowMs / slotMs) * slotMs
    }
    val windowEndMs = windowStartMs + WINDOW_HOURS * 60 * 60_000L
    val totalMinutes = (windowEndMs - windowStartMs) / 60_000L
    val totalWidth = MINUTE_WIDTH * totalMinutes.toInt()
    val timeScroll = rememberScrollState()

    Column(modifier = modifier.fillMaxSize()) {
        // Time axis header (day label pinned over the channel column, slot ticks scroll).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(colors.surfaceElevated),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.width(CHANNEL_COL_WIDTH).padding(start = NuvioTokens.Space.s12),
            ) {
                Text(
                    text = liveDayLabel(nowMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Box(modifier = Modifier.horizontalScroll(timeScroll)) {
                Row(modifier = Modifier.width(totalWidth)) {
                    val slots = (totalMinutes / SLOT_MINUTES).toInt()
                    repeat(slots) { i ->
                        val slotMs = windowStartMs + i.toLong() * SLOT_MINUTES * 60_000L
                        Text(
                            text = liveClockLabel(slotMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                            modifier = Modifier.width(MINUTE_WIDTH * SLOT_MINUTES),
                        )
                    }
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(channels, key = { it.contentId }) { channel ->
                LaunchedEffect(channel.contentId) { onNeedProgrammes(channel.contentId) }
                GuideRow(
                    channel = channel,
                    isCurrent = channel.contentId == currentContentId,
                    programmes = programmesOf(channel.contentId),
                    windowStartMs = windowStartMs,
                    windowEndMs = windowEndMs,
                    nowMs = nowMs,
                    totalWidth = totalWidth,
                    timeScroll = timeScroll,
                    accent = colors.accent,
                    onSecondary = colors.onAccent,
                    nowLineColor = colors.danger,
                    cardColor = colors.surfaceCard,
                    textPrimary = colors.textPrimary,
                    textMuted = colors.textMuted,
                    surface = colors.surface,
                    border = colors.borderSubtle,
                    onClick = { onSelectChannel(channel) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuideRow(
    channel: LiveGuideChannel,
    isCurrent: Boolean,
    programmes: List<XtreamProgram>?,
    windowStartMs: Long,
    windowEndMs: Long,
    nowMs: Long,
    totalWidth: Dp,
    timeScroll: androidx.compose.foundation.ScrollState,
    accent: Color,
    onSecondary: Color,
    nowLineColor: Color,
    cardColor: Color,
    textPrimary: Color,
    textMuted: Color,
    surface: Color,
    border: Color,
    onClick: () -> Unit,
) {
    val rowBg = if (isCurrent) accent.copy(alpha = 0.10f) else surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(rowBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pinned channel cell (logo + name). Whole row is the tap target for switching.
        Row(
            modifier = Modifier
                .width(CHANNEL_COL_WIDTH)
                .height(ROW_HEIGHT)
                .clickable(onClick = onClick)
                .padding(horizontal = NuvioTokens.Space.s6),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
        ) {
            Box(
                modifier = Modifier
                    .width(44.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(NuvioTokens.Radius.sm))
                    .background(cardColor),
                contentAlignment = Alignment.Center,
            ) {
                if (!channel.logo.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = channel.name,
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Text(
                        text = channel.name.take(3).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = textMuted,
                        maxLines = 1,
                    )
                }
            }
        }

        // Scrolling programme lane (shares the header's scroll state).
        Box(
            modifier = Modifier
                .weight(1f)
                .height(ROW_HEIGHT)
                .horizontalScroll(timeScroll),
        ) {
            Box(modifier = Modifier.width(totalWidth).height(ROW_HEIGHT)) {
                val progs = programmes
                if (progs.isNullOrEmpty()) {
                    ProgrammeBlock(
                        title = "No EPG",
                        startX = 0.dp,
                        width = totalWidth,
                        isNow = false,
                        accent = accent,
                        onSecondary = onSecondary,
                        cardColor = cardColor,
                        textPrimary = textMuted,
                        textMuted = textMuted,
                        border = border,
                    )
                } else {
                    progs.forEach { prog ->
                        val clampedStart = prog.startMs.coerceAtLeast(windowStartMs)
                        val clampedEnd = prog.endMs.coerceAtMost(windowEndMs)
                        if (clampedEnd <= windowStartMs || clampedStart >= windowEndMs) return@forEach
                        val startMin = (clampedStart - windowStartMs) / 60_000L
                        val widthMin = ((clampedEnd - clampedStart) / 60_000L).coerceAtLeast(1)
                        val isNow = nowMs in prog.startMs until prog.endMs
                        ProgrammeBlock(
                            title = prog.title,
                            startX = MINUTE_WIDTH * startMin.toInt(),
                            width = MINUTE_WIDTH * widthMin.toInt(),
                            isNow = isNow,
                            accent = accent,
                            onSecondary = onSecondary,
                            cardColor = cardColor,
                            textPrimary = textPrimary,
                            textMuted = textMuted,
                            border = border,
                        )
                    }
                }
                // Per-row now-line — together the rows form one continuous line that scrolls with EPG.
                if (nowMs in windowStartMs..windowEndMs) {
                    val nowMin = (nowMs - windowStartMs) / 60_000L
                    Box(
                        modifier = Modifier
                            .offset(x = MINUTE_WIDTH * nowMin.toInt())
                            .width(2.dp)
                            .height(ROW_HEIGHT)
                            .background(nowLineColor),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgrammeBlock(
    title: String,
    startX: Dp,
    width: Dp,
    isNow: Boolean,
    accent: Color,
    onSecondary: Color,
    cardColor: Color,
    textPrimary: Color,
    textMuted: Color,
    border: Color,
) {
    Box(
        modifier = Modifier
            .offset(x = startX)
            .width(width)
            .height(ROW_HEIGHT)
            .padding(1.dp)
            .clip(RoundedCornerShape(NuvioTokens.Radius.xs))
            .background(if (isNow) accent else cardColor),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = if (isNow) onSecondary else textPrimary,
            fontWeight = if (isNow) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s4),
        )
    }
}
