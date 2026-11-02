package com.vidhub.tv

import android.app.Activity
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.vidhub.core.Api
import com.vidhub.core.More
import com.vidhub.core.Site
import com.vidhub.core.Store
import com.vidhub.core.UpdateDialog
import com.vidhub.core.Updater

private fun openMore(title: String, more: More) {
    when (more) {
        More.Videos -> TvState.tab = Tab.Videos
        is More.Tag -> TvNav.push(TvRoute.Tag(more.tag, more.order))
        is More.SeriesList -> TvNav.push(TvRoute.SeriesList(title, more.filter, more.sort))
    }
}

/** 首頁：一排一排的區塊，每排最後一張是「更多」 */
@Composable
fun HomeScreen() {
    val home = TvState.home
    LaunchedEffect(Unit) { home.load() }
    val shelves = home.data
    if (shelves == null) {
        LoadState(home.loading || home.error == null, home.error) { home.load(force = true) }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle("VidHub") { TvButton(onClick = { home.load(force = true) }) { Text("重新整理") } } }
        items(shelves, key = { it.title }) { s ->
            ShelfTitle(s.title)
            LazyRow(contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                if (s.series.isNotEmpty()) items(s.series, key = { it.id }) { SeriesCard(it, Modifier.width(if (it.poster) 170.dp else 240.dp)) }
                else items(s.videos, key = { it.id }) { VideoCard(it, Modifier.width(240.dp)) }
                item(key = "more") { MoreCard { openMore(s.title, s.more) } }
            }
        }
    }
}

@Composable
private fun MoreCard(onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Card(
        onClick = onClick,
        modifier = Modifier.width(160.dp).aspectRatio(16f / 10f),
        shape = CardDefaults.shape(shape),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
        colors = CardDefaults.colors(containerColor = CardBg, focusedContainerColor = CardFocusBg),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("更多 ›", fontSize = 18.sp) }
    }
}

@Composable
fun VideosScreen() {
    FeedGrid(TvState.videoFeed, key = { it.id }, header = {
        fullRow("title") { ScreenTitle("影片") }
        fullRow("order") { ChipRow(Api.ORDERS, TvState.videoOrder) { TvState.videoOrder = it } }
    }) { v, m, mark -> VideoCard(v, m) { mark(); TvNav.push(TvRoute.Detail(v)) } }
}

@Composable
fun SeriesGrid(title: String, state: SeriesState) {
    FeedGrid(state.feed, columns = 6, key = { it.id }, header = {
        fullRow("title") { ScreenTitle(title) }
        fullRow("filter") { ChipRow(Api.SERIES_FILTERS, state.filter) { state.filter = it } }
        fullRow("sort") { ChipRow(Api.SERIES_SORTS, state.sort) { state.sort = it } }
        if (state.tags.isNotEmpty()) fullRow("tags") { ChipRow(listOf("" to "全部類型") + state.tags.map { it to it }, state.tag) { state.tag = it } }
    }) { s, m, mark -> SeriesCard(s, m) { mark(); TvNav.push(TvRoute.Show(s)) } }
}

@Composable
fun SeriesListScreen(route: TvRoute.SeriesList) = SeriesGrid(route.title, route.state)

@Composable
fun TagScreen(route: TvRoute.Tag) {
    FeedGrid(route.feed, key = { it.id }, header = {
        fullRow("title") { ScreenTitle(route.tag + if (route.total > 0) "（${route.total}）" else "") }
        fullRow("order") { ChipRow(Api.ORDERS, route.order) { route.order = it } }
        if (route.siblings.isNotEmpty()) fullRow("sib") { ChipRow(route.siblings.map { it to it }, "") { TvNav.push(TvRoute.Tag(it)) } }
    }) { v, m, mark -> VideoCard(v, m) { mark(); TvNav.push(TvRoute.Detail(v)) } }
}

@Composable
fun CatsScreen() {
    val cats = TvState.cats
    LaunchedEffect(Unit) { cats.load() }
    val c = cats.data
    if (c == null) {
        LoadState(cats.loading || cats.error == null, cats.error) { cats.load(force = true) }
        return
    }
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTitle("分類") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(c.cats, key = { it.id }) { x ->
                    val shape = RoundedCornerShape(10.dp)
                    val request = remember(x.cover) { ImageRequest.Builder(context).data(x.cover).size(400, 225).build() }
                    Card(
                        onClick = { TvNav.push(TvRoute.Tag(x.id)) },
                        modifier = Modifier.width(260.dp).aspectRatio(16f / 9f),
                        shape = CardDefaults.shape(shape),
                        scale = CardDefaults.scale(focusedScale = 1.06f),
                        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape)),
                        colors = CardDefaults.colors(containerColor = CardBg),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, alpha = 0.5f, modifier = Modifier.fillMaxSize())
                            Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                                Text(x.id, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text("${x.count} 部", fontSize = 12.sp, color = Color(0xFFDDDDDD))
                            }
                        }
                    }
                }
            }
        }
        items(c.cats.filter { it.kids.isNotEmpty() }, key = { "k:" + it.id }) { x ->
            ShelfTitle(x.id)
            ChipRow(x.kids.map { it.first to "${it.first}  ${it.second}" }, "") { TvNav.push(TvRoute.Tag(it)) }
        }
        if (c.genres.isNotEmpty()) item {
            ShelfTitle("類型")
            ChipRow(c.genres.map { it.first to "${it.first}  ${it.second}" }, "") { TvNav.push(TvRoute.Tag(it)) }
        }
    }
}

@Composable
fun SearchScreen() {
    var query by rememberSaveable { mutableStateOf("") }

    fun search(q: String) {
        val word = q.trim()
        if (word.isEmpty()) return
        Store.addSearchWord(word)
        TvNav.push(TvRoute.Search(word))
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("搜尋")
        Row(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { androidx.compose.material3.Text("影片、劇集名稱") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(query) }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                modifier = Modifier.width(520.dp),
            )
            Spacer(Modifier.width(16.dp))
            TvButton(onClick = { search(query) }) { Text("搜尋") }
        }
        if (Store.searchWords.isNotEmpty()) {
            Row(Modifier.padding(start = 28.dp, end = 28.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("搜尋紀錄", color = Gray, fontSize = 16.sp)
                Spacer(Modifier.width(16.dp))
                TvButton(onClick = Store::clearSearchWords) { Text("清除") }
            }
            ChipRow(Store.searchWords.map { it to it }, "") { search(it) }
        }
    }
}

@Composable
fun SearchResultScreen(route: TvRoute.Search) {
    val (nv, ns) = route.totals
    LaunchedEffect(route) { route.cats.load() }
    val cats = route.cats.data ?: listOf("" to "全部")
    val header: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit = {
        fullRow("title") { ScreenTitle("搜尋：${route.query}") }
        fullRow("tab") { ChipRow(listOf("video" to "影片 $nv", "series" to "劇集 $ns"), route.tab) { route.tab = it } }
        fullRow("cat") { ChipRow(cats, route.cat) { route.cat = it } }
        fullRow("sort") { ChipRow(Api.SEARCH_SORTS, route.sort) { route.sort = it } }
    }
    if (route.tab == "series") {
        FeedGrid(route.seriesFeed, columns = 6, key = { it.id }, header = header) { s, m, mark -> SeriesCard(s, m) { mark(); TvNav.push(TvRoute.Show(s)) } }
    } else {
        FeedGrid(route.videoFeed, key = { it.id }, header = header) { v, m, mark -> VideoCard(v, m) { mark(); TvNav.push(TvRoute.Detail(v)) } }
    }
}

@Composable
fun LibraryScreen(favorites: Boolean) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(if (favorites) "收藏" else "觀看紀錄") {
            if (!favorites && Store.history.isNotEmpty()) TvButton(onClick = Store::clearHistory) { Text("清除紀錄") }
        }
        if (favorites) SavedGrid(Store.favorites, "還沒有收藏，在影片或劇集頁按「收藏」加入")
        else SavedGrid(Store.history, "還沒有觀看紀錄")
    }
}

@Composable
fun SettingsScreen() {
    val activity = LocalContext.current as Activity
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(Site.base) }
    var note by remember { mutableStateOf("") }
    fun save(input: String) {
        val v = Site.normalize(input)
        if (v == null) {
            note = "網址格式不正確"
            return
        }
        Site.base = v
        text = Site.base
        note = if (v.isEmpty()) "已還原預設網址" else "已儲存"
        editing = false
        TvState.home.load(force = true)
        TvState.cats.load(force = true)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        ScreenTitle("設定", Modifier.padding(start = 0.dp))
        Spacer(Modifier.height(12.dp))
        Text("網址設定", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("網站換網址時，改成新網址。", color = Gray, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save(text) }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                    modifier = Modifier.width(460.dp),
                )
                Spacer(Modifier.width(12.dp))
                TvButton(onClick = { save(text) }) { Text("儲存") }
            } else {
                Text(Site.base + if (Site.isDefaultBase) "（預設）" else "", fontSize = 16.sp, modifier = Modifier.background(CardBg, RoundedCornerShape(6.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
                Spacer(Modifier.width(12.dp))
                TvButton(onClick = { editing = true }) { Text("修改") }
            }
            Spacer(Modifier.width(12.dp))
            TvButton(onClick = { save("") }) { Text("還原預設") }
            Spacer(Modifier.width(12.dp))
            Text(note, color = Gray, fontSize = 14.sp)
        }
        Spacer(Modifier.height(28.dp))
        Text("版本", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Updater.currentVersionName(activity), fontSize = 16.sp)
            Spacer(Modifier.width(16.dp))
            TvButton(onClick = { UpdateDialog.checkManually(activity) }) { Text("檢查更新") }
        }
        Spacer(Modifier.height(28.dp))
        Text("遙控器操作（播放時）", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        listOf(
            "左右鍵：倒退／快轉 10 秒（按住加速）",
            "OK：暫停／播放；按住 OK：2 倍速",
            "上鍵或選單鍵：下一集（劇集）",
            "下鍵：顯示進度列；返回：離開播放",
        ).forEach { Text("・$it", color = Gray, fontSize = 15.sp, modifier = Modifier.padding(vertical = 2.dp)) }
    }
}
