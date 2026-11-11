-- Pagamentos do Pro pelo Mercado Pago (v1.9.8)
create table if not exists public.pagamentos (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  conta_id text,
  plano text not null check (plano in ('mensal','trimestral','semestral','anual')),
  dias int not null check (dias > 0),
  valor numeric(10,2) not null,
  status text not null default 'criado',
  mp_preference_id text,
  mp_payment_id text unique,
  aplicado_em timestamptz,
  revertido_em timestamptz,
  pro_ate_depois timestamptz,
  detalhe jsonb,
  criado_em timestamptz not null default now(),
  atualizado_em timestamptz not null default now()
);
create index if not exists pagamentos_user_idx on public.pagamentos(user_id, criado_em desc);
alter table public.pagamentos enable row level security;
drop policy if exists "ver meus pagamentos" on public.pagamentos;
create policy "ver meus pagamentos" on public.pagamentos for select to authenticated using (user_id = (select auth.uid()));
revoke insert, update, delete on public.pagamentos from anon, authenticated;

-- Chamado só pelo servidor (webhook) com a chave de serviço
create or replace function public.mp_registrar(p_pedido uuid, p_payment_id text, p_status text, p_valor numeric, p_detalhe jsonb)
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
  r public.pagamentos%rowtype;
  novo public.pagamentos%rowtype;
  ate timestamptz;
begin
  select * into r from public.pagamentos where id = p_pedido for update;
  if not found then return 'pedido-desconhecido'; end if;

  -- mesmo link pago de novo (outro pagamento aprovado): vira um pedido novo
  if r.mp_payment_id is not null and r.mp_payment_id <> p_payment_id then
    select * into novo from public.pagamentos where mp_payment_id = p_payment_id for update;
    if found then
      r := novo;
    elsif r.aplicado_em is not null then
      insert into public.pagamentos(user_id, conta_id, plano, dias, valor, status, mp_preference_id, mp_payment_id)
      values (r.user_id, r.conta_id, r.plano, r.dias, r.valor, 'criado', r.mp_preference_id, p_payment_id)
      returning * into r;
    end if;
  end if;

  update public.pagamentos
     set status = p_status, mp_payment_id = p_payment_id, detalhe = p_detalhe, atualizado_em = now()
   where id = r.id;

  if p_status = 'approved' and r.aplicado_em is null then
    if abs(coalesce(p_valor,0) - r.valor) > 0.01 then
      update public.pagamentos set status = 'valor_divergente' where id = r.id;
      return 'valor-divergente';
    end if;
    update public.perfis
       set pro_ate = greatest(coalesce(pro_ate, now()), now()) + make_interval(days => r.dias),
           plano = case when plano = 'admin' then 'admin' else 'pro' end
     where id = r.user_id
     returning pro_ate into ate;
    update public.pagamentos set aplicado_em = now(), pro_ate_depois = ate where id = r.id;
    insert into public.admin_log(admin_id, acao, conta_id, detalhes)
      values (r.user_id, 'pagamento_aprovado', r.conta_id, jsonb_build_object('plano', r.plano, 'dias', r.dias, 'valor', r.valor, 'mp_payment_id', p_payment_id, 'pro_ate', ate));
    return 'aplicado';
  end if;

  if p_status in ('refunded','charged_back','cancelled') and r.aplicado_em is not null and r.revertido_em is null then
    update public.perfis
       set pro_ate = pro_ate - make_interval(days => r.dias)
     where id = r.user_id
     returning pro_ate into ate;
    update public.pagamentos set revertido_em = now() where id = r.id;
    insert into public.admin_log(admin_id, acao, conta_id, detalhes)
      values (r.user_id, 'pagamento_revertido', r.conta_id, jsonb_build_object('plano', r.plano, 'dias', r.dias, 'status', p_status, 'mp_payment_id', p_payment_id, 'pro_ate', ate));
    return 'revertido';
  end if;

  return 'registrado';
end;
$$;
revoke all on function public.mp_registrar(uuid, text, text, numeric, jsonb) from public, anon, authenticated;
grant execute on function public.mp_registrar(uuid, text, text, numeric, jsonb) to service_role;
