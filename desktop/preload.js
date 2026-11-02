// 網頁一開始載入就注入清廣告的腳本（在網頁自己的腳本之前）。
// 不能等 dom-ready 再用 executeJavaScript：Electron 會把它排到整頁載入完才執行，
// 圖片多的時候要等好幾十秒，這段時間廣告和彈出視窗都會先跑出來
const { ipcRenderer, webFrame } = require('electron')

const code = ipcRenderer.sendSync('inject:get')
if (code) webFrame.executeJavaScript(code).catch(() => {})
