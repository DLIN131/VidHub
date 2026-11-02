package com.vidhub.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vidhub.core.Feed
import com.vidhub.core.Saved
import com.vidhub.core.Series
import com.vidhub.core.Store
import com.vidhub.core.Video
import com.vidhub.core.fmtCount
import com.vidhub.core.fmtTime
import kotlinx.coroutines.delay

@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
        colors = ButtonDefaults.colors(
            containerColor = if (selected) Accent else Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        content = content,
    )
}

private val cardShape = RoundedCornerShape(10.dp)
private val focusBorder = Border(BorderStroke(3.dp, Color.White), shape = cardShape)

@Composable
private fun Badge(text: String, modifier: Modifier, bg: Color = Color(0xB0000000)) {
    Text(
        text,
        fontSize = 11.sp,
        color = Color.White,
        fontWeight = if (bg == Accent) FontWeight.Bold else FontWeight.Normal,
        modifier = modifier.padding(6.dp).background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 封面卡片；圖片固定解碼成小尺寸，一頁幾十張也不會吃光記憶體 */
@Composable
private fun CoverCard(
    cover: String,
    title: String,
    meta: String,
    ratio: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badges: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val request = remember(cover) { ImageRequest.Builder(context).data(cover).size(400, (400 / ratio).toInt()).build() }
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = CardDefaults.shape(cardShape),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(focusedBorder = focusBorder),
        colors = CardDefaults.colors(containerColor = CardBg, focusedContainerColor = CardFocusBg),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color(0xFF26262D))) {
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            badges()
        }
        Text(title, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp))
        Text(meta, fontSize = 11.sp, color = Gray, maxLines = 1, modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp))
    }
}

@Composable
fun VideoCard(v: Video, modifier: Modifier = Modifier, onClick: () -> Unit = { TvNav.push(TvRoute.Detail(v)) }) {
    val meta = listOfNotNull(v.views.takeIf { it > 0 }?.let { "${fmtCount(it)} 次觀看" }, v.tags.firstOrNull()).joinToString("・")
    CoverCard(v.cover, v.title, meta, 16f / 10f, onClick, modifier) {
        if (v.duration > 0) Badge(fmtTime(v.duration), Modifier.align(Alignment.BottomEnd))
        if (v.res >= 720) Badge("${v.res}P", Modifier.align(Alignment.TopStart), Accent)
        val pos = Store.position(v.id)
        if (pos > 5) Badge("看到 ${fmtTime(pos)}", Modifier.align(Alignment.BottomStart))
    }
}

@Composable
fun SeriesCard(s: Series, modifier: Modifier = Modifier, onClick: () -> Unit = { TvNav.push(TvRoute.Show(s)) }) {
    val meta = listOfNotNull(s.status.ifEmpty { null }, s.views.takeIf { it > 0 }?.let { "${fmtCount(it)} 次觀看" }).joinToString("・")
    CoverCard(s.cover, s.title, meta, if (s.poster) 3f / 4f else 16f / 10f, onClick, modifier) {
        if (s.epLabel.isNotEmpty()) Badge(s.epLabel, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
fun SavedCard(x: Saved, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    if (x.series) SeriesCard(x.toSeries(), modifier, onClick ?: { TvNav.push(TvRoute.Show(x.toSeries())) })
    else VideoCard(x.toVideo(), modifier, onClick ?: { TvNav.push(TvRoute.Detail(x.toVideo())) })
}

@Composable
fun Loading(modifier: Modifier = Modifier, size: Int = 48) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Accent, modifier = Modifier.size(size.dp))
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Gray, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        TvButton(onClick = onRetry) { Text("重試") }
    }
}

/** 一次載完的資料：載入中／錯誤 */
@Composable
fun LoadState(loading: Boolean, error: String?, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (error != null && !loading) ErrorBox(error, onRetry) else Loading()
    }
}

@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

/** 一排單選按鈕（排序、篩選） */
@Composable
fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(options, key = { it.second }) { (key, name) ->
            TvButton(onClick = { if (key != selected) onSelect(key) }, selected = key == selected) { Text(name) }
        }
    }
}

/** 首頁、影片頁下方的一排卡片 */
@Composable
fun ShelfTitle(title: String) {
    Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 28.dp, top = 18.dp, bottom = 2.dp))
}

@Composable
fun VideoShelf(items: List<Video>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        items(items, key = { it.id }) { VideoCard(it, Modifier.width(240.dp)) }
    }
}

@Composable
fun SeriesShelf(items: List<Series>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        items(items, key = { it.id }) { SeriesCard(it, Modifier.width(if (it.poster) 170.dp else 240.dp)) }
    }
}

/**
 * 格狀列表，接近底部自動載下一頁；header 是列表上方（篩選列），跟著一起捲。
 * 從詳細頁返回時，焦點回到剛剛點的那張卡片。
 */
@Composable
fun <T> FeedGrid(
    feed: Feed<T>,
    columns: Int = 5,
    key: (T) -> String,
    modifier: Modifier = Modifier,
    header: (LazyGridScope.() -> Unit)? = null,
    card: @Composable (item: T, modifier: Modifier, onOpen: () -> Unit) -> Unit,
) {
    LaunchedEffect(feed) { if (feed.items.isEmpty() && !feed.end) feed.loadMore() }
    val restore = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (feed.restoreFocus) {
            feed.restoreFocus = false
            delay(80)
            runCatching { restore.requestFocus() }
        }
    }
    val items = feed.items
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = feed.grid,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        header?.invoke(this)
        itemsIndexed(items, key = { _, it -> key(it) }, contentType = { _, _ -> "card" }) { index, item ->
            if (index >= items.size - 10) LaunchedEffect(items.size) { feed.loadMore() }
            card(item, if (index == feed.focusIndex) Modifier.focusRequester(restore) else Modifier) {
                feed.focusIndex = index
                feed.restoreFocus = true
            }
        }
        val err = feed.error
        if (feed.loading || err != null || items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        err != null -> ErrorBox(err, onRetry = feed::loadMore)
                        feed.loading || !feed.end -> Loading(size = 36)
                        else -> Text("這裡什麼都沒有", color = Gray, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

/** 整列的元素（篩選列）放在格狀列表裡 */
fun LazyGridScope.fullRow(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = "row") { content() }
}

/** 本機清單（收藏、觀看紀錄），一次全部列出 */
@Composable
fun SavedGrid(items: List<Saved>, empty: String, modifier: Modifier = Modifier) {
    if (items.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(empty, color = Gray, fontSize = 18.sp) }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(5),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        itemsIndexed(items, key = { _, it -> it.id }, contentType = { _, _ -> "card" }) { _, item -> SavedCard(item) }
    }
}
