package com.vidhub.core

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * 影片下載（寫法參考 GrabBox / MPlayer 的下載器）。
 * 播放清單和片段都包在 PNG 裡（見 [Stream.unwrap]），拆開後是 TS 片段，依序接成一個 <id>.ts，App 內離線播放。
 * 存在 App 專屬資料夾（不會出現在相簿，移除 App 時一起刪掉）：
 * <id>.ts、<id>.json（進度與影片資料）、<id>.jpg（封面，離線也看得到）。
 * 一次下載一部，每部同時抓 4 個片段；狀態都在主執行緒更新。
 */
object Downloads {
    enum class Status { Queued, Running, Paused, Failed, Done }

    class Item(
        val video: Video,
        status: Status,
        total: Int,
        done: Int,
        bytes: Long,
        val addedAt: Long,
    ) {
        val id get() = video.id
        var status by mutableStateOf(status)
        var total by mutableIntStateOf(total)
        var done by mutableIntStateOf(done)
        var bytes by mutableLongStateOf(bytes)
        var error by mutableStateOf<String?>(null)
        val progress get() = if (total > 0) done.toFloat() / total else 0f
        val file get() = File(root, "$id.ts")
        /** 封面：有存到本機就用本機的 */
        val cover: Any get() = File(root, "$id.jpg").takeIf { it.exists() } ?: video.cover
    }

    private const val PARALLEL = 4
    private const val RETRIES = 3

    /** 新加入的在前面 */
    val items = mutableStateListOf<Item>()

    private lateinit var app: Context
    private val root: File by lazy {
        (app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(app.filesDir, "downloads"))
            .apply {
                mkdirs()
                // 保險起見叫媒體掃描不要收錄
                File(this, ".nomedia").takeIf { !it.exists() }?.createNewFile()
            }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 進度存檔一次只寫一個，順序才不會亂 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var runner: Job? = null
    private var current: Pair<String, Job>? = null
    private var loaded = false

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        app = context.applicationContext
        root.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.addedAt }
            .forEach { items.add(it) }
    }

    fun find(id: String): Item? = items.firstOrNull { it.id == id }

    /** 下載完成、檔案還在的話回傳檔案 */
    fun doneFile(id: String): File? = find(id)?.takeIf { it.status == Status.Done }?.file?.takeIf { it.exists() }

    /** 可用空間（位元組） */
    fun freeSpace(): Long = runCatching { StatFs(root.path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    fun start(video: Video) {
        find(video.id)?.let { delete(it.id) }
        val item = Item(video, Status.Queued, 0, 0, 0, System.currentTimeMillis())
        items.add(0, item)
        save(item)
        kick()
    }

    fun pause(id: String) {
        val item = find(id) ?: return
        if (current?.first == id) current?.second?.cancel()
        if (item.status == Status.Running || item.status == Status.Queued) item.status = Status.Paused
        save(item)
    }

    fun resume(id: String) {
        val item = find(id) ?: return
        if (item.status == Status.Done) return
        item.error = null
        item.status = Status.Queued
        save(item)
        kick()
    }

    fun delete(id: String) {
        val item = find(id) ?: return
        if (current?.first == id) current?.second?.cancel()
        items.remove(item)
        scope.launch(Dispatchers.IO) {
            // 等正在寫的片段停下來再刪
            delay(300)
            listOf("ts", "json", "jpg").forEach { File(root, "$id.$it").delete() }
        }
    }

    /** 依序處理排隊中的下載（先加入的先下載） */
    private fun kick() {
        DownloadService.start(app)
        if (runner?.isActive == true) return
        runner = scope.launch {
            while (true) {
                val next = items.lastOrNull { it.status == Status.Queued } ?: break
                val job = launch { run(next) }
                current = next.id to job
                job.join()
                current = null
            }
        }
    }

    /** 播放清單 → 片段網址（片段網址有簽章、會過期，每次開始下載都重新拿） */
    private suspend fun segments(id: String): List<String> = withContext(Dispatchers.IO) {
        val (url, body) = fetch(Api.streamUrl(id))
        val lines = String(body).lines().map { it.trim() }
        if (lines.firstOrNull() != "#EXTM3U") throw IOException("讀不到影片的播放清單")
        val key = lines.firstOrNull { it.startsWith("#EXT-X-KEY:") }
        if (key != null && !key.contains("METHOD=NONE")) throw IOException("這部影片有加密，無法下載")
        val base = java.net.URI(url)
        lines.filter { it.isNotEmpty() && !it.startsWith("#") }.map { base.resolve(it).toString() }
    }

    /** 抓一個網址並拆掉 PNG 包裝；回傳（轉址後的網址, 內容） */
    private fun fetch(url: String): Pair<String, ByteArray> =
        Api.http.newCall(Request.Builder().url(url).header("User-Agent", Site.USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.request.url.toString() to Stream.unwrap(r.body!!.bytes())
        }

    private suspend fun run(item: Item) {
        item.status = Status.Running
        item.error = null
        DownloadService.update(app, item)
        try {
            val segs = segments(item.id)
            if (segs.isEmpty()) throw IOException("找不到影片片段")
            // 片段數變了（網站重新轉檔）就不能接著用舊的檔案
            if (item.total != 0 && item.total != segs.size) {
                item.done = 0
                item.bytes = 0
            }
            item.total = segs.size
            withContext(Dispatchers.IO) {
                // 上次中斷時寫到一半的片段丟掉，從記錄的位置接著寫
                RandomAccessFile(item.file, "rw").use { it.setLength(item.bytes) }
            }
            saveCover(item)
            FileOutputStream(item.file, true).use { out ->
                while (item.done < segs.size) {
                    val from = item.done
                    val to = minOf(from + PARALLEL, segs.size)
                    // 同時抓幾個片段，但依序寫入
                    val chunks = coroutineScope {
                        (from until to).map { i -> async(Dispatchers.IO) { segment(segs[i]) } }.map { it.await() }
                    }
                    withContext(Dispatchers.IO) { chunks.forEach(out::write) }
                    item.done = to
                    item.bytes += chunks.sumOf { it.size.toLong() }
                    save(item)
                    DownloadService.update(app, item)
                }
            }
            item.status = Status.Done
        } catch (e: CancellationException) {
            if (item.status == Status.Running) item.status = Status.Paused
            throw e
        } catch (e: Exception) {
            item.status = Status.Failed
            item.error = e.message ?: e.toString()
        } finally {
            if (items.contains(item)) save(item)
        }
    }

    private suspend fun segment(url: String): ByteArray {
        var last: Exception? = null
        repeat(RETRIES) { attempt ->
            try {
                return alignTs(fetch(url).second)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                delay(1000L * (attempt + 1))
            }
        }
        throw IOException("網路錯誤：${last?.message}")
    }

    /** TS 每 188 位元組一個封包、開頭都是 0x47；前面多了別的東西就從連續三個封包開頭的位置開始 */
    private fun alignTs(b: ByteArray): ByteArray {
        val sync = 0x47.toByte()
        if (b.size < 188 * 3 || (b[0] == sync && b[188] == sync)) return b
        val limit = minOf(b.size - 188 * 2, 1024 * 1024)
        for (i in 1 until limit) {
            if (b[i] == sync && b[i + 188] == sync && b[i + 376] == sync) return b.copyOfRange(i, b.size)
        }
        return b
    }

    private suspend fun saveCover(item: Item) {
        val f = File(root, "${item.id}.jpg")
        if (f.exists() || item.video.cover.isEmpty()) return
        runCatching {
            withContext(Dispatchers.IO) {
                Api.http.newCall(Request.Builder().url(item.video.cover).build()).execute().use { r ->
                    if (r.isSuccessful) f.writeBytes(r.body!!.bytes())
                }
            }
        }
    }

    private fun save(item: Item) {
        val json = JSONObject()
            .put("id", item.id)
            .put("title", item.video.title)
            .put("cover", item.video.cover)
            .put("duration", item.video.duration)
            .put("total", item.total)
            .put("done", item.done)
            .put("bytes", item.bytes)
            .put("complete", item.status == Status.Done)
            .put("addedAt", item.addedAt)
            .toString()
        scope.launch(saveDispatcher) { runCatching { File(root, "${item.id}.json").writeText(json) } }
    }

    private fun fromJson(o: JSONObject): Item {
        val complete = o.optBoolean("complete")
        return Item(
            video = Video(id = o.getString("id"), title = o.optString("title"), cover = o.optString("cover"), duration = o.optDouble("duration", 0.0)),
            // App 被關掉時還沒下載完的，先當作暫停
            status = if (complete) Status.Done else Status.Paused,
            total = o.optInt("total"),
            done = o.optInt("done"),
            bytes = o.optLong("bytes"),
            addedAt = o.optLong("addedAt"),
        )
    }
}

/** 位元組顯示成 MB／GB */
fun fmtSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    else -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
}
