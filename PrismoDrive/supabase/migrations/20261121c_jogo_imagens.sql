-- ═══════════════════════════════════════════════════════════════════════════
-- Prismo Drive · v2.3 · Missões e insígnias com imagem + missões que exigem o assistente de rotas
-- Rodar uma vez no SQL Editor do Supabase (pode rodar de novo sem problema).
-- ═══════════════════════════════════════════════════════════════════════════

-- 1) colunas novas
alter table public.missoes add column if not exists imagem text check (imagem is null or length(imagem) <= 500);
alter table public.missoes add column if not exists exige_assistente boolean not null default false;
alter table public.insignias add column if not exists imagem text check (imagem is null or length(imagem) <= 500);
-- tipo novo: dias com o assistente de rotas ligado
alter table public.missoes drop constraint if exists missoes_tipo_check;
alter table public.missoes add constraint missoes_tipo_check check (tipo in ('rotas','km','dias','gorjetas','ganho','turnos','meta','indicacoes','assistente'));

-- 2) imagens: pasta pública "jogo" (qualquer um vê; só o administrador envia, troca ou apaga)
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('jogo', 'jogo', true, 2097152, array['image/png','image/jpeg','image/webp','image/gif'])
on conflict (id) do update set public = true, file_size_limit = 2097152, allowed_mime_types = array['image/png','image/jpeg','image/webp','image/gif'];
drop policy if exists "jogo: admin envia" on storage.objects;
create policy "jogo: admin envia" on storage.objects for insert to authenticated with check (bucket_id = 'jogo' and public.eh_admin());
drop policy if exists "jogo: admin troca" on storage.objects;
create policy "jogo: admin troca" on storage.objects for update to authenticated using (bucket_id = 'jogo' and public.eh_admin()) with check (bucket_id = 'jogo' and public.eh_admin());
-- ler os metadados é preciso para enviar com "substituir se existir" (x-upsert)
drop policy if exists "jogo: todos veem" on storage.objects;
create policy "jogo: todos veem" on storage.objects for select to anon, authenticated using (bucket_id = 'jogo');
drop policy if exists "jogo: admin apaga" on storage.objects;
create policy "jogo: admin apaga" on storage.objects for delete to authenticated using (bucket_id = 'jogo' and public.eh_admin());

-- 3) funções com os campos novos
create or replace function public._insignia_json(i public.insignias)
returns json language sql stable security definer set search_path to '' as $$
  select json_build_object('id', i.id, 'nome', i.nome, 'descricao', i.descricao, 'icone', i.icone, 'imagem', i.imagem,
    'cor1', i.cor1, 'cor2', i.cor2, 'raridade', i.raridade, 'ativa', i.ativa, 'ordem', i.ordem);
$$;
revoke all on function public._insignia_json(public.insignias) from public, anon, authenticated;

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

create or replace function public.admin_missoes()
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return json_build_object(
    'missoes', (select coalesce(json_agg(json_build_object(
        'id', m.id, 'titulo', m.titulo, 'descricao', m.descricao, 'icone', m.icone, 'imagem', m.imagem, 'cor1', m.cor1, 'cor2', m.cor2,
        'dificuldade', m.dificuldade, 'tipo', m.tipo, 'meta', m.meta, 'prazo_dias', m.prazo_dias, 'insignia_id', m.insignia_id,
        'exige_assistente', m.exige_assistente, 'ativa', m.ativa, 'ordem', m.ordem, 'criado_em', m.criado_em,
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
    insert into public.missoes(titulo, descricao, icone, imagem, cor1, cor2, dificuldade, tipo, meta, prazo_dias, insignia_id, exige_assistente, ativa, ordem)
    values (d->>'titulo', coalesce(d->>'descricao',''), coalesce(nullif(d->>'icone',''),'flag'), nullif(d->>'imagem',''), coalesce(nullif(d->>'cor1',''),'#6d28d9'),
            coalesce(nullif(d->>'cor2',''),'#a78bfa'), coalesce(nullif(d->>'dificuldade',''),'media'), d->>'tipo', (d->>'meta')::numeric,
            nullif(d->>'prazo_dias','')::int, nullif(d->>'insignia_id','')::bigint, coalesce((d->>'exige_assistente')::boolean, false),
            coalesce((d->>'ativa')::boolean, true), coalesce(nullif(d->>'ordem','')::int, 0))
    returning id into novo;
  else
    update public.missoes set titulo = d->>'titulo', descricao = coalesce(d->>'descricao',''), icone = coalesce(nullif(d->>'icone',''),'flag'),
           imagem = nullif(d->>'imagem',''),
           cor1 = coalesce(nullif(d->>'cor1',''),cor1), cor2 = coalesce(nullif(d->>'cor2',''),cor2), dificuldade = coalesce(nullif(d->>'dificuldade',''),dificuldade),
           tipo = d->>'tipo', meta = (d->>'meta')::numeric, prazo_dias = nullif(d->>'prazo_dias','')::int,
           insignia_id = nullif(d->>'insignia_id','')::bigint, exige_assistente = coalesce((d->>'exige_assistente')::boolean, exige_assistente),
           ativa = coalesce((d->>'ativa')::boolean, ativa), ordem = coalesce(nullif(d->>'ordem','')::int, ordem), atualizado_em = now()
     where id = (d->>'id')::bigint returning id into novo;
  end if;
  return json_build_object('ok', true, 'id', novo);
end; $$;
revoke all on function public.admin_missao_salvar(jsonb) from public, anon;
grant execute on function public.admin_missao_salvar(jsonb) to authenticated;

create or replace function public.admin_insignia_salvar(d jsonb)
returns json language plpgsql security definer set search_path to '' as $$
declare novo bigint;
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  if nullif(d->>'id','') is null then
    insert into public.insignias(nome, descricao, icone, imagem, cor1, cor2, raridade, ativa, ordem)
    values (d->>'nome', coalesce(d->>'descricao',''), coalesce(nullif(d->>'icone',''),'military_tech'), nullif(d->>'imagem',''), coalesce(nullif(d->>'cor1',''),'#b45309'),
            coalesce(nullif(d->>'cor2',''),'#fbbf24'), coalesce(nullif(d->>'raridade',''),'comum'), coalesce((d->>'ativa')::boolean, true), coalesce(nullif(d->>'ordem','')::int, 0))
    returning id into novo;
  else
    update public.insignias set nome = d->>'nome', descricao = coalesce(d->>'descricao',''), icone = coalesce(nullif(d->>'icone',''),'military_tech'),
           imagem = nullif(d->>'imagem',''),
           cor1 = coalesce(nullif(d->>'cor1',''),cor1), cor2 = coalesce(nullif(d->>'cor2',''),cor2), raridade = coalesce(nullif(d->>'raridade',''),raridade),
           ativa = coalesce((d->>'ativa')::boolean, ativa), ordem = coalesce(nullif(d->>'ordem','')::int, ordem)
     where id = (d->>'id')::bigint returning id into novo;
  end if;
  return json_build_object('ok', true, 'id', novo);
end; $$;
revoke all on function public.admin_insignia_salvar(jsonb) from public, anon;
grant execute on function public.admin_insignia_salvar(jsonb) to authenticated;
