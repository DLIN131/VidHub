package com.vidhub.core

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** 更新視窗：顯示更新內容 → 下載（進度條）→ 交給系統安裝 */
object UpdateDialog {
    private val main = Handler(Looper.getMainLooper())
    private var checkedThisRun = false

    /** 啟動時自動檢查一次；使用者略過的版本不再提醒 */
    fun checkOnStart(activity: Activity) {
        if (checkedThisRun) return
        checkedThisRun = true
        thread {
            val r = runCatching { Updater.check(activity) }.getOrNull() ?: return@thread
            if (Site.prefsStore.getInt("skipVersion", 0) == r.versionCode) return@thread
            main.postDelayed({ if (!activity.isFinishing) show(activity, r) }, 1500)
        }
    }

    fun checkManually(activity: Activity) {
        Toast.makeText(activity, "檢查更新中…", Toast.LENGTH_SHORT).show()
        thread {
            val result = runCatching { Updater.check(activity) }
            main.post {
                if (activity.isFinishing) return@post
                result.onSuccess { r ->
                    if (r == null) Toast.makeText(activity, "已經是最新版本（${Updater.currentVersionName(activity)}）", Toast.LENGTH_LONG).show()
                    else show(activity, r)
                }.onFailure { Toast.makeText(activity, "檢查更新失敗：${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    fun show(activity: Activity, release: Updater.Release) {
        val d = activity.resources.displayMetrics.density
        val pad = (20 * d).toInt()
        val notes = TextView(activity).apply {
            text = buildString {
                append("${Updater.currentVersionName(activity)}  →  v1.0.${release.versionCode}")
                if (release.notes.isNotBlank()) append("\n\n更新內容：\n").append(release.notes)
            }
            textSize = 15f
            setLineSpacing(0f, 1.2f)
        }
        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        val status = TextView(activity).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            visibility = View.GONE
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(notes)
            addView(bar)
            addView(status)
        }
        val scroll = ScrollView(activity).apply { addView(box) }

        var file: File? = null
        var downloading = false
        val canceled = AtomicBoolean(false)

        fun say(text: String) {
            status.text = text
            status.visibility = View.VISIBLE
        }

        fun tryInstall(apk: File) {
            if (Updater.canInstall(activity)) {
                Updater.install(activity, apk)
            } else {
                say("請在接下來的設定畫面允許本 App「安裝不明應用程式」，返回後再按「安裝」。")
                if (!Updater.openInstallPermissionSettings(activity)) say("找不到設定頁面，請到系統設定允許本 App 安裝不明應用程式，再回來按「安裝」。")
            }
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("發現新版本")
            .setView(scroll)
            .setPositiveButton("立即更新", null)
            .setNegativeButton("稍後再說") { _, _ -> canceled.set(true) }
            .setNeutralButton("略過這個版本") { _, _ ->
                canceled.set(true)
                Site.prefsStore.edit().putInt("skipVersion", release.versionCode).apply()
            }
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.requestFocus()
            // 自己處理點擊，下載中不關閉視窗
            ok.setOnClickListener {
                file?.let { tryInstall(it); return@setOnClickListener }
                if (downloading) return@setOnClickListener
                downloading = true
                ok.text = "下載中…"
                bar.visibility = View.VISIBLE
                bar.progress = 0
                say("下載中 0%")
                thread {
                    val result = runCatching { Updater.download(activity, release, { p -> main.post { bar.progress = p; say("下載中 $p%") } }, { canceled.get() }) }
                    main.post {
                        downloading = false
                        if (canceled.get() || activity.isFinishing) return@post
                        result.onSuccess { apk ->
                            if (apk == null) return@onSuccess
                            file = apk
                            ok.text = "安裝"
                            say("下載完成")
                            tryInstall(apk)
                        }.onFailure {
                            ok.text = "重試"
                            bar.visibility = View.GONE
                            say("下載失敗：${it.message}")
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
