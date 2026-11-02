const { app, BrowserWindow, Menu, dialog, ipcMain, shell } = require('electron')
const path = require('path')
const site = require('./site')
const source = require('./source')
const state = require('./state')
const updater = require('./updater')

const APP_NAME = 'VidHub'
app.userAgentFallback = source.UA

// 看過的縮圖和影片片段不存磁碟快取，userData 才不會越看越大（同一次執行裡還是有記憶體快取）
app.commandLine.appendSwitch('disable-http-cache')

// 播放用的 vh:// 代理要在 app ready 之前登記
source.registerScheme()

let win = null

function createWindow() {
  const b = site.get('bounds') || {}
  win = new BrowserWindow({
    width: b.width || 1400,
    height: b.height || 900,
    x: b.x,
    y: b.y,
    minWidth: 900,
    minHeight: 600,
    backgroundColor: '#0f0f13',
    title: APP_NAME,
    icon: path.join(__dirname, 'icon.png'),
    autoHideMenuBar: true,
    show: process.env.VIDHUB_TEST !== '1',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false },
  })
  win.removeMenu()
  if (b.maximized) win.maximize()
  win.loadFile(path.join(__dirname, 'src', 'index.html'))

  const wc = win.webContents
  // 介面裡沒有要開新視窗的地方；萬一有就用系統瀏覽器
  wc.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url)
    return { action: 'deny' }
  })
  wc.on('will-navigate', (e) => e.preventDefault())
  wc.on('before-input-event', (e, input) => {
    if (input.type !== 'keyDown') return
    if (input.key === 'F12') wc.toggleDevTools()
    else if (input.key === 'F11') win.setFullScreen(!win.isFullScreen())
    else if (input.key === 'F5') wc.reload()
    else if (input.control && (input.key === '=' || input.key === '+')) wc.setZoomLevel(wc.getZoomLevel() + 0.5)
    else if (input.control && input.key === '-') wc.setZoomLevel(wc.getZoomLevel() - 0.5)
    else if (input.control && input.key === '0') wc.setZoomLevel(0)
    else return
    e.preventDefault()
  })
  // 滑鼠側鍵：交給介面處理「上一步」
  win.on('app-command', (_e, cmd) => {
    if (cmd === 'browser-backward') wc.send('nav:back')
  })

  win.on('close', () => {
    const maximized = win.isMaximized()
    site.set('bounds', { ...(maximized ? site.get('bounds') : win.getNormalBounds()), maximized })
  })
}

function setupIpc() {
  ipcMain.handle('site:page', (_e, p) => source.page(String(p)))
  ipcMain.handle('site:base', () => ({ base: site.base(), isDefault: !site.get('base') }))
  ipcMain.handle('site:setBase', (_e, input) => {
    const b = input ? site.normalizeBase(input) : ''
    site.set('base', b === site.DEFAULT_BASE ? '' : b)
    return site.base()
  })
  ipcMain.handle('shell:external', (_e, url) => /^https?:/.test(url) && shell.openExternal(url))
  ipcMain.handle('win:fullscreen', (_e, on) => {
    if (win && !win.isDestroyed()) win.setFullScreen(on === undefined ? !win.isFullScreen() : !!on)
    return win?.isFullScreen() || false
  })
  ipcMain.handle('app:version', () => app.getVersion())
  ipcMain.handle('update:check', () => checkUpdate(true))
}

// ---------- 程式內更新 ----------
let updating = false

/** 回傳 'latest' | 'shown' | 'error' | 'busy' */
async function checkUpdate(manual = false) {
  if (updating) return 'busy'
  let r
  try {
    r = await updater.latest()
  } catch (e) {
    if (manual) dialog.showMessageBox(win, { type: 'error', title: APP_NAME, message: '檢查更新失敗', detail: String(e.message || e) })
    return 'error'
  }
  const now = updater.currentCode()
  if (!r || r.code <= now) {
    if (manual) dialog.showMessageBox(win, { type: 'info', title: APP_NAME, message: '已經是最新版本', detail: `目前版本 v${app.getVersion()}` })
    return 'latest'
  }
  if (!manual && site.get('skipVersion') === r.code) return 'latest'
  const choice = dialog.showMessageBoxSync(win, {
    type: 'info',
    title: APP_NAME,
    message: `發現新版本 v1.0.${r.code}（目前 v${app.getVersion()}）`,
    detail: r.notes ? `更新內容：\n${r.notes}` : '',
    buttons: updater.supported() ? ['立即更新', '稍後再說', '略過這個版本'] : ['開啟下載頁面', '稍後再說', '略過這個版本'],
    defaultId: 0,
    cancelId: 1,
  })
  if (choice === 2) site.set('skipVersion', r.code)
  if (choice !== 0) return 'shown'
  if (!updater.supported()) {
    shell.openExternal(r.url)
    return 'shown'
  }

  updating = true
  try {
    await updater.prepare(r.url, (p) => {
      if (win.isDestroyed()) return
      win.setProgressBar(p >= 0 ? p / 100 : 2)
      win.setTitle(`${APP_NAME} - 下載更新 ${p >= 0 ? p + '%' : '…'}`)
    })
    win.setProgressBar(-1)
    dialog.showMessageBoxSync(win, { type: 'info', title: APP_NAME, message: '更新已下載完成', detail: '按「確定」後程式會關閉並安裝新版，完成後自動重新開啟。', buttons: ['確定'] })
    updater.install()
  } catch (e) {
    updating = false
    if (!win.isDestroyed()) {
      win.setProgressBar(-1)
      win.setTitle(APP_NAME)
    }
    dialog.showMessageBox(win, { type: 'error', title: APP_NAME, message: '更新失敗', detail: String(e.message || e) })
  }
  return 'shown'
}

// 同時只開一份：再開一次就把原本的視窗叫到前面
if (!app.requestSingleInstanceLock()) {
  app.quit()
} else {
  app.on('second-instance', () => {
    if (!win || win.isDestroyed()) return
    if (win.isMinimized()) win.restore()
    win.focus()
  })

  app.whenReady().then(() => {
    Menu.setApplicationMenu(null)
    state.setup()
    source.setupProtocol()
    setupIpc()
    createWindow()
    updater.cleanup()
    if (process.env.VIDHUB_TEST !== '1') setTimeout(() => checkUpdate(false), 5000)
  })

  app.on('window-all-closed', () => app.quit())
}

module.exports = { get win() { return win } }
