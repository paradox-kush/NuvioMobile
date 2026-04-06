package com.nuvio.app.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.home.PosterShape

@Composable
fun HomeCollectionRowSection(
    collection: Collection,
    modifier: Modifier = Modifier,
    onFolderClick: ((collectionId: String, folderId: String) -> Unit)? = null,
) {
    if (collection.folders.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val sectionPadding = homeSectionHorizontalPaddingForWidth(maxWidth.value)
        NuvioShelfSection(
            title = collection.title,
            entries = collection.folders,
            modifier = Modifier.fillMaxWidth(),
            headerHorizontalPadding = sectionPadding,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            key = { folder -> "collection_${collection.id}_folder_${folder.id}" },
        ) { folder ->
            CollectionFolderCard(
                folder = folder,
                onClick = onFolderClick?.let { { it(collection.id, folder.id) } },
            )
        }
    }
}

@Composable
private fun CollectionFolderCard(
    folder: CollectionFolder,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val shape = folder.posterShape
    val cardWidth: Dp
    val aspectRatio: Float

    when (shape) {
        PosterShape.Poster -> {
            cardWidth = 110.dp
            aspectRatio = 0.675f
        }
        PosterShape.Landscape -> {
            cardWidth = 180.dp
            aspectRatio = 1.77f
        }
        PosterShape.Square -> {
            cardWidth = 120.dp
            aspectRatio = 1f
        }
    }

    Column(
        modifier = modifier.width(cardWidth),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .then(
                    if (onClick != null) Modifier.clickable(onClick = onClick)
                    else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !folder.coverImageUrl.isNullOrBlank() -> {
                    AsyncImage(
                        model = folder.coverImageUrl,
                        contentDescription = folder.title,
                        modifier = Modifier
                            .matchParentSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                !folder.coverEmoji.isNullOrBlank() -> {
                    Text(
                        text = folder.coverEmoji,
                        fontSize = 36.sp,
                    )
                }
                else -> {
                    Text(
                        text = folder.title.take(2).uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (!folder.hideTitle) {
            Text(
                text = folder.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
