const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('settings', {
  get: () => ipcRenderer.invoke('settings:get'),
  save: (base) => ipcRenderer.invoke('settings:save', base),
  close: () => ipcRenderer.invoke('settings:close'),
})
