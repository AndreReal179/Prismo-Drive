// Prismo Drive · Suporte: recebe a mensagem do app e envia direto para o e-mail da empresa (com anexos)
// Secrets: SMTP_SENHA (senha de app do Gmail prismolabtec@gmail.com).
import { createClient } from "npm:@supabase/supabase-js@2.45.4";
import nodemailer from "npm:nodemailer@6.9.16";

const DESTINO = "prismolabtec@gmail.com";
const MAX_ARQ = 4, MAX_TOTAL = 7 * 1024 * 1024; // bytes depois de decodificar
const TIPOS = /^(image\/(png|jpe?g|webp|gif|heic|heif)|application\/pdf|text\/plain|video\/mp4)$/i;
const CATS: Record<string, string> = { duvida: "Dúvida", problema: "Problema", bug: "🐞 BUG", pagamento: "Pagamento", sugestao: "Sugestão", outro: "Outro" };
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const resp = (s: number, o: unknown) => new Response(JSON.stringify(o), { status: s, headers: { ...CORS, "Content-Type": "application/json" } });
function chaveServico(): string {
  const a = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (a) return a;
  try { const j = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") || "{}"); const v = j.default || Object.values(j)[0]; if (v) return String(v); } catch { /* */ }
  return Deno.env.get("SUPABASE_SECRET_KEY") || "";
}
const esc = (s: string) => String(s || "").replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]!));
// v2.1 (segurança): resumo do IP (nunca o IP em texto) para limitar abuso de quem manda sem conta
async function ipResumo(req: Request): Promise<string> {
  const ip = (req.headers.get("x-forwarded-for") || "").split(",")[0].trim();
  if (!ip) return "";
  const h = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode("prismo:" + ip)));
  return Array.from(h).slice(0, 16).map((b) => b.toString(16).padStart(2, "0")).join("");
}
const emailOk = (e: string) => /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(e);

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return resp(405, { erro: "metodo" });
  const senha = Deno.env.get("SMTP_SENHA");
  if (!senha) return resp(503, { erro: "desligado", msg: "O suporte ainda não está configurado. Tente mais tarde." });

  let b: any = {};
  try { b = await req.json(); } catch { return resp(400, { erro: "corpo", msg: "Mensagem inválida." }); }

  // ── Administrador respondendo um chamado: grava no app e manda por e-mail para o cliente ──
  if (b.acao === "responder") {
    const adm0 = createClient(Deno.env.get("SUPABASE_URL")!, chaveServico(), { auth: { persistSession: false } });
    const jwt0 = (req.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
    const { data: u0 } = await adm0.auth.getUser(jwt0);
    if (!u0?.user) return resp(401, { erro: "sessao", msg: "Entre na sua conta de novo." });
    const { data: eu } = await adm0.from("perfis").select("plano,nome").eq("id", u0.user.id).maybeSingle();
    if (eu?.plano !== "admin") return resp(403, { erro: "sem_permissao", msg: "Só o administrador pode responder." });
    const id = Number(b.chamado || 0), texto = String(b.texto || "").trim().slice(0, 4000);
    if (!id || texto.length < 2) return resp(400, { erro: "texto", msg: "Escreva a resposta." });
    const { data: c } = await adm0.from("suporte").select("*").eq("id", id).maybeSingle();
    if (!c) return resp(404, { erro: "chamado", msg: "Chamado não encontrado." });
    await adm0.from("suporte_msgs").insert({ chamado_id: id, autor: "admin", texto });
    const agora = new Date().toISOString();
    await adm0.from("suporte").update({ status: "respondido", respondido_em: agora, visto_em: c.visto_em || agora, cliente_visto_em: null, atualizado_em: agora }).eq("id", id);
    let emailOk2 = true;
    try {
      const t = nodemailer.createTransport({ host: "smtp.gmail.com", port: 465, secure: true, auth: { user: DESTINO, pass: senha } });
      await t.sendMail({
        from: `"Suporte Prismo Drive" <${DESTINO}>`, to: c.email, replyTo: DESTINO,
        subject: `Re: ${c.assunto} · protocolo nº ${id}`,
        html: `<div style="font-family:Arial,sans-serif;font-size:14px;color:#111;max-width:560px">
          <p>Olá${c.nome ? ", " + esc(String(c.nome).split(" ")[0]) : ""}! A equipe do Prismo Drive respondeu seu chamado <b>nº ${id}</b>:</p>
          <div style="white-space:pre-wrap;background:#f4f6f5;border-left:4px solid #2ea44f;padding:12px 14px;border-radius:6px">${esc(texto)}</div>
          <p style="color:#555;font-size:13px">Você também vê a resposta no app, em Menu › Suporte › Meus chamados.</p>
          <p style="color:#999;font-size:12px;border-top:1px solid #eee;padding-top:10px">Sua mensagem: ${esc(c.assunto)}<br>${esc(String(c.mensagem).slice(0, 600))}</p></div>`,
      });
    } catch { emailOk2 = false; }
    return resp(200, { ok: true, email: emailOk2 });
  }
  // ── Relatório automático de erro: o app envia sozinho; o 1º de cada tipo vira chamado e e-mail ──
  if (b.acao === "erro") {
    const admE = createClient(Deno.env.get("SUPABASE_URL")!, chaveServico(), { auth: { persistSession: false } });
    const ass = String(b.assinatura || "").replace(/[^a-z0-9]/gi, "").slice(0, 64);
    const msgE = String(b.mensagem || "").trim().slice(0, 500);
    if (ass.length < 6 || msgE.length < 3) return resp(400, { erro: "dados" });
    const versaoE = String(b.versao || "?").slice(0, 20), origem = /^(js|android|promessa)$/.test(String(b.origem)) ? String(b.origem) : "js";
    const pilha = String(b.pilha || "").slice(0, 4000), telaE = String(b.tela || "").slice(0, 60), aparE = String(b.aparelho || "").slice(0, 80);
    let uidE: string | null = null, emailE = DESTINO, nomeE = "", contaE = "", planoE = "usuario";
    const jwtE = (req.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
    if (jwtE && jwtE.split(".").length === 3) {
      const { data: u } = await admE.auth.getUser(jwtE);
      if (u?.user) {
        uidE = u.user.id; emailE = u.user.email || DESTINO;
        const { data: p } = await admE.from("perfis").select("conta_id,nome,plano,pro_ate").eq("id", uidE).maybeSingle();
        if (p) { contaE = p.conta_id || ""; nomeE = p.nome || ""; planoE = p.plano === "admin" ? "admin" : (p.pro_ate && Date.parse(p.pro_ate) > Date.now() ? "pro" : "free"); }
      }
    }
    const { data: reg, error: errE } = await admE.rpc("erro_registrar", {
      p_assinatura: ass, p_versao: versaoE, p_origem: origem, p_mensagem: msgE, p_pilha: pilha, p_tela: telaE, p_aparelho: aparE, p_nova_pessoa: !!b.primeira_vez,
    });
    if (errE || !reg) return resp(500, { erro: "banco" });
    // só o primeiro de cada erro (por versão) abre chamado; no máximo 30 erros novos por hora
    if (!reg.novo || reg.chamado || Number(reg.recentes) > 30) return resp(200, { ok: true, novo: false, vezes: reg.ocorrencias });
    // v2.1: sem conta, cada rede abre no máximo 3 chamados de erro por hora; e no total até 40 e-mails de erro por dia
    const ipE = await ipResumo(req);
    const umaHoraE = new Date(Date.now() - 3600e3).toISOString(), umDiaE = new Date(Date.now() - 86400e3).toISOString();
    if (!uidE && ipE) {
      const { count: nIp } = await admE.from("suporte").select("id", { count: "exact", head: true }).eq("categoria", "erro_auto").eq("ip", ipE).gte("criado_em", umaHoraE);
      if ((nIp || 0) >= 3) return resp(200, { ok: true, novo: false, vezes: reg.ocorrencias });
    }
    const { count: nDia } = await admE.from("suporte").select("id", { count: "exact", head: true }).eq("categoria", "erro_auto").gte("criado_em", umDiaE);
    if ((nDia || 0) >= 40) return resp(200, { ok: true, novo: false, vezes: reg.ocorrencias });
    const ORIG: Record<string, string> = { js: "Tela do app (JavaScript)", promessa: "Tela do app (tarefa em segundo plano)", android: "Android (o app fechou)" };
    const assuntoE = "Erro automático: " + msgE.replace(/\s+/g, " ").slice(0, 90);
    const mensagemE = `Relatório enviado automaticamente pelo app.\n\nOnde: ${ORIG[origem]}${telaE ? " · tela " + telaE : ""}\nVersão: ${versaoE}\nAparelho: ${aparE || "?"}\n\nErro: ${msgE}\n\n${pilha.slice(0, 2500)}`;
    const linhaE: Record<string, unknown> = {
      user_id: uidE, conta_id: contaE, nome: nomeE || "Relatório automático", email: emailE, categoria: "erro_auto",
      assunto: assuntoE.slice(0, 120), mensagem: mensagemE.slice(0, 4000), anexos: [], app_versao: versaoE, aparelho: aparE, plano: planoE, ip: ipE || null,
    };
    let { data: ch, error: eCh } = await admE.from("suporte").insert(linhaE).select("id").single();
    if (eCh && /ip/.test(eCh.message || "")) { delete linhaE.ip; ({ data: ch } = await admE.from("suporte").insert(linhaE).select("id").single()); } // banco sem a coluna ip ainda
    if (ch?.id) await admE.from("erros_app").update({ chamado_id: ch.id }).eq("id", reg.id);
    try {
      const t = nodemailer.createTransport({ host: "smtp.gmail.com", port: 465, secure: true, auth: { user: DESTINO, pass: senha } });
      await t.sendMail({
        from: `"Erros Prismo Drive" <${DESTINO}>`, to: DESTINO,
        subject: `[Erro automático] ${msgE.replace(/\s+/g, " ").slice(0, 80)} · v${versaoE}${ch?.id ? " · nº " + ch.id : ""}`,
        html: `<div style="font-family:Arial,sans-serif;font-size:14px;color:#111"><h2 style="margin:0 0 6px">⚠️ Erro automático no app</h2>
          <p style="color:#555;margin:0 0 12px">${esc(ORIG[origem])}${telaE ? " · tela " + esc(telaE) : ""} · versão ${esc(versaoE)} · ${esc(aparE || "?")}<br>
          Conta: ${esc(nomeE || "sem conta")}${contaE ? " · ID " + esc(contaE) : ""} · plano ${esc(planoE)}</p>
          <div style="background:#fff4f4;border-left:4px solid #e5534b;padding:10px 12px;border-radius:6px;font-weight:bold">${esc(msgE)}</div>
          <pre style="white-space:pre-wrap;font-size:12px;background:#f4f6f5;padding:10px;border-radius:6px">${esc(pilha.slice(0, 3000))}</pre>
          <p style="color:#777;font-size:12px">Chamado aberto no app (Administração › Suporte). Os próximos iguais só somam no contador.</p></div>`,
      });
      if (ch?.id) await admE.from("suporte").update({ enviado: true }).eq("id", ch.id);
    } catch { /* o chamado já ficou registrado */ }
    return resp(200, { ok: true, novo: true, id: ch?.id || null });
  }

  const cat = CATS[String(b.categoria || "")] ? String(b.categoria) : "outro";
  const assunto = String(b.assunto || "").trim().slice(0, 120);
  const mensagem = String(b.mensagem || "").trim().slice(0, 4000);
  if (assunto.length < 3) return resp(400, { erro: "assunto", msg: "Escreva o assunto." });
  if (mensagem.length < 10) return resp(400, { erro: "mensagem", msg: "Escreva a mensagem (pelo menos 10 letras)." });

  const adm = createClient(Deno.env.get("SUPABASE_URL")!, chaveServico(), { auth: { persistSession: false } });
  // conta (opcional)
  let userId: string | null = null, contaId = "", nome = String(b.nome || "").slice(0, 80), email = String(b.email || "").trim().toLowerCase().slice(0, 120), plano = "usuario";
  const jwt = (req.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
  if (jwt && jwt.split(".").length === 3) {
    const { data: u } = await adm.auth.getUser(jwt);
    if (u?.user) {
      userId = u.user.id; email = u.user.email || email;
      const { data: p } = await adm.from("perfis").select("conta_id,nome,plano,pro_ate").eq("id", userId).maybeSingle();
      if (p) { contaId = p.conta_id || ""; nome = p.nome || nome; plano = p.plano === "admin" ? "admin" : (p.pro_ate && Date.parse(p.pro_ate) > Date.now() ? "pro" : "free"); }
    }
  }
  if (!emailOk(email)) return resp(400, { erro: "email", msg: "Informe um e-mail válido para a resposta." });

  // limite: 5 mensagens por hora por pessoa
  const umaHora = new Date(Date.now() - 3600e3).toISOString();
  const { count } = await adm.from("suporte").select("id", { count: "exact", head: true })
    .gte("criado_em", umaHora).or(userId ? `user_id.eq.${userId}` : `email.eq.${email}`);
  if ((count || 0) >= 5) return resp(429, { erro: "limite", msg: "Você já enviou várias mensagens agora. Aguarde um pouco." });
  // v2.1 (segurança): sem conta, trocar o e-mail não burla o limite: 3 por hora por rede e 30 por hora no total
  const ip = await ipResumo(req);
  if (!userId) {
    if (ip) {
      const { count: nIp } = await adm.from("suporte").select("id", { count: "exact", head: true }).is("user_id", null).neq("categoria", "erro_auto").eq("ip", ip).gte("criado_em", umaHora);
      if ((nIp || 0) >= 3) return resp(429, { erro: "limite", msg: "Muitas mensagens enviadas desta rede agora. Aguarde um pouco ou entre na sua conta." });
    }
    const { count: nAnon } = await adm.from("suporte").select("id", { count: "exact", head: true }).is("user_id", null).neq("categoria", "erro_auto").gte("criado_em", umaHora);
    if ((nAnon || 0) >= 30) return resp(429, { erro: "limite", msg: "O suporte está recebendo muitas mensagens agora. Tente de novo em alguns minutos ou entre na sua conta." });
  }

  // anexos
  const anexos: { filename: string; content: Uint8Array; contentType: string }[] = [];
  let total = 0;
  for (const a of (Array.isArray(b.anexos) ? b.anexos : []).slice(0, MAX_ARQ)) {
    const tipo = String(a?.tipo || "");
    if (!TIPOS.test(tipo)) continue;
    let bin: Uint8Array;
    try { bin = Uint8Array.from(atob(String(a.base64 || "")), (c) => c.charCodeAt(0)); } catch { continue; }
    total += bin.length;
    if (total > MAX_TOTAL) return resp(413, { erro: "anexos", msg: "Os anexos passaram de 7 MB. Envie arquivos menores." });
    anexos.push({ filename: String(a.nome || "anexo").replace(/[^\w.\- ()À-ú]/g, "_").slice(0, 80), content: bin, contentType: tipo });
  }

  const versao = String(b.versao || "").slice(0, 20), aparelho = String(b.aparelho || "").slice(0, 80);
  const linha: Record<string, unknown> = {
    user_id: userId, conta_id: contaId, nome, email, categoria: cat, assunto, mensagem, ip: ip || null,
    anexos: anexos.map((x) => ({ nome: x.filename, tipo: x.contentType, tam: x.content.length })), app_versao: versao, aparelho, plano,
  };
  let { data: reg, error: eReg } = await adm.from("suporte").insert(linha).select("id").single();
  if (eReg && /ip/.test(eReg.message || "")) { delete linha.ip; ({ data: reg } = await adm.from("suporte").insert(linha).select("id").single()); } // banco sem a coluna ip ainda

  const html = `<div style="font-family:Arial,sans-serif;font-size:14px;color:#111">
  <h2 style="margin:0 0 6px">${esc(CATS[cat])} · ${esc(assunto)}</h2>
  <p style="margin:0 0 14px;color:#555">Mensagem enviada pelo app Prismo Drive${reg?.id ? " · nº " + reg.id : ""}</p>
  <table style="border-collapse:collapse;font-size:13px;margin-bottom:14px">
  <tr><td style="padding:3px 10px 3px 0;color:#777">Nome</td><td><b>${esc(nome || "(sem nome)")}</b></td></tr>
  <tr><td style="padding:3px 10px 3px 0;color:#777">E-mail</td><td>${esc(email)}</td></tr>
  <tr><td style="padding:3px 10px 3px 0;color:#777">ID da conta</td><td>${esc(contaId || "sem conta")}</td></tr>
  <tr><td style="padding:3px 10px 3px 0;color:#777">Plano</td><td>${esc(plano)}</td></tr>
  <tr><td style="padding:3px 10px 3px 0;color:#777">App</td><td>${esc(versao)} · ${esc(aparelho)}</td></tr>
  <tr><td style="padding:3px 10px 3px 0;color:#777">Anexos</td><td>${anexos.length}</td></tr></table>
  <div style="white-space:pre-wrap;background:#f4f6f5;border-left:4px solid #2ea44f;padding:12px 14px;border-radius:6px">${esc(mensagem)}</div>
  <p style="color:#777;font-size:12px;margin-top:14px">Responda este e-mail para falar direto com o cliente.</p></div>`;

  try {
    const t = nodemailer.createTransport({ host: "smtp.gmail.com", port: 465, secure: true, auth: { user: DESTINO, pass: senha } });
    await t.sendMail({
      from: `"Suporte Prismo Drive" <${DESTINO}>`, to: DESTINO, replyTo: nome ? `"${nome.replace(/"/g, "")}" <${email}>` : email,
      subject: `[Suporte Prismo Drive] ${CATS[cat]} · ${assunto}${contaId ? " · ID " + contaId : ""}`,
      html, attachments: anexos,
    });
    if (reg?.id) await adm.from("suporte").update({ enviado: true }).eq("id", reg.id);
  } catch (e) {
    if (reg?.id) await adm.from("suporte").update({ erro: String((e as Error)?.message || e).slice(0, 300) }).eq("id", reg.id);
    return resp(502, { erro: "email", msg: "Não foi possível enviar agora. Tente de novo em instantes." });
  }
  return resp(200, { ok: true, id: reg?.id || null });
});
