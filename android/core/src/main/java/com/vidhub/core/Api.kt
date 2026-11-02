package com.vidhub.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** 影片 */
data class Video(
    val id: String,
    val title: String,
    val cover: String,
    val duration: Double = 0.0,
    val views: Long = 0,
    val tags: List<String> = emptyList(),
    val date: String = "",
    val res: Int = 0,
    val code: String = "",
    val episode: Int? = null,
)

/** 劇集 */
data class Series(
    val id: String,
    val title: String,
    val cover: String,
    val poster: Boolean = false,
    val epLabel: String = "",
    val status: String = "",
    val views: Long = 0,
    val tags: List<String> = emptyList(),
    val description: String = "",
)

/** 一頁列表；hasMore = 還有下一頁 */
data class Page<T>(val items: List<T>, val hasMore: Boolean, val total: Int = 0)

/** 首頁的一個區塊：videos 或 series 其中一個有東西；more 是「更多」要開的列表 */
data class Shelf(val title: String, val videos: List<Video>, val series: List<Series>, val more: More)

/** 「更多」可以開的列表 */
sealed class More {
    data object Videos : More()
    data class Tag(val tag: String, val order: String = "createdAt") : More()
    data class SeriesList(val filter: String = "all", val sort: String = "updated") : More()
}

data class VideoDetail(val video: Video, val related: List<Video>, val series: Series?, val episodes: List<Video>, val moreSeries: List<Series>)
data class SeriesDetail(val series: Series, val episodes: List<Video>, val more: List<Series>)
data class Category(val id: String, val count: Int, val cover: String, val kids: List<Pair<String, Int>>)
data class Categories(val cats: List<Category>, val genres: List<Pair<String, Int>>)
data class TagPage(val page: Page<Video>, val siblings: List<String>)
data class SeriesPage(val page: Page<Series>, val tags: List<String>)
data class SearchPage(val videos: Page<Video>, val series: Page<Series>, val totalVideos: Int, val totalSeries: Int)

/**
 * 網站資料 → App 用的格式。頁面資料見 [Tsr]；網站改版時要改的是這個檔案（電腦版是 src/api.js）。
 */
@Suppress("UNCHECKED_CAST")
object Api {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /** 影片排序（影片庫、分類） */
    val ORDERS = listOf("createdAt" to "最新發布", "viewCount" to "最多觀看", "likeCount" to "最多喜歡")
    val SERIES_FILTERS = listOf("all" to "全部", "ongoing" to "連載中", "completed" to "已完結", "fresh" to "本週更新")
    val SERIES_SORTS = listOf("updated" to "最新更新", "hot" to "最多觀看", "likes" to "最多喜歡", "most" to "集數最多")
    val SEARCH_SORTS = listOf("" to "相關度", "createdAt:desc" to "最新發布", "viewCount:desc" to "最多播放", "likeCount:desc" to "最多喜歡")

    private suspend fun page(path: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(Site.base + path)
            .header("User-Agent", Site.USER_AGENT)
            .header("Accept-Language", "zh-TW,zh;q=0.9")
            .build()
        val html = http.newCall(req).execute().use { r ->
            if (r.code == 404) throw IOException("找不到這個頁面（可能已被刪除）")
            if (!r.isSuccessful) throw IOException("網站回應錯誤 HTTP ${r.code}")
            r.body!!.string()
        }
        Tsr.pageData(html) ?: throw IOException("讀不到頁面內容（網站可能改版或換網址，可以到設定的「網址設定」修改）")
    }

    // ---------- 欄位整理 ----------

    private fun Map<String, Any?>.str(k: String) = (this[k] as? String).orEmpty()
    private fun Map<String, Any?>.num(k: String) = (this[k] as? Number)?.toDouble() ?: 0.0
    private fun Map<String, Any?>.list(k: String) = this[k] as? List<Any?> ?: emptyList()
    private fun Map<String, Any?>.maps(k: String) = list(k).mapNotNull { it as? Map<String, Any?> }
    private fun Map<String, Any?>.strings(k: String) = list(k).mapNotNull { it as? String }

    private fun video(m: Map<String, Any?>) = Video(
        id = m.str("id"),
        title = m.str("name"),
        cover = m.str("coverImageUrl"),
        duration = m.num("duration"),
        views = m.num("viewCount").toLong(),
        tags = m.strings("tags"),
        date = m.str("createdAt").take(10),
        res = m.maps("sources").maxOfOrNull { it.num("resolution").toInt() } ?: 0,
        code = m.str("vid"),
        episode = (m["episode"] as? Number)?.toInt(),
    )

    private fun series(m: Map<String, Any?>) = Series(
        id = m.str("id"),
        title = m.str("name"),
        cover = m.str("coverImageUrl"),
        poster = m["isPoster"] == true,
        epLabel = m.str("epLabel").ifEmpty { m.num("episodeCount").toInt().takeIf { it > 0 }?.let { "$it 集" }.orEmpty() },
        status = when (m.str("status")) {
            "COMPLETED" -> "已完結"
            "ONGOING" -> "連載中"
            else -> ""
        },
        views = m.num("viewCount").toLong(),
        tags = m.strings("tags"),
        description = m.str("description"),
    )

    private fun <T> paged(d: Map<String, Any?>, items: List<T>) = Page(
        items = items,
        hasMore = d.num("pageNum").coerceAtLeast(1.0) < d.num("totalPage").coerceAtLeast(1.0),
        total = (d["totalVideoNum"] as? Number ?: d["total"] as? Number)?.toInt() ?: 0,
    )

    /** 網址參數；空字串、第 1 頁都省略（跟網站自己的連結一樣） */
    private fun qs(vararg p: Pair<String, Any?>): String {
        val q = p.filter { (_, v) -> v != null && v != "" && v != 1 }.joinToString("&") { (k, v) -> "$k=" + enc(v.toString()) }
        return if (q.isEmpty()) "" else "?$q"
    }

    /** 網址編碼（空白是 %20，路徑和參數都能用） */
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---------- 頁面 ----------

    /**
     * 首頁的各個區塊。區塊的分類名稱不寫死在程式裡，從資料本身判斷：
     * 欄位名稱開頭 latest = 最新影片、daily = 今日熱門、hot = 最受歡迎、fresh/ranked = 劇集；
     * 分類是這一區影片最常見的第一個標籤。
     */
    suspend fun home(): List<Shelf> {
        val d = page("/home")
        val out = LinkedHashMap<String, Shelf>()
        for ((key, value) in d) {
            if (key.startsWith("hero")) continue // 跟熱門劇集重複
            val maps = (value as? List<Any?>)?.mapNotNull { it as? Map<String, Any?> }.orEmpty()
            if (maps.isEmpty()) continue
            val shelf = when {
                "episodeCount" in maps[0] ->
                    if (key.startsWith("fresh")) Shelf("本週更新劇集", emptyList(), maps.map(::series), More.SeriesList(filter = "fresh"))
                    else Shelf("熱門劇集", emptyList(), maps.map(::series), More.SeriesList(sort = "hot"))
                "duration" in maps[0] -> {
                    val videos = maps.map(::video)
                    if (key.startsWith("latest")) Shelf("最新影片", videos, emptyList(), More.Videos)
                    else {
                        val tag = videos.mapNotNull { it.tags.firstOrNull() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: continue
                        val hot = key.startsWith("hot")
                        Shelf(if (hot) "最受歡迎・$tag" else "今日熱門・$tag", videos, emptyList(), More.Tag(tag, if (hot) "likeCount" else "createdAt"))
                    }
                }
                else -> continue
            }
            out.putIfAbsent(shelf.title, shelf)
        }
        // 最新影片放最上面
        return out.values.sortedBy { if (it.more == More.Videos) 0 else 1 }
    }

    suspend fun videos(order: String, page: Int): Page<Video> {
        val d = page("/v" + qs("order" to order.takeIf { it != "createdAt" }, "page" to page))
        return paged(d, d.maps("videos").map(::video))
    }

    suspend fun tag(name: String, order: String, page: Int): TagPage {
        val d = page("/t/${enc(name)}" + qs("order" to order.takeIf { it != "createdAt" }, "page" to page))
        return TagPage(paged(d, d.maps("videos").map(::video)), d.maps("siblings").map { it.str("id") })
    }

    suspend fun seriesList(filter: String, sort: String, tag: String, page: Int): SeriesPage {
        val d = page("/series" + qs("f" to filter.takeIf { it != "all" }, "sort" to sort.takeIf { it != "updated" }, "tag" to tag, "page" to page))
        return SeriesPage(paged(d, d.maps("list").map(::series)), d.maps("tagOptions").map { it.str("id") })
    }

    /** tab："video" 或 "series" */
    suspend fun search(q: String, cat: String, tab: String, sort: String, page: Int): SearchPage {
        val d = page("/search" + qs("q" to q, "t" to cat, "tab" to tab.takeIf { it != "video" }, "sort" to sort, "page" to page))
        return SearchPage(
            videos = paged(d, d.maps("videos").map(::video)),
            series = paged(d, d.maps("seriesList").map(::series)),
            totalVideos = d.num("totalVideoNum").toInt(),
            totalSeries = d.num("totalSeriesNum").toInt(),
        )
    }

    @Volatile
    private var catsCache: Categories? = null

    /** 分類一覽（第一次抓完就記住，搜尋的分類篩選也用這份） */
    suspend fun categories(): Categories {
        catsCache?.let { return it }
        val t = page("/cat")["taxonomy"] as? Map<String, Any?> ?: emptyMap()
        val byParent = t["byParent"] as? Map<String, Any?> ?: emptyMap()
        val pair = { m: Map<String, Any?> -> m.str("id") to m.num("count").toInt() }
        return Categories(
            cats = t.maps("cats").map { c ->
                val id = c.str("id")
                Category(id, c.num("count").toInt(), c.str("coverImageUrl"), (byParent[id] as? List<Any?>).orEmpty().mapNotNull { (it as? Map<String, Any?>)?.let(pair) })
            },
            genres = t.maps("genre").map(pair),
        ).also { catsCache = it }
    }

    /** 搜尋的分類篩選：全部 + 網站的主分類 */
    suspend fun searchCats(): List<Pair<String, String>> = listOf("" to "全部") + categories().cats.map { it.id to it.id }

    suspend fun videoDetail(id: String): VideoDetail {
        val d = page("/v/$id")
        val v = d["video"] as? Map<String, Any?> ?: throw IOException("找不到這部影片（可能已被刪除）")
        return VideoDetail(
            video = video(v),
            related = d.maps("relatedVideos").map(::video),
            series = (d["series"] as? Map<String, Any?>)?.let(::series),
            episodes = d.maps("episodes").map(::video),
            moreSeries = d.maps("moreSeries").map(::series),
        )
    }

    suspend fun seriesDetail(id: String): SeriesDetail {
        val d = page("/s/$id")
        val s = d["series"] as? Map<String, Any?> ?: throw IOException("找不到這部劇集（可能已被刪除）")
        return SeriesDetail(series(s), d.maps("episodes").map(::video), d.maps("more").map(::series))
    }

    /** 播放清單網址（會轉址到影片 CDN；內容包在 PNG 裡，見 [Stream]） */
    fun streamUrl(id: String) = "${Site.base}/api/hls/$id"
}

/** 秒數 → h:mm:ss */
fun fmtTime(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val ss = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, ss) else "%d:%02d".format(m, ss)
}

/** 觀看次數：1.2 萬 */
fun fmtCount(n: Long): String = when {
    n >= 100_000 -> "${n / 10_000} 萬"
    n >= 10_000 -> "%.1f 萬".format(n / 10_000.0)
    else -> n.toString()
}
