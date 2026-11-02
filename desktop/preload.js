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
})
