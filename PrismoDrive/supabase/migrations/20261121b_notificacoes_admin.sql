-- ═══════════════════════════════════════════════════════════════════════════
-- Prismo Drive · v2.3 · Prismo Admin: notificações personalizadas (na hora ou agendadas),
-- histórico de ações do administrador e correção do "em rota".
-- Rodar uma vez no SQL Editor do Supabase (pode rodar de novo sem problema).
-- ═══════════════════════════════════════════════════════════════════════════

-- ══════════════ 1) Notificações personalizadas ══════════════
create table if not exists public.notif_envios (
  id bigint generated always as identity primary key,
  titulo text not null check (length(titulo) between 1 and 120),
  texto text not null check (length(texto) between 2 and 400),
  publico text not null check (publico in ('todos','free','pro','usuario','conta')),
  conta_id text,
  user_id uuid references auth.users(id) on delete set null,
  enviar_em timestamptz not null default now(),
  status text not null default 'agendada' check (status in ('agendada','enviada','cancelada','erro')),
  enviada_em timestamptz,
  fila_id bigint,
  criado_por uuid,
  criado_em timestamptz not null default now()
);
create index if not exists notif_envios_agenda_idx on public.notif_envios(enviar_em) where status = 'agendada';
alter table public.notif_envios enable row level security;
revoke all on public.notif_envios from anon, authenticated;

-- coloca na fila do push e chama a função "push" (Firebase) na hora
create or replace function public._notif_disparar(p_id bigint)
returns void language plpgsql security definer set search_path to '' as $$
declare n public.notif_envios; f bigint;
begin
  select * into n from public.notif_envios where id = p_id and status = 'agendada' for update;
  if not found then return; end if;
  insert into public.push_fila(user_id, admins, topico, nid, titulo, texto)
  values (case when n.publico = 'conta' then n.user_id end, false,
          case when n.publico = 'conta' then null else n.publico end,
          9400 + (n.id % 100)::int, n.titulo, n.texto)
  returning id into f;
  update public.notif_envios set status = 'enviada', enviada_em = now(), fila_id = f where id = n.id;
  begin
    perform net.http_post(
      url := 'https://xzdqvdrwvozoedmjwwhm.supabase.co/functions/v1/push',
      body := jsonb_build_object('id', f),
      headers := '{"Content-Type":"application/json"}'::jsonb,
      timeout_milliseconds := 30000);
  exception when others then
    update public.notif_envios set status = 'erro' where id = n.id;
  end;
end; $$;
revoke all on function public._notif_disparar(bigint) from public, anon, authenticated;

-- programa: enviar agora (p_quando vazio) ou agendar para uma data e hora
create or replace function public.admin_notif_enviar(p_titulo text, p_texto text, p_publico text default 'todos', p_conta text default null, p_quando timestamptz default null)
returns json language plpgsql security definer set search_path to '' as $$
declare u uuid; c text; novo public.notif_envios;
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  if coalesce(length(trim(p_titulo)),0) < 1 then raise exception 'titulo_vazio'; end if;
  if coalesce(length(trim(p_texto)),0) < 2 then raise exception 'texto_curto'; end if;
  if p_publico not in ('todos','free','pro','usuario','conta') then raise exception 'publico_invalido'; end if;
  if p_publico = 'conta' then
    c := regexp_replace(coalesce(p_conta,''), '\D', '', 'g');
    select id into u from public.perfis where conta_id = c;
    if u is null then raise exception 'conta_nao_encontrada'; end if;
  end if;
  if p_quando is not null and p_quando > now() + interval '366 days' then raise exception 'data_longe'; end if;
  insert into public.notif_envios(titulo, texto, publico, conta_id, user_id, enviar_em, criado_por)
  values (left(trim(p_titulo),120), left(trim(p_texto),400), p_publico, c, u, coalesce(greatest(p_quando, now()), now()), auth.uid())
  returning * into novo;
  begin
    insert into public.admin_log(admin_id, acao, conta_id, detalhes)
    values (auth.uid(), case when p_quando is null or p_quando <= now() + interval '30 seconds' then 'notificar' else 'agendar_notificacao' end, c, row_to_json(novo)::jsonb);
  exception when others then null;
  end;
  if p_quando is null or p_quando <= now() + interval '30 seconds' then
    perform public._notif_disparar(novo.id);
  end if;
  return (select row_to_json(x) from public.notif_envios x where x.id = novo.id);
end; $$;
revoke all on function public.admin_notif_enviar(text, text, text, text, timestamptz) from public, anon;
grant execute on function public.admin_notif_enviar(text, text, text, text, timestamptz) to authenticated;

create or replace function public.admin_notif_lista()
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return (select coalesce(json_agg(json_build_object(
      'id', n.id, 'titulo', n.titulo, 'texto', n.texto, 'publico', n.publico, 'conta_id', n.conta_id,
      'nome', (select f.nome from public.perfis f where f.id = n.user_id),
      'enviar_em', n.enviar_em, 'status', n.status, 'enviada_em', n.enviada_em, 'criado_em', n.criado_em,
      'entregues', q.enviados, 'erro', q.erro, 'processada_em', q.enviado_em
    ) order by case when n.status = 'agendada' then 0 else 1 end, coalesce(n.enviada_em, n.enviar_em) desc), '[]'::json)
    from (select * from public.notif_envios order by criado_em desc limit 80) n
    left join public.push_fila q on q.id = n.fila_id);
end; $$;
revoke all on function public.admin_notif_lista() from public, anon;
grant execute on function public.admin_notif_lista() to authenticated;

create or replace function public.admin_notif_cancelar(p_id bigint)
returns json language plpgsql security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  update public.notif_envios set status = 'cancelada' where id = p_id and status = 'agendada';
  return json_build_object('ok', found);
end; $$;
revoke all on function public.admin_notif_cancelar(bigint) from public, anon;
grant execute on function public.admin_notif_cancelar(bigint) to authenticated;

-- a cada minuto: dispara as agendadas que chegaram na hora
create or replace function public.notif_cron()
returns int language plpgsql security definer set search_path to '' as $$
declare r record; n int := 0;
begin
  for r in select id from public.notif_envios where status = 'agendada' and enviar_em <= now() order by enviar_em limit 20 loop
    perform public._notif_disparar(r.id); n := n + 1;
  end loop;
  return n;
end; $$;
revoke all on function public.notif_cron() from public, anon, authenticated;

select cron.unschedule('prismo-notif-agendadas') where exists (select 1 from cron.job where jobname = 'prismo-notif-agendadas');
select cron.schedule('prismo-notif-agendadas', '* * * * *', $$select public.notif_cron()$$);

-- ══════════════ 2) Histórico de ações do administrador ══════════════
create or replace function public.admin_historico(p_limite int default 100)
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return (select coalesce(json_agg(to_jsonb(l) || jsonb_build_object('admin_nome', (select f.nome from public.perfis f where f.id = l.admin_id))), '[]'::json)
            from (select * from public.admin_log order by 1 desc limit least(greatest(coalesce(p_limite,100),1),300)) l);
end; $$;
revoke all on function public.admin_historico(int) from public, anon;
grant execute on function public.admin_historico(int) to authenticated;

-- ══════════════ 3) Correção: "em rota" marcado sem a acessibilidade ligada (app 2.2.6) ══════════════
-- O app 2.2.6 lia a rota como "em andamento" sempre. A partir da 2.2.7 só conta com o assistente
-- ligado e uma rota de verdade. Aqui limpamos o que foi marcado errado (vale o que tem rota registrada).
update public.presenca set em_rota = false where em_rota;
update public.presenca_dia d set em_rota = exists (select 1 from public.rotas_reg r where r.user_id = d.user_id and r.dia = d.dia)
 where d.dia >= (now() at time zone 'America/Sao_Paulo')::date - 7;

-- quem ainda está na 2.2.6 manda "em rota" errado: o servidor ignora até a pessoa atualizar
create or replace function public.app_presenca(p_em_rota boolean default false, p_app text default null, p_versao text default null)
returns json language plpgsql security definer set search_path to '' as $$
declare hoje date := (now() at time zone 'America/Sao_Paulo')::date; rota boolean;
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  rota := coalesce(p_em_rota,false) and coalesce(p_versao,'') <> '2.2.6';
  insert into public.presenca(user_id, em_rota, app, versao, atualizado_em)
  values (auth.uid(), rota, case when rota then left(p_app,20) end, left(p_versao,20), now())
  on conflict (user_id) do update set em_rota = excluded.em_rota, app = excluded.app, versao = excluded.versao, atualizado_em = now();
  insert into public.presenca_dia(user_id, dia, em_rota) values (auth.uid(), hoje, rota)
  on conflict (user_id, dia) do update set em_rota = public.presenca_dia.em_rota or excluded.em_rota;
  return json_build_object('ok', true);
end; $$;
revoke all on function public.app_presenca(boolean, text, text) from public, anon;
grant execute on function public.app_presenca(boolean, text, text) to authenticated;
