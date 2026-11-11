-- v1.9.8 · termos de uso, vendas (admin) e anúncios
alter table public.perfis add column if not exists termos_versao text;
alter table public.perfis add column if not exists termos_em timestamptz;

create or replace function public.aceitar_termos(versao text)
returns json language plpgsql security definer set search_path to '' as $$
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  if versao is null or length(versao) > 20 then raise exception 'versao_invalida'; end if;
  update public.perfis set termos_versao = versao, termos_em = now() where id = auth.uid();
  return json_build_object('ok', true, 'versao', versao, 'em', now());
end; $$;
revoke all on function public.aceitar_termos(text) from public, anon;
grant execute on function public.aceitar_termos(text) to authenticated;

-- Vendas para o administrador
create or replace function public.admin_vendas(dias integer default 30)
returns json language plpgsql stable security definer set search_path to '' as $$
declare ini timestamptz; hoje timestamptz; mes timestamptz;
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  hoje := date_trunc('day', now() at time zone 'America/Sao_Paulo') at time zone 'America/Sao_Paulo';
  mes  := date_trunc('month', now() at time zone 'America/Sao_Paulo') at time zone 'America/Sao_Paulo';
  ini  := now() - make_interval(days => greatest(1, least(coalesce(dias,30), 366)));
  return json_build_object(
    'hoje',  (select json_build_object('qtd', count(*), 'total', coalesce(sum(valor),0)) from public.pagamentos where aplicado_em >= hoje and revertido_em is null),
    'mes',   (select json_build_object('qtd', count(*), 'total', coalesce(sum(valor),0)) from public.pagamentos where aplicado_em >= mes and revertido_em is null),
    'geral', (select json_build_object('qtd', count(*), 'total', coalesce(sum(valor),0)) from public.pagamentos where aplicado_em is not null and revertido_em is null),
    'estornos', (select count(*) from public.pagamentos where revertido_em is not null),
    'planos', (select coalesce(json_object_agg(plano, n), '{}'::json) from (select plano, count(*) n from public.pagamentos where aplicado_em >= mes and revertido_em is null group by plano) q),
    'lista', (select coalesce(json_agg(x order by x.criado_em desc), '[]'::json) from (
        select p.criado_em, p.aplicado_em, p.revertido_em, p.plano, p.dias, p.valor, p.status,
               p.detalhe->>'metodo' as metodo, p.conta_id, f.nome, f.email
          from public.pagamentos p left join public.perfis f on f.id = p.user_id
         where p.criado_em >= ini and p.status not in ('criado','erro_mp')
           and (p.aplicado_em is not null or p.status <> 'aguardando' or p.criado_em > now() - interval '1 day')
         order by p.criado_em desc limit 100) x)
  );
end; $$;
revoke all on function public.admin_vendas(integer) from public, anon;
grant execute on function public.admin_vendas(integer) to authenticated;

-- Anúncios: o admin escreve, o app de cada cliente agenda as notificações
create table if not exists public.avisos (
  id bigint generated always as identity primary key,
  titulo text not null default 'Prismo Drive',
  texto text not null check (length(texto) between 3 and 300),
  publico text not null default 'todos' check (publico in ('todos','free','pro','usuario')),
  inicio date not null,
  dias int not null check (dias between 1 and 60),
  semana int[] not null default '{0,1,2,3,4,5,6}',
  turnos text[] not null default '{manha}',
  ativo boolean not null default true,
  criado_por uuid,
  criado_em timestamptz not null default now()
);
alter table public.avisos enable row level security;
drop policy if exists "ler avisos ativos" on public.avisos;
create policy "ler avisos ativos" on public.avisos for select to anon, authenticated
  using (ativo and (inicio + dias) >= (now() at time zone 'America/Sao_Paulo')::date);
revoke insert, update, delete on public.avisos from anon, authenticated;

create or replace function public.admin_anunciar(titulo text, texto text, publico text, inicio date, dias integer, semana integer[], turnos text[])
returns json language plpgsql security definer set search_path to '' as $$
declare novo public.avisos;
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  if texto is null or length(trim(texto)) < 3 then raise exception 'texto_curto'; end if;
  if turnos is null or array_length(turnos,1) is null or not (turnos <@ array['manha','tarde','noite']) then raise exception 'turnos_invalidos'; end if;
  if semana is null or array_length(semana,1) is null then raise exception 'dias_semana_invalidos'; end if;
  insert into public.avisos(titulo, texto, publico, inicio, dias, semana, turnos, criado_por)
  values (coalesce(nullif(trim(titulo),''),'Prismo Drive'), trim(texto), coalesce(publico,'todos'), coalesce(inicio, (now() at time zone 'America/Sao_Paulo')::date), dias, semana, turnos, auth.uid())
  returning * into novo;
  insert into public.admin_log(admin_id, acao, conta_id, detalhes) values (auth.uid(), 'anunciar', null, row_to_json(novo)::jsonb);
  return row_to_json(novo);
end; $$;
revoke all on function public.admin_anunciar(text,text,text,date,integer,integer[],text[]) from public, anon;
grant execute on function public.admin_anunciar(text,text,text,date,integer,integer[],text[]) to authenticated;

create or replace function public.admin_avisos()
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return (select coalesce(json_agg(a order by a.criado_em desc), '[]'::json) from (select * from public.avisos order by criado_em desc limit 30) a);
end; $$;
revoke all on function public.admin_avisos() from public, anon;
grant execute on function public.admin_avisos() to authenticated;

create or replace function public.admin_encerrar_aviso(aviso bigint)
returns json language plpgsql security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  update public.avisos set ativo = false where id = aviso;
  insert into public.admin_log(admin_id, acao, conta_id, detalhes) values (auth.uid(), 'encerrar_aviso', null, json_build_object('id', aviso));
  return json_build_object('ok', true);
end; $$;
revoke all on function public.admin_encerrar_aviso(bigint) from public, anon;
grant execute on function public.admin_encerrar_aviso(bigint) to authenticated;
