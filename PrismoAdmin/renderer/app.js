/* Prismo Admin · programa de administração do Prismo Drive
   Fala direto com o Supabase do app usando a conta de administrador (e-mail e senha).
   A chave abaixo é a pública (a mesma do app): o que só o admin pode ver é protegido no servidor (eh_admin). */
'use strict';
const URL_SB = 'https://xzdqvdrwvozoedmjwwhm.supabase.co';
const CHAVE = 'sb_publishable_OGjy3PepqOrIiVmw4s9EWA_kJGoFOFL';
const K_SESS = 'pa_sessao';

const $ = (id) => document.getElementById(id);
const esc = (t) => String(t == null ? '' : t).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const num = (v, c = 0) => (Number(v) || 0).toLocaleString('pt-BR', { minimumFractionDigits: c, maximumFractionDigits: c });
const brl = (v) => (Number(v) || 0).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
const data = (d) => d ? new Date(d).toLocaleDateString('pt-BR') : '—';
const dataHora = (d) => d ? new Date(d).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', year: '2-digit', hour: '2-digit', minute: '2-digit' }) : '—';
function ha(d) {
  if (!d) return '—';
  const s = Math.max(0, (Date.now() - new Date(d).getTime()) / 1000);
  if (s < 60) return 'agora';
  if (s < 3600) return 'há ' + Math.round(s / 60) + ' min';
  if (s < 86400) return 'há ' + Math.round(s / 3600) + ' h';
  return 'há ' + Math.round(s / 86400) + ' dias';
}
const idFmt = (id) => /^\d{8}$/.test(String(id || '')) ? String(id).slice(0, 4) + ' ' + String(id).slice(4) : esc(id || '—');
const CORES_AVA = ['#1d4ed8', '#7c3aed', '#0f766e', '#be123c', '#b45309', '#15803d', '#0e7490', '#9333ea'];
function ava(nome, id) {
  const n = String(nome || '?').trim(), h = [...String(id || n)].reduce((a, c) => a + c.charCodeAt(0), 0);
  return '<span class="ava" style="background:' + CORES_AVA[h % CORES_AVA.length] + '">' + esc(n.charAt(0).toUpperCase() || '?') + '</span>';
}
const chipPlano = (p) => '<span class="chip ' + (p || 'free') + '">' + ({ admin: '<span class="mi">shield</span>Admin', pro: '<span class="mi">workspace_premium</span>Pro', free: 'Free' }[p] || 'Free') + '</span>';

function toast(t, ruim) {
  const e = $('toast'); e.textContent = t; e.className = 'toast' + (ruim ? ' ruim' : ''); e.hidden = false;
  clearTimeout(toast._t); toast._t = setTimeout(() => { e.hidden = true; }, 3500);
}

/* ═══════════ sessão ═══════════ */
let sess = null;
try { sess = JSON.parse(localStorage.getItem(K_SESS) || 'null'); } catch (e) { sess = null; }
function salvarSess(j) {
  sess = { access_token: j.access_token, refresh_token: j.refresh_token, expira: Date.now() + (Number(j.expires_in) || 3600) * 1000, email: (j.user && j.user.email) || (sess && sess.email) || '' };
  localStorage.setItem(K_SESS, JSON.stringify(sess));
}
async function auth(caminho, corpo) {
  const r = await fetch(URL_SB + '/auth/v1/' + caminho, { method: 'POST', headers: { apikey: CHAVE, 'Content-Type': 'application/json' }, body: JSON.stringify(corpo) });
  let j = null; try { j = await r.json(); } catch (e) { }
  return { status: r.status, json: j };
}
let renovando = null;
async function token() {
  if (!sess) return null;
  if (Date.now() < sess.expira - 90000) return sess.access_token;
  if (!renovando) renovando = auth('token?grant_type=refresh_token', { refresh_token: sess.refresh_token }).then((r) => {
    renovando = null;
    if (r.status === 200 && r.json && r.json.access_token) { salvarSess(r.json); return sess.access_token; }
    return null;
  }, () => { renovando = null; return null; });
  return renovando;
}
async function api(metodo, caminho, corpo) {
  const t = await token();
  if (!t) { sair(true); return { status: 401, json: null }; }
  try {
    const r = await fetch(URL_SB + caminho, { method: metodo, headers: { apikey: CHAVE, Authorization: 'Bearer ' + t, 'Content-Type': 'application/json' }, body: corpo == null ? undefined : JSON.stringify(corpo) });
    const tx = await r.text(); let j = null; try { j = tx ? JSON.parse(tx) : null; } catch (e) { }
    if (r.status === 401) { sess.expira = 0; }
    return { status: r.status, json: j };
  } catch (e) { return { status: 0, json: null }; }
}
const rpc = (n, a) => api('POST', '/rest/v1/rpc/' + n, a || {});
function erroTxt(r) {
  if (!r || r.status === 0) return 'Sem internet. Verifique a conexão.';
  const m = JSON.stringify(r.json || '');
  if (m.includes('sem_permissao')) return 'Esta conta não é de administrador.';
  if (m.includes('Could not find the function') || m.includes('PGRST202')) return 'Falta rodar o SQL 20261121_painel_admin.sql no Supabase.';
  return (r.json && (r.json.message || r.json.msg)) || ('Erro ' + r.status);
}

async function entrar(ev) {
  ev.preventDefault();
  const b = $('lg-bt'), er = $('lg-erro'); er.textContent = ''; b.disabled = true; b.innerHTML = '<span class="mi">hourglass_top</span>Entrando…';
  try {
    const r = await auth('token?grant_type=password', { email: $('lg-email').value.trim(), password: $('lg-senha').value });
    if (r.status !== 200 || !r.json || !r.json.access_token) {
      const m = JSON.stringify(r.json || '');
      er.textContent = /invalid_credentials|Invalid login/i.test(m) ? 'E-mail ou senha incorretos.' : (/not confirmed/i.test(m) ? 'Falta confirmar o e-mail da conta.' : (r.status ? 'Não foi possível entrar (' + r.status + ').' : 'Sem internet.'));
      return;
    }
    salvarSess(r.json);
    const t = await rpc('admin_painel');
    if (t.status !== 200) { er.textContent = erroTxt(t); if (JSON.stringify(t.json || '').includes('sem_permissao')) sair(true); return; }
    $('lg-senha').value = '';
    iniciar();
  } catch (e) { er.textContent = 'Sem internet.'; }
  finally { b.disabled = false; b.innerHTML = '<span class="mi">login</span>Entrar'; }
}
function sair(silencioso) {
  if (sess && sess.access_token && !silencioso) fetch(URL_SB + '/auth/v1/logout', { method: 'POST', headers: { apikey: CHAVE, Authorization: 'Bearer ' + sess.access_token } }).catch(() => { });
  sess = null; localStorage.removeItem(K_SESS);
  clearInterval(timer); $('app').hidden = true; $('login').hidden = false;
}

/* ═══════════ navegação ═══════════ */
const PAGINAS = {
  painel: ['Painel', 'Tudo do Prismo Drive em tempo real', painel],
  relatorios: ['Relatórios', 'Vendas do Pro e usuários por dia, mês e ano', relatorios],
  online: ['Online agora', 'Quem está com o app aberto e quem está em rota', online],
  usuarios: ['Usuários', 'Todas as contas: ID, nome, plano e atividade', usuarios],
  suporte: ['Suporte', 'Chamados enviados pelos clientes', suporte],
  erros: ['Erros do app', 'Relatório automático de erros enviado pelos celulares', erros],
  missoes: ['Missões', 'Crie e ajuste as missões que aparecem no app', missoes],
  insignias: ['Insígnias', 'As conquistas que os motoristas ganham nas missões', insignias],
  vendas: ['Vendas do Pro', 'Pagamentos do Mercado Pago', vendas],
  notificacoes: ['Notificações', 'Envie avisos no celular dos motoristas, na hora ou agendados', notificacoes],
  planos: ['Planos e preços', 'O que muda aqui vale na hora no app, no site e na cobrança do Mercado Pago', planos],
  codigos: ['Códigos Pro', 'Gere códigos de dias de Pro para presentear ou vender', codigos],
  backups: ['Backups', 'Cópias de segurança do banco de dados', backups],
  historico: ['Histórico de ações', 'Tudo o que foi feito pelos administradores', historico],
  ranqueada: ['Ranqueada', 'Classificação mundial do mês, prêmios e pausa', ranqueada]
};
let atual = 'painel', timer = 0;
const graficos = [];
function limparGraficos() { while (graficos.length) { try { graficos.pop().destroy(); } catch (e) { } } }
function ir(pg) {
  if (!PAGINAS[pg]) pg = 'painel';
  atual = pg;
  document.querySelectorAll('#nav button').forEach((b) => b.classList.toggle('on', b.dataset.pg === pg));
  $('pg-titulo').textContent = PAGINAS[pg][0]; $('pg-sub').textContent = PAGINAS[pg][1];
  limparGraficos(); clearInterval(timer);
  $('conteudo').innerHTML = '<div class="carreg"><span class="mi">autorenew</span><p>Carregando…</p></div>';
  PAGINAS[pg][2]();
}
/* atualiza a tela aberta sozinha a cada 1 minuto (Suporte e Online têm o próprio ritmo, mais rápido).
   Não atualiza com uma janela aberta, enquanto você digita, nem nas telas de escrever. */
setInterval(() => {
  if (!sess || $('app').hidden || !$('modal').hidden) return;
  if (['suporte', 'online'].includes(atual)) return;
  if (atual === 'notificacoes' && nt.aba !== 'lista') return;
  const a = document.activeElement; if (a && a.matches && a.matches('input,textarea,select')) return;
  recarregar();
}, 60000);
function recarregar() { const sc = $('conteudo').scrollTop; limparGraficos(); PAGINAS[atual][2](true); $('conteudo').scrollTop = sc; }
function marcarAtualizado() { $('atualizado').textContent = 'Atualizado às ' + new Date().toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit', second: '2-digit' }); }
function falhou(r) { $('conteudo').innerHTML = '<div class="vazio"><span class="mi">cloud_off</span><b>' + esc(erroTxt(r)) + '</b><p class="small" style="margin-top:6px">Toque em Atualizar para tentar de novo.</p></div>'; }
function badge(id, n) { const e = $(id); if (!e) return; e.textContent = n > 99 ? '99+' : String(n); e.classList.toggle('on', n > 0); }
async function atualizarBadges(p) {
  if (!p) { const r = await rpc('admin_painel'); if (r.status !== 200) return; p = r.json; }
  badge('nb-online', p.online); badge('nb-suporte', p.chamados_abertos); badge('nb-erros', p.erros_novos);
}

/* ═══════════ gráficos ═══════════ */
const COR = { azul: '#60a5fa', verde: '#4ade80', ouro: '#fbbf24', roxo: '#a78bfa', cia: '#22d3ee', lar: '#fb923c', cinza: '#94a3b8' };
function alfa(hex, a) { const n = parseInt(hex.slice(1), 16); return 'rgba(' + (n >> 16) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + a + ')'; }
if (window.Chart) {
  Chart.defaults.color = '#8fa197';
  Chart.defaults.font.family = '"Segoe UI",system-ui,sans-serif';
  Chart.defaults.font.size = 12;
  Chart.defaults.borderColor = 'rgba(255,255,255,.06)';
  Chart.defaults.plugins.legend.labels.usePointStyle = true;
  Chart.defaults.plugins.legend.labels.boxWidth = 8;
  Chart.defaults.plugins.tooltip.backgroundColor = '#0b130e';
  Chart.defaults.plugins.tooltip.borderColor = '#2a3b31';
  Chart.defaults.plugins.tooltip.borderWidth = 1;
  Chart.defaults.plugins.tooltip.padding = 10;
  Chart.defaults.animation.duration = 500;
}
function grafico(id, cfg) {
  const el = $(id); if (!el || !window.Chart) return null;
  const g = new Chart(el, cfg); graficos.push(g); return g;
}
function linha(lbl, cor, dados, extra) { return Object.assign({ type: 'line', label: lbl, data: dados, borderColor: cor, backgroundColor: alfa(cor, .14), fill: true, tension: .35, borderWidth: 2.4, pointRadius: 0, pointHoverRadius: 5, pointBackgroundColor: cor }, extra || {}); }
function barra(lbl, cor, dados, extra) { return Object.assign({ type: 'bar', label: lbl, data: dados, backgroundColor: alfa(cor, .75), hoverBackgroundColor: cor, borderRadius: 6, maxBarThickness: 26 }, extra || {}); }
const eixos = (fmtY) => ({ x: { grid: { display: false }, ticks: { maxRotation: 0, autoSkipPadding: 14 } }, y: { beginAtZero: true, ticks: { precision: 0, callback: fmtY || undefined }, grid: { color: 'rgba(255,255,255,.05)' } } });
const MESES = ['jan', 'fev', 'mar', 'abr', 'mai', 'jun', 'jul', 'ago', 'set', 'out', 'nov', 'dez'];
function rotulo(iso, escala) {
  const [a, m, d] = String(iso).slice(0, 10).split('-');
  if (escala === 'ano') return a;
  if (escala === 'mes') return MESES[Number(m) - 1] + '/' + a.slice(2);
  return d + '/' + m;
}
function kpi(ic, cor, t, v, s, vivo) {
  return '<div class="card kpi' + (vivo ? ' vivo' : '') + '"><div class="t"><span class="ic" style="background:' + alfa(cor, .14) + ';color:' + cor + '"><span class="mi">' + ic + '</span></span>' + t + '</div><div class="v">' + v + '</div><div class="s">' + (s || '&nbsp;') + '</div></div>';
}

/* ═══════════ PAINEL ═══════════ */
async function painel() {
  const [r, o] = await Promise.all([rpc('admin_painel'), rpc('admin_online')]);
  if (atual !== 'painel') return;
  if (r.status !== 200) return falhou(r);
  const p = r.json, s = p.serie || [], on = Array.isArray(o.json) ? o.json : [];
  atualizarBadges(p);
  const lb = s.map((x) => rotulo(x.dia, 'dia'));
  const somaVendas30 = s.reduce((a, x) => a + (Number(x.vendas) || 0), 0);
  $('conteudo').innerHTML =
    '<div class="grid k4">'
    + kpi('sensors', COR.azul, 'Online agora', num(p.online), 'com o app aberto nos últimos 3 min', true)
    + kpi('route', COR.verde, 'Em rota agora', num(p.em_rota), 'iFood e InDrive · ' + num(p.rotas_hoje) + ' rotas hoje')
    + kpi('group', COR.cia, 'Usuários', num(p.usuarios), num(p.ativos_hoje) + ' usaram hoje · ' + num(p.novos_hoje) + ' novos')
    + kpi('workspace_premium', COR.ouro, 'Pro ativos', num(p.pro), num((p.pro / Math.max(1, p.usuarios - (p.planos ? p.planos.admin : 0))) * 100, 1) + '% das contas')
    + '</div><div class="grid k4 mt">'
    + kpi('payments', COR.ouro, 'Vendas hoje', brl(p.vendas_hoje), 'Pro pago e aprovado')
    + kpi('calendar_month', COR.lar, 'Vendas no mês', brl(p.vendas_mes), brl(somaVendas30) + ' nos últimos 30 dias')
    + kpi('support_agent', COR.roxo, 'Chamados abertos', num(p.chamados_abertos), '<a href="#" data-pg-ir="suporte" style="color:#c4b5fd">abrir o Suporte ›</a>')
    + kpi('bug_report', COR.lar, 'Erros novos', num(p.erros_novos), 'nos últimos 7 dias, sem resolver')
    + '</div>'
    + '<div class="grid g21 mt"><div class="card"><h3><span class="mi">show_chart</span>Usuários por dia<span class="r">últimos 30 dias</span></h3><div class="ch alto"><canvas id="g-usu" role="img" aria-label="Gráfico de linhas: usuários ativos e usuários em rota por dia nos últimos 30 dias"></canvas></div></div>'
    + '<div class="card"><h3><span class="mi">donut_large</span>Contas por plano</h3><div class="ch alto"><canvas id="g-plano" role="img" aria-label="Gráfico de rosca: contas Free, Pro e Admin"></canvas></div></div></div>'
    + '<div class="grid k2 mt"><div class="card"><h3><span class="mi">payments</span>Vendas do Pro por dia<span class="r">R$ · 30 dias</span></h3><div class="ch"><canvas id="g-ven" role="img" aria-label="Gráfico de barras: vendas do Pro em reais por dia"></canvas></div></div>'
    + '<div class="card"><h3><span class="mi">route</span>Rotas e contas novas por dia<span class="r">30 dias</span></h3><div class="ch"><canvas id="g-rot" role="img" aria-label="Gráfico de barras: rotas por dia, e linha de contas novas"></canvas></div></div></div>'
    + '<div class="card mt"><h3><span class="mi">sensors</span>Online agora<span class="r"><a href="#" data-pg-ir="online" style="color:#93c5fd">ver todos ›</a></span></h3>' + listaOnline(on.slice(0, 8), true) + '</div>';
  grafico('g-usu', { type: 'line', data: { labels: lb, datasets: [linha('Usaram o app', COR.azul, s.map((x) => x.ativos)), linha('Rodaram (em rota)', COR.verde, s.map((x) => x.em_rota))] }, options: { maintainAspectRatio: false, interaction: { mode: 'index', intersect: false }, scales: eixos(), plugins: { legend: { position: 'top', align: 'end' } } } });
  const pl = p.planos || {};
  grafico('g-plano', { type: 'doughnut', data: { labels: ['Free', 'Pro', 'Admin'], datasets: [{ data: [pl.free || 0, pl.pro || 0, pl.admin || 0], backgroundColor: [COR.cinza, COR.ouro, COR.cia], borderColor: '#0f1813', borderWidth: 3, hoverOffset: 6 }] }, options: { maintainAspectRatio: false, cutout: '68%', plugins: { legend: { position: 'bottom' } } } });
  grafico('g-ven', { type: 'bar', data: { labels: lb, datasets: [barra('Vendas (R$)', COR.ouro, s.map((x) => Number(x.vendas) || 0))] }, options: { maintainAspectRatio: false, scales: eixos((v) => 'R$ ' + num(v)), plugins: { legend: { display: false }, tooltip: { callbacks: { label: (c) => brl(c.parsed.y) } } } } });
  grafico('g-rot', { type: 'bar', data: { labels: lb, datasets: [barra('Rotas', COR.roxo, s.map((x) => x.rotas)), linha('Contas novas', COR.cia, s.map((x) => x.novos), { fill: false, yAxisID: 'y1', pointRadius: 2 })] }, options: { maintainAspectRatio: false, interaction: { mode: 'index', intersect: false }, scales: Object.assign(eixos(), { y1: { beginAtZero: true, position: 'right', grid: { display: false }, ticks: { precision: 0, color: COR.cia } } }), plugins: { legend: { position: 'top', align: 'end' } } } });
  marcarAtualizado();

}
function listaOnline(l, curta) {
  if (!l.length) return '<div class="vazio" style="padding:24px"><span class="mi">bedtime</span>Ninguém online agora.</div>';
  return '<table class="tab"><thead><tr><th>USUÁRIO</th><th>ID</th><th>PLANO</th><th>SITUAÇÃO</th><th>VERSÃO</th><th class="num">VISTO</th></tr></thead><tbody>'
    + l.map((u) => '<tr><td><div class="pessoa">' + ava(u.nome, u.conta_id) + '<div><b>' + esc(u.nome || '(sem nome)') + '</b>' + (curta ? '' : '<small>' + esc(u.email || '') + '</small>') + '</div></div></td><td class="idf">' + idFmt(u.conta_id) + '</td><td>' + chipPlano(u.plano) + '</td>'
      + '<td>' + (u.em_rota ? '<span class="chip rota"><span class="mi">route</span>Em rota · ' + esc(u.app === 'indrive' ? 'InDrive' : 'iFood') + '</span>' : (u.online ? '<span class="chip on"><span class="mi">smartphone</span>App aberto</span>' : '<span class="chip cinza">Saiu ' + ha(u.visto_em) + '</span>')) + '</td>'
      + '<td class="muted">' + esc(u.versao || '—') + '</td><td class="num muted">' + ha(u.visto_em) + '</td></tr>').join('') + '</tbody></table>';
}

/* ═══════════ RELATÓRIOS ═══════════ */
let escala = 'dia';
async function relatorios() {
  const r = await rpc('admin_relatorio', { p_escala: escala });
  if (atual !== 'relatorios') return;
  if (r.status !== 200) return falhou(r);
  const s = r.json || [], lb = s.map((x) => rotulo(x.inicio, escala));
  const tot = (k) => s.reduce((a, x) => a + (Number(x[k]) || 0), 0);
  const nomeEsc = { dia: 'últimos 30 dias', mes: 'últimos 12 meses', ano: 'últimos 5 anos' }[escala];
  const unid = { dia: 'dia', mes: 'mês', ano: 'ano' }[escala];
  const pico = (k) => s.reduce((a, x) => Math.max(a, Number(x[k]) || 0), 0);
  $('conteudo').innerHTML =
    '<div class="barra"><div class="seg">' + [['dia', 'Por dia'], ['mes', 'Por mês'], ['ano', 'Por ano']].map((t) => '<button data-escala="' + t[0] + '" class="' + (escala === t[0] ? 'on' : '') + '">' + t[1] + '</button>').join('') + '</div><span class="muted small">' + nomeEsc + '</span></div>'
    + '<div class="grid k4">'
    + kpi('payments', COR.ouro, 'Vendas do Pro', brl(tot('vendas')), num(tot('vendas_qtd')) + ' pagamentos · ' + nomeEsc)
    + kpi('group', COR.azul, 'Pico de usuários', num(pico('ativos')), 'maior número em um ' + unid)
    + kpi('route', COR.verde, 'Pico em rota', num(pico('em_rota')), 'motoristas rodando em um ' + unid)
    + kpi('add_road', COR.roxo, 'Rotas registradas', num(tot('rotas')), num(tot('km'), 0) + ' km rodados')
    + '</div>'
    + '<div class="card mt"><h3><span class="mi">payments</span>Vendas do Pro por ' + unid + '<span class="r">barras: R$ · linha: quantidade</span></h3><div class="ch alto"><canvas id="g-rv" role="img" aria-label="Vendas do Pro em reais e quantidade de pagamentos por período"></canvas></div></div>'
    + '<div class="grid k2 mt"><div class="card"><h3><span class="mi">group</span>Usuários por ' + unid + '</h3><div class="ch"><canvas id="g-ru" role="img" aria-label="Usuários ativos e em rota por período"></canvas></div></div>'
    + '<div class="card"><h3><span class="mi">route</span>Rotas e km por ' + unid + '</h3><div class="ch"><canvas id="g-rr" role="img" aria-label="Rotas e quilômetros por período"></canvas></div></div></div>'
    + '<div class="card mt"><h3><span class="mi">table_chart</span>Tabela</h3><table class="tab"><thead><tr><th>' + unid.toUpperCase() + '</th><th class="num">VENDAS (R$)</th><th class="num">PAGAMENTOS</th><th class="num">USARAM O APP</th><th class="num">EM ROTA</th><th class="num">ROTAS</th><th class="num">KM</th><th class="num">CONTAS NOVAS</th></tr></thead><tbody>'
    + s.slice().reverse().map((x) => '<tr><td><b>' + rotulo(x.inicio, escala) + '</b></td><td class="num">' + brl(x.vendas) + '</td><td class="num">' + num(x.vendas_qtd) + '</td><td class="num">' + num(x.ativos) + '</td><td class="num">' + num(x.em_rota) + '</td><td class="num">' + num(x.rotas) + '</td><td class="num">' + num(x.km, 0) + '</td><td class="num">' + num(x.novos) + '</td></tr>').join('')
    + '</tbody></table></div>';
  grafico('g-rv', { type: 'bar', data: { labels: lb, datasets: [barra('Vendas (R$)', COR.ouro, s.map((x) => Number(x.vendas) || 0), { yAxisID: 'y' }), linha('Pagamentos', COR.lar, s.map((x) => x.vendas_qtd), { fill: false, yAxisID: 'y1', pointRadius: escala === 'dia' ? 0 : 3 })] },
    options: { maintainAspectRatio: false, interaction: { mode: 'index', intersect: false }, scales: { x: { grid: { display: false } }, y: { beginAtZero: true, ticks: { callback: (v) => 'R$ ' + num(v) }, grid: { color: 'rgba(255,255,255,.05)' } }, y1: { beginAtZero: true, position: 'right', ticks: { precision: 0 }, grid: { display: false } } }, plugins: { legend: { position: 'top', align: 'end' }, tooltip: { callbacks: { label: (c) => c.dataset.yAxisID === 'y' ? ' Vendas: ' + brl(c.parsed.y) : ' Pagamentos: ' + num(c.parsed.y) } } } } });
  grafico('g-ru', { type: 'line', data: { labels: lb, datasets: [linha('Usaram o app', COR.azul, s.map((x) => x.ativos), { pointRadius: escala === 'dia' ? 0 : 3 }), linha('Em rota', COR.verde, s.map((x) => x.em_rota), { pointRadius: escala === 'dia' ? 0 : 3 })] }, options: { maintainAspectRatio: false, interaction: { mode: 'index', intersect: false }, scales: eixos(), plugins: { legend: { position: 'top', align: 'end' } } } });
  grafico('g-rr', { type: 'bar', data: { labels: lb, datasets: [barra('Rotas', COR.roxo, s.map((x) => x.rotas), { yAxisID: 'y' }), linha('Km', COR.cia, s.map((x) => Number(x.km) || 0), { fill: false, yAxisID: 'y1', pointRadius: escala === 'dia' ? 0 : 3 })] },
    options: { maintainAspectRatio: false, interaction: { mode: 'index', intersect: false }, scales: { x: { grid: { display: false } }, y: { beginAtZero: true, ticks: { precision: 0 }, grid: { color: 'rgba(255,255,255,.05)' } }, y1: { beginAtZero: true, position: 'right', grid: { display: false }, ticks: { callback: (v) => num(v) + ' km' } } }, plugins: { legend: { position: 'top', align: 'end' } } } });
  marcarAtualizado();
}

/* ═══════════ ONLINE ═══════════ */
let filtroOn = 'todos';
async function online() {
  const r = await rpc('admin_online');
  if (atual !== 'online') return;
  if (r.status !== 200) return falhou(r);
  const l = r.json || [], onl = l.filter((u) => u.online), rota = l.filter((u) => u.em_rota);
  badge('nb-online', onl.length);
  const vis = filtroOn === 'rota' ? rota : (filtroOn === 'app' ? onl.filter((u) => !u.em_rota) : l);
  $('conteudo').innerHTML =
    '<div class="grid k3">' + kpi('sensors', COR.azul, 'Com o app aberto', num(onl.length), 'últimos 3 minutos', true) + kpi('route', COR.verde, 'Em rota', num(rota.length), 'iFood ' + num(rota.filter((u) => u.app !== 'indrive').length) + ' · InDrive ' + num(rota.filter((u) => u.app === 'indrive').length)) + kpi('history', COR.cinza, 'Vistos há pouco', num(l.length), 'últimos 15 minutos') + '</div>'
    + '<div class="barra mt"><div class="seg">' + [['todos', 'Todos'], ['rota', 'Em rota'], ['app', 'Só com o app aberto']].map((t) => '<button data-fon="' + t[0] + '" class="' + (filtroOn === t[0] ? 'on' : '') + '">' + t[1] + '</button>').join('') + '</div><span class="muted small">atualiza sozinho a cada 15 s</span></div>'
    + '<div class="card">' + listaOnline(vis, false) + '</div>'
    + '<p class="muted small mt">Em rota = o assistente de rotas está acompanhando uma rota do iFood ou do InDrive. O app avisa a cada 2 minutos enquanto está aberto e quando sai para o app de corridas.</p>';
  marcarAtualizado();
  clearInterval(timer); timer = setInterval(() => { if (atual === 'online') recarregar(); }, 15000);
}

/* ═══════════ USUÁRIOS ═══════════ */
const us = { busca: '', plano: '', pag: 0, total: 0, lista: [] };
async function usuarios(manter) {
  if (!manter || !$('us-busca')) {
    $('conteudo').innerHTML = '<div class="barra"><div class="busca"><span class="mi">search</span><input id="us-busca" placeholder="Buscar por ID, nome ou e-mail" value="' + esc(us.busca) + '"></div>'
      + '<div class="seg">' + [['', 'Todos'], ['free', 'Free'], ['pro', 'Pro'], ['admin', 'Admin']].map((t) => '<button data-uplano="' + t[0] + '" class="' + (us.plano === t[0] ? 'on' : '') + '">' + t[1] + '</button>').join('') + '</div></div><div id="us-lista" class="card"><div class="carreg"><span class="mi">autorenew</span></div></div>';
  }
  const r = await rpc('admin_usuarios', { p_busca: us.busca, p_limite: 50, p_offset: us.pag * 50, p_plano: us.plano });
  if (atual !== 'usuarios') return;
  const box = $('us-lista'); if (!box) return;
  if (r.status !== 200) { box.innerHTML = '<div class="vazio"><span class="mi">cloud_off</span>' + esc(erroTxt(r)) + '</div>'; return; }
  us.total = r.json.total || 0; us.lista = r.json.lista || [];
  if (!us.lista.length) { box.innerHTML = '<div class="vazio"><span class="mi">person_search</span>Nenhuma conta encontrada.</div>'; return; }
  box.innerHTML = '<table class="tab"><thead><tr><th>USUÁRIO</th><th>ID</th><th>PLANO</th><th>CRIADA EM</th><th>ÚLTIMA VEZ NO APP</th><th class="num">ROTAS</th><th class="num">INSÍGNIAS</th><th></th></tr></thead><tbody>'
    + us.lista.map((u, i) => '<tr><td><div class="pessoa">' + ava(u.nome, u.conta_id) + '<div><b>' + esc(u.nome || '(sem nome)') + '</b><small>' + esc(u.email || '') + '</small></div></div></td>'
      + '<td><button class="bt ghost sm idf" data-copiar="' + esc(u.conta_id) + '" title="Copiar ID">' + idFmt(u.conta_id) + '<span class="mi" style="font-size:14px">content_copy</span></button></td>'
      + '<td>' + chipPlano(u.plano) + (u.plano === 'pro' && u.pro_ate ? '<div class="muted small">até ' + data(u.pro_ate) + '</div>' : '') + '</td>'
      + '<td class="muted">' + data(u.criado_em) + '</td>'
      + '<td>' + (u.em_rota ? '<span class="chip rota">Em rota agora</span>' : (u.online ? '<span class="chip on">Online agora</span>' : '<span class="muted">' + (u.visto_em ? ha(u.visto_em) : 'ainda não') + '</span>')) + '</td>'
      + '<td class="num">' + num(u.rotas) + '</td><td class="num">' + num(u.insignias) + '</td>'
      + '<td class="num"><button class="bt sm ghost" data-unotif="' + esc(u.conta_id) + '" title="Mandar notificação só para esta pessoa"><span class="mi">notifications</span></button> ' + (u.plano === 'admin' ? '' : '<button class="bt sm" data-pro="' + i + '" title="Dar 30 dias de Pro"><span class="mi">add</span>30 dias Pro</button> ' + (u.plano === 'pro' ? '<button class="bt sm verm" data-free="' + i + '" title="Tirar o Pro">Free</button>' : '')) + '</td></tr>').join('')
    + '</tbody></table><div class="pag"><span>' + num(us.pag * 50 + 1) + '–' + num(us.pag * 50 + us.lista.length) + ' de ' + num(us.total) + ' contas</span><span><button class="bt sm" data-upag="-1"' + (us.pag ? '' : ' disabled') + '><span class="mi">chevron_left</span>Anterior</button> <button class="bt sm" data-upag="1"' + ((us.pag + 1) * 50 < us.total ? '' : ' disabled') + '>Próxima<span class="mi">chevron_right</span></button></span></div>';
  marcarAtualizado();
}
async function darPro(i) {
  const u = us.lista[i]; if (!u) return;
  if (!confirm('Dar 30 dias de Pro para ' + (u.nome || u.conta_id) + '?')) return;
  const r = await rpc('admin_dar_pro', { conta: u.conta_id, dias: 30 });
  if (r.status === 200) { toast('30 dias de Pro liberados para ' + (u.nome || u.conta_id)); usuarios(true); } else toast(erroTxt(r), true);
}
async function tirarPro(i) {
  const u = us.lista[i]; if (!u) return;
  if (!confirm('Tirar o Pro de ' + (u.nome || u.conta_id) + ' (volta para o Free)?')) return;
  const r = await rpc('admin_tornar_free', { conta: u.conta_id });
  if (r.status === 200) { toast('Conta voltou para o Free'); usuarios(true); } else toast(erroTxt(r), true);
}

/* ═══════════ SUPORTE ═══════════ */
const sp = { abertos: true, lista: [], sel: null };
const ST = { aguardando: ['Aguardando', 'verm'], andamento: ['Em andamento', 'on'], respondido: ['Respondido', 'rota'], finalizado: ['Finalizado', 'cinza'] };
const CAT = { duvida: 'Dúvida', problema: 'Problema', bug: 'BUG', pagamento: 'Pagamento', sugestao: 'Sugestão' };
function assinCh(l) { return l.map((c) => c.id + ':' + c.status + ':' + c.atualizado_em + ':' + (c.msgs || []).length).join('|'); }
async function suporte(manter) {
  const r = await rpc('admin_chamados', { abertos: sp.abertos });
  if (atual !== 'suporte') return;
  if (r.status !== 200) { if (!manter || !$('sp-det')) falhou(r); return; }
  const nova = r.json || [];
  /* tempo real: a cada 4 s confere se chegou algo; só redesenha o que mudou (sem apagar o que você está digitando) */
  const mudou = assinCh(nova) !== assinCh(sp.lista) || !$('sp-det');
  sp.lista = nova;
  if (sp.abertos) badge('nb-suporte', sp.lista.filter((c) => c.status === 'aguardando' || c.status === 'andamento').length);
  if (mudou) {
    const lst = $('sp-lst');
    const itens = (sp.lista.length ? sp.lista.map((c) => '<button class="it' + (sp.sel === c.id ? ' on' : '') + (c.status === 'aguardando' ? ' novo' : '') + '" data-ch="' + c.id + '"><div class="l1"><span class="chip ' + (ST[c.status] || ST.aguardando)[1] + '">' + (ST[c.status] || ST.aguardando)[0] + '</span><small>' + ha(c.atualizado_em) + '</small></div><b>' + esc(c.assunto) + '</b><small>#' + c.id + ' · ' + esc(CAT[c.categoria] || c.categoria) + ' · ' + esc(c.nome || c.email || '') + (c.anexos ? ' · 📎 ' + c.anexos : '') + ((c.msgs || []).length ? ' · 💬 ' + c.msgs.length : '') + '</small></button>').join('')
      : '<div class="vazio"><span class="mi">inbox</span>' + (sp.abertos ? 'Nenhum chamado aberto. Tudo em dia! 🎉' : 'Nenhum chamado finalizado.') + '</div>');
    if (lst && manter) lst.innerHTML = itens;
    else $('conteudo').innerHTML = '<div class="dois"><div class="lst"><div class="seg" style="align-self:flex-start;margin-bottom:6px"><button data-sab="1" class="' + (sp.abertos ? 'on' : '') + '">Abertos</button><button data-sab="0" class="' + (!sp.abertos ? 'on' : '') + '">Finalizados</button></div><div id="sp-lst" class="lst2">' + itens + '</div><p class="muted small" style="margin-top:6px"><span class="ao-vivo"></span>Ao vivo: novas mensagens aparecem sozinhas</p></div>'
      + '<div class="card det" id="sp-det"><div class="vazio"><span class="mi">forum</span>Escolha um chamado na lista.</div></div></div>';
    if (sp.sel && sp.lista.some((c) => c.id === sp.sel)) abrirChamado(sp.sel, true);
    else if (sp.sel) { sp.sel = null; const d = $('sp-det'); if (d) d.innerHTML = '<div class="vazio"><span class="mi">task_alt</span>Este chamado foi finalizado.</div>'; }
  }
  marcarAtualizado();
  clearInterval(timer); timer = setInterval(() => { if (atual === 'suporte' && $('modal').hidden) suporte(true); }, 4000);
}
async function abrirChamado(id, sóMostrar) {
  sp.sel = id;
  document.querySelectorAll('.it').forEach((b) => b.classList.toggle('on', Number(b.dataset.ch) === id));
  let c = sp.lista.find((x) => x.id === id);
  if (!sóMostrar && c && c.status === 'aguardando') { const r = await rpc('admin_chamado_abrir', { chamado: id }); if (r.status === 200 && r.json && r.json.id) { c = r.json; sp.lista = sp.lista.map((x) => x.id === id ? c : x); const it = document.querySelector('.it[data-ch="' + id + '"] .chip'); if (it) { it.className = 'chip on'; it.textContent = 'Em andamento'; } } }
  if (!c) return;
  const box = $('sp-det'); if (!box) return;
  /* guarda o que está sendo digitado e a posição antes de redesenhar */
  const ta = $('sp-txt'), rasc = ta && box.dataset.ch === String(id) ? ta.value : '', foco = ta && document.activeElement === ta;
  const ms = box.querySelector('.msgs'), noFim = !ms || ms.scrollHeight - ms.scrollTop - ms.clientHeight < 40;
  box.dataset.ch = String(id);
  box.innerHTML = '<div style="display:flex;align-items:flex-start;gap:10px"><div style="flex:1"><span class="chip ' + (ST[c.status] || ST.aguardando)[1] + '">' + (ST[c.status] || ST.aguardando)[0] + '</span> <span class="chip cinza">' + esc(CAT[c.categoria] || c.categoria) + '</span><h3 style="font-size:18px;margin:10px 0 6px">' + esc(c.assunto) + '</h3>'
    + '<div class="info"><span>Cliente: <b>' + esc(c.nome || '—') + '</b></span><span>E-mail: <b>' + esc(c.email || '—') + '</b></span><span>ID: <b class="idf">' + idFmt(c.conta_id) + '</b></span><span>Plano: <b>' + esc(c.plano || '—') + '</b></span><span>App: <b>' + esc(c.app_versao || '—') + '</b></span><span>Celular: <b>' + esc(c.aparelho || '—') + '</b></span><span>Aberto: <b>' + dataHora(c.criado_em) + '</b></span></div></div>'
    + (c.status !== 'finalizado' ? '<button class="bt sm" data-fin="' + c.id + '"><span class="mi">task_alt</span>Finalizar</button>' : '') + '</div>'
    + (c.ocorrencias ? '<p class="small" style="margin-top:10px;color:#fdba74">Erro automático: aconteceu ' + num(c.ocorrencias.vezes) + ' vezes com ' + num(c.ocorrencias.pessoas) + ' pessoa(s). Último: ' + dataHora(c.ocorrencias.ultimo) + '</p>' : '')
    + '<div class="msgs"><div class="msg"><small>' + esc(c.nome || 'Cliente') + ' · ' + dataHora(c.criado_em) + '</small>' + esc(c.mensagem) + (c.anexos ? '\n\n📎 ' + c.anexos + ' anexo(s): chegaram no e-mail da empresa.' : '') + '</div>'
    + (c.msgs || []).map((m) => '<div class="msg' + (m.autor === 'admin' ? ' adm' : '') + '"><small>' + (m.autor === 'admin' ? 'Você (equipe)' : esc(c.nome || 'Cliente')) + ' · ' + dataHora(m.criado_em) + '</small>' + esc(m.texto) + '</div>').join('') + '</div>'
    + (c.status !== 'finalizado' ? '<div style="margin-top:auto"><textarea id="sp-txt" maxlength="4000" placeholder="Escreva a resposta (Enter envia · Shift+Enter pula linha)"></textarea><div style="display:flex;justify-content:flex-end;gap:8px;margin-top:8px"><button class="bt pri" data-resp="' + c.id + '" id="sp-bt"><span class="mi">send</span>Responder</button></div></div>' : '');
  const t2 = $('sp-txt'); if (t2) { t2.value = rasc; if (foco || !sóMostrar) { t2.focus(); t2.setSelectionRange(rasc.length, rasc.length); } }
  const ms2 = box.querySelector('.msgs'); if (ms2 && noFim) ms2.scrollTop = ms2.scrollHeight;
}
async function responder(id) {
  const t = ($('sp-txt').value || '').trim(); if (t.length < 2) return toast('Escreva a resposta.', true);
  const b = $('sp-bt'); b.disabled = true; b.innerHTML = '<span class="mi">hourglass_top</span>Enviando…';
  const r = await api('POST', '/functions/v1/suporte', { acao: 'responder', chamado: id, texto: t });
  b.disabled = false; b.innerHTML = '<span class="mi">send</span>Responder';
  if (r.status === 200 && r.json && r.json.ok) { const ta = $('sp-txt'); if (ta) ta.value = ''; toast(r.json.email ? 'Resposta enviada no app e no e-mail do cliente.' : 'Resposta salva no app (o e-mail não saiu).'); suporte(true); }
  else toast((r.json && r.json.msg) || erroTxt(r), true);
}
async function finalizar(id) {
  if (!confirm('Finalizar o chamado #' + id + '?')) return;
  const r = await rpc('admin_chamado_finalizar', { chamado: id });
  if (r.status === 200) { toast('Chamado finalizado.'); sp.sel = null; suporte(true); } else toast(erroTxt(r), true);
}

/* ═══════════ ERROS ═══════════ */
let errRes = false, errAberto = {};
async function erros() {
  const r = await rpc('admin_erros', { p_resolvidos: errRes, p_dias: 60 });
  if (atual !== 'erros') return;
  if (r.status !== 200) return falhou(r);
  const l = r.json || [];
  if (!errRes) badge('nb-erros', l.filter((e) => Date.now() - new Date(e.ultimo_em) < 7 * 864e5).length);
  const totOc = l.reduce((a, e) => a + (e.ocorrencias || 0), 0), pes = l.reduce((a, e) => a + (e.pessoas || 0), 0);
  $('conteudo').innerHTML = '<div class="barra"><div class="seg"><button data-eres="0" class="' + (!errRes ? 'on' : '') + '">Para resolver</button><button data-eres="1" class="' + (errRes ? 'on' : '') + '">Resolvidos</button></div><span class="muted small">últimos 60 dias</span></div>'
    + '<div class="grid k3">' + kpi('bug_report', COR.lar, errRes ? 'Erros resolvidos' : 'Erros diferentes', num(l.length), 'agrupados pela mesma mensagem e versão') + kpi('repeat', COR.roxo, 'Vezes que aconteceram', num(totOc), 'somando todos') + kpi('person', COR.azul, 'Pessoas afetadas', num(pes), 'contas ou celulares diferentes') + '</div>'
    + '<div class="grid mt">' + (l.length ? l.map((e) => '<div class="card err"><div class="l1"><span class="chip ' + (e.ocorrencias >= 10 ? 'verm' : 'cinza') + '">' + num(e.ocorrencias) + 'x</span><div class="m">' + esc(e.mensagem || '(sem mensagem)') + '</div>'
      + '<button class="bt sm ghost" data-epilha="' + e.id + '"><span class="mi">code</span>' + (errAberto[e.id] ? 'Esconder' : 'Detalhes') + '</button><button class="bt sm" data-eresolve="' + e.id + '" data-v="' + (e.resolvido_em ? 0 : 1) + '"><span class="mi">' + (e.resolvido_em ? 'undo' : 'check') + '</span>' + (e.resolvido_em ? 'Reabrir' : 'Resolvido') + '</button></div>'
      + '<div class="nums"><span>Versão <b>' + esc(e.versao) + '</b></span><span>Tela <b>' + esc(e.tela || '—') + '</b></span><span>Origem <b>' + esc(e.origem || '—') + '</b></span><span><b>' + num(e.pessoas) + '</b> pessoa(s)</span><span>Primeiro <b>' + dataHora(e.primeiro_em) + '</b></span><span>Último <b>' + ha(e.ultimo_em) + '</b></span>' + (e.aparelho ? '<span>Celular <b>' + esc(e.aparelho) + '</b></span>' : '') + (e.chamado_id ? '<span><a href="#" data-pg-ir="suporte" style="color:#c4b5fd">chamado #' + e.chamado_id + '</a></span>' : '') + '</div>'
      + (errAberto[e.id] ? '<pre>' + esc(e.pilha || 'Sem detalhes técnicos.') + '</pre>' : '') + '</div>').join('')
      : '<div class="card vazio"><span class="mi">verified</span>' + (errRes ? 'Nenhum erro resolvido ainda.' : 'Nenhum erro para resolver. 🎉') + '</div>') + '</div>';
  marcarAtualizado();
}
async function resolverErro(id, v) {
  const r = await rpc('admin_erro_resolver', { p_id: id, p_resolvido: v });
  if (r.status === 200) { toast(v ? 'Marcado como resolvido' : 'Erro reaberto'); erros(); } else toast(erroTxt(r), true);
}

/* ═══════════ MISSÕES E INSÍGNIAS ═══════════ */
const ICONES = ['flag', 'route', 'local_shipping', 'delivery_dining', 'local_taxi', 'two_wheeler', 'directions_car', 'local_fire_department', 'bolt', 'rocket_launch', 'emoji_events', 'military_tech', 'workspace_premium', 'star', 'diamond', 'whatshot', 'speed', 'timer', 'schedule', 'calendar_month', 'today', 'nights_stay', 'wb_sunny', 'savings', 'payments', 'paid', 'attach_money', 'volunteer_activism', 'favorite', 'thumb_up', 'groups', 'person_add', 'handshake', 'sports_mma', 'map', 'explore', 'place', 'local_gas_station', 'eco', 'build', 'verified', 'shield', 'auto_awesome', 'celebration', 'pets', 'coffee', 'restaurant', 'trending_up', 'insights', 'stars'];
const TIPOS = { rotas_todas: ['Rotas feitas (todas: lançadas à mão e automáticas)', 'rotas'], rotas: ['Rotas feitas só no iFood/InDrive (automáticas)', 'rotas'], km: ['Km rodados em rotas', 'km'], dias: ['Dias diferentes rodando', 'dias'], gorjetas: ['Gorjetas registradas', 'gorjetas'], ganho: ['Faturamento das rotas (R$)', 'R$'], turnos: ['Turnos finalizados', 'turnos'], meta: ['Dias com a meta diária batida', 'dias'], indicacoes: ['Indicações confirmadas (total)', 'indicações'], duelos: ['Duelos vencidos (contra qualquer amigo)', 'vitórias'], assistente: ['Dias com o assistente de rotas ligado', 'dias'] };
const DIFS = { facil: ['Fácil', COR.verde], media: ['Média', COR.azul], dificil: ['Difícil', '#fb7185'] };
const RARS = { comum: ['Comum', '#9ca3af'], rara: ['Rara', COR.azul], epica: ['Épica', '#c084fc'], lendaria: ['Lendária', COR.ouro] };
let jogo = { missoes: [], insignias: [] };
async function carregarJogo() {
  const r = await rpc('admin_missoes');
  if (r.status !== 200) { falhou(r); return false; }
  jogo = r.json || { missoes: [], insignias: [] }; return true;
}
const okImg = (u) => /^https:\/\/xzdqvdrwvozoedmjwwhm\.supabase\.co\/storage\/v1\/object\/public\/jogo\//.test(String(u || ''));
const hex = (i, g, tam) => okImg(i.imagem) ? '<div class="ins-solta' + (g ? ' g' : '') + '"' + (tam ? ' style="width:' + tam + 'px;height:' + tam + 'px"' : '') + '><img src="' + esc(i.imagem) + '" alt=""></div>' : '<div class="hex' + (g ? ' g' : '') + '" style="background:linear-gradient(160deg,' + esc(i.cor2 || '#fbbf24') + ',' + esc(i.cor1 || '#b45309') + ')">' + (okImg(i.imagem) ? '<img class="ins-img" src="' + esc(i.imagem) + '" alt="">' : '<span class="mi">' + esc(i.icone || 'military_tech') + '</span>') + '</div>';
const micH = (m, tam) => '<div class="mic" style="' + (tam ? 'width:' + tam + 'px;height:' + tam + 'px;border-radius:' + Math.round(tam * .3) + 'px;margin:0 auto;' : '') + 'background:linear-gradient(135deg,' + esc(m.cor1) + ',' + esc(m.cor2) + ')">' + (okImg(m.imagem) ? '<img class="mis-img" src="' + esc(m.imagem) + '" alt="">' : '<span class="mi"' + (tam ? ' style="font-size:' + Math.round(tam / 2) + 'px"' : '') + '>' + esc(m.icone || 'flag') + '</span>') + '</div>';
/* imagem da missão/insígnia: reduz para 256 px e envia para a pasta pública "jogo" do Supabase */
function reduzirImagem(arq) {
  return new Promise((ok, erro) => {
    const r = new FileReader();
    r.onload = () => { const im = new Image(); im.onload = () => {
      const n = 256, c = document.createElement('canvas'); c.width = n; c.height = n; const g = c.getContext('2d');
      const e = Math.max(n / im.naturalWidth, n / im.naturalHeight), w = im.naturalWidth * e, h = im.naturalHeight * e;
      g.drawImage(im, (n - w) / 2, (n - h) / 2, w, h);
      c.toBlob((b) => b ? ok(b) : erro(new Error('não deu para ler a imagem')), 'image/webp', 0.9);
    }; im.onerror = () => erro(new Error('arquivo não é uma imagem')); im.src = r.result; };
    r.onerror = () => erro(new Error('não deu para ler o arquivo')); r.readAsDataURL(arq);
  });
}
async function enviarImagem(arq, pasta) {
  const blob = await reduzirImagem(arq);
  const t = await token(); if (!t) throw new Error('sessão expirada');
  const nome = pasta + '/' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7) + '.webp';
  const r = await fetch(URL_SB + '/storage/v1/object/jogo/' + nome, { method: 'POST', headers: { apikey: CHAVE, Authorization: 'Bearer ' + t, 'Content-Type': 'image/webp', 'x-upsert': 'true' }, body: blob });
  if (!r.ok) { const j = await r.json().catch(() => ({})); throw new Error(j.message || j.error || ('erro ' + r.status)); }
  return URL_SB + '/storage/v1/object/public/jogo/' + nome;
}
function campoImagem(v) {
  return '<label class="full">Imagem (opcional: aparece no lugar do ícone)<input type="hidden" name="imagem" value="' + esc(v || '') + '"><div class="img-up"><span class="img-pv" id="img-pv">' + (okImg(v) ? '<img src="' + esc(v) + '" alt="">' : '<span class="mi">image</span>') + '</span>'
    + '<button type="button" class="bt sm" data-imgesc="1"><span class="mi">upload</span>' + (v ? 'Trocar imagem' : 'Enviar imagem') + '</button>' + (v ? '<button type="button" class="bt sm ghost" data-imgtira="1"><span class="mi">close</span>Tirar</button>' : '')
    + '<input type="file" id="img-arq" accept="image/png,image/jpeg,image/webp,image/gif" hidden><span class="muted small">PNG, JPG ou WEBP · fica quadrada (256 px)</span></div></label>';
}
async function trocarImagem(arq) {
  const f = $('fm'); if (!f || !arq) return;
  const missao = !!f.querySelector('[name=titulo]');
  toast('Enviando imagem…');
  try {
    const url = await enviarImagem(arq, missao ? 'missoes' : 'insignias');
    f.querySelector('[name=imagem]').value = url;
    $('img-pv').innerHTML = '<img src="' + esc(url) + '" alt="">';
    toast('Imagem enviada. Clique em Salvar para usar.');
    missao ? prevMissao() : prevInsignia();
  } catch (e) {
    toast('Não deu para enviar a imagem: ' + e.message, true);
  }
}
async function missoes() {
  if (!(await carregarJogo()) || atual !== 'missoes') return;
  const l = jogo.missoes || [];
  const ac = l.reduce((a, m) => a + m.aceitas, 0), co = l.reduce((a, m) => a + m.concluidas, 0);
  $('conteudo').innerHTML = '<div class="barra"><button class="bt pri" data-mnova="1"><span class="mi">add</span>Nova missão</button><span class="muted small">As missões ativas aparecem na aba Missões do app. O progresso é contado no celular desde que a pessoa aceita.</span></div>'
    + '<div class="grid k3">' + kpi('flag', COR.roxo, 'Missões ativas', num(l.filter((m) => m.ativa).length), num(l.length) + ' no total') + kpi('pending_actions', COR.azul, 'Em andamento agora', num(ac), 'pessoas fazendo alguma missão') + kpi('emoji_events', COR.ouro, 'Missões concluídas', num(co), 'insígnias entregues pelas missões') + '</div>'
    + '<div class="cards mt">' + (l.length ? l.map((m) => {
      const ins = jogo.insignias.find((i) => i.id === m.insignia_id), d = DIFS[m.dificuldade] || DIFS.media, t = TIPOS[m.tipo] || ['', ''];
      return '<div class="card mcard' + (m.ativa ? '' : ' off') + '"><div class="top2">' + micH(m) + '<div><b>' + esc(m.titulo) + '</b><div class="rod" style="margin-top:4px"><span class="chip" style="color:' + d[1] + ';border-color:' + alfa(d[1], .4) + ';background:' + alfa(d[1], .1) + '">' + d[0] + '</span>' + (m.ativa ? '' : '<span class="chip cinza">Desativada</span>') + (m.exige_assistente ? '<span class="chip rota"><span class="mi">route</span>Assistente ligado</span>' : '') + (m.livre ? '<span class="chip"><span class="mi">all_inclusive</span>Qualquer momento</span>' : '<span class="chip cinza">Fila nº ' + esc(m.ordem) + '</span>') + '</div></div></div>'
        + '<p>' + esc(m.descricao || '') + '</p>'
        + '<div class="rod small muted"><span class="mi" style="font-size:16px">flag</span>' + num(m.meta, m.meta % 1 ? 1 : 0) + ' ' + t[1] + (m.prazo_dias ? ' · em ' + m.prazo_dias + ' dias' : '') + '</div>'
        + '<div class="rod small">' + (ins ? '<span class="chip pro"><span class="mi">military_tech</span>' + esc(ins.nome) + '</span>' : '<span class="chip cinza">Sem insígnia</span>') + '<span class="chip on">' + num(m.aceitas) + ' fazendo</span><span class="chip rota">' + num(m.concluidas) + ' concluíram</span></div>'
        + '<div class="acts"><button class="bt sm" data-medit="' + m.id + '"><span class="mi">edit</span>Editar</button><button class="bt sm" data-mativa="' + m.id + '"><span class="mi">' + (m.ativa ? 'visibility_off' : 'visibility') + '</span>' + (m.ativa ? 'Desativar' : 'Ativar') + '</button><button class="bt sm verm" data-mdel="' + m.id + '"><span class="mi">delete</span></button></div></div>';
    }).join('') : '<div class="card vazio"><span class="mi">flag</span>Nenhuma missão ainda. Crie a primeira!</div>') + '</div>';
  marcarAtualizado();
}
function editorMissao(m) {
  m = m || { titulo: '', descricao: '', icone: 'flag', imagem: '', cor1: '#6d28d9', cor2: '#a78bfa', dificuldade: 'media', tipo: 'rotas_todas', meta: 10, prazo_dias: '', insignia_id: '', exige_assistente: false, livre: false, premio_dias: 0, ativa: true, ordem: (jogo.missoes.length + 1) };
  abrirModal('<h3><span class="mi">flag</span>' + (m.id ? 'Editar missão' : 'Nova missão') + '</h3><div class="ed"><div class="form" id="fm">'
    + '<label class="full">Título<input name="titulo" maxlength="40" value="' + esc(m.titulo) + '" placeholder="Ex.: Maratonista"></label>'
    + '<label class="full">Descrição (o que a pessoa precisa fazer)<textarea name="descricao" maxlength="400" placeholder="Ex.: Rode 300 km em rotas dentro de 7 dias.">' + esc(m.descricao) + '</textarea></label>'
    + '<label>O que conta<select name="tipo">' + Object.keys(TIPOS).map((k) => '<option value="' + k + '"' + (m.tipo === k ? ' selected' : '') + '>' + TIPOS[k][0] + '</option>').join('') + '</select></label>'
    + '<label>Meta (quanto precisa)<input name="meta" type="number" min="0.01" step="any" value="' + esc(m.meta) + '"></label>'
    + '<label>Prazo depois de aceitar (dias)<input name="prazo_dias" type="number" min="1" max="365" value="' + esc(m.prazo_dias || '') + '" placeholder="sem prazo"></label>'
    + '<label>Dificuldade<select name="dificuldade">' + Object.keys(DIFS).map((k) => '<option value="' + k + '"' + (m.dificuldade === k ? ' selected' : '') + '>' + DIFS[k][0] + '</option>').join('') + '</select></label>'
    + '<label>Insígnia que ganha<select name="insignia_id"><option value="">Nenhuma</option>' + jogo.insignias.map((i) => '<option value="' + i.id + '"' + (Number(m.insignia_id) === i.id ? ' selected' : '') + '>' + esc(i.nome) + (i.ativa ? '' : ' (desativada)') + '</option>').join('') + '</select></label>'
    + '<label>Ordem na fila<input name="ordem" type="number" value="' + esc(m.ordem) + '"></label>'
    + '<label>Prêmio em dias de Pro<input name="premio_dias" type="number" min="0" max="365" value="' + esc(m.premio_dias || 0) + '" placeholder="0 = só a insígnia"></label>'
    + '<label class="full chk"><input name="exige_assistente" type="checkbox"' + (m.exige_assistente ? ' checked' : '') + '><span><b>Só conta com o assistente de rotas ativo</b><small>Para aceitar, o assistente precisa estar ligado, e só contam as rotas que o assistente registrou sozinho (rotas lançadas à mão não contam).</small></span></label>'
    + '<label class="full chk"><input name="livre" type="checkbox"' + (m.livre ? ' checked' : '') + '><span><b>Mostrar a qualquer momento</b><small>Fica fora da fila: aparece sempre. Sem marcar, a missão entra na fila pela ordem e só aparece quando a pessoa concluir a anterior.</small></span></label>'
    + '<div class="cores full"><label>Cor 1<input name="cor1" type="color" value="' + esc(m.cor1) + '"></label><label>Cor 2<input name="cor2" type="color" value="' + esc(m.cor2) + '"></label><label style="display:flex;align-items:center;gap:8px;padding-top:22px"><input name="ativa" type="checkbox"' + (m.ativa ? ' checked' : '') + '>Ativa no app</label></div>'
    + campoImagem(m.imagem)
    + '<label class="full">Ícone<input type="hidden" name="icone" value="' + esc(m.icone) + '"><div class="icones" id="icones">' + ICONES.map((i) => '<button type="button" data-ico="' + i + '" class="' + (m.icone === i ? 'on' : '') + '" title="' + i + '"><span class="mi">' + i + '</span></button>').join('') + '</div></label>'
    + '</div><div><p class="muted small" style="margin-bottom:8px">COMO FICA NO APP</p><div class="prev" id="prev"></div></div></div>'
    + '<div class="acoes"><button class="bt ghost" data-fechar="1">Cancelar</button><button class="bt pri" data-msalvar="' + (m.id || '') + '"><span class="mi">check</span>Salvar missão</button></div>');
  prevMissao();
}
function lerForm() { const o = {}; $('fm').querySelectorAll('[name]').forEach((e) => { o[e.name] = e.type === 'checkbox' ? e.checked : e.value; }); return o; }
function prevMissao() {
  const f = lerForm(), d = DIFS[f.dificuldade] || DIFS.media, ins = jogo.insignias.find((i) => i.id === Number(f.insignia_id)), t = TIPOS[f.tipo] || ['', ''];
  $('prev').innerHTML = micH(f, 80)
    + '<b>' + esc(f.titulo || 'Título da missão') + '</b><p>' + esc(f.descricao || 'Descrição da missão') + '</p>'
    + '<div class="rod" style="justify-content:center;display:flex;gap:6px;flex-wrap:wrap;margin-top:12px"><span class="chip" style="color:' + d[1] + ';border-color:' + alfa(d[1], .4) + '">' + d[0] + '</span><span class="chip cinza">Meta: ' + esc(f.meta || 0) + ' ' + t[1] + '</span>' + (f.prazo_dias ? '<span class="chip cinza">' + esc(f.prazo_dias) + ' dias</span>' : '') + (f.exige_assistente ? '<span class="chip rota">Assistente ligado</span>' : '') + '</div>'
    + (ins ? '<div style="display:flex;align-items:center;gap:10px;margin-top:14px;padding:10px;border-radius:14px;background:rgba(250,204,21,.06);border:1px solid rgba(250,204,21,.25);text-align:left">' + (okImg(ins.imagem) ? hex(ins, false, 44) : hex(ins).replace('class="hex"', 'class="hex" style="width:44px;height:48px;background:linear-gradient(160deg,' + esc(ins.cor2) + ',' + esc(ins.cor1) + ')"')) + '<div><small style="color:' + (RARS[ins.raridade] || RARS.comum)[1] + ';font-weight:800;font-size:10px">INSÍGNIA ' + (RARS[ins.raridade] || RARS.comum)[0].toUpperCase() + '</small><b style="font-size:14px;margin:0">' + esc(ins.nome) + '</b></div></div>' : '')
    + '<div style="margin-top:14px;height:42px;border-radius:13px;background:linear-gradient(135deg,#6d28d9,#a78bfa);display:flex;align-items:center;justify-content:center;font-weight:800">Aceitar missão</div>';
}
async function salvarMissao(id) {
  const f = lerForm();
  if (f.titulo.trim().length < 2) return toast('Escreva o título.', true);
  if (!(Number(f.meta) > 0)) return toast('A meta precisa ser maior que zero.', true);
  const d = Object.assign(f, { id: id || '', titulo: f.titulo.trim() });
  const r = await rpc('admin_missao_salvar', { d });
  if (r.status === 200) { fecharModal(); toast('Missão salva. Já aparece no app.'); missoes(); } else toast(erroTxt(r), true);
}
async function ativarMissao(id) {
  const m = jogo.missoes.find((x) => x.id === id); if (!m) return;
  const r = await rpc('admin_missao_salvar', { d: Object.assign({}, m, { ativa: !m.ativa }) });
  if (r.status === 200) { toast(m.ativa ? 'Missão desativada (sai do app)' : 'Missão ativada'); missoes(); } else toast(erroTxt(r), true);
}
async function apagarMissao(id) {
  const m = jogo.missoes.find((x) => x.id === id); if (!m) return;
  if (!confirm('Apagar a missão "' + m.titulo + '"? Quem já ganhou a insígnia continua com ela.')) return;
  const r = await rpc('admin_missao_apagar', { p_id: id });
  if (r.status === 200) { toast('Missão apagada'); missoes(); } else toast(erroTxt(r), true);
}
async function insignias() {
  if (!(await carregarJogo()) || atual !== 'insignias') return;
  const l = jogo.insignias || [];
  $('conteudo').innerHTML = '<div class="barra"><button class="bt pri" data-inova="1"><span class="mi">add</span>Nova insígnia</button><span class="muted small">A insígnia aparece no Perfil do app (bloqueada até a pessoa concluir a missão que dá ela).</span></div>'
    + '<div class="grid k3">' + kpi('military_tech', COR.ouro, 'Insígnias', num(l.length), num(l.filter((i) => i.ativa).length) + ' ativas') + kpi('emoji_events', COR.verde, 'Entregues', num(l.reduce((a, i) => a + i.ganhas, 0)), 'somando todas as pessoas') + kpi('link', COR.roxo, 'Sem missão', num(l.filter((i) => !jogo.missoes.some((m) => m.insignia_id === i.id)).length), 'ninguém consegue ganhar ainda') + '</div>'
    + '<div class="cards mt">' + (l.length ? l.map((i) => {
      const rr = RARS[i.raridade] || RARS.comum, ms = jogo.missoes.filter((m) => m.insignia_id === i.id);
      return '<div class="card mcard' + (i.ativa ? '' : ' off') + '"><div class="top2">' + hex(i) + '<div><b>' + esc(i.nome) + '</b><div class="rod" style="margin-top:4px"><span class="chip" style="color:' + rr[1] + ';border-color:' + alfa(rr[1], .4) + ';background:' + alfa(rr[1], .1) + '">' + rr[0] + '</span>' + (i.ativa ? '' : '<span class="chip cinza">Desativada</span>') + '</div></div></div>'
        + '<p>' + esc(i.descricao || '') + '</p><div class="rod small"><span class="chip rota">' + num(i.ganhas) + ' ganharam</span>' + (ms.length ? ms.map((m) => '<span class="chip on">' + esc(m.titulo) + '</span>').join('') : '<span class="chip verm">Nenhuma missão dá esta</span>') + '</div>'
        + '<div class="acts"><button class="bt sm" data-iedit="' + i.id + '"><span class="mi">edit</span>Editar</button><button class="bt sm verm" data-idel="' + i.id + '"><span class="mi">delete</span></button></div></div>';
    }).join('') : '<div class="card vazio"><span class="mi">military_tech</span>Nenhuma insígnia ainda. Crie a primeira!</div>') + '</div>';
  marcarAtualizado();
}
function editorInsignia(i) {
  i = i || { nome: '', descricao: '', icone: 'military_tech', imagem: '', cor1: '#b45309', cor2: '#fbbf24', raridade: 'comum', ativa: true, ordem: (jogo.insignias.length + 1) };
  abrirModal('<h3><span class="mi">military_tech</span>' + (i.id ? 'Editar insígnia' : 'Nova insígnia') + '</h3><div class="ed"><div class="form" id="fm">'
    + '<label class="full">Nome<input name="nome" maxlength="40" value="' + esc(i.nome) + '" placeholder="Ex.: Rei da estrada"></label>'
    + '<label class="full">Descrição<textarea name="descricao" maxlength="300" placeholder="Ex.: Rodou 1.000 km em um mês.">' + esc(i.descricao) + '</textarea></label>'
    + '<label>Raridade<select name="raridade">' + Object.keys(RARS).map((k) => '<option value="' + k + '"' + (i.raridade === k ? ' selected' : '') + '>' + RARS[k][0] + '</option>').join('') + '</select></label>'
    + '<label>Ordem no Perfil<input name="ordem" type="number" value="' + esc(i.ordem) + '"></label>'
    + '<div class="cores full"><label>Cor de baixo<input name="cor1" type="color" value="' + esc(i.cor1) + '"></label><label>Cor de cima<input name="cor2" type="color" value="' + esc(i.cor2) + '"></label><label style="display:flex;align-items:center;gap:8px;padding-top:22px"><input name="ativa" type="checkbox"' + (i.ativa ? ' checked' : '') + '>Ativa</label></div>'
    + campoImagem(i.imagem)
    + '<label class="full">Ícone<input type="hidden" name="icone" value="' + esc(i.icone) + '"><div class="icones" id="icones">' + ICONES.map((x) => '<button type="button" data-ico="' + x + '" class="' + (i.icone === x ? 'on' : '') + '" title="' + x + '"><span class="mi">' + x + '</span></button>').join('') + '</div></label>'
    + '</div><div><p class="muted small" style="margin-bottom:8px">COMO FICA NO PERFIL</p><div class="prev" id="prev" style="background:radial-gradient(260px 160px at 50% 0%,rgba(250,204,21,.18),transparent),linear-gradient(180deg,#1a160a,#0b1410 70%);border-color:rgba(250,204,21,.35)"></div></div></div>'
    + '<div class="acoes"><button class="bt ghost" data-fechar="1">Cancelar</button><button class="bt pri" data-isalvar="' + (i.id || '') + '"><span class="mi">check</span>Salvar insígnia</button></div>');
  prevInsignia();
}
function prevInsignia() {
  const f = lerForm(), rr = RARS[f.raridade] || RARS.comum;
  $('prev').innerHTML = '<div style="display:flex;justify-content:center">' + hex(f, true) + '</div><p style="font-size:11px;font-weight:900;letter-spacing:.12em;color:' + rr[1] + ';margin-top:12px">INSÍGNIA ' + rr[0].toUpperCase() + '</p><b style="margin-top:2px">' + esc(f.nome || 'Nome da insígnia') + '</b><p>' + esc(f.descricao || 'Descrição') + '</p>';
}
async function salvarInsignia(id) {
  const f = lerForm();
  if (f.nome.trim().length < 2) return toast('Escreva o nome.', true);
  const r = await rpc('admin_insignia_salvar', { d: Object.assign(f, { id: id || '', nome: f.nome.trim() }) });
  if (r.status === 200) { fecharModal(); toast('Insígnia salva.'); insignias(); } else toast(erroTxt(r), true);
}
async function apagarInsignia(id) {
  const i = jogo.insignias.find((x) => x.id === id); if (!i) return;
  if (!confirm('Apagar a insígnia "' + i.nome + '"? Se alguém já ganhou, ela só fica desativada.')) return;
  const r = await rpc('admin_insignia_apagar', { p_id: id });
  if (r.status === 200) { toast(r.json && r.json.desativada ? 'Já foi ganha por alguém: ficou desativada' : 'Insígnia apagada'); insignias(); } else toast(erroTxt(r), true);
}

/* ═══════════ VENDAS ═══════════ */
let venDias = 30;
async function vendas() {
  const r = await rpc('admin_vendas', { dias: venDias });
  if (atual !== 'vendas') return;
  if (r.status !== 200) return falhou(r);
  const v = r.json || {}, l = v.lista || [];
  const STV = { aprovado: ['Aprovado', 'rota'], aplicado: ['Aprovado', 'rota'], aguardando: ['Aguardando', 'on'], pendente: ['Aguardando', 'on'], recusado: ['Recusado', 'verm'], estornado: ['Estornado', 'verm'], reembolsado: ['Reembolsado', 'verm'] };
  $('conteudo').innerHTML = '<div class="barra"><div class="seg">' + [7, 30, 90, 365].map((d) => '<button data-vdias="' + d + '" class="' + (venDias === d ? 'on' : '') + '">' + (d === 365 ? '1 ano' : d + ' dias') + '</button>').join('') + '</div><span class="muted small">lista dos pagamentos (até 100)</span></div>'
    + '<div class="grid k4">' + kpi('today', COR.ouro, 'Hoje', brl(v.hoje && v.hoje.total), num(v.hoje && v.hoje.qtd) + ' pagamentos') + kpi('calendar_month', COR.lar, 'Este mês', brl(v.mes && v.mes.total), num(v.mes && v.mes.qtd) + ' pagamentos') + kpi('savings', COR.verde, 'Desde o início', brl(v.geral && v.geral.total), num(v.geral && v.geral.qtd) + ' pagamentos') + kpi('undo', COR.verm || '#f87171', 'Estornos', num(v.estornos), 'reembolsos e contestações') + '</div>'
    + '<div class="card mt"><table class="tab"><thead><tr><th>DATA</th><th>CLIENTE</th><th>ID</th><th>PLANO</th><th>MÉTODO</th><th>SITUAÇÃO</th><th class="num">VALOR</th></tr></thead><tbody>'
    + (l.length ? l.map((p) => { const st = p.revertido_em ? ['Estornado', 'verm'] : (p.aplicado_em ? ['Aprovado', 'rota'] : (STV[p.status] || [p.status || '—', 'cinza'])); return '<tr><td class="muted">' + dataHora(p.criado_em) + '</td><td><div class="pessoa">' + ava(p.nome, p.conta_id) + '<div><b>' + esc(p.nome || '—') + '</b><small>' + esc(p.email || '') + '</small></div></div></td><td class="idf">' + idFmt(p.conta_id) + '</td><td>' + esc(p.plano || '—') + (p.dias ? ' <span class="muted small">(' + p.dias + ' dias)</span>' : '') + '</td><td class="muted">' + esc(p.metodo || '—') + '</td><td><span class="chip ' + st[1] + '">' + esc(st[0]) + '</span></td><td class="num"><b>' + brl(p.valor) + '</b></td></tr>'; }).join('')
      : '<tr><td colspan="7"><div class="vazio"><span class="mi">receipt_long</span>Nenhum pagamento neste período.</div></td></tr>')
    + '</tbody></table></div>';
  marcarAtualizado();
}

/* ═══════════ NOTIFICAÇÕES ═══════════ */
const nt = { aba: 'enviar', publico: 'todos', conta: '', quando: 'agora', titulo: '', texto: '', data: '' };
const PUBLICOS = { todos: 'Todos', pro: 'Só Pro', free: 'Só Free', usuario: 'Sem conta', conta: 'Uma pessoa (ID)' };
const STN = { agendada: ['Agendada', 'on'], enviada: ['Enviada', 'rota'], cancelada: ['Cancelada', 'cinza'], erro: ['Erro', 'verm'] };
function abasNt() { return '<div class="barra"><div class="seg">' + [['enviar', 'Nova notificação'], ['lista', 'Agendadas e enviadas'], ['anuncios', 'Anúncios e popups']].map((t) => '<button data-nab="' + t[0] + '" class="' + (nt.aba === t[0] ? 'on' : '') + '">' + t[1] + '</button>').join('') + '</div></div>'; }
function dtLocal(d) { const p = (n) => String(n).padStart(2, '0'); return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + 'T' + p(d.getHours()) + ':' + p(d.getMinutes()); }
async function notificacoes() {
  if (nt.aba === 'lista') return notifLista();
  if (nt.aba === 'anuncios') return anuncios();
  if (!nt.data) { const d = new Date(Date.now() + 3600e3); d.setMinutes(0); nt.data = dtLocal(d); }
  $('conteudo').innerHTML = abasNt() + '<div class="grid g21"><div class="card"><h3><span class="mi">edit_notifications</span>Escrever notificação</h3><div class="form" id="nf">'
    + '<label class="full">Título<input name="titulo" maxlength="120" value="' + esc(nt.titulo) + '" placeholder="Ex.: 🎁 Fim de semana com Pro em dobro"></label>'
    + '<label class="full">Mensagem<textarea name="texto" maxlength="400" placeholder="O que aparece no celular">' + esc(nt.texto) + '</textarea><div class="cont" id="nf-cont">' + nt.texto.length + '/400</div></label>'
    + '<label class="full">Para quem<div class="opc">' + Object.keys(PUBLICOS).map((k) => '<button type="button" data-npub="' + k + '" class="' + (nt.publico === k ? 'on' : '') + '">' + PUBLICOS[k] + '</button>').join('') + '</div></label>'
    + (nt.publico === 'conta' ? '<label class="full">ID da pessoa (8 números)<input name="conta" inputmode="numeric" maxlength="9" value="' + esc(nt.conta) + '" placeholder="0000 0000"></label>' : '')
    + '<label class="full">Quando<div class="opc"><button type="button" data-nqd="agora" class="' + (nt.quando === 'agora' ? 'on' : '') + '"><span class="mi" style="font-size:15px">bolt</span> Enviar agora</button><button type="button" data-nqd="agendar" class="' + (nt.quando === 'agendar' ? 'on' : '') + '"><span class="mi" style="font-size:15px">schedule</span> Agendar</button></div></label>'
    + (nt.quando === 'agendar' ? '<label class="full">Data e hora (horário de Brasília deste computador)<input name="data" type="datetime-local" value="' + esc(nt.data) + '" min="' + dtLocal(new Date()) + '"></label>' : '')
    + '</div><div class="acoes" style="display:flex;justify-content:flex-end;gap:10px;margin-top:16px"><button class="bt pri" data-nenviar="1" id="nf-bt"><span class="mi">' + (nt.quando === 'agora' ? 'send' : 'schedule_send') + '</span>' + (nt.quando === 'agora' ? 'Enviar agora' : 'Agendar notificação') + '</button></div></div>'
    + '<div class="card"><h3><span class="mi">smartphone</span>Como aparece no celular</h3><div class="cel"><div class="barra-st"><span>' + (nt.quando === 'agendar' && nt.data ? new Date(nt.data).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' }) : new Date().toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })) + '</span><span>▲ ◼ 100%</span></div>'
    + '<div class="notif"><span class="nic"><img src="notif-icone.png" alt=""></span><div style="min-width:0;flex:1"><div class="napp">Prismo Drive <i>•</i> agora</div><b id="pv-t">' + esc(nt.titulo || 'Título da notificação') + '</b><p id="pv-x">' + esc(nt.texto || 'A mensagem aparece aqui.') + '</p></div></div></div>'
    + '<p class="muted small" style="margin-top:12px;text-align:center">Chega para quem tem as notificações do Prismo Drive ligadas. "Sem conta" = quem usa o app sem entrar numa conta.</p></div></div>';
}
function lerNf() { const f = $('nf'); if (!f) return; f.querySelectorAll('[name]').forEach((e) => { nt[e.name] = e.value; }); }
async function enviarNotif() {
  lerNf();
  if (!nt.titulo.trim()) return toast('Escreva o título.', true);
  if (nt.texto.trim().length < 2) return toast('Escreva a mensagem.', true);
  if (nt.publico === 'conta' && !/^\d{8}$/.test(nt.conta.replace(/\D/g, ''))) return toast('Digite o ID da pessoa (8 números).', true);
  let quando = null;
  if (nt.quando === 'agendar') { const d = new Date(nt.data); if (!nt.data || isNaN(d)) return toast('Escolha a data e a hora.', true); if (d < Date.now() - 60000) return toast('Escolha uma hora no futuro.', true); quando = d.toISOString(); }
  const alvo = nt.publico === 'conta' ? 'o ID ' + nt.conta : PUBLICOS[nt.publico].toLowerCase();
  if (!confirm((quando ? 'Agendar para ' + new Date(quando).toLocaleString('pt-BR') : 'Enviar AGORA') + ' para ' + alvo + '?\n\n' + nt.titulo + '\n' + nt.texto)) return;
  const b = $('nf-bt'); b.disabled = true;
  const r = await rpc('admin_notif_enviar', { p_titulo: nt.titulo.trim(), p_texto: nt.texto.trim(), p_publico: nt.publico, p_conta: nt.publico === 'conta' ? nt.conta : null, p_quando: quando });
  b.disabled = false;
  if (r.status === 200) {
    toast(quando ? 'Notificação agendada.' : 'Notificação enviada!');
    Object.assign(nt, { titulo: '', texto: '', conta: '', aba: 'lista' }); notificacoes();
  } else {
    const m = JSON.stringify(r.json || '');
    toast(m.includes('conta_nao_encontrada') ? 'Nenhuma conta com esse ID.' : erroTxt(r), true);
  }
}
async function notifLista() {
  const r = await rpc('admin_notif_lista');
  if (atual !== 'notificacoes') return;
  if (r.status !== 200) return falhou(r);
  const l = r.json || [], ag = l.filter((n) => n.status === 'agendada').length;
  badge('nb-notif', ag);
  $('conteudo').innerHTML = abasNt() + '<div class="card"><table class="tab"><thead><tr><th>QUANDO</th><th>NOTIFICAÇÃO</th><th>PARA</th><th>SITUAÇÃO</th><th class="num">CELULARES</th><th></th></tr></thead><tbody>'
    + (l.length ? l.map((n) => '<tr><td class="muted">' + dataHora(n.enviada_em || n.enviar_em) + (n.status === 'agendada' ? '<div class="small" style="color:#93c5fd">' + emQuanto(n.enviar_em) + '</div>' : '') + '</td><td><b>' + esc(n.titulo) + '</b><div class="muted small" style="max-width:420px">' + esc(n.texto) + '</div></td>'
      + '<td>' + (n.publico === 'conta' ? '<span class="chip on">' + esc(n.nome || 'ID') + ' · ' + idFmt(n.conta_id) + '</span>' : '<span class="chip cinza">' + esc(PUBLICOS[n.publico] || n.publico) + '</span>') + '</td>'
      + '<td><span class="chip ' + (STN[n.status] || STN.enviada)[1] + '">' + (STN[n.status] || STN.enviada)[0] + '</span>' + (n.erro ? '<div class="small" style="color:#fca5a5;max-width:200px">' + esc(n.erro) + '</div>' : '') + '</td>'
      + '<td class="num">' + (n.entregues != null ? num(n.entregues) : (n.status === 'enviada' ? (n.publico === 'conta' ? '—' : 'por tópico') : '—')) + '</td>'
      + '<td class="num">' + (n.status === 'agendada' ? '<button class="bt sm verm" data-ncanc="' + n.id + '">Cancelar</button>' : '<button class="bt sm ghost" data-nrep="' + n.id + '" title="Usar de novo"><span class="mi">content_copy</span></button>') + '</td></tr>').join('')
      : '<tr><td colspan="6"><div class="vazio"><span class="mi">notifications_none</span>Nenhuma notificação enviada ainda.</div></td></tr>')
    + '</tbody></table></div><p class="muted small mt">As agendadas saem sozinhas no minuto marcado, mesmo com este programa fechado.</p>';
  notifLista._l = l;
  marcarAtualizado();
}
function emQuanto(d) { const m = Math.round((new Date(d) - Date.now()) / 60000); if (m <= 0) return 'saindo agora'; if (m < 60) return 'em ' + m + ' min'; if (m < 1440) return 'em ' + Math.round(m / 60) + ' h'; return 'em ' + Math.round(m / 1440) + ' dias'; }
async function cancelarNotif(id) {
  if (!confirm('Cancelar esta notificação agendada?')) return;
  const r = await rpc('admin_notif_cancelar', { p_id: id });
  if (r.status === 200) { toast('Notificação cancelada'); notifLista(); } else toast(erroTxt(r), true);
}
/* anúncios recorrentes (o app agenda no próprio celular: dias da semana e turnos) */
const an = { publico: 'todos', semana: [0, 1, 2, 3, 4, 5, 6], turnos: ['manha', 'tarde', 'noite'], dias: 5, titulo: '', texto: '', inicio: '', imagem: '', popup: true };
const SEM = ['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb'], TURN = { manha: 'Manhã', tarde: 'Tarde', noite: 'Noite' };
async function anuncios() {
  const r = await rpc('admin_avisos');
  if (atual !== 'notificacoes') return;
  if (r.status !== 200) return falhou(r);
  const l = r.json || [], hj = new Date().toISOString().slice(0, 10);
  if (!an.inicio || an.inicio < hj) an.inicio = hj;
  $('conteudo').innerHTML = abasNt() + '<div class="grid g21"><div class="card"><h3><span class="mi">campaign</span>Novo anúncio</h3><p class="muted small" style="margin:-6px 0 12px">O servidor manda a notificação nos turnos escolhidos (8h, 13h e 19h), mesmo com o app fechado. Com imagem, quem abrir o app no turno vê o popup (fecha depois de 10 segundos), sem atrapalhar o odômetro, a atualização ou outras janelas.</p>'
    + '<div class="grid" style="grid-template-columns:1fr 230px;gap:18px;align-items:start"><div class="form" id="af">'
    + '<label class="full">Título<input name="titulo" maxlength="60" placeholder="Prismo Drive" value="' + esc(an.titulo) + '"></label>'
    + '<label class="full">Mensagem<textarea name="texto" maxlength="300" placeholder="Ex.: Pro Anual com 53% OFF só até domingo!">' + esc(an.texto) + '</textarea></label>'
    + '<label class="full">Imagem do popup (opcional)<div class="img-up"><span class="img-pv an-pv" id="an-pv">' + (okImg(an.imagem) ? '<img src="' + esc(an.imagem) + '" alt="">' : '<span class="mi">image</span>') + '</span>'
    + '<button type="button" class="bt sm" data-anesc="1"><span class="mi">upload</span>' + (an.imagem ? 'Trocar imagem' : 'Enviar imagem') + '</button>' + (an.imagem ? '<button type="button" class="bt sm ghost" data-antira="1"><span class="mi">close</span>Tirar</button>' : '')
    + '<input type="file" id="an-arq" accept="image/png,image/jpeg,image/webp" hidden><span class="muted small">PNG, JPG ou WEBP · em pé ou quadrada fica melhor</span></div></label>'
    + '<label class="full chk"><input name="popup" type="checkbox"' + (an.popup ? ' checked' : '') + '><span><b>Mostrar popup ao abrir o app</b><small>Uma vez em cada turno escolhido, enquanto o anúncio estiver valendo. Sem imagem, o popup mostra só o título e a mensagem.</small></span></label>'
    + '<label>Começa em<input name="inicio" type="date" value="' + esc(an.inicio) + '" min="' + hj + '"></label>'
    + '<label>Por quantos dias<input name="dias" type="number" min="1" max="60" value="' + an.dias + '"></label>'
    + '<label class="full">Para quem<div class="opc">' + ['todos', 'pro', 'free', 'usuario'].map((k) => '<button type="button" data-apub="' + k + '" class="' + (an.publico === k ? 'on' : '') + '">' + PUBLICOS[k] + '</button>').join('') + '</div></label>'
    + '<label class="full">Dias da semana<div class="opc">' + SEM.map((d, i) => '<button type="button" data-asem="' + i + '" class="' + (an.semana.includes(i) ? 'on' : '') + '">' + d + '</button>').join('') + '</div></label>'
    + '<label class="full">Vezes ao dia (turnos)<div class="opc">' + Object.keys(TURN).map((k) => '<button type="button" data-atur="' + k + '" class="' + (an.turnos.includes(k) ? 'on' : '') + '">' + TURN[k] + ' · ' + { manha: '8h', tarde: '13h', noite: '19h' }[k] + '</button>').join('') + '</div></label>'
    + '<p class="muted small full" id="an-resumo">' + anResumo() + '</p>'
    + '</div><div><p class="muted small" style="margin-bottom:8px">COMO FICA NO APP</p><div class="an-prev" id="an-prev">' + anPrevia() + '</div></div></div>'
    + '<div style="display:flex;justify-content:flex-end;margin-top:16px"><button class="bt pri" data-apub2="1"><span class="mi">campaign</span>Publicar anúncio</button></div></div>'
    + '<div class="card"><h3><span class="mi">list</span>Anúncios publicados</h3><div class="grid">' + (l.length ? l.map((a) => {
      const p = a.inicio.split('-'), fim = new Date(+p[0], +p[1] - 1, +p[2] + a.dias - 1).toISOString().slice(0, 10);
      const st = !a.ativo ? ['Encerrado', 'cinza'] : (fim < hj ? ['Terminou', 'cinza'] : (a.inicio > hj ? ['Agendado', 'on'] : ['Ativo', 'rota']));
      return '<div style="padding:10px 0;border-bottom:1px solid var(--line);display:flex;gap:10px">' + (okImg(a.imagem) ? '<img src="' + esc(a.imagem) + '" alt="" style="width:54px;height:54px;object-fit:cover;border-radius:10px;flex-shrink:0">' : '')
        + '<div style="flex:1;min-width:0"><div style="display:flex;gap:8px;align-items:center"><span class="chip ' + st[1] + '">' + st[0] + '</span>' + (a.popup ? '<span class="chip">Popup</span>' : '') + '<b style="flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">' + esc(a.titulo) + '</b>' + (a.ativo && fim >= hj ? '<button class="bt sm verm" data-aenc="' + a.id + '">Encerrar</button>' : '') + '</div>'
        + '<div class="muted small" style="margin-top:4px">' + esc(a.texto) + '</div><div class="muted small" style="margin-top:4px">' + esc(PUBLICOS[a.publico] || a.publico) + ' · ' + data(a.inicio) + ' até ' + data(fim) + ' · ' + (a.turnos || []).map((t) => TURN[t] || t).join(', ') + (a.servidor ? ' · ' + num(a.envios || 0) + ' envio(s) do servidor' : '') + '</div></div></div>';
    }).join('') : '<div class="vazio" style="padding:20px">Nenhum anúncio ainda.</div>') + '</div></div></div>';
  marcarAtualizado();
}
function anResumo() {
  const v = an.dias * an.turnos.length, sem = an.semana.length;
  return '<span class="mi" style="font-size:16px;vertical-align:-3px">info</span> ' + (an.turnos.length ? an.turnos.length + 'x ao dia' : 'escolha um turno') + ' por ' + an.dias + ' dia(s)' + (sem < 7 ? ' (só ' + an.semana.map((i) => SEM[i]).join(', ') + ')' : '') + ': até ' + v + ' notificações. Expira e some sozinho depois disso.';
}
function anPrevia() {
  return '<div class="an-pp">' + (okImg(an.imagem) ? '<img src="' + esc(an.imagem) + '" alt="">' : '<div class="an-pp-ic"><span class="mi">campaign</span></div>') + '<span class="an-pp-x">10</span>'
    + '<div class="an-pp-t"><b>' + esc(an.titulo || 'Prismo Drive') + '</b><p>' + esc(an.texto || 'Sua mensagem aparece aqui.') + '</p></div></div>';
}
function lerAf() { const f = $('af'); if (!f) return; f.querySelectorAll('[name]').forEach((e) => { an[e.name] = e.type === 'checkbox' ? e.checked : (e.name === 'dias' ? Math.max(1, Math.min(60, Number(e.value) || 1)) : e.value); }); const pv = $('an-prev'); if (pv) pv.innerHTML = anPrevia(); const rs = $('an-resumo'); if (rs) rs.innerHTML = anResumo(); }
/* imagem do popup: até 1080 px no lado maior, mantém o formato */
function reduzirFoto(arq) {
  return new Promise((ok, erro) => {
    const r = new FileReader();
    r.onload = () => { const im = new Image(); im.onload = () => {
      const e = Math.min(1, 1080 / Math.max(im.naturalWidth, im.naturalHeight)), c = document.createElement('canvas');
      c.width = Math.round(im.naturalWidth * e); c.height = Math.round(im.naturalHeight * e); c.getContext('2d').drawImage(im, 0, 0, c.width, c.height);
      c.toBlob((b) => b ? ok(b) : erro(new Error('não deu para ler a imagem')), 'image/webp', 0.86);
    }; im.onerror = () => erro(new Error('arquivo não é uma imagem')); im.src = r.result; };
    r.onerror = () => erro(new Error('não deu para ler o arquivo')); r.readAsDataURL(arq);
  });
}
async function anImagem(arq) {
  toast('Enviando imagem…');
  try {
    const blob = await reduzirFoto(arq), t = await token(); if (!t) throw new Error('sessão expirada');
    const nome = 'avisos/' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7) + '.webp';
    const r = await fetch(URL_SB + '/storage/v1/object/jogo/' + nome, { method: 'POST', headers: { apikey: CHAVE, Authorization: 'Bearer ' + t, 'Content-Type': 'image/webp', 'x-upsert': 'true' }, body: blob });
    if (!r.ok) { const j = await r.json().catch(() => ({})); throw new Error(j.message || j.error || ('erro ' + r.status)); }
    lerAf(); an.imagem = URL_SB + '/storage/v1/object/public/jogo/' + nome; an.popup = true; toast('Imagem pronta.'); anuncios();
  } catch (e) { toast('Não deu para enviar a imagem: ' + e.message, true); }
}
async function publicarAnuncio() {
  lerAf();
  if ((an.texto || '').trim().length < 3) return toast('Escreva a mensagem.', true);
  if (!an.semana.length || !an.turnos.length) return toast('Escolha pelo menos um dia e um turno.', true);
  if (!confirm('Publicar o anúncio para ' + (PUBLICOS[an.publico] || an.publico) + '? ' + anResumo().replace(/<[^>]+>/g, '').replace(/^\s*info\s*/, ''))) return;
  const r = await rpc('admin_anunciar', { titulo: (an.titulo || '').trim(), texto: an.texto.trim(), publico: an.publico, inicio: an.inicio, dias: an.dias, semana: an.semana.slice().sort(), turnos: an.turnos.slice(), imagem: an.imagem || null, popup: !!an.popup });
  if (r.status === 200) { toast('Anúncio publicado.'); Object.assign(an, { titulo: '', texto: '', imagem: '' }); anuncios(); } else toast(erroTxt(r), true);
}
async function encerrarAnuncio(id) {
  if (!confirm('Encerrar este anúncio?')) return;
  const r = await rpc('admin_encerrar_aviso', { aviso: id });
  if (r.status === 200) { toast('Anúncio encerrado.'); anuncios(); } else toast(erroTxt(r), true);
}

/* ═══════════ CÓDIGOS PRO ═══════════ */
let codGerados = [];
async function codigos() {
  const r = await rpc('admin_codigos');
  if (atual !== 'codigos') return;
  if (r.status !== 200) return falhou(r);
  const l = Array.isArray(r.json) ? r.json : [], livres = l.filter((c) => !c.usado_em).length;
  $('conteudo').innerHTML = '<div class="grid g21"><div class="card"><h3><span class="mi">redeem</span>Gerar códigos</h3><div class="form" id="cf">'
    + '<label>Dias de Pro<select name="dias">' + [7, 30, 90, 180, 365].map((d) => '<option value="' + d + '"' + (d === 30 ? ' selected' : '') + '>' + d + ' dias</option>').join('') + '</select></label>'
    + '<label>Quantidade<input name="qtd" type="number" min="1" max="50" value="1"></label></div>'
    + '<div style="display:flex;justify-content:flex-end;margin-top:14px"><button class="bt pri" data-cgerar="1"><span class="mi">add</span>Gerar</button></div>'
    + (codGerados.length ? '<div class="mt"><p class="muted small" style="margin-bottom:8px">ACABOU DE GERAR</p><div class="codg">' + codGerados.map((c) => '<span>' + esc(c) + '</span>').join('') + '</div><button class="bt sm mt" data-ccopiar="1"><span class="mi">content_copy</span>Copiar todos</button></div>' : '')
    + '<p class="muted small mt">A pessoa usa em Plano › Resgatar código. Cada código vale uma vez.</p></div>'
    + '<div class="card"><h3><span class="mi">inventory_2</span>Resumo</h3>' + kpi('confirmation_number', COR.verde, 'Livres', num(livres), 'ainda não usados').replace('class="card kpi"', 'class="kpi"') + '<div class="mt">' + kpi('task_alt', COR.cinza, 'Usados', num(l.length - livres), 'dos últimos ' + l.length).replace('class="card kpi"', 'class="kpi"') + '</div></div></div>'
    + '<div class="card mt"><h3><span class="mi">receipt_long</span>Últimos códigos</h3><table class="tab"><thead><tr><th>CÓDIGO</th><th>DIAS</th><th>SITUAÇÃO</th><th>CRIADO</th></tr></thead><tbody>'
    + (l.length ? l.map((c) => '<tr><td><button class="bt ghost sm idf" data-copiar="' + esc(c.codigo) + '">' + esc(c.codigo) + '<span class="mi" style="font-size:14px">content_copy</span></button></td><td>' + c.dias + ' dias</td><td>' + (c.usado_em ? '<span class="chip cinza">Usado ' + (c.usado_conta ? 'por ' + idFmt(c.usado_conta) : '') + ' · ' + data(c.usado_em) + '</span>' : '<span class="chip rota">Livre</span>') + '</td><td class="muted">' + data(c.criado_em) + '</td></tr>').join('')
      : '<tr><td colspan="4"><div class="vazio">Nenhum código ainda.</div></td></tr>') + '</tbody></table></div>';
  marcarAtualizado();
}
async function gerarCodigos() {
  const f = {}; $('cf').querySelectorAll('[name]').forEach((e) => { f[e.name] = e.value; });
  const dias = Number(f.dias), qtd = Math.max(1, Math.min(50, Number(f.qtd) || 1));
  if (!confirm('Gerar ' + qtd + ' código(s) de ' + dias + ' dias de Pro?')) return;
  const r = await rpc('admin_gerar_codigos', { dias, qtd });
  if (r.status === 200) { codGerados = Array.isArray(r.json) ? r.json : []; toast(codGerados.length + ' código(s) gerado(s)'); codigos(); } else toast(erroTxt(r), true);
}

/* ═══════════ BACKUPS ═══════════ */
async function backups() {
  const r = await rpc('admin_backups');
  if (atual !== 'backups') return;
  if (r.status !== 200) return falhou(r);
  const l = Array.isArray(r.json) ? r.json : [], ok = l.filter((b) => !b.erro)[0];
  $('conteudo').innerHTML = '<div class="barra"><button class="bt pri" data-bagora="1" id="bk-bt"><span class="mi">cloud_upload</span>Fazer cópia agora</button><span class="muted small">Uma cópia sai sozinha todo domingo e vai para o seu e-mail.</span></div>'
    + '<div class="grid k3">' + kpi('cloud_done', COR.verde, 'Última cópia', ok ? data(ok.criado_em) : '—', ok ? ha(ok.criado_em) : 'nenhuma ainda') + kpi('storage', COR.azul, 'Tamanho', ok && ok.bytes ? num(ok.bytes / 1048576, 2) + ' MB' : '—', 'da última cópia') + kpi('warning', COR.lar, 'Com erro', num(l.filter((b) => b.erro).length), 'nas últimas ' + l.length) + '</div>'
    + '<div class="card mt"><table class="tab"><thead><tr><th>DATA</th><th>ORIGEM</th><th>TAMANHO</th><th>E-MAIL</th><th>SITUAÇÃO</th></tr></thead><tbody>'
    + (l.length ? l.map((b) => '<tr><td>' + dataHora(b.criado_em) + '</td><td class="muted">' + esc(b.origem || '—') + '</td><td>' + (b.bytes ? num(b.bytes / 1048576, 2) + ' MB' : '—') + '</td><td>' + (b.email ? '<span class="chip rota">Enviado</span>' : '<span class="chip cinza">Não</span>') + '</td><td>' + (b.erro ? '<span class="chip verm">' + esc(b.erro) + '</span>' : '<span class="chip rota">OK</span>') + '</td></tr>').join('')
      : '<tr><td colspan="5"><div class="vazio">Nenhuma cópia ainda.</div></td></tr>') + '</tbody></table></div>';
  marcarAtualizado();
}
async function backupAgora() {
  const b = $('bk-bt'); b.disabled = true;
  const r = await rpc('admin_backup_agora');
  if (r.status === 200) { toast('Cópia iniciada. Em até 1 minuto chega no seu e-mail.'); setTimeout(() => { if (atual === 'backups') backups(); }, 25000); } else { b.disabled = false; toast(erroTxt(r), true); }
}

/* ═══════════ HISTÓRICO ═══════════ */
const ACOES = { planos: 'Mudou os preços dos planos', ranking: 'Mudou a ranqueada', dar_pro: 'Deu dias de Pro', tornar_free: 'Tirou o Pro', anunciar: 'Publicou anúncio', notificar: 'Enviou notificação', agendar_notificacao: 'Agendou notificação', gerar_codigos: 'Gerou códigos', encerrar_aviso: 'Encerrou anúncio' };
async function historico() {
  const r = await rpc('admin_historico', { p_limite: 150 });
  if (atual !== 'historico') return;
  if (r.status !== 200) return falhou(r);
  const l = r.json || [];
  const quando = (x) => x.criado_em || x.em || x.data || x.created_at;
  $('conteudo').innerHTML = '<div class="card"><table class="tab"><thead><tr><th>QUANDO</th><th>QUEM</th><th>O QUE</th><th>CONTA</th><th>DETALHES</th></tr></thead><tbody>'
    + (l.length ? l.map((x) => { let d = x.detalhes; if (d && typeof d === 'object') { d = Object.keys(d).filter((k) => !/^(id|criado_por|user_id)$/.test(k) && d[k] != null && d[k] !== '').slice(0, 6).map((k) => k + ': ' + (typeof d[k] === 'object' ? JSON.stringify(d[k]) : d[k])).join('\n'); }
      return '<tr><td class="muted" style="white-space:nowrap">' + dataHora(quando(x)) + '</td><td>' + esc(x.admin_nome || '—') + '</td><td><b>' + esc(ACOES[x.acao] || x.acao || '—') + '</b></td><td class="idf">' + (x.conta_id ? idFmt(x.conta_id) : '—') + '</td><td>' + (d ? '<pre class="js">' + esc(String(d)) + '</pre>' : '<span class="muted">—</span>') + '</td></tr>'; }).join('')
      : '<tr><td colspan="5"><div class="vazio"><span class="mi">history</span>Nada registrado ainda.</div></td></tr>') + '</tbody></table></div>';
  marcarAtualizado();
}



/* ═══════════ PLANOS E PREÇOS ═══════════ */
let plOrig = null, plEd = null;   // como está no servidor · como está sendo editado
const reais = (v) => 'R$ ' + Number(v || 0).toFixed(2).replace('.', ',');
async function planos(forcar) {
  if (!plEd || (forcar && !plMudancas().length)) {   // a atualização automática não apaga o que você está editando
    const r = await rpc('admin_planos');
    if (atual !== 'planos') return;
    if (r.status !== 200) return falhou(r);
    plOrig = (r.json || []).map((x) => ({ ...x, preco: Number(x.preco) })); plEd = plOrig.map((x) => ({ ...x }));
  }
  if (atual !== 'planos') return;
  const base = (plEd.find((x) => x.id === 'mensal') || plEd[0] || {}).preco || 0, mudou = plMudancas().length;
  $('conteudo').innerHTML = '<div class="grid g21"><div class="card"><h3><span class="mi">sell</span>Planos do Pro</h3>'
    + '<table class="tab pl-tab"><thead><tr><th>PLANO</th><th>NO APP</th><th>PREÇO (R$)</th><th>DIAS</th><th>CARTÃO</th><th>SELO</th><th>POR MÊS</th><th>DESCONTO</th></tr></thead><tbody>'
    + plEd.map((x, i) => { const pm = x.preco / x.meses, pc = x.id === 'mensal' ? 0 : Math.round((1 - x.preco / (base * x.meses)) * 100), o = plOrig[i] || {};
      const dif = (k) => String(o[k]) !== String(x[k]) ? ' mudou' : '';
      return '<tr class="' + (x.ativo ? '' : 'off') + '"><td><b>' + esc(x.nome) + '</b><div class="muted small">' + x.meses + (x.meses === 1 ? ' mês' : ' meses') + '</div></td>'
        + '<td><label class="sw"><input type="checkbox" data-plc="ativo" data-pli="' + i + '"' + (x.ativo ? ' checked' : '') + '><span></span></label></td>'
        + '<td><input class="pl-in' + dif('preco') + '" data-plc="preco" data-pli="' + i + '" type="number" step="0.10" min="5" max="1000" value="' + Number(x.preco).toFixed(2) + '"></td>'
        + '<td><input class="pl-in sm' + dif('dias') + '" data-plc="dias" data-pli="' + i + '" type="number" min="1" max="3660" value="' + x.dias + '"></td>'
        + '<td><select class="pl-in sm' + dif('parcelas') + '" data-plc="parcelas" data-pli="' + i + '">' + [1, 2, 3, 4, 5, 6, 10, 12].map((n) => '<option value="' + n + '"' + (Number(x.parcelas) === n ? ' selected' : '') + '>' + (n === 1 ? 'à vista' : 'até ' + n + 'x') + '</option>').join('') + '</select></td>'
        + '<td><input class="pl-in' + dif('selo') + '" data-plc="selo" data-pli="' + i + '" maxlength="30" placeholder="—" value="' + esc(x.selo || '') + '"></td>'
        + '<td><b>' + reais(pm) + '</b></td><td>' + (pc > 0 ? '<span class="chip rota">-' + pc + '%</span>' : '<span class="muted">—</span>') + '</td></tr>'; }).join('')
    + '</tbody></table>'
    + '<div style="display:flex;gap:10px;align-items:center;margin-top:16px"><span class="muted small" style="flex:1">' + (mudou ? '<b style="color:#fbbf24">' + mudou + ' mudança(s) não salva(s)</b>' : 'Tudo salvo.') + ' O desconto e o "por mês" são calculados em cima do Mensal.</span>'
    + (mudou ? '<button class="bt ghost" data-pldesf="1"><span class="mi">undo</span>Desfazer</button>' : '') + '<button class="bt pri" data-plsalvar="1"' + (mudou ? '' : ' disabled') + '><span class="mi">save</span>Salvar preços</button></div></div>'
    + '<div><div class="card"><h3><span class="mi">smartphone</span>Como fica no app</h3><div class="pl-prev">' + plEd.filter((x) => x.ativo).map((x) => { const pc = x.id === 'mensal' ? 0 : Math.round((1 - x.preco / (base * x.meses)) * 100);
      return '<div class="pl-op' + (x.selo ? ' dest' : '') + '">' + (x.selo ? '<i>' + esc(x.selo) + '</i>' : '') + '<b>' + esc(x.nome) + '</b><small>' + x.dias + ' dias</small><strong>' + reais(x.preco) + '</strong><small>' + (x.meses > 1 ? reais(x.preco / x.meses) + '/mês' : 'por mês') + '</small>' + (pc > 0 ? '<em>-' + pc + '%</em>' : '<em class="n">sem desconto</em>') + '</div>'; }).join('') + '</div></div>'
    + '<div class="card mt"><h3><span class="mi">verified_user</span>Segurança</h3><ul class="pl-seg"><li>Vale na hora no app, no site (Termos) e na cobrança do Mercado Pago.</li><li>O preço é decidido pelo servidor: ninguém consegue mudar pelo celular.</li><li>Quem já pagou não é afetado: vale para os próximos pedidos.</li><li>Limite de R$ 5 a R$ 1.000 por plano. Toda mudança vai para o Histórico de ações.</li></ul></div></div></div>';
  marcarAtualizado();
}
function plMudancas() {
  const l = []; if (!plOrig || !plEd) return l;
  plEd.forEach((x, i) => { const o = plOrig[i] || {}; [['preco', 'Preço', (v) => reais(v)], ['dias', 'Dias', (v) => v + ' dias'], ['parcelas', 'Cartão', (v) => Number(v) === 1 ? 'à vista' : 'até ' + v + 'x'], ['selo', 'Selo', (v) => v || 'sem selo'], ['ativo', 'No app', (v) => v ? 'aparece' : 'escondido']].forEach(([k, n, f]) => { if (String(o[k] ?? '') !== String(x[k] ?? '')) l.push(x.nome + ' · ' + n + ': ' + f(o[k]) + ' → ' + f(x[k])); }); });
  return l;
}
async function plSalvar() {
  const m = plMudancas(); if (!m.length) return;
  if (plEd.some((x) => !(Number(x.preco) >= 5 && Number(x.preco) <= 1000))) return toast('Cada preço precisa ficar entre R$ 5,00 e R$ 1.000,00.', true);
  if (!plEd.some((x) => x.ativo)) return toast('Deixe pelo menos um plano aparecendo no app.', true);
  if (!confirm('Salvar estas mudanças? Valem na hora no app e no Mercado Pago.\n\n• ' + m.join('\n• '))) return;
  const r = await rpc('admin_planos_salvar', { p_planos: plEd.map((x) => ({ id: x.id, preco: Number(x.preco), dias: Number(x.dias), parcelas: Number(x.parcelas), selo: x.selo || '', ativo: !!x.ativo })) });
  if (r.status === 200) { toast('Preços salvos. Já valem no app e no Mercado Pago.'); plEd = null; planos(); } else toast(erroTxt(r), true);
}

/* ═══════════ RANQUEADA ═══════════ */
let rkEd = null;   // prêmios sendo editados (sobrevive à atualização automática)
const MESES_L = ['janeiro', 'fevereiro', 'março', 'abril', 'maio', 'junho', 'julho', 'agosto', 'setembro', 'outubro', 'novembro', 'dezembro'];
const mesNome = (m) => { const s = String(m || ''); return s ? MESES_L[Number(s.slice(5, 7)) - 1] + ' de ' + s.slice(0, 4) : '—'; };
async function ranqueada() {
  const r = await rpc('admin_ranking');
  if (atual !== 'ranqueada') return;
  if (r.status !== 200) return falhou(r);
  const d = r.json || {}, cfg = d.config || {}, top = d.top || [], temps = d.temporadas || [], du = d.duelos || {};
  if (!rkEd) rkEd = (cfg.premios || []).map((p) => ({ pos: p.pos, texto: p.texto, dias: p.dias || 0 }));
  const COR_P = { 1: '#fbbf24', 2: '#cbd5e1', 3: '#d97706' };
  $('conteudo').innerHTML = '<div class="grid k4">'
    + kpi(cfg.pausado ? 'pause_circle' : 'play_circle', cfg.pausado ? COR.lar : COR.verde, 'Situação', cfg.pausado ? 'Pausada' : 'No ar', cfg.pausado ? 'o app mostra "temporariamente indisponível"' : 'temporada de ' + mesNome(d.mes))
    + kpi('groups', COR.roxo, 'Participantes', num(d.total || 0), 'com rotas no mês')
    + kpi('sports_mma', COR.azul, 'Duelos', num(du.ativos || 0) + ' ativos', num(du.convites || 0) + ' convites · ' + num(du.encerrados || 0) + ' encerrados')
    + kpi('diversity_3', COR.verde, 'Amizades', num(d.amizades || 0), 'pares de amigos') + '</div>'
    + '<div class="grid g21 mt"><div class="card"><h3><span class="mi">leaderboard</span>Classificação de ' + mesNome(d.mes) + '</h3>'
    + '<table class="tab"><thead><tr><th>#</th><th>MOTORISTA</th><th>ID</th><th>ROTAS</th></tr></thead><tbody>'
    + (top.length ? top.map((x) => '<tr><td><b style="color:' + (COR_P[x.pos] || 'inherit') + '">' + x.pos + 'º</b></td><td><b>' + esc(x.nome) + '</b><div class="muted small">' + esc(x.email || '') + '</div></td><td class="idf">' + idFmt(x.conta_id) + '</td><td><b>' + num(x.rotas) + '</b></td></tr>').join('')
      : '<tr><td colspan="4"><div class="vazio"><span class="mi">emoji_events</span>Ninguém pontuou ainda neste mês.</div></td></tr>') + '</tbody></table>'
    + '<p class="muted small mt">Conta 1 ponto por rota automática do assistente de rotas (iFood e InDrive) no mês. Rotas lançadas à mão não contam.</p></div>'
    + '<div><div class="card"><h3><span class="mi">' + (cfg.pausado ? 'pause' : 'play_arrow') + '</span>Ranqueada no app</h3>'
    + '<p class="muted small">' + (cfg.pausado ? 'Pausada: o app mostra "Sistema temporariamente indisponível" e ninguém recebe prêmio enquanto estiver assim.' : 'No ar: todos veem a classificação e o pódio. No dia 1º, às 00:05, o mês fecha e os prêmios em dias de Pro são entregues sozinhos.') + '</p>'
    + '<button class="bt ' + (cfg.pausado ? 'pri' : '') + ' mt" data-rkpausa="' + (cfg.pausado ? '0' : '1') + '"><span class="mi">' + (cfg.pausado ? 'play_arrow' : 'pause') + '</span>' + (cfg.pausado ? 'Retomar a ranqueada' : 'Pausar a ranqueada') + '</button></div>'
    + '<div class="card mt"><h3><span class="mi">workspace_premium</span>Premiação</h3><div id="rkp">'
    + rkEd.map((p, i) => '<div class="rkp-l"><b style="color:' + (COR_P[p.pos] || '#c9d1d9') + '">' + p.pos + 'º</b><input data-rki="' + i + '" data-rkc="texto" value="' + esc(p.texto) + '" placeholder="Prêmio (aparece no app)" maxlength="60"><label class="rkp-d"><input data-rki="' + i + '" data-rkc="dias" type="number" min="0" max="365" value="' + esc(p.dias) + '"><span>dias Pro</span></label><button class="bt ghost sm" data-rkdel="' + i + '" title="Tirar"><span class="mi">close</span></button></div>').join('')
    + '</div><div style="display:flex;gap:8px;margin-top:10px">' + (rkEd.length < 10 ? '<button class="bt sm" data-rkadd="1"><span class="mi">add</span>Posição</button>' : '') + '<span class="esp" style="flex:1"></span><button class="bt pri" data-rksalvar="1"><span class="mi">save</span>Salvar prêmios</button></div>'
    + '<p class="muted small mt">O texto aparece no pódio do app. "Dias Pro" é entregue sozinho ao fechar o mês (0 = só reconhecimento). Administradores não recebem dias.</p></div></div></div>'
    + '<div class="card mt"><h3><span class="mi">history</span>Temporadas encerradas</h3><table class="tab"><thead><tr><th>MÊS</th><th>PARTICIPANTES</th><th>PÓDIO</th><th>PRÊMIOS ENTREGUES</th></tr></thead><tbody>'
    + (temps.length ? temps.map((t) => '<tr><td><b>' + esc(mesNome(t.mes)) + '</b></td><td>' + num(t.participantes) + '</td><td>' + (t.top || []).slice(0, 3).map((x) => x.pos + 'º ' + esc(x.nome) + ' (' + num(x.rotas) + ')').join('<br>') + '</td><td>' + ((t.premios || []).map((x) => x.pos + 'º: ' + esc(x.texto || '') + (x.dias ? ' (+' + x.dias + ' dias)' : '')).join('<br>') || '<span class="muted">nenhum</span>') + '</td></tr>').join('')
      : '<tr><td colspan="4"><div class="vazio">A primeira temporada fecha no dia 1º do próximo mês.</div></td></tr>') + '</tbody></table></div>';
  marcarAtualizado();
}
async function rkSalvar(pausado, comPremios) {
  if (pausado !== null && !confirm(pausado ? 'Pausar a ranqueada? O app vai mostrar "Sistema temporariamente indisponível" e ninguém recebe prêmio enquanto estiver pausada.' : 'Retomar a ranqueada?')) return;
  const premios = comPremios ? rkEd.map((p, i) => ({ pos: i + 1, texto: String(p.texto || '').trim(), dias: Number(p.dias) || 0 })) : null;
  if (premios && premios.some((p) => !p.texto)) return toast('Escreva o prêmio de cada posição (ou tire a posição).', true);
  const r = await rpc('admin_ranking_salvar', { p_pausado: pausado, p_premios: premios });
  if (r.status === 200) { toast(pausado === null ? 'Prêmios salvos' : (pausado ? 'Ranqueada pausada' : 'Ranqueada no ar')); if (comPremios) rkEd = null; ranqueada(); } else toast(erroTxt(r), true);
}

/* ═══════════ ATUALIZAÇÃO DO PROGRAMA ═══════════ */
const temApp = !!window.prismoApp;
function mb(b) { return num((b || 0) / 1048576, 1) + ' MB'; }
function pintarUpd(e) {
  const box = $('upd'), vb = $('ver-bt'); if (!box) return;
  vb.classList.toggle('tem', ['disponivel', 'baixando', 'pronto'].includes(e.fase));
  $('ver-txt').textContent = 'Versão ' + e.atual + (e.fase === 'verificando' ? ' · procurando…' : (e.fase === 'atualizado' ? ' · em dia' : (e.nova ? ' · nova ' + e.nova : '')));
  if (e.fase === 'baixando' || e.fase === 'disponivel') {
    const pc = e.tamanho ? Math.round(e.baixado / e.tamanho * 100) : 0;
    box.className = 'upd'; box.hidden = false;
    box.innerHTML = '<span class="mi">downloading</span><div><b>Baixando a versão ' + esc(e.nova) + '</b><small>' + mb(e.baixado) + ' de ' + mb(e.tamanho) + ' · pode continuar usando</small></div><div class="bar"><i style="width:' + pc + '%"></i></div><span class="esp"></span>';
  } else if (e.fase === 'pronto') {
    box.className = 'upd'; box.hidden = false;
    box.innerHTML = '<span class="mi">system_update_alt</span><div><b>Versão ' + esc(e.nova) + ' pronta para instalar</b><small>O programa fecha, atualiza e abre de novo sozinho (leva uns segundos).</small></div><span class="esp"></span><button class="bt pri" data-a="instalar"><span class="mi">restart_alt</span>Reiniciar e atualizar</button>';
  } else if (e.fase === 'instalando') {
    box.className = 'upd'; box.hidden = false; box.innerHTML = '<span class="mi">hourglass_top</span><div><b>Atualizando…</b><small>O programa vai abrir de novo em instantes.</small></div>';
  } else if (e.fase === 'erro' && e.nova) {
    box.className = 'upd erro'; box.hidden = false;
    box.innerHTML = '<span class="mi">error</span><div><b>Não deu para baixar a versão ' + esc(e.nova) + '</b><small>' + esc(e.erro || '') + '</small></div><span class="esp"></span><button class="bt" data-a="upd-baixar"><span class="mi">refresh</span>Tentar de novo</button>';
  } else box.hidden = true;
}
if (temApp) {
  window.prismoApp.aoMudar(pintarUpd);
  window.prismoApp.versao().then((v) => { $('ver-txt').textContent = 'Versão ' + v; });
}

/* ═══════════ janela ═══════════ */
function abrirModal(h) { $('modal-box').innerHTML = h; $('modal').hidden = false; const f = $('modal-box').querySelector('input:not([type=hidden])'); if (f) f.focus(); }
function fecharModal() { $('modal').hidden = true; $('modal-box').innerHTML = ''; }

/* ═══════════ cliques (sem scripts no HTML: mais seguro) ═══════════ */
let tBusca = 0;
document.addEventListener('click', (e) => {
  const t = e.target.closest('button,a,[data-pg-ir]'); if (!t) { if (e.target === $('modal')) fecharModal(); return; }
  const d = t.dataset;
  if (d.pg) return ir(d.pg);
  if (d.pgIr) { e.preventDefault(); return ir(d.pgIr); }
  if (d.a === 'sair') { if (confirm('Sair do Prismo Admin?')) sair(); return; }
  if (d.a === 'recarregar') return recarregar();
  if (d.escala) { escala = d.escala; return relatorios(); }
  if (d.fon) { filtroOn = d.fon; return online(); }
  if (d.uplano !== undefined) { us.plano = d.uplano; us.pag = 0; document.querySelectorAll('[data-uplano]').forEach((b) => b.classList.toggle('on', b.dataset.uplano === us.plano)); return usuarios(true); }
  if (d.upag) { us.pag = Math.max(0, us.pag + Number(d.upag)); return usuarios(true); }
  if (d.copiar) { navigator.clipboard.writeText(d.copiar).then(() => toast('ID copiado: ' + d.copiar)); return; }
  if (d.pro) return darPro(Number(d.pro));
  if (d.free) return tirarPro(Number(d.free));
  if (d.sab) { sp.abertos = d.sab === '1'; sp.sel = null; return suporte(); }
  if (d.ch) return abrirChamado(Number(d.ch));
  if (d.resp) return responder(Number(d.resp));
  if (d.fin) return finalizar(Number(d.fin));
  if (d.eres) { errRes = d.eres === '1'; return erros(); }
  if (d.epilha) { errAberto[d.epilha] = !errAberto[d.epilha]; return erros(); }
  if (d.eresolve) return resolverErro(Number(d.eresolve), d.v === '1');
  if (d.mnova) return editorMissao(null);
  if (d.medit) return editorMissao(jogo.missoes.find((m) => m.id === Number(d.medit)));
  if (d.mativa) return ativarMissao(Number(d.mativa));
  if (d.mdel) return apagarMissao(Number(d.mdel));
  if (d.msalvar !== undefined) return salvarMissao(d.msalvar);
  if (d.inova) return editorInsignia(null);
  if (d.iedit) return editorInsignia(jogo.insignias.find((i) => i.id === Number(d.iedit)));
  if (d.idel) return apagarInsignia(Number(d.idel));
  if (d.isalvar !== undefined) return salvarInsignia(d.isalvar);
  if (d.ico) { const f = $('fm'); f.querySelector('[name=icone]').value = d.ico; document.querySelectorAll('#icones button').forEach((b) => b.classList.toggle('on', b === t)); return $('prev') && ($('fm').querySelector('[name=titulo]') ? prevMissao() : prevInsignia()); }
  if (d.fechar) return fecharModal();
  if (d.imgesc) return $('img-arq') && $('img-arq').click();
  if (d.imgtira) { const f = $('fm'); f.querySelector('[name=imagem]').value = ''; $('img-pv').innerHTML = '<span class="mi">image</span>'; t.remove(); return f.querySelector('[name=titulo]') ? prevMissao() : prevInsignia(); }
  if (d.vdias) { venDias = Number(d.vdias); return vendas(); }
  if (d.a === 'upd') { if (!temApp) return toast('A atualização automática funciona no programa instalado.'); toast('Procurando atualização…'); window.prismoApp.checar().then((e) => { if (e.fase === 'atualizado') toast('Você já está na versão mais nova (' + e.atual + ').'); else if (e.fase === 'erro') toast('Não consegui procurar agora: ' + (e.erro || ''), true); }); return; }
  if (d.a === 'instalar') return window.prismoApp && window.prismoApp.instalar();
  if (d.a === 'upd-baixar') return window.prismoApp && window.prismoApp.baixar();
  if (d.nab) { lerNf(); nt.aba = d.nab; return notificacoes(); }
  if (d.npub) { lerNf(); nt.publico = d.npub; return notificacoes(); }
  if (d.nqd) { lerNf(); nt.quando = d.nqd; return notificacoes(); }
  if (d.nenviar) return enviarNotif();
  if (d.ncanc) return cancelarNotif(Number(d.ncanc));
  if (d.nrep) { const n = (notifLista._l || []).find((x) => x.id === Number(d.nrep)); if (n) Object.assign(nt, { titulo: n.titulo, texto: n.texto, publico: n.publico, conta: n.conta_id || '', aba: 'enviar' }); return notificacoes(); }
  if (d.unotif) { Object.assign(nt, { publico: 'conta', conta: d.unotif, aba: 'enviar', quando: 'agora' }); return ir('notificacoes'); }
  if (d.apub) { lerAf(); an.publico = d.apub; document.querySelectorAll('[data-apub]').forEach((b) => b.classList.toggle('on', b === t)); return; }
  if (d.asem) { const i = Number(d.asem); an.semana = an.semana.includes(i) ? an.semana.filter((x) => x !== i) : an.semana.concat(i); t.classList.toggle('on'); lerAf(); return; }
  if (d.atur) { an.turnos = an.turnos.includes(d.atur) ? an.turnos.filter((x) => x !== d.atur) : an.turnos.concat(d.atur); t.classList.toggle('on'); lerAf(); return; }
  if (d.apub2) return publicarAnuncio();
  if (d.anesc) return $('an-arq') && $('an-arq').click();
  if (d.antira) { lerAf(); an.imagem = ''; return anuncios(); }
  if (d.aenc) return encerrarAnuncio(Number(d.aenc));
  if (d.cgerar) return gerarCodigos();
  if (d.ccopiar) { navigator.clipboard.writeText(codGerados.join('\n')).then(() => toast('Códigos copiados')); return; }
  if (d.bagora) return backupAgora();
  if (d.plsalvar) return plSalvar();
  if (d.pldesf) { plEd = plOrig.map((x) => ({ ...x })); return planos(); }
  if (d.rkpausa) return rkSalvar(d.rkpausa === '1', false);
  if (d.rksalvar) return rkSalvar(null, true);
  if (d.rkadd) { rkEd.push({ pos: rkEd.length + 1, texto: '', dias: 0 }); return ranqueada(); }
  if (d.rkdel) { rkEd.splice(Number(d.rkdel), 1); rkEd.forEach((p, i) => { p.pos = i + 1; }); return ranqueada(); }
});
document.addEventListener('input', (e) => {
  if (e.target.closest && e.target.closest('#af')) { lerAf(); return; }
  if (e.target.dataset && e.target.dataset.rki !== undefined && rkEd) { const p = rkEd[Number(e.target.dataset.rki)]; if (p) p[e.target.dataset.rkc] = e.target.dataset.rkc === 'dias' ? (Number(e.target.value) || 0) : e.target.value; return; }
  if (e.target.id === 'us-busca') { clearTimeout(tBusca); tBusca = setTimeout(() => { us.busca = e.target.value.trim(); us.pag = 0; usuarios(true); }, 350); return; }
  if (e.target.closest && e.target.closest('#nf')) {
    lerNf(); const pt = $('pv-t'), px = $('pv-x'), c = $('nf-cont');
    if (pt) pt.textContent = nt.titulo || 'Título da notificação'; if (px) px.textContent = nt.texto || 'A mensagem aparece aqui.'; if (c) c.textContent = nt.texto.length + '/400';
    return;
  }
  if (e.target.closest && e.target.closest('#fm')) { if ($('fm').querySelector('[name=titulo]')) prevMissao(); else prevInsignia(); }
});
document.addEventListener('change', (e) => { if (e.target.dataset && e.target.dataset.pli !== undefined && plEd) { const x = plEd[Number(e.target.dataset.pli)], k = e.target.dataset.plc; if (x) { x[k] = k === 'ativo' ? e.target.checked : (k === 'selo' ? e.target.value.trim() : Number(e.target.value)); } return planos(); } if (e.target.id === 'an-arq' && e.target.files && e.target.files[0]) { anImagem(e.target.files[0]); e.target.value = ''; return; } if (e.target.closest && e.target.closest('#af')) lerAf(); if (e.target.id === 'img-arq' && e.target.files && e.target.files[0]) { trocarImagem(e.target.files[0]); e.target.value = ''; } });
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && !$('modal').hidden) fecharModal();
  /* chat do suporte: Enter envia, Shift+Enter pula linha */
  if (e.key === 'Enter' && !e.shiftKey && e.target.id === 'sp-txt') { e.preventDefault(); const b = $('sp-bt'); if (b && !b.disabled) responder(Number(b.dataset.resp)); }
});

function iniciar() {
  $('login').hidden = true; $('app').hidden = false;
  $('me-email').textContent = (sess && sess.email) || '—';
  ir('painel');
  setInterval(() => { if (sess && atual !== 'painel') atualizarBadges(); }, 60000);
}
$('login-form').addEventListener('submit', entrar);
if (sess && sess.refresh_token) iniciar();
