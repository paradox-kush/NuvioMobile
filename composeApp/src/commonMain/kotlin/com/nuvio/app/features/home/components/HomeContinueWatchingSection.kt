package com.nuvio.app.features.home.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioProgressBar
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.core.ui.posterCardClickable
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.ContinueWatchingSectionStyle
import kotlin.math.roundToInt

@Composable
fun HomeContinueWatchingSection(
    items: List<ContinueWatchingItem>,
    style: ContinueWatchingSectionStyle,
    modifier: Modifier = Modifier,
    onItemClick: ((ContinueWatchingItem) -> Unit)? = null,
    onItemLongPress: ((ContinueWatchingItem) -> Unit)? = null,
) {
    if (items.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val layout = rememberContinueWatchingLayout(maxWidth.value)
        val sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        NuvioShelfSection(
            title = "Continue Watching",
            entries = items,
            modifier = Modifier.fillMaxWidth(),
            headerHorizontalPadding = sectionPadding,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            itemSpacing = layout.itemGap,
            key = { item -> item.videoId },
        ) { item ->
            when (style) {
                ContinueWatchingSectionStyle.Wide -> ContinueWatchingWideCard(
                    item = item,
                    layout = layout,
                    onClick = onItemClick?.let { { it(item) } },
                    onLongClick = onItemLongPress?.let { { it(item) } },
                )
                ContinueWatchingSectionStyle.Poster -> ContinueWatchingPosterCard(
                    item = item,
                    layout = layout,
                    onClick = onItemClick?.let { { it(item) } },
                    onLongClick = onItemLongPress?.let { { it(item) } },
                )
            }
        }
    }
}

@Composable
fun ContinueWatchingStylePreview(
    style: ContinueWatchingSectionStyle,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    }
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (style) {
            ContinueWatchingSectionStyle.Wide -> WideCardPreview()
            ContinueWatchingSectionStyle.Poster -> PosterCardPreview()
        }
    }
}

@Composable
private fun WideCardPreview() {
    Row(
        modifier = Modifier
            .width(100.dp)
            .height(60.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)),
    ) {
        Box(
            modifier = Modifier
                .width(40.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(4.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
            )
            NuvioProgressBar(
                progress = 0.6f,
                modifier = Modifier.fillMaxWidth(),
                height = 4.dp,
                trackColor = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.16f),
            )
        }
    }
}

@Composable
private fun PosterCardPreview() {
    Box(
        modifier = Modifier
            .width(44.dp)
            .height(60.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
    ) {
        NuvioProgressBar(
            progress = 0.45f,
            modifier = Modifier.align(Alignment.BottomCenter),
            height = 4.dp,
            trackColor = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.16f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingWideCard(
    item: ContinueWatchingItem,
    layout: ContinueWatchingLayout,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .width(layout.wideCardWidth)
            .height(layout.wideCardHeight)
            .clip(RoundedCornerShape(layout.cardRadius))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .border(
                width = 1.5.dp,
                color = Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(layout.cardRadius),
            )
            .combinedClickable(
                enabled = onClick != null || onLongClick != null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick,
            ),
    ) {
        ArtworkPanel(
            imageUrl = item.imageUrl,
            width = layout.widePosterStripWidth,
            modifier = Modifier.fillMaxHeight(),
        )
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .padding(layout.wideContentPadding),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            val isEpisodeCard = item.seasonNumber != null && item.episodeNumber != null
            val hasEpisodeTitle = !item.episodeTitle.isNullOrBlank()
            val wideMetaLine = when {
                item.progressFraction <= 0f && isEpisodeCard -> "Up Next • S${item.seasonNumber}E${item.episodeNumber}"
                isEpisodeCard -> "S${item.seasonNumber}E${item.episodeNumber}"
                else -> item.subtitle
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = item.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = layout.wideTitleSize,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.progressFraction <= 0f && item.seasonNumber != null && item.episodeNumber != null) {
                        UpNextBadge(compact = false, textSize = layout.wideBadgeTextSize)
                    }
                }
                Text(
                    text = wideMetaLine,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = layout.wideMetaSize,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasEpisodeTitle) {
                    Text(
                        text = item.episodeTitle.orEmpty(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = layout.wideMetaSize,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (item.progressFraction > 0f) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    NuvioProgressBar(
                        progress = item.progressFraction,
                        modifier = Modifier.fillMaxWidth(),
                        height = layout.progressHeight,
                        trackColor = Color.White.copy(alpha = 0.10f),
                    )
                    Text(
                        text = "${(item.progressFraction * 100).roundToInt()}% watched",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = layout.progressLabelSize,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueWatchingPosterCard(
    item: ContinueWatchingItem,
    layout: ContinueWatchingLayout,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    Column(
        modifier = Modifier.width(layout.posterCardWidth),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.posterCardHeight)
                .clip(RoundedCornerShape(layout.cardRadius))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 1.5.dp,
                    color = Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(layout.cardRadius),
                )
                .posterCardClickable(onClick = onClick, onLongClick = onLongClick),
        ) {
            val imageUrl = item.poster ?: item.imageUrl
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(layout.posterCardHeight * 0.5f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                        ),
                    ),
            )
            if (item.seasonNumber != null && item.episodeNumber != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "S${item.seasonNumber} E${item.episodeNumber}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = layout.posterMetaSize,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = Color.White,
                    )
                }
            }
            if (item.progressFraction <= 0f && item.seasonNumber != null && item.episodeNumber != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    UpNextBadge(compact = true, textSize = layout.posterBadgeTextSize)
                }
            }
            if (item.progressFraction > 0f) {
                NuvioProgressBar(
                    progress = item.progressFraction,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    height = 4.dp,
                    trackColor = Color.White.copy(alpha = 0.3f),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = item.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = layout.posterTitleSize,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 18.sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.progressFraction > 0f) {
                Text(
                    text = "${(item.progressFraction * 100).roundToInt()}%",
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = layout.progressLabelSize,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ArtworkPanel(
    imageUrl: String?,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(width)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun UpNextBadge(
    compact: Boolean,
    textSize: androidx.compose.ui.unit.TextUnit,
) {
    val chipColor = MaterialTheme.colorScheme.primary
    val chipTextColor = contentColorFor(chipColor)

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(if (compact) 4.dp else 12.dp))
            .background(chipColor)
            .padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 3.dp else 4.dp,
            ),
    ) {
        Text(
            text = "Up next",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = textSize,
                fontWeight = FontWeight.Bold,
            ),
            color = chipTextColor,
            maxLines = 1,
        )
    }
}

private data class ContinueWatchingLayout(
    val itemGap: Dp,
    val wideCardWidth: Dp,
    val wideCardHeight: Dp,
    val widePosterStripWidth: Dp,
    val wideContentPadding: Dp,
    val posterCardWidth: Dp,
    val posterCardHeight: Dp,
    val cardRadius: Dp,
    val progressHeight: Dp,
    val wideTitleSize: androidx.compose.ui.unit.TextUnit,
    val wideMetaSize: androidx.compose.ui.unit.TextUnit,
    val posterTitleSize: androidx.compose.ui.unit.TextUnit,
    val posterMetaSize: androidx.compose.ui.unit.TextUnit,
    val progressLabelSize: androidx.compose.ui.unit.TextUnit,
    val wideBadgeTextSize: androidx.compose.ui.unit.TextUnit,
    val posterBadgeTextSize: androidx.compose.ui.unit.TextUnit,
)

private fun rememberContinueWatchingLayout(maxWidthDp: Float): ContinueWatchingLayout =
    when {
        maxWidthDp >= 1440f -> ContinueWatchingLayout(
            itemGap = 20.dp,
            wideCardWidth = 400.dp,
            wideCardHeight = 160.dp,
            widePosterStripWidth = 100.dp,
            wideContentPadding = 16.dp,
            posterCardWidth = 180.dp,
            posterCardHeight = 270.dp,
            cardRadius = 18.dp,
            progressHeight = 6.dp,
            wideTitleSize = 20.sp,
            wideMetaSize = 16.sp,
            posterTitleSize = 16.sp,
            posterMetaSize = 14.sp,
            progressLabelSize = 14.sp,
            wideBadgeTextSize = 14.sp,
            posterBadgeTextSize = 12.sp,
        )
        maxWidthDp >= 1024f -> ContinueWatchingLayout(
            itemGap = 18.dp,
            wideCardWidth = 350.dp,
            wideCardHeight = 140.dp,
            widePosterStripWidth = 90.dp,
            wideContentPadding = 14.dp,
            posterCardWidth = 160.dp,
            posterCardHeight = 240.dp,
            cardRadius = 16.dp,
            progressHeight = 5.dp,
            wideTitleSize = 18.sp,
            wideMetaSize = 15.sp,
            posterTitleSize = 15.sp,
            posterMetaSize = 13.sp,
            progressLabelSize = 13.sp,
            wideBadgeTextSize = 13.sp,
            posterBadgeTextSize = 10.sp,
        )
        maxWidthDp >= 768f -> ContinueWatchingLayout(
            itemGap = 16.dp,
            wideCardWidth = 320.dp,
            wideCardHeight = 130.dp,
            widePosterStripWidth = 85.dp,
            wideContentPadding = 12.dp,
            posterCardWidth = 140.dp,
            posterCardHeight = 210.dp,
            cardRadius = 16.dp,
            progressHeight = 4.dp,
            wideTitleSize = 17.sp,
            wideMetaSize = 14.sp,
            posterTitleSize = 14.sp,
            posterMetaSize = 12.sp,
            progressLabelSize = 12.sp,
            wideBadgeTextSize = 12.sp,
            posterBadgeTextSize = 10.sp,
        )
        else -> ContinueWatchingLayout(
            itemGap = 16.dp,
            wideCardWidth = 280.dp,
            wideCardHeight = 120.dp,
            widePosterStripWidth = 80.dp,
            wideContentPadding = 12.dp,
            posterCardWidth = 120.dp,
            posterCardHeight = 180.dp,
            cardRadius = 16.dp,
            progressHeight = 4.dp,
            wideTitleSize = 16.sp,
            wideMetaSize = 13.sp,
            posterTitleSize = 14.sp,
            posterMetaSize = 12.sp,
            progressLabelSize = 11.sp,
            wideBadgeTextSize = 12.sp,
            posterBadgeTextSize = 10.sp,
        )
    }
