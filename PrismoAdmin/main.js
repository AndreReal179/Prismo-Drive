// Prismo Admin · programa de administração do Prismo Drive (Windows)
const { app, BrowserWindow, shell, Menu } = require('electron');
const path = require('path');

function criar() {
  const w = new BrowserWindow({
    width: 1440, height: 920, minWidth: 1100, minHeight: 700,
    backgroundColor: '#070b09', title: 'Prismo Admin', autoHideMenuBar: true,
    icon: path.join(__dirname, 'renderer', 'logo.png'),
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, spellcheck: false }
  });
  Menu.setApplicationMenu(null);
  w.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  // links (privacidade, e-mail do cliente) abrem no navegador / app de e-mail
  w.webContents.setWindowOpenHandler(({ url }) => {
    if (/^(https:|mailto:)/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  w.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file:')) { e.preventDefault(); if (/^https:/.test(url)) shell.openExternal(url); } });
  // F5 atualiza a tela, F12 abre as ferramentas (para suporte)
  w.webContents.on('before-input-event', (e, i) => {
    if (i.type === 'keyDown' && i.key === 'F5') { w.webContents.reload(); e.preventDefault(); }
    if (i.type === 'keyDown' && i.key === 'F12') { w.webContents.toggleDevTools(); e.preventDefault(); }
  });
}

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { const w = BrowserWindow.getAllWindows()[0]; if (w) { if (w.isMinimized()) w.restore(); w.focus(); } });
  app.whenReady().then(criar);
  app.on('window-all-closed', () => app.quit());
}
