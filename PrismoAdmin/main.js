// Prismo Drive Admin · programa para Windows (Electron)
// A janela não acessa a internet nem o computador direto: tudo passa por aqui (só para o Supabase do Prismo Drive).
const { app, BrowserWindow, ipcMain, Notification, Tray, Menu, safeStorage, shell, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');

const SUPABASE = 'https://xzdqvdrwvozoedmjwwhm.supabase.co';
const APP_ID = 'com.prismo.drive.admin';

let janela = null;
let bandeja = null;
let saindo = false;

if (!app.requestSingleInstanceLock()) { app.quit(); }
app.on('second-instance', () => mostrar());
app.setAppUserModelId(APP_ID);   // notificações do Windows com o nome e o ícone do programa

function mostrar() {
  if (!janela) return criarJanela();
  if (janela.isMinimized()) janela.restore();
  janela.show();
  janela.focus();
}

function criarJanela() {
  janela = new BrowserWindow({
    width: 1320, height: 840, minWidth: 1040, minHeight: 640,
    backgroundColor: '#06100b',
    title: 'Prismo Drive Admin',
    icon: path.join(__dirname, 'build', 'icon.png'),
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: true,
      devTools: !app.isPackaged
    }
  });
  janela.removeMenu();
  janela.loadFile(path.join(__dirname, 'src', 'index.html'));
  janela.once('ready-to-show', () => janela.show());
  // links externos (e-mail do cliente, Mercado Pago…) abrem no navegador, nunca dentro do programa
  janela.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https:\/\//.test(url) || /^mailto:/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  janela.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file://')) e.preventDefault(); });
  // fechar no X: continua na bandeja para avisar chamados e vendas
  janela.on('close', (e) => {
    if (!saindo) {
      e.preventDefault();
      janela.hide();
      if (!lerConfig().avisouBandeja) {
        gravarConfig({ ...lerConfig(), avisouBandeja: true });
        notificar('Prismo Drive Admin continua aberto', 'Ele fica perto do relógio e avisa quando chegar chamado ou venda. Para fechar de vez: botão direito no ícone › Sair.');
      }
    }
  });
  janela.on('closed', () => { janela = null; });
}

function criarBandeja() {
  const ic = nativeImage.createFromPath(path.join(__dirname, 'src', 'tray.png'));
  bandeja = new Tray(ic);
  bandeja.setToolTip('Prismo Drive Admin');
  bandeja.setContextMenu(Menu.buildFromTemplate([
    { label: 'Abrir', click: mostrar },
    { type: 'separator' },
    { label: 'Sair', click: () => { saindo = true; app.quit(); } }
  ]));
  bandeja.on('click', mostrar);
}

/* ── sessão guardada criptografada pelo Windows (só este usuário do Windows consegue ler) ── */
const ARQ_SESSAO = () => path.join(app.getPath('userData'), 'sessao.bin');
const ARQ_CONFIG = () => path.join(app.getPath('userData'), 'config.json');
function lerConfig() { try { return JSON.parse(fs.readFileSync(ARQ_CONFIG(), 'utf8')) || {}; } catch (e) { return {}; } }
function gravarConfig(o) { try { fs.writeFileSync(ARQ_CONFIG(), JSON.stringify(o)); } catch (e) {} }

ipcMain.handle('sessao:ler', () => {
  try {
    const b = fs.readFileSync(ARQ_SESSAO());
    const txt = safeStorage.isEncryptionAvailable() ? safeStorage.decryptString(b) : '';
    return txt ? JSON.parse(txt) : null;
  } catch (e) { return null; }
});
ipcMain.handle('sessao:gravar', (_e, s) => {
  try {
    if (!s) { fs.rmSync(ARQ_SESSAO(), { force: true }); return true; }
    if (!safeStorage.isEncryptionAvailable()) return false;   // sem criptografia: não guarda (pede login de novo)
    fs.writeFileSync(ARQ_SESSAO(), safeStorage.encryptString(JSON.stringify(s)));
    return true;
  } catch (e) { return false; }
});

/* ── internet: só o Supabase do Prismo Drive ── */
ipcMain.handle('http', async (_e, req) => {
  try {
    const url = String(req.url || '');
    if (!url.startsWith(SUPABASE + '/')) return { status: 0, texto: 'destino_bloqueado' };
    const r = await fetch(url, { method: req.metodo || 'GET', headers: req.headers || {}, body: req.corpo == null ? undefined : req.corpo, signal: AbortSignal.timeout(45000) });
    return { status: r.status, texto: await r.text() };
  } catch (e) { return { status: 0, texto: '' }; }
});

function notificar(titulo, texto) {
  try {
    if (!Notification.isSupported()) return;
    const n = new Notification({ title: titulo, body: texto, icon: path.join(__dirname, 'build', 'icon.png') });
    n.on('click', mostrar);
    n.show();
  } catch (e) {}
}
ipcMain.handle('notificar', (_e, t, x) => { notificar(String(t || '').slice(0, 120), String(x || '').slice(0, 300)); return true; });
ipcMain.handle('contador', (_e, n) => {
  n = Number(n) || 0;
  try { bandeja && bandeja.setToolTip(n ? `Prismo Drive Admin · ${n} chamado(s) aguardando` : 'Prismo Drive Admin'); } catch (e) {}
  try { janela && janela.flashFrame(n > 0 && !janela.isFocused()); } catch (e) {}
  return true;
});
ipcMain.handle('versao', () => app.getVersion());
ipcMain.handle('abrir-email', (_e, email) => { if (/^[^\s@]+@[^\s@]+$/.test(String(email))) shell.openExternal('mailto:' + email); return true; });

app.whenReady().then(() => {
  criarJanela();
  criarBandeja();
});
app.on('before-quit', () => { saindo = true; });
app.on('window-all-closed', () => { /* fica na bandeja */ });
