package com.vidhub.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidhub.core.Series
import com.vidhub.core.Store
import com.vidhub.core.Video
import com.vidhub.core.fmtCount
import com.vidhub.core.fmtTime
import com.vidhub.core.saved

@Composable
private fun FavButton(id: String, onToggle: () -> Unit) {
    val fav = Store.isFavorite(id)
    IconButton(onClick = onToggle) {
        Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "收藏", tint = if (fav) Accent else TextColor)
    }
}

@Composable
private fun PlayButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Accent), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
        Text(text, fontSize = 16.sp, modifier = Modifier.padding(vertical = 4.dp))
    }
}

/** 劇集的選集：橫向一排，目前（上次看）的那一集有框 */
@Composable
private fun EpisodeRow(episodes: List<Video>, current: String, onPick: (Video) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(episodes, key = { it.id }) { e ->
            val on = e.id == current
            Column(Modifier.width(150.dp).clip(RoundedCornerShape(8.dp)).clickable { onPick(e) }) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)).background(Surface)
                        .then(if (on) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier),
                ) {
                    AsyncImage(model = e.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    if (e.duration > 0) Text(
                        fmtTime(e.duration),
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).background(Color(0xB0000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                Text(e.episode?.let { "第 $it 集" } ?: e.title, color = if (on) Accent else TextColor, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Tags(tags: List<String>, onClick: (String) -> Unit) {
    if (tags.isEmpty()) return
    FlowRow(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { t -> AssistChip(onClick = { onClick(t) }, label = { Text(t, fontSize = 13.sp) }) }
    }
}

@Composable
private fun Cover(url: String, poster: Boolean) {
    Box(
        Modifier.fillMaxWidth().then(if (poster) Modifier.padding(horizontal = 80.dp).aspectRatio(3f / 4f) else Modifier.aspectRatio(16f / 10f))
            .clip(RoundedCornerShape(if (poster) 10.dp else 0.dp)).background(Surface),
    ) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun VideoScreen(route: Route.VideoPage) {
    val loader = route.detail
    LaunchedEffect(route) { loader.load() }
    val d = loader.data
    val v = d?.video ?: route.preview
    Column(Modifier.fillMaxSize()) {
        AppBar(v.title, back = true) { FavButton(v.id) { Store.toggleFavorite(v.saved()) } }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Cover(v.cover, false)
            Text(v.title, fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp, modifier = Modifier.padding(14.dp))
            val meta = listOf(v.code, if (v.duration > 0) fmtTime(v.duration) else "", "${fmtCount(v.views)} 次觀看", v.date).filter { it.isNotEmpty() }.joinToString("・")
            Text(meta, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
            Spacer(Modifier.height(14.dp))
            if (d == null) {
                StatusBox(loader.loading || loader.error == null, loader.error, { loader.load(force = true) })
                return@Column
            }
            val resume = Store.position(v.id)
            val queue = d.episodes
            PlayButton(if (resume > 5) "▶  繼續播放（${fmtTime(resume)}）" else "▶  播放") { Nav.push(Route.Player(v, queue, d.series)) }
            Spacer(Modifier.height(12.dp))
            Tags(v.tags) { Nav.push(Route.Tag(it)) }
            d.series?.let { s ->
                ShelfHead("${s.title}（${s.epLabel}）") { Nav.push(Route.ShowPage(s)) }
                EpisodeRow(queue, v.id) { e -> Nav.push(Route.Player(e, queue, s)) }
            }
            if (d.related.isNotEmpty()) {
                ShelfHead("相關影片")
                VideoRow(d.related)
            }
            if (d.moreSeries.isNotEmpty()) {
                ShelfHead("更多劇集")
                SeriesRow(d.moreSeries)
            }
        }
    }
}

@Composable
fun ShowScreen(route: Route.ShowPage) {
    val loader = route.detail
    LaunchedEffect(route) { loader.load() }
    val d = loader.data
    val s: Series = d?.series ?: route.preview
    Column(Modifier.fillMaxSize()) {
        AppBar(s.title, back = true) { FavButton(s.id) { Store.toggleFavorite(s.saved()) } }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Cover(s.cover, s.poster)
            Text(s.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp))
            val meta = listOf(s.epLabel, s.status, "${fmtCount(s.views)} 次觀看").filter { it.isNotEmpty() }.joinToString("・")
            Text(meta, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
            if (d == null) {
                StatusBox(loader.loading || loader.error == null, loader.error, { loader.load(force = true) })
                return@Column
            }
            val queue = d.episodes
            val lastId = Store.lastEpisode(s.id)
            val last = queue.firstOrNull { it.id == lastId }
            val start = last ?: queue.firstOrNull()
            if (start != null) {
                PlayButton(if (last != null) "▶  繼續看第 ${last.episode ?: ""} 集" else "▶  從第 1 集開始") { Nav.push(Route.Player(start, queue, s)) }
                Spacer(Modifier.height(12.dp))
            }
            Tags(s.tags) { Nav.push(Route.SeriesList(it, tag = it)) }
            if (s.description.isNotBlank()) {
                Text(s.description, color = Color(0xFFCFD3DA), fontSize = 14.sp, lineHeight = 22.sp, maxLines = 8, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(14.dp))
            }
            ShelfHead("選集")
            EpisodeRow(queue, lastId) { e -> Nav.push(Route.Player(e, queue, s)) }
            if (d.more.isNotEmpty()) {
                ShelfHead("更多劇集")
                SeriesRow(d.more)
            }
        }
    }
}
