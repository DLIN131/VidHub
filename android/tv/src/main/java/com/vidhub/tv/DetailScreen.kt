package com.vidhub.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vidhub.core.Series
import com.vidhub.core.Store
import com.vidhub.core.Video
import com.vidhub.core.fmtCount
import com.vidhub.core.fmtTime
import com.vidhub.core.saved
import kotlinx.coroutines.delay

/** 左邊封面、右邊標題和按鈕，下面是選集和相關內容 */
@Composable
private fun DetailLayout(
    cover: String,
    poster: Boolean,
    head: @Composable () -> Unit,
    rows: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val context = LocalContext.current
    val request = remember(cover) { ImageRequest.Builder(context).data(cover).size(800, if (poster) 1066 else 500).build() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 36.dp, top = 32.dp)) {
                Box(
                    Modifier.width(if (poster) 260.dp else 480.dp).aspectRatio(if (poster) 3f / 4f else 16f / 10f)
                        .clip(RoundedCornerShape(12.dp)).background(CardBg),
                ) {
                    AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { head() }
            }
        }
        rows()
    }
}

/** 選集：一排，目前（上次看）的那一集標成粉紅 */
@Composable
private fun EpisodeShelf(episodes: List<Video>, current: String, onPick: (Video) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        items(episodes, key = { it.id }) { e ->
            val label = (e.episode?.let { "第 $it 集" } ?: e.title) + if (e.id == current) "（上次看）" else ""
            VideoCard(e.copy(title = label, views = 0, tags = emptyList()), Modifier.width(220.dp)) { onPick(e) }
        }
    }
}

@Composable
private fun TagRow(tags: List<String>, onClick: (String) -> Unit) {
    if (tags.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(tags, key = { it }) { t -> TvButton(onClick = { onClick(t) }) { Text(t, fontSize = 14.sp) } }
    }
}

@Composable
fun DetailScreen(route: TvRoute.Detail) {
    val loader = route.detail
    LaunchedEffect(route) { loader.load() }
    val context = LocalContext.current
    val d = loader.data
    val v = d?.video ?: route.preview
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(d != null) {
        if (d != null) {
            delay(60)
            runCatching { playFocus.requestFocus() }
        }
    }
    val fav = Store.isFavorite(v.id)
    DetailLayout(v.cover, false, head = {
        Text(v.title, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Text(listOf(v.code, if (v.duration > 0) fmtTime(v.duration) else "", "${fmtCount(v.views)} 次觀看", v.date).filter { it.isNotEmpty() }.joinToString("・"), color = Gray, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                d != null -> {
                    val resume = Store.position(v.id)
                    TvButton(onClick = { PlayerActivity.start(context, v, d.episodes, d.series) }, modifier = Modifier.focusRequester(playFocus)) {
                        Text(if (resume > 5) "▶  繼續播放（${fmtTime(resume)}）" else "▶  播放")
                    }
                }
                loader.error != null -> ErrorBox(loader.error!!, onRetry = { loader.load(force = true) })
                else -> Loading(size = 32)
            }
            TvButton(onClick = { Store.toggleFavorite(v.saved()) }, selected = fav) { Text(if (fav) "♥  已收藏" else "♡  收藏") }
        }
        TagRow(v.tags) { TvNav.push(TvRoute.Tag(it)) }
    }) {
        if (d == null) return@DetailLayout
        d.series?.let { s ->
            item { ShelfTitle("${s.title}（${s.epLabel}）") }
            item { EpisodeShelf(d.episodes, v.id) { e -> PlayerActivity.start(context, e, d.episodes, s) } }
            item {
                Row(Modifier.padding(start = 28.dp)) { TvButton(onClick = { TvNav.push(TvRoute.Show(s)) }) { Text("劇集頁 ›") } }
            }
        }
        if (d.related.isNotEmpty()) {
            item { ShelfTitle("相關影片") }
            item { VideoShelf(d.related) }
        }
        if (d.moreSeries.isNotEmpty()) {
            item { ShelfTitle("更多劇集") }
            item { SeriesShelf(d.moreSeries) }
        }
    }
}

@Composable
fun ShowScreen(route: TvRoute.Show) {
    val loader = route.detail
    LaunchedEffect(route) { loader.load() }
    val context = LocalContext.current
    val d = loader.data
    val s: Series = d?.series ?: route.preview
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(d != null) {
        if (d != null) {
            delay(60)
            runCatching { playFocus.requestFocus() }
        }
    }
    val fav = Store.isFavorite(s.id)
    val lastId = Store.lastEpisode(s.id)
    DetailLayout(s.cover, s.poster, head = {
        Text(s.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOf(s.epLabel, s.status, "${fmtCount(s.views)} 次觀看").filter { it.isNotEmpty() }.joinToString("・"), color = Gray, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                d != null -> {
                    val last = d.episodes.firstOrNull { it.id == lastId }
                    val start = last ?: d.episodes.firstOrNull()
                    if (start != null) TvButton(onClick = { PlayerActivity.start(context, start, d.episodes, s) }, modifier = Modifier.focusRequester(playFocus)) {
                        Text(if (last != null) "▶  繼續看第 ${last.episode ?: ""} 集" else "▶  從第 1 集開始")
                    }
                }
                loader.error != null -> ErrorBox(loader.error!!, onRetry = { loader.load(force = true) })
                else -> Loading(size = 32)
            }
            TvButton(onClick = { Store.toggleFavorite(s.saved()) }, selected = fav) { Text(if (fav) "♥  已收藏" else "♡  收藏") }
        }
        TagRow(s.tags) { TvNav.push(TvRoute.SeriesList(it, tag = it)) }
        if (s.description.isNotBlank()) Text(s.description, color = Color(0xFFCFD3DA), fontSize = 15.sp, lineHeight = 22.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
    }) {
        if (d == null) return@DetailLayout
        item { ShelfTitle("選集") }
        item { EpisodeShelf(d.episodes, lastId) { e -> PlayerActivity.start(context, e, d.episodes, s) } }
        if (d.more.isNotEmpty()) {
            item { ShelfTitle("更多劇集") }
            item { SeriesShelf(d.more) }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
