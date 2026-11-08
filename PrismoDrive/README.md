# Prismo Drive — projeto Android em Kotlin

App nativo Android (Kotlin) do Prismo Drive. As telas ficam dentro do APK
(`app/src/main/assets/index.html`), 100% offline. A parte nativa em Kotlin cuida de:

- Notificações e lembretes agendados (vencimentos, manutenção, metas) — continuam com o app fechado e após reiniciar o celular
- Widgets da tela inicial 4×2 e 2×2
- Compartilhar a imagem do fechamento (WhatsApp etc.)
- Escolher foto da galeria (perfil)
- Botão voltar: fecha carteira, guia, janelas e menu antes de minimizar o app
- **Assistente de rotas** (ex-Prismo Route): serviço de acessibilidade que avalia as ofertas do iFood e do InDrive — código em `app/src/main/java/com/prismo/drive/rota/`
- **Atualização automática** pela aba Atualizações (GitHub Releases): o app avisa, baixa e abre o instalador
- Links externos abrem fora do app

Pacote `com.prismo.drive` · versão **1.7** (o versionCode sobe sozinho a cada build).

## Gerar o APK no GitHub

1. Crie um repositório (pode ser privado) e envie **todo o conteúdo desta pasta**
   (inclusive a pasta oculta `.github`).
2. Em **Settings → Secrets and variables → Actions → New repository secret**, crie:
   | Nome | Valor |
   |---|---|
   | `KEYSTORE_BASE64` | conteúdo do arquivo `keystore_base64.txt` (enviado à parte) |
   | `KEYSTORE_PASSWORD` | senha da keystore |
   | `KEY_ALIAS` | `prismo` (opcional, é o padrão) |
   | `KEY_PASSWORD` | senha da chave (opcional; se vazio usa a mesma da keystore) |
3. Vá em **Actions → Gerar APK Prismo Drive → Run workflow**.
4. Ao terminar, baixe o APK em **Artifacts → PrismoDrive-apk**.
   Criando uma tag `v1.7` o APK também sai em **Releases**.

O passo "Conferir assinatura" avisa se a chave usada é a mesma da v1.6.
Se for a mesma, o APK instala por cima e **mantém todos os dados**.

## Como publicar uma atualização para os usuários
1. Faça as mudanças (ex.: no `index.html`).
2. Escreva o que mudou em `NOVIDADES.md` — aparece no aviso e na aba Atualizações.
3. Se quiser um número novo, mude `versionName` em `app/build.gradle.kts` (ex.: "1.8")
   e `var APP_VERSAO='1.8'` no `index.html`.
4. Envie (commit) para a branch `main`. O GitHub gera o APK e publica um Release.
   Em até alguns segundos depois de abrirem o app, os usuários recebem o aviso
   "Nova atualização disponível" e atualizam com um toque, sem perder dados.

Só é publicado como atualização o APK assinado com a keystore (secrets configurados).
Para gerar sem publicar: Actions → Run workflow → desmarque "Publicar".

### O app só lê Releases de repositório PÚBLICO
Se este repositório for privado (recomendado, para não expor o código), faça assim:
1. Crie um segundo repositório **público**, ex.: `prismo-drive-atualizacoes`, com um README.
2. Crie um token em GitHub → Settings → Developer settings → Fine-grained tokens,
   com acesso só a esse repositório e permissão **Contents: Read and write**.
3. No repositório do código: Settings → Secrets and variables → Actions:
   - aba **Variables**: `REPO_ATUALIZACAO` = `seu-usuario/prismo-drive-atualizacoes`
   - aba **Secrets**: `RELEASE_TOKEN` = o token criado
O código fica privado e só o APK vai para o repositório público.

## Nova versão
O `versionCode` é automático. Para mudar o nome da versão, altere `versionName`
em `app/build.gradle.kts` e `var APP_VERSAO='…'` no `index.html`.

Nunca envie a keystore (.p12) para o repositório — ela fica só no Secret.
