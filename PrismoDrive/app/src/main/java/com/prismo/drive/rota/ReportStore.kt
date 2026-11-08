package com.prismo.drive.rota

import com.prismo.drive.R
import com.prismo.drive.BuildConfig

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

/** Uma rota analisada, como fica guardada no relatório. */
class Rec(
    val t: Long,            // quando foi vista (epoch ms)
    val route: Boolean,     // true = iFood (rota com coletas/entregas); false = InDrive
    val price: Double,      // valor bruto
    val km: Double?,        // km total (busca + corrida)
    val rpk: Double?,       // R$ por km rodado
    val orders: Int,        // nº de pedidos (entregas); 0 se não for rota
    val per: Double?,       // valor bruto por pedido (só rotas do iFood)
    val net: Double?,       // ganho líquido (preço - gasolina)
    val rating: Double?     // nota do passageiro
)

/**
 * Armazenamento do relatório.
 *
 * - Cada mês vira um arquivo (reports/2026-11.jsonl), uma rota por linha.
 * - Guarda o mês atual + os 2 anteriores (3 meses). Quando começa um 4º mês, o arquivo mais
 *   antigo é APAGADO de vez (não ocupa mais espaço).
 * - Uma oferta fica na tela por vários segundos e é relida várias vezes; aqui ela é registrada
 *   uma única vez (quando some da tela), usando a leitura mais exata que apareceu.
 */
class ReportStore(private val ctx: Context) {

    private class Pending(var r: Result, val firstUp: Long, val firstWall: Long, var lastUp: Long)

    private val pending = HashMap<String, Pending>()
    private val done = HashMap<String, Long>()

    companion object {
        const val KEEP_MONTHS = 3
        private const val DWELL_MS = 1200L          // tempo mínimo na tela para contar como "analisada"
        private const val GONE_MS = 3000L           // sumiu da tela há 3 s = registra
        private const val DEDUPE_MS = 5 * 60_000L   // mesma oferta dentro de 5 min não conta de novo
        private val LOCK = Any()
        private val NAME = Regex("""(\d{4})-(\d{2})\.jsonl""")

        private fun dir(ctx: Context): File = File(ctx.filesDir, "reports").also { it.mkdirs() }

        fun monthIdx(t: Long): Int {
            val c = Calendar.getInstance().apply { timeInMillis = t }
            return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
        }

        private fun fileFor(ctx: Context, t: Long): File {
            val c = Calendar.getInstance().apply { timeInMillis = t }
            return File(dir(ctx), String.format(Locale.US, "%04d-%02d.jsonl", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1))
        }

        /** Apaga os meses que passaram do limite (mês atual + 2 anteriores). */
        fun purge(ctx: Context) {
            synchronized(LOCK) {
                val cur = monthIdx(System.currentTimeMillis())
                dir(ctx).listFiles()?.forEach { f ->
                    val m = NAME.matchEntire(f.name) ?: return@forEach
                    val idx = m.groupValues[1].toInt() * 12 + m.groupValues[2].toInt() - 1
                    if (idx < cur - (KEEP_MONTHS - 1)) f.delete()
                }
            }
        }

        private fun JSONObject.dbl(k: String): Double? = optDouble(k).takeIf { !it.isNaN() }

        /** Lê todas as rotas guardadas (já descartando os meses vencidos), da mais antiga para a mais nova. */
        fun load(ctx: Context): List<Rec> {
            purge(ctx)
            val out = ArrayList<Rec>()
            synchronized(LOCK) {
                dir(ctx).listFiles()?.filter { NAME.matches(it.name) }?.forEach { f ->
                    f.forEachLine { line ->
                        runCatching {
                            val j = JSONObject(line)
                            out.add(
                                Rec(
                                    t = j.getLong("t"),
                                    route = j.optInt("r") == 1,
                                    price = j.getDouble("p"),
                                    km = j.dbl("k"),
                                    rpk = j.dbl("e"),
                                    orders = j.optInt("o"),
                                    per = j.dbl("d"),
                                    net = j.dbl("n"),
                                    rating = j.dbl("a")
                                )
                            )
                        }
                    }
                }
            }
            out.sortBy { it.t }
            return out
        }
    }

    private fun keyOf(r: Result): String {
        val o = r.offer
        val cents = Math.round(o.price * 100)
        // Rota (iFood): lista e popup mostram o mesmo valor. Corrida (InDrive): valor + nota do passageiro.
        return if (o.route) "R|$cents" else "D|$cents|${Math.round((o.rating ?: 0.0) * 100)}"
    }

    /** Chamado a cada leitura da tela com as ofertas visíveis. */
    fun observe(results: List<Result>) {
        val now = SystemClock.uptimeMillis()
        val wall = System.currentTimeMillis()
        for (r in results) {
            if (r.offer.price <= 0) continue
            val key = keyOf(r)
            val d = done[key]
            if (d != null && now - d < DEDUPE_MS) {   // já registrada: só renova enquanto estiver na tela
                done[key] = now
                continue
            }
            val p = pending[key]
            if (p == null) {
                pending[key] = Pending(r, now, wall, now)
            } else {
                p.lastUp = now
                if (!r.estimated || p.r.estimated) p.r = r   // leitura exata (detalhe) vale mais que a estimada (lista)
                // ficou na tela o bastante para contar como analisada: confere se é o maior valor do dia
                if (now - p.firstUp >= DWELL_MS) runCatching { DayBest.offer(ctx, p.r) }
            }
        }
    }

    /** Registra as ofertas que já saíram da tela (force = registra todas, ex.: serviço parando). */
    fun flush(force: Boolean = false) {
        if (pending.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        var wrote = false
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            val p = e.value
            if (force || now - p.lastUp > GONE_MS) {
                it.remove()
                if (p.lastUp - p.firstUp >= DWELL_MS) {
                    commit(p)
                    done[e.key] = now
                    wrote = true
                }
            }
        }
        if (done.size > 300) done.entries.removeAll { now - it.value > DEDUPE_MS }
        if (wrote) purge(ctx)
    }

    private fun commit(p: Pending) {
        val r = p.r
        val o = r.offer
        val j = JSONObject()
        j.put("t", p.firstWall)
        j.put("r", if (o.route) 1 else 0)
        j.put("p", o.price)
        r.totalKm?.takeIf { it.isFinite() }?.let { j.put("k", it) }
        r.effRpk?.takeIf { it.isFinite() }?.let { j.put("e", it) }
        if (o.route) j.put("o", o.entregas)
        r.perOrder?.takeIf { it.isFinite() }?.let { j.put("d", it) }
        r.net?.takeIf { it.isFinite() }?.let { j.put("n", it) }
        o.rating?.takeIf { it.isFinite() }?.let { j.put("a", it) }
        synchronized(LOCK) {
            runCatching { fileFor(ctx, p.firstWall).appendText(j.toString() + "\n") }
        }
    }
}

/**
 * Maior valor (R$ bruto) analisado HOJE. Atualiza sozinho sempre que aparece uma oferta melhor
 * e zera na virada do dia. Mostrado no painel do botão flutuante.
 */
object DayBest {
    class Best(val t: Long, val route: Boolean, val price: Double, val km: Double?, val rpk: Double?, val orders: Int)

    private const val SP = "rotaguru_best"
    @Volatile private var cache: Best? = null
    @Volatile private var cacheDay = -1

    private fun dayOf(t: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
    }

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    /** O maior valor de hoje (null = nada analisado hoje ainda). */
    fun get(ctx: Context): Best? {
        val today = dayOf(System.currentTimeMillis())
        if (cacheDay == today) return cache
        synchronized(this) {
            if (cacheDay == today) return cache
            val s = sp(ctx)
            var b: Best? = null
            if (s.getInt("day", -1) == today) {
                b = Best(
                    s.getLong("t", 0), s.getBoolean("r", false), s.getFloat("p", 0f).toDouble(),
                    s.getFloat("k", -1f).takeIf { it >= 0 }?.toDouble(),
                    s.getFloat("e", -1f).takeIf { it >= 0 }?.toDouble(),
                    s.getInt("o", 0)
                )
            } else {
                // 1ª vez no dia (ou logo após atualizar o app): procura no relatório o que já foi visto hoje
                runCatching {
                    ReportStore.load(ctx).filter { dayOf(it.t) == today }.maxByOrNull { it.price }?.let {
                        b = Best(it.t, it.route, it.price, it.km, it.rpk, it.orders)
                    }
                }
                b?.let { save(ctx, today, it) } ?: s.edit().clear().putInt("day", today).apply()
            }
            cache = b
            cacheDay = today
            return b
        }
    }

    /** Oferta analisada: se for maior que o recorde de hoje, vira o novo recorde. */
    fun offer(ctx: Context, r: Result) {
        val o = r.offer
        if (o.price <= 0 || !o.price.isFinite()) return
        val cur = get(ctx)
        if (cur != null && o.price <= cur.price + 0.001) return
        val now = System.currentTimeMillis()
        val b = Best(
            now, o.route, o.price,
            r.totalKm?.takeIf { it.isFinite() },
            r.effRpk?.takeIf { it.isFinite() },
            if (o.route) o.entregas else 0
        )
        synchronized(this) {
            val today = dayOf(now)
            save(ctx, today, b)
            cache = b
            cacheDay = today
        }
    }

    private fun save(ctx: Context, day: Int, b: Best) {
        sp(ctx).edit()
            .putInt("day", day)
            .putLong("t", b.t)
            .putBoolean("r", b.route)
            .putFloat("p", b.price.toFloat())
            .putFloat("k", (b.km ?: -1.0).toFloat())
            .putFloat("e", (b.rpk ?: -1.0).toFloat())
            .putInt("o", b.orders)
            .apply()
    }
}

/**
 * Referência do mês para as abas iFood / InDrive: as mesmas faixas automáticas do Relatório
 * (baixo / médio / alto), calculadas só com as rotas do mês atual. Recalcula sempre que é chamada,
 * então acompanha o Relatório sozinha.
 */
object MonthRef {
    class Tier(val pct: Int, val count: Int, val avg: Double?)
    class Ref(val n: Int, val mid: Double, val high: Double, val low: Tier, val medium: Tier, val top: Tier) {
        /** 0 = baixo, 1 = médio, 2 = alto — a faixa com mais rotas (empate: a de maior valor). */
        val topIdx: Int get() = listOf(low, medium, top).withIndex().maxByOrNull { it.value.count * 10 + it.index }!!.index
        fun tier(i: Int) = when (i) { 0 -> low; 1 -> medium; else -> top }
    }

    /** Rotas do mês atual: route = true (iFood) / false (InDrive). */
    fun monthRecs(ctx: Context, route: Boolean): List<Rec> {
        val c = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = c.timeInMillis
        return ReportStore.load(ctx).filter { it.t >= start && it.route == route }
    }

    /** Faixas de [values] (null = sem dados). Mesmo cálculo do Relatório: 3 terços entre 5% e 95%. */
    fun of(values: List<Double>, step: Double = 0.05): Ref? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        fun q(p: Double): Double {
            val pos = p * (s.size - 1)
            val i = Math.floor(pos).toInt()
            val f = pos - i
            return if (i + 1 < s.size) s[i] + (s[i + 1] - s[i]) * f else s[i]
        }
        val lo = if (s.size >= 20) q(0.05) else s.first()
        val hi = if (s.size >= 20) q(0.95) else s.last()
        val third = (hi - lo) / 3.0
        fun round(v: Double) = Math.round(v / step) * step
        val mid = round(lo + third)
        var high = round(lo + 2 * third)
        if (high <= mid) high = mid + step
        fun tier(f: (Double) -> Boolean): Tier {
            val l = values.filter(f)
            return Tier(Math.round(l.size * 100f / values.size), l.size, l.takeIf { it.isNotEmpty() }?.average())
        }
        return Ref(values.size, mid, high, tier { it < mid }, tier { it >= mid && it < high }, tier { it >= high })
    }
}
