package com.vidhub.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject

/** 收藏、紀錄裡存的卡片資料；series = true 是劇集 */
data class Saved(
    val id: String,
    val title: String,
    val cover: String,
    val series: Boolean = false,
    val duration: Double = 0.0,
    val epLabel: String = "",
    val poster: Boolean = false,
) {
    fun toVideo() = Video(id, title, cover, duration)
    fun toSeries() = Series(id, title, cover, poster, epLabel)
}

fun Video.saved() = Saved(id, title, cover, false, duration)
fun Series.saved() = Saved(id, title, cover, true, epLabel = epLabel, poster = poster)

/** 本機的觀看紀錄與收藏（不登入網站帳號，全部存在裝置上） */
object Store {
    private const val MAX_HISTORY = 300
    private lateinit var sp: SharedPreferences

    val history = mutableStateListOf<Saved>()
    val favorites = mutableStateListOf<Saved>()
    val searchWords = mutableStateListOf<String>()

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)
        history.addAll(load("history"))
        favorites.addAll(load("favorites"))
        searchWords.addAll(sp.getString("search_words", "").orEmpty().split('\n').filter { it.isNotBlank() })
    }

    fun addSearchWord(word: String) {
        searchWords.remove(word)
        searchWords.add(0, word)
        while (searchWords.size > 20) searchWords.removeAt(searchWords.lastIndex)
        sp.edit().putString("search_words", searchWords.joinToString("\n")).apply()
    }

    fun clearSearchWords() {
        searchWords.clear()
        sp.edit().remove("search_words").apply()
    }

    fun addHistory(v: Video) {
        history.removeAll { it.id == v.id }
        history.add(0, v.saved())
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)
        save("history", history)
    }

    fun clearHistory() {
        history.clear()
        save("history", history)
        sp.edit().apply { sp.all.keys.filter { it.startsWith("pos_") || it.startsWith("ep_") }.forEach(::remove) }.apply()
    }

    fun isFavorite(id: String) = favorites.any { it.id == id }

    fun toggleFavorite(item: Saved) {
        if (!favorites.removeAll { it.id == item.id }) favorites.add(0, item)
        save("favorites", favorites)
    }

    /** 播放進度（秒），看完會清掉 */
    fun position(id: String) = sp.getFloat("pos_$id", 0f).toDouble()

    fun savePosition(id: String, sec: Double) {
        sp.edit().apply { if (sec > 0) putFloat("pos_$id", sec.toFloat()) else remove("pos_$id") }.apply()
    }

    /** 每部劇集上次看到哪一集（那一集的影片 id） */
    fun lastEpisode(seriesId: String): String = sp.getString("ep_$seriesId", "").orEmpty()

    fun setLastEpisode(seriesId: String, videoId: String) {
        sp.edit().putString("ep_$seriesId", videoId).apply()
    }

    private fun load(key: String): List<Saved> = runCatching {
        val arr = JSONArray(sp.getString(key, "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Saved(
                id = o.getString("id"),
                title = o.optString("title"),
                cover = o.optString("cover"),
                series = o.optBoolean("series"),
                duration = o.optDouble("duration", 0.0),
                epLabel = o.optString("epLabel"),
                poster = o.optBoolean("poster"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(key: String, list: List<Saved>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("cover", it.cover)
                    .put("series", it.series)
                    .put("duration", it.duration)
                    .put("epLabel", it.epLabel)
                    .put("poster", it.poster)
            )
        }
        sp.edit().putString(key, arr.toString()).apply()
    }
}
