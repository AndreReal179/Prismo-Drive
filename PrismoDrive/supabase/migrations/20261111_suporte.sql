-- v1.9.8 · Suporte (mensagens do app para o e-mail da empresa)
create table if not exists public.suporte (
  id bigint generated always as identity primary key,
  criado_em timestamptz not null default now(),
  user_id uuid references auth.users(id) on delete set null,
  conta_id text, nome text, email text not null, plano text,
  categoria text not null, assunto text not null, mensagem text not null,
  anexos jsonb, app_versao text, aparelho text,
  enviado boolean not null default false, erro text
);
create index if not exists suporte_criado_idx on public.suporte(criado_em desc);
alter table public.suporte enable row level security;
revoke all on public.suporte from anon, authenticated;
