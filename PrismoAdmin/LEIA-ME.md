# Prismo Drive Admin (Windows)

Programa do administrador do Prismo Drive: suporte, vendas, usuários, códigos Pro, avisos, erros do app e backups.

- O GitHub gera o instalador sozinho (workflow "Gerar Prismo Drive Admin (Windows)") sempre que algo nesta pasta muda.
- O instalador fica em Releases › "Prismo Drive Admin x.y.z (Windows)" (pré-lançamento, não mexe nas atualizações do app).
- Entre com a conta de administrador (e-mail e senha). Sem conta admin o programa não mostra nada.
- Antes do primeiro uso, rode no Supabase o SQL `PrismoDrive/supabase/migrations/20261118_admin_pc.sql`.
- Para lançar uma versão nova do programa, aumente "version" no package.json.
