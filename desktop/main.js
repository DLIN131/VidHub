const { app, BrowserWindow, Menu, dialog, ipcMain, session, shell } = require('electron')
const path = require('path')
const site = require('./site')
const updater = require('./updater')

const APP_NAME = 'VidHub'
// 用一般 Chrome 的 UA，網站對 Electron 字樣的 UA 可能會擋
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
app.userAgentFallback = UA

// 看過的影片片段不存磁碟快取，userData 才不會越看越大（同一次執行裡還是有記憶體快取）
app.commandLine.appendSwitch('disable-http-cache')

let win = null
let settingsWin = null

function home() {
  win.loadURL(site.base() + '/home')
}

function createWindow() {
  const b = site.get('bounds') || {}
  win = new BrowserWindow({
    width: b.width || 1400,
    height: b.height || 900,
    x: b.x,
    y: b.y,
    minWidth: 480,
    minHeight: 400,
    backgroundColor: '#0f0f13',
    title: APP_NAME,
    icon: path.join(__dirname, 'icon.png'),
    show: process.env.VIDHUB_TEST !== '1',
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true },
  })
  if (b.maximized) win.maximize()
  const wc = win.webContents

  // 視窗標題不顯示網站名稱：「影片名稱 - 網站名…」只留前面
  win.on('page-title-updated', (e, title) => {
    e.preventDefault()
    const t = String(title || '').split(' - ')[0].trim()
    win.setTitle(t && !/^https?:/.test(t) ? `${t} - ${APP_NAME}` : APP_NAME)
  })

  wc.on('dom-ready', () => wc.executeJavaScript(site.injectJs).catch(() => {}))

  // 新分頁一律不開：站內網址就在目前視窗開，站外的（彈出式廣告）直接擋掉
  wc.setWindowOpenHandler(({ url }) => {
    try {
      if (site.isSite(new URL(url).hostname)) wc.loadURL(url)
    } catch {}
    return { action: 'deny' }
  })

  // 站外連結（地址發布頁、社群）先問過再用系統瀏覽器開，避免廣告自己跳轉出去
  wc.on('will-navigate', (e, url) => {
    let u
    try {
      u = new URL(url)
    } catch {
      return
    }
    if (site.isSite(u.hostname)) return
    e.preventDefault()
    if (!/^https?:$/.test(u.protocol)) return
    const r = dialog.showMessageBoxSync(win, { type: 'question', title: APP_NAME, message: '要用瀏覽器開啟這個站外網址嗎？', detail: url, buttons: ['開啟', '取消'], defaultId: 1, cancelId: 1 })
    if (r === 0) shell.openExternal(url)
  })
  wc.on('will-redirect', (e, url) => {
    try {
      if (!site.isSite(new URL(url).hostname)) e.preventDefault()
    } catch {}
  })

  wc.on('did-fail-load', (_e, code, desc, url, isMainFrame) => {
    // -3 = 被中斷（換頁、擋掉的請求），不算失敗
    if (!isMainFrame || code === -3) return
    const r = dialog.showMessageBoxSync(win, {
      type: 'warning',
      title: APP_NAME,
      message: '無法連線到網站',
      detail: `${desc}（${code}）\n\n網站可能換了網址，可以到「設定 → 網址設定」改成新網址。`,
      buttons: ['重試', '網址設定', '關閉'],
      defaultId: 0,
      cancelId: 2,
    })
    if (r === 0) wc.loadURL(url)
    else if (r === 1) openSettings()
  })

  // 滑鼠側鍵上一頁 / 下一頁
  win.on('app-command', (_e, cmd) => {
    if (cmd === 'browser-backward' && wc.canGoBack()) wc.goBack()
    if (cmd === 'browser-forward' && wc.canGoForward()) wc.goForward()
  })

  win.on('close', () => {
    const maximized = win.isMaximized()
    site.set('bounds', { ...(maximized ? site.get('bounds') : win.getNormalBounds()), maximized })
  })

  home()
}

function buildMenu() {
  const wc = () => win.webContents
  const template = [
    {
      label: '導覽',
      submenu: [
        { label: '首頁', accelerator: 'Alt+Home', click: home },
        { label: '上一頁', accelerator: 'Alt+Left', click: () => wc().canGoBack() && wc().goBack() },
        { label: '下一頁', accelerator: 'Alt+Right', click: () => wc().canGoForward() && wc().goForward() },
        { type: 'separator' },
        { label: '重新整理', accelerator: 'F5', click: () => wc().reload() },
        { label: '強制重新整理', accelerator: 'Ctrl+F5', click: () => wc().reloadIgnoringCache() },
        { type: 'separator' },
        { label: '結束', accelerator: 'Alt+F4', role: 'quit' },
      ],
    },
    {
      label: '檢視',
      submenu: [
        { label: '全螢幕', accelerator: 'F11', click: () => win.setFullScreen(!win.isFullScreen()) },
        { type: 'separator' },
        { label: '放大', accelerator: 'Ctrl+=', click: () => wc().setZoomLevel(wc().getZoomLevel() + 0.5) },
        { label: '縮小', accelerator: 'Ctrl+-', click: () => wc().setZoomLevel(wc().getZoomLevel() - 0.5) },
        { label: '原始大小', accelerator: 'Ctrl+0', click: () => wc().setZoomLevel(0) },
        { type: 'separator' },
        { label: '開發人員工具', accelerator: 'F12', click: () => wc().toggleDevTools() },
      ],
    },
    {
      label: '設定',
      submenu: [
        { label: '網址設定…', click: openSettings },
        { label: '檢查更新', click: () => checkUpdate(true) },
        { type: 'separator' },
        {
          label: '關於',
          click: () =>
            dialog.showMessageBox(win, { type: 'info', title: APP_NAME, message: `${APP_NAME} v${app.getVersion()}`, detail: '已內建廣告攔截。\n網站換網址時，到「設定 → 網址設定」修改即可。' }),
        },
      ],
    },
  ]
  Menu.setApplicationMenu(Menu.buildFromTemplate(template))
}

// ---------- 網址設定 ----------
function openSettings() {
  if (settingsWin && !settingsWin.isDestroyed()) return settingsWin.focus()
  settingsWin = new BrowserWindow({
    parent: win,
    modal: true,
    width: 480,
    height: 260,
    resizable: false,
    minimizable: false,
    maximizable: false,
    title: '網址設定',
    backgroundColor: '#1a1a20',
    webPreferences: { preload: path.join(__dirname, 'settings-preload.js'), contextIsolation: true, sandbox: false },
  })
  settingsWin.removeMenu()
  settingsWin.loadFile(path.join(__dirname, 'settings.html'))
}

function setupIpc() {
  ipcMain.handle('settings:get', () => ({ base: site.base(), isDefault: !site.get('base') }))
  ipcMain.handle('settings:save', (_e, input) => {
    const base = input ? site.normalizeBase(input) : ''
    site.set('base', base === site.DEFAULT_BASE ? '' : base)
    if (settingsWin && !settingsWin.isDestroyed()) settingsWin.close()
    home()
    return site.base()
  })
  ipcMain.handle('settings:close', () => settingsWin && !settingsWin.isDestroyed() && settingsWin.close())
}

// ---------- 程式內更新 ----------
let updating = false

async function checkUpdate(manual = false) {
  if (updating) return
  let r
  try {
    r = await updater.latest()
  } catch (e) {
    if (manual) dialog.showMessageBox(win, { type: 'error', title: APP_NAME, message: '檢查更新失敗', detail: String(e.message || e) })
    return
  }
  const now = updater.currentCode()
  if (!r || r.code <= now) {
    if (manual) dialog.showMessageBox(win, { type: 'info', title: APP_NAME, message: '已經是最新版本', detail: `目前版本 v${app.getVersion()}` })
    return
  }
  if (!manual && site.get('skipVersion') === r.code) return
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
  if (choice !== 0) return
  if (!updater.supported()) return shell.openExternal(r.url)

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
    if (!win.isDestroyed()) win.setProgressBar(-1)
    dialog.showMessageBox(win, { type: 'error', title: APP_NAME, message: '更新失敗', detail: String(e.message || e) })
  }
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
    // 廣告攔截：主程序的請求（檢查更新）也會經過這裡，GitHub 在規則的白名單裡
    session.defaultSession.webRequest.onBeforeRequest({ urls: ['http://*/*', 'https://*/*'] }, (details, cb) => {
      cb({ cancel: site.shouldBlock(details.url) })
    })
    setupIpc()
    buildMenu()
    createWindow()
    updater.cleanup()
    if (process.env.VIDHUB_TEST !== '1') setTimeout(() => checkUpdate(false), 5000)
  })

  app.on('window-all-closed', () => app.quit())
}

module.exports = { get win() { return win } }
