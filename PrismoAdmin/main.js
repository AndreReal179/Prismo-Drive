// Prismo Drive Admin · programa para Windows (Electron)
// A janela não acessa a internet nem o computador direto: tudo passa por aqui (só para o Supabase do Prismo Drive).
const { app, BrowserWindow, ipcMain, Notification, Tray, Menu, safeStorage, shell, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const http = require('http');
const crypto = require('crypto');

const SUPABASE = 'https://xzdqvdrwvozoedmjwwhm.supabase.co';
const APP_ID = 'com.prismo.drive.admin';
const CHAVE = 'sb_publishable_OGjy3PepqOrIiVmw4s9EWA_kJGoFOFL';   // chave pública (a mesma do app)
const PORTA_GOOGLE = 53682;                                        // volta do Google: http://127.0.0.1:53682/callback

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

/* ── Entrar com Google: abre o navegador, o Supabase devolve um código para este computador (PKCE) ── */
let googleAberto = null;
const b64url = (b) => b.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const paginaVolta = (ok, msg) => `<!doctype html><meta charset="utf-8"><title>Prismo Drive Admin</title><body style="margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#06100b;color:#e6edf3;font-family:Segoe UI,Arial,sans-serif"><div style="text-align:center;padding:30px;border-radius:18px;background:#0a140e;border:1px solid #17291e;max-width:420px"><div style="font-size:42px">${ok ? '✅' : '⚠️'}</div><h2 style="margin:10px 0 6px">${ok ? 'Pronto!' : 'Não deu certo'}</h2><p style="color:#9fb0a6;line-height:1.5">${msg}</p></div></body>`;
ipcMain.handle('google:entrar', () => {
  if (googleAberto) { try { googleAberto.close(); } catch (e) {} googleAberto = null; }
  const verificador = b64url(crypto.randomBytes(48));
  const desafio = b64url(crypto.createHash('sha256').update(verificador).digest());
  const volta = `http://127.0.0.1:${PORTA_GOOGLE}/callback`;
  return new Promise((ok) => {
    let fim = false;
    const terminar = (r) => {
      if (fim) return; fim = true;
      clearTimeout(limite);
      setTimeout(() => { try { srv.close(); } catch (e) {} if (googleAberto === srv) googleAberto = null; }, 500);
      mostrar();
      ok(r);
    };
    const srv = http.createServer(async (req, res) => {
      const u = new URL(req.url, volta);
      if (u.pathname !== '/callback') { res.writeHead(404); res.end(); return; }
      const codigo = u.searchParams.get('code');
      const erro = u.searchParams.get('error_description') || u.searchParams.get('error');
      if (!codigo) {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(paginaVolta(false, 'O Google não liberou a entrada. Feche esta aba e tente de novo no programa.'));
        return terminar({ erro: erro || 'sem_codigo' });
      }
      try {
        const r = await fetch(SUPABASE + '/auth/v1/token?grant_type=pkce', {
          method: 'POST', headers: { apikey: CHAVE, 'Content-Type': 'application/json' },
          body: JSON.stringify({ auth_code: codigo, code_verifier: verificador }), signal: AbortSignal.timeout(30000)
        });
        const j = await r.json().catch(() => null);
        const certo = r.status === 200 && j && j.access_token;
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(paginaVolta(!!certo, certo ? 'Você entrou no Prismo Drive Admin. Pode fechar esta aba e voltar para o programa.' : 'Não foi possível concluir a entrada. Feche esta aba e tente de novo.'));
        terminar(certo ? { sessao: j } : { erro: (j && (j.msg || j.error_description || j.error)) || 'troca_falhou' });
      } catch (e) {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(paginaVolta(false, 'Sem internet. Feche esta aba e tente de novo.'));
        terminar({ erro: 'sem_internet' });
      }
    });
    const limite = setTimeout(() => terminar({ erro: 'tempo' }), 4 * 60 * 1000);
    srv.on('error', () => terminar({ erro: 'porta' }));
    srv.listen(PORTA_GOOGLE, '127.0.0.1', () => {
      googleAberto = srv;
      const url = SUPABASE + '/auth/v1/authorize?provider=google'
        + '&redirect_to=' + encodeURIComponent(volta)
        + '&code_challenge=' + desafio + '&code_challenge_method=s256';
      shell.openExternal(url);
    });
  });
});
ipcMain.handle('google:cancelar', () => { if (googleAberto) { try { googleAberto.close(); } catch (e) {} googleAberto = null; } return true; });

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
