package com.vidhub.app

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

sealed class Route {
    abstract val key: String

    data object Main : Route() {
        override val key = "main"
    }

    /** 某個分類（標籤）的影片 */
    class Tag(val tag: String, order: String = "createdAt") : Route() {
        override val key = "tag:$tag"
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

    /** 劇集列表（從首頁「更多」或劇集頁的類型標籤進來） */
    class SeriesList(val title: String, filter: String = "all", sort: String = "updated", tag: String = "") : Route() {
        override val key = "series:$title"
        val state = SeriesState(filter, sort, tag)
    }

    class Search(val query: String) : Route() {
        override val key = "search:$query"
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

    class VideoPage(val preview: Video) : Route() {
        override val key = "video:${preview.id}"
        val detail = Loader { Api.videoDetail(preview.id) }
    }

    class ShowPage(val preview: Series) : Route() {
        override val key = "show:${preview.id}"
        val detail = Loader { Api.seriesDetail(preview.id) }
    }

    /** queue：劇集的所有集（看完自動播下一集），series：所屬劇集 */
    class Player(val video: Video, val queue: List<Video> = emptyList(), val series: Series? = null) : Route() {
        override val key = "player:${video.id}"
    }
}

/** 劇集列表的篩選和資料（劇集分頁、劇集列表頁共用） */
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

object Nav {
    val stack = mutableStateListOf<Route>(Route.Main)
    val top: Route get() = stack.last()

    fun push(r: Route) {
        stack.add(r)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

/** 主畫面各分頁的狀態 */
object MainState {
    var tab by mutableIntStateOf(0)
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
    var libraryTab by mutableIntStateOf(0)
}
