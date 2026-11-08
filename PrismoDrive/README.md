# Prismo Drive — projeto Android em Kotlin

App nativo Android (Kotlin) do Prismo Drive. As telas ficam dentro do APK
(`app/src/main/assets/index.html`), 100% offline. A parte nativa em Kotlin cuida de:

- Notificações e lembretes agendados (vencimentos, manutenção, metas) — continuam com o app fechado e após reiniciar o celular
- Widgets da tela inicial 4×2 e 2×2
- Compartilhar a imagem do fechamento (WhatsApp etc.)
- Escolher foto da galeria (perfil)
- Botão voltar: fecha carteira, guia, janelas e menu antes de minimizar o app
- Links externos (WhatsApp de "Comprar atualização") abrem fora do app

Pacote `com.prismo.drive` · versão **1.7** (versionCode 30).

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

## Nova versão
Em `app/build.gradle.kts`, aumente `versionCode` (+1) e `versionName`; e no
`index.html` atualize `var APP_VERSAO='…'`.

Nunca envie a keystore (.p12) para o repositório — ela fica só no Secret.
