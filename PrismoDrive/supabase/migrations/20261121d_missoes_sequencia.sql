-- ═══════════════════════════════════════════════════════════════════════════
-- Prismo Drive · Missões em sequência
-- As missões aparecem uma de cada vez, na "Ordem no app": concluiu a 1, aparece a 2, e assim vai.
-- Missão marcada como "Mostrar a qualquer momento" (livre) aparece sempre, fora da fila.
-- Rodar uma vez no SQL Editor do Supabase (pode rodar de novo sem problema).
-- ═══════════════════════════════════════════════════════════════════════════

alter table public.missoes add column if not exists livre boolean not null default false;
-- tipo novo: todas as rotas (lançadas à mão e automáticas)
alter table public.missoes drop constraint if exists missoes_tipo_check;
alter table public.missoes add constraint missoes_tipo_check check (tipo in ('rotas','rotas_todas','km','dias','gorjetas','ganho','turnos','meta','indicacoes','assistente'));

-- missões que a pessoa pode ver e aceitar agora
create or replace function public._missoes_liberadas(p_user uuid)
returns table(id bigint) language sql stable security definer set search_path to '' as $$
  with fila as (
    select m.id, row_number() over (order by m.ordem, m.id) as pos,
           coalesce(mu.status = 'concluida', false) as feita
      from public.missoes m
      left join public.missoes_usuario mu on mu.missao_id = m.id and mu.user_id = p_user
     where m.ativa and not m.livre
  ), corte as (
    select coalesce(min(pos) filter (where not feita), 2147483647) as c from fila
  )
  select f.id from fila f, corte where f.pos <= corte.c
  union
  select m.id from public.missoes m where m.ativa and m.livre
  union -- o que a pessoa já aceitou ou concluiu nunca some
  select mu.missao_id from public.missoes_usuario mu where mu.user_id = p_user and mu.status in ('aceita','concluida');
$$;
revoke all on function public._missoes_liberadas(uuid) from public, anon, authenticated;

create or replace function public.missoes_app()
returns json language plpgsql security definer set search_path to '' as $$
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  update public.missoes_usuario mu set status = 'falhou'
    from public.missoes m
   where mu.missao_id = m.id and mu.user_id = auth.uid() and mu.status = 'aceita'
     and m.prazo_dias is not null and now() > mu.aceita_em + make_interval(days => m.prazo_dias);
  return json_build_object(
    'missoes', (select coalesce(json_agg(json_build_object(
        'id', m.id, 'titulo', m.titulo, 'descricao', m.descricao, 'icone', m.icone, 'imagem', m.imagem, 'cor1', m.cor1, 'cor2', m.cor2,
        'dificuldade', m.dificuldade, 'tipo', m.tipo, 'meta', m.meta, 'prazo_dias', m.prazo_dias, 'exige_assistente', m.exige_assistente,
        'livre', m.livre,
        'insignia', (select public._insignia_json(i) from public.insignias i where i.id = m.insignia_id),
        'status', mu.status, 'progresso', mu.progresso,
        'aceita_em', case when mu.aceita_em is not null then (extract(epoch from mu.aceita_em)*1000)::bigint end,
        'concluida_em', mu.concluida_em
      ) order by m.ordem, m.id), '[]'::json)
      from public.missoes m left join public.missoes_usuario mu on mu.missao_id = m.id and mu.user_id = auth.uid()
     where m.id in (select l.id from public._missoes_liberadas(auth.uid()) l)),
    -- quantas missões da fila ainda estão escondidas (o app mostra "conclua a atual para liberar")
    'trancadas', (select count(*) from public.missoes m
                   where m.ativa and not m.livre and m.id not in (select l.id from public._missoes_liberadas(auth.uid()) l)),
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
  if p_missao not in (select l.id from public._missoes_liberadas(auth.uid()) l) then raise exception 'missao_trancada'; end if;
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

-- administrador: lê e grava o "livre"
create or replace function public.admin_missoes()
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return json_build_object(
    'missoes', (select coalesce(json_agg(json_build_object(
        'id', m.id, 'titulo', m.titulo, 'descricao', m.descricao, 'icone', m.icone, 'imagem', m.imagem, 'cor1', m.cor1, 'cor2', m.cor2,
        'dificuldade', m.dificuldade, 'tipo', m.tipo, 'meta', m.meta, 'prazo_dias', m.prazo_dias, 'insignia_id', m.insignia_id,
        'exige_assistente', m.exige_assistente, 'livre', m.livre, 'ativa', m.ativa, 'ordem', m.ordem, 'criado_em', m.criado_em,
        'aceitas', (select count(*) from public.missoes_usuario u where u.missao_id = m.id and u.status = 'aceita'),
        'concluidas', (select count(*) from public.missoes_usuario u where u.missao_id = m.id and u.status = 'concluida')
      ) order by m.ordem, m.id), '[]'::json) from public.missoes m),
    'insignias', (select coalesce(json_agg(json_build_object(
        'id', i.id, 'nome', i.nome, 'descricao', i.descricao, 'icone', i.icone, 'imagem', i.imagem, 'cor1', i.cor1, 'cor2', i.cor2,
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
    insert into public.missoes(titulo, descricao, icone, imagem, cor1, cor2, dificuldade, tipo, meta, prazo_dias, insignia_id, exige_assistente, livre, ativa, ordem)
    values (d->>'titulo', coalesce(d->>'descricao',''), coalesce(nullif(d->>'icone',''),'flag'), nullif(d->>'imagem',''), coalesce(nullif(d->>'cor1',''),'#6d28d9'),
            coalesce(nullif(d->>'cor2',''),'#a78bfa'), coalesce(nullif(d->>'dificuldade',''),'media'), d->>'tipo', (d->>'meta')::numeric,
            nullif(d->>'prazo_dias','')::int, nullif(d->>'insignia_id','')::bigint, coalesce((d->>'exige_assistente')::boolean, false),
            coalesce((d->>'livre')::boolean, false),
            coalesce((d->>'ativa')::boolean, true), coalesce(nullif(d->>'ordem','')::int, 0))
    returning id into novo;
  else
    update public.missoes set titulo = d->>'titulo', descricao = coalesce(d->>'descricao',''), icone = coalesce(nullif(d->>'icone',''),'flag'),
           imagem = nullif(d->>'imagem',''),
           cor1 = coalesce(nullif(d->>'cor1',''),cor1), cor2 = coalesce(nullif(d->>'cor2',''),cor2), dificuldade = coalesce(nullif(d->>'dificuldade',''),dificuldade),
           tipo = d->>'tipo', meta = (d->>'meta')::numeric, prazo_dias = nullif(d->>'prazo_dias','')::int,
           insignia_id = nullif(d->>'insignia_id','')::bigint, exige_assistente = coalesce((d->>'exige_assistente')::boolean, exige_assistente),
           livre = coalesce((d->>'livre')::boolean, livre),
           ativa = coalesce((d->>'ativa')::boolean, ativa), ordem = coalesce(nullif(d->>'ordem','')::int, ordem), atualizado_em = now()
     where id = (d->>'id')::bigint returning id into novo;
  end if;
  return json_build_object('ok', true, 'id', novo);
end; $$;
revoke all on function public.admin_missao_salvar(jsonb) from public, anon;
grant execute on function public.admin_missao_salvar(jsonb) to authenticated;
