package com.vidhub.core

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * App 內更新：讀 GitHub 最新 Release，tag 為 v1.0.<versionCode>（CI 的 run_number）。
 * 手機版、電視版的安裝檔都在同一個 Release 裡，依套件名稱挑自己的。
 */
object Updater {
    private const val LATEST_API = "https://api.github.com/repos/DLIN131/VidHub/releases/latest"

    data class Release(val versionCode: Int, val name: String, val notes: String, val apkUrl: String)

    fun apkName(context: Context) = if (context.packageName.endsWith(".tv")) "VidHubTV.apk" else "VidHub.apk"

    @Suppress("DEPRECATION")
    fun currentVersionCode(context: Context): Int {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }

    fun currentVersionName(context: Context): String =
        "v" + context.packageManager.getPackageInfo(context.packageName, 0).versionName

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    /** 在背景執行緒呼叫：有比目前新的版本才回傳 */
    fun check(context: Context): Release? {
        val c = open(LATEST_API)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val tag = o.optString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return null
            if (code <= currentVersionCode(context)) return null
            val assets = o.optJSONArray("assets") ?: return null
            val name = apkName(context)
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name") == name) {
                    return Release(code, o.optString("name").ifEmpty { tag }, cleanNotes(o.optString("body")), a.optString("browser_download_url"))
                }
            }
            return null
        } finally {
            c.disconnect()
        }
    }

    /** 發布說明裡 `---` 以下是固定的下載資訊，Co-Authored-By 也不用給使用者看 */
    private fun cleanNotes(body: String) = body
        .substringBefore("\n---")
        .lines()
        .filterNot { it.startsWith("Co-Authored-By") }
        .joinToString("\n")
        .trim()

    /** 在背景執行緒呼叫；onProgress 收到 0~100，canceled() 回傳 true 就停止 */
    fun download(context: Context, release: Release, onProgress: (Int) -> Unit, canceled: () -> Boolean): File? {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "VidHub-${release.versionCode}.apk")
        // GitHub 會轉址到另一台主機下載，跨主機的轉址要自己跟
        var url = release.apkUrl
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = open(url).apply { instanceFollowRedirects = false; setRequestProperty("Accept", "*/*") }
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) {
                url = URL(URL(url), c.getHeaderField("Location")).toString()
                c.disconnect()
                continue
            }
            if (code != 200) throw IOException("HTTP $code")
            break
        }
        try {
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var last = -1
                    while (true) {
                        if (canceled()) return null
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val p = (read * 100 / total).toInt()
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
            if (total > 0 && file.length() != total) throw IOException("下載不完整，請再試一次")
        } finally {
            c.disconnect()
        }
        return file
    }

    /** Android 8 以上要使用者允許本 App「安裝不明應用程式」 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
