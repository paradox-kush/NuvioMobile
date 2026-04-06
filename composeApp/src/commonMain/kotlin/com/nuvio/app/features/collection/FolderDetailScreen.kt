package com.nuvio.app.features.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.nuvioPlatformExtraBottomPadding
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.canOpenCatalog
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@Composable
fun FolderDetailScreen(
    onBack: () -> Unit,
    onCatalogClick: (HomeCatalogSection) -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
) {
    val uiState by FolderDetailRepository.uiState.collectAsState()
    val folder = uiState.folder
    val coverImageUrl = folder?.coverImageUrl?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (coverImageUrl != null) {
            FolderCoverImage(
                imageUrl = coverImageUrl,
                title = folder.title,
            )
        }

        NuvioScreenHeader(
            title = folder?.title ?: uiState.collectionTitle,
            modifier = Modifier.padding(horizontal = 16.dp),
            includeStatusBarPadding = coverImageUrl == null,
            onBack = onBack,
        )

        if (folder == null && !uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Folder not found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        when (uiState.viewMode) {
            FolderViewMode.TABBED_GRID -> TabbedGridContent(
                uiState = uiState,
                onTabSelected = { FolderDetailRepository.selectTab(it) },
                onPosterClick = onPosterClick,
            )
            FolderViewMode.ROWS -> RowsContent(
                uiState = uiState,
                onCatalogClick = onCatalogClick,
                onPosterClick = onPosterClick,
            )
            FolderViewMode.FOLLOW_LAYOUT -> RowsContent(
                uiState = uiState,
                onCatalogClick = onCatalogClick,
                onPosterClick = onPosterClick,
            )
        }
    }
}

@Composable
private fun FolderCoverImage(
    imageUrl: String,
    title: String,
) {
    AsyncImage(
        model = imageUrl,
        contentDescription = title,
        modifier = Modifier
            .fillMaxWidth()
            .height(176.dp),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun TabbedGridContent(
    uiState: FolderDetailUiState,
    onTabSelected: (Int) -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState, uiState.selectedTabIndex, uiState.selectedTabCanLoadMore, uiState.selectedTabIsLoadingMore) {
        snapshotFlow { gridState.layoutInfo }
            .map { layoutInfo ->
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= layoutInfo.totalItemsCount - 6
            }
            .distinctUntilChanged()
            .filter { it && uiState.selectedTabCanLoadMore && !uiState.selectedTabIsLoadingMore }
            .collect {
                FolderDetailRepository.loadMoreSelectedTab()
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.tabs.size > 1) {
            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                ScrollableTabRow(
                    selectedTabIndex = uiState.selectedTabIndex,
                    modifier = Modifier.fillMaxWidth(),
                    edgePadding = 16.dp,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    divider = {},
                ) {
                    uiState.tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = index == uiState.selectedTabIndex,
                            onClick = { onTabSelected(index) },
                            text = {
                                Text(
                                    text = tab.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Grid content for selected tab
        val selectedTab = uiState.tabs.getOrNull(uiState.selectedTabIndex)
        if (selectedTab == null) return

        when {
            selectedTab.isLoading && selectedTab.items.isEmpty() -> LoadingIndicator()
            selectedTab.error != null && selectedTab.items.isEmpty() -> ErrorMessage(selectedTab.error)
            selectedTab.items.isEmpty() -> EmptyMessage()
            else -> {
                val nuvioShape = NuvioPosterShape.Poster
                val columns = 3

                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 18.dp + nuvioPlatformExtraBottomPadding,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(
                        items = selectedTab.items,
                        key = { item -> item.stableKey() },
                    ) { item ->
                        NuvioPosterCard(
                            title = item.name,
                            imageUrl = item.poster,
                            shape = nuvioShape,
                            detailLine = item.releaseInfo,
                            onClick = { onPosterClick(item) },
                        )
                    }

                    if (uiState.selectedTabIsLoadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            PaginationLoadingFooter()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowsContent(
    uiState: FolderDetailUiState,
    onCatalogClick: (HomeCatalogSection) -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
) {
    val sections = FolderDetailRepository.getCatalogSectionsForRows()

    if (uiState.isLoading && sections.isEmpty()) {
        LoadingIndicator()
        return
    }

    if (sections.isEmpty() && !uiState.isLoading) {
        EmptyMessage()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            bottom = 18.dp + nuvioPlatformExtraBottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(
            items = sections,
            key = { it.key },
        ) { section ->
            HomeCatalogRowSection(
                section = section,
                entries = section.items.take(18),
                onViewAllClick = if (section.canOpenCatalog(18)) {
                    { onCatalogClick(section) }
                } else {
                    null
                },
                onPosterClick = { onPosterClick(it) },
            )
        }
    }
}

@Composable
private fun PaginationLoadingFooter() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
        )
    }
}

@Composable
private fun LoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
        )
    }
}

@Composable
private fun ErrorMessage(error: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyMessage() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No items found",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun PosterShape.toNuvioPosterShape(): NuvioPosterShape =
    when (this) {
        PosterShape.Poster -> NuvioPosterShape.Poster
        PosterShape.Square -> NuvioPosterShape.Square
        PosterShape.Landscape -> NuvioPosterShape.Landscape
    }
