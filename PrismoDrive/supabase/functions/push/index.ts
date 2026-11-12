// Prismo Drive · Push: envia na hora as notificações da fila (push_fila) pelo Firebase Cloud Messaging.
// Secret: FCM_CONTA = o JSON inteiro da conta de serviço do Firebase (Configurações do projeto › Contas de serviço).
// Sem o secret a função não envia nada (o app continua avisando pelos lembretes de sempre).
// Chamada pelo banco (public.push_enviar) com { id }: só envia o que está na fila e ainda não saiu.
import { createClient } from "npm:@supabase/supabase-js@2.45.4";

function chaveServico(): string {
  const a = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (a) return a;
  try { const j = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") || "{}"); const v = j.default || Object.values(j)[0]; if (v) return String(v); } catch { /* */ }
  return Deno.env.get("SUPABASE_SECRET_KEY") || "";
}
const resp = (o: unknown, s = 200) => new Response(JSON.stringify(o), { status: s, headers: { "Content-Type": "application/json" } });

const b64url = (b: Uint8Array | string) => {
  const bin = typeof b === "string" ? new TextEncoder().encode(b) : b;
  let s = ""; bin.forEach((x) => s += String.fromCharCode(x));
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

let cache: { token: string; ate: number } | null = null;
async function tokenGoogle(conta: any): Promise<string> {
  if (cache && cache.ate > Date.now() + 60000) return cache.token;
  const agora = Math.floor(Date.now() / 1000);
  const cab = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const corpo = b64url(JSON.stringify({
    iss: conta.client_email, scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token", iat: agora, exp: agora + 3600,
  }));
  const pem = String(conta.private_key).replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const chave = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const ass = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", chave, new TextEncoder().encode(cab + "." + corpo)));
  const r = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: "grant_type=" + encodeURIComponent("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + cab + "." + corpo + "." + b64url(ass),
  });
  const j = await r.json();
  if (!j.access_token) throw new Error("google_token: " + (j.error_description || j.error || r.status));
  cache = { token: j.access_token, ate: Date.now() + (Number(j.expires_in) || 3600) * 1000 };
  return cache.token;
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return resp({ ok: true, servico: "prismo-push" });
  let b: any = {};
  try { b = await req.json(); } catch { return resp({ erro: "corpo" }, 400); }
  const id = Number(b.id || 0);
  if (!id) return resp({ erro: "id" }, 400);

  const adm = createClient(Deno.env.get("SUPABASE_URL")!, chaveServico(), { auth: { persistSession: false } });
  const { data: f } = await adm.from("push_fila").select("*").eq("id", id).is("enviado_em", null).maybeSingle();
  if (!f) return resp({ ignorado: "nao_encontrado_ou_enviado" });
  if (Date.parse(f.criado_em) < Date.now() - 6 * 3600e3) {
    await adm.from("push_fila").update({ erro: "vencido" }).eq("id", id);
    return resp({ ignorado: "vencido" });
  }

  let conta: any = null;
  try { conta = JSON.parse(Deno.env.get("FCM_CONTA") || "null"); } catch { conta = null; }
  if (!conta?.private_key || !conta?.client_email || !conta?.project_id) {
    await adm.from("push_fila").update({ erro: "sem_config" }).eq("id", id);
    return resp({ ignorado: "sem_config" });
  }

  // destinos: tópico (anúncios) ou aparelhos da pessoa / dos administradores
  let tokens: string[] = [];
  if (!f.topico) {
    let uids: string[] = [];
    if (f.user_id) uids = [f.user_id];
    if (f.admins) {
      const { data: a } = await adm.from("perfis").select("id").eq("plano", "admin");
      uids = uids.concat((a || []).map((x: any) => x.id));
    }
    if (uids.length) {
      const { data: t } = await adm.from("push_tokens").select("token").in("user_id", uids);
      tokens = (t || []).map((x: any) => x.token);
    }
    if (!tokens.length) {
      await adm.from("push_fila").update({ enviado_em: new Date().toISOString(), enviados: 0, erro: "sem_aparelho" }).eq("id", id);
      return resp({ ok: true, enviados: 0 });
    }
  }

  let acesso: string;
  try { acesso = await tokenGoogle(conta); } catch (e) {
    await adm.from("push_fila").update({ erro: String((e as Error).message).slice(0, 300) }).eq("id", id);
    return resp({ erro: "google" }, 502);
  }

  const url = `https://fcm.googleapis.com/v1/projects/${conta.project_id}/messages:send`;
  // só dados: quem desenha a notificação é o app (mesmo som, ícone e número dos lembretes → sem aviso repetido)
  const dados = { nid: String(f.nid), titulo: String(f.titulo), texto: String(f.texto), fila: String(f.id) };
  const enviar = async (alvo: Record<string, string>) => {
    const r = await fetch(url, {
      method: "POST", headers: { "Authorization": `Bearer ${acesso}`, "Content-Type": "application/json" },
      body: JSON.stringify({ message: { ...alvo, data: dados, android: { priority: "HIGH", ttl: "21600s" } } }),
    });
    if (r.ok) return "ok";
    const j = await r.json().catch(() => ({}));
    const st = JSON.stringify(j?.error?.details || j?.error?.status || r.status);
    return /UNREGISTERED|INVALID_ARGUMENT|NOT_FOUND/.test(st) ? "invalido" : "falha:" + st.slice(0, 120);
  };

  let ok = 0; const erros: string[] = [], invalidos: string[] = [];
  if (f.topico) {
    const r = await enviar({ topic: f.topico });
    if (r === "ok") ok++; else erros.push(r);
  } else {
    for (const t of [...new Set(tokens)]) {
      const r = await enviar({ token: t });
      if (r === "ok") ok++; else if (r === "invalido") invalidos.push(t); else erros.push(r);
    }
  }
  if (invalidos.length) await adm.from("push_tokens").delete().in("token", invalidos);
  await adm.from("push_fila").update({ enviado_em: new Date().toISOString(), enviados: ok, erro: erros.length ? erros.join(" | ").slice(0, 300) : null }).eq("id", id);
  return resp({ ok: true, enviados: ok });
});
