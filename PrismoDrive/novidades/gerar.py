#!/usr/bin/env python3
"""
Monta app/src/main/assets/novidades.js (lista "Novidades" do app).

Ordem: versão sendo gerada agora (NOVIDADES.md)  →  versões já publicadas no GitHub (notas de cada Release)
→  histórico fixo (novidades/historico.json, até a 1.7.11).

Uso: python3 novidades/gerar.py <versao> [releases.json]
  releases.json = saída de `gh api repos/<repo>/releases` (opcional; sem ele usa só o histórico).
"""
import json, os, re, sys, datetime

AQUI = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(AQUI)

def itens_de_texto(txt):
    out = []
    for l in (txt or "").splitlines():
        l = l.strip()
        if l.startswith("<!--"):
            continue
        m = re.match(r"^[•\-\*]\s+(.*)$", l)
        if m and m.group(1).strip():
            out.append(m.group(1).strip())
    return out

def chave(v):
    return [int(x) if x.isdigit() else 0 for x in re.split(r"[.\-]", v)]

def main():
    versao = sys.argv[1] if len(sys.argv) > 1 else "dev"
    hoje = datetime.date.today().isoformat()
    hist = json.load(open(os.path.join(AQUI, "historico.json"), encoding="utf-8"))
    conhecidas = {h["versao"] for h in hist}
    # 1.7.1 – 1.7.3 aparecem juntas no histórico
    conhecidas |= {"1.7.1", "1.7.2", "1.7.3"}

    lista = []
    nov = os.path.join(PROJ, "NOVIDADES.md")
    atuais = itens_de_texto(open(nov, encoding="utf-8").read()) if os.path.exists(nov) else []
    if atuais:
        lista.append({"versao": versao, "data": hoje, "itens": atuais, "atual": True})

    # notas das versões já publicadas depois do histórico (1.8, 1.8.1, …)
    if len(sys.argv) > 2 and os.path.exists(sys.argv[2]):
        try:
            rels = json.load(open(sys.argv[2], encoding="utf-8"))
        except Exception:
            rels = []
        vistos = {versao}
        pub = []
        for r in rels if isinstance(rels, list) else []:
            tag = str(r.get("tag_name", ""))
            if not re.match(r"^v\d+\.\d+(\.\d+)?$", tag) or r.get("draft"):
                continue
            v = tag[1:]
            if v in conhecidas or v in vistos:
                continue
            itens = itens_de_texto(r.get("body", ""))
            if not itens:
                continue
            vistos.add(v)
            pub.append({"versao": v, "data": str(r.get("published_at", ""))[:10], "itens": itens})
        pub.sort(key=lambda x: chave(x["versao"]), reverse=True)
        lista += pub

    lista += hist
    destino = os.path.join(PROJ, "app", "src", "main", "assets", "novidades.js")
    with open(destino, "w", encoding="utf-8") as f:
        f.write("/* gerado automaticamente por novidades/gerar.py — não edite à mão */\n")
        f.write("window.PRISMO_NOVIDADES=" + json.dumps(lista, ensure_ascii=False) + ";\n")
    print(f"novidades.js: {len(lista)} versões (atual {versao})")

if __name__ == "__main__":
    main()
