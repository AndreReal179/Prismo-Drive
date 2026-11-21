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
  vendas: ['Vendas do Pro', 'Pagamentos do Mercado Pago', vendas]
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
  clearInterval(timer); timer = setInterval(() => { if (atual === 'painel' && $('modal').hidden) recarregar(); }, 30000);
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
      + '<td class="num">' + (u.plano === 'admin' ? '' : '<button class="bt sm" data-pro="' + i + '" title="Dar 30 dias de Pro"><span class="mi">add</span>30 dias Pro</button> ' + (u.plano === 'pro' ? '<button class="bt sm verm" data-free="' + i + '" title="Tirar o Pro">Free</button>' : '')) + '</td></tr>').join('')
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
async function suporte(manter) {
  const r = await rpc('admin_chamados', { abertos: sp.abertos });
  if (atual !== 'suporte') return;
  if (r.status !== 200) return falhou(r);
  sp.lista = r.json || [];
  if (sp.abertos) badge('nb-suporte', sp.lista.filter((c) => c.status === 'aguardando' || c.status === 'andamento').length);
  $('conteudo').innerHTML = '<div class="dois"><div class="lst"><div class="seg" style="align-self:flex-start;margin-bottom:6px"><button data-sab="1" class="' + (sp.abertos ? 'on' : '') + '">Abertos</button><button data-sab="0" class="' + (!sp.abertos ? 'on' : '') + '">Finalizados</button></div>'
    + (sp.lista.length ? sp.lista.map((c) => '<button class="it' + (sp.sel === c.id ? ' on' : '') + '" data-ch="' + c.id + '"><div class="l1"><span class="chip ' + (ST[c.status] || ST.aguardando)[1] + '">' + (ST[c.status] || ST.aguardando)[0] + '</span><small>' + ha(c.atualizado_em) + '</small></div><b>' + esc(c.assunto) + '</b><small>#' + c.id + ' · ' + esc(CAT[c.categoria] || c.categoria) + ' · ' + esc(c.nome || c.email || '') + (c.anexos ? ' · 📎 ' + c.anexos : '') + '</small></button>').join('')
      : '<div class="vazio"><span class="mi">inbox</span>' + (sp.abertos ? 'Nenhum chamado aberto. Tudo em dia! 🎉' : 'Nenhum chamado finalizado.') + '</div>')
    + '</div><div class="card det" id="sp-det"><div class="vazio"><span class="mi">forum</span>Escolha um chamado na lista.</div></div></div>';
  if (sp.sel && sp.lista.some((c) => c.id === sp.sel)) abrirChamado(sp.sel, true);
  marcarAtualizado();
  clearInterval(timer); timer = setInterval(() => { if (atual === 'suporte' && !document.activeElement.matches('textarea')) suporte(true); }, 60000);
}
async function abrirChamado(id, sóMostrar) {
  sp.sel = id;
  document.querySelectorAll('.it').forEach((b) => b.classList.toggle('on', Number(b.dataset.ch) === id));
  let c = sp.lista.find((x) => x.id === id);
  if (!sóMostrar && c && c.status === 'aguardando') { const r = await rpc('admin_chamado_abrir', { chamado: id }); if (r.status === 200 && r.json) { c = r.json; sp.lista = sp.lista.map((x) => x.id === id ? c : x); } }
  if (!c) return;
  const box = $('sp-det');
  box.innerHTML = '<div style="display:flex;align-items:flex-start;gap:10px"><div style="flex:1"><span class="chip ' + (ST[c.status] || ST.aguardando)[1] + '">' + (ST[c.status] || ST.aguardando)[0] + '</span> <span class="chip cinza">' + esc(CAT[c.categoria] || c.categoria) + '</span><h3 style="font-size:18px;margin:10px 0 6px">' + esc(c.assunto) + '</h3>'
    + '<div class="info"><span>Cliente: <b>' + esc(c.nome || '—') + '</b></span><span>E-mail: <b>' + esc(c.email || '—') + '</b></span><span>ID: <b class="idf">' + idFmt(c.conta_id) + '</b></span><span>Plano: <b>' + esc(c.plano || '—') + '</b></span><span>App: <b>' + esc(c.app_versao || '—') + '</b></span><span>Celular: <b>' + esc(c.aparelho || '—') + '</b></span><span>Aberto: <b>' + dataHora(c.criado_em) + '</b></span></div></div>'
    + (c.status !== 'finalizado' ? '<button class="bt sm" data-fin="' + c.id + '"><span class="mi">task_alt</span>Finalizar</button>' : '') + '</div>'
    + (c.ocorrencias ? '<p class="small" style="margin-top:10px;color:#fdba74">Erro automático: aconteceu ' + num(c.ocorrencias.vezes) + ' vezes com ' + num(c.ocorrencias.pessoas) + ' pessoa(s). Último: ' + dataHora(c.ocorrencias.ultimo) + '</p>' : '')
    + '<div class="msgs"><div class="msg"><small>' + esc(c.nome || 'Cliente') + ' · ' + dataHora(c.criado_em) + '</small>' + esc(c.mensagem) + (c.anexos ? '\n\n📎 ' + c.anexos + ' anexo(s): chegaram no e-mail da empresa.' : '') + '</div>'
    + (c.msgs || []).map((m) => '<div class="msg' + (m.autor === 'admin' ? ' adm' : '') + '"><small>' + (m.autor === 'admin' ? 'Você (equipe)' : esc(c.nome || 'Cliente')) + ' · ' + dataHora(m.criado_em) + '</small>' + esc(m.texto) + '</div>').join('') + '</div>'
    + (c.status !== 'finalizado' ? '<div style="margin-top:auto"><textarea id="sp-txt" maxlength="4000" placeholder="Escreva a resposta para o cliente (vai no app e no e-mail dele)"></textarea><div style="display:flex;justify-content:flex-end;gap:8px;margin-top:8px"><button class="bt pri" data-resp="' + c.id + '" id="sp-bt"><span class="mi">send</span>Responder</button></div></div>' : '');
}
async function responder(id) {
  const t = ($('sp-txt').value || '').trim(); if (t.length < 2) return toast('Escreva a resposta.', true);
  const b = $('sp-bt'); b.disabled = true; b.innerHTML = '<span class="mi">hourglass_top</span>Enviando…';
  const r = await api('POST', '/functions/v1/suporte', { acao: 'responder', chamado: id, texto: t });
  b.disabled = false; b.innerHTML = '<span class="mi">send</span>Responder';
  if (r.status === 200 && r.json && r.json.ok) { toast(r.json.email ? 'Resposta enviada no app e no e-mail do cliente.' : 'Resposta salva no app (o e-mail não saiu).'); suporte(true); }
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
const TIPOS = { rotas: ['Rotas feitas (iFood/InDrive)', 'rotas'], km: ['Km rodados em rotas', 'km'], dias: ['Dias diferentes rodando', 'dias'], gorjetas: ['Gorjetas registradas', 'gorjetas'], ganho: ['Faturamento das rotas (R$)', 'R$'], turnos: ['Turnos finalizados', 'turnos'], meta: ['Dias com a meta diária batida', 'dias'], indicacoes: ['Indicações confirmadas (total)', 'indicações'] };
const DIFS = { facil: ['Fácil', COR.verde], media: ['Média', COR.azul], dificil: ['Difícil', '#fb7185'] };
const RARS = { comum: ['Comum', '#9ca3af'], rara: ['Rara', COR.azul], epica: ['Épica', '#c084fc'], lendaria: ['Lendária', COR.ouro] };
let jogo = { missoes: [], insignias: [] };
async function carregarJogo() {
  const r = await rpc('admin_missoes');
  if (r.status !== 200) { falhou(r); return false; }
  jogo = r.json || { missoes: [], insignias: [] }; return true;
}
const hex = (i, g) => '<div class="hex' + (g ? ' g' : '') + '" style="background:linear-gradient(160deg,' + esc(i.cor2 || '#fbbf24') + ',' + esc(i.cor1 || '#b45309') + ')"><span class="mi">' + esc(i.icone || 'military_tech') + '</span></div>';
async function missoes() {
  if (!(await carregarJogo()) || atual !== 'missoes') return;
  const l = jogo.missoes || [];
  const ac = l.reduce((a, m) => a + m.aceitas, 0), co = l.reduce((a, m) => a + m.concluidas, 0);
  $('conteudo').innerHTML = '<div class="barra"><button class="bt pri" data-mnova="1"><span class="mi">add</span>Nova missão</button><span class="muted small">As missões ativas aparecem na aba Missões do app. O progresso é contado no celular desde que a pessoa aceita.</span></div>'
    + '<div class="grid k3">' + kpi('flag', COR.roxo, 'Missões ativas', num(l.filter((m) => m.ativa).length), num(l.length) + ' no total') + kpi('pending_actions', COR.azul, 'Em andamento agora', num(ac), 'pessoas fazendo alguma missão') + kpi('emoji_events', COR.ouro, 'Missões concluídas', num(co), 'insígnias entregues pelas missões') + '</div>'
    + '<div class="cards mt">' + (l.length ? l.map((m) => {
      const ins = jogo.insignias.find((i) => i.id === m.insignia_id), d = DIFS[m.dificuldade] || DIFS.media, t = TIPOS[m.tipo] || ['', ''];
      return '<div class="card mcard' + (m.ativa ? '' : ' off') + '"><div class="top2"><div class="mic" style="background:linear-gradient(135deg,' + esc(m.cor1) + ',' + esc(m.cor2) + ')"><span class="mi">' + esc(m.icone) + '</span></div><div><b>' + esc(m.titulo) + '</b><div class="rod" style="margin-top:4px"><span class="chip" style="color:' + d[1] + ';border-color:' + alfa(d[1], .4) + ';background:' + alfa(d[1], .1) + '">' + d[0] + '</span>' + (m.ativa ? '' : '<span class="chip cinza">Desativada</span>') + '</div></div></div>'
        + '<p>' + esc(m.descricao || '') + '</p>'
        + '<div class="rod small muted"><span class="mi" style="font-size:16px">flag</span>' + num(m.meta, m.meta % 1 ? 1 : 0) + ' ' + t[1] + (m.prazo_dias ? ' · em ' + m.prazo_dias + ' dias' : '') + '</div>'
        + '<div class="rod small">' + (ins ? '<span class="chip pro"><span class="mi">military_tech</span>' + esc(ins.nome) + '</span>' : '<span class="chip cinza">Sem insígnia</span>') + '<span class="chip on">' + num(m.aceitas) + ' fazendo</span><span class="chip rota">' + num(m.concluidas) + ' concluíram</span></div>'
        + '<div class="acts"><button class="bt sm" data-medit="' + m.id + '"><span class="mi">edit</span>Editar</button><button class="bt sm" data-mativa="' + m.id + '"><span class="mi">' + (m.ativa ? 'visibility_off' : 'visibility') + '</span>' + (m.ativa ? 'Desativar' : 'Ativar') + '</button><button class="bt sm verm" data-mdel="' + m.id + '"><span class="mi">delete</span></button></div></div>';
    }).join('') : '<div class="card vazio"><span class="mi">flag</span>Nenhuma missão ainda. Crie a primeira!</div>') + '</div>';
  marcarAtualizado();
}
function editorMissao(m) {
  m = m || { titulo: '', descricao: '', icone: 'flag', cor1: '#6d28d9', cor2: '#a78bfa', dificuldade: 'media', tipo: 'rotas', meta: 10, prazo_dias: '', insignia_id: '', ativa: true, ordem: (jogo.missoes.length + 1) };
  abrirModal('<h3><span class="mi">flag</span>' + (m.id ? 'Editar missão' : 'Nova missão') + '</h3><div class="ed"><div class="form" id="fm">'
    + '<label class="full">Título<input name="titulo" maxlength="40" value="' + esc(m.titulo) + '" placeholder="Ex.: Maratonista"></label>'
    + '<label class="full">Descrição (o que a pessoa precisa fazer)<textarea name="descricao" maxlength="400" placeholder="Ex.: Rode 300 km em rotas dentro de 7 dias.">' + esc(m.descricao) + '</textarea></label>'
    + '<label>O que conta<select name="tipo">' + Object.keys(TIPOS).map((k) => '<option value="' + k + '"' + (m.tipo === k ? ' selected' : '') + '>' + TIPOS[k][0] + '</option>').join('') + '</select></label>'
    + '<label>Meta (quanto precisa)<input name="meta" type="number" min="0.01" step="any" value="' + esc(m.meta) + '"></label>'
    + '<label>Prazo depois de aceitar (dias)<input name="prazo_dias" type="number" min="1" max="365" value="' + esc(m.prazo_dias || '') + '" placeholder="sem prazo"></label>'
    + '<label>Dificuldade<select name="dificuldade">' + Object.keys(DIFS).map((k) => '<option value="' + k + '"' + (m.dificuldade === k ? ' selected' : '') + '>' + DIFS[k][0] + '</option>').join('') + '</select></label>'
    + '<label>Insígnia que ganha<select name="insignia_id"><option value="">Nenhuma</option>' + jogo.insignias.map((i) => '<option value="' + i.id + '"' + (Number(m.insignia_id) === i.id ? ' selected' : '') + '>' + esc(i.nome) + (i.ativa ? '' : ' (desativada)') + '</option>').join('') + '</select></label>'
    + '<label>Ordem no app<input name="ordem" type="number" value="' + esc(m.ordem) + '"></label>'
    + '<div class="cores full"><label>Cor 1<input name="cor1" type="color" value="' + esc(m.cor1) + '"></label><label>Cor 2<input name="cor2" type="color" value="' + esc(m.cor2) + '"></label><label style="display:flex;align-items:center;gap:8px;padding-top:22px"><input name="ativa" type="checkbox"' + (m.ativa ? ' checked' : '') + '>Ativa no app</label></div>'
    + '<label class="full">Ícone<input type="hidden" name="icone" value="' + esc(m.icone) + '"><div class="icones" id="icones">' + ICONES.map((i) => '<button type="button" data-ico="' + i + '" class="' + (m.icone === i ? 'on' : '') + '" title="' + i + '"><span class="mi">' + i + '</span></button>').join('') + '</div></label>'
    + '</div><div><p class="muted small" style="margin-bottom:8px">COMO FICA NO APP</p><div class="prev" id="prev"></div></div></div>'
    + '<div class="acoes"><button class="bt ghost" data-fechar="1">Cancelar</button><button class="bt pri" data-msalvar="' + (m.id || '') + '"><span class="mi">check</span>Salvar missão</button></div>');
  prevMissao();
}
function lerForm() { const o = {}; $('fm').querySelectorAll('[name]').forEach((e) => { o[e.name] = e.type === 'checkbox' ? e.checked : e.value; }); return o; }
function prevMissao() {
  const f = lerForm(), d = DIFS[f.dificuldade] || DIFS.media, ins = jogo.insignias.find((i) => i.id === Number(f.insignia_id)), t = TIPOS[f.tipo] || ['', ''];
  $('prev').innerHTML = '<div class="mic" style="width:80px;height:80px;border-radius:24px;margin:0 auto;background:linear-gradient(135deg,' + esc(f.cor1) + ',' + esc(f.cor2) + ')"><span class="mi" style="font-size:40px">' + esc(f.icone) + '</span></div>'
    + '<b>' + esc(f.titulo || 'Título da missão') + '</b><p>' + esc(f.descricao || 'Descrição da missão') + '</p>'
    + '<div class="rod" style="justify-content:center;display:flex;gap:6px;flex-wrap:wrap;margin-top:12px"><span class="chip" style="color:' + d[1] + ';border-color:' + alfa(d[1], .4) + '">' + d[0] + '</span><span class="chip cinza">Meta: ' + esc(f.meta || 0) + ' ' + t[1] + '</span>' + (f.prazo_dias ? '<span class="chip cinza">' + esc(f.prazo_dias) + ' dias</span>' : '') + '</div>'
    + (ins ? '<div style="display:flex;align-items:center;gap:10px;margin-top:14px;padding:10px;border-radius:14px;background:rgba(250,204,21,.06);border:1px solid rgba(250,204,21,.25);text-align:left">' + hex(ins).replace('class="hex"', 'class="hex" style="width:44px;height:48px;background:linear-gradient(160deg,' + esc(ins.cor2) + ',' + esc(ins.cor1) + ')"') + '<div><small style="color:' + (RARS[ins.raridade] || RARS.comum)[1] + ';font-weight:800;font-size:10px">INSÍGNIA ' + (RARS[ins.raridade] || RARS.comum)[0].toUpperCase() + '</small><b style="font-size:14px;margin:0">' + esc(ins.nome) + '</b></div></div>' : '')
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
  i = i || { nome: '', descricao: '', icone: 'military_tech', cor1: '#b45309', cor2: '#fbbf24', raridade: 'comum', ativa: true, ordem: (jogo.insignias.length + 1) };
  abrirModal('<h3><span class="mi">military_tech</span>' + (i.id ? 'Editar insígnia' : 'Nova insígnia') + '</h3><div class="ed"><div class="form" id="fm">'
    + '<label class="full">Nome<input name="nome" maxlength="40" value="' + esc(i.nome) + '" placeholder="Ex.: Rei da estrada"></label>'
    + '<label class="full">Descrição<textarea name="descricao" maxlength="300" placeholder="Ex.: Rodou 1.000 km em um mês.">' + esc(i.descricao) + '</textarea></label>'
    + '<label>Raridade<select name="raridade">' + Object.keys(RARS).map((k) => '<option value="' + k + '"' + (i.raridade === k ? ' selected' : '') + '>' + RARS[k][0] + '</option>').join('') + '</select></label>'
    + '<label>Ordem no Perfil<input name="ordem" type="number" value="' + esc(i.ordem) + '"></label>'
    + '<div class="cores full"><label>Cor de baixo<input name="cor1" type="color" value="' + esc(i.cor1) + '"></label><label>Cor de cima<input name="cor2" type="color" value="' + esc(i.cor2) + '"></label><label style="display:flex;align-items:center;gap:8px;padding-top:22px"><input name="ativa" type="checkbox"' + (i.ativa ? ' checked' : '') + '>Ativa</label></div>'
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
  if (d.vdias) { venDias = Number(d.vdias); return vendas(); }
});
document.addEventListener('input', (e) => {
  if (e.target.id === 'us-busca') { clearTimeout(tBusca); tBusca = setTimeout(() => { us.busca = e.target.value.trim(); us.pag = 0; usuarios(true); }, 350); return; }
  if (e.target.closest && e.target.closest('#fm')) { if ($('fm').querySelector('[name=titulo]')) prevMissao(); else prevInsignia(); }
});
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !$('modal').hidden) fecharModal(); });

function iniciar() {
  $('login').hidden = true; $('app').hidden = false;
  $('me-email').textContent = (sess && sess.email) || '—';
  ir('painel');
  setInterval(() => { if (sess && atual !== 'painel') atualizarBadges(); }, 60000);
}
$('login-form').addEventListener('submit', entrar);
if (sess && sess.refresh_token) iniciar();
