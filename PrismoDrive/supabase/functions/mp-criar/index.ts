// Prismo Drive · cria o link de pagamento do Pro no Mercado Pago (Checkout Pro)
// Secrets: MP_ACCESS_TOKEN (Mercado Pago). SUPABASE_URL / SUPABASE_SERVICE_ROLE_KEY já existem.
import { createClient } from "npm:@supabase/supabase-js@2.45.4";

function chaveServico(): string {
  const a = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (a) return a;
  try { const j = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") || "{}"); const v = j.default || Object.values(j)[0]; if (v) return String(v); } catch { /* */ }
  return Deno.env.get("SUPABASE_SECRET_KEY") || "";
}

const PLANOS: Record<string, { nome: string; dias: number; preco: number; parcelas: number }> = {
  mensal: { nome: "Mensal", dias: 30, preco: 29.9, parcelas: 1 },
  trimestral: { nome: "Trimestral", dias: 90, preco: 79.9, parcelas: 1 },
  semestral: { nome: "Semestral", dias: 180, preco: 149.9, parcelas: 3 },
  anual: { nome: "Anual", dias: 365, preco: 249.9, parcelas: 3 },
};
const VOLTA = "https://andrereal179.github.io/Prismo-Drive/pagamento.html";
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const resp = (s: number, o: unknown) =>
  new Response(JSON.stringify(o), { status: s, headers: { ...CORS, "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return resp(405, { erro: "metodo" });
  const tokenMp = Deno.env.get("MP_ACCESS_TOKEN");
  if (!tokenMp) return resp(503, { erro: "pagamento-desligado", msg: "Pagamento ainda não configurado." });

  const url = Deno.env.get("SUPABASE_URL")!;
  const adm = createClient(url, chaveServico(), { auth: { persistSession: false } });
  const jwt = (req.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
  const { data: u, error: eu } = await adm.auth.getUser(jwt);
  if (eu || !u?.user) return resp(401, { erro: "sessao", msg: "Entre na sua conta de novo." });

  let corpo: { plano?: string } = {};
  try { corpo = await req.json(); } catch { /* vazio */ }
  const id = String(corpo.plano || "");
  const p = PLANOS[id];
  if (!p) return resp(400, { erro: "plano", msg: "Plano inválido." });

  const { data: perfil } = await adm.from("perfis").select("conta_id,nome,email").eq("id", u.user.id).maybeSingle();
  const contaId = perfil?.conta_id || "";

  const { data: ped, error: ep } = await adm.from("pagamentos")
    .insert({ user_id: u.user.id, conta_id: contaId, plano: id, dias: p.dias, valor: p.preco })
    .select("id").single();
  if (ep || !ped) return resp(500, { erro: "pedido", msg: "Não foi possível criar o pedido." });

  const pref = {
    items: [{
      id: "pro_" + id,
      title: `Prismo Drive Pro ${p.nome} (${p.dias} dias)`,
      description: `Plano Pro por ${p.dias} dias · conta ${contaId}`,
      category_id: "services",
      quantity: 1,
      currency_id: "BRL",
      unit_price: p.preco,
    }],
    payer: { email: u.user.email || perfil?.email || undefined },
    external_reference: ped.id,
    metadata: { conta_id: contaId, plano: id, dias: p.dias },
    notification_url: `${url}/functions/v1/mp-webhook`,
    back_urls: { success: VOLTA + "?s=ok", pending: VOLTA + "?s=pendente", failure: VOLTA + "?s=falhou" },
    auto_return: "approved",
    statement_descriptor: "PRISMODRIVE",
    payment_methods: {
      excluded_payment_types: [{ id: "ticket" }, { id: "atm" }],
      installments: p.parcelas,
    },
    expires: true,
    expiration_date_to: new Date(Date.now() + 24 * 3600e3).toISOString(),
  };
  const r = await fetch("https://api.mercadopago.com/checkout/preferences", {
    method: "POST",
    headers: { "Authorization": `Bearer ${tokenMp}`, "Content-Type": "application/json", "X-Idempotency-Key": ped.id },
    body: JSON.stringify(pref),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || !j.init_point) {
    await adm.from("pagamentos").update({ status: "erro_mp", detalhe: { http: r.status, resposta: j } }).eq("id", ped.id);
    return resp(502, { erro: "mp", msg: "O Mercado Pago não respondeu. Tente de novo em instantes." });
  }
  await adm.from("pagamentos").update({ mp_preference_id: j.id, status: "aguardando" }).eq("id", ped.id);
  return resp(200, { url: j.init_point, pedido: ped.id, plano: id, valor: p.preco, dias: p.dias });
});
