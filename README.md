# VidHub

影片網站的用戶端，有手機版、電視版和 Windows 電腦版。用自己的介面瀏覽和播放，不開網站本身，所以網站上的廣告、彈出視窗和追蹤程式完全不會載入。

| 版本 | 檔案 | 說明 |
| --- | --- | --- |
| 手機版（Android） | `VidHub.apk` | Jetpack Compose + Media3 ExoPlayer |
| 電視版（Android TV） | `VidHubTV.apk` | 遙控器操作，針對電視盒子最佳化 |
| 電腦版（Windows） | `VidHub-Windows.zip` | Electron + hls.js，解壓後執行 `VidHub.exe` |

## 下載

永遠是最新版：

- 手機版：https://github.com/DLIN131/VidHub/releases/latest/download/VidHub.apk
- 電視版：https://github.com/DLIN131/VidHub/releases/latest/download/VidHubTV.apk
- 電腦版：https://github.com/DLIN131/VidHub/releases/latest/download/VidHub-Windows.zip

## 功能

- **首頁**：最新影片、熱門劇集、本週更新、各分類的今日熱門與最受歡迎
- **影片／劇集／分類**：排序、狀態與類型篩選，捲到底自動載入下一頁
- **搜尋**：影片和劇集，可依分類篩選、排序，保留搜尋紀錄
- **影片頁**：資訊、標籤、相關影片；屬於劇集時列出選集
- **劇集頁**：簡介、選集，記住上次看到第幾集
- **播放器**：自動記住播放進度、劇集看完自動接下一集、倍速
  - 手機：點一下顯示控制列，左右兩側連點兩下倒退／快轉 10 秒、三下 1 分鐘，按住 2 倍速
  - 電視：左右鍵倒退／快轉（按住加速），OK 暫停，按住 OK 2 倍速，上鍵下一集
  - 電腦：空白鍵暫停、← → 10 秒（Shift 1 分鐘）、↑ ↓ 音量、F 全螢幕、N 下一集
- **收藏、觀看紀錄**：存在本機，不需要登入
- **網址設定**：網站換網址時，在設定裡改成新網址
- **程式內更新**：開啟時檢查新版本，可選「立即更新」「稍後再說」或「略過這個版本」

## 運作方式

- 網站的頁面資料內嵌在 HTML 裡（一段框架產生的 JS），程式不執行它，而是自己解析資料格式（`desktop/tsr.js`、`android/core/.../Tsr.kt`）
- 影片是 HLS，播放清單和片段包在 PNG 檔裡；播放前把真正的內容拆出來（電腦版走 `vh://` 代理，Android 是自訂的 DataSource）

## 專案結構

```
android/   core（資料、播放來源、收藏紀錄、更新）、phone、tv
desktop/   Electron（Windows）；src/ 是介面
```

## 建置

```bash
# Android
cd android && ./gradlew :phone:assembleRelease :tv:assembleRelease

# Windows
cd desktop && npm ci && node pack.js dist
```

推送到 `main` 會由 GitHub Actions 建置三個版本並發布 Release（版本號 `v1.0.<建置編號>`），各版本的程式內更新都從最新 Release 取得。
