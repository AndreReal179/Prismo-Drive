-- ═══════════════════════════════════════════════════════════════════════════
-- Prismo Drive · Anúncios com imagem (popup no app) enviados pelo servidor
-- Rodar uma vez no SQL Editor do Supabase (pode rodar de novo sem problema).
--
-- • O Prismo Admin cria o anúncio com título, mensagem, imagem, dias, dias da semana e turnos.
-- • O SERVIDOR manda a notificação às 8h (manhã), 13h (tarde) e 19h (noite), mesmo com o app fechado
--   e mesmo para quem não abre o app há dias.
-- • Quem abre o app num turno do anúncio vê o popup com a imagem (fecha depois de 10 segundos).
-- • Os anúncios antigos (sem "servidor") continuam como antes: o próprio celular agenda.
-- ═══════════════════════════════════════════════════════════════════════════

alter table public.avisos add column if not exists imagem text check (imagem is null or length(imagem) <= 500);
alter table public.avisos add column if not exists popup boolean not null default false;
alter table public.avisos add column if not exists servidor boolean not null default false;

-- versões antigas do app leem a tabela direto e agendam sozinhas: elas não veem os anúncios novos
-- (quem envia esses é o servidor, então não chegam em dobro)
drop policy if exists "ler avisos ativos" on public.avisos;
create policy "ler avisos ativos" on public.avisos for select to anon, authenticated
  using (ativo and not servidor and (inicio + dias) >= (now() at time zone 'America/Sao_Paulo')::date);

-- o app novo lê todos os anúncios ativos por aqui (com imagem e popup)
create or replace function public.avisos_app()
returns json language sql stable security definer set search_path to '' as $$
  select coalesce(json_agg(json_build_object('id', a.id, 'titulo', a.titulo, 'texto', a.texto, 'publico', a.publico,
           'inicio', a.inicio, 'dias', a.dias, 'semana', a.semana, 'turnos', a.turnos,
           'imagem', a.imagem, 'popup', a.popup, 'servidor', a.servidor) order by a.id desc), '[]'::json)
    from (select * from public.avisos
           where ativo and (inicio + dias) > (now() at time zone 'America/Sao_Paulo')::date
           order by id desc limit 30) a;
$$;
revoke all on function public.avisos_app() from public;
grant execute on function public.avisos_app() to anon, authenticated;

-- controle do que já foi enviado (um envio por anúncio, dia e turno)
create table if not exists public.avisos_envios (
  aviso_id bigint not null references public.avisos(id) on delete cascade,
  dia date not null,
  turno text not null,
  enviado_em timestamptz not null default now(),
  primary key (aviso_id, dia, turno)
);
alter table public.avisos_envios enable row level security;
revoke all on public.avisos_envios from anon, authenticated;

-- envia os anúncios do turno (roda às 8h, 13h e 19h de Brasília)
create or replace function public.avisos_disparar(p_turno text)
returns int language plpgsql security definer set search_path to '' as $$
declare a public.avisos; h date := (now() at time zone 'America/Sao_Paulo')::date; n int := 0;
begin
  for a in select * from public.avisos
            where ativo and servidor and inicio <= h and (inicio + dias) > h
              and extract(dow from h)::int = any(semana) and p_turno = any(turnos)
              and criado_em < now() - interval '90 minutes'   -- acabou de ser criado: já foi a notificação da publicação
  loop
    insert into public.avisos_envios(aviso_id, dia, turno) values (a.id, h, p_turno) on conflict do nothing;
    if found then
      perform public.push_enviar(null, false, a.publico, 9298 - (a.id % 8)::int, coalesce(nullif(a.titulo,''), 'Prismo Drive'), a.texto);
      n := n + 1;
    end if;
  end loop;
  return n;
end; $$;
revoke all on function public.avisos_disparar(text) from public, anon, authenticated;

-- notificação da publicação: só se começa hoje e cai num dia da semana escolhido
create or replace function public._push_aviso()
returns trigger language plpgsql security definer set search_path to '' as $$
declare h date := (now() at time zone 'America/Sao_Paulo')::date;
begin
  if new.ativo and new.inicio <= h and (not new.servidor or extract(dow from h)::int = any(new.semana)) then
    perform public.push_enviar(null, false, new.publico, 9298 - (new.id % 8)::int, coalesce(nullif(new.titulo,''), 'Prismo Drive'), new.texto);
  end if;
  return new;
end; $$;

-- Prismo Admin: publicar com imagem e popup
drop function if exists public.admin_anunciar(text, text, text, date, integer, integer[], text[]);
create or replace function public.admin_anunciar(titulo text, texto text, publico text, inicio date, dias integer, semana integer[], turnos text[],
                                                 imagem text default null, popup boolean default false)
returns json language plpgsql security definer set search_path to '' as $$
declare novo public.avisos; img text := nullif(trim(coalesce(imagem,'')),'');
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  if texto is null or length(trim(texto)) < 3 then raise exception 'texto_curto'; end if;
  if turnos is null or array_length(turnos,1) is null or not (turnos <@ array['manha','tarde','noite']) then raise exception 'turnos_invalidos'; end if;
  if semana is null or array_length(semana,1) is null then raise exception 'dias_semana_invalidos'; end if;
  if img is not null and img !~ '^https://xzdqvdrwvozoedmjwwhm\.supabase\.co/storage/v1/object/public/jogo/' then raise exception 'imagem_invalida'; end if;
  insert into public.avisos(titulo, texto, publico, inicio, dias, semana, turnos, criado_por, imagem, popup, servidor)
  values (coalesce(nullif(trim(titulo),''),'Prismo Drive'), trim(texto), coalesce(publico,'todos'), coalesce(inicio, (now() at time zone 'America/Sao_Paulo')::date),
          dias, semana, turnos, auth.uid(), img, coalesce(popup, false) or img is not null, true)
  returning * into novo;
  insert into public.admin_log(admin_id, acao, conta_id, detalhes) values (auth.uid(), 'anunciar', null, row_to_json(novo)::jsonb);
  return row_to_json(novo);
end; $$;
revoke all on function public.admin_anunciar(text,text,text,date,integer,integer[],text[],text,boolean) from public, anon;
grant execute on function public.admin_anunciar(text,text,text,date,integer,integer[],text[],text,boolean) to authenticated;

create or replace function public.admin_avisos()
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return (select coalesce(json_agg(json_build_object('id', a.id, 'titulo', a.titulo, 'texto', a.texto, 'publico', a.publico, 'inicio', a.inicio,
            'dias', a.dias, 'semana', a.semana, 'turnos', a.turnos, 'ativo', a.ativo, 'imagem', a.imagem, 'popup', a.popup, 'servidor', a.servidor,
            'criado_em', a.criado_em, 'envios', (select count(*) from public.avisos_envios e where e.aviso_id = a.id)) order by a.criado_em desc), '[]'::json)
            from (select * from public.avisos order by criado_em desc limit 30) a);
end; $$;
revoke all on function public.admin_avisos() from public, anon;
grant execute on function public.admin_avisos() to authenticated;

-- agendamentos: 8h, 13h e 19h de Brasília = 11h, 16h e 22h UTC
do $$ begin perform cron.unschedule('prismo-avisos-manha'); exception when others then null; end $$;
do $$ begin perform cron.unschedule('prismo-avisos-tarde'); exception when others then null; end $$;
do $$ begin perform cron.unschedule('prismo-avisos-noite'); exception when others then null; end $$;
select cron.schedule('prismo-avisos-manha', '0 11 * * *', $c$select public.avisos_disparar('manha')$c$);
select cron.schedule('prismo-avisos-tarde', '0 16 * * *', $c$select public.avisos_disparar('tarde')$c$);
select cron.schedule('prismo-avisos-noite', '0 22 * * *', $c$select public.avisos_disparar('noite')$c$);
