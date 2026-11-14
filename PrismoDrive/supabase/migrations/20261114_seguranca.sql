-- v2.1 · Correções do teste de segurança (14/11/2026)
-- Rode inteiro no SQL Editor do Supabase. Pode rodar mais de uma vez sem problema.

-- IP de quem chamou (o Supabase repassa no cabeçalho). Guardamos só um resumo (md5), nunca o IP em texto.
create or replace function public._ip_resumo()
returns text language sql stable set search_path to '' as $$
  select nullif(md5(split_part(coalesce(
    (nullif(current_setting('request.headers', true), '')::json ->> 'x-forwarded-for'), ''), ',', 1)), md5(''));
$$;
revoke all on function public._ip_resumo() from public, anon, authenticated;

-- 1) Backup na conta: no máximo ~3 MB por pessoa (antes não tinha limite e dava para encher o banco)
alter table public.backups drop constraint if exists backups_tamanho_chk;
alter table public.backups add constraint backups_tamanho_chk check (pg_column_size(dados) < 3000000) not valid;

-- 2) Nome do perfil (a pessoa pode editar): até 80 letras
alter table public.perfis drop constraint if exists perfis_nome_tam_chk;
alter table public.perfis add constraint perfis_nome_tam_chk check (nome is null or char_length(nome) <= 80) not valid;

-- 3) Aparelhos para push sem conta: no máximo 300 novos por hora (evita encher a tabela)
create or replace function public.push_registrar(token text, aparelho text default null)
returns json language plpgsql security definer set search_path to '' as $$
begin
  if token is null or length(token) not between 20 and 500 then raise exception 'token_invalido'; end if;
  if auth.uid() is null and (select count(*) from public.push_tokens where user_id is null and atualizado_em > now() - interval '1 hour') >= 300 then
    raise exception 'limite';
  end if;
  insert into public.push_tokens(token, user_id, aparelho, atualizado_em)
  values (token, auth.uid(), left(aparelho, 80), now())
  on conflict (token) do update set user_id = excluded.user_id, aparelho = excluded.aparelho, atualizado_em = now();
  return json_build_object('ok', true);
end; $$;
revoke all on function public.push_registrar(text, text) from public;
grant execute on function public.push_registrar(text, text) to anon, authenticated;

-- 4) Teste grátis: além de 1 por celular, no máximo 2 por rede (IP) a cada 30 dias
--    (o identificador do celular vem do app e um robô poderia inventar vários)
alter table public.testes_pro add column if not exists ip text;
create index if not exists testes_pro_ip_idx on public.testes_pro(ip, criado_em);

create or replace function public.ativar_teste(aparelho text)
returns json language plpgsql security definer set search_path to '' as $$
declare eu public.perfis; conf timestamptz; ap text; ate timestamptz; meu_ip text := public._ip_resumo();
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  select * into eu from public.perfis where id = auth.uid() for update;
  if not found then return json_build_object('ok', false, 'motivo', 'sem_conta'); end if;
  if eu.teste_em is not null then return json_build_object('ok', false, 'motivo', 'ja_usou'); end if;
  if eu.plano = 'admin' or (eu.pro_ate is not null and eu.pro_ate > now()) then
    update public.perfis set teste_em = now() where id = eu.id;
    return json_build_object('ok', false, 'motivo', 'ja_pro');
  end if;
  if eu.criado_em < now() - interval '7 days' then
    update public.perfis set teste_em = now() where id = eu.id;
    return json_build_object('ok', false, 'motivo', 'prazo');
  end if;
  select email_confirmed_at into conf from auth.users where id = auth.uid();
  if conf is null then return json_build_object('ok', false, 'motivo', 'email'); end if;
  ap := case when aparelho ~ '^[0-9a-f]{64}$' then aparelho end;
  if ap is null then return json_build_object('ok', false, 'motivo', 'atualize_app'); end if;
  if exists (select 1 from public.testes_pro t where t.aparelho = ap)
     or exists (select 1 from public.perfis p where p.id <> eu.id and p.teste_em is not null and ap = any(p.aparelhos))
     or (meu_ip is not null and (select count(*) from public.testes_pro t where t.ip = meu_ip and t.criado_em > now() - interval '30 days') >= 2) then
    update public.perfis set teste_em = now(), aparelhos = array_append(array_remove(perfis.aparelhos, ap), ap) where id = eu.id;
    insert into public.admin_log(admin_id, acao, conta_id, detalhes) values (eu.id, 'teste_negado', eu.conta_id, json_build_object('motivo', 'aparelho_usado'));
    return json_build_object('ok', false, 'motivo', 'aparelho_usado');
  end if;
  insert into public.testes_pro(aparelho, user_id, ip) values (ap, eu.id, meu_ip);
  update public.perfis
     set teste_em = now(), plano = 'pro', pro_ate = now() + interval '3 days',
         aparelhos = array_append(array_remove(perfis.aparelhos, ap), ap)
   where id = eu.id
  returning pro_ate into ate;
  insert into public.admin_log(admin_id, acao, conta_id, detalhes) values (eu.id, 'teste_pro', eu.conta_id, json_build_object('dias', 3, 'pro_ate', ate));
  return json_build_object('ok', true, 'dias', 3, 'pro_ate', ate);
end; $$;
revoke all on function public.ativar_teste(text) from public, anon;
grant execute on function public.ativar_teste(text) to authenticated;

-- 5) Indique e ganhe: no máximo 3 códigos usados por rede (IP) a cada 7 dias
alter table public.indicacoes add column if not exists ip text;
create index if not exists indicacoes_ip_idx on public.indicacoes(ip, criado_em);

create or replace function public.usar_codigo_indicacao(codigo text, aparelho text default null)
returns json language plpgsql security definer set search_path to '' as $$
declare eu public.perfis; dono public.perfis; conf timestamptz; ap text; meu_ip text := public._ip_resumo();
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
  if meu_ip is not null and (select count(*) from public.indicacoes i where i.ip = meu_ip and i.criado_em > now() - interval '7 days') >= 3 then
    raise exception 'aparelho_usado';
  end if;
  if (select count(*) from public.indicacoes where indicador = dono.id and criado_em > now() - interval '1 day') >= 15 then raise exception 'limite_dia'; end if;
  update public.perfis set aparelhos = (array_append(array_remove(aparelhos, ap), ap)) where id = auth.uid();
  insert into public.indicacoes(indicador, indicado, codigo, aparelho, dias_uso, ultimo_uso, ip)
  values (dono.id, auth.uid(), dono.codigo_indicacao, ap, 1, (now() at time zone 'America/Sao_Paulo')::date, meu_ip);
  perform public._indicacao_validar(auth.uid());
  return json_build_object('ok', true, 'indicador', split_part(coalesce(dono.nome,''),' ',1));
end; $$;
revoke all on function public.usar_codigo_indicacao(text, text) from public, anon;
grant execute on function public.usar_codigo_indicacao(text, text) to authenticated;

-- 6) Suporte: resumo do IP para limitar mensagens e relatórios de erro sem conta
alter table public.suporte add column if not exists ip text;
create index if not exists suporte_ip_idx on public.suporte(ip, criado_em);

-- 7) Relatório de erros: no máximo 300 tipos novos de erro por hora (evita encher o banco)
create or replace function public.erro_registrar(p_assinatura text, p_versao text, p_origem text, p_mensagem text, p_pilha text, p_tela text, p_aparelho text, p_nova_pessoa boolean)
returns json language plpgsql security definer set search_path to '' as $$
declare e public.erros_app; novo boolean := false;
begin
  if not exists (select 1 from public.erros_app where assinatura = left(p_assinatura, 64) and versao = left(p_versao, 20))
     and (select count(*) from public.erros_app where primeiro_em > now() - interval '1 hour') >= 300 then
    return json_build_object('id', null, 'novo', false, 'ocorrencias', 0, 'chamado', null, 'recentes', 999);
  end if;
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

-- limpeza diária: erros que aconteceram uma vez só e nunca viraram chamado, depois de 30 dias
select cron.unschedule('prismo-erros-limpeza') where exists (select 1 from cron.job where jobname = 'prismo-erros-limpeza');
select cron.schedule('prismo-erros-limpeza', '40 7 * * *', $$delete from public.erros_app where chamado_id is null and ocorrencias = 1 and ultimo_em < now() - interval '30 days'$$);

-- 8) Sem TRUNCATE para quem usa o app (a API não usa, mas não precisa ter)
do $$ declare t text; begin
  for t in select tablename from pg_tables where schemaname = 'public' loop
    execute format('revoke truncate, trigger, references on public.%I from anon, authenticated', t);
  end loop;
end $$;
