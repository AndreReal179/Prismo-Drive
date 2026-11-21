-- ═══════════════════════════════════════════════════════════════════════════
-- Prismo Drive · v2.3 · Programa de administração (PrismoAdmin) + Missões e Insígnias
--   1) Presença (quem está com o app aberto agora / em rota) e histórico por dia
--   2) Registro das rotas (só app, dia e km: sem valores) para os relatórios
--   3) Missões e insígnias (criadas no programa, valem no app)
--   4) Funções do programa: painel, relatórios, online, usuários, erros, missões
-- Rodar uma vez no SQL Editor do Supabase (pode rodar de novo sem problema).
-- ═══════════════════════════════════════════════════════════════════════════

-- ══════════════ 1) Presença ══════════════
create table if not exists public.presenca (
  user_id uuid primary key references auth.users(id) on delete cascade,
  em_rota boolean not null default false,
  app text,
  versao text,
  atualizado_em timestamptz not null default now()
);
create index if not exists presenca_atual_idx on public.presenca(atualizado_em desc);
alter table public.presenca enable row level security;
revoke all on public.presenca from anon, authenticated;

create table if not exists public.presenca_dia (
  user_id uuid not null references auth.users(id) on delete cascade,
  dia date not null,
  em_rota boolean not null default false,
  primeira timestamptz not null default now(),
  primary key (user_id, dia)
);
create index if not exists presenca_dia_idx on public.presenca_dia(dia);
alter table public.presenca_dia enable row level security;
revoke all on public.presenca_dia from anon, authenticated;

-- o app chama a cada ~2 min enquanto está aberto (e quando a rota começa ou termina)
create or replace function public.app_presenca(p_em_rota boolean default false, p_app text default null, p_versao text default null)
returns json language plpgsql security definer set search_path to '' as $$
declare hoje date := (now() at time zone 'America/Sao_Paulo')::date;
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  insert into public.presenca(user_id, em_rota, app, versao, atualizado_em)
  values (auth.uid(), coalesce(p_em_rota,false), left(p_app,20), left(p_versao,20), now())
  on conflict (user_id) do update set em_rota = excluded.em_rota, app = excluded.app, versao = excluded.versao, atualizado_em = now();
  insert into public.presenca_dia(user_id, dia, em_rota) values (auth.uid(), hoje, coalesce(p_em_rota,false))
  on conflict (user_id, dia) do update set em_rota = public.presenca_dia.em_rota or excluded.em_rota;
  return json_build_object('ok', true);
end; $$;
revoke all on function public.app_presenca(boolean, text, text) from public, anon;
grant execute on function public.app_presenca(boolean, text, text) to authenticated;

-- ══════════════ 2) Rotas (para os relatórios) ══════════════
create table if not exists public.rotas_reg (
  user_id uuid not null references auth.users(id) on delete cascade,
  rid text not null,
  dia date not null,
  app text not null default 'ifood',
  km numeric(8,2) not null default 0,
  criado_em timestamptz not null default now(),
  primary key (user_id, rid)
);
create index if not exists rotas_reg_dia_idx on public.rotas_reg(dia);
alter table public.rotas_reg enable row level security;
revoke all on public.rotas_reg from anon, authenticated;

create or replace function public.rota_registrar(p_rid text, p_app text, p_km numeric, p_ts bigint)
returns json language plpgsql security definer set search_path to '' as $$
declare d date;
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  if p_rid is null or length(p_rid) > 80 then return json_build_object('ok', false); end if;
  d := (to_timestamp(coalesce(p_ts, extract(epoch from now())*1000) / 1000.0) at time zone 'America/Sao_Paulo')::date;
  if d > (now() at time zone 'America/Sao_Paulo')::date + 1 or d < (now() at time zone 'America/Sao_Paulo')::date - 400 then
    return json_build_object('ok', false);
  end if;
  insert into public.rotas_reg(user_id, rid, dia, app, km)
  values (auth.uid(), p_rid, d, case when p_app in ('ifood','indrive','turno') then p_app else 'ifood' end, least(greatest(coalesce(p_km,0),0),2000))
  on conflict (user_id, rid) do update set km = excluded.km, app = excluded.app;
  insert into public.presenca_dia(user_id, dia, em_rota) values (auth.uid(), d, true)
  on conflict (user_id, dia) do update set em_rota = true;
  return json_build_object('ok', true);
end; $$;
revoke all on function public.rota_registrar(text, text, numeric, bigint) from public, anon;
grant execute on function public.rota_registrar(text, text, numeric, bigint) to authenticated;

-- ══════════════ 3) Missões e insígnias ══════════════
create table if not exists public.insignias (
  id bigint generated always as identity primary key,
  nome text not null check (length(nome) between 2 and 40),
  descricao text not null default '' check (length(descricao) <= 300),
  icone text not null default 'military_tech',
  cor1 text not null default '#b45309',
  cor2 text not null default '#fbbf24',
  raridade text not null default 'comum' check (raridade in ('comum','rara','epica','lendaria')),
  ativa boolean not null default true,
  ordem int not null default 0,
  criado_em timestamptz not null default now()
);
alter table public.insignias enable row level security;
revoke all on public.insignias from anon, authenticated;

create table if not exists public.missoes (
  id bigint generated always as identity primary key,
  titulo text not null check (length(titulo) between 2 and 40),
  descricao text not null default '' check (length(descricao) <= 400),
  icone text not null default 'flag',
  cor1 text not null default '#6d28d9',
  cor2 text not null default '#a78bfa',
  dificuldade text not null default 'media' check (dificuldade in ('facil','media','dificil')),
  -- o que o app conta desde que a pessoa aceitou a missão
  tipo text not null check (tipo in ('rotas','km','dias','gorjetas','ganho','turnos','meta','indicacoes')),
  meta numeric(10,2) not null check (meta > 0),
  prazo_dias int check (prazo_dias is null or prazo_dias between 1 and 365),
  insignia_id bigint references public.insignias(id) on delete set null,
  ativa boolean not null default true,
  ordem int not null default 0,
  criado_em timestamptz not null default now(),
  atualizado_em timestamptz not null default now()
);
alter table public.missoes enable row level security;
revoke all on public.missoes from anon, authenticated;

create table if not exists public.missoes_usuario (
  user_id uuid not null references auth.users(id) on delete cascade,
  missao_id bigint not null references public.missoes(id) on delete cascade,
  status text not null default 'aceita' check (status in ('aceita','concluida','falhou')),
  progresso numeric(12,2) not null default 0,
  aceita_em timestamptz not null default now(),
  concluida_em timestamptz,
  primary key (user_id, missao_id)
);
alter table public.missoes_usuario enable row level security;
revoke all on public.missoes_usuario from anon, authenticated;

create table if not exists public.insignias_usuario (
  user_id uuid not null references auth.users(id) on delete cascade,
  insignia_id bigint not null references public.insignias(id) on delete cascade,
  missao_id bigint,
  ganha_em timestamptz not null default now(),
  primary key (user_id, insignia_id)
);
alter table public.insignias_usuario enable row level security;
revoke all on public.insignias_usuario from anon, authenticated;

create or replace function public._insignia_json(i public.insignias)
returns json language sql stable security definer set search_path to '' as $$
  select json_build_object('id', i.id, 'nome', i.nome, 'descricao', i.descricao, 'icone', i.icone,
    'cor1', i.cor1, 'cor2', i.cor2, 'raridade', i.raridade, 'ativa', i.ativa, 'ordem', i.ordem);
$$;
revoke all on function public._insignia_json(public.insignias) from public, anon, authenticated;

-- o app: missões ativas com a situação da pessoa, insígnias e as que ela já ganhou
create or replace function public.missoes_app()
returns json language plpgsql security definer set search_path to '' as $$
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  -- prazo vencido vira "falhou"
  update public.missoes_usuario mu set status = 'falhou'
    from public.missoes m
   where mu.missao_id = m.id and mu.user_id = auth.uid() and mu.status = 'aceita'
     and m.prazo_dias is not null and now() > mu.aceita_em + make_interval(days => m.prazo_dias);
  return json_build_object(
    'missoes', (select coalesce(json_agg(json_build_object(
        'id', m.id, 'titulo', m.titulo, 'descricao', m.descricao, 'icone', m.icone, 'cor1', m.cor1, 'cor2', m.cor2,
        'dificuldade', m.dificuldade, 'tipo', m.tipo, 'meta', m.meta, 'prazo_dias', m.prazo_dias,
        'insignia', (select public._insignia_json(i) from public.insignias i where i.id = m.insignia_id),
        'status', mu.status, 'progresso', mu.progresso,
        'aceita_em', case when mu.aceita_em is not null then (extract(epoch from mu.aceita_em)*1000)::bigint end,
        'concluida_em', mu.concluida_em
      ) order by m.ordem, m.id), '[]'::json)
      from public.missoes m left join public.missoes_usuario mu on mu.missao_id = m.id and mu.user_id = auth.uid()
     where m.ativa or mu.status = 'concluida'),
    'insignias', (select coalesce(json_agg(public._insignia_json(i) order by i.ordem, i.id), '[]'::json) from public.insignias i where i.ativa
                   or exists (select 1 from public.insignias_usuario iu where iu.insignia_id = i.id and iu.user_id = auth.uid())),
    'minhas', (select coalesce(json_agg(json_build_object('id', iu.insignia_id, 'ganha_em', iu.ganha_em) order by iu.ganha_em), '[]'::json)
                 from public.insignias_usuario iu where iu.user_id = auth.uid())
  );
end; $$;
revoke all on function public.missoes_app() from public, anon;
grant execute on function public.missoes_app() to authenticated;

create or replace function public.missao_aceitar(p_missao bigint)
returns json language plpgsql security definer set search_path to '' as $$
declare st text;
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  if not exists (select 1 from public.missoes where id = p_missao and ativa) then raise exception 'missao_indisponivel'; end if;
  select status into st from public.missoes_usuario where user_id = auth.uid() and missao_id = p_missao;
  if st = 'concluida' then raise exception 'ja_concluida'; end if;
  if (select count(*) from public.missoes_usuario where user_id = auth.uid() and status = 'aceita') >= 5 and st is distinct from 'aceita' then
    raise exception 'limite_missoes';
  end if;
  insert into public.missoes_usuario(user_id, missao_id, status, progresso, aceita_em)
  values (auth.uid(), p_missao, 'aceita', 0, now())
  on conflict (user_id, missao_id) do update set status = 'aceita', progresso = 0, aceita_em = now(), concluida_em = null
   where public.missoes_usuario.status = 'falhou';
  return json_build_object('ok', true, 'aceita_em', (select (extract(epoch from aceita_em)*1000)::bigint from public.missoes_usuario where user_id = auth.uid() and missao_id = p_missao));
end; $$;
revoke all on function public.missao_aceitar(bigint) from public, anon;
grant execute on function public.missao_aceitar(bigint) to authenticated;

create or replace function public.missao_desistir(p_missao bigint)
returns json language plpgsql security definer set search_path to '' as $$
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  delete from public.missoes_usuario where user_id = auth.uid() and missao_id = p_missao and status <> 'concluida';
  return json_build_object('ok', true);
end; $$;
revoke all on function public.missao_desistir(bigint) from public, anon;
grant execute on function public.missao_desistir(bigint) to authenticated;

-- o app conta o progresso com os dados do celular e envia; chegou na meta, conclui e dá a insígnia
create or replace function public.missao_progresso(p_missao bigint, p_valor numeric)
returns json language plpgsql security definer set search_path to '' as $$
declare mu public.missoes_usuario; m public.missoes; ins json; nova boolean := false;
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  select * into mu from public.missoes_usuario where user_id = auth.uid() and missao_id = p_missao for update;
  if not found then raise exception 'nao_aceita'; end if;
  select * into m from public.missoes where id = p_missao;
  if mu.status <> 'aceita' then return json_build_object('status', mu.status, 'progresso', mu.progresso); end if;
  if m.prazo_dias is not null and now() > mu.aceita_em + make_interval(days => m.prazo_dias) then
    update public.missoes_usuario set status = 'falhou' where user_id = auth.uid() and missao_id = p_missao;
    return json_build_object('status', 'falhou', 'progresso', mu.progresso);
  end if;
  update public.missoes_usuario set progresso = greatest(progresso, least(coalesce(p_valor,0), m.meta * 10))
   where user_id = auth.uid() and missao_id = p_missao returning * into mu;
  if mu.progresso >= m.meta then
    update public.missoes_usuario set status = 'concluida', concluida_em = now() where user_id = auth.uid() and missao_id = p_missao;
    if m.insignia_id is not null then
      insert into public.insignias_usuario(user_id, insignia_id, missao_id) values (auth.uid(), m.insignia_id, m.id)
      on conflict do nothing;
      nova := found;
      select public._insignia_json(i) into ins from public.insignias i where i.id = m.insignia_id;
    end if;
    return json_build_object('status', 'concluida', 'progresso', mu.progresso, 'insignia', ins, 'nova', nova);
  end if;
  return json_build_object('status', 'aceita', 'progresso', mu.progresso);
end; $$;
revoke all on function public.missao_progresso(bigint, numeric) from public, anon;
grant execute on function public.missao_progresso(bigint, numeric) to authenticated;

-- ══════════════ 4) Programa de administração ══════════════
alter table public.erros_app add column if not exists resolvido_em timestamptz;

-- tela inicial: números de agora e os últimos 30 dias
create or replace function public.admin_painel()
returns json language plpgsql stable security definer set search_path to '' as $$
declare hoje date := (now() at time zone 'America/Sao_Paulo')::date;
        ini_hoje timestamptz := date_trunc('day', now() at time zone 'America/Sao_Paulo') at time zone 'America/Sao_Paulo';
        ini_mes timestamptz := date_trunc('month', now() at time zone 'America/Sao_Paulo') at time zone 'America/Sao_Paulo';
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return json_build_object(
    'agora', now(),
    'online', (select count(*) from public.presenca where atualizado_em > now() - interval '3 minutes'),
    'em_rota', (select count(*) from public.presenca where em_rota and atualizado_em > now() - interval '15 minutes'),
    'usuarios', (select count(*) from public.perfis),
    'pro', (select count(*) from public.perfis where plano <> 'admin' and pro_ate > now()),
    'novos_hoje', (select count(*) from public.perfis where criado_em >= ini_hoje),
    'ativos_hoje', (select count(*) from public.presenca_dia where dia = hoje),
    'rotas_hoje', (select count(*) from public.rotas_reg where dia = hoje),
    'chamados_abertos', (select count(*) from public.suporte where status in ('aguardando','andamento')),
    'erros_novos', (select count(*) from public.erros_app where resolvido_em is null and ultimo_em > now() - interval '7 days'),
    'vendas_hoje', (select coalesce(sum(valor),0) from public.pagamentos where aplicado_em >= ini_hoje and revertido_em is null),
    'vendas_mes', (select coalesce(sum(valor),0) from public.pagamentos where aplicado_em >= ini_mes and revertido_em is null),
    'serie', (select json_agg(json_build_object(
        'dia', d::date,
        'ativos', (select count(*) from public.presenca_dia p where p.dia = d::date),
        'em_rota', (select count(distinct u) from (select user_id u from public.rotas_reg r where r.dia = d::date
                                                    union select user_id from public.presenca_dia p where p.dia = d::date and p.em_rota) x),
        'rotas', (select count(*) from public.rotas_reg r where r.dia = d::date),
        'vendas', (select coalesce(sum(valor),0) from public.pagamentos g where (g.aplicado_em at time zone 'America/Sao_Paulo')::date = d::date and g.revertido_em is null),
        'novos', (select count(*) from public.perfis f where (f.criado_em at time zone 'America/Sao_Paulo')::date = d::date)
      ) order by d) from generate_series(hoje - 29, hoje, interval '1 day') d),
    'planos', json_build_object(
        'free', (select count(*) from public.perfis where plano <> 'admin' and (pro_ate is null or pro_ate <= now())),
        'pro', (select count(*) from public.perfis where plano <> 'admin' and pro_ate > now()),
        'admin', (select count(*) from public.perfis where plano = 'admin')),
    'apps_hoje', (select coalesce(json_object_agg(app, n), '{}'::json) from (select app, count(*) n from public.rotas_reg where dia = hoje group by app) q)
  );
end; $$;
revoke all on function public.admin_painel() from public, anon;
grant execute on function public.admin_painel() to authenticated;

-- relatórios por dia (30 dias), mês (12 meses) ou ano (5 anos): vendas do Pro, usuários ativos e em rota
create or replace function public.admin_relatorio(p_escala text default 'dia')
returns json language plpgsql stable security definer set search_path to '' as $$
declare hoje date := (now() at time zone 'America/Sao_Paulo')::date; passo interval; ini date; un text;
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  if p_escala = 'ano' then un := 'year'; passo := interval '1 year'; ini := date_trunc('year', hoje)::date - interval '4 years';
  elsif p_escala = 'mes' then un := 'month'; passo := interval '1 month'; ini := date_trunc('month', hoje)::date - interval '11 months';
  else un := 'day'; passo := interval '1 day'; ini := hoje - 29; end if;
  return (select json_agg(json_build_object(
      'inicio', p::date,
      'vendas', (select coalesce(sum(valor),0) from public.pagamentos g where g.revertido_em is null and g.aplicado_em is not null
                   and date_trunc(un, (g.aplicado_em at time zone 'America/Sao_Paulo'))::date = p::date),
      'vendas_qtd', (select count(*) from public.pagamentos g where g.revertido_em is null and g.aplicado_em is not null
                   and date_trunc(un, (g.aplicado_em at time zone 'America/Sao_Paulo'))::date = p::date),
      'ativos', (select count(distinct user_id) from public.presenca_dia x where date_trunc(un, x.dia)::date = p::date),
      'em_rota', (select count(distinct u) from (select user_id u from public.rotas_reg r where date_trunc(un, r.dia)::date = p::date
                   union select user_id from public.presenca_dia x where x.em_rota and date_trunc(un, x.dia)::date = p::date) z),
      'rotas', (select count(*) from public.rotas_reg r where date_trunc(un, r.dia)::date = p::date),
      'km', (select coalesce(sum(km),0) from public.rotas_reg r where date_trunc(un, r.dia)::date = p::date),
      'novos', (select count(*) from public.perfis f where date_trunc(un, (f.criado_em at time zone 'America/Sao_Paulo'))::date = p::date)
    ) order by p) from generate_series(ini, hoje, passo) p);
end; $$;
revoke all on function public.admin_relatorio(text) from public, anon;
grant execute on function public.admin_relatorio(text) to authenticated;

create or replace function public.admin_online()
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return (select coalesce(json_agg(json_build_object(
      'conta_id', f.conta_id, 'nome', f.nome, 'email', f.email,
      'plano', case when f.plano = 'admin' then 'admin' when f.pro_ate > now() then 'pro' else 'free' end,
      'em_rota', p.em_rota and p.atualizado_em > now() - interval '15 minutes',
      'online', p.atualizado_em > now() - interval '3 minutes',
      'app', p.app, 'versao', p.versao, 'visto_em', p.atualizado_em
    ) order by p.atualizado_em desc), '[]'::json)
    from public.presenca p join public.perfis f on f.id = p.user_id
   where p.atualizado_em > now() - interval '15 minutes');
end; $$;
revoke all on function public.admin_online() from public, anon;
grant execute on function public.admin_online() to authenticated;

create or replace function public.admin_usuarios(p_busca text default '', p_limite int default 50, p_offset int default 0, p_plano text default '')
returns json language plpgsql stable security definer set search_path to '' as $$
declare b text := lower(trim(coalesce(p_busca,'')));
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return (with base as (
      select f.*, case when f.plano = 'admin' then 'admin' when f.pro_ate > now() then 'pro' else 'free' end as ef,
             p.atualizado_em as visto_em, p.em_rota
        from public.perfis f left join public.presenca p on p.user_id = f.id
       where (b = '' or f.conta_id like '%' || replace(b, ' ', '') || '%' or lower(coalesce(f.nome,'')) like '%' || b || '%' or lower(coalesce(f.email,'')) like '%' || b || '%')
    ), filtrada as (select * from base where coalesce(p_plano,'') = '' or ef = p_plano)
    select json_build_object(
      'total', (select count(*) from filtrada),
      'lista', (select coalesce(json_agg(json_build_object(
          'conta_id', x.conta_id, 'nome', x.nome, 'email', x.email, 'plano', x.ef, 'pro_ate', x.pro_ate,
          'criado_em', x.criado_em, 'visto_em', x.visto_em,
          'online', x.visto_em > now() - interval '3 minutes', 'em_rota', coalesce(x.em_rota,false) and x.visto_em > now() - interval '15 minutes',
          'rotas', (select count(*) from public.rotas_reg r where r.user_id = x.id),
          'insignias', (select count(*) from public.insignias_usuario i where i.user_id = x.id)
        ) order by x.criado_em desc), '[]'::json)
        from (select * from filtrada order by criado_em desc limit least(greatest(coalesce(p_limite,50),1),200) offset greatest(coalesce(p_offset,0),0)) x)
    ));
end; $$;
revoke all on function public.admin_usuarios(text, int, int, text) from public, anon;
grant execute on function public.admin_usuarios(text, int, int, text) to authenticated;

create or replace function public.admin_erros(p_resolvidos boolean default false, p_dias int default 30)
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return (select coalesce(json_agg(json_build_object(
      'id', e.id, 'versao', e.versao, 'origem', e.origem, 'mensagem', e.mensagem, 'pilha', e.pilha, 'tela', e.tela,
      'aparelho', e.aparelho, 'primeiro_em', e.primeiro_em, 'ultimo_em', e.ultimo_em, 'ocorrencias', e.ocorrencias,
      'pessoas', e.pessoas, 'chamado_id', e.chamado_id, 'resolvido_em', e.resolvido_em
    ) order by e.ultimo_em desc), '[]'::json)
    from (select * from public.erros_app
           where ((p_resolvidos and resolvido_em is not null) or (not p_resolvidos and resolvido_em is null))
             and ultimo_em > now() - make_interval(days => least(greatest(coalesce(p_dias,30),1),365))
           order by ultimo_em desc limit 200) e);
end; $$;
revoke all on function public.admin_erros(boolean, int) from public, anon;
grant execute on function public.admin_erros(boolean, int) to authenticated;

create or replace function public.admin_erro_resolver(p_id bigint, p_resolvido boolean default true)
returns json language plpgsql security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  update public.erros_app set resolvido_em = case when p_resolvido then now() end where id = p_id;
  return json_build_object('ok', true);
end; $$;
revoke all on function public.admin_erro_resolver(bigint, boolean) from public, anon;
grant execute on function public.admin_erro_resolver(bigint, boolean) to authenticated;

-- missões e insígnias (programa)
create or replace function public.admin_missoes()
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return json_build_object(
    'missoes', (select coalesce(json_agg(json_build_object(
        'id', m.id, 'titulo', m.titulo, 'descricao', m.descricao, 'icone', m.icone, 'cor1', m.cor1, 'cor2', m.cor2,
        'dificuldade', m.dificuldade, 'tipo', m.tipo, 'meta', m.meta, 'prazo_dias', m.prazo_dias, 'insignia_id', m.insignia_id,
        'ativa', m.ativa, 'ordem', m.ordem, 'criado_em', m.criado_em,
        'aceitas', (select count(*) from public.missoes_usuario u where u.missao_id = m.id and u.status = 'aceita'),
        'concluidas', (select count(*) from public.missoes_usuario u where u.missao_id = m.id and u.status = 'concluida')
      ) order by m.ordem, m.id), '[]'::json) from public.missoes m),
    'insignias', (select coalesce(json_agg(json_build_object(
        'id', i.id, 'nome', i.nome, 'descricao', i.descricao, 'icone', i.icone, 'cor1', i.cor1, 'cor2', i.cor2,
        'raridade', i.raridade, 'ativa', i.ativa, 'ordem', i.ordem,
        'ganhas', (select count(*) from public.insignias_usuario u where u.insignia_id = i.id)
      ) order by i.ordem, i.id), '[]'::json) from public.insignias i)
  );
end; $$;
revoke all on function public.admin_missoes() from public, anon;
grant execute on function public.admin_missoes() to authenticated;

create or replace function public.admin_missao_salvar(d jsonb)
returns json language plpgsql security definer set search_path to '' as $$
declare novo bigint;
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  if nullif(d->>'id','') is null then
    insert into public.missoes(titulo, descricao, icone, cor1, cor2, dificuldade, tipo, meta, prazo_dias, insignia_id, ativa, ordem)
    values (d->>'titulo', coalesce(d->>'descricao',''), coalesce(nullif(d->>'icone',''),'flag'), coalesce(nullif(d->>'cor1',''),'#6d28d9'),
            coalesce(nullif(d->>'cor2',''),'#a78bfa'), coalesce(nullif(d->>'dificuldade',''),'media'), d->>'tipo', (d->>'meta')::numeric,
            nullif(d->>'prazo_dias','')::int, nullif(d->>'insignia_id','')::bigint, coalesce((d->>'ativa')::boolean, true), coalesce(nullif(d->>'ordem','')::int, 0))
    returning id into novo;
  else
    update public.missoes set titulo = d->>'titulo', descricao = coalesce(d->>'descricao',''), icone = coalesce(nullif(d->>'icone',''),'flag'),
           cor1 = coalesce(nullif(d->>'cor1',''),cor1), cor2 = coalesce(nullif(d->>'cor2',''),cor2), dificuldade = coalesce(nullif(d->>'dificuldade',''),dificuldade),
           tipo = d->>'tipo', meta = (d->>'meta')::numeric, prazo_dias = nullif(d->>'prazo_dias','')::int,
           insignia_id = nullif(d->>'insignia_id','')::bigint, ativa = coalesce((d->>'ativa')::boolean, ativa),
           ordem = coalesce(nullif(d->>'ordem','')::int, ordem), atualizado_em = now()
     where id = (d->>'id')::bigint returning id into novo;
  end if;
  return json_build_object('ok', true, 'id', novo);
end; $$;
revoke all on function public.admin_missao_salvar(jsonb) from public, anon;
grant execute on function public.admin_missao_salvar(jsonb) to authenticated;

create or replace function public.admin_missao_apagar(p_id bigint)
returns json language plpgsql security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  -- quem já concluiu continua com a insígnia; a missão some do app
  delete from public.missoes where id = p_id;
  return json_build_object('ok', true);
end; $$;
revoke all on function public.admin_missao_apagar(bigint) from public, anon;
grant execute on function public.admin_missao_apagar(bigint) to authenticated;

create or replace function public.admin_insignia_salvar(d jsonb)
returns json language plpgsql security definer set search_path to '' as $$
declare novo bigint;
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  if nullif(d->>'id','') is null then
    insert into public.insignias(nome, descricao, icone, cor1, cor2, raridade, ativa, ordem)
    values (d->>'nome', coalesce(d->>'descricao',''), coalesce(nullif(d->>'icone',''),'military_tech'), coalesce(nullif(d->>'cor1',''),'#b45309'),
            coalesce(nullif(d->>'cor2',''),'#fbbf24'), coalesce(nullif(d->>'raridade',''),'comum'), coalesce((d->>'ativa')::boolean, true), coalesce(nullif(d->>'ordem','')::int, 0))
    returning id into novo;
  else
    update public.insignias set nome = d->>'nome', descricao = coalesce(d->>'descricao',''), icone = coalesce(nullif(d->>'icone',''),'military_tech'),
           cor1 = coalesce(nullif(d->>'cor1',''),cor1), cor2 = coalesce(nullif(d->>'cor2',''),cor2), raridade = coalesce(nullif(d->>'raridade',''),raridade),
           ativa = coalesce((d->>'ativa')::boolean, ativa), ordem = coalesce(nullif(d->>'ordem','')::int, ordem)
     where id = (d->>'id')::bigint returning id into novo;
  end if;
  return json_build_object('ok', true, 'id', novo);
end; $$;
revoke all on function public.admin_insignia_salvar(jsonb) from public, anon;
grant execute on function public.admin_insignia_salvar(jsonb) to authenticated;

create or replace function public.admin_insignia_apagar(p_id bigint)
returns json language plpgsql security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  if exists (select 1 from public.insignias_usuario where insignia_id = p_id) then
    -- já foi ganha por alguém: só desativa (some das missões novas, quem ganhou continua com ela)
    update public.insignias set ativa = false where id = p_id;
    return json_build_object('ok', true, 'desativada', true);
  end if;
  delete from public.insignias where id = p_id;
  return json_build_object('ok', true);
end; $$;
revoke all on function public.admin_insignia_apagar(bigint) from public, anon;
grant execute on function public.admin_insignia_apagar(bigint) to authenticated;

-- limpeza: presença do dia e rotas com mais de 6 anos
select cron.unschedule('prismo-presenca-limpeza') where exists (select 1 from cron.job where jobname = 'prismo-presenca-limpeza');
select cron.schedule('prismo-presenca-limpeza', '40 7 * * *',
  $$delete from public.presenca_dia where dia < current_date - 2200; delete from public.rotas_reg where dia < current_date - 2200;$$);

-- missões e insígnias iniciais (só na primeira vez: depois tudo é feito pelo programa)
do $$
declare i1 bigint; i2 bigint; i3 bigint; i4 bigint; i5 bigint; i6 bigint;
begin
  if exists (select 1 from public.missoes) or exists (select 1 from public.insignias) then return; end if;
  insert into public.insignias(nome, descricao, icone, cor1, cor2, raridade, ordem) values ('Primeiros passos', 'Fez a primeira rota com o Prismo Drive.', 'route', '#15803d', '#4ade80', 'comum', 1) returning id into i1;
  insert into public.insignias(nome, descricao, icone, cor1, cor2, raridade, ordem) values ('Semana cheia', 'Rodou 5 dias na mesma semana.', 'calendar_month', '#1d4ed8', '#60a5fa', 'rara', 2) returning id into i2;
  insert into public.insignias(nome, descricao, icone, cor1, cor2, raridade, ordem) values ('Maratonista', 'Rodou 300 km em 7 dias.', 'local_fire_department', '#c2410c', '#fb923c', 'epica', 3) returning id into i3;
  insert into public.insignias(nome, descricao, icone, cor1, cor2, raridade, ordem) values ('Gorjeta de ouro', 'Recebeu 5 gorjetas.', 'volunteer_activism', '#a16207', '#facc15', 'rara', 4) returning id into i4;
  insert into public.insignias(nome, descricao, icone, cor1, cor2, raridade, ordem) values ('Meta batida', 'Bateu a meta diária em 3 dias.', 'savings', '#0f766e', '#2dd4bf', 'rara', 5) returning id into i5;
  insert into public.insignias(nome, descricao, icone, cor1, cor2, raridade, ordem) values ('Bom de turma', 'Indicou 3 amigos que entraram no app.', 'groups', '#6d28d9', '#a78bfa', 'lendaria', 6) returning id into i6;
  insert into public.missoes(titulo, descricao, icone, cor1, cor2, dificuldade, tipo, meta, prazo_dias, insignia_id, ordem) values
    ('Primeira rota', 'Faça 1 rota do iFood ou do InDrive com o assistente de rotas ligado.', 'route', '#16a34a', '#4ade80', 'facil', 'rotas', 1, null, i1, 1),
    ('Semana cheia', 'Rode em 5 dias diferentes dentro de 7 dias depois de aceitar.', 'calendar_month', '#2563eb', '#60a5fa', 'media', 'dias', 5, 7, i2, 2),
    ('Maratonista', 'Rode 300 km em rotas dentro de 7 dias depois de aceitar.', 'local_fire_department', '#ea580c', '#fb923c', 'dificil', 'km', 300, 7, i3, 3),
    ('Gorjeta de ouro', 'Registre 5 gorjetas na Linha do tempo.', 'volunteer_activism', '#ca8a04', '#facc15', 'facil', 'gorjetas', 5, null, i4, 4),
    ('Meta batida', 'Bata a sua meta diária em 3 dias.', 'savings', '#0f766e', '#2dd4bf', 'media', 'meta', 3, 30, i5, 5),
    ('Bom de turma', 'Tenha 3 indicações confirmadas no Indique e ganhe.', 'groups', '#7c3aed', '#a78bfa', 'dificil', 'indicacoes', 3, null, i6, 6);
end $$;
