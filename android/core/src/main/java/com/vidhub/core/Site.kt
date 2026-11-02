package com.vidhub.core

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64

/** 網站網址（可在設定裡的「網址設定」改） */
object Site {
    // 預設網址只用 Base64 存
    val DEFAULT_BASE: String by lazy { String(Base64.decode("aHR0cHM6Ly9yb3V2YjEueHl6", Base64.DEFAULT)) }

    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private lateinit var prefs: SharedPreferences

    /** 單元測試用：不經過 SharedPreferences 直接指定網址 */
    internal var testBase: String? = null

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("vidhub", Context.MODE_PRIVATE)
    }

    val prefsStore: SharedPreferences get() = prefs

    var base: String
        get() = testBase ?: prefs.getString("base", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE
        set(value) {
            prefs.edit().apply { if (value.isBlank() || value == DEFAULT_BASE) remove("base") else putString("base", value) }.apply()
        }

    val isDefaultBase: Boolean get() = base == DEFAULT_BASE

    /** 使用者輸入的網址整理成 https://host，格式不對回傳 null；空字串代表還原預設 */
    fun normalize(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        val u = Uri.parse(s)
        val host = u.host?.takeIf { it.contains('.') } ?: return null
        return "${u.scheme}://$host" + if (u.port > 0) ":${u.port}" else ""
    }
}
