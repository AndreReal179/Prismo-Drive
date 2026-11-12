-- v1.9.8 · Chamados do suporte (status, respostas, avisos)
alter table public.suporte add column if not exists status text not null default 'aguardando';
alter table public.suporte drop constraint if exists suporte_status_chk;
alter table public.suporte add constraint suporte_status_chk check (status in ('aguardando','andamento','respondido','finalizado'));
alter table public.suporte add column if not exists visto_em timestamptz;
alter table public.suporte add column if not exists respondido_em timestamptz;
alter table public.suporte add column if not exists finalizado_em timestamptz;
alter table public.suporte add column if not exists cliente_visto_em timestamptz;
alter table public.suporte add column if not exists atualizado_em timestamptz not null default now();

create table if not exists public.suporte_msgs (
  id bigint generated always as identity primary key,
  chamado_id bigint not null references public.suporte(id) on delete cascade,
  autor text not null check (autor in ('cliente','admin')),
  texto text not null check (length(texto) between 1 and 4000),
  criado_em timestamptz not null default now()
);
create index if not exists suporte_msgs_chamado_idx on public.suporte_msgs(chamado_id, criado_em);
alter table public.suporte_msgs enable row level security;
revoke all on public.suporte_msgs from anon, authenticated;

create or replace function public._chamado_json(c public.suporte, admin boolean)
returns json language sql stable security definer set search_path to '' as $$
  select json_build_object(
    'id', c.id, 'criado_em', c.criado_em, 'categoria', c.categoria, 'assunto', c.assunto, 'mensagem', c.mensagem,
    'status', c.status, 'visto_em', c.visto_em, 'respondido_em', c.respondido_em, 'finalizado_em', c.finalizado_em,
    'cliente_visto_em', c.cliente_visto_em, 'atualizado_em', c.atualizado_em,
    'anexos', coalesce(jsonb_array_length(c.anexos), 0),
    'nome', case when admin then c.nome end, 'email', case when admin then c.email end,
    'conta_id', case when admin then c.conta_id end, 'plano', case when admin then c.plano end,
    'app_versao', case when admin then c.app_versao end, 'aparelho', case when admin then c.aparelho end,
    'msgs', (select coalesce(json_agg(json_build_object('autor', m.autor, 'texto', m.texto, 'criado_em', m.criado_em) order by m.criado_em), '[]'::json)
             from public.suporte_msgs m where m.chamado_id = c.id)
  );
$$;
revoke all on function public._chamado_json(public.suporte, boolean) from public, anon, authenticated;

create or replace function public.meus_chamados()
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  return (select coalesce(json_agg(public._chamado_json(c, false) order by c.criado_em desc), '[]'::json)
          from (select * from public.suporte where user_id = auth.uid() order by criado_em desc limit 30) c);
end; $$;
revoke all on function public.meus_chamados() from public, anon;
grant execute on function public.meus_chamados() to authenticated;

create or replace function public.chamado_visto(chamado bigint)
returns json language plpgsql security definer set search_path to '' as $$
begin
  update public.suporte set cliente_visto_em = now() where id = chamado and user_id = auth.uid();
  return json_build_object('ok', true);
end; $$;
revoke all on function public.chamado_visto(bigint) from public, anon;
grant execute on function public.chamado_visto(bigint) to authenticated;

create or replace function public.chamado_responder(chamado bigint, texto text)
returns json language plpgsql security definer set search_path to '' as $$
declare c public.suporte;
begin
  select * into c from public.suporte where id = chamado and user_id = auth.uid() for update;
  if not found then raise exception 'chamado_nao_encontrado'; end if;
  if c.status = 'finalizado' then raise exception 'chamado_finalizado'; end if;
  if texto is null or length(trim(texto)) < 2 then raise exception 'texto_curto'; end if;
  insert into public.suporte_msgs(chamado_id, autor, texto) values (c.id, 'cliente', left(trim(texto), 4000));
  update public.suporte set status = 'aguardando', cliente_visto_em = now(), atualizado_em = now() where id = c.id;
  return json_build_object('ok', true);
end; $$;
revoke all on function public.chamado_responder(bigint, text) from public, anon;
grant execute on function public.chamado_responder(bigint, text) to authenticated;

create or replace function public.admin_chamados(abertos boolean default true)
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return (select coalesce(json_agg(public._chamado_json(c, true) order by
            case c.status when 'aguardando' then 0 when 'andamento' then 1 when 'respondido' then 2 else 3 end, c.atualizado_em desc), '[]'::json)
          from (select * from public.suporte
                 where (abertos and status <> 'finalizado') or (not abertos and status = 'finalizado')
                 order by atualizado_em desc limit 60) c);
end; $$;
revoke all on function public.admin_chamados(boolean) from public, anon;
grant execute on function public.admin_chamados(boolean) to authenticated;

create or replace function public.admin_chamado_abrir(chamado bigint)
returns json language plpgsql security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  update public.suporte set status = 'andamento', visto_em = coalesce(visto_em, now()), atualizado_em = now()
   where id = chamado and status = 'aguardando';
  return (select public._chamado_json(c, true) from public.suporte c where c.id = chamado);
end; $$;
revoke all on function public.admin_chamado_abrir(bigint) from public, anon;
grant execute on function public.admin_chamado_abrir(bigint) to authenticated;

create or replace function public.admin_chamado_finalizar(chamado bigint)
returns json language plpgsql security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  update public.suporte set status = 'finalizado', finalizado_em = now(), atualizado_em = now() where id = chamado;
  insert into public.admin_log(admin_id, acao, conta_id, detalhes) values (auth.uid(), 'finalizar_chamado', null, json_build_object('id', chamado));
  return json_build_object('ok', true);
end; $$;
revoke all on function public.admin_chamado_finalizar(bigint) from public, anon;
grant execute on function public.admin_chamado_finalizar(bigint) to authenticated;

create or replace function public.admin_suporte_pendentes()
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return json_build_object(
    'aguardando', (select count(*) from public.suporte where status = 'aguardando'),
    'abertos', (select count(*) from public.suporte where status <> 'finalizado'),
    'ultimo', (select max(atualizado_em) from public.suporte where status = 'aguardando'));
end; $$;
revoke all on function public.admin_suporte_pendentes() from public, anon;
grant execute on function public.admin_suporte_pendentes() to authenticated;
