// Prismo Admin · programa de administração do Prismo Drive (Windows)
const { app, BrowserWindow, shell, Menu, ipcMain, net } = require('electron');
const path = require('path');
const fs = require('fs');
const os = require('os');
const { spawn } = require('child_process');

const REPO = 'AndreReal179/Prismo-Drive';
let janela = null;

function criar() {
  janela = new BrowserWindow({
    width: 1440, height: 920, minWidth: 1100, minHeight: 700,
    backgroundColor: '#070b09', title: 'Prismo Admin', autoHideMenuBar: true,
    icon: path.join(__dirname, 'renderer', 'logo.png'),
    webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true, spellcheck: false, preload: path.join(__dirname, 'preload.js') }
  });
  Menu.setApplicationMenu(null);
  janela.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  janela.webContents.setWindowOpenHandler(({ url }) => {
    if (/^(https:|mailto:)/.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  janela.webContents.on('will-navigate', (e, url) => { if (!url.startsWith('file:')) { e.preventDefault(); if (/^https:/.test(url)) shell.openExternal(url); } });
  janela.webContents.on('before-input-event', (e, i) => {
    if (i.type === 'keyDown' && i.key === 'F5') { janela.webContents.reload(); e.preventDefault(); }
    if (i.type === 'keyDown' && i.key === 'F12') { janela.webContents.toggleDevTools(); e.preventDefault(); }
  });
}

/* ═══════════ atualizador automático ═══════════
   Procura nos Releases do GitHub a versão mais nova "admin-v1.0.N" (separada das versões do app),
   baixa o instalador em segundo plano e, quando você toca em "Reiniciar e atualizar", instala sem
   perguntar nada e abre o programa de novo. */
const est = { fase: 'parado', atual: app.getVersion(), nova: null, url: null, nome: null, tamanho: 0, baixado: 0, arquivo: null, erro: null, notas: '' };
function avisar() { if (janela && !janela.isDestroyed()) janela.webContents.send('upd-estado', Object.assign({}, est)); }
function maior(a, b) {
  const x = String(a).split('.').map(Number), y = String(b).split('.').map(Number);
  for (let i = 0; i < Math.max(x.length, y.length); i++) { const d = (x[i] || 0) - (y[i] || 0); if (d) return d > 0; }
  return false;
}
async function checar() {
  if (['baixando', 'pronto', 'instalando'].includes(est.fase)) return est;
  est.fase = 'verificando'; est.erro = null; avisar();
  try {
    const r = await net.fetch('https://api.github.com/repos/' + REPO + '/releases?per_page=40', { headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'PrismoAdmin' } });
    if (!r.ok) throw new Error('GitHub respondeu ' + r.status);
    const lista = await r.json();
    const adm = lista.filter((x) => !x.draft && /^admin-v\d+\.\d+\.\d+$/.test(x.tag_name))
      .sort((a, b) => (maior(a.tag_name.slice(7), b.tag_name.slice(7)) ? -1 : 1))[0];
    if (!adm || !maior(adm.tag_name.slice(7), est.atual)) { est.fase = 'atualizado'; est.nova = null; avisar(); return est; }
    const exe = (adm.assets || []).find((a) => /\.exe$/i.test(a.name));
    if (!exe) { est.fase = 'atualizado'; avisar(); return est; }
    Object.assign(est, { fase: 'disponivel', nova: adm.tag_name.slice(7), url: exe.browser_download_url, nome: exe.name, tamanho: exe.size, notas: adm.body || '' });
    avisar();
    baixar();
  } catch (e) { est.fase = 'erro'; est.erro = String(e.message || e); avisar(); }
  return est;
}
async function baixar() {
  if (est.fase !== 'disponivel' && est.fase !== 'erro') return est;
  if (!est.url) return est;
  const destino = path.join(os.tmpdir(), est.nome || 'PrismoAdmin-Setup.exe');
  est.fase = 'baixando'; est.baixado = 0; est.erro = null; avisar();
  try {
    const r = await net.fetch(est.url);
    if (!r.ok || !r.body) throw new Error('download respondeu ' + r.status);
    const total = Number(r.headers.get('content-length')) || est.tamanho || 0;
    est.tamanho = total;
    const out = fs.createWriteStream(destino);
    const leitor = r.body.getReader();
    let ult = 0;
    for (;;) {
      const { done, value } = await leitor.read();
      if (done) break;
      out.write(Buffer.from(value));
      est.baixado += value.length;
      if (Date.now() - ult > 300) { ult = Date.now(); avisar(); }
    }
    await new Promise((ok, err) => { out.end(ok); out.on('error', err); });
    if (total && fs.statSync(destino).size !== total) throw new Error('arquivo incompleto');
    est.arquivo = destino; est.fase = 'pronto'; avisar();
  } catch (e) { est.fase = 'erro'; est.erro = 'Não foi possível baixar: ' + (e.message || e); avisar(); }
  return est;
}
function instalar() {
  if (est.fase !== 'pronto' || !est.arquivo || !fs.existsSync(est.arquivo)) return false;
  est.fase = 'instalando'; avisar();
  // /S = instala sem perguntar (no mesmo lugar) · --force-run = abre o programa quando terminar
  spawn(est.arquivo, ['/S', '--force-run'], { detached: true, stdio: 'ignore' }).unref();
  setTimeout(() => app.quit(), 600);
  return true;
}
ipcMain.handle('versao', () => app.getVersion());
ipcMain.handle('upd-checar', () => checar());
ipcMain.handle('upd-baixar', () => baixar());
ipcMain.handle('upd-instalar', () => instalar());

if (!app.requestSingleInstanceLock()) app.quit();
else {
  app.on('second-instance', () => { if (janela) { if (janela.isMinimized()) janela.restore(); janela.focus(); } });
  app.whenReady().then(() => {
    criar();
    if (app.isPackaged) {
      setTimeout(checar, 8000);                  // ao abrir
      setInterval(checar, 3 * 60 * 60 * 1000);   // e a cada 3 horas
    }
  });
  app.on('window-all-closed', () => app.quit());
}
