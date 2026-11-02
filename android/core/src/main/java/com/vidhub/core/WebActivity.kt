package com.vidhub.core

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/**
 * 手機版和電視版共用的主畫面：一個全螢幕的 WebView，加上廣告攔截、影片全螢幕、選單和 App 內更新。
 * 電視版另外會注入遙控器操作（方向鍵移動焦點、播放器快轉）。
 */
abstract class WebActivity : ComponentActivity() {
    /** 電視版：桌面版網頁、方向鍵導覽 */
    protected abstract val isTv: Boolean

    private lateinit var root: FrameLayout
    private lateinit var web: WebView
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var lastBackAt = 0L

    private val script: String get() = (if (isTv) "window.__VH_TV = true;\n" else "") + Site.injectJs

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Site.init(this)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#0f0f13")) }
        web = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#0f0f13"))
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        // Android 15 起一律滿版：網頁要避開狀態列和導覽列，影片全螢幕時才蓋滿
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            if (customView == null) v.setPadding(bars.left, bars.top, bars.right, bars.bottom) else v.setPadding(0, 0, 0, 0)
            insets
        }

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // 網站對「; wv」（WebView）字樣可能有不同處理，拿掉；電視版用桌面瀏覽器的 UA 拿到電腦版排版
            userAgentString = if (isTv) DESKTOP_UA else userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, false)
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, script, setOf("*"))
        }
        web.webViewClient = Client()
        web.webChromeClient = Chrome()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        if (web.url == null) web.loadUrl(Site.home)
        web.requestFocus()

        UpdateDialog.checkOnStart(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        root.removeAllViews()
        web.destroy()
        super.onDestroy()
    }

    // 電視遙控器的「選單」鍵 → 選單；手機在首頁按返回也會出現選單
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (event.action == KeyEvent.ACTION_UP) showMenu()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun back() {
        when {
            customView != null -> customCallback?.onCustomViewHidden() ?: hideCustom()
            web.canGoBack() -> web.goBack()
            else -> {
                // 已經在第一頁：連按兩次返回離開，按一次跳出選單
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000) finish() else {
                    lastBackAt = now
                    showMenu()
                }
            }
        }
    }

    private fun showMenu() {
        val items = arrayOf("首頁", "重新整理", "網址設定", "檢查更新", "關於", "離開")
        AlertDialog.Builder(this)
            .setTitle("VidHub ${Updater.currentVersionName(this)}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> web.loadUrl(Site.home)
                    1 -> web.reload()
                    2 -> showBaseDialog()
                    3 -> UpdateDialog.checkManually(this)
                    4 -> AlertDialog.Builder(this)
                        .setTitle("VidHub ${Updater.currentVersionName(this)}")
                        .setMessage("已內建廣告攔截。\n網站換網址時，到選單的「網址設定」修改即可。\n\n" + if (isTv) "遙控器：方向鍵移動、確認鍵開啟；影片全螢幕時左右鍵快轉 10 秒、上下鍵 1 分鐘；選單鍵叫出這個選單。" else "在第一頁按返回會叫出這個選單，連按兩次離開。")
                        .setPositiveButton("確定", null)
                        .show()
                    5 -> finish()
                }
            }
            .show()
    }

    private fun showBaseDialog() {
        val input = EditText(this).apply {
            setText(Site.base)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            selectAll()
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("網址設定")
            .setMessage("網站換網址時，把新網址填在這裡（例如 https://example.com）。")
            .setView(box)
            .setPositiveButton("儲存") { _, _ ->
                val v = Site.normalize(input.text.toString())
                if (v == null) {
                    Toast.makeText(this, "網址格式不正確", Toast.LENGTH_SHORT).show()
                } else {
                    Site.base = v
                    web.clearHistory()
                    web.loadUrl(Site.home)
                }
            }
            .setNeutralButton("還原預設") { _, _ ->
                Site.base = ""
                web.loadUrl(Site.home)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmExternal(url: Uri) {
        AlertDialog.Builder(this)
            .setTitle("開啟站外網址？")
            .setMessage(url.toString())
            .setPositiveButton("用瀏覽器開啟") { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(this, "找不到可以開啟的瀏覽器", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (!Site.shouldBlock(request.url)) return null
            return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (!request.isForMainFrame) return false
            if (Site.isSite(url.host)) return false
            // 站外：使用者自己點的連結才問要不要用瀏覽器開，網頁自己跳轉的（廣告）直接擋掉
            if ((url.scheme == "http" || url.scheme == "https") && request.hasGesture()) confirmExternal(url)
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(script, null)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            // 舊版 WebView 不支援 document start 腳本時的備案；腳本自己會避免重複執行
            view.evaluateJavascript(script, null)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || isFinishing) return
            AlertDialog.Builder(this@WebActivity)
                .setTitle("無法連線到網站")
                .setMessage("${error.description}\n\n網站可能換了網址，可以到選單的「網址設定」改成新網址。")
                .setPositiveButton("重試") { _, _ -> view.reload() }
                .setNeutralButton("網址設定") { _, _ -> showBaseDialog() }
                .setNegativeButton("關閉", null)
                .show()
        }
    }

    private inner class Chrome : WebChromeClient() {
        // 影片全螢幕
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customCallback = callback
            view.keepScreenOn = true
            root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            web.visibility = View.GONE
            immersive(true)
            view.requestFocus()
        }

        override fun onHideCustomView() = hideCustom()

        // 新視窗（target=_blank、window.open）：站內網址在目前畫面開，站外的不理
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val probe = WebView(this@WebActivity)
            probe.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    open(request.url)
                    return true
                }

                override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                    url?.let { open(Uri.parse(it)) }
                    v.stopLoading()
                }

                private fun open(url: Uri) {
                    if (Site.isSite(url.host)) web.loadUrl(url.toString())
                    else if (isUserGesture && (url.scheme == "http" || url.scheme == "https")) confirmExternal(url)
                    probe.post { probe.destroy() }
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = probe
            resultMsg.sendToTarget()
            return true
        }
    }

    private fun hideCustom() {
        val v = customView ?: return
        root.removeView(v)
        customView = null
        customCallback = null
        web.visibility = View.VISIBLE
        immersive(false)
        web.requestFocus()
    }

    private fun immersive(on: Boolean) {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        ViewCompat.requestApplyInsets(root)
    }

    companion object {
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
