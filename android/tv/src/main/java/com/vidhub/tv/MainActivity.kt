package com.vidhub.tv

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.vidhub.core.UpdateDialog
import kotlinx.coroutines.delay

/** 電視版：自己的介面（不開網站本身），遙控器操作；播放在 [PlayerActivity] */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TvAppTheme { AppRoot() } }
        UpdateDialog.checkOnStart(this)
    }
}

private val Tab.icon: ImageVector
    get() = when (this) {
        Tab.Home -> Icons.Filled.Home
        Tab.Search -> Icons.Filled.Search
        Tab.Videos -> Icons.Filled.PlayArrow
        Tab.Series -> Icons.AutoMirrored.Filled.List
        Tab.Cats -> Icons.Filled.Star
        Tab.Favorites -> Icons.Filled.Favorite
        Tab.History -> Icons.Filled.DateRange
        Tab.Settings -> Icons.Filled.Settings
    }

@Composable
fun AppRoot() {
    var firstLaunch by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxSize().background(Bg)) {
        val top = TvNav.stack.lastOrNull()
        if (top == null) {
            MainScreen(focusDrawer = firstLaunch, onFocused = { firstLaunch = false })
        } else {
            key(TvNav.stack.size, top) {
                when (top) {
                    is TvRoute.Detail -> DetailScreen(top)
                    is TvRoute.Show -> ShowScreen(top)
                    is TvRoute.Tag -> TagScreen(top)
                    is TvRoute.SeriesList -> SeriesListScreen(top)
                    is TvRoute.Search -> SearchResultScreen(top)
                }
            }
        }
    }
    BackHandler(enabled = TvNav.stack.isNotEmpty()) { TvNav.pop() }
}

@Composable
fun MainScreen(focusDrawer: Boolean, onFocused: () -> Unit) {
    val activity = LocalContext.current as Activity
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }
    var drawerFocused by remember { mutableStateOf(false) }
    val tab = TvState.tab

    LaunchedEffect(Unit) {
        if (focusDrawer) {
            delay(100)
            runCatching { requesters.getValue(tab).requestFocus() }
            onFocused()
        }
    }

    // 內容區按返回鍵先回到側邊選單，在選單上再按返回才離開
    BackHandler {
        if (drawerFocused) activity.finish()
        else runCatching { requesters.getValue(tab).requestFocus() }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { value ->
            Column(
                Modifier.fillMaxHeight()
                    .background(if (value == DrawerValue.Open) Color(0xFF17171C) else Color.Transparent)
                    .padding(12.dp)
                    .onFocusChanged { drawerFocused = it.hasFocus }
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.height(16.dp))
                Text(
                    if (value == DrawerValue.Open) "VidHub" else "V",
                    color = Accent,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, bottom = 16.dp),
                )
                Tab.entries.forEach { t ->
                    NavigationDrawerItem(
                        selected = t == tab,
                        onClick = { TvState.tab = t },
                        leadingContent = { Icon(t.icon, contentDescription = null) },
                        modifier = Modifier.focusRequester(requesters.getValue(t)),
                    ) { Text(t.label) }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().padding(start = 92.dp)) {
            when (tab) {
                Tab.Home -> HomeScreen()
                Tab.Search -> SearchScreen()
                Tab.Videos -> VideosScreen()
                Tab.Series -> SeriesGrid("劇集", TvState.series)
                Tab.Cats -> CatsScreen()
                Tab.Favorites -> LibraryScreen(favorites = true)
                Tab.History -> LibraryScreen(favorites = false)
                Tab.Settings -> SettingsScreen()
            }
        }
    }
}
