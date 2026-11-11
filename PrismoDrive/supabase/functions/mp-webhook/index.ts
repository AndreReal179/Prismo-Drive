// Prismo Drive · recebe o aviso do Mercado Pago e libera (ou retira) os dias de Pro
// Secrets: MP_ACCESS_TOKEN e MP_WEBHOOK_SECRET (assinatura secreta do webhook).
// Nunca confia no conteúdo do aviso: busca o pagamento direto no Mercado Pago.
import { createClient } from "npm:@supabase/supabase-js@2.45.4";

function chaveServico(): string {
  const a = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (a) return a;
  try { const j = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") || "{}"); const v = j.default || Object.values(j)[0]; if (v) return String(v); } catch { /* */ }
  return Deno.env.get("SUPABASE_SECRET_KEY") || "";
}

const ok = (o: unknown = { ok: true }, s = 200) =>
  new Response(JSON.stringify(o), { status: s, headers: { "Content-Type": "application/json" } });

async function hmacHex(chave: string, texto: string) {
  const k = await crypto.subtle.importKey("raw", new TextEncoder().encode(chave), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const a = new Uint8Array(await crypto.subtle.sign("HMAC", k, new TextEncoder().encode(texto)));
  return Array.from(a).map((b) => b.toString(16).padStart(2, "0")).join("");
}
function iguais(a: string, b: string) {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

Deno.serve(async (req) => {
  if (req.method === "GET") return ok({ ok: true, servico: "prismo-mp-webhook" });
  const tokenMp = Deno.env.get("MP_ACCESS_TOKEN");
  if (!tokenMp) return ok({ erro: "sem-token" }, 503);

  const u = new URL(req.url);
  let corpo: any = {};
  try { corpo = await req.json(); } catch { /* aviso sem corpo */ }
  const tipo = String(corpo.type || corpo.topic || u.searchParams.get("type") || u.searchParams.get("topic") || "");
  let pid = String(corpo?.data?.id || u.searchParams.get("data.id") || u.searchParams.get("id") || "");
  if (tipo !== "payment" || !pid) return ok({ ignorado: tipo || "sem-tipo" });

  // assinatura do Mercado Pago (x-signature: ts=…,v1=…)
  const segredo = Deno.env.get("MP_WEBHOOK_SECRET");
  if (segredo) {
    const sig = req.headers.get("x-signature") || "";
    const rid = req.headers.get("x-request-id") || "";
    const partes: Record<string, string> = {};
    sig.split(",").forEach((p) => { const [k, v] = p.split("=").map((s) => (s || "").trim()); if (k) partes[k] = v || ""; });
    const idAss = (u.searchParams.get("data.id") || pid).toLowerCase();
    let manifesto = `id:${idAss};`;
    if (rid) manifesto += `request-id:${rid};`;
    manifesto += `ts:${partes.ts || ""};`;
    const esperado = await hmacHex(segredo, manifesto);
    if (!partes.v1 || !iguais(esperado, partes.v1)) return ok({ erro: "assinatura" }, 401);
  }

  const r = await fetch(`https://api.mercadopago.com/v1/payments/${encodeURIComponent(pid)}`, {
    headers: { "Authorization": `Bearer ${tokenMp}` },
  });
  if (r.status === 404) return ok({ ignorado: "pagamento-nao-encontrado" });
  if (!r.ok) return ok({ erro: "mp", http: r.status }, 500); // o Mercado Pago tenta de novo
  const pg = await r.json();
  const pedido = String(pg.external_reference || "");
  if (!/^[0-9a-f-]{36}$/i.test(pedido)) return ok({ ignorado: "sem-pedido" });
  if (pg.currency_id && pg.currency_id !== "BRL") return ok({ ignorado: "moeda" });

  const adm = createClient(Deno.env.get("SUPABASE_URL")!, chaveServico(), { auth: { persistSession: false } });
  const detalhe = {
    status_detail: pg.status_detail, metodo: pg.payment_method_id, tipo: pg.payment_type_id,
    parcelas: pg.installments, aprovado_em: pg.date_approved, live: pg.live_mode,
  };
  const { data, error } = await adm.rpc("mp_registrar", {
    p_pedido: pedido, p_payment_id: String(pg.id), p_status: String(pg.status || ""),
    p_valor: Number(pg.transaction_amount || 0), p_detalhe: detalhe,
  });
  if (error) return ok({ erro: "banco", msg: error.message }, 500);
  return ok({ ok: true, resultado: data });
});
