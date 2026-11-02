package com.vidhub.app

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidhub.core.Api
import com.vidhub.core.More
import com.vidhub.core.Site
import com.vidhub.core.Store
import com.vidhub.core.UpdateDialog
import com.vidhub.core.Updater

private data class TabItem(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("首頁", Icons.Filled.Home),
    TabItem("影片", Icons.Filled.PlayArrow),
    TabItem("劇集", Icons.AutoMirrored.Filled.List),
    TabItem("分類", Icons.Filled.Star),
    TabItem("我的", Icons.Filled.Favorite),
)

@Composable
fun MainScreen() {
    Scaffold(
        containerColor = Background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar(containerColor = Surface, windowInsets = WindowInsets(0)) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = MainState.tab == i,
                        onClick = { MainState.tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Accent, indicatorColor = Accent),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (MainState.tab) {
                0 -> HomeTab()
                1 -> VideosTab()
                2 -> SeriesTab()
                3 -> CatsTab()
                else -> LibraryTab()
            }
        }
    }
}

@Composable
private fun SearchAction() {
    IconButton(onClick = { Nav.push(Route.Search("")) }) { Icon(Icons.Filled.Search, contentDescription = "搜尋") }
}

private fun openMore(title: String, more: More) {
    when (more) {
        More.Videos -> MainState.tab = 1
        is More.Tag -> Nav.push(Route.Tag(more.tag, more.order))
        is More.SeriesList -> Nav.push(Route.SeriesList(title, more.filter, more.sort))
    }
}

@Composable
private fun HomeTab() {
    val home = MainState.home
    LaunchedEffect(Unit) { home.load() }
    Column(Modifier.fillMaxSize()) {
        AppBar("VidHub") {
            SearchAction()
            IconButton(onClick = { home.load(force = true) }) { Icon(Icons.Filled.Refresh, contentDescription = "重新整理") }
        }
        val shelves = home.data
        if (shelves == null) {
            StatusBox(home.loading || home.error == null, home.error, { home.load(force = true) })
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(shelves, key = { it.title }) { s ->
                ShelfHead(s.title) { openMore(s.title, s.more) }
                if (s.series.isNotEmpty()) SeriesRow(s.series) else VideoRow(s.videos)
            }
        }
    }
}

@Composable
private fun VideosTab() {
    Column(Modifier.fillMaxSize()) {
        AppBar("影片") { SearchAction() }
        FilterRow(Api.ORDERS, MainState.videoOrder) { MainState.videoOrder = it }
        FeedGrid(MainState.videoFeed, key = { it.id }) { VideoCard(it) }
    }
}

@Composable
fun SeriesFilters(state: SeriesState) {
    FilterRow(Api.SERIES_FILTERS, state.filter) { state.filter = it }
    FilterRow(Api.SERIES_SORTS, state.sort) { state.sort = it }
    if (state.tags.isNotEmpty()) FilterRow(listOf("" to "全部類型") + state.tags.map { it to it }, state.tag) { state.tag = it }
}

@Composable
private fun SeriesTab() {
    Column(Modifier.fillMaxSize()) {
        AppBar("劇集") { SearchAction() }
        SeriesFilters(MainState.series)
        FeedGrid(MainState.series.feed, minCell = 120, key = { it.id }) { SeriesCard(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatsTab() {
    val cats = MainState.cats
    LaunchedEffect(Unit) { cats.load() }
    Column(Modifier.fillMaxSize()) {
        AppBar("分類") { SearchAction() }
        val c = cats.data
        if (c == null) {
            StatusBox(cats.loading || cats.error == null, cats.error, { cats.load(force = true) })
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            c.cats.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { x ->
                        Box(
                            Modifier.weight(1f).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Surface).clickable { Nav.push(Route.Tag(x.id)) },
                        ) {
                            AsyncImage(model = x.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), alpha = 0.55f)
                            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                                Text(x.id, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                Text("${x.count} 部", fontSize = 11.sp, color = Color(0xFFDDDDDD))
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            c.cats.filter { it.kids.isNotEmpty() }.forEach { x ->
                ShelfHead(x.id)
                TagFlow(x.kids)
            }
            if (c.genres.isNotEmpty()) {
                ShelfHead("類型")
                TagFlow(c.genres)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagFlow(tags: List<Pair<String, Int>>) {
    FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { (t, n) -> AssistChip(onClick = { Nav.push(Route.Tag(t)) }, label = { Text("$t  $n", fontSize = 13.sp) }) }
    }
}

@Composable
private fun LibraryTab() {
    val activity = LocalContext.current as Activity
    var confirmClear by remember { mutableStateOf(false) }
    var urlDialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        AppBar("我的")
        TabRow(selectedTabIndex = MainState.libraryTab, containerColor = Background, contentColor = Accent) {
            listOf("收藏", "觀看紀錄", "設定").forEachIndexed { i, name ->
                Tab(
                    selected = MainState.libraryTab == i,
                    onClick = { MainState.libraryTab = i },
                    text = { Text(name, color = if (MainState.libraryTab == i) Accent else Muted) },
                )
            }
        }
        when (MainState.libraryTab) {
            0 -> SavedGrid(Store.favorites, "還沒有收藏，在影片或劇集頁按愛心加入")
            1 -> Column {
                if (Store.history.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }, modifier = Modifier.align(Alignment.End)) { Text("清除紀錄", color = Muted) }
                }
                SavedGrid(Store.history, "還沒有觀看紀錄")
            }
            else -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingRow("網址設定", Site.base + if (Site.isDefaultBase) "（預設）" else "") { urlDialog = true }
                SettingRow("檢查更新", "目前版本 ${Updater.currentVersionName(activity)}") { UpdateDialog.checkManually(activity) }
                SettingRow("播放器操作", "點一下顯示控制列；左右兩側連點兩下倒退／快轉 10 秒，三下 1 分鐘；按住 2 倍速") {}
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除觀看紀錄？") },
            text = { Text("觀看紀錄和播放進度都會刪除，收藏不受影響。") },
            confirmButton = {
                TextButton(onClick = {
                    Store.clearHistory()
                    confirmClear = false
                }) { Text("清除", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
    if (urlDialog) UrlDialog { urlDialog = false }
}

@Composable
private fun SettingRow(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface).clickable(onClick = onClick).padding(16.dp)) {
        Text(title, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(sub, color = Muted, fontSize = 13.sp)
    }
}

/** 網站換網址時在這裡改；改完重新載入首頁和分類 */
@Composable
fun UrlDialog(onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(Site.base) }
    var error by remember { mutableStateOf<String?>(null) }
    fun save(input: String) {
        val v = Site.normalize(input)
        if (v == null) {
            error = "網址格式不正確"
            return
        }
        Site.base = v
        MainState.home.load(force = true)
        MainState.cats.load(force = true)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("網址設定") },
        text = {
            Column {
                Text("網站換網址時，改成新網址。", color = Muted, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent),
                )
            }
        },
        confirmButton = { TextButton(onClick = { save(text) }) { Text("儲存", color = Accent) } },
        dismissButton = {
            Row {
                TextButton(onClick = { save("") }) { Text("還原預設") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(route: Route.Search) {
    // 還沒輸入關鍵字：搜尋框和搜尋紀錄
    if (route.query.isEmpty()) {
        var query by rememberSaveable { mutableStateOf("") }
        val keyboard = LocalSoftwareKeyboardController.current
        fun search(q: String) {
            val word = q.trim()
            if (word.isEmpty()) return
            keyboard?.hide()
            Store.addSearchWord(word)
            Nav.pop()
            Nav.push(Route.Search(word))
        }
        Column(Modifier.fillMaxSize()) {
            AppBar("搜尋", back = true)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("影片、劇集名稱") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search(query) }),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, cursorColor = Accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (Store.searchWords.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("搜尋紀錄", color = Muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = Store::clearSearchWords) { Text("清除", color = Muted) }
                }
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Store.searchWords.forEach { w -> AssistChip(onClick = { search(w) }, label = { Text(w) }) }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        AppBar("搜尋：${route.query}", back = true)
        val (nv, ns) = route.totals
        FilterRow(listOf("video" to "影片 $nv", "series" to "劇集 $ns"), route.tab) { route.tab = it }
        LaunchedEffect(route) { route.cats.load() }
        FilterRow(route.cats.data ?: listOf("" to "全部"), route.cat) { route.cat = it }
        FilterRow(Api.SEARCH_SORTS, route.sort) { route.sort = it }
        if (route.tab == "series") FeedGrid(route.seriesFeed, minCell = 120, key = { it.id }) { SeriesCard(it) }
        else FeedGrid(route.videoFeed, key = { it.id }) { VideoCard(it) }
    }
}

@Composable
fun TagScreen(route: Route.Tag) {
    Column(Modifier.fillMaxSize()) {
        AppBar(route.tag + if (route.total > 0) "（${route.total}）" else "", back = true)
        FilterRow(Api.ORDERS, route.order) { route.order = it }
        if (route.siblings.isNotEmpty()) FilterRow(route.siblings.map { it to it }, "") { Nav.push(Route.Tag(it)) }
        FeedGrid(route.feed, key = { it.id }) { VideoCard(it) }
    }
}

@Composable
fun SeriesListScreen(route: Route.SeriesList) {
    Column(Modifier.fillMaxSize()) {
        AppBar(route.title, back = true)
        SeriesFilters(route.state)
        FeedGrid(route.state.feed, minCell = 120, key = { it.id }) { SeriesCard(it) }
    }
}
