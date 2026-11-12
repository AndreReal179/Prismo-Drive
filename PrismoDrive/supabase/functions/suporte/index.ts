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
  const { data: reg } = await adm.from("suporte").insert({
    user_id: userId, conta_id: contaId, nome, email, categoria: cat, assunto, mensagem,
    anexos: anexos.map((x) => ({ nome: x.filename, tipo: x.contentType, tam: x.content.length })), app_versao: versao, aparelho, plano,
  }).select("id").single();

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
