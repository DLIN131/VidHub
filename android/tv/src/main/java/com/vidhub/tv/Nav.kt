package com.vidhub.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vidhub.core.Api
import com.vidhub.core.Feed
import com.vidhub.core.Loader
import com.vidhub.core.Series
import com.vidhub.core.Video

/** 主畫面上疊的頁面；狀態存在物件裡，返回時列表和捲動位置都還在 */
sealed class TvRoute {
    class Detail(val preview: Video) : TvRoute() {
        val detail = Loader { Api.videoDetail(preview.id) }
    }

    class Show(val preview: Series) : TvRoute() {
        val detail = Loader { Api.seriesDetail(preview.id) }
    }

    /** 某個分類（標籤）的影片 */
    class Tag(val tag: String, order: String = "createdAt") : TvRoute() {
        var order by mutableStateOf(order)
        var siblings by mutableStateOf(listOf<String>())
        var total by mutableIntStateOf(0)
        private val feeds = HashMap<String, Feed<Video>>()
        val feed: Feed<Video>
            get() = feeds.getOrPut(order) {
                val o = order
                Feed({ it.id }) { p ->
                    val r = Api.tag(tag, o, p)
                    if (p == 1) {
                        siblings = r.siblings.filter { it != tag }
                        total = r.page.total
                    }
                    r.page
                }
            }
    }

    class SeriesList(val title: String, filter: String = "all", sort: String = "updated", tag: String = "") : TvRoute() {
        val state = SeriesState(filter, sort, tag)
    }

    class Search(val query: String) : TvRoute() {
        var tab by mutableStateOf("video")
        var cat by mutableStateOf("")
        var sort by mutableStateOf("")
        var totals by mutableStateOf(0 to 0)
        /** 分類篩選的選項（從網站的分類一覽來） */
        val cats = Loader { Api.searchCats() }
        private val videoFeeds = HashMap<String, Feed<Video>>()
        private val seriesFeeds = HashMap<String, Feed<Series>>()
        val videoFeed: Feed<Video>
            get() = videoFeeds.getOrPut("$cat|$sort") {
                val c = cat
                val s = sort
                Feed({ it.id }) { p -> Api.search(query, c, "video", s, p).also { totals = it.totalVideos to it.totalSeries }.videos }
            }
        val seriesFeed: Feed<Series>
            get() = seriesFeeds.getOrPut("$cat|$sort") {
                val c = cat
                val s = sort
                Feed({ it.id }) { p -> Api.search(query, c, "series", s, p).also { totals = it.totalVideos to it.totalSeries }.series }
            }
    }
}

/** 劇集列表的篩選和資料 */
class SeriesState(filter: String = "all", sort: String = "updated", tag: String = "") {
    var filter by mutableStateOf(filter)
    var sort by mutableStateOf(sort)
    var tag by mutableStateOf(tag)
    var tags by mutableStateOf(listOf<String>())
    private val feeds = HashMap<String, Feed<Series>>()
    val feed: Feed<Series>
        get() = feeds.getOrPut("$filter|$sort|$tag") {
            val f = filter
            val s = sort
            val t = tag
            Feed({ it.id }) { p ->
                val r = Api.seriesList(f, s, t, p)
                if (p == 1 && tags.isEmpty()) tags = r.tags
                r.page
            }
        }
}

object TvNav {
    val stack = mutableStateListOf<TvRoute>()

    fun push(r: TvRoute) {
        stack.add(r)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }
}

enum class Tab(val label: String) {
    Home("首頁"),
    Search("搜尋"),
    Videos("影片"),
    Series("劇集"),
    Cats("分類"),
    Favorites("收藏"),
    History("觀看紀錄"),
    Settings("設定"),
}

/** 側邊選單各頁的狀態 */
object TvState {
    var tab by mutableStateOf(Tab.Home)
    val home = Loader { Api.home() }
    var videoOrder by mutableStateOf("createdAt")
    private val videoFeeds = HashMap<String, Feed<Video>>()
    val videoFeed: Feed<Video>
        get() = videoFeeds.getOrPut(videoOrder) {
            val o = videoOrder
            Feed({ it.id }) { Api.videos(o, it) }
        }
    val series = SeriesState()
    val cats = Loader { Api.categories() }
}
