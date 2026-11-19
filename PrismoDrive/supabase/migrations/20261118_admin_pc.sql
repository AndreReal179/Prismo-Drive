-- v2.1.8 · Prismo Drive Admin (programa para Windows): resumo do painel, ficha da conta e erros do app.
-- Rode inteiro no SQL Editor do Supabase. Pode rodar mais de uma vez. Só o administrador consegue usar.

-- Painel: números do dia e faturamento dos últimos 30 dias
create or replace function public.admin_resumo()
returns json language plpgsql stable security definer set search_path to '' as $$
declare hoje timestamptz; ontem timestamptz; semana timestamptz;
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  hoje   := date_trunc('day', now() at time zone 'America/Sao_Paulo') at time zone 'America/Sao_Paulo';
  ontem  := hoje - interval '1 day';
  semana := now() - interval '7 days';
  return json_build_object(
    'contas',        (select count(*) from public.perfis),
    'contas_hoje',   (select count(*) from public.perfis where criado_em >= hoje),
    'pro_ativos',    (select count(*) from public.perfis where plano = 'pro' and pro_ate > now()),
    'em_teste',      (select count(*) from public.perfis p where p.plano = 'pro' and p.pro_ate > now() and p.teste_em > now() - interval '3 days'
                        and not exists (select 1 from public.pagamentos g where g.user_id = p.id and g.aplicado_em is not null)),
    'pagantes_semana', (select count(distinct user_id) from public.pagamentos where aplicado_em >= semana and revertido_em is null),
    'vendas_hoje',   (select json_build_object('qtd', count(*), 'total', coalesce(sum(valor),0)) from public.pagamentos where aplicado_em >= hoje and revertido_em is null),
    'vendas_ontem',  (select json_build_object('qtd', count(*), 'total', coalesce(sum(valor),0)) from public.pagamentos where aplicado_em >= ontem and aplicado_em < hoje and revertido_em is null),
    'suporte',       (select json_build_object(
                        'aguardando', count(*) filter (where status = 'aguardando'),
                        'andamento',  count(*) filter (where status in ('andamento','respondido')),
                        'finalizados', count(*) filter (where status = 'finalizado')) from public.suporte),
    'erros_24h',     (select count(*) from public.erros_app where ultimo_em > now() - interval '1 day'),
    'dias', (select coalesce(json_agg(json_build_object('dia', d.dia, 'qtd', coalesce(v.qtd,0), 'total', coalesce(v.total,0)) order by d.dia), '[]'::json)
               from (select generate_series((hoje - interval '29 days')::date, hoje::date, interval '1 day')::date as dia) d
               left join (select (aplicado_em at time zone 'America/Sao_Paulo')::date as dia, count(*) qtd, sum(valor) total
                            from public.pagamentos where aplicado_em >= hoje - interval '29 days' and revertido_em is null group by 1) v on v.dia = d.dia)
  );
end; $$;
revoke all on function public.admin_resumo() from public, anon;
grant execute on function public.admin_resumo() to authenticated;

-- Ficha da conta: perfil, pagamentos, indicações e chamados
create or replace function public.admin_conta(conta text)
returns json language plpgsql stable security definer set search_path to '' as $$
declare p public.perfis;
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  select * into p from public.perfis where conta_id = conta;
  if not found then raise exception 'conta_nao_encontrada'; end if;
  return json_build_object(
    'conta_id', p.conta_id, 'nome', p.nome, 'email', p.email, 'plano', p.plano, 'pro_ate', p.pro_ate,
    'criado_em', p.criado_em, 'teste_em', p.teste_em, 'aparelhos', coalesce(array_length(p.aparelhos, 1), 0),
    'termos_versao', p.termos_versao, 'codigo_indicacao', p.codigo_indicacao,
    'pago_total', (select coalesce(sum(valor),0) from public.pagamentos where user_id = p.id and aplicado_em is not null and revertido_em is null),
    'pagamentos', (select coalesce(json_agg(x order by x.criado_em desc), '[]'::json) from (
        select criado_em, aplicado_em, revertido_em, plano, dias, valor, status, detalhe->>'metodo' as metodo
          from public.pagamentos where user_id = p.id and status not in ('criado','erro_mp') order by criado_em desc limit 30) x),
    'indicacoes', (select count(*) from public.indicacoes where indicador = p.id and cancelada is null),
    'indicacoes_ok', (select count(*) from public.indicacoes where indicador = p.id and validada_em is not null and cancelada is null),
    'chamados', (select count(*) from public.suporte where user_id = p.id)
  );
end; $$;
revoke all on function public.admin_conta(text) from public, anon;
grant execute on function public.admin_conta(text) to authenticated;

-- Erros do app (relatório automático)
create or replace function public.admin_erros(dias integer default 30)
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return (select coalesce(json_agg(x order by x.ultimo_em desc), '[]'::json) from (
    select id, versao, origem, left(mensagem, 300) as mensagem, left(pilha, 1500) as pilha, tela, aparelho,
           primeiro_em, ultimo_em, ocorrencias, pessoas, chamado_id
      from public.erros_app
     where ultimo_em > now() - make_interval(days => greatest(1, least(coalesce(dias,30), 120)))
     order by ultimo_em desc limit 150) x);
end; $$;
revoke all on function public.admin_erros(integer) from public, anon;
grant execute on function public.admin_erros(integer) to authenticated;
