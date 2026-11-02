package com.vidhub.app

import android.app.Activity
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.vidhub.core.Store
import com.vidhub.core.Stream
import com.vidhub.core.Video
import kotlinx.coroutines.delay

private val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/** 全螢幕播放器；影片來源見 [Stream] */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(route: Route.Player) {
    val activity = LocalContext.current as Activity
    // 劇集看完自動接下一集：在同一個畫面裡換影片
    var video by remember { mutableStateOf(route.video) }
    // 監聽器裡要用當下的影片算，不能用畫面組起來那時候的值
    fun nextOf(v: Video): Video? = route.queue.indexOfFirst { it.id == v.id }.let { i -> if (i >= 0) route.queue.getOrNull(i + 1) else null }
    val next = nextOf(video)
    var error by remember { mutableStateOf<String?>(null) }
    var buffering by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(true) }
    var speed by remember { mutableStateOf(1f) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintSeq by remember { mutableIntStateOf(0) }
    var holding2x by remember { mutableStateOf(false) }

    fun showHint(text: String) {
        hint = text
        hintSeq++
    }
    LaunchedEffect(hintSeq) {
        delay(1200)
        hint = null
    }

    val player = remember {
        ExoPlayer.Builder(activity)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
    }

    fun savePosition() {
        val dur = player.duration
        val pos = player.currentPosition
        if (pos <= 0) return
        // 快看完就當作看完，下次從頭開始
        val done = dur != C.TIME_UNSET && pos > dur - 30_000
        Store.savePosition(video.id, if (done) 0.0 else pos / 1000.0)
    }

    // 換影片（第一次開啟或接下一集）
    LaunchedEffect(video.id) {
        Store.addHistory(video)
        route.series?.let { Store.setLastEpisode(it.id, video.id) }
        error = null
        player.setMediaSource(Stream.source(video.id))
        val start = Store.position(video.id)
        if (start > 5) {
            player.seekTo((start * 1000).toLong())
            showHint("從 ${fmtSec(start)} 繼續播放")
        }
        player.prepare()
        player.playWhenReady = true
    }

    fun seekBy(sec: Int) {
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + sec * 1000L).coerceIn(0, dur))
        showHint(
            when {
                sec >= 60 -> "快轉 1 分鐘"
                sec > 0 -> "快轉 $sec 秒"
                sec <= -60 -> "倒退 1 分鐘"
                else -> "倒退 ${-sec} 秒"
            }
        )
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) error = null
                if (state == Player.STATE_ENDED) {
                    Store.savePosition(video.id, 0.0)
                    nextOf(video)?.let {
                        showHint("接著播放第 ${it.episode ?: ""} 集")
                        video = it
                    }
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                val cause = generateSequence(e.cause) { it.cause }.lastOrNull()?.message
                error = "播放失敗（${e.errorCodeName}）" + (cause?.let { "\n$it" } ?: "")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) savePosition()
            }
        }
        player.addListener(listener)
        onDispose {
            savePosition()
            player.removeListener(listener)
            player.release()
        }
    }

    // 播放中每 5 秒存一次進度，被系統殺掉也不會全部遺失
    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            if (player.isPlaying) savePosition()
        }
    }

    DisposableEffect(Unit) {
        val window = activity.window
        val ctrl = WindowCompat.getInsetsController(window, window.decorView)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.video_player, null) as PlayerView).apply {
                    this.player = player
                    setShowSubtitleButton(false)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v ->
                        controlsVisible = v == android.view.View.VISIBLE
                    })
                    setOnTouchListener(VideoGestures(
                        view = this,
                        onTap = { if (isControllerFullyVisible) hideController() else showController() },
                        onMultiTap = { taps, right ->
                            val sec = if (taps >= 3) 60 else 10
                            seekBy(if (right) sec else -sec)
                        },
                        onHold = { down ->
                            holding2x = down
                            player.setPlaybackSpeed(if (down) 2f else speed)
                        },
                    ))
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && error == null) {
            CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.Center))
        }

        val badge = if (holding2x) "2 倍速播放中 ▶▶" else hint
        if (badge != null) {
            Text(
                badge,
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
                    .background(Color(0x99000000), RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        // 上方列：返回、標題、下一集、倍速，跟著播放器控制列一起顯示／隱藏
        if (controlsVisible || error != null) {
            Row(
                Modifier.fillMaxWidth().background(Color(0x88000000)).statusBarsPadding().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = Nav::pop) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
                }
                val title = route.series?.let { s -> s.title + (video.episode?.let { "　第 $it 集" } ?: "") } ?: video.title
                Text(title, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (next != null) TextButton(onClick = { savePosition(); video = next }) { Text("下一集 ›", color = Color.White, fontSize = 14.sp) }
                Menu(label = if (speed == 1f) "倍速" else "${speed}x", options = speeds.map { "${it}x" }, selected = speeds.indexOf(speed)) {
                    speed = speeds[it]
                    player.setPlaybackSpeed(speed)
                }
            }
        }

        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp).background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(msg, color = Color.White, textAlign = TextAlign.Center, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        error = null
                        player.prepare()
                        player.play()
                    }, colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text("重試") }
                    TextButton(onClick = Nav::pop) { Text("返回", color = Color.White) }
                }
            }
        }
    }
}

private fun fmtSec(sec: Double) = com.vidhub.core.fmtTime(sec)

@Composable
private fun Menu(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(label, color = Color.White, fontSize = 14.sp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, name ->
                DropdownMenuItem(
                    text = { Text(name, color = if (i == selected) Accent else Color.Unspecified) },
                    onClick = {
                        open = false
                        onSelect(i)
                    },
                )
            }
        }
    }
}
