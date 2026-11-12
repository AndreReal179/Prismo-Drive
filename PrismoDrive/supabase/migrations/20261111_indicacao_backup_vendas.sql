-- v1.9.8 · pagamentos guardados após excluir a conta, alertas do admin, indique e ganhe, backup semanal
-- 1) pagamentos continuam após excluir a conta (sem vínculo)
alter table public.pagamentos alter column user_id drop not null;
alter table public.pagamentos drop constraint if exists pagamentos_user_id_fkey;
alter table public.pagamentos add constraint pagamentos_user_id_fkey foreign key (user_id) references auth.users(id) on delete set null;

-- 2) alertas do administrador: suporte + última venda
create or replace function public.admin_suporte_pendentes()
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return json_build_object(
    'aguardando', (select count(*) from public.suporte where status = 'aguardando'),
    'abertos', (select count(*) from public.suporte where status <> 'finalizado'),
    'ultimo', (select max(atualizado_em) from public.suporte where status = 'aguardando'),
    'venda', (select row_to_json(v) from (select p.id, p.aplicado_em, p.plano, p.valor, p.detalhe->>'metodo' as metodo, f.nome
                from public.pagamentos p left join public.perfis f on f.id = p.user_id
               where p.aplicado_em is not null and p.revertido_em is null order by p.aplicado_em desc limit 1) v),
    'vendas_hoje', (select count(*) from public.pagamentos where aplicado_em >= date_trunc('day', now() at time zone 'America/Sao_Paulo') at time zone 'America/Sao_Paulo' and revertido_em is null));
end; $$;

-- 3) Indique e ganhe
alter table public.perfis add column if not exists codigo_indicacao text;
create unique index if not exists perfis_codigo_indicacao_uk on public.perfis(codigo_indicacao);
create table if not exists public.indicacoes (
  id bigint generated always as identity primary key,
  indicador uuid not null references auth.users(id) on delete cascade,
  indicado uuid not null unique references auth.users(id) on delete cascade,
  codigo text not null,
  criado_em timestamptz not null default now()
);
create index if not exists indicacoes_indicador_idx on public.indicacoes(indicador);
alter table public.indicacoes enable row level security;
revoke all on public.indicacoes from anon, authenticated;
create table if not exists public.indicacao_premios (
  id bigint generated always as identity primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  marco int not null, dias int not null,
  criado_em timestamptz not null default now(),
  unique (user_id, marco)
);
alter table public.indicacao_premios enable row level security;
revoke all on public.indicacao_premios from anon, authenticated;

create or replace function public._codigo_indicacao(uid uuid)
returns text language plpgsql security definer set search_path to '' as $$
declare p public.perfis; base text; cod text; t int := 0;
begin
  select * into p from public.perfis where id = uid;
  if not found then return null; end if;
  if p.codigo_indicacao is not null then return p.codigo_indicacao; end if;
  base := upper(regexp_replace(translate(coalesce(split_part(p.nome,' ',1),''), 'áàâãäéèêëíìîïóòôõöúùûüçÁÀÂÃÄÉÈÊËÍÌÎÏÓÒÔÕÖÚÙÛÜÇ', 'aaaaaeeeeiiiiooooouuuucAAAAAEEEEIIIIOOOOOUUUUC'), '[^A-Za-z]', '', 'g'));
  base := left(case when length(base) < 3 then 'PRISMO' else base end, 6);
  cod := base || right(coalesce(p.conta_id,'0000'), 4);
  while exists (select 1 from public.perfis where codigo_indicacao = cod) and t < 20 loop
    t := t + 1; cod := base || lpad((floor(random()*10000))::int::text, 4, '0');
  end loop;
  update public.perfis set codigo_indicacao = cod where id = uid;
  return cod;
end; $$;
revoke all on function public._codigo_indicacao(uuid) from public, anon, authenticated;

create or replace function public.minha_indicacao()
returns json language plpgsql security definer set search_path to '' as $$
declare cod text; n int; usado text; criado timestamptz;
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  cod := public._codigo_indicacao(auth.uid());
  select count(*) into n from public.indicacoes where indicador = auth.uid();
  select codigo into usado from public.indicacoes where indicado = auth.uid();
  select criado_em into criado from public.perfis where id = auth.uid();
  return json_build_object('codigo', cod, 'total', n, 'usou', usado,
    'pode_usar', usado is null and coalesce(criado, now()) > now() - interval '30 days',
    'marcos', (select json_agg(json_build_object('qtd', m.q, 'dias', m.d,
                  'ganho', exists(select 1 from public.indicacao_premios x where x.user_id = auth.uid() and x.marco = m.q)) order by m.q)
               from (values (10,3),(20,10),(30,15),(50,30)) m(q,d)));
end; $$;
revoke all on function public.minha_indicacao() from public, anon;
grant execute on function public.minha_indicacao() to authenticated;

create or replace function public.usar_codigo_indicacao(codigo text)
returns json language plpgsql security definer set search_path to '' as $$
declare eu public.perfis; dono public.perfis; n int; m record; ganhou int := 0;
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  select * into eu from public.perfis where id = auth.uid();
  if not found then raise exception 'sem_conta'; end if;
  if exists (select 1 from public.indicacoes where indicado = auth.uid()) then raise exception 'ja_usou_codigo'; end if;
  if eu.criado_em < now() - interval '30 days' then raise exception 'prazo_codigo'; end if;
  select * into dono from public.perfis where codigo_indicacao = upper(trim(codigo));
  if not found then raise exception 'codigo_invalido'; end if;
  if dono.id = auth.uid() then raise exception 'proprio_codigo'; end if;
  insert into public.indicacoes(indicador, indicado, codigo) values (dono.id, auth.uid(), dono.codigo_indicacao);
  select count(*) into n from public.indicacoes where indicador = dono.id;
  for m in select * from (values (10,3),(20,10),(30,15),(50,30)) v(q,d) loop
    if n >= m.q and not exists (select 1 from public.indicacao_premios where user_id = dono.id and marco = m.q) then
      insert into public.indicacao_premios(user_id, marco, dias) values (dono.id, m.q, m.d);
      update public.perfis set pro_ate = greatest(coalesce(pro_ate, now()), now()) + make_interval(days => m.d),
             plano = case when plano = 'admin' then 'admin' else 'pro' end where id = dono.id;
      insert into public.admin_log(admin_id, acao, conta_id, detalhes) values (dono.id, 'premio_indicacao', dono.conta_id, json_build_object('marco', m.q, 'dias', m.d));
      ganhou := ganhou + m.d;
    end if;
  end loop;
  return json_build_object('ok', true, 'indicador', split_part(coalesce(dono.nome,''),' ',1));
end; $$;
revoke all on function public.usar_codigo_indicacao(text) from public, anon;
grant execute on function public.usar_codigo_indicacao(text) to authenticated;

-- 4) Backup semanal (domingo 3h de Brasília) feito pela função "backup"
create extension if not exists pg_net;
create extension if not exists pg_cron;
create table if not exists public.backup_pedidos (nonce uuid primary key, criado_em timestamptz not null default now());
alter table public.backup_pedidos enable row level security;
revoke all on public.backup_pedidos from anon, authenticated;
create table if not exists public.backup_log (
  id bigint generated always as identity primary key, criado_em timestamptz not null default now(),
  arquivo text, bytes bigint, linhas jsonb, email boolean, erro text, origem text
);
alter table public.backup_log enable row level security;
revoke all on public.backup_log from anon, authenticated;

create or replace function public.backup_disparar(origem text default 'semanal')
returns void language plpgsql security definer set search_path to '' as $$
declare n uuid := gen_random_uuid();
begin
  delete from public.backup_pedidos where criado_em < now() - interval '1 day';
  insert into public.backup_pedidos(nonce) values (n);
  perform net.http_post(
    url := 'https://xzdqvdrwvozoedmjwwhm.supabase.co/functions/v1/backup',
    body := jsonb_build_object('nonce', n, 'origem', origem),
    headers := '{"Content-Type":"application/json"}'::jsonb,
    timeout_milliseconds := 120000);
end; $$;
revoke all on function public.backup_disparar(text) from public, anon, authenticated;

create or replace function public.admin_backup_agora()
returns json language plpgsql security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  perform public.backup_disparar('manual');
  return json_build_object('ok', true);
end; $$;
revoke all on function public.admin_backup_agora() from public, anon;
grant execute on function public.admin_backup_agora() to authenticated;

create or replace function public.admin_backups()
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return (select coalesce(json_agg(b order by b.criado_em desc), '[]'::json) from (select id, criado_em, arquivo, bytes, email, erro, origem from public.backup_log order by criado_em desc limit 10) b);
end; $$;
revoke all on function public.admin_backups() from public, anon;
grant execute on function public.admin_backups() to authenticated;

select cron.unschedule('prismo-backup-semanal') where exists (select 1 from cron.job where jobname = 'prismo-backup-semanal');
select cron.schedule('prismo-backup-semanal', '0 6 * * 0', $$ select public.backup_disparar('semanal'); $$);
