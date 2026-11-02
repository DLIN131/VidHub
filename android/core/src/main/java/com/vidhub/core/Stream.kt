package com.vidhub.core

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.Inflater

/**
 * 影片是 HLS，但播放清單和每個片段都包在 PNG 圖檔裡：
 * 真正的內容放在一個自訂類型的區塊（類型碼 0x726f5564），第一個位元組是旗標（bit0 = 有 zlib 壓縮）。
 * [UnwrapDataSource] 把內容拆出來，ExoPlayer 看到的就是一般的 HLS。
 */
@OptIn(UnstableApi::class)
object Stream {
    private val PNG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private const val PAYLOAD = 0x726f5564

    /** 拆出 PNG 包裝裡的內容；不是 PNG 就原樣回傳 */
    fun unwrap(buf: ByteArray): ByteArray {
        if (buf.size < 8 || !PNG.indices.all { buf[it] == PNG[it] }) return buf
        var i = 8
        while (i + 8 <= buf.size) {
            val len = int32(buf, i)
            val type = int32(buf, i + 4)
            val d = i + 8
            if (len < 0 || d + len > buf.size) break
            if (type == PAYLOAD) {
                val body = buf.copyOfRange(d + 1, d + len)
                return if (buf[d].toInt() and 1 != 0) inflate(body) else body
            }
            i = d + len + 4
        }
        throw IOException("影片資料格式不認得")
    }

    private fun int32(b: ByteArray, i: Int) =
        (b[i].toInt() and 0xff shl 24) or (b[i + 1].toInt() and 0xff shl 16) or (b[i + 2].toInt() and 0xff shl 8) or (b[i + 3].toInt() and 0xff)

    private fun inflate(data: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(data)
        val out = ByteArrayOutputStream(data.size * 2)
        val buf = ByteArray(64 * 1024)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
            }
        } finally {
            inf.end()
        }
        return out.toByteArray()
    }

    private val upstream by lazy { OkHttpDataSource.Factory(Api.http).setUserAgent(Site.USER_AGENT) }

    fun source(videoId: String): MediaSource =
        HlsMediaSource.Factory(DataSource.Factory { UnwrapDataSource(upstream.createDataSource()) })
            .setAllowChunklessPreparation(true)
            .createMediaSource(MediaItem.Builder().setUri(Api.streamUrl(videoId)).setMimeType(MimeTypes.APPLICATION_M3U8).build())

    /** 電視盒子記憶體小：預設緩衝會吃到上百 MB，壓到 30 秒／32 MB 就夠順了 */
    fun lowMemoryLoadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
        .setTargetBufferBytes(32 * 1024 * 1024)
        .setPrioritizeTimeOverSizeThresholds(false)
        .build()
}

/** 整個檔案讀進來、拆掉 PNG 包裝，再依 DataSpec 要的範圍吐出去 */
@OptIn(UnstableApi::class)
private class UnwrapDataSource(private val upstream: DataSource) : DataSource {
    private var data: ByteArray? = null
    private var pos = 0
    private var end = 0
    private var uri: Uri? = null
    private var headers: Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        // 包裝過的內容沒辦法只抓一段，一律抓整個檔
        val full = dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build()
        val raw = try {
            upstream.open(full)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = upstream.read(buf, 0, buf.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buf, 0, n)
            }
            uri = upstream.uri
            headers = upstream.responseHeaders
            out.toByteArray()
        } finally {
            upstream.close()
        }
        val body = Stream.unwrap(raw)
        val isPlaylist = body.size >= 7 && String(body, 0, 7, Charsets.US_ASCII) == "#EXTM3U"
        // 片段原本標成 image/png，改成 TS，ExoPlayer 才不會拿去猜別的格式
        headers = headers.filterKeys { !it.equals("Content-Type", true) } +
            ("Content-Type" to listOf(if (isPlaylist) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP2T))
        data = body
        pos = dataSpec.position.toInt().coerceIn(0, body.size)
        end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) body.size else (pos + dataSpec.length).toInt().coerceAtMost(body.size)
        return (end - pos).toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val d = data ?: return C.RESULT_END_OF_INPUT
        if (length == 0) return 0
        if (pos >= end) return C.RESULT_END_OF_INPUT
        val n = minOf(length, end - pos)
        System.arraycopy(d, pos, buffer, offset, n)
        pos += n
        return n
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun close() {
        data = null
    }
}
