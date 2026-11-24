package com.vidhub.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.vidhub.core.Downloads
import com.vidhub.core.Video
import com.vidhub.core.fmtSize

/** 影片頁的下載按鈕：沒下載 → 下載；下載中 → 進度（點了暫停）；暫停／失敗 → 繼續；完成 → 已下載 */
@Composable
fun DownloadButton(video: Video, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val item = Downloads.find(video.id)
    // Android 13 起要允許通知，才看得到通知列的下載進度（不允許也照樣下載）
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askNotify() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val text = when (item?.status) {
        null -> "⬇  下載"
        Downloads.Status.Done -> "✓  已下載（離線也能播）"
        Downloads.Status.Running -> "下載中 ${(item.progress * 100).toInt()}%・點一下暫停"
        Downloads.Status.Queued -> "排隊中…"
        Downloads.Status.Paused -> "已暫停 ${(item.progress * 100).toInt()}%・繼續下載"
        Downloads.Status.Failed -> "下載失敗・重試"
    }
    OutlinedButton(
        onClick = {
            when (item?.status) {
                null -> {
                    askNotify()
                    Downloads.start(video)
                }
                Downloads.Status.Running, Downloads.Status.Queued -> Downloads.pause(video.id)
                Downloads.Status.Paused, Downloads.Status.Failed -> {
                    askNotify()
                    Downloads.resume(video.id)
                }
                Downloads.Status.Done -> MainState.libraryTab = 2
            }
        },
        modifier = modifier,
    ) { Text(text, fontSize = 15.sp) }
}

/** 「我的 → 下載」：所有下載，點一下播放（完成的離線播） */
@Composable
fun DownloadList() {
    var confirmDelete by remember { mutableStateOf<Downloads.Item?>(null) }
    if (Downloads.items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("還沒有下載，在影片頁按「下載」加入", color = Muted)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "共 ${Downloads.items.size} 部・${fmtSize(Downloads.items.sumOf { it.bytes })}・剩餘空間 ${fmtSize(Downloads.freeSpace())}",
                color = Muted,
                fontSize = 13.sp,
            )
        }
        items(Downloads.items, key = { it.id }) { item -> DownloadRow(item) { confirmDelete = item } }
    }
    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("刪除下載？") },
            text = { Text(item.video.title) },
            confirmButton = {
                TextButton(onClick = {
                    Downloads.delete(item.id)
                    confirmDelete = null
                }) { Text("刪除", color = Accent) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun DownloadRow(item: Downloads.Item, onDelete: () -> Unit) {
    val done = item.status == Downloads.Status.Done
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Surface)
            .clickable { if (done) Nav.push(Route.Player(item.video)) else Nav.push(Route.VideoPage(item.video)) }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(120.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(item.video.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(
                when (item.status) {
                    Downloads.Status.Done -> "${fmtSize(item.bytes)}・點一下播放"
                    Downloads.Status.Running -> "下載中 ${(item.progress * 100).toInt()}%・${fmtSize(item.bytes)}"
                    Downloads.Status.Queued -> "排隊中"
                    Downloads.Status.Paused -> "已暫停 ${(item.progress * 100).toInt()}%"
                    Downloads.Status.Failed -> "失敗：${item.error ?: "未知錯誤"}"
                },
                color = if (item.status == Downloads.Status.Failed) Accent else Muted,
                fontSize = 12.sp,
                maxLines = 2,
            )
            if (!done) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { item.progress }, color = Accent, modifier = Modifier.fillMaxWidth())
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            when (item.status) {
                Downloads.Status.Running, Downloads.Status.Queued -> TextButton(onClick = { Downloads.pause(item.id) }) { Text("暫停") }
                Downloads.Status.Paused, Downloads.Status.Failed -> TextButton(onClick = { Downloads.resume(item.id) }) { Text("繼續") }
                Downloads.Status.Done -> {}
            }
            TextButton(onClick = onDelete) { Text(if (done) "刪除" else "取消", color = Muted) }
        }
    }
}
