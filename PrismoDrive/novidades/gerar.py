#!/usr/bin/env python3
"""
Monta app/src/main/assets/novidades.js (lista "Novidades" do app).

Ordem: versão sendo gerada agora (NOVIDADES.md)  →  versões já publicadas no GitHub (notas de cada Release)
→  histórico fixo (novidades/historico.json, até a 1.7.11).

Uso: python3 novidades/gerar.py <versao> [releases.json]
  releases.json = saída de `gh api repos/<repo>/releases`. Sem ele, o script busca sozinho os Releases
  do repositório (GITHUB_REPOSITORY / REPO_ATUALIZACAO, token GH_TOKEN/GITHUB_TOKEN se houver).
  Roda automaticamente antes de cada build (app/build.gradle.kts → tarefa gerarNovidades).
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

def buscar_releases():
    import urllib.request
    repo = os.environ.get("REPO_ATUALIZACAO") or os.environ.get("GITHUB_REPOSITORY") or ""
    if not repo:
        return []
    req = urllib.request.Request(f"https://api.github.com/repos/{repo}/releases?per_page=100",
                                 headers={"Accept": "application/vnd.github+json", "User-Agent": "PrismoDrive-build"})
    tok = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception as e:
        print("aviso: não consegui ler os Releases (" + str(e) + ") — uso só o histórico")
        return []

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
    rels = None
    if len(sys.argv) > 2 and os.path.exists(sys.argv[2]):
        try:
            rels = json.load(open(sys.argv[2], encoding="utf-8"))
        except Exception:
            rels = None
    if rels is None and versao != "dev":
        rels = buscar_releases()
    if rels:
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
