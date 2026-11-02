package com.vidhub.core

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** 整個 App 共用的 coroutine scope，列表載入不跟著畫面消失而取消 */
val appScope: CoroutineScope = MainScope()

/**
 * 可無限往下捲的列表。狀態（含捲動位置）存在物件裡，
 * 跟著導覽堆疊走，回上一頁時列表和位置都還在。
 * onPage 在每一頁回來時呼叫（拿總數、篩選選項之類的）。
 */
class Feed<T>(
    private val key: (T) -> String,
    private val onPage: (Page<T>) -> Unit = {},
    private val loader: suspend (page: Int) -> Page<T>,
) {
    val items = mutableStateListOf<T>()
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var end by mutableStateOf(false)
        private set
    val grid = LazyGridState()
    /** 電視版：從影片頁返回時，焦點要回到剛剛點的那張卡片 */
    var focusIndex = 0
    var restoreFocus = false
    private var page = 0
    private var job: Job? = null

    fun loadMore() {
        if (loading || end) return
        loading = true
        error = null
        job = appScope.launch {
            try {
                val r = loader(page + 1)
                page++
                onPage(r)
                val seen = items.mapTo(HashSet()) { key(it) }
                items.addAll(r.items.filter { key(it) !in seen })
                if (!r.hasMore || r.items.isEmpty()) end = true
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    fun refresh() {
        job?.cancel()
        loading = false
        items.clear()
        page = 0
        end = false
        error = null
        appScope.launch { grid.scrollToItem(0) }
        loadMore()
    }
}

/** 只載一次的資料（首頁、分類、影片頁…） */
class Loader<T>(private val load: suspend () -> T) {
    var data by mutableStateOf<T?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    fun load(force: Boolean = false) {
        if (loading || (data != null && !force)) return
        loading = true
        error = null
        appScope.launch {
            try {
                data = load.invoke()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }
}
