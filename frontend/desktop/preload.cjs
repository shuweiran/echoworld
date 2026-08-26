const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('roleplayDesktop', {
  isDesktop: true,
  updates: {
    state: () => ipcRenderer.invoke('update:state'),
    check: () => ipcRenderer.invoke('update:check'),
    download: () => ipcRenderer.invoke('update:download'),
    install: () => ipcRenderer.invoke('update:install'),
    onStatus: (listener) => {
      const handler = (_event, state) => listener(state);
      ipcRenderer.on('update:status', handler);
      return () => ipcRenderer.removeListener('update:status', handler);
    },
  },
});
