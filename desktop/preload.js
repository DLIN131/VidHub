// 介面不能直接碰網路和檔案，透過這裡呼叫主程序
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('hub', {
  // 收藏、觀看紀錄、播放進度存在主程序的 state.json
  state: {
    all: () => ipcRenderer.sendSync('state:all'),
    set: (key, value) => ipcRenderer.send('state:set', key, value),
  },
  /** 抓網站某一頁的資料，例如 page('/v?order=viewCount&page=2') */
  page: (p) => ipcRenderer.invoke('site:page', p),
  base: () => ipcRenderer.invoke('site:base'),
  setBase: (url) => ipcRenderer.invoke('site:setBase', url),
  external: (url) => ipcRenderer.invoke('shell:external', url),
  fullscreen: (on) => ipcRenderer.invoke('win:fullscreen', on),
  version: () => ipcRenderer.invoke('app:version'),
  checkUpdate: () => ipcRenderer.invoke('update:check'),
  onBack: (fn) => ipcRenderer.on('nav:back', () => fn()),
  // 下載（見 downloads.js）
  dl: {
    list: () => ipcRenderer.invoke('dl:list'),
    add: (v) => ipcRenderer.invoke('dl:add', v),
    pause: (id) => ipcRenderer.invoke('dl:pause', id),
    resume: (id) => ipcRenderer.invoke('dl:resume', id),
    remove: (id, deleteFile) => ipcRenderer.invoke('dl:remove', id, deleteFile),
    show: (id) => ipcRenderer.invoke('dl:show', id),
    dir: () => ipcRenderer.invoke('dl:dir'),
    chooseDir: () => ipcRenderer.invoke('dl:chooseDir'),
    openDir: () => ipcRenderer.invoke('dl:openDir'),
    onUpdate: (fn) => ipcRenderer.on('dl:update', (_e, j) => fn(j)),
    onRemoved: (fn) => ipcRenderer.on('dl:removed', (_e, id) => fn(id)),
  },
})
