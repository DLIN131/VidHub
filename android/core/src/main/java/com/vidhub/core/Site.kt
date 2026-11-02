package com.vidhub.core

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import org.json.JSONObject

/** 網站網址（可在選單「網址設定」改）與廣告攔截規則，規則檔和電腦版共用 */
object Site {
    // 預設網址只用 Base64 存
    val DEFAULT_BASE: String = String(Base64.decode("aHR0cHM6Ly9yb3V2YjEueHl6", Base64.DEFAULT))

    private lateinit var prefs: SharedPreferences
    private lateinit var allowHosts: Set<String>
    private lateinit var allowHostSuffixes: List<String>
    private lateinit var allowPathPrefixes: List<String>
    private lateinit var blockSitePaths: List<String>
    lateinit var injectJs: String
        private set

    @Volatile
    private var siteHost = ""

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        val app = context.applicationContext
        prefs = app.getSharedPreferences("vidhub", Context.MODE_PRIVATE)
        val rules = JSONObject(app.assets.open("rules.json").bufferedReader().use { it.readText() })
        fun list(key: String) = rules.optJSONArray(key)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        allowHosts = list("allowHosts").toSet()
        allowHostSuffixes = list("allowHostSuffixes")
        allowPathPrefixes = list("allowPathPrefixes")
        blockSitePaths = list("blockSitePaths")
        injectJs = app.assets.open("inject.js").bufferedReader().use { it.readText() }
        siteHost = Uri.parse(base).host.orEmpty()
    }

    val prefsStore: SharedPreferences get() = prefs

    var base: String
        get() = prefs.getString("base", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE
        set(value) {
            prefs.edit().apply { if (value.isBlank() || value == DEFAULT_BASE) remove("base") else putString("base", value) }.apply()
            siteHost = Uri.parse(base).host.orEmpty()
        }

    val home: String get() = "$base/home"

    /** 使用者輸入的網址整理成 https://host，格式不對回傳 null */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        val u = Uri.parse(s)
        val host = u.host?.takeIf { it.contains('.') } ?: return null
        return "${u.scheme}://$host" + if (u.port > 0) ":${u.port}" else ""
    }

    fun isSite(host: String?): Boolean {
        if (host == null) return false
        val h = siteHost
        return host == h || host.endsWith(".$h")
    }

    /** 站外的請求只放行規則裡允許的（影片片段、縮圖、字型…），其餘都是廣告和追蹤 */
    fun shouldBlock(url: Uri): Boolean {
        val scheme = url.scheme
        if (scheme != "http" && scheme != "https") return false
        val host = url.host ?: return false
        val path = url.path ?: "/"
        if (isSite(host)) return blockSitePaths.any { path.startsWith(it) }
        if (host in allowHosts) return false
        if (allowHostSuffixes.any { host == it || host.endsWith(".$it") }) return false
        return allowPathPrefixes.none { path.startsWith(it) }
    }
}
