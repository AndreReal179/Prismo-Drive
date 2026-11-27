// ponte segura entre a tela do programa e o atualizador (processo principal)
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('prismoApp', {
  versao: () => ipcRenderer.invoke('versao'),
  checar: () => ipcRenderer.invoke('upd-checar'),
  baixar: () => ipcRenderer.invoke('upd-baixar'),
  instalar: () => ipcRenderer.invoke('upd-instalar'),
  aoMudar: (cb) => ipcRenderer.on('upd-estado', (_e, est) => cb(est))
});
