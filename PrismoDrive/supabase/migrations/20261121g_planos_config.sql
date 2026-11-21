-- ═══════════════════════════════════════════════════════════════════════════
-- Prismo Drive · Preços dos planos Pro num lugar só (editados no Prismo Admin)
-- Rodar uma vez no SQL Editor do Supabase (pode rodar de novo sem problema: não apaga preços já editados).
--
-- • O app (tela de Plano e Termos), o site e a cobrança do Mercado Pago (função mp-criar) leem daqui.
-- • Só administrador muda (conferido no servidor); toda mudança vai para o Histórico de ações.
-- • O preço nunca vem do celular: o app só diz qual plano escolheu.
-- ═══════════════════════════════════════════════════════════════════════════

create table if not exists public.planos_config (
  id text primary key check (id in ('mensal','trimestral','semestral','anual')),
  nome text not null,
  dias int not null check (dias between 1 and 3660),
  meses int not null check (meses between 1 and 120),
  preco numeric(10,2) not null check (preco between 5 and 1000),
  parcelas int not null default 1 check (parcelas between 1 and 12),
  selo text check (selo is null or length(selo) <= 30),
  ativo boolean not null default true,
  ordem int not null default 0,
  atualizado_em timestamptz not null default now(),
  atualizado_por uuid
);
insert into public.planos_config(id, nome, dias, meses, preco, parcelas, selo, ordem) values
  ('mensal',     'Mensal',      30,  1,  24.90, 1, null,            1),
  ('trimestral', 'Trimestral',  90,  3,  63.90, 1, null,            2),
  ('semestral',  'Semestral',  180,  6,  89.90, 3, null,            3),
  ('anual',      'Anual',      365, 12, 139.90, 3, 'Melhor oferta', 4)
on conflict (id) do nothing;
alter table public.planos_config enable row level security;
revoke all on public.planos_config from anon, authenticated;

-- app e site: os planos na ordem (com "ativo")
create or replace function public.planos_app()
returns json language sql stable security definer set search_path to '' as $$
  select coalesce(json_agg(json_build_object('id', p.id, 'nome', p.nome, 'dias', p.dias, 'meses', p.meses, 'preco', p.preco,
           'parcelas', p.parcelas, 'selo', p.selo, 'ativo', p.ativo) order by p.ordem, p.id), '[]'::json)
    from public.planos_config p;   -- o desligado também vem (o app esconde; o mensal serve de base para o desconto)
$$;
revoke all on function public.planos_app() from public;
grant execute on function public.planos_app() to anon, authenticated;

-- Prismo Admin
create or replace function public.admin_planos()
returns json language plpgsql stable security definer set search_path to '' as $$
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return (select coalesce(json_agg(row_to_json(p) order by p.ordem, p.id), '[]'::json) from public.planos_config p);
end; $$;
revoke all on function public.admin_planos() from public, anon;
grant execute on function public.admin_planos() to authenticated;

create or replace function public.admin_planos_salvar(p_planos jsonb)
returns json language plpgsql security definer set search_path to '' as $$
declare e jsonb; antes jsonb; n int := 0; v numeric;
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  if jsonb_typeof(p_planos) <> 'array' then raise exception 'dados_invalidos'; end if;
  select coalesce(jsonb_agg(row_to_json(p)), '[]'::jsonb) into antes from public.planos_config p;
  for e in select * from jsonb_array_elements(p_planos) loop
    v := round((e->>'preco')::numeric, 2);
    if v is null or v < 5 or v > 1000 then raise exception 'preco_fora_do_limite'; end if;
    update public.planos_config set
      preco = v,
      dias = coalesce(nullif(e->>'dias','')::int, dias),
      parcelas = least(greatest(coalesce(nullif(e->>'parcelas','')::int, parcelas), 1), 12),
      selo = nullif(left(trim(coalesce(e->>'selo','')), 30), ''),
      ativo = coalesce((e->>'ativo')::boolean, ativo),
      atualizado_em = now(), atualizado_por = auth.uid()
     where id = e->>'id';
    n := n + found::int;
  end loop;
  if not exists (select 1 from public.planos_config where ativo) then raise exception 'precisa_um_plano'; end if;
  insert into public.admin_log(admin_id, acao, conta_id, detalhes)
  values (auth.uid(), 'planos', null, jsonb_build_object('antes', (select jsonb_object_agg(x->>'id', x->'preco') from jsonb_array_elements(antes) x),
                                                         'depois', (select jsonb_object_agg(id, preco) from public.planos_config)));
  return json_build_object('ok', true, 'alterados', n, 'planos', public.admin_planos());
end; $$;
revoke all on function public.admin_planos_salvar(jsonb) from public, anon;
grant execute on function public.admin_planos_salvar(jsonb) to authenticated;
