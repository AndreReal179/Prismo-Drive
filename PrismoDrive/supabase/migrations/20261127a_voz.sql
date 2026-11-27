-- ═══════════════════════════════════════════════════════════════════════════
-- Prismo Drive · v2.3 · vozes humanizadas do assistente "Ei Prismo!"
-- Rodar uma vez no SQL Editor do Supabase (pode rodar de novo sem problema).
-- • voz_uso: quantas frases cada conta pediu no dia (limite na função "voz")
-- • bucket privado voz-cache: as frases fixas já geradas (ninguém lê direto, só a função)
-- ═══════════════════════════════════════════════════════════════════════════
create table if not exists public.voz_uso (
  user_id uuid not null,
  dia date not null default (now() at time zone 'America/Sao_Paulo')::date,
  n int not null default 0,
  primary key (user_id, dia)
);
alter table public.voz_uso enable row level security;
revoke all on public.voz_uso from anon, authenticated;

create or replace function public.voz_contar(p_uid uuid)
returns int language plpgsql security definer set search_path to '' as $$
declare r int;
begin
  insert into public.voz_uso(user_id, dia, n) values (p_uid, (now() at time zone 'America/Sao_Paulo')::date, 1)
  on conflict (user_id, dia) do update set n = public.voz_uso.n + 1
  returning n into r;
  delete from public.voz_uso where dia < (now() at time zone 'America/Sao_Paulo')::date - 30;
  return r;
end; $$;
revoke all on function public.voz_contar(uuid) from public, anon, authenticated;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('voz-cache', 'voz-cache', false, 1048576, array['audio/mpeg'])
on conflict (id) do nothing;
