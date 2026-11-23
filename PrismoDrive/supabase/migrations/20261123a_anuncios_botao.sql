-- ═══════════════════════════════════════════════════════════════════════════
-- Prismo Drive · Popup do anúncio com tempo para fechar e botão personalizado
-- Rodar uma vez no SQL Editor do Supabase (pode rodar de novo sem problema).
--
-- • fechar_seg: quantos segundos até o X liberar (0 = fecha na hora · padrão 10 · máximo 120)
-- • botão opcional: texto (ex.: "Saiba mais") + ação:
--     link → abre um endereço https:// no navegador
--     app  → abre uma tela do Prismo Drive (lista fechada abaixo)
-- • Vários popups ativos aparecem um depois do outro: ao fechar um, abre o próximo.
-- ═══════════════════════════════════════════════════════════════════════════

alter table public.avisos add column if not exists fechar_seg int not null default 10;
alter table public.avisos add column if not exists botao_texto text;
alter table public.avisos add column if not exists botao_acao text;
alter table public.avisos add column if not exists botao_alvo text;

do $$ begin
  alter table public.avisos add constraint avisos_fechar_seg_ck check (fechar_seg between 0 and 120);
exception when duplicate_object then null; end $$;
do $$ begin
  alter table public.avisos add constraint avisos_botao_ck check (
    (botao_texto is null and botao_acao is null and botao_alvo is null)
    or (length(botao_texto) between 1 and 30 and (
         (botao_acao = 'link' and botao_alvo ~ '^https://' and length(botao_alvo) <= 500)
      or (botao_acao = 'app' and botao_alvo in ('plano','missoes','amigos','ranking','perfil','veiculo','turno','notificacoes','ajuda','suporte','novidades')))));
exception when duplicate_object then null; end $$;

-- app: lê os anúncios com os campos novos
create or replace function public.avisos_app()
returns json language sql stable security definer set search_path to '' as $$
  select coalesce(json_agg(json_build_object('id', a.id, 'titulo', a.titulo, 'texto', a.texto, 'publico', a.publico,
           'inicio', a.inicio, 'dias', a.dias, 'semana', a.semana, 'turnos', a.turnos,
           'imagem', a.imagem, 'popup', a.popup, 'servidor', a.servidor,
           'fechar_seg', a.fechar_seg, 'botao_texto', a.botao_texto, 'botao_acao', a.botao_acao, 'botao_alvo', a.botao_alvo)
           order by a.id desc), '[]'::json)
    from (select * from public.avisos
           where ativo and (inicio + dias) > (now() at time zone 'America/Sao_Paulo')::date
           order by id desc limit 30) a;
$$;
revoke all on function public.avisos_app() from public;
grant execute on function public.avisos_app() to anon, authenticated;

-- Prismo Admin: publicar (os parâmetros novos têm padrão: o exe antigo continua funcionando)
drop function if exists public.admin_anunciar(text, text, text, date, integer, integer[], text[], text, boolean);
create or replace function public.admin_anunciar(titulo text, texto text, publico text, inicio date, dias integer, semana integer[], turnos text[],
                                                 imagem text default null, popup boolean default false,
                                                 fechar_seg integer default 10, botao_texto text default null,
                                                 botao_acao text default null, botao_alvo text default null)
returns json language plpgsql security definer set search_path to '' as $$
declare novo public.avisos; img text := nullif(trim(coalesce(imagem,'')),'');
        bt text := nullif(trim(coalesce(botao_texto,'')),''); ba text := nullif(trim(coalesce(botao_acao,'')),'');
        bal text := nullif(trim(coalesce(botao_alvo,'')),''); fs int := coalesce(fechar_seg, 10);
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  if texto is null or length(trim(texto)) < 3 then raise exception 'texto_curto'; end if;
  if turnos is null or array_length(turnos,1) is null or not (turnos <@ array['manha','tarde','noite']) then raise exception 'turnos_invalidos'; end if;
  if semana is null or array_length(semana,1) is null then raise exception 'dias_semana_invalidos'; end if;
  if img is not null and img !~ '^https://xzdqvdrwvozoedmjwwhm\.supabase\.co/storage/v1/object/public/jogo/' then raise exception 'imagem_invalida'; end if;
  if fs < 0 or fs > 120 then raise exception 'tempo_invalido'; end if;
  if bt is null then ba := null; bal := null;
  else
    if length(bt) > 30 then raise exception 'botao_texto_longo'; end if;
    if ba = 'link' then
      if bal is null or bal !~ '^https://' or length(bal) > 500 then raise exception 'botao_link_invalido'; end if;
    elsif ba = 'app' then
      if bal is null or bal not in ('plano','missoes','amigos','ranking','perfil','veiculo','turno','notificacoes','ajuda','suporte','novidades') then raise exception 'botao_tela_invalida'; end if;
    else raise exception 'botao_acao_invalida'; end if;
  end if;
  insert into public.avisos(titulo, texto, publico, inicio, dias, semana, turnos, criado_por, imagem, popup, servidor, fechar_seg, botao_texto, botao_acao, botao_alvo)
  values (coalesce(nullif(trim(titulo),''),'Prismo Drive'), trim(texto), coalesce(publico,'todos'), coalesce(inicio, (now() at time zone 'America/Sao_Paulo')::date),
          dias, semana, turnos, auth.uid(), img, coalesce(popup, false) or img is not null, true, fs, bt, ba, bal)
  returning * into novo;
  insert into public.admin_log(admin_id, acao, conta_id, detalhes) values (auth.uid(), 'anunciar', null, row_to_json(novo)::jsonb);
  return row_to_json(novo);
end; $$;
revoke all on function public.admin_anunciar(text,text,text,date,integer,integer[],text[],text,boolean,integer,text,text,text) from public, anon;
grant execute on function public.admin_anunciar(text,text,text,date,integer,integer[],text[],text,boolean,integer,text,text,text) to authenticated;

create or replace function public.admin_avisos()
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return (select coalesce(json_agg(json_build_object('id', a.id, 'titulo', a.titulo, 'texto', a.texto, 'publico', a.publico, 'inicio', a.inicio,
            'dias', a.dias, 'semana', a.semana, 'turnos', a.turnos, 'ativo', a.ativo, 'imagem', a.imagem, 'popup', a.popup, 'servidor', a.servidor,
            'fechar_seg', a.fechar_seg, 'botao_texto', a.botao_texto, 'botao_acao', a.botao_acao, 'botao_alvo', a.botao_alvo,
            'criado_em', a.criado_em, 'envios', (select count(*) from public.avisos_envios e where e.aviso_id = a.id)) order by a.criado_em desc), '[]'::json)
            from (select * from public.avisos order by criado_em desc limit 30) a);
end; $$;
revoke all on function public.admin_avisos() from public, anon;
grant execute on function public.admin_avisos() to authenticated;
