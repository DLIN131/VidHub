# VidHub

把影片網站包成獨立 App 的外殼，三個版本共用同一套廣告攔截規則：

| 版本 | 檔案 | 說明 |
| --- | --- | --- |
| 手機版（Android） | `VidHub.apk` | 觸控操作，影片可全螢幕 |
| 電視版（Android TV） | `VidHubTV.apk` | 遙控器方向鍵移動焦點、確認鍵開啟；全螢幕時左右鍵快轉 10 秒、上下鍵 1 分鐘 |
| 電腦版（Windows） | `VidHub-Windows.zip` | 解壓後執行 `VidHub.exe` |

## 下載

永遠是最新版：

- 手機版：https://github.com/DLIN131/VidHub/releases/latest/download/VidHub.apk
- 電視版：https://github.com/DLIN131/VidHub/releases/latest/download/VidHubTV.apk
- 電腦版：https://github.com/DLIN131/VidHub/releases/latest/download/VidHub-Windows.zip

## 功能

- 內建廣告攔截：站外請求只放行影片片段、縮圖與字型，彈出視窗和網頁自動跳轉一律擋掉
- 程式內更新：啟動時自動檢查，也可以從選單手動檢查
  - Android：下載新版 APK 後交給系統安裝
  - Windows：下載新版後自動關閉、覆蓋安裝並重新開啟
- 網址設定：網站換網址時，可在選單「網址設定」改成新網址
- Android：在第一頁按返回（電視版按選單鍵）叫出選單

## 專案結構

```
shared/    廣告攔截規則 rules.json、注入網頁的腳本 inject.js（三個版本共用）
android/   core（WebView 外殼、攔截、更新）、phone、tv
desktop/   Electron（Windows）
```

## 建置

```bash
# Android
cd android && ./gradlew :phone:assembleRelease :tv:assembleRelease

# Windows
cd desktop && npm ci && node pack.js dist
```

推送到 `main` 會由 GitHub Actions 建置三個版本並發布 Release（版本號 `v1.0.<建置編號>`），各版本的程式內更新都從最新 Release 取得。
