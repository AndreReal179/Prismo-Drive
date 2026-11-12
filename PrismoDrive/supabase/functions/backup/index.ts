// Prismo Drive · cópia de segurança semanal do banco (chamada pelo agendador do banco ou pelo admin)
// Guarda no Storage (bucket privado "copias-seguranca", últimas 8) e manda por e-mail para a empresa.
import { createClient } from "npm:@supabase/supabase-js@2.45.4";
import nodemailer from "npm:nodemailer@6.9.16";

const DESTINO = "prismolabtec@gmail.com";
const BUCKET = "copias-seguranca";
const TABELAS = ["perfis", "backups", "pagamentos", "codigos_pro", "admin_log", "avisos", "suporte", "suporte_msgs", "indicacoes", "indicacao_premios"];
const MAX_EMAIL = 18 * 1024 * 1024;
function chaveServico(): string {
  const a = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (a) return a;
  try { const j = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") || "{}"); const v = j.default || Object.values(j)[0]; if (v) return String(v); } catch { /* */ }
  return Deno.env.get("SUPABASE_SECRET_KEY") || "";
}
const ok = (o: unknown, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method !== "POST") return ok({ erro: "metodo" }, 405);
  let b: any = {};
  try { b = await req.json(); } catch { /* */ }
  const adm = createClient(Deno.env.get("SUPABASE_URL")!, chaveServico(), { auth: { persistSession: false } });
  // só roda com um pedido válido criado pelo próprio banco (uso único, até 15 min)
  const nonce = String(b.nonce || "");
  if (!/^[0-9a-f-]{36}$/i.test(nonce)) return ok({ erro: "pedido" }, 403);
  const { data: ped } = await adm.from("backup_pedidos").delete().eq("nonce", nonce).select("criado_em").maybeSingle();
  if (!ped || Date.now() - Date.parse(ped.criado_em) > 15 * 60e3) return ok({ erro: "pedido" }, 403);
  const origem = String(b.origem || "semanal").slice(0, 20);

  const linhas: Record<string, number> = {};
  const dados: Record<string, unknown[]> = {};
  try {
    for (const t of TABELAS) {
      const todas: unknown[] = [];
      for (let de = 0; ; de += 1000) {
        const { data, error } = await adm.from(t).select("*").range(de, de + 999);
        if (error) throw new Error(t + ": " + error.message);
        todas.push(...(data || []));
        if (!data || data.length < 1000) break;
      }
      dados[t] = todas; linhas[t] = todas.length;
    }
  } catch (e) {
    await adm.from("backup_log").insert({ erro: String((e as Error).message).slice(0, 300), origem });
    return ok({ erro: "exportar" }, 500);
  }
  const agora = new Date();
  const json = JSON.stringify({ app: "Prismo Drive", versao_backup: 1, criado_em: agora.toISOString(), linhas, dados });
  const gz = new Uint8Array(await new Response(new Blob([json]).stream().pipeThrough(new CompressionStream("gzip"))).arrayBuffer());
  const dia = agora.toISOString().slice(0, 16).replace(/[:T]/g, "-");
  const arquivo = `prismo-backup-${dia}.json.gz`;

  let erro = "";
  try {
    await adm.storage.createBucket(BUCKET, { public: false }).catch(() => {});
    const up = await adm.storage.from(BUCKET).upload(arquivo, gz, { contentType: "application/gzip", upsert: true });
    if (up.error) erro = "storage: " + up.error.message;
    const { data: lista } = await adm.storage.from(BUCKET).list("", { limit: 100, sortBy: { column: "name", order: "desc" } });
    const velhos = (lista || []).filter((f) => f.name.startsWith("prismo-backup-")).slice(8).map((f) => f.name);
    if (velhos.length) await adm.storage.from(BUCKET).remove(velhos);
  } catch (e) { erro = "storage: " + String((e as Error).message); }

  let enviou = false;
  const senha = Deno.env.get("SMTP_SENHA");
  if (senha) {
    try {
      const t = nodemailer.createTransport({ host: "smtp.gmail.com", port: 465, secure: true, auth: { user: DESTINO, pass: senha } });
      const resumo = Object.entries(linhas).map(([k, v]) => `<tr><td style="padding:2px 12px 2px 0">${k}</td><td><b>${v}</b></td></tr>`).join("");
      await t.sendMail({
        from: `"Prismo Drive · Backup" <${DESTINO}>`, to: DESTINO,
        subject: `🗄️ Backup ${origem} do Prismo Drive · ${agora.toLocaleDateString("pt-BR", { timeZone: "America/Sao_Paulo" })}`,
        html: `<div style="font-family:Arial,sans-serif;font-size:14px"><p>Cópia de segurança do banco de dados (${(gz.length / 1048576).toFixed(2)} MB compactado).</p><table style="font-size:13px">${resumo}</table>
          <p style="color:#777;font-size:12px">${gz.length <= MAX_EMAIL ? "O arquivo vai anexado. " : "O arquivo ficou grande demais para o e-mail. "}Ele também fica guardado no Supabase › Storage › ${BUCKET} (últimas 8 cópias).${erro ? "<br>Aviso: " + erro : ""}</p></div>`,
        attachments: gz.length <= MAX_EMAIL ? [{ filename: arquivo, content: gz, contentType: "application/gzip" }] : [],
      });
      enviou = true;
    } catch (e) { erro = (erro ? erro + " · " : "") + "email: " + String((e as Error).message).slice(0, 120); }
  }
  await adm.from("backup_log").insert({ arquivo, bytes: gz.length, linhas, email: enviou, erro: erro || null, origem });
  return ok({ ok: true, arquivo, bytes: gz.length, email: enviou });
});
