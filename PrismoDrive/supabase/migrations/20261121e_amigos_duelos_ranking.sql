-- ═══════════════════════════════════════════════════════════════════════════
-- Prismo Drive · Amigos, Duelos de 7 dias e Ranqueada mundial
-- Rodar uma vez no SQL Editor do Supabase (pode rodar de novo sem problema).
--
-- • Amigos: pedido pelo ID de 8 números; vira amigo quando o outro aceita.
-- • Perfil público: nome de exibição e foto (pasta pública "avatares"), rotas, conquistas e colocação.
-- • Duelo: 7 dias a partir do aceite. Conta só as rotas automáticas do assistente de rotas + gorjetas.
--   O app envia o total de cada dia SÓ enquanto o duelo está ativo; o outro vê apenas a porcentagem.
-- • Ranqueada: temporada mensal, rotas automáticas do iFood/InDrive. Prêmios e pausa definidos no Prismo Admin.
-- • Missões: tipo novo "duelos" (duelos vencidos) e prêmio em dias de Pro ao concluir.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── 1. perfil público e rotas ───────────────────────────────────────────────
alter table public.perfis add column if not exists apelido text check (apelido is null or length(apelido) <= 40);
alter table public.perfis add column if not exists foto_url text check (foto_url is null or length(foto_url) <= 300);
alter table public.rotas_reg add column if not exists manual boolean not null default false;
create index if not exists rotas_reg_dia_idx on public.rotas_reg(dia, user_id);

-- rota_registrar ganha "manual" (rota parada à mão não conta na ranqueada)
drop function if exists public.rota_registrar(text, text, numeric, bigint);
create or replace function public.rota_registrar(p_rid text, p_app text, p_km numeric, p_ts bigint, p_manual boolean default false)
returns json language plpgsql security definer set search_path to '' as $$
declare d date;
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  if p_rid is null or length(p_rid) > 80 then return json_build_object('ok', false); end if;
  d := (to_timestamp(coalesce(p_ts, extract(epoch from now())*1000) / 1000.0) at time zone 'America/Sao_Paulo')::date;
  if d > (now() at time zone 'America/Sao_Paulo')::date + 1 or d < (now() at time zone 'America/Sao_Paulo')::date - 400 then
    return json_build_object('ok', false);
  end if;
  insert into public.rotas_reg(user_id, rid, dia, app, km, manual)
  values (auth.uid(), p_rid, d, case when p_app in ('ifood','indrive','turno') then p_app else 'ifood' end, least(greatest(coalesce(p_km,0),0),2000), coalesce(p_manual,false))
  on conflict (user_id, rid) do update set km = excluded.km, app = excluded.app, manual = excluded.manual;
  insert into public.presenca_dia(user_id, dia, em_rota) values (auth.uid(), d, true)
  on conflict (user_id, dia) do update set em_rota = true;
  return json_build_object('ok', true);
end; $$;
revoke all on function public.rota_registrar(text, text, numeric, bigint, boolean) from public, anon;
grant execute on function public.rota_registrar(text, text, numeric, bigint, boolean) to authenticated;

-- fotos de perfil: pasta pública "avatares", cada um só mexe na própria foto (<id da conta>.webp)
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('avatares', 'avatares', true, 524288, array['image/webp','image/jpeg','image/png'])
on conflict (id) do update set public = true, file_size_limit = 524288, allowed_mime_types = array['image/webp','image/jpeg','image/png'];
drop policy if exists "avatares: todos veem" on storage.objects;
create policy "avatares: todos veem" on storage.objects for select to anon, authenticated using (bucket_id = 'avatares');
drop policy if exists "avatares: dono envia" on storage.objects;
create policy "avatares: dono envia" on storage.objects for insert to authenticated with check (bucket_id = 'avatares' and name = auth.uid()::text || '.webp');
drop policy if exists "avatares: dono troca" on storage.objects;
create policy "avatares: dono troca" on storage.objects for update to authenticated using (bucket_id = 'avatares' and name = auth.uid()::text || '.webp') with check (bucket_id = 'avatares' and name = auth.uid()::text || '.webp');
drop policy if exists "avatares: dono apaga" on storage.objects;
create policy "avatares: dono apaga" on storage.objects for delete to authenticated using (bucket_id = 'avatares' and name = auth.uid()::text || '.webp');

-- o app publica o nome de exibição e a foto
create or replace function public.perfil_publicar(p_apelido text, p_foto text)
returns json language plpgsql security definer set search_path to '' as $$
declare f text := nullif(trim(coalesce(p_foto,'')),'');
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  if f is not null and f !~ ('^https://xzdqvdrwvozoedmjwwhm\.supabase\.co/storage/v1/object/public/avatares/' || auth.uid()::text || '\.webp(\?v=\d+)?$') then f := null; end if;
  update public.perfis set apelido = nullif(left(trim(coalesce(p_apelido,'')), 40), ''), foto_url = f where id = auth.uid();
  return json_build_object('ok', true);
end; $$;
revoke all on function public.perfil_publicar(text, text) from public, anon;
grant execute on function public.perfil_publicar(text, text) to authenticated;

-- nome que os outros veem: o nome de exibição, ou o primeiro nome da conta
create or replace function public._nome_pub(p public.perfis)
returns text language sql immutable set search_path to '' as $$
  select coalesce(nullif(trim(p.apelido),''), nullif(split_part(trim(coalesce(p.nome,'')),' ',1),''), 'Motorista');
$$;
revoke all on function public._nome_pub(public.perfis) from public, anon, authenticated;

create or replace function public._hoje() returns date language sql stable set search_path to '' as $$
  select (now() at time zone 'America/Sao_Paulo')::date;
$$;

-- ── 2. amizades ─────────────────────────────────────────────────────────────
create table if not exists public.amizades (
  a uuid not null references auth.users(id) on delete cascade,   -- sempre o menor id
  b uuid not null references auth.users(id) on delete cascade,
  de uuid not null references auth.users(id) on delete cascade,  -- quem pediu
  status text not null default 'pendente' check (status in ('pendente','aceita')),
  criado_em timestamptz not null default now(),
  aceito_em timestamptz,
  primary key (a, b),
  check (a < b)
);
create index if not exists amizades_b_idx on public.amizades(b);
alter table public.amizades enable row level security;
revoke all on public.amizades from anon, authenticated;

create or replace function public._amigos_de(p uuid)
returns table(id uuid) language sql stable security definer set search_path to '' as $$
  select case when z.a = p then z.b else z.a end from public.amizades z where (z.a = p or z.b = p) and z.status = 'aceita';
$$;
revoke all on function public._amigos_de(uuid) from public, anon, authenticated;

create or replace function public._relacao(eu uuid, outro uuid)
returns text language sql stable security definer set search_path to '' as $$
  select case when eu = outro then 'eu' else coalesce((
    select case when z.status = 'aceita' then 'amigo' when z.de = eu then 'enviado' else 'recebido' end
      from public.amizades z where z.a = least(eu, outro) and z.b = greatest(eu, outro)), 'nada') end;
$$;
revoke all on function public._relacao(uuid, uuid) from public, anon, authenticated;

-- ── 3. duelos ───────────────────────────────────────────────────────────────
create table if not exists public.duelos (
  id bigint generated always as identity primary key,
  desafiante uuid not null references auth.users(id) on delete cascade,
  desafiado uuid not null references auth.users(id) on delete cascade,
  status text not null default 'convite' check (status in ('convite','ativo','fim','recusado','cancelado')),
  inicio date, fim date,
  vencedor uuid,                       -- null com status fim = empate
  pct numeric,                         -- quanto % o vencedor ficou à frente
  criado_em timestamptz not null default now(),
  aceito_em timestamptz, fechado_em timestamptz,
  check (desafiante <> desafiado)
);
create unique index if not exists duelos_aberto_par on public.duelos (least(desafiante, desafiado), greatest(desafiante, desafiado)) where status in ('convite','ativo');
create index if not exists duelos_desafiado_idx on public.duelos(desafiado);
create index if not exists duelos_vencedor_idx on public.duelos(vencedor) where status = 'fim';
alter table public.duelos enable row level security;
revoke all on public.duelos from anon, authenticated;

create table if not exists public.duelo_dias (
  duelo_id bigint not null references public.duelos(id) on delete cascade,
  user_id uuid not null,
  dia date not null,
  valor numeric(10,2) not null default 0,
  atualizado_em timestamptz not null default now(),
  primary key (duelo_id, user_id, dia)
);
alter table public.duelo_dias enable row level security;
revoke all on public.duelo_dias from anon, authenticated;

-- ── 4. ranqueada ────────────────────────────────────────────────────────────
create table if not exists public.ranking_config (
  id int primary key default 1 check (id = 1),
  pausado boolean not null default false,
  premios jsonb not null default '[{"pos":1,"texto":"30 dias de Pro","dias":30},{"pos":2,"texto":"15 dias de Pro","dias":15},{"pos":3,"texto":"7 dias de Pro","dias":7}]'::jsonb,
  atualizado_em timestamptz not null default now()
);
insert into public.ranking_config(id) values (1) on conflict do nothing;
alter table public.ranking_config enable row level security;
revoke all on public.ranking_config from anon, authenticated;

create table if not exists public.ranking_temporadas (
  mes date primary key,                -- 1º dia do mês
  fechado_em timestamptz not null default now(),
  participantes int not null default 0,
  top jsonb not null default '[]'::jsonb,
  premios jsonb not null default '[]'::jsonb
);
alter table public.ranking_temporadas enable row level security;
revoke all on public.ranking_temporadas from anon, authenticated;

-- colocação do mês (rotas automáticas do iFood/InDrive) até um dia; p_grupo limita a um grupo (amigos)
create or replace function public._ranking(p_mes date, p_ate date, p_grupo uuid[] default null)
returns table(user_id uuid, rotas bigint, pos bigint) language sql stable security definer set search_path to '' as $$
  with c as (
    select r.user_id, count(*) as rotas, max(r.criado_em) as ultima
      from public.rotas_reg r
     where r.dia >= p_mes and r.dia < (p_mes + interval '1 month')::date and r.dia <= p_ate
       and r.app in ('ifood','indrive') and not r.manual
       and (p_grupo is null or r.user_id = any(p_grupo))
     group by r.user_id
  )
  select c.user_id, c.rotas, rank() over (order by c.rotas desc) from c;
$$;
revoke all on function public._ranking(date, date, uuid[]) from public, anon, authenticated;

create or replace function public._mes_atual() returns date language sql stable set search_path to '' as $$
  select date_trunc('month', public._hoje())::date;
$$;

-- colocação de uma pessoa (para o cartão do Perfil)
create or replace function public._rank_de(p uuid)
returns json language plpgsql stable security definer set search_path to '' as $$
declare m date := public._mes_atual(); h date := public._hoje(); r record; ant bigint; tot bigint;
begin
  if (select pausado from public.ranking_config where id = 1) then return json_build_object('pausado', true); end if;
  select count(*) into tot from public._ranking(m, h);
  select * into r from public._ranking(m, h) x where x.user_id = p;
  if h - 7 >= m then select x.pos into ant from public._ranking(m, h - 7) x where x.user_id = p; end if;
  return json_build_object('pausado', false, 'total', tot, 'pos', r.pos, 'rotas', coalesce(r.rotas, 0),
    'subiu', case when r.pos is not null and ant is not null then ant - r.pos end);
end; $$;
revoke all on function public._rank_de(uuid) from public, anon, authenticated;

create or replace function public._pessoa(p public.perfis)
returns json language sql stable security definer set search_path to '' as $$
  select json_build_object('conta_id', p.conta_id, 'nome', public._nome_pub(p), 'foto', p.foto_url,
    'pro', (p.plano = 'admin' or (p.plano = 'pro' and p.pro_ate > now())));
$$;
revoke all on function public._pessoa(public.perfis) from public, anon, authenticated;

-- ── 5. RPCs de amigos ───────────────────────────────────────────────────────
create or replace function public.amigo_buscar(p_conta text)
returns json language plpgsql stable security definer set search_path to '' as $$
declare p public.perfis;
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  select * into p from public.perfis where conta_id = regexp_replace(coalesce(p_conta,''), '\D', '', 'g');
  if not found then return json_build_object('achou', false); end if;
  return json_build_object('achou', true, 'pessoa', public._pessoa(p), 'relacao', public._relacao(auth.uid(), p.id),
    'conquistas', (select count(*) from public.insignias_usuario i where i.user_id = p.id),
    'rotas', (select count(*) from public.rotas_reg r where r.user_id = p.id and r.app in ('ifood','indrive')));
end; $$;
revoke all on function public.amigo_buscar(text) from public, anon;
grant execute on function public.amigo_buscar(text) to authenticated;

create or replace function public.amigo_pedir(p_conta text)
returns json language plpgsql security definer set search_path to '' as $$
declare eu uuid := auth.uid(); p public.perfis; rel text; n int;
begin
  if eu is null then raise exception 'sem_sessao'; end if;
  select * into p from public.perfis where conta_id = regexp_replace(coalesce(p_conta,''), '\D', '', 'g');
  if not found then raise exception 'conta_nao_encontrada'; end if;
  rel := public._relacao(eu, p.id);
  if rel = 'eu' then raise exception 'voce_mesmo'; end if;
  if rel in ('amigo','enviado') then return json_build_object('ok', true, 'relacao', rel); end if;
  if rel = 'recebido' then   -- a outra pessoa já tinha pedido: vira amizade
    update public.amizades set status = 'aceita', aceito_em = now() where a = least(eu, p.id) and b = greatest(eu, p.id);
    perform public.push_enviar(p.id, false, null, 9700 + (abs(hashtext(eu::text)) % 100)::int, '🤝 Pedido aceito',
      (select public._nome_pub(x) from public.perfis x where x.id = eu) || ' agora é seu amigo no Prismo Drive.');
    return json_build_object('ok', true, 'relacao', 'amigo');
  end if;
  select count(*) into n from public.amizades where de = eu and status = 'pendente' and criado_em > now() - interval '1 day';
  if n >= 30 then raise exception 'muitos_pedidos'; end if;
  insert into public.amizades(a, b, de) values (least(eu, p.id), greatest(eu, p.id), eu);
  perform public.push_enviar(p.id, false, null, 9700 + (abs(hashtext(eu::text)) % 100)::int, '👋 Pedido de amizade',
    (select public._nome_pub(x) from public.perfis x where x.id = eu) || ' quer ser seu amigo no Prismo Drive. Toque para ver.');
  return json_build_object('ok', true, 'relacao', 'enviado');
end; $$;
revoke all on function public.amigo_pedir(text) from public, anon;
grant execute on function public.amigo_pedir(text) to authenticated;

create or replace function public.amigo_responder(p_conta text, p_aceitar boolean)
returns json language plpgsql security definer set search_path to '' as $$
declare eu uuid := auth.uid(); o uuid;
begin
  if eu is null then raise exception 'sem_sessao'; end if;
  select id into o from public.perfis where conta_id = p_conta;
  if o is null or public._relacao(eu, o) <> 'recebido' then raise exception 'sem_pedido'; end if;
  if p_aceitar then
    update public.amizades set status = 'aceita', aceito_em = now() where a = least(eu, o) and b = greatest(eu, o);
    perform public.push_enviar(o, false, null, 9700 + (abs(hashtext(eu::text)) % 100)::int, '🤝 Pedido aceito',
      (select public._nome_pub(x) from public.perfis x where x.id = eu) || ' agora é seu amigo no Prismo Drive.');
  else
    delete from public.amizades where a = least(eu, o) and b = greatest(eu, o);
  end if;
  return json_build_object('ok', true);
end; $$;
revoke all on function public.amigo_responder(text, boolean) from public, anon;
grant execute on function public.amigo_responder(text, boolean) to authenticated;

create or replace function public.amigo_remover(p_conta text)
returns json language plpgsql security definer set search_path to '' as $$
declare eu uuid := auth.uid(); o uuid;
begin
  if eu is null then raise exception 'sem_sessao'; end if;
  select id into o from public.perfis where conta_id = p_conta;
  if o is null then return json_build_object('ok', true); end if;
  delete from public.amizades where a = least(eu, o) and b = greatest(eu, o);
  update public.duelos set status = 'cancelado', fechado_em = now()
   where status in ('convite','ativo') and least(desafiante, desafiado) = least(eu, o) and greatest(desafiante, desafiado) = greatest(eu, o);
  return json_build_object('ok', true);
end; $$;
revoke all on function public.amigo_remover(text) from public, anon;
grant execute on function public.amigo_remover(text) to authenticated;

-- resumo de um duelo do ponto de vista de "eu" (só porcentagens, nunca valores)
create or replace function public._duelo_json(d public.duelos, eu uuid)
returns json language plpgsql stable security definer set search_path to '' as $$
declare o uuid := case when d.desafiante = eu then d.desafiado else d.desafiante end;
  h date := public._hoje(); dias json; me numeric; ele numeric; pe int := 0; po int := 0; k int; dd date; a numeric; b numeric;
  me_h numeric; ele_h numeric; arr json[] := '{}';
begin
  if d.status in ('ativo','fim') then
    for k in 0..6 loop
      dd := d.inicio + k;
      select coalesce(sum(valor) filter (where user_id = eu), 0), coalesce(sum(valor) filter (where user_id = o), 0) into a, b
        from public.duelo_dias where duelo_id = d.id and dia = dd;
      if dd < h or d.status = 'fim' then
        if a > b then pe := pe + 1; elsif b > a then po := po + 1; end if;
        arr := arr || json_build_object('dia', dd, 'r', case when a > b then 'eu' when b > a then 'ele' when a > 0 then 'empate' else null end);
      else
        arr := arr || json_build_object('dia', dd, 'r', case when dd = h then 'hoje' else null end);
      end if;
      if dd = h then me_h := a; ele_h := b; end if;
    end loop;
    select coalesce(sum(valor) filter (where user_id = eu), 0), coalesce(sum(valor) filter (where user_id = o), 0) into me, ele
      from public.duelo_dias where duelo_id = d.id;
  end if;
  return json_build_object('id', d.id, 'status', d.status, 'inicio', d.inicio, 'fim', d.fim,
    'eu_desafiei', d.desafiante = eu,
    'outro', (select public._pessoa(p) from public.perfis p where p.id = o),
    'dias', array_to_json(arr), 'placar', json_build_array(pe, po),
    'lider', case when coalesce(me,0) > coalesce(ele,0) then 'eu' when coalesce(ele,0) > coalesce(me,0) then 'ele' else 'empate' end,
    'pct', case when coalesce(me,0) = coalesce(ele,0) then 0 when least(me,ele) <= 0 then 100
                else round((greatest(me,ele) - least(me,ele)) / least(me,ele) * 100) end,
    'parte', case when coalesce(me,0) + coalesce(ele,0) > 0 then round(me / (me + ele) * 100) else 50 end,
    'hoje', case when d.status = 'ativo' and me_h is not null then json_build_object(
        'lider', case when me_h > ele_h then 'eu' when ele_h > me_h then 'ele' else 'empate' end,
        'pct', case when me_h = ele_h then 0 when least(me_h,ele_h) <= 0 then 100 else round((greatest(me_h,ele_h) - least(me_h,ele_h)) / least(me_h,ele_h) * 100) end) end,
    'venci', case when d.status = 'fim' then (case when d.vencedor = eu then true when d.vencedor = o then false end) end,
    'vitorias', json_build_array(
      (select count(*) from public.duelos x where x.status = 'fim' and x.vencedor = eu and (x.desafiante = o or x.desafiado = o)),
      (select count(*) from public.duelos x where x.status = 'fim' and x.vencedor = o and (x.desafiante = eu or x.desafiado = eu))));
end; $$;
revoke all on function public._duelo_json(public.duelos, uuid) from public, anon, authenticated;

-- tela Amigos: meus amigos, pedidos e duelos
create or replace function public.amigos_meus()
returns json language plpgsql security definer set search_path to '' as $$
declare eu uuid := auth.uid(); m date := public._mes_atual(); h date := public._hoje();
begin
  if eu is null then raise exception 'sem_sessao'; end if;
  return json_build_object(
    'amigos', (select coalesce(json_agg(json_build_object('pessoa', public._pessoa(p),
        'rotas', (select count(*) from public.rotas_reg r where r.user_id = p.id and r.app in ('ifood','indrive')),
        'conquistas', (select count(*) from public.insignias_usuario i where i.user_id = p.id),
        'pos', (select x.pos from public._ranking(m, h) x where x.user_id = p.id),
        'duelo', (select public._duelo_json(d, eu) from public.duelos d where d.status in ('convite','ativo')
                   and least(d.desafiante, d.desafiado) = least(eu, p.id) and greatest(d.desafiante, d.desafiado) = greatest(eu, p.id) limit 1)
      ) order by public._nome_pub(p)), '[]'::json)
      from public.perfis p where p.id in (select id from public._amigos_de(eu))),
    'recebidos', (select coalesce(json_agg(public._pessoa(p) order by z.criado_em desc), '[]'::json)
      from public.amizades z join public.perfis p on p.id = z.de
     where (z.a = eu or z.b = eu) and z.status = 'pendente' and z.de <> eu),
    'enviados', (select coalesce(json_agg(public._pessoa(p) order by z.criado_em desc), '[]'::json)
      from public.amizades z join public.perfis p on p.id = case when z.a = eu then z.b else z.a end
     where (z.a = eu or z.b = eu) and z.status = 'pendente' and z.de = eu),
    'duelos', (select coalesce(json_agg(public._duelo_json(d, eu) order by d.criado_em desc), '[]'::json)
      from public.duelos d where (d.desafiante = eu or d.desafiado = eu)
       and (d.status in ('convite','ativo') or (d.status = 'fim' and d.fechado_em > now() - interval '7 days'))),
    'n_duelos', (select count(*) from public.duelos d where (d.desafiante = eu or d.desafiado = eu) and d.status = 'fim'),
    'vitorias', (select coalesce(json_agg((extract(epoch from d.fechado_em)*1000)::bigint), '[]'::json)
      from public.duelos d where d.status = 'fim' and d.vencedor = eu),
    'rank', public._rank_de(eu)
  );
end; $$;
revoke all on function public.amigos_meus() from public, anon;
grant execute on function public.amigos_meus() to authenticated;

-- perfil de qualquer pessoa (abre ao tocar num amigo ou em alguém da classificação)
create or replace function public.amigo_perfil(p_conta text)
returns json language plpgsql stable security definer set search_path to '' as $$
declare eu uuid := auth.uid(); p public.perfis;
begin
  if eu is null then raise exception 'sem_sessao'; end if;
  select * into p from public.perfis where conta_id = p_conta;
  if not found then raise exception 'conta_nao_encontrada'; end if;
  return json_build_object('pessoa', public._pessoa(p), 'relacao', public._relacao(eu, p.id),
    'amigos', (select count(*) from public._amigos_de(p.id)),
    'duelos', (select count(*) from public.duelos d where (d.desafiante = p.id or d.desafiado = p.id) and d.status = 'fim'),
    'rotas', (select count(*) from public.rotas_reg r where r.user_id = p.id and r.app in ('ifood','indrive')),
    'rank', public._rank_de(p.id),
    'minhas', (select coalesce(json_agg(json_build_object('id', i.insignia_id, 'ganha_em', i.ganha_em) order by i.ganha_em), '[]'::json)
                 from public.insignias_usuario i where i.user_id = p.id),
    'lista', (select coalesce(json_agg(json_build_object('pessoa', public._pessoa(x), 'relacao', public._relacao(eu, x.id),
                'rotas', (select count(*) from public.rotas_reg r where r.user_id = x.id and r.app in ('ifood','indrive')))
               order by public._nome_pub(x)), '[]'::json)
               from (select * from public.perfis where id in (select id from public._amigos_de(p.id)) limit 100) x),
    'duelo', (select public._duelo_json(d, eu) from public.duelos d where d.status in ('convite','ativo')
               and least(d.desafiante, d.desafiado) = least(eu, p.id) and greatest(d.desafiante, d.desafiado) = greatest(eu, p.id) limit 1),
    'vs', json_build_array(
      (select count(*) from public.duelos x where x.status = 'fim' and x.vencedor = eu and (x.desafiante = p.id or x.desafiado = p.id)),
      (select count(*) from public.duelos x where x.status = 'fim' and x.vencedor = p.id and (x.desafiante = eu or x.desafiado = eu))));
end; $$;
revoke all on function public.amigo_perfil(text) from public, anon;
grant execute on function public.amigo_perfil(text) to authenticated;

-- ── 6. RPCs de duelo ────────────────────────────────────────────────────────
create or replace function public.duelo_desafiar(p_conta text)
returns json language plpgsql security definer set search_path to '' as $$
declare eu uuid := auth.uid(); o uuid; novo public.duelos;
begin
  if eu is null then raise exception 'sem_sessao'; end if;
  select id into o from public.perfis where conta_id = p_conta;
  if o is null then raise exception 'conta_nao_encontrada'; end if;
  if public._relacao(eu, o) <> 'amigo' then raise exception 'nao_amigos'; end if;
  if exists (select 1 from public.duelos d where d.status in ('convite','ativo')
              and least(d.desafiante, d.desafiado) = least(eu, o) and greatest(d.desafiante, d.desafiado) = greatest(eu, o)) then
    raise exception 'duelo_aberto';
  end if;
  insert into public.duelos(desafiante, desafiado) values (eu, o) returning * into novo;
  perform public.push_enviar(o, false, null, 9800 + (novo.id % 100)::int, '⚔️ Desafio de duelo',
    (select public._nome_pub(x) from public.perfis x where x.id = eu) || ' te desafiou para um duelo de 7 dias. Quem faturar mais vence!');
  return json_build_object('ok', true, 'duelo', public._duelo_json(novo, eu));
end; $$;
revoke all on function public.duelo_desafiar(text) from public, anon;
grant execute on function public.duelo_desafiar(text) to authenticated;

create or replace function public.duelo_responder(p_id bigint, p_aceitar boolean)
returns json language plpgsql security definer set search_path to '' as $$
declare eu uuid := auth.uid(); d public.duelos;
begin
  if eu is null then raise exception 'sem_sessao'; end if;
  select * into d from public.duelos where id = p_id and status = 'convite' for update;
  if not found then raise exception 'duelo_indisponivel'; end if;
  if d.desafiado = eu then
    if p_aceitar then
      update public.duelos set status = 'ativo', inicio = public._hoje(), fim = public._hoje() + 6, aceito_em = now() where id = d.id returning * into d;
      perform public.push_enviar(d.desafiante, false, null, 9800 + (d.id % 100)::int, '⚔️ Duelo aceito!',
        (select public._nome_pub(x) from public.perfis x where x.id = eu) || ' aceitou o duelo. Valendo a partir de hoje, por 7 dias.');
    else
      update public.duelos set status = 'recusado', fechado_em = now() where id = d.id returning * into d;
    end if;
  elsif d.desafiante = eu and not p_aceitar then
    update public.duelos set status = 'cancelado', fechado_em = now() where id = d.id returning * into d;
  else raise exception 'duelo_indisponivel';
  end if;
  return json_build_object('ok', true, 'duelo', public._duelo_json(d, eu));
end; $$;
revoke all on function public.duelo_responder(bigint, boolean) from public, anon;
grant execute on function public.duelo_responder(bigint, boolean) to authenticated;

create or replace function public.duelo_ver(p_id bigint)
returns json language plpgsql stable security definer set search_path to '' as $$
declare eu uuid := auth.uid(); d public.duelos;
begin
  if eu is null then raise exception 'sem_sessao'; end if;
  select * into d from public.duelos where id = p_id and (desafiante = eu or desafiado = eu);
  if not found then raise exception 'duelo_indisponivel'; end if;
  return public._duelo_json(d, eu);
end; $$;
revoke all on function public.duelo_ver(bigint) from public, anon;
grant execute on function public.duelo_ver(bigint) to authenticated;

-- o app manda o total de cada dia (rotas automáticas + gorjetas) SÓ dos dias de duelo ativo
create or replace function public.duelo_enviar(p_dias jsonb)
returns json language plpgsql security definer set search_path to '' as $$
declare eu uuid := auth.uid(); d public.duelos; k text; v numeric; dd date; n int := 0;
begin
  if eu is null then raise exception 'sem_sessao'; end if;
  if jsonb_typeof(p_dias) <> 'object' then return json_build_object('ok', false); end if;
  for d in select * from public.duelos where status = 'ativo' and (desafiante = eu or desafiado = eu) loop
    for k, v in select key, (value #>> '{}')::numeric from jsonb_each(p_dias) loop
      begin dd := k::date; exception when others then continue; end;
      if dd < d.inicio or dd > least(d.fim, public._hoje()) then continue; end if;
      insert into public.duelo_dias(duelo_id, user_id, dia, valor) values (d.id, eu, dd, least(greatest(coalesce(v,0),0), 5000))
      on conflict (duelo_id, user_id, dia) do update set valor = excluded.valor, atualizado_em = now();
      n := n + 1;
    end loop;
  end loop;
  return json_build_object('ok', true, 'gravados', n);
end; $$;
revoke all on function public.duelo_enviar(jsonb) from public, anon;
grant execute on function public.duelo_enviar(jsonb) to authenticated;

-- fecha os duelos que acabaram (roda todo dia logo depois da meia-noite)
create or replace function public.duelos_fechar()
returns int language plpgsql security definer set search_path to '' as $$
declare d public.duelos; a numeric; b numeric; venc uuid; pc numeric; n int := 0; na text; nb text;
begin
  for d in select * from public.duelos where status = 'ativo' and fim < public._hoje() for update loop
    select coalesce(sum(valor) filter (where user_id = d.desafiante), 0), coalesce(sum(valor) filter (where user_id = d.desafiado), 0) into a, b
      from public.duelo_dias where duelo_id = d.id;
    venc := case when a > b then d.desafiante when b > a then d.desafiado end;
    pc := case when a = b then 0 when least(a,b) <= 0 then 100 else round((greatest(a,b) - least(a,b)) / least(a,b) * 100) end;
    update public.duelos set status = 'fim', vencedor = venc, pct = pc, fechado_em = now() where id = d.id;
    select public._nome_pub(p) into na from public.perfis p where p.id = d.desafiante;
    select public._nome_pub(p) into nb from public.perfis p where p.id = d.desafiado;
    if venc is null then
      perform public.push_enviar(d.desafiante, false, null, 9800 + (d.id % 100)::int, '🤝 Duelo empatado', 'O duelo com ' || nb || ' terminou empatado.');
      perform public.push_enviar(d.desafiado, false, null, 9800 + (d.id % 100)::int, '🤝 Duelo empatado', 'O duelo com ' || na || ' terminou empatado.');
    else
      perform public.push_enviar(venc, false, null, 9800 + (d.id % 100)::int, '🏆 Você venceu o duelo!',
        'Você faturou ' || pc || '% a mais que ' || case when venc = d.desafiante then nb else na end || '. Toque para ver.');
      perform public.push_enviar(case when venc = d.desafiante then d.desafiado else d.desafiante end, false, null, 9800 + (d.id % 100)::int, '⚔️ Duelo encerrado',
        case when venc = d.desafiante then na else nb end || ' venceu desta vez. Bora de revanche?');
    end if;
    n := n + 1;
  end loop;
  -- convites esquecidos somem depois de 3 dias
  update public.duelos set status = 'cancelado', fechado_em = now() where status = 'convite' and criado_em < now() - interval '3 days';
  return n;
end; $$;
revoke all on function public.duelos_fechar() from public, anon, authenticated;

-- ── 7. RPCs da ranqueada ────────────────────────────────────────────────────
create or replace function public.ranking_ver(p_aba text default 'mundial')
returns json language plpgsql stable security definer set search_path to '' as $$
declare eu uuid := auth.uid(); m date := public._mes_atual(); h date := public._hoje(); cfg public.ranking_config; g uuid[];
begin
  if eu is null then raise exception 'sem_sessao'; end if;
  select * into cfg from public.ranking_config where id = 1;
  if cfg.pausado then return json_build_object('pausado', true); end if;
  if p_aba = 'amigos' then g := array(select id from public._amigos_de(eu)) || eu; end if;
  return json_build_object('pausado', false, 'aba', coalesce(p_aba,'mundial'), 'mes', m,
    'termina', (m + interval '1 month')::date, 'premios', cfg.premios,
    'total', (select count(*) from public._ranking(m, h, g)),
    'top', (select coalesce(json_agg(json_build_object('pos', x.pos, 'rotas', x.rotas, 'eu', x.user_id = eu,
               'pessoa', public._pessoa(p),
               'subiu', case when h - 7 >= m then (select y.pos from public._ranking(m, h - 7, g) y where y.user_id = x.user_id) - x.pos end)
             order by x.pos, p.criado_em), '[]'::json)
             from (select * from public._ranking(m, h, g) order by pos limit 50) x join public.perfis p on p.id = x.user_id),
    'eu', (select json_build_object('pos', x.pos, 'rotas', x.rotas,
              'subiu', case when h - 7 >= m then (select y.pos from public._ranking(m, h - 7, g) y where y.user_id = eu) - x.pos end,
              'acima', (select min(y.rotas) - x.rotas from public._ranking(m, h, g) y where y.pos < x.pos))
             from public._ranking(m, h, g) x where x.user_id = eu),
    'anterior', (select json_build_object('mes', t.mes, 'top', t.top) from public.ranking_temporadas t order by t.mes desc limit 1));
end; $$;
revoke all on function public.ranking_ver(text) from public, anon;
grant execute on function public.ranking_ver(text) to authenticated;

-- fecha o mês anterior e entrega os prêmios (roda todo dia 1º logo depois da meia-noite)
create or replace function public.ranking_fechar()
returns json language plpgsql security definer set search_path to '' as $$
declare m date := (public._mes_atual() - interval '1 month')::date; cfg public.ranking_config; r record; pr jsonb; dias int; dados jsonb := '[]'; ent jsonb := '[]'; tot int;
begin
  if exists (select 1 from public.ranking_temporadas where mes = m) then return json_build_object('ok', false, 'motivo', 'ja_fechado'); end if;
  select * into cfg from public.ranking_config where id = 1;
  select count(*) into tot from public._ranking(m, (m + interval '1 month')::date - 1);
  for r in select x.*, p.conta_id, public._nome_pub(p) as nome, p.plano, row_number() over (order by x.pos, p.criado_em) as lugar
             from public._ranking(m, (m + interval '1 month')::date - 1) x join public.perfis p on p.id = x.user_id order by x.pos, p.criado_em limit 10 loop
    pr := (select e from jsonb_array_elements(cfg.premios) e where (e->>'pos')::int = r.lugar limit 1);
    dados := dados || jsonb_build_object('pos', r.lugar, 'conta_id', r.conta_id, 'nome', r.nome, 'rotas', r.rotas, 'premio', pr->>'texto');
    if pr is not null and not cfg.pausado then
      dias := coalesce((pr->>'dias')::int, 0);
      if dias > 0 and r.plano <> 'admin' then
        update public.perfis set plano = 'pro', pro_ate = greatest(coalesce(pro_ate, now()), now()) + make_interval(days => dias) where id = r.user_id;
      end if;
      ent := ent || jsonb_build_object('pos', r.lugar, 'conta_id', r.conta_id, 'dias', dias, 'texto', pr->>'texto');
      perform public.push_enviar(r.user_id, false, null, 9900 + r.lugar::int, '🏆 Você ficou em ' || r.lugar || 'º na ranqueada!',
        'Parabéns! Seu prêmio: ' || coalesce(pr->>'texto', 'reconhecimento') || '.');
    end if;
  end loop;
  insert into public.ranking_temporadas(mes, participantes, top, premios) values (m, tot, dados, ent);
  return json_build_object('ok', true, 'mes', m, 'premiados', ent);
end; $$;
revoke all on function public.ranking_fechar() from public, anon, authenticated;

-- ── 8. Prismo Admin ─────────────────────────────────────────────────────────
create or replace function public.admin_ranking()
returns json language plpgsql stable security definer set search_path to '' as $$
declare m date := public._mes_atual(); h date := public._hoje();
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  return json_build_object('config', (select row_to_json(c) from public.ranking_config c where id = 1),
    'mes', m, 'total', (select count(*) from public._ranking(m, h)),
    'top', (select coalesce(json_agg(json_build_object('pos', x.pos, 'rotas', x.rotas, 'conta_id', p.conta_id, 'nome', public._nome_pub(p), 'email', p.email, 'foto', p.foto_url) order by x.pos, p.criado_em), '[]'::json)
             from (select * from public._ranking(m, h) order by pos limit 20) x join public.perfis p on p.id = x.user_id),
    'temporadas', (select coalesce(json_agg(row_to_json(t) order by t.mes desc), '[]'::json) from (select * from public.ranking_temporadas order by mes desc limit 12) t),
    'duelos', json_build_object('ativos', (select count(*) from public.duelos where status = 'ativo'),
                                'convites', (select count(*) from public.duelos where status = 'convite'),
                                'encerrados', (select count(*) from public.duelos where status = 'fim')),
    'amizades', (select count(*) from public.amizades where status = 'aceita'));
end; $$;
revoke all on function public.admin_ranking() from public, anon;
grant execute on function public.admin_ranking() to authenticated;

create or replace function public.admin_ranking_salvar(p_pausado boolean, p_premios jsonb)
returns json language plpgsql security definer set search_path to '' as $$
declare limpo jsonb := '[]'; e jsonb;
begin
  if not public.eh_admin() then raise exception 'sem_permissao'; end if;
  if p_premios is not null then
    if jsonb_typeof(p_premios) <> 'array' or jsonb_array_length(p_premios) > 10 then raise exception 'premios_invalidos'; end if;
    for e in select * from jsonb_array_elements(p_premios) loop
      if coalesce(trim(e->>'texto'),'') = '' then continue; end if;
      limpo := limpo || jsonb_build_object('pos', (e->>'pos')::int, 'texto', left(trim(e->>'texto'), 60),
                                           'dias', least(greatest(coalesce(nullif(e->>'dias','')::int, 0), 0), 365));
    end loop;
  end if;
  update public.ranking_config set pausado = coalesce(p_pausado, pausado), premios = case when p_premios is null then premios else limpo end, atualizado_em = now() where id = 1;
  insert into public.admin_log(admin_id, acao, conta_id, detalhes) values (auth.uid(), 'ranking', null, json_build_object('pausado', p_pausado, 'premios', limpo));
  return (select row_to_json(c) from public.ranking_config c where id = 1);
end; $$;
revoke all on function public.admin_ranking_salvar(boolean, jsonb) from public, anon;
grant execute on function public.admin_ranking_salvar(boolean, jsonb) to authenticated;

-- ── 9. missões: "duelos vencidos" e prêmio em dias de Pro ───────────────────
alter table public.missoes add column if not exists premio_dias int not null default 0 check (premio_dias between 0 and 365);
alter table public.missoes drop constraint if exists missoes_tipo_check;
alter table public.missoes add constraint missoes_tipo_check check (tipo in ('rotas','rotas_todas','km','dias','gorjetas','ganho','turnos','meta','indicacoes','assistente','duelos'));

create or replace function public.missao_progresso(p_missao bigint, p_valor numeric)
returns json language plpgsql security definer set search_path to '' as $$
declare mu public.missoes_usuario; m public.missoes; ins json; nova boolean := false; v numeric := coalesce(p_valor,0); real_n bigint;
begin
  if auth.uid() is null then raise exception 'sem_sessao'; end if;
  select * into mu from public.missoes_usuario where user_id = auth.uid() and missao_id = p_missao for update;
  if not found then raise exception 'nao_aceita'; end if;
  select * into m from public.missoes where id = p_missao;
  if mu.status <> 'aceita' then return json_build_object('status', mu.status, 'progresso', mu.progresso); end if;
  if m.prazo_dias is not null and now() > mu.aceita_em + make_interval(days => m.prazo_dias) then
    update public.missoes_usuario set status = 'falhou' where user_id = auth.uid() and missao_id = p_missao;
    return json_build_object('status', 'falhou', 'progresso', mu.progresso);
  end if;
  -- duelos vencidos: o servidor confere (só os que terminaram depois de aceitar a missão)
  if m.tipo = 'duelos' then
    select count(*) into real_n from public.duelos d where d.status = 'fim' and d.vencedor = auth.uid() and d.fechado_em >= mu.aceita_em;
    v := least(v, real_n);
  end if;
  update public.missoes_usuario set progresso = greatest(progresso, least(v, m.meta * 10))
   where user_id = auth.uid() and missao_id = p_missao returning * into mu;
  if mu.progresso >= m.meta then
    update public.missoes_usuario set status = 'concluida', concluida_em = now() where user_id = auth.uid() and missao_id = p_missao;
    if m.insignia_id is not null then
      insert into public.insignias_usuario(user_id, insignia_id, missao_id) values (auth.uid(), m.insignia_id, m.id)
      on conflict do nothing;
      nova := found;
      select public._insignia_json(i) into ins from public.insignias i where i.id = m.insignia_id;
    end if;
    if m.premio_dias > 0 then
      update public.perfis set plano = case when plano = 'admin' then plano else 'pro' end,
             pro_ate = case when plano = 'admin' then pro_ate else greatest(coalesce(pro_ate, now()), now()) + make_interval(days => m.premio_dias) end
       where id = auth.uid();
    end if;
    return json_build_object('status', 'concluida', 'progresso', mu.progresso, 'insignia', ins, 'nova', nova, 'premio_dias', m.premio_dias);
  end if;
  return json_build_object('status', 'aceita', 'progresso', mu.progresso);
end; $$;
revoke all on function public.missao_progresso(bigint, numeric) from public, anon;
grant execute on function public.missao_progresso(bigint, numeric) to authenticated;

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
        'livre', m.livre, 'premio_dias', m.premio_dias,
        'insignia', (select public._insignia_json(i) from public.insignias i where i.id = m.insignia_id),
        'status', mu.status, 'progresso', mu.progresso,
        'aceita_em', case when mu.aceita_em is not null then (extract(epoch from mu.aceita_em)*1000)::bigint end,
        'concluida_em', mu.concluida_em
      ) order by m.ordem, m.id), '[]'::json)
      from public.missoes m left join public.missoes_usuario mu on mu.missao_id = m.id and mu.user_id = auth.uid()
     where m.id in (select l.id from public._missoes_liberadas(auth.uid()) l)),
    'trancadas', (select count(*) from public.missoes m
                   where m.ativa and not m.livre and m.id not in (select l.id from public._missoes_liberadas(auth.uid()) l)),
    'insignias', (select coalesce(json_agg(public._insignia_json(i) order by i.ordem, i.id), '[]'::json) from public.insignias i where i.ativa
                   or exists (select 1 from public.insignias_usuario iu where iu.insignia_id = i.id and iu.user_id = auth.uid())),
    'minhas', (select coalesce(json_agg(json_build_object('id', iu.insignia_id, 'ganha_em', iu.ganha_em) order by iu.ganha_em), '[]'::json)
                 from public.insignias_usuario iu where iu.user_id = auth.uid()),
    -- quando cada duelo foi vencido (para as missões de duelos)
    'vitorias', (select coalesce(json_agg((extract(epoch from d.fechado_em)*1000)::bigint), '[]'::json)
                   from public.duelos d where d.status = 'fim' and d.vencedor = auth.uid())
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
        'exige_assistente', m.exige_assistente, 'livre', m.livre, 'premio_dias', m.premio_dias, 'ativa', m.ativa, 'ordem', m.ordem, 'criado_em', m.criado_em,
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
    insert into public.missoes(titulo, descricao, icone, imagem, cor1, cor2, dificuldade, tipo, meta, prazo_dias, insignia_id, exige_assistente, livre, premio_dias, ativa, ordem)
    values (d->>'titulo', coalesce(d->>'descricao',''), coalesce(nullif(d->>'icone',''),'flag'), nullif(d->>'imagem',''), coalesce(nullif(d->>'cor1',''),'#6d28d9'),
            coalesce(nullif(d->>'cor2',''),'#a78bfa'), coalesce(nullif(d->>'dificuldade',''),'media'), d->>'tipo', (d->>'meta')::numeric,
            nullif(d->>'prazo_dias','')::int, nullif(d->>'insignia_id','')::bigint, coalesce((d->>'exige_assistente')::boolean, false),
            coalesce((d->>'livre')::boolean, false), least(greatest(coalesce(nullif(d->>'premio_dias','')::int, 0), 0), 365),
            coalesce((d->>'ativa')::boolean, true), coalesce(nullif(d->>'ordem','')::int, 0))
    returning id into novo;
  else
    update public.missoes set titulo = d->>'titulo', descricao = coalesce(d->>'descricao',''), icone = coalesce(nullif(d->>'icone',''),'flag'),
           imagem = nullif(d->>'imagem',''),
           cor1 = coalesce(nullif(d->>'cor1',''),cor1), cor2 = coalesce(nullif(d->>'cor2',''),cor2), dificuldade = coalesce(nullif(d->>'dificuldade',''),dificuldade),
           tipo = d->>'tipo', meta = (d->>'meta')::numeric, prazo_dias = nullif(d->>'prazo_dias','')::int,
           insignia_id = nullif(d->>'insignia_id','')::bigint, exige_assistente = coalesce((d->>'exige_assistente')::boolean, exige_assistente),
           livre = coalesce((d->>'livre')::boolean, livre),
           premio_dias = case when d ? 'premio_dias' then least(greatest(coalesce(nullif(d->>'premio_dias','')::int, 0), 0), 365) else premio_dias end,
           ativa = coalesce((d->>'ativa')::boolean, ativa), ordem = coalesce(nullif(d->>'ordem','')::int, ordem), atualizado_em = now()
     where id = (d->>'id')::bigint returning id into novo;
  end if;
  return json_build_object('ok', true, 'id', novo);
end; $$;
revoke all on function public.admin_missao_salvar(jsonb) from public, anon;
grant execute on function public.admin_missao_salvar(jsonb) to authenticated;

-- ── 10. agendamentos ────────────────────────────────────────────────────────
-- 03:10 UTC = 00:10 em Brasília
do $$ begin perform cron.unschedule('prismo-duelos-fechar'); exception when others then null; end $$;
select cron.schedule('prismo-duelos-fechar', '10 3 * * *', 'select public.duelos_fechar()');
do $$ begin perform cron.unschedule('prismo-ranking-fechar'); exception when others then null; end $$;
select cron.schedule('prismo-ranking-fechar', '5 3 1 * *', 'select public.ranking_fechar()');
