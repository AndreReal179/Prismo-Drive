-- v1.9.8 · teste grátis do Pro: 3 dias, UMA vez por celular
-- Antes: toda conta nova nascia com 7 dias de Pro (dava para repetir criando e-mails novos).
-- Agora: a conta nasce Free; o app pede o teste com o identificador anônimo do aparelho e o servidor confere.

create table if not exists public.testes_pro (
  aparelho text primary key check (aparelho ~ '^[0-9a-f]{64}$'),
  user_id uuid references auth.users(id) on delete set null,
  criado_em timestamptz not null default now()
);
alter table public.testes_pro enable row level security;
revoke all on public.testes_pro from anon, authenticated;

alter table public.perfis add column if not exists teste_em timestamptz;
-- quem já tem conta já teve o teste antigo: não ganha outro, e os celulares dessas contas contam como usados
update public.perfis set teste_em = coalesce(teste_em, criado_em, now()) where teste_em is null;
insert into public.testes_pro(aparelho, user_id, criado_em)
select distinct on (a) a, p.id, coalesce(p.criado_em, now())
  from public.perfis p, unnest(p.aparelhos) a
 where a ~ '^[0-9a-f]{64}$'
 order by a, p.criado_em
on conflict (aparelho) do nothing;

-- conta nova nasce Free (sem Pro automático)
create or replace function public.criar_perfil()
returns trigger language plpgsql security definer set search_path to '' as $$
begin
  insert into public.perfis (id, nome, email, plano, pro_ate)
  values (new.id, coalesce(new.raw_user_meta_data->>'nome', new.raw_user_meta_data->>'full_name', new.raw_user_meta_data->>'name'),
          new.email, 'free', null);
  return new;
end; $$;

create or replace function public.ativar_teste(aparelho text)
returns json language plpgsql security definer set search_path to '' as $$
declare eu public.perfis; conf timestamptz; ap text; ate timestamptz;
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  select * into eu from public.perfis where id = auth.uid() for update;
  if not found then return json_build_object('ok', false, 'motivo', 'sem_conta'); end if;
  if eu.teste_em is not null then return json_build_object('ok', false, 'motivo', 'ja_usou'); end if;
  if eu.plano = 'admin' or (eu.pro_ate is not null and eu.pro_ate > now()) then
    update public.perfis set teste_em = now() where id = eu.id;
    return json_build_object('ok', false, 'motivo', 'ja_pro');
  end if;
  if eu.criado_em < now() - interval '7 days' then
    update public.perfis set teste_em = now() where id = eu.id;
    return json_build_object('ok', false, 'motivo', 'prazo');
  end if;
  select email_confirmed_at into conf from auth.users where id = auth.uid();
  if conf is null then return json_build_object('ok', false, 'motivo', 'email'); end if;
  ap := case when aparelho ~ '^[0-9a-f]{64}$' then aparelho end;
  if ap is null then return json_build_object('ok', false, 'motivo', 'atualize_app'); end if;
  -- celular já usado: por outro teste, ou por qualquer outra conta que já teve teste nele
  if exists (select 1 from public.testes_pro t where t.aparelho = ap)
     or exists (select 1 from public.perfis p where p.id <> eu.id and p.teste_em is not null and ap = any(p.aparelhos)) then
    update public.perfis set teste_em = now(), aparelhos = array_append(array_remove(perfis.aparelhos, ap), ap) where id = eu.id;
    insert into public.admin_log(admin_id, acao, conta_id, detalhes) values (eu.id, 'teste_negado', eu.conta_id, json_build_object('motivo', 'aparelho_usado'));
    return json_build_object('ok', false, 'motivo', 'aparelho_usado');
  end if;
  insert into public.testes_pro(aparelho, user_id) values (ap, eu.id);
  update public.perfis
     set teste_em = now(), plano = 'pro', pro_ate = now() + interval '3 days',
         aparelhos = array_append(array_remove(perfis.aparelhos, ap), ap)
   where id = eu.id
  returning pro_ate into ate;
  insert into public.admin_log(admin_id, acao, conta_id, detalhes) values (eu.id, 'teste_pro', eu.conta_id, json_build_object('dias', 3, 'pro_ate', ate));
  return json_build_object('ok', true, 'dias', 3, 'pro_ate', ate);
end; $$;
revoke all on function public.ativar_teste(text) from public, anon;
grant execute on function public.ativar_teste(text) to authenticated;
