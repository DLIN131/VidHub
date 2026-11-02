package com.vidhub.core

import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 連到真的網站測一遍（平常不跑）：設定環境變數 VIDHUB_LIVE_BASE=https://網址 才會執行。
 * 測資料欄位、翻頁網址、播放清單和影片片段的拆包裝。
 */
class LiveTest {
    private val base = System.getenv("VIDHUB_LIVE_BASE")

    @Test
    fun liveSite() = runBlocking {
        if (base.isNullOrEmpty()) return@runBlocking
        Site.testBase = base

        val home = Api.home()
        println("home: " + home.joinToString { "${it.title}(${it.videos.size + it.series.size})" })
        assertTrue(home.size >= 5)
        val v = home.first { it.videos.isNotEmpty() }.videos.first()
        println("video: $v")
        assertTrue(v.id.isNotEmpty() && v.title.isNotEmpty() && v.cover.startsWith("http") && v.duration > 0)

        val p2 = Api.videos("viewCount", 2)
        println("videos p2: ${p2.items.size} hasMore=${p2.hasMore} total=${p2.total} first=${p2.items.first().title}")
        assertTrue(p2.items.size > 10 && p2.hasMore)

        val tag = Api.tag(Api.categories().cats.first().id, "likeCount", 1)
        println("tag: ${tag.page.items.size} total=${tag.page.total} siblings=${tag.siblings.take(5)}")
        assertTrue(tag.page.items.isNotEmpty())

        val sl = Api.seriesList("completed", "hot", "", 1)
        println("series: ${sl.page.items.size} tags=${sl.tags.take(5)} first=${sl.page.items.first()}")
        assertTrue(sl.page.items.isNotEmpty())

        val sd = Api.seriesDetail(sl.page.items.first().id)
        println("series detail: ${sd.series.title} eps=${sd.episodes.map { it.episode }} more=${sd.more.size}")
        assertTrue(sd.episodes.isNotEmpty())

        val s = Api.search("KTV", "", "video", "", 1)
        println("search: videos=${s.totalVideos} series=${s.totalSeries} got=${s.videos.items.size}")
        assertTrue(s.videos.items.isNotEmpty())

        val cats = Api.categories()
        println("cats: ${cats.cats.map { "${it.id}:${it.kids.size}" }} genres=${cats.genres.size}")
        assertTrue(cats.cats.isNotEmpty())

        val d = Api.videoDetail(v.id)
        println("detail: ${d.video.title} tags=${d.video.tags} related=${d.related.size}")
        assertTrue(d.related.isNotEmpty())

        // 播放清單：轉址到 CDN，拆掉 PNG 包裝後是一般 HLS
        val playlist = fetch(Api.streamUrl(v.id))
        val text = String(Stream.unwrap(playlist.second))
        println("playlist from ${playlist.first.take(80)}:\n" + text.lines().take(5).joinToString("\n"))
        assertTrue(text.startsWith("#EXTM3U"))
        val seg = text.lines().first { it.isNotBlank() && !it.startsWith("#") }
        val ts = Stream.unwrap(fetch(java.net.URI(playlist.first).resolve(seg).toString()).second)
        println("segment: ${ts.size} bytes, sync=${ts[0] == 0x47.toByte() && ts[188] == 0x47.toByte()}")
        assertTrue(ts[0] == 0x47.toByte() && ts[188] == 0x47.toByte())
    }

    /** 回傳（最後的網址, 內容） */
    private fun fetch(url: String): Pair<String, ByteArray> =
        Api.http.newCall(Request.Builder().url(url).header("User-Agent", Site.USER_AGENT).build()).execute().use { r ->
            assertTrue("HTTP ${r.code} $url", r.isSuccessful)
            r.request.url.toString() to r.body!!.bytes()
        }
}
