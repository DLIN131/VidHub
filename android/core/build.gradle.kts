plugins {
    id("com.android.library")
}

// 手機版和電視版共用：網頁外殼、廣告攔截、App 內更新
android {
    namespace = "com.vidhub.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    // 廣告規則和注入腳本跟電腦版共用（專案根目錄的 shared/）
    sourceSets["main"].assets.directories.add("../../shared")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        disable += setOf("SetJavaScriptEnabled")
    }
}

dependencies {
    api("androidx.core:core-ktx:1.16.0")
    api("androidx.activity:activity-ktx:1.10.1")
    api("androidx.webkit:webkit:1.14.0")
}
