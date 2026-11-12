-- v1.9.8 · notificações em tempo real (push), relatório automático de erros e proteção do Indique e ganhe
create extension if not exists pg_net;

-- ══════════════ 1) Push (Firebase Cloud Messaging) ══════════════
create table if not exists public.push_tokens (
  token text primary key check (length(token) between 20 and 500),
  user_id uuid references auth.users(id) on delete cascade,
  aparelho text,
  atualizado_em timestamptz not null default now()
);
create index if not exists push_tokens_user_idx on public.push_tokens(user_id);
alter table public.push_tokens enable row level security;
revoke all on public.push_tokens from anon, authenticated;

create table if not exists public.push_fila (
  id bigint generated always as identity primary key,
  criado_em timestamptz not null default now(),
  user_id uuid references auth.users(id) on delete cascade,
  admins boolean not null default false,
  topico text check (topico is null or topico in ('todos','free','pro','usuario')),
  nid int not null default 9210,
  titulo text not null,
  texto text not null,
  enviado_em timestamptz,
  enviados int,
  erro text
);
create index if not exists push_fila_criado_idx on public.push_fila(criado_em desc);
alter table public.push_fila enable row level security;
revoke all on public.push_fila from anon, authenticated;

-- configuração PÚBLICA do Firebase (appId, apiKey, projectId, senderId) que o app baixa para ligar o push
create table if not exists public.app_config (
  chave text primary key,
  valor jsonb not null,
  atualizado_em timestamptz not null default now()
);
alter table public.app_config enable row level security;
drop policy if exists "ler config publica" on public.app_config;
create policy "ler config publica" on public.app_config for select to anon, authenticated using (chave in ('firebase'));
revoke all on public.app_config from anon, authenticated;
grant select on public.app_config to anon, authenticated;

-- o app registra o aparelho para receber push (com ou sem conta)
create or replace function public.push_registrar(token text, aparelho text default null)
returns json language plpgsql security definer set search_path to '' as $$
begin
  if token is null or length(token) not between 20 and 500 then raise exception 'token_invalido'; end if;
  insert into public.push_tokens(token, user_id, aparelho, atualizado_em)
  values (token, auth.uid(), left(aparelho, 80), now())
  on conflict (token) do update set user_id = excluded.user_id, aparelho = excluded.aparelho, atualizado_em = now();
  return json_build_object('ok', true);
end; $$;
revoke all on function public.push_registrar(text, text) from public;
grant execute on function public.push_registrar(text, text) to anon, authenticated;

-- enfileira e dispara a função "push" na hora
create or replace function public.push_enviar(p_user uuid, p_admins boolean, p_topico text, p_nid int, p_titulo text, p_texto text)
returns void language plpgsql security definer set search_path to '' as $$
declare i bigint;
begin
  insert into public.push_fila(user_id, admins, topico, nid, titulo, texto)
  values (p_user, coalesce(p_admins, false), p_topico, coalesce(p_nid, 9210), left(p_titulo, 120), left(p_texto, 400))
  returning id into i;
  perform net.http_post(
    url := 'https://xzdqvdrwvozoedmjwwhm.supabase.co/functions/v1/push',
    body := jsonb_build_object('id', i),
    headers := '{"Content-Type":"application/json"}'::jsonb,
    timeout_milliseconds := 30000);
exception when others then null; -- push nunca pode travar a operação principal
end; $$;
revoke all on function public.push_enviar(uuid, boolean, text, int, text, text) from public, anon, authenticated;

-- suporte: novo chamado / cliente respondeu → administrador · equipe respondeu / finalizou → cliente
create or replace function public._push_suporte()
returns trigger language plpgsql security definer set search_path to '' as $$
begin
  if tg_op = 'INSERT' then
    if new.categoria = 'erro_auto' then
      perform public.push_enviar(null, true, null, 9291, '⚠️ Erro no app', left(new.assunto, 200));
    else
      perform public.push_enviar(null, true, null, 9290, '📩 Novo chamado de suporte', coalesce(split_part(new.nome,' ',1),'Cliente') || ': ' || left(new.assunto, 160));
    end if;
    return new;
  end if;
  if new.status is distinct from old.status or new.respondido_em is distinct from old.respondido_em then
    if new.status = 'respondido' and new.user_id is not null then
      perform public.push_enviar(new.user_id, false, null, 9300 + (new.id % 100)::int, '💬 O suporte respondeu', 'Seu chamado "' || left(new.assunto, 80) || '" tem uma resposta da equipe Prismo. Toque para ver.');
    elsif new.status = 'finalizado' and old.status <> 'finalizado' and new.user_id is not null then
      perform public.push_enviar(new.user_id, false, null, 9600 + (new.id % 100)::int, '✅ Chamado finalizado', 'O chamado "' || left(new.assunto, 80) || '" (nº ' || new.id || ') foi finalizado pela equipe Prismo.');
    elsif new.status = 'aguardando' and old.status in ('respondido','andamento') then
      perform public.push_enviar(null, true, null, 9290, '💬 Cliente respondeu', 'Chamado nº ' || new.id || ': ' || left(new.assunto, 120));
    end if;
  end if;
  return new;
end; $$;
drop trigger if exists suporte_push on public.suporte;
create trigger suporte_push after insert or update on public.suporte for each row execute function public._push_suporte();

-- anúncio do administrador que já começa hoje → todos do público na hora
create or replace function public._push_aviso()
returns trigger language plpgsql security definer set search_path to '' as $$
begin
  if new.ativo and new.inicio <= (now() at time zone 'America/Sao_Paulo')::date then
    perform public.push_enviar(null, false, new.publico, 9298, coalesce(nullif(new.titulo,''), 'Prismo Drive'), new.texto);
  end if;
  return new;
end; $$;
drop trigger if exists avisos_push on public.avisos;
create trigger avisos_push after insert on public.avisos for each row execute function public._push_aviso();

-- ══════════════ 2) Indique e ganhe protegido ══════════════
-- A indicação só conta quando o amigo: confirmou o e-mail, está em outro aparelho
-- e usou o app em 3 dias diferentes (ou assinou o Pro) dentro de 30 dias.
alter table public.perfis add column if not exists aparelhos text[] not null default '{}';
alter table public.indicacoes add column if not exists aparelho text;
alter table public.indicacoes add column if not exists dias_uso int not null default 0;
alter table public.indicacoes add column if not exists ultimo_uso date;
alter table public.indicacoes add column if not exists validada_em timestamptz;
alter table public.indicacoes add column if not exists cancelada text;
-- as que já existiam continuam valendo
update public.indicacoes set validada_em = criado_em where validada_em is null and cancelada is null and aparelho is null;
create index if not exists indicacoes_aparelho_idx on public.indicacoes(aparelho);

create or replace function public._indicacao_premiar(dono uuid)
returns int language plpgsql security definer set search_path to '' as $$
declare n int; m record; ganhou int := 0; d public.perfis;
begin
  select * into d from public.perfis where id = dono;
  if not found then return 0; end if;
  select count(*) into n from public.indicacoes where indicador = dono and validada_em is not null and cancelada is null;
  for m in select * from (values (10,3),(20,10),(30,15),(50,30)) v(q,dd) loop
    if n >= m.q and not exists (select 1 from public.indicacao_premios where user_id = dono and marco = m.q) then
      insert into public.indicacao_premios(user_id, marco, dias) values (dono, m.q, m.dd);
      update public.perfis set pro_ate = greatest(coalesce(pro_ate, now()), now()) + make_interval(days => m.dd),
             plano = case when plano = 'admin' then 'admin' else 'pro' end where id = dono;
      insert into public.admin_log(admin_id, acao, conta_id, detalhes) values (dono, 'premio_indicacao', d.conta_id, json_build_object('marco', m.q, 'dias', m.dd));
      perform public.push_enviar(dono, false, null, 9451, '🏆 Você ganhou ' || m.dd || ' dias de Pro!', m.q || ' amigos confirmaram seu código. Os dias já foram somados ao seu Pro.');
      ganhou := ganhou + m.dd;
    end if;
  end loop;
  return ganhou;
end; $$;
revoke all on function public._indicacao_premiar(uuid) from public, anon, authenticated;

create or replace function public._indicacao_validar(quem uuid)
returns boolean language plpgsql security definer set search_path to '' as $$
declare i public.indicacoes; conf timestamptz; pagou boolean; n int;
begin
  select * into i from public.indicacoes where indicado = quem and validada_em is null and cancelada is null for update;
  if not found then return false; end if;
  if i.criado_em < now() - interval '30 days' then
    update public.indicacoes set cancelada = 'prazo' where id = i.id; return false;
  end if;
  select email_confirmed_at into conf from auth.users where id = quem;
  if conf is null then return false; end if;
  pagou := exists (select 1 from public.pagamentos where user_id = quem and aplicado_em is not null and revertido_em is null);
  if i.dias_uso < 3 and not pagou then return false; end if;
  update public.indicacoes set validada_em = now() where id = i.id;
  select count(*) into n from public.indicacoes where indicador = i.indicador and validada_em is not null and cancelada is null;
  perform public.push_enviar(i.indicador, false, null, 9450, '🎉 Indicação confirmada!', 'Mais um amigo confirmou seu código. Agora são ' || n || ' indicações.');
  perform public._indicacao_premiar(i.indicador);
  return true;
end; $$;
revoke all on function public._indicacao_validar(uuid) from public, anon, authenticated;

-- chamado pelo app uma vez por dia: guarda o aparelho (impressão digital anônima) e conta o dia de uso
create or replace function public.app_ping(aparelho text default null)
returns json language plpgsql security definer set search_path to '' as $$
declare hoje date := (now() at time zone 'America/Sao_Paulo')::date; ap text;
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  ap := case when aparelho ~ '^[0-9a-f]{64}$' then aparelho end;
  if ap is not null then
    update public.perfis set aparelhos = (
      select coalesce(array_agg(s.x order by s.o), '{}')
        from (select t.x, t.o from unnest(array_append(array_remove(perfis.aparelhos, ap), ap)) with ordinality t(x, o) order by t.o desc limit 4) s)
     where id = auth.uid();
  end if;
  update public.indicacoes set dias_uso = dias_uso + 1, ultimo_uso = hoje
   where indicado = auth.uid() and validada_em is null and cancelada is null and ultimo_uso is distinct from hoje;
  perform public._indicacao_validar(auth.uid());
  return json_build_object('ok', true);
end; $$;
revoke all on function public.app_ping(text) from public, anon;
grant execute on function public.app_ping(text) to authenticated;

drop function if exists public.usar_codigo_indicacao(text);
create or replace function public.usar_codigo_indicacao(codigo text, aparelho text default null)
returns json language plpgsql security definer set search_path to '' as $$
declare eu public.perfis; dono public.perfis; conf timestamptz; ap text;
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  select * into eu from public.perfis where id = auth.uid();
  if not found then raise exception 'sem_conta'; end if;
  if exists (select 1 from public.indicacoes where indicado = auth.uid()) then raise exception 'ja_usou_codigo'; end if;
  if eu.criado_em < now() - interval '30 days' then raise exception 'prazo_codigo'; end if;
  select email_confirmed_at into conf from auth.users where id = auth.uid();
  if conf is null then raise exception 'email_nao_confirmado'; end if;
  ap := case when aparelho ~ '^[0-9a-f]{64}$' then aparelho end;
  if ap is null then raise exception 'atualize_app'; end if;
  select * into dono from public.perfis where codigo_indicacao = upper(trim(codigo));
  if not found then raise exception 'codigo_invalido'; end if;
  if dono.id = auth.uid() then raise exception 'proprio_codigo'; end if;
  if ap = any(dono.aparelhos) or dono.aparelhos && eu.aparelhos then raise exception 'mesmo_aparelho'; end if;
  if exists (select 1 from public.indicacoes where indicacoes.aparelho = ap) then raise exception 'aparelho_usado'; end if;
  if (select count(*) from public.indicacoes where indicador = dono.id and criado_em > now() - interval '1 day') >= 15 then raise exception 'limite_dia'; end if;
  update public.perfis set aparelhos = (array_append(array_remove(aparelhos, ap), ap)) where id = auth.uid();
  insert into public.indicacoes(indicador, indicado, codigo, aparelho, dias_uso, ultimo_uso)
  values (dono.id, auth.uid(), dono.codigo_indicacao, ap, 1, (now() at time zone 'America/Sao_Paulo')::date);
  perform public._indicacao_validar(auth.uid());
  return json_build_object('ok', true, 'indicador', split_part(coalesce(dono.nome,''),' ',1));
end; $$;
revoke all on function public.usar_codigo_indicacao(text, text) from public, anon;
grant execute on function public.usar_codigo_indicacao(text, text) to authenticated;

create or replace function public.minha_indicacao()
returns json language plpgsql security definer set search_path to '' as $$
declare cod text; n int; pend int; usado public.indicacoes; criado timestamptz;
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  cod := public._codigo_indicacao(auth.uid());
  select count(*) into n from public.indicacoes where indicador = auth.uid() and validada_em is not null and cancelada is null;
  select count(*) into pend from public.indicacoes where indicador = auth.uid() and validada_em is null and cancelada is null and criado_em > now() - interval '30 days';
  select * into usado from public.indicacoes where indicado = auth.uid();
  select criado_em into criado from public.perfis where id = auth.uid();
  return json_build_object('codigo', cod, 'total', n, 'pendentes', pend, 'usou', usado.codigo,
    'meu_status', case when usado.id is null then null when usado.cancelada is not null then 'cancelada' when usado.validada_em is not null then 'confirmada' else 'pendente' end,
    'meus_dias', coalesce(usado.dias_uso, 0),
    'pode_usar', usado.id is null and coalesce(criado, now()) > now() - interval '30 days',
    'marcos', (select json_agg(json_build_object('qtd', m.q, 'dias', m.d,
                  'ganho', exists(select 1 from public.indicacao_premios x where x.user_id = auth.uid() and x.marco = m.q)) order by m.q)
               from (values (10,3),(20,10),(30,15),(50,30)) m(q,d)));
end; $$;
revoke all on function public.minha_indicacao() from public, anon;
grant execute on function public.minha_indicacao() to authenticated;

-- pagamento aprovado: avisa cliente e administrador na hora e confirma a indicação de quem assinou
create or replace function public._push_pagamento()
returns trigger language plpgsql security definer set search_path to '' as $$
declare nm text; pl text;
begin
  pl := initcap(coalesce(new.plano, ''));
  if new.aplicado_em is not null and old.aplicado_em is null then
    select split_part(coalesce(nome,'Um cliente'),' ',1) into nm from public.perfis where id = new.user_id;
    if new.user_id is not null then
      perform public.push_enviar(new.user_id, false, null, 9296, '🎉 Pagamento aprovado!', 'Pro ' || pl || ' liberado: +' || new.dias || ' dias. Obrigado por assinar o Prismo Drive!');
      perform public._indicacao_validar(new.user_id);
    end if;
    perform public.push_enviar(null, true, null, 9295, '💰 Nova venda: Pro ' || pl || ' R$ ' || replace(to_char(new.valor, 'FM999990.00'), '.', ','), coalesce(nm, 'Um cliente') || ' acabou de pagar.');
  elsif new.revertido_em is not null and old.revertido_em is null then
    perform public.push_enviar(null, true, null, 9297, '↩️ Estorno: Pro ' || pl, 'Pagamento de R$ ' || replace(to_char(new.valor, 'FM999990.00'), '.', ',') || ' foi estornado e os dias foram retirados.');
  end if;
  return new;
end; $$;
drop trigger if exists pagamentos_push on public.pagamentos;
create trigger pagamentos_push after update on public.pagamentos for each row execute function public._push_pagamento();

-- ══════════════ 3) Relatório automático de erros ══════════════
create table if not exists public.erros_app (
  id bigint generated always as identity primary key,
  assinatura text not null,
  versao text not null,
  origem text,
  mensagem text,
  pilha text,
  tela text,
  aparelho text,
  primeiro_em timestamptz not null default now(),
  ultimo_em timestamptz not null default now(),
  ocorrencias int not null default 1,
  pessoas int not null default 1,
  chamado_id bigint references public.suporte(id) on delete set null,
  unique (assinatura, versao)
);
alter table public.erros_app enable row level security;
revoke all on public.erros_app from anon, authenticated;

-- usado só pela função "suporte" (chave de serviço)
create or replace function public.erro_registrar(p_assinatura text, p_versao text, p_origem text, p_mensagem text, p_pilha text, p_tela text, p_aparelho text, p_nova_pessoa boolean)
returns json language plpgsql security definer set search_path to '' as $$
declare e public.erros_app; novo boolean := false;
begin
  insert into public.erros_app(assinatura, versao, origem, mensagem, pilha, tela, aparelho)
  values (left(p_assinatura, 64), left(p_versao, 20), left(p_origem, 20), left(p_mensagem, 500), left(p_pilha, 4000), left(p_tela, 60), left(p_aparelho, 80))
  on conflict (assinatura, versao) do update
     set ocorrencias = public.erros_app.ocorrencias + 1, ultimo_em = now(),
         pessoas = public.erros_app.pessoas + case when p_nova_pessoa then 1 else 0 end
  returning * into e;
  novo := e.ocorrencias = 1;
  return json_build_object('id', e.id, 'novo', novo, 'ocorrencias', e.ocorrencias, 'chamado', e.chamado_id,
    'recentes', (select count(*) from public.erros_app where primeiro_em > now() - interval '1 hour'));
end; $$;
revoke all on function public.erro_registrar(text, text, text, text, text, text, text, boolean) from public, anon, authenticated;

-- ocorrências aparecem no chamado do administrador
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
    'ocorrencias', case when admin then (select json_build_object('vezes', e.ocorrencias, 'pessoas', e.pessoas, 'ultimo', e.ultimo_em) from public.erros_app e where e.chamado_id = c.id limit 1) end,
    'msgs', (select coalesce(json_agg(json_build_object('autor', m.autor, 'texto', m.texto, 'criado_em', m.criado_em) order by m.criado_em), '[]'::json)
             from public.suporte_msgs m where m.chamado_id = c.id)
  );
$$;
revoke all on function public._chamado_json(public.suporte, boolean) from public, anon, authenticated;

-- limpeza: fila de push com mais de 30 dias
create extension if not exists pg_cron;
select cron.unschedule('prismo-push-limpeza') where exists (select 1 from cron.job where jobname = 'prismo-push-limpeza');
select cron.schedule('prismo-push-limpeza', '30 7 * * *', $$delete from public.push_fila where criado_em < now() - interval '30 days'$$);
