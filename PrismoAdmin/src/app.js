/* Prismo Drive Admin · tela do programa
   Usa as mesmas funções de administrador do servidor que o painel do app (só funcionam para a conta admin). */
'use strict';
const SB = 'https://xzdqvdrwvozoedmjwwhm.supabase.co';
const CHAVE = 'sb_publishable_OGjy3PepqOrIiVmw4s9EWA_kJGoFOFL';   // chave pública (a mesma do app)
const P = window.prismo;

const $ = (id) => document.getElementById(id);
const esc = (t) => String(t == null ? '' : t).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const moeda = (v) => 'R$ ' + (Number(v) || 0).toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const p2 = (n) => String(n).padStart(2, '0');
const data = (s) => { const d = new Date(s); return isNaN(d) ? '—' : p2(d.getDate()) + '/' + p2(d.getMonth() + 1) + '/' + d.getFullYear(); };
const dataCurta = (s) => { const d = new Date(s); return isNaN(d) ? '—' : p2(d.getDate()) + '/' + p2(d.getMonth() + 1); };
const hora = (s) => { const d = new Date(s); return p2(d.getHours()) + ':' + p2(d.getMinutes()); };
function quando(s) {
  const d = new Date(s), m = Math.round((Date.now() - d) / 60000);
  if (isNaN(d)) return '';
  if (m < 1) return 'agora';
  if (m < 60) return 'há ' + m + ' min';
  if (m < 1440 && d.getDate() === new Date().getDate()) return 'hoje ' + hora(s);
  if (m < 2880) return 'ontem ' + hora(s);
  return dataCurta(s) + ' ' + hora(s);
}
const NOME_PL = { mensal: 'Mensal', trimestral: 'Trimestral', semestral: 'Semestral', anual: 'Anual' };
const CAT = { duvida: ['DÚVIDA', 'p-n'], problema: ['PROBLEMA', 'p-a'], bug: ['BUG', 'p-r'], pagamento: ['PAGAMENTO', 'p-a'], sugestao: ['SUGESTÃO', 'p-b'], outro: ['OUTRO', 'p-n'], erro_auto: ['ERRO', 'p-r'] };
const ST_CH = { aguardando: ['Aguardando', 'p-r'], andamento: ['Em andamento', 'p-b'], respondido: ['Respondido', 'p-g'], finalizado: ['Finalizado', 'p-n'] };

function toast(t, erro) {
  const el = $('toast'); el.textContent = t; el.className = 'toast' + (erro ? ' err' : ''); el.hidden = false;
  clearTimeout(toast._t); toast._t = setTimeout(() => { el.hidden = true; }, 3800);
}
function confirmar(tit, txt, sim, perigo) {
  return new Promise((ok) => {
    $('md-tit').textContent = tit; $('md-txt').textContent = txt || '';
    const b = $('md-sim'); b.textContent = sim || 'Confirmar'; b.className = 'btn ' + (perigo ? 'r' : 'g');
    $('modal').hidden = false;
    const fim = (v) => { $('modal').hidden = true; $('md-sim').onclick = null; $('md-nao').onclick = null; ok(v); };
    $('md-sim').onclick = () => fim(true); $('md-nao').onclick = () => fim(false);
  });
}

/* ── internet (pelo programa, só para o Supabase) ── */
async function http(metodo, caminho, corpo, token) {
  const h = { apikey: CHAVE, 'Content-Type': 'application/json' };
  if (token) h.Authorization = 'Bearer ' + token;
  const r = await P.http(metodo, SB + caminho, h, corpo == null ? null : JSON.stringify(corpo));
  let j = null; try { j = r.texto ? JSON.parse(r.texto) : null; } catch (e) {}
  return { status: r.status, json: j };
}
function msgErro(r) {
  if (!r || r.status === 0) return 'Sem internet. Verifique a conexão e tente de novo.';
  const j = r.json || {}, m = String(j.msg || j.message || j.error_description || j.erro || '');
  if (/invalid_credentials|Invalid login/i.test(m + (j.error_code || ''))) return 'E-mail ou senha incorretos.';
  if (/email_not_confirmed/i.test(m + (j.error_code || ''))) return 'Este e-mail ainda não foi confirmado.';
  if (/sem_permissao/.test(m)) return 'Só administradores podem fazer isso.';
  if (/conta_nao_encontrada/.test(m)) return 'Conta não encontrada.';
  if (/conta_admin/.test(m)) return 'Contas de administrador não têm prazo de Pro.';
  if (/Could not find the function|PGRST202/.test(m + (j.code || ''))) return 'Falta rodar o SQL novo do programa no Supabase (20261118_admin_pc.sql).';
  if (r.status === 429) return 'Muitas tentativas agora. Espere um minuto.';
  return 'Não foi possível concluir agora.' + (m ? ' (' + m + ')' : '');
}

/* ── sessão ── */
let S = null;   // {access_token, refresh_token, expires_at, email}
let renovando = null;
async function token() {
  if (!S) return null;
  if (Date.now() < S.expires_at - 60000) return S.access_token;
  if (!renovando) renovando = (async () => {
    const r = await http('POST', '/auth/v1/token?grant_type=refresh_token', { refresh_token: S.refresh_token });
    renovando = null;
    if (r.status === 200 && r.json && r.json.access_token) { await guardar(r.json); return S.access_token; }
    if (r.status >= 400 && r.status < 500) { await sair(true); return null; }
    return null;
  })();
  return renovando;
}
async function guardar(j) {
  S = { access_token: j.access_token, refresh_token: j.refresh_token, expires_at: Date.now() + (j.expires_in || 3600) * 1000, email: (j.user && j.user.email) || (S && S.email) || '' };
  await P.sessaoGravar(S);
}
async function rpc(nome, args) {
  const t = await token();
  if (!t) return { status: 401, json: { msg: 'Entre de novo.' } };
  return http('POST', '/rest/v1/rpc/' + nome, args || {}, t);
}

async function entrar() {
  const email = $('lg-email').value.trim(), senha = $('lg-senha').value;
  const er = $('lg-erro'); er.textContent = '';
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || !senha) { er.textContent = 'Digite o e-mail e a senha.'; return; }
  const b = $('lg-entrar'); b.disabled = true; b.innerHTML = '<span class="material-icons gira">autorenew</span>Entrando…';
  const r = await http('POST', '/auth/v1/token?grant_type=password', { email, password: senha });
  b.disabled = false; b.innerHTML = '<span class="material-icons">login</span>Entrar';
  if (r.status !== 200 || !r.json || !r.json.access_token) { er.textContent = msgErro(r); return; }
  await guardar(r.json);
  $('lg-senha').value = '';
  if (!(await conferirAdmin())) { er.textContent = 'Esta conta não é de administrador.'; await sair(true); return; }
  abrirApp();
}
async function entrarGoogle() {
  const er = $('lg-erro'); er.textContent = '';
  const b = $('lg-google'); b.disabled = true; const antes = b.innerHTML;
  b.innerHTML = '<span class="material-icons gira">autorenew</span>Continue no navegador…';
  const r = await P.google();
  b.disabled = false; b.innerHTML = antes;
  if (!r || !r.sessao) {
    const e = String((r && r.erro) || '');
    er.textContent = e === 'tempo' ? 'O tempo acabou. Toque em "Entrar com Google" de novo.'
      : e === 'porta' ? 'Outro programa está usando a porta 53682. Feche e abra o Prismo Drive Admin e tente de novo.'
      : e === 'sem_internet' ? 'Sem internet. Verifique a conexão.'
      : /access_denied/i.test(e) ? 'A entrada foi cancelada no Google.'
      : 'Não foi possível entrar com o Google.' + (e && e !== 'sem_codigo' ? ' (' + e + ')' : '');
    return;
  }
  await guardar(r.sessao);
  if (!(await conferirAdmin())) { er.textContent = 'Esta conta Google não é de administrador.'; await sair(true); return; }
  abrirApp();
}
let EU = {};
async function conferirAdmin() {
  const r = await rpc('minha_conta');
  if (r.status !== 200 || !r.json) return false;
  EU = r.json;
  return EU.plano === 'admin';
}
async function sair(silencioso) {
  try { const t = S && S.access_token; if (t) await http('POST', '/auth/v1/logout', {}, t); } catch (e) {}
  S = null; await P.sessaoGravar(null);
  clearInterval(vigia);
  $('tela-app').hidden = true; $('tela-login').hidden = false;
  if (!silencioso) toast('Você saiu da conta.');
}

/* ── navegação ── */
let VIEW = 'painel';
const VIEWS = {};
function ir(v) {
  VIEW = v;
  document.querySelectorAll('.nv').forEach((b) => b.classList.toggle('on', b.dataset.v === v));
  $('mn').innerHTML = '<div class="vazio"><span class="material-icons gira">autorenew</span>Carregando…</div>';
  VIEWS[v]();
}
function abrirApp() {
  $('tela-login').hidden = true; $('tela-app').hidden = false;
  const nome = EU.nome || 'Administrador';
  $('me-nome').textContent = nome; $('me-av').textContent = nome.trim().charAt(0).toUpperCase() || 'A';
  ir('painel');
  vigiar(true);
  clearInterval(vigia); vigia = setInterval(() => vigiar(false), 60000);
}

/* ── avisos do Windows: chamados novos e vendas (a cada 1 min) ── */
let vigia = null, ultimo = null;
async function vigiar(primeira) {
  const r = await rpc('admin_suporte_pendentes');
  if (r.status !== 200 || !r.json) return;
  const j = r.json, ag = Number(j.aguardando) || 0, venda = j.venda && j.venda.aplicado_em;
  const bd = $('bd-suporte'); bd.textContent = ag; bd.hidden = !ag;
  P.contador(ag);
  if (!primeira && ultimo) {
    if (ag > ultimo.ag || (ag > 0 && j.ultimo && j.ultimo !== ultimo.ult)) P.notificar('📩 Novo chamado de suporte', ag + ' chamado' + (ag > 1 ? 's' : '') + ' aguardando resposta.');
    if (venda && venda !== ultimo.venda) {
      const v = j.venda;
      P.notificar('💰 Nova venda: Pro ' + (NOME_PL[v.plano] || v.plano) + ' ' + moeda(v.valor), (v.nome || 'Um cliente') + ' pagou por ' + (v.metodo === 'pix' ? 'Pix' : 'cartão') + '. Vendas hoje: ' + (j.vendas_hoje || 1) + '.');
    }
  }
  ultimo = { ag, ult: j.ultimo || '', venda: venda || (ultimo && ultimo.venda) || '' };
  if (VIEW === 'suporte' && !primeira && !document.hidden) VIEWS.suporte(true);
}

/* ══════════ PAINEL ══════════ */
VIEWS.painel = async () => {
  const [r, v] = await Promise.all([rpc('admin_resumo'), rpc('admin_vendas', { dias: 30 })]);
  if (VIEW !== 'painel') return;
  const h = new Date().getHours(), oi = h < 12 ? 'Bom dia' : (h < 18 ? 'Boa tarde' : 'Boa noite');
  const hoje = new Date().toLocaleDateString('pt-BR', { weekday: 'long', day: 'numeric', month: 'long' });
  let html = `<div class="hd"><div><h1>${oi}, ${esc((EU.nome || 'André').split(' ')[0])}</h1><p>${esc(hoje.charAt(0).toUpperCase() + hoje.slice(1))} · atualizado ${hora(new Date())}</p></div><span class="sp"></span>
    <button class="btn" data-acao="painel"><span class="material-icons">refresh</span>Atualizar</button><button class="btn g" data-ir="avisos"><span class="material-icons">campaign</span>Novo aviso</button></div>`;
  if (r.status !== 200 || !r.json) { $('mn').innerHTML = html + `<div class="box vazio"><span class="material-icons">cloud_off</span>${esc(msgErro(r))}</div>`; return; }
  const j = r.json, vh = j.vendas_hoje || {}, vo = j.vendas_ontem || {}, su = j.suporte || {};
  const dif = Number(vo.total) > 0 ? Math.round((Number(vh.total) - Number(vo.total)) / Number(vo.total) * 100) : null;
  html += `<div class="cards">
    <div class="kp"><p><span class="material-icons up">payments</span>VENDAS HOJE</p><b>${moeda(vh.total)}</b><small class="${dif == null || dif >= 0 ? 'up' : 'dn'}">${vh.qtd || 0} aprovada${vh.qtd == 1 ? '' : 's'}${dif == null ? '' : ' · ' + (dif >= 0 ? '▲ ' : '▼ ') + Math.abs(dif) + '% vs ontem'}</small></div>
    <div class="kp"><p><span class="material-icons am">workspace_premium</span>PRO ATIVOS</p><b>${j.pro_ativos || 0}</b><small class="up">${j.pagantes_semana || 0} pagaram nos últimos 7 dias</small></div>
    <div class="kp"><p><span class="material-icons bl">person_add</span>CONTAS NOVAS</p><b>${j.contas_hoje || 0}</b><small class="mut">hoje · ${j.em_teste || 0} em teste grátis · ${j.contas || 0} no total</small></div>
    <div class="kp"><p><span class="material-icons dn">support_agent</span>SUPORTE</p><b>${su.aguardando || 0}</b><small class="${su.aguardando ? 'dn' : 'up'}">${su.aguardando ? 'aguardando resposta' : 'tudo respondido'} · ${su.andamento || 0} em andamento</small></div></div>`;
  const dias = j.dias || [], tot30 = dias.reduce((a, d) => a + Number(d.total || 0), 0);
  html += `<div class="g2"><div class="box"><h3>FATURAMENTO · ÚLTIMOS 30 DIAS<span class="sp">${moeda(tot30)}</span></h3>${grafico(dias)}</div>
    <div class="box"><h3>ÚLTIMAS VENDAS<button class="sp btn" style="height:26px;padding:0 10px;font-size:11.5px" data-ir="vendas">ver todas</button></h3>${listaVendas(((v.json && v.json.lista) || []).slice(0, 6))}</div></div>`;
  if (j.erros_24h) html += `<div class="box" style="display:flex;align-items:center;gap:10px"><span class="material-icons dn">bug_report</span><span>${j.erros_24h} tipo${j.erros_24h > 1 ? 's' : ''} de erro do app nas últimas 24 h.</span><button class="btn sp" data-ir="erros">Ver erros</button></div>`;
  $('mn').innerHTML = html;
  const bdE = $('bd-erros'); bdE.textContent = j.erros_24h || ''; bdE.hidden = !j.erros_24h;
};
function grafico(dias) {
  if (!dias.length) return '<div class="vazio">Sem vendas no período.</div>';
  const W = 640, H = 230, max = Math.max(1, ...dias.map((d) => Number(d.total) || 0)), n = dias.length;
  const x = (i) => Math.round(i * (W - 20) / Math.max(1, n - 1)) + 10, y = (v) => Math.round(H - 26 - (v / max) * (H - 50));
  const bw = Math.max(4, Math.floor((W - 20) / n) - 4);
  let barras = '';
  dias.forEach((d, i) => {
    const v = Number(d.total) || 0, yy = y(v);
    barras += `<rect x="${x(i) - bw / 2}" y="${yy}" width="${bw}" height="${Math.max(0, H - 26 - yy)}" rx="3" fill="${i === n - 1 ? '#4ade80' : '#2EA44F'}" opacity="${v ? 1 : .25}"><title>${dataCurta(d.dia + 'T12:00')}: ${moeda(v)} · ${d.qtd} venda(s)</title></rect>`;
  });
  const lin = [0, .5, 1].map((f) => { const v = max * f, yy = y(v); return `<line x1="10" y1="${yy}" x2="${W - 10}" y2="${yy}" stroke="#14241b"/><text x="${W - 8}" y="${yy - 4}" fill="#8b9a91" font-size="10.5" text-anchor="end">${moeda(v).replace(',00', '')}</text>`; }).join('');
  const lb = [0, Math.floor(n / 2), n - 1].map((i) => `<text x="${x(i)}" y="${H - 6}" fill="#7d8a83" font-size="11" text-anchor="middle">${i === n - 1 ? 'hoje' : dataCurta(dias[i].dia + 'T12:00')}</text>`).join('');
  return `<svg width="100%" viewBox="0 0 ${W} ${H}" role="img" aria-label="Faturamento por dia nos últimos 30 dias">${lin}${barras}${lb}</svg>`;
}
function stPag(x) {
  if (x.revertido_em) return ['Estornado', 'p-n'];
  if (x.aplicado_em) return ['Aprovado', 'p-g'];
  if (/rejected|recusad|cancel/i.test(x.status || '')) return ['Recusado', 'p-r'];
  return ['Pendente', 'p-a'];
}
function listaVendas(l) {
  if (!l.length) return '<div class="vazio"><span class="material-icons">shopping_bag</span>Nenhuma venda ainda.</div>';
  return l.map((x) => { const st = stPag(x), n = x.nome || '(sem nome)';
    return `<div class="row"><div class="av2">${esc(n.charAt(0).toUpperCase())}</div><div style="flex:1;min-width:0"><b class="trunc" style="display:block">${esc(n)}</b><div class="mut">${NOME_PL[x.plano] || esc(x.plano)} · ${x.metodo === 'pix' ? 'Pix' : (x.metodo ? 'Cartão' : '—')} · ${quando(x.criado_em)}</div></div><b>${moeda(x.valor)}</b><span class="pill ${st[1]}">${st[0]}</span></div>`; }).join('');
}

/* ══════════ SUPORTE ══════════ */
const SUP = { filtro: 'abertos', lista: [], aberto: null };
VIEWS.suporte = async (silencioso) => {
  const r = await rpc('admin_chamados', { abertos: SUP.filtro === 'abertos' });
  if (VIEW !== 'suporte') return;
  if (r.status !== 200 || !Array.isArray(r.json)) { if (!silencioso) $('mn').innerHTML = `<div class="box vazio"><span class="material-icons">cloud_off</span>${esc(msgErro(r))}</div>`; return; }
  SUP.lista = r.json;
  // não apaga o que está sendo digitado na resposta
  const rascunho = $('ch-txt') ? $('ch-txt').value : '';
  desenharSuporte();
  if (rascunho && $('ch-txt')) $('ch-txt').value = rascunho;
};
function desenharSuporte() {
  const ag = SUP.lista.filter((c) => c.status === 'aguardando').length, an = SUP.lista.filter((c) => c.status === 'andamento' || c.status === 'respondido').length;
  let html = `<div class="hd"><div><h1>Suporte</h1><p>${SUP.filtro === 'abertos' ? ag + ' aguardando · ' + an + ' em andamento' : 'Chamados finalizados (últimos 60)'}</p></div><span class="sp"></span><button class="btn" data-acao="suporte"><span class="material-icons">refresh</span>Atualizar</button></div>
  <div class="sup"><div class="box ch-l"><div class="ch-f"><button data-chf="abertos" class="${SUP.filtro === 'abertos' ? 'on' : ''}">Abertos</button><button data-chf="finalizados" class="${SUP.filtro === 'finalizados' ? 'on' : ''}">Finalizados</button></div><div class="ch-lista">`;
  html += SUP.lista.length ? SUP.lista.map((c) => { const cat = CAT[c.categoria] || CAT.outro;
    return `<button class="ch-it${SUP.aberto === c.id ? ' on' : ''}${c.status === 'aguardando' ? ' novo' : ''}" data-ch="${c.id}"><div class="t"><b class="trunc">#${c.id} · ${esc(c.assunto)}</b><span class="pill ${cat[1]}">${cat[0]}</span></div><div class="mut" style="margin-top:4px">${esc(c.nome || (c.conta_id ? 'Cliente' : 'Sem conta'))}${c.plano ? ' · ' + esc(c.plano) : ''} · ${quando(c.atualizado_em || c.criado_em)}</div><div class="x trunc">${esc((c.mensagem || '').slice(0, 90))}</div></button>`; }).join('')
    : `<div class="vazio"><span class="material-icons">inbox</span>${SUP.filtro === 'abertos' ? 'Nenhum chamado aberto. Tudo em dia! 🎉' : 'Nenhum chamado finalizado.'}</div>`;
  html += '</div></div>';
  const c = SUP.lista.find((x) => x.id === SUP.aberto);
  html += c ? conversa(c) : '<div class="box vazio" style="display:flex;flex-direction:column;justify-content:center"><span class="material-icons">forum</span>Escolha um chamado à esquerda para ler e responder.</div>';
  html += '</div>';
  $('mn').innerHTML = html;
  const m = document.querySelector('.ch-msgs'); if (m) m.scrollTop = m.scrollHeight;
}
function conversa(c) {
  const st = ST_CH[c.status] || ST_CH.aguardando, n = c.nome || 'Cliente';
  let h = `<div class="box ch-c"><div class="ch-hd"><div class="av2" style="width:38px;height:38px">${esc(n.charAt(0).toUpperCase())}</div><div class="tx"><b style="display:block;line-height:1.3">#${c.id} · ${esc(c.assunto)}</b><div class="mut trunc">${esc(n)}${c.conta_id ? ' · conta ' + esc(c.conta_id) : ' · sem conta'}${c.plano ? ' · ' + esc(c.plano) : ''} · ${esc(c.aparelho || '')} · app ${esc(c.app_versao || '?')}</div></div><span class="pill ${st[1]}">${st[0]}</span>
    ${c.conta_id ? `<button class="btn" data-conta="${esc(c.conta_id)}"><span class="material-icons">person</span>Ver conta</button>` : ''}
    ${c.email ? `<button class="btn" data-email="${esc(c.email)}" title="${esc(c.email)}"><span class="material-icons">mail</span>E-mail</button>` : ''}
    ${c.status !== 'finalizado' ? `<button class="btn" data-fin="${c.id}"><span class="material-icons">task_alt</span>Finalizar</button>` : ''}</div>`;
  if (c.ocorrencias) h += `<div class="info"><span class="pill p-r">${c.ocorrencias.vezes}× · ${c.ocorrencias.pessoas} pessoa${c.ocorrencias.pessoas > 1 ? 's' : ''}</span></div>`;
  h += '<div class="ch-msgs">';
  h += `<div class="msg"><small>${esc(n)} · ${quando(c.criado_em)}</small>${esc(c.mensagem)}${c.anexos ? `<em><span class="material-icons" style="font-size:15px">attach_file</span>${c.anexos} anexo${c.anexos > 1 ? 's' : ''} (chegaram no e-mail da empresa)</em>` : ''}</div>`;
  (c.msgs || []).forEach((m) => { h += `<div class="msg${m.autor === 'admin' ? ' adm' : ''}"><small>${m.autor === 'admin' ? 'Equipe Prismo' : esc(n)} · ${quando(m.criado_em)}</small>${esc(m.texto)}</div>`; });
  h += '</div>';
  h += c.status !== 'finalizado'
    ? `<div class="ch-resp"><textarea id="ch-txt" maxlength="4000" placeholder="Escreva a resposta (vai no app e no e-mail do cliente) · Ctrl+Enter envia"></textarea><button class="btn g" id="ch-env" data-resp="${c.id}" style="height:44px"><span class="material-icons">send</span>Enviar</button></div>`
    : '<div class="ch-resp mut">Chamado finalizado.</div>';
  return h + '</div>';
}
async function abrirChamado(id) {
  SUP.aberto = id; desenharSuporte();
  const c = SUP.lista.find((x) => x.id === id);
  if (c && c.status === 'aguardando') {
    const r = await rpc('admin_chamado_abrir', { chamado: id });
    if (r.status === 200 && r.json) { SUP.lista = SUP.lista.map((x) => (x.id === id ? r.json : x)); if (VIEW === 'suporte') desenharSuporte(); vigiar(true); }
  }
  setTimeout(() => { const t = $('ch-txt'); if (t) t.focus(); }, 50);
}
async function responder(id) {
  const t = ($('ch-txt').value || '').trim();
  if (t.length < 2) return toast('Escreva a resposta.', true);
  const b = $('ch-env'); b.disabled = true; b.innerHTML = '<span class="material-icons gira">autorenew</span>Enviando…';
  const tk = await token();
  const r = tk ? await http('POST', '/functions/v1/suporte', { acao: 'responder', chamado: id, texto: t }, tk) : { status: 401 };
  if (r.status === 200 && r.json && r.json.ok) { toast(r.json.email ? 'Resposta enviada no app e no e-mail do cliente.' : 'Resposta salva no app (o e-mail não saiu).'); $('ch-txt').value = ''; VIEWS.suporte(true); return; }
  b.disabled = false; b.innerHTML = '<span class="material-icons">send</span>Enviar';
  toast((r.json && r.json.msg) || msgErro(r), true);
}
async function finalizar(id) {
  if (!(await confirmar('Finalizar o chamado #' + id + '?', 'O cliente recebe um aviso de que foi encerrado.', 'Finalizar'))) return;
  const r = await rpc('admin_chamado_finalizar', { chamado: id });
  if (r.status !== 200) return toast(msgErro(r), true);
  toast('Chamado finalizado.'); SUP.aberto = null; VIEWS.suporte(); vigiar(true);
}

/* ══════════ VENDAS ══════════ */
VIEWS.vendas = async () => {
  const r = await rpc('admin_vendas', { dias: 60 });
  if (VIEW !== 'vendas') return;
  let html = '<div class="hd"><div><h1>Vendas</h1><p>Compras do Pro pelo Mercado Pago (últimos 60 dias)</p></div><span class="sp"></span><button class="btn" data-acao="vendas"><span class="material-icons">refresh</span>Atualizar</button></div>';
  if (r.status !== 200 || !r.json) { $('mn').innerHTML = html + `<div class="box vazio"><span class="material-icons">cloud_off</span>${esc(msgErro(r))}</div>`; return; }
  const j = r.json, pl = j.planos || {}, k = (t, o, cls) => `<div class="kp"><p>${t}</p><b class="${cls || ''}">${moeda((o || {}).total)}</b><small class="mut">${(o || {}).qtd || 0} venda${(o || {}).qtd == 1 ? '' : 's'}</small></div>`;
  html += `<div class="cards">${k('HOJE', j.hoje)}${k('ESTE MÊS', j.mes)}${k('TOTAL', j.geral)}<div class="kp"><p>PLANOS NO MÊS</p><div style="display:flex;gap:10px;margin-top:10px;flex-wrap:wrap">${['mensal', 'trimestral', 'semestral', 'anual'].map((p) => `<span class="mut"><b style="color:#fff;font-size:16px">${pl[p] || 0}</b> ${NOME_PL[p]}</span>`).join('')}</div><small class="mut">${j.estornos ? j.estornos + ' estorno(s) no total' : 'nenhum estorno'}</small></div></div>`;
  const l = j.lista || [];
  html += `<div class="box"><table class="tab"><thead><tr><th>CLIENTE</th><th>CONTA</th><th>PLANO</th><th>FORMA</th><th>DATA</th><th style="text-align:right">VALOR</th><th>SITUAÇÃO</th></tr></thead><tbody>`
    + (l.length ? l.map((x) => { const st = stPag(x); return `<tr class="cl" data-conta="${esc(x.conta_id || '')}"><td><b>${esc(x.nome || '(sem nome)')}</b><div class="mut">${esc(x.email || '')}</div></td><td>${esc(x.conta_id || '')}</td><td>${NOME_PL[x.plano] || esc(x.plano)} · ${x.dias} dias</td><td>${x.metodo === 'pix' ? 'Pix' : (x.metodo ? 'Cartão' : '—')}</td><td>${data(x.criado_em)} ${hora(x.criado_em)}</td><td style="text-align:right"><b>${moeda(x.valor)}</b></td><td><span class="pill ${st[1]}">${st[0]}</span></td></tr>`; }).join('')
      : '<tr><td colspan="7" class="vazio">Nenhuma venda nos últimos 60 dias.</td></tr>') + '</tbody></table></div>';
  $('mn').innerHTML = html;
};

/* ══════════ USUÁRIOS ══════════ */
const US = { busca: '', lista: [], sel: null, ficha: null };
VIEWS.usuarios = () => {
  desenharUsuarios();
  if (US.busca) buscar(true);
};
function planoPill(p) {
  if (p.plano === 'admin') return '<span class="pill p-g">ADMIN</span>';
  const pro = p.pro_ate && Date.parse(p.pro_ate) > Date.now();
  if (pro) return `<span class="pill p-a">PRO até ${dataCurta(p.pro_ate)}</span>`;
  return '<span class="pill p-n">FREE</span>';
}
function desenharUsuarios() {
  let html = `<div class="hd"><div><h1>Usuários</h1><p>Busque por nome, e-mail ou número da conta</p></div></div>
    <div style="display:flex;gap:10px"><label class="busca"><span class="material-icons">search</span><input id="us-q" placeholder="Digite pelo menos 3 letras e aperte Enter" value="${esc(US.busca)}" spellcheck="false"></label><button class="btn g" id="us-bt" style="height:42px">Buscar</button></div>
    <div style="display:grid;grid-template-columns:minmax(0,1fr) 420px;gap:14px;align-items:start">`;
  html += '<div class="box">' + (US.lista.length ? `<table class="tab"><thead><tr><th>NOME</th><th>CONTA</th><th>PLANO</th><th>CRIADA EM</th></tr></thead><tbody>${US.lista.map((p, i) => `<tr class="cl${US.sel === p.conta_id ? ' on' : ''}" data-us="${i}"><td><b>${esc(p.nome || '(sem nome)')}</b><div class="mut">${esc(p.email || '')}</div></td><td>${esc(p.conta_id)}</td><td>${planoPill(p)}</td><td class="mut">${data(p.criado_em)}</td></tr>`).join('')}</tbody></table>`
    : `<div class="vazio"><span class="material-icons">person_search</span>${US.busca ? 'Nenhuma conta encontrada.' : 'Busque uma conta para ver a ficha.'}</div>`) + '</div>';
  html += '<div class="box" id="us-ficha">' + fichaHtml() + '</div></div>';
  $('mn').innerHTML = html;
  const q = $('us-q'); q.focus(); q.setSelectionRange(q.value.length, q.value.length);
}
function fichaHtml() {
  const f = US.ficha;
  if (!f) return '<div class="vazio"><span class="material-icons">badge</span>Escolha uma conta na lista.</div>';
  if (f.carregando) return '<div class="vazio"><span class="material-icons gira">autorenew</span>Carregando ficha…</div>';
  const n = f.nome || '(sem nome)', pro = f.pro_ate && Date.parse(f.pro_ate) > Date.now(), dias = pro ? Math.ceil((Date.parse(f.pro_ate) - Date.now()) / 864e5) : 0;
  let h = `<div style="display:flex;align-items:center;gap:12px"><div class="av2" style="width:46px;height:46px;font-size:18px">${esc(n.charAt(0).toUpperCase())}</div><div style="min-width:0;flex:1"><b class="trunc selec" style="color:#fff;font-size:16px;display:block">${esc(n)}</b><div class="mut selec">conta ${esc(f.conta_id)} · desde ${data(f.criado_em)}</div><div class="mut selec trunc">${esc(f.email || '')}</div></div>${planoPill(f)}</div>`;
  if (f.erro) return h + `<p class="erro" style="margin-top:12px">${esc(f.erro)}</p>`;
  h += `<div class="mini">
    <div class="kp"><p>PRO ATÉ</p><b>${pro ? data(f.pro_ate) : (f.plano === 'admin' ? '—' : 'Free')}</b><small class="mut">${pro ? dias + ' dia' + (dias > 1 ? 's' : '') : (f.pro_ate ? 'venceu em ' + data(f.pro_ate) : 'nunca teve Pro')}</small></div>
    <div class="kp"><p>PAGOU NO TOTAL</p><b>${moeda(f.pago_total)}</b><small class="mut">${(f.pagamentos || []).filter((x) => x.aplicado_em).length} compra(s)</small></div>
    <div class="kp"><p>INDICAÇÕES</p><b>${f.indicacoes || 0}</b><small class="mut">${f.indicacoes_ok || 0} confirmada(s)${f.codigo_indicacao ? ' · ' + esc(f.codigo_indicacao) : ''}</small></div>
    <div class="kp"><p>APARELHOS</p><b>${f.aparelhos || 0}</b><small class="mut">${f.teste_em ? 'teste grátis usado' : 'sem teste grátis'} · ${f.chamados || 0} chamado(s)</small></div></div>`;
  if (f.plano !== 'admin') h += `<h3 style="margin-top:16px">DAR OU TIRAR PRO</h3><div class="chips">${[3, 7, 30, 90].map((d) => `<button class="btn${US.dias === d ? ' on' : ''}" data-dias="${d}">+${d} dias</button>`).join('')}<button class="btn${US.dias === -7 ? ' on' : ''}" data-dias="-7">−7 dias</button></div>
    <div style="display:flex;gap:8px;margin-top:10px"><button class="btn g" style="flex:1" id="us-pro"><span class="material-icons">workspace_premium</span>${(US.dias || 30) > 0 ? 'Dar ' + (US.dias || 30) + ' dias de Pro' : 'Tirar 7 dias'}</button><button class="btn r" style="flex:1" id="us-free"><span class="material-icons">block</span>Tornar Free</button></div>
    <p class="mut peq" style="margin-top:8px">Toda ação fica registrada no histórico do administrador.</p>`;
  const pg = f.pagamentos || [];
  h += `<h3 style="margin-top:16px">PAGAMENTOS</h3>` + (pg.length ? pg.slice(0, 8).map((x) => { const st = stPag(x); return `<div class="row"><div style="flex:1"><b>${NOME_PL[x.plano] || esc(x.plano)}</b> <span class="mut">${data(x.criado_em)} · ${x.metodo === 'pix' ? 'Pix' : (x.metodo ? 'Cartão' : '—')}</span></div><b>${moeda(x.valor)}</b><span class="pill ${st[1]}">${st[0]}</span></div>`; }).join('') : '<p class="mut">Nenhum pagamento.</p>');
  return h;
}
async function buscar(silencioso) {
  const q = ($('us-q') ? $('us-q').value : US.busca).trim();
  if (q.length < 3) { if (!silencioso) toast('Digite o número da conta, o e-mail ou parte do nome', true); return; }
  US.busca = q;
  const r = await rpc('admin_buscar', { busca: q });
  if (r.status !== 200) return toast(msgErro(r), true);
  US.lista = Array.isArray(r.json) ? r.json : [];
  if (VIEW === 'usuarios') desenharUsuarios();
  if (US.lista.length === 1 && US.sel !== US.lista[0].conta_id) ficha(US.lista[0].conta_id);
}
async function ficha(conta) {
  US.sel = conta; US.dias = 30;
  const base = US.lista.find((x) => x.conta_id === conta) || { conta_id: conta };
  US.ficha = { ...base, carregando: true };
  if (VIEW === 'usuarios') desenharUsuarios();
  const r = await rpc('admin_conta', { conta });
  US.ficha = r.status === 200 && r.json ? r.json : { ...base, erro: msgErro(r) };
  if (VIEW === 'usuarios') desenharUsuarios();
}
async function abrirConta(conta) {
  if (!conta) return;
  US.busca = conta; US.lista = [];
  ir('usuarios');
  await buscar(true);
  ficha(conta);
}
async function darPro() {
  const f = US.ficha; if (!f) return; const d = US.dias || 30;
  if (!(await confirmar(d > 0 ? 'Dar ' + d + ' dias de Pro?' : 'Tirar ' + (-d) + ' dias de Pro?', (f.nome || '') + ' · conta ' + f.conta_id, d > 0 ? 'Dar Pro' : 'Tirar dias', d < 0))) return;
  const r = await rpc('admin_dar_pro', { conta: f.conta_id, dias: d });
  if (r.status !== 200) return toast(msgErro(r), true);
  toast(d > 0 ? 'Pro liberado até ' + data(r.json.pro_ate) : 'Dias removidos');
  buscar(true); ficha(f.conta_id);
}
async function tornarFree() {
  const f = US.ficha; if (!f) return;
  if (!(await confirmar('Tirar o Pro desta conta?', (f.nome || '') + ' · conta ' + f.conta_id + '\nA conta volta para Free na hora.', 'Tornar Free', true))) return;
  const r = await rpc('admin_tornar_free', { conta: f.conta_id });
  if (r.status !== 200) return toast(msgErro(r), true);
  toast('Conta agora é Free'); buscar(true); ficha(f.conta_id);
}

/* ══════════ CÓDIGOS PRO ══════════ */
let gerados = [];
VIEWS.codigos = async () => {
  const r = await rpc('admin_codigos');
  if (VIEW !== 'codigos') return;
  const l = r.status === 200 && Array.isArray(r.json) ? r.json : [];
  let html = `<div class="hd"><div><h1>Códigos Pro</h1><p>Códigos para resgatar dias de Pro no app (Perfil › Resgatar código)</p></div></div>
  <div style="display:grid;grid-template-columns:380px minmax(0,1fr);gap:14px;align-items:start">
   <div class="box form"><h3>GERAR CÓDIGOS</h3>
    <label>Dias de Pro<select id="cd-dias" class="in">${[3, 7, 15, 30, 60, 90, 180, 365].map((d) => `<option value="${d}"${d === 30 ? ' selected' : ''}>${d} dias</option>`).join('')}</select></label>
    <label>Quantidade<input id="cd-qtd" class="in" type="number" min="1" max="50" value="1"></label>
    <button class="btn g" id="cd-gerar"><span class="material-icons">add_circle</span>Gerar</button>
    ${gerados.length ? `<h3 style="margin-top:8px">GERADOS AGORA<button class="btn sp" style="height:28px" id="cd-copiar"><span class="material-icons">content_copy</span>Copiar todos</button></h3>${gerados.map((c) => `<div class="cod">${esc(c)}</div>`).join('')}` : ''}
   </div>
   <div class="box"><h3>ÚLTIMOS CÓDIGOS</h3>${r.status !== 200 ? `<p class="erro">${esc(msgErro(r))}</p>` : (l.length ? `<table class="tab"><thead><tr><th>CÓDIGO</th><th>DIAS</th><th>SITUAÇÃO</th></tr></thead><tbody>${l.slice(0, 80).map((c) => `<tr><td class="selec" style="font-family:Consolas,monospace;color:#fff">${esc(c.codigo)}</td><td>${c.dias}</td><td>${c.usado_em ? `<span class="pill p-n">usado</span> <span class="mut">${c.usado_conta ? 'conta ' + esc(c.usado_conta) + ' · ' : ''}${data(c.usado_em)}</span>` : '<span class="pill p-g">livre</span>'}</td></tr>`).join('')}</tbody></table>` : '<div class="vazio">Nenhum código ainda.</div>')}</div>
  </div>`;
  $('mn').innerHTML = html;
};
async function gerarCodigos() {
  const dias = Number($('cd-dias').value) || 30, qtd = Math.max(1, Math.min(50, Number($('cd-qtd').value) || 1));
  if (!(await confirmar('Gerar ' + qtd + ' código' + (qtd > 1 ? 's' : '') + ' de ' + dias + ' dias?', 'Cada código vale uma vez.', 'Gerar'))) return;
  const r = await rpc('admin_gerar_codigos', { dias, qtd });
  if (r.status !== 200) return toast(msgErro(r), true);
  gerados = Array.isArray(r.json) ? r.json : [];
  toast(gerados.length + ' código(s) gerado(s)');
  VIEWS.codigos();
}

/* ══════════ AVISOS (notificações para os clientes) ══════════ */
const AV = { publico: 'todos', dias: 1, semana: [0, 1, 2, 3, 4, 5, 6], turnos: ['manha'] };
const TURNOS = [['manha', 'Manhã · 8h'], ['tarde', 'Tarde · 13h'], ['noite', 'Noite · 19h']];
const hojeISO = () => { const d = new Date(); return d.getFullYear() + '-' + p2(d.getMonth() + 1) + '-' + p2(d.getDate()); };
VIEWS.avisos = async () => {
  const r = await rpc('admin_avisos');
  if (VIEW !== 'avisos') return;
  const ch = (g, v, t) => { const on = Array.isArray(AV[g]) ? AV[g].includes(v) : AV[g] === v; return `<button class="btn${on ? ' on' : ''}" data-av="${g}" data-v="${v}">${t}</button>`; };
  const hj = hojeISO(), l = r.status === 200 && Array.isArray(r.json) ? r.json : [];
  let html = `<div class="hd"><div><h1>Avisos</h1><p>Notificações que aparecem no celular dos clientes</p></div></div>
  <div style="display:grid;grid-template-columns:440px minmax(0,1fr);gap:14px;align-items:start">
   <div class="box form"><h3>NOVA NOTIFICAÇÃO</h3>
    <label>Título<input id="av-tit" class="in" maxlength="40" placeholder="Ex.: Promoção de fim de semana"></label>
    <label>Mensagem<textarea id="av-txt" maxlength="300" rows="3" placeholder="O texto que aparece na notificação"></textarea></label>
    <label>Para quem<div class="chips">${ch('publico', 'todos', 'Todos')}${ch('publico', 'free', 'Free')}${ch('publico', 'pro', 'Pro')}${ch('publico', 'usuario', 'Sem conta')}</div></label>
    <label>Começa em<input id="av-ini" type="date" class="in" value="${hj}" min="${hj}"></label>
    <label>Por quantos dias<div class="chips">${[1, 3, 7, 15, 30].map((d) => ch('dias', d, d + (d === 1 ? ' dia' : ' dias'))).join('')}</div></label>
    <label>Dias da semana<div class="chips">${['Dom', 'Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb'].map((t, i) => ch('semana', i, t)).join('')}</div></label>
    <label>Turnos<div class="chips">${TURNOS.map((z) => ch('turnos', z[0], z[1])).join('')}</div></label>
    <button class="btn g" id="av-pub"><span class="material-icons">campaign</span>Publicar</button>
   </div>
   <div class="box"><h3>PUBLICADAS</h3>${r.status !== 200 ? `<p class="erro">${esc(msgErro(r))}</p>` : (l.length ? l.map((a) => {
      const pp = a.inicio.split('-'), fim = new Date(+pp[0], +pp[1] - 1, +pp[2] + a.dias - 1), fimISO = fim.getFullYear() + '-' + p2(fim.getMonth() + 1) + '-' + p2(fim.getDate());
      const est = !a.ativo ? ['Encerrada', 'p-n'] : (fimISO < hj ? ['Terminou', 'p-n'] : (a.inicio > hj ? ['Agendada', 'p-a'] : ['Ativa', 'p-g']));
      return `<div class="row"><span class="material-icons am">campaign</span><div style="flex:1;min-width:0"><b>${esc(a.titulo)}</b><div class="mut">${esc(a.texto)}</div><div class="mut">${pp[2]}/${pp[1]} até ${p2(fim.getDate())}/${p2(fim.getMonth() + 1)} · ${(a.turnos || []).map((k) => (TURNOS.find((z) => z[0] === k) || [k, k])[1].split(' ·')[0]).join(', ')} · ${({ todos: 'Todos', free: 'Free', pro: 'Pro', usuario: 'Sem conta' })[a.publico] || a.publico}</div></div><span class="pill ${est[1]}">${est[0]}</span>${a.ativo && fimISO >= hj ? `<button class="btn r" data-enc="${a.id}">Encerrar</button>` : ''}</div>`; }).join('') : '<div class="vazio">Nenhuma notificação publicada ainda.</div>')}</div>
  </div>`;
  const tit = $('av-tit') ? $('av-tit').value : '', txt = $('av-txt') ? $('av-txt').value : '';
  $('mn').innerHTML = html;
  $('av-tit').value = tit; $('av-txt').value = txt;
};
function avChip(g, v) {
  v = /^\d+$/.test(v) ? Number(v) : v;
  if (Array.isArray(AV[g])) { const i = AV[g].indexOf(v); if (i >= 0) { if (AV[g].length > 1) AV[g].splice(i, 1); } else AV[g].push(v); }
  else AV[g] = v;
  VIEWS.avisos();
}
async function publicar() {
  const t = $('av-tit').value.trim() || 'Prismo Drive', x = $('av-txt').value.trim(), ini = $('av-ini').value || hojeISO();
  if (x.length < 3) return toast('Escreva a mensagem.', true);
  if (!(await confirmar('Publicar esta notificação?', t + '\n' + x, 'Publicar'))) return;
  const r = await rpc('admin_anunciar', { titulo: t, texto: x, publico: AV.publico, inicio: ini, dias: AV.dias, semana: AV.semana.slice().sort(), turnos: AV.turnos.slice() });
  if (r.status !== 200) return toast(msgErro(r), true);
  toast('Notificação publicada.'); $('av-tit').value = ''; $('av-txt').value = ''; VIEWS.avisos();
}
async function encerrar(id) {
  if (!(await confirmar('Encerrar esta notificação?', 'Os clientes deixam de recebê-la quando o app atualizar.', 'Encerrar', true))) return;
  const r = await rpc('admin_encerrar_aviso', { aviso: id });
  if (r.status !== 200) return toast(msgErro(r), true);
  toast('Notificação encerrada.'); VIEWS.avisos();
}

/* ══════════ ERROS DO APP ══════════ */
let erroAberto = null;
VIEWS.erros = async () => {
  const r = await rpc('admin_erros', { dias: 30 });
  if (VIEW !== 'erros') return;
  let html = '<div class="hd"><div><h1>Erros do app</h1><p>Relatório automático dos últimos 30 dias (sem dados pessoais)</p></div><span class="sp"></span><button class="btn" data-acao="erros"><span class="material-icons">refresh</span>Atualizar</button></div>';
  if (r.status !== 200 || !Array.isArray(r.json)) { $('mn').innerHTML = html + `<div class="box vazio"><span class="material-icons">cloud_off</span>${esc(msgErro(r))}</div>`; return; }
  html += '<div class="box">' + (r.json.length ? `<table class="tab"><thead><tr><th>ERRO</th><th>VERSÃO</th><th>VEZES</th><th>PESSOAS</th><th>ÚLTIMA VEZ</th><th>CHAMADO</th></tr></thead><tbody>${r.json.map((e) => `<tr class="cl" data-erro="${e.id}"><td style="max-width:460px"><b class="trunc" style="display:block">${esc(e.mensagem || '(sem mensagem)')}</b><div class="mut">${esc(e.origem || '')}${e.tela ? ' · ' + esc(e.tela) : ''}${e.aparelho ? ' · ' + esc(e.aparelho) : ''}</div>${erroAberto === e.id ? `<div class="pre">${esc(e.pilha || 'sem detalhes')}</div>` : ''}</td><td>${esc(e.versao)}</td><td><b>${e.ocorrencias}</b></td><td>${e.pessoas}</td><td class="mut">${quando(e.ultimo_em)}</td><td>${e.chamado_id ? `<button class="btn" style="height:28px" data-chlink="${e.chamado_id}">#${e.chamado_id}</button>` : '<span class="mut">—</span>'}</td></tr>`).join('')}</tbody></table>` : '<div class="vazio"><span class="material-icons">verified</span>Nenhum erro nos últimos 30 dias. 🎉</div>') + '</div>';
  $('mn').innerHTML = html;
};

/* ══════════ BACKUPS ══════════ */
VIEWS.backups = async () => {
  const r = await rpc('admin_backups');
  if (VIEW !== 'backups') return;
  let html = '<div class="hd"><div><h1>Backups</h1><p>Cópia de segurança do banco (sai sozinha todo domingo e vai para o e-mail da empresa)</p></div><span class="sp"></span><button class="btn g" id="bk-agora"><span class="material-icons">cloud_upload</span>Fazer agora</button></div>';
  html += '<div class="box">' + (r.status !== 200 || !Array.isArray(r.json) ? `<p class="erro">${esc(msgErro(r))}</p>` : (r.json.length ? r.json.map((x) => `<div class="row"><span class="material-icons ${x.erro ? 'am' : 'up'}">${x.erro ? 'warning' : 'cloud_done'}</span><div style="flex:1"><b>${data(x.criado_em)} ${hora(x.criado_em)}</b><div class="mut">${x.bytes ? (x.bytes / 1048576).toFixed(2).replace('.', ',') + ' MB' : ''}${x.email ? ' · enviado por e-mail' : ''} · ${esc(x.origem || '')}${x.erro ? ' · ' + esc(x.erro) : ''}</div></div></div>`).join('') : '<div class="vazio">Nenhuma cópia ainda.</div>')) + '</div>';
  $('mn').innerHTML = html;
};
async function backupAgora() {
  const b = $('bk-agora'); b.disabled = true; b.innerHTML = '<span class="material-icons gira">autorenew</span>Fazendo…';
  const r = await rpc('admin_backup_agora');
  if (r.status !== 200) { b.disabled = false; b.innerHTML = '<span class="material-icons">cloud_upload</span>Fazer agora'; return toast(msgErro(r), true); }
  toast('Cópia iniciada. Em até 1 minuto ela chega no e-mail.');
  setTimeout(() => { if (VIEW === 'backups') VIEWS.backups(); }, 25000);
}

/* ── cliques (sem código dentro do HTML) ── */
document.addEventListener('click', (e) => {
  const el = e.target.closest('button,tr,[data-ir]'); if (!el) return;
  const d = el.dataset;
  if (el.classList.contains('nv')) return ir(d.v);
  if (d.ir) return ir(d.ir);
  if (d.acao) return VIEWS[d.acao]();
  if (d.chf) { SUP.filtro = d.chf; SUP.aberto = null; return VIEWS.suporte(); }
  if (d.ch) return abrirChamado(Number(d.ch));
  if (d.resp) return responder(Number(d.resp));
  if (d.fin) return finalizar(Number(d.fin));
  if (d.email) return P.abrirEmail(d.email);
  if (d.chlink) { e.stopPropagation(); SUP.filtro = 'abertos'; ir('suporte'); setTimeout(() => abrirChamado(Number(d.chlink)), 600); return; }
  if (d.erro) { erroAberto = erroAberto === Number(d.erro) ? null : Number(d.erro); return VIEWS.erros(); }
  if (d.conta !== undefined) return abrirConta(d.conta);
  if (d.us !== undefined) { const p = US.lista[Number(d.us)]; if (p) ficha(p.conta_id); return; }
  if (d.dias) { US.dias = Number(d.dias); const b = $('us-ficha'); if (b) b.innerHTML = fichaHtml(); return; }
  if (d.av) return avChip(d.av, d.v);
  if (d.enc) return encerrar(Number(d.enc));
  switch (el.id) {
    case 'lg-entrar': return entrar();
    case 'lg-google': return entrarGoogle();
    case 'bt-sair': return confirmar('Sair da conta?', 'Para voltar, entre de novo com e-mail e senha.', 'Sair', true).then((s) => s && sair());
    case 'us-bt': return buscar();
    case 'us-pro': return darPro();
    case 'us-free': return tornarFree();
    case 'cd-gerar': return gerarCodigos();
    case 'cd-copiar': navigator.clipboard.writeText(gerados.join('\n')).then(() => toast('Copiado!')); return;
    case 'av-pub': return publicar();
    case 'bk-agora': return backupAgora();
  }
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && (e.target.id === 'lg-senha' || e.target.id === 'lg-email')) entrar();
  if (e.key === 'Enter' && e.target.id === 'us-q') buscar();
  if (e.key === 'Enter' && e.ctrlKey && e.target.id === 'ch-txt') { const b = $('ch-env'); if (b && !b.disabled) responder(Number(b.dataset.resp)); }
  if (e.key === 'F5') { e.preventDefault(); if (!$('tela-app').hidden) VIEWS[VIEW](); }
});

/* ── início: volta logado se a sessão guardada ainda vale ── */
(async () => {
  try { $('ver').textContent = 'versão ' + (await P.versao()); } catch (e) {}
  S = await P.sessaoLer();
  if (S && S.refresh_token) {
    if (await conferirAdmin()) return abrirApp();
    await sair(true);
  }
  $('tela-login').hidden = false;
  setTimeout(() => $('lg-email').focus(), 50);
})();
