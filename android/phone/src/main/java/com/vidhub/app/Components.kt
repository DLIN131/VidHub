package com.vidhub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidhub.core.Feed
import com.vidhub.core.Saved
import com.vidhub.core.Series
import com.vidhub.core.Store
import com.vidhub.core.Video
import com.vidhub.core.fmtCount
import com.vidhub.core.fmtTime

val Accent = Color(0xFFE0457B)
val Background = Color(0xFF16181D)
val Surface = Color(0xFF22252C)
val Muted = Color(0xFF9AA0AA)
val TextColor = Color(0xFFEDEDED)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            secondary = Accent,
            background = Background,
            surface = Background,
            surfaceContainer = Surface,
            surfaceVariant = Surface,
            onBackground = TextColor,
            onSurface = TextColor,
            onSurfaceVariant = Muted,
        ),
    ) {
        // 沒有包在 Scaffold／Surface 裡的畫面，文字預設會是黑色，這裡統一給白色
        CompositionLocalProvider(LocalContentColor provides TextColor, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(title: String, back: Boolean = false, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 19.sp) },
        navigationIcon = {
            if (back) IconButton(onClick = Nav::pop) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
        windowInsets = WindowInsets(0),
    )
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Int = 32) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent, modifier = Modifier.size(size.dp))
    }
}

@Composable
fun ErrorText(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRetry) { Text("重試") }
    }
}

/** 載入中／錯誤／沒有資料 */
@Composable
fun StatusBox(loading: Boolean, error: String?, onRetry: () -> Unit, empty: String? = null) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> Spinner()
            error != null -> ErrorText(error, onRetry)
            empty != null -> Text(empty, color = Muted)
        }
    }
}

/** 一排可以單選的標籤 */
@Composable
fun <T> FilterRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (key, name) ->
            FilterChip(
                selected = key == selected,
                onClick = { if (key != selected) onSelect(key) },
                label = { Text(name) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Accent, selectedLabelColor = Color.White),
            )
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier, bg: Color = Color(0xB0000000)) {
    Text(
        text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = if (bg == Accent) FontWeight.Bold else FontWeight.Normal,
        modifier = modifier.padding(4.dp).background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
fun VideoCard(v: Video, modifier: Modifier = Modifier, onClick: () -> Unit = { Nav.push(Route.VideoPage(v)) }) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)).background(Surface)) {
            AsyncImage(model = v.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (v.duration > 0) Badge(fmtTime(v.duration), Modifier.align(Alignment.BottomEnd))
            if (v.res >= 720) Badge("${v.res}P", Modifier.align(Alignment.TopStart), Accent)
            val pos = Store.position(v.id)
            if (pos > 5) Badge("看到 ${fmtTime(pos)}", Modifier.align(Alignment.BottomStart))
        }
        Text(
            v.title,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp),
        )
        val meta = listOfNotNull(v.views.takeIf { it > 0 }?.let { "${fmtCount(it)} 次觀看" }, v.tags.firstOrNull()).joinToString("・")
        if (meta.isNotEmpty()) Text(meta, color = Muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
fun SeriesCard(s: Series, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable { Nav.push(Route.ShowPage(s)) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (s.poster) 3f / 4f else 16f / 10f).clip(RoundedCornerShape(8.dp)).background(Surface)) {
            AsyncImage(model = s.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (s.epLabel.isNotEmpty()) Badge(s.epLabel, Modifier.align(Alignment.BottomEnd))
        }
        Text(s.title, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp))
        val meta = listOfNotNull(s.status.ifEmpty { null }, s.views.takeIf { it > 0 }?.let { "${fmtCount(it)} 次觀看" }).joinToString("・")
        if (meta.isNotEmpty()) Text(meta, color = Muted, fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
fun SavedCard(x: Saved) {
    if (x.series) SeriesCard(x.toSeries()) else VideoCard(x.toVideo())
}

/** 區塊標題列，右邊可以有「更多」 */
@Composable
fun ShelfHead(title: String, onMore: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (onMore != null) TextButton(onClick = onMore) { Text("更多 ›", color = Muted) }
    }
}

/** 橫向捲動的一排卡片 */
@Composable
fun VideoRow(items: List<Video>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.id }) { VideoCard(it, Modifier.width(200.dp)) }
    }
}

@Composable
fun SeriesRow(items: List<Series>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.id }) { SeriesCard(it, Modifier.width(if (it.poster) 130.dp else 200.dp)) }
    }
}

/**
 * 可以一直往下捲的格狀列表；header 是列表上方（篩選列之類），跟著一起捲。
 * 捲到接近底部自動載下一頁。
 */
@Composable
fun <T> FeedGrid(
    feed: Feed<T>,
    minCell: Int = 170,
    key: (T) -> String,
    header: (LazyGridScope.() -> Unit)? = null,
    card: @Composable (T) -> Unit,
) {
    LaunchedEffect(feed) { if (feed.items.isEmpty() && !feed.end) feed.loadMore() }
    val state = feed.grid
    val nearEnd by remember(state) {
        derivedStateOf {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= state.layoutInfo.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, feed.end, feed.loading, feed.error, feed.items.size) {
        if (nearEnd && !feed.end && !feed.loading && feed.error == null) feed.loadMore()
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minCell.dp),
        state = state,
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        header?.invoke(this)
        items(feed.items, key = { key(it) }) { card(it) }
        item(span = { GridItemSpan(maxLineSpan) }) {
            StatusBox(feed.loading, feed.error, feed::loadMore, if (feed.items.isEmpty() && feed.end) "沒有內容" else null)
        }
    }
}

/** 本機清單（收藏、紀錄），一次全部列出 */
@Composable
fun SavedGrid(items: List<Saved>, empty: String) {
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(empty, color = Muted) }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { SavedCard(it) }
    }
}

/** 整列的元素（篩選列、標題）放在格狀列表裡 */
fun LazyGridScope.fullRow(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}
