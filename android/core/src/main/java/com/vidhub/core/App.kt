package com.vidhub.core

import android.app.Application
import android.content.pm.PackageManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/** 手機版和電視版共用的 Application：初始化設定、收藏紀錄，以及封面的圖片快取 */
class App : Application(), ImageLoaderFactory {
    companion object {
        /** 電視版（盒子效能和記憶體都比較吃緊） */
        var isTv = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || packageName.endsWith(".tv")
        Site.init(this)
        Store.init(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Api.http }
        // 電視上卡片一次很多張，淡入動畫和 ARGB 圖片都很吃效能／記憶體
        .crossfade(!isTv)
        .allowRgb565(isTv)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(if (isTv) 0.15 else 0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .build()
}
