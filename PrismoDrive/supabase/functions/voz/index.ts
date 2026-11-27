// Prismo Drive · v2.3 · vozes humanizadas do assistente "Ei Prismo!" (Google Cloud Text-to-Speech, vozes Chirp 3 HD)
// Secret: GOOGLE_TTS_KEY (chave de API do Google Cloud, restrita à "Cloud Text-to-Speech API").
// Sem a chave a função responde 503 e o app usa a voz do próprio celular.
// • Só com conta (login). Limite por pessoa por dia (tabela voz_uso).
// • Frases fixas ("Oi! Pode falar.") ficam guardadas no bucket privado voz-cache: geradas uma vez só.
//   Frases com valores (saldo, R$) nunca são guardadas.
import { createClient } from "npm:@supabase/supabase-js@2.45.4";

function chaveServico(): string {
  const a = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  if (a) return a;
  try { const j = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") || "{}"); const v = j.default || Object.values(j)[0]; if (v) return String(v); } catch { /* */ }
  return Deno.env.get("SUPABASE_SECRET_KEY") || "";
}
const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const resp = (s: number, o: unknown) =>
  new Response(JSON.stringify(o), { status: s, headers: { ...CORS, "Content-Type": "application/json" } });

// 3 vozes femininas e 3 masculinas (o app mostra os nomes Ana, Clara, Júlia, Bruno, Rafael e Diego)
const VOZES: Record<string, string> = {
  ana: "pt-BR-Chirp3-HD-Aoede", clara: "pt-BR-Chirp3-HD-Kore", julia: "pt-BR-Chirp3-HD-Leda",
  bruno: "pt-BR-Chirp3-HD-Charon", rafael: "pt-BR-Chirp3-HD-Puck", diego: "pt-BR-Chirp3-HD-Fenrir",
};
const LIMITE_DIA = 400;

function b64(buf: Uint8Array): string {
  let s = ""; const passo = 0x8000;
  for (let i = 0; i < buf.length; i += passo) s += String.fromCharCode(...buf.subarray(i, i + passo));
  return btoa(s);
}
async function sha(t: string): Promise<string> {
  const h = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(t)));
  return Array.from(h).map((x) => x.toString(16).padStart(2, "0")).join("");
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return resp(405, { erro: "metodo" });
  const chave = Deno.env.get("GOOGLE_TTS_KEY");
  if (!chave) return resp(503, { erro: "sem_chave" });

  const adm = createClient(Deno.env.get("SUPABASE_URL")!, chaveServico(), { auth: { persistSession: false } });
  const jwt = (req.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
  const { data: u, error: eu } = await adm.auth.getUser(jwt);
  if (eu || !u?.user) return resp(401, { erro: "sessao" });

  let corpo: { texto?: string; voz?: string; fixo?: boolean } = {};
  try { corpo = await req.json(); } catch { /* vazio */ }
  const texto = String(corpo.texto || "").replace(/\s+/g, " ").trim().slice(0, 300);
  const voz = VOZES[String(corpo.voz || "")];
  if (!texto || !voz) return resp(400, { erro: "dados" });
  // frase "fixa" só se não tiver número (nada de saldo ou valores no cache)
  const fixo = !!corpo.fixo && !/\d/.test(texto);

  const nome = fixo ? `${String(corpo.voz)}/${await sha(voz + "|" + texto)}.mp3` : "";
  if (fixo) {
    const { data: f } = await adm.storage.from("voz-cache").download(nome);
    if (f) return resp(200, { audio: b64(new Uint8Array(await f.arrayBuffer())), cache: true });
  }

  const { data: n, error: en } = await adm.rpc("voz_contar", { p_uid: u.user.id });
  if (en) return resp(503, { erro: "limite" });
  if (Number(n) > LIMITE_DIA) return resp(429, { erro: "limite_dia" });

  const g = await fetch("https://texttospeech.googleapis.com/v1/text:synthesize?key=" + encodeURIComponent(chave), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ input: { text: texto }, voice: { languageCode: "pt-BR", name: voz }, audioConfig: { audioEncoding: "MP3" } }),
  });
  if (!g.ok) { console.error("google", g.status, (await g.text()).slice(0, 300)); return resp(502, { erro: "google", status: g.status }); }
  const j = await g.json();
  const audio = String(j.audioContent || "");
  if (!audio) return resp(502, { erro: "vazio" });
  if (fixo) {
    const bin = Uint8Array.from(atob(audio), (c) => c.charCodeAt(0));
    await adm.storage.from("voz-cache").upload(nome, bin, { contentType: "audio/mpeg", upsert: true });
  }
  return resp(200, { audio });
});
