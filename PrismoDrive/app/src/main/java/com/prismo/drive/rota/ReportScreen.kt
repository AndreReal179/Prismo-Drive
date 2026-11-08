package com.prismo.drive.rota

import com.prismo.drive.R
import com.prismo.drive.BuildConfig

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar
import java.util.Locale

/**
 * Aba "Relatório": tudo que o Assistente de Rotas analisou, com filtro Dia / Semana / Mês,
 * 3 gráficos (valor total, R$/km e valor por pedido), comparação com o período anterior
 * e histórico dos meses guardados (mês atual + 2 anteriores).
 */
class ReportScreen(private val act: Activity) {

    private enum class Per { DAY, WEEK, MONTH }

    private var per = Per.DAY
    private var app = 0                      // 0 = todos, 1 = iFood, 2 = InDrive
    private lateinit var box: LinearLayout
    private val BR = Locale("pt", "BR")

    private val BG = 0xFF080C10.toInt()
    private val CARD = 0xFF0D1117.toInt()
    private val CARD2 = 0xFF141A20.toInt()
    private val LINE = 0xFF21262D.toInt()
    private val TXT = 0xFFFFFFFF.toInt()
    private val MUTED = 0xFF8B949E.toInt()
    private val GOLD = 0xFF4ADE80.toInt()      // destaque (roxo da marca)
    private val GREEN = 0xFF4CAF50.toInt()
    private val AMBER = 0xFFFFB300.toInt()
    private val RED = 0xFFEF5350.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private val MONTHS = arrayOf(
        "Janeiro", "Fevereiro", "Março", "Abril", "Maio", "Junho",
        "Julho", "Agosto", "Setembro", "Outubro", "Novembro", "Dezembro"
    )

    // ------------------------------------------------------------------ helpers de UI
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun tx(t: CharSequence, sp: Float, color: Int, bold: Boolean = false) = TextView(act).apply {
        text = t
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(CARD, 18, LINE)
    }

    private fun m2(v: Double) = String.format(BR, "R$ %.2f", v)
    private fun rpk2(v: Double) = String.format(BR, "R$ %.2f/km", v)

    // ------------------------------------------------------------------ dados
    private class S(val n: Int, val price: Double?, val rpk: Double?, val per: Double?)

    private fun avg(l: List<Double>): Double? = if (l.isEmpty()) null else l.average()

    private fun stats(l: List<Rec>) =
        S(l.size, avg(l.map { it.price }), avg(l.mapNotNull { it.rpk }), avg(l.mapNotNull { it.per }))

    private fun matchApp(r: Rec) = when (app) {
        1 -> r.route
        2 -> !r.route
        else -> true
    }

    /** [início, fim) do período; back = 0 atual, 1 anterior. */
    private fun range(p: Per, back: Int): LongArray {
        val c = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        when (p) {
            Per.DAY -> c.add(Calendar.DAY_OF_YEAR, -back)
            Per.WEEK -> { c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY); c.add(Calendar.WEEK_OF_YEAR, -back) }
            Per.MONTH -> { c.set(Calendar.DAY_OF_MONTH, 1); c.add(Calendar.MONTH, -back) }
        }
        val start = c.timeInMillis
        when (p) {
            Per.DAY -> c.add(Calendar.DAY_OF_YEAR, 1)
            Per.WEEK -> c.add(Calendar.WEEK_OF_YEAR, 1)
            Per.MONTH -> c.add(Calendar.MONTH, 1)
        }
        return longArrayOf(start, c.timeInMillis)
    }

    private fun dayMonth(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return String.format(BR, "%02d %s", c.get(Calendar.DAY_OF_MONTH), MONTHS[c.get(Calendar.MONTH)].take(3).lowercase())
    }

    private fun periodTitle(r: LongArray): String = when (per) {
        Per.DAY -> "Hoje · " + dayMonth(r[0])
        Per.WEEK -> "Semana · " + dayMonth(r[0]) + " a " + dayMonth(r[1] - 1)
        Per.MONTH -> {
            val c = Calendar.getInstance().apply { timeInMillis = r[0] }
            MONTHS[c.get(Calendar.MONTH)] + " de " + c.get(Calendar.YEAR)
        }
    }

    private fun periodLabel() = when (per) {
        Per.DAY -> "DIA"
        Per.WEEK -> "SEMANA"
        Per.MONTH -> "MÊS"
    } + " · " + when (app) {
        1 -> "IFOOD"
        2 -> "INDRIVE"
        else -> "TODOS OS APPS"
    }

    private fun vsLabel() = when (per) {
        Per.DAY -> "ontem"
        Per.WEEK -> "semana passada"
        Per.MONTH -> "mês passado"
    }

    // ------------------------------------------------------------------ construção
    fun build(container: LinearLayout) {
        box = container
        render()
    }

    fun refresh() {
        if (::box.isInitialized) render()
    }

    private fun render() {
        box.removeAllViews()
        val all = ReportStore.load(act)
        val cur = range(per, 0)
        val prv = range(per, 1)
        val a = all.filter { it.t >= cur[0] && it.t < cur[1] && matchApp(it) }
        val b = all.filter { it.t >= prv[0] && it.t < prv[1] && matchApp(it) }
        val sa = stats(a)
        val sb = stats(b)

        // cabeçalho
        box.addView(tx("Tudo o que o Assistente de Rotas analisou, para você saber quanto as rotas realmente pagam.", 13f, MUTED), lp(0))

        // filtros: período (controle segmentado) + app (chips)
        box.addView(
            segmented(listOf("Dia", "Semana", "Mês"), per.ordinal) { per = Per.values()[it]; render() },
            lp(16)
        )
        box.addView(
            appChips(
                listOf(Triple("Todos", null, 0), Triple("iFood", 0xFFEA1D2C.toInt(), 1), Triple("InDrive", 0xFFB5E61D.toInt(), 2)),
                app
            ) { app = it; render() },
            lp(10)
        )

        // linha do período: título + contador discreto
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titleCol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(tx(periodLabel(), 10.5f, MUTED, true).apply { letterSpacing = 0.12f })
        titleCol.addView(tx(periodTitle(cur), 16f, TXT, true), lp(2))
        head.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(chip("${a.size} rota" + (if (a.size == 1) "" else "s"), CARD2, MUTED).apply {
            background = rounded(CARD2, 20, LINE)
        })
        box.addView(View(act).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = dp(16) })
        box.addView(head, lp(14))

        if (a.isEmpty()) {
            box.addView(emptyCard(all.isEmpty()), lp(12))
        } else {
            box.addView(kpis(sa, sb), lp(12))
        }

        // gráficos — as faixas (baixo / médio / alto) são automáticas: calculadas com TODAS as rotas
        // já guardadas (do mesmo app do filtro), e se ajustam a cada nova rota que chega
        val vs = vsLabel()
        val baseRecs = all.filter { matchApp(it) }
        val since = baseRecs.minOfOrNull { it.t }
        box.addView(
            chartCard(
                "Valor total", "Quanto as rotas pagam no bruto",
                a.map { it.price }, b.map { it.price },
                autoBands(baseRecs.map { it.price }, 1.0),
                { String.format(BR, "R$ %.2f", it) }, { String.format(BR, "R$ %.0f", it) },
                "das rotas", "Nenhuma rota neste período.", vs, since, "rotas"
            ), lp(14)
        )
        box.addView(
            chartCard(
                "Valor por km", "R$ por km rodado (busca + corrida)",
                a.mapNotNull { it.rpk }, b.mapNotNull { it.rpk },
                autoBands(baseRecs.mapNotNull { it.rpk }, 0.05),
                { String.format(BR, "R$ %.2f", it) }, { String.format(BR, "R$ %.2f", it) },
                "das rotas", "Nenhuma rota neste período.", vs, since, "rotas"
            ), lp(12)
        )
        box.addView(
            chartCard(
                "Valor por pedido", "Valor bruto ÷ entregas (rotas do iFood)",
                a.mapNotNull { it.per }, b.mapNotNull { it.per },
                autoBands(baseRecs.mapNotNull { it.per }, 0.05),
                { String.format(BR, "R$ %.2f", it) }, { String.format(BR, "R$ %.2f", it) },
                "dos pedidos",
                if (app == 2) "Disponível só para rotas do iFood." else "Nenhuma rota do iFood neste período.", vs,
                since, "pedidos"
            ), lp(12)
        )

        box.addView(history(baseRecs), lp(14))
    }

    // ------------------------------------------------------------------ faixas automáticas
    /** Limites calculados com os valores que já entraram. */
    private class Bands(val mid: Double, val high: Double, val min: Double, val max: Double, val n: Int)

    /**
     * Divide os valores que já chegaram em 3 faixas iguais, do menor ao maior:
     * baixo = primeiro terço, médio = segundo terço, alto = último terço.
     * Com 20 ou mais valores, ignora os 5% mais extremos de cada ponta (uma corrida fora da curva
     * não estica as faixas). Os limites são arredondados (R$ 1 no valor total, R$ 0,05 nos demais).
     */
    private fun autoBands(values: List<Double>, step: Double): Bands? {
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
        if (high <= mid) high = mid + step          // valores todos iguais (ou quase): tudo cai em "médio"
        return Bands(mid, high, s.first(), s.last(), s.size)
    }

    // ------------------------------------------------------------------ peças
    private fun chip(t: String, bg: Int, fg: Int) = tx(t, 11.5f, fg, true).apply {
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = rounded(bg, 10)
    }

    /**
     * Controle segmentado (Dia | Semana | Mês): trilho escuro e discreto, opção ativa em "pílula"
     * levemente mais clara com um traço dourado fino embaixo. A pílula desliza ao trocar.
     */
    private fun segmented(labels: List<String>, sel: Int, onSel: (Int) -> Unit): View {
        val track = android.widget.FrameLayout(act).apply {
            background = rounded(0xFF0B0F14.toInt(), 12, LINE)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val pill = android.widget.FrameLayout(act).apply {
            background = rounded(0xFF1A2129.toInt(), 9, 0x14FFFFFF)
            elevation = dp(1).toFloat()
            addView(View(act).apply { background = rounded(GOLD, 2) },
                android.widget.FrameLayout.LayoutParams(dp(18), dp(2), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = dp(4)
                })
        }
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        labels.forEachIndexed { i, l ->
            val on = i == sel
            row.addView(tx(l, 13.5f, if (on) TXT else MUTED, on).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(10))
                setOnClickListener {
                    if (on) return@setOnClickListener
                    // desliza a pílula até a opção tocada e só então troca o conteúdo
                    val w = row.width / labels.size
                    pill.animate().translationX((w * i).toFloat()).setDuration(160)
                        .withEndAction { onSel(i) }.start()
                }
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        track.addView(pill, android.widget.FrameLayout.LayoutParams(0, MATCH))
        track.addView(row, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        // a pílula ocupa 1/N da largura; posiciona assim que o layout tiver tamanho
        row.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
            val w = (r - l) / labels.size
            val lpP = pill.layoutParams
            if (lpP.width != w) {
                lpP.width = w
                pill.layoutParams = lpP
                pill.translationX = (w * sel).toFloat()
            }
        }
        return track
    }

    /** Filtro de app em chips discretos: contorno fino, ativo com fundo sutil e bolinha da cor do app. */
    private fun appChips(items: List<Triple<String, Int?, Int>>, sel: Int, onSel: (Int) -> Unit): View {
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        items.forEachIndexed { idx, (label, color, value) ->
            val on = value == sel
            val chip = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(7), dp(14), dp(7))
                background = if (on) rounded(0xFF1A2129.toInt(), 20, 0x6622C55E) else rounded(0x00000000, 20, LINE)
                if (color != null) addView(View(act).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(if (on) color else (color and 0x00FFFFFF) or 0x99000000.toInt())
                    }
                }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(7) })
                addView(tx(label, 12.5f, if (on) TXT else MUTED, on))
                setOnClickListener { if (!on) onSel(value) }
            }
            row.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (idx > 0) leftMargin = dp(8) })
        }
        return row
    }

    private fun emptyCard(noData: Boolean): View {
        val c = card()
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(tx("📊", 30f, TXT).apply { gravity = Gravity.CENTER })
        c.addView(
            tx(if (noData) "Ainda não há rotas no relatório" else "Nenhuma rota neste período", 15f, TXT, true)
                .apply { gravity = Gravity.CENTER }, lp(8)
        )
        c.addView(
            tx(
                if (noData) "Com o serviço ativo, abra o app de corridas. Cada rota analisada é guardada aqui automaticamente."
                else "Troque o filtro (Dia, Semana ou Mês) para ver outros períodos.",
                13f, MUTED
            ).apply { gravity = Gravity.CENTER }, lp(4)
        )
        return c
    }

    /** ▲ / ▼ com a diferença (sem texto de comparação). */
    private fun diff(curV: Double, prevV: Double, fmt: (Double) -> String): Pair<String, Int> {
        val d = curV - prevV
        return when {
            Math.abs(d) < 0.005 -> Pair("= igual", MUTED)
            d > 0 -> Pair("▲ " + fmt(d), GREEN)
            else -> Pair("▼ " + fmt(-d), RED)
        }
    }

    /** ▲ / ▼ com a diferença para o período anterior. */
    private fun delta(curV: Double?, prevV: Double?, fmt: (Double) -> String, vs: String): Pair<String, Int> {
        if (curV == null || prevV == null) return Pair("sem dados $vs", MUTED)
        val r = diff(curV, prevV, fmt)
        return Pair(if (r.first.startsWith("=")) "= igual a $vs" else r.first + " vs $vs", r.second)
    }

    /**
     * Cartões de resumo (2 × 2): selo colorido com ícone, rótulo discreto, valor grande e
     * a variação para o período anterior numa "pílula" verde/vermelha.
     */
    private fun kpis(a: S, b: S): View {
        val vs = vsLabel()
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        fun tile(icon: String, accent: Int, label: String, value: String, sub: Pair<String, Int>) =
            LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(CARD, 16, LINE)
                val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                top.addView(tx(icon, 11f, accent, true).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    background = rounded((accent and 0x00FFFFFF) or 0x24000000, 8)
                }, LinearLayout.LayoutParams(dp(26), dp(26)).apply { rightMargin = dp(8) })
                top.addView(tx(label.uppercase(BR), 10f, MUTED, true).apply {
                    letterSpacing = 0.06f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(top)
                addView(tx(value, 21f, TXT, true).apply { maxLines = 1 }, lp(10))
                // variação em pílula (verde sobe, vermelho desce, cinza igual/sem dados)
                val pillBg = if (sub.second == MUTED) CARD2 else (sub.second and 0x00FFFFFF) or 0x1F000000
                addView(LinearLayout(act).apply {
                    addView(tx(sub.first, 10.5f, sub.second, true).apply {
                        setPadding(dp(8), dp(3), dp(8), dp(3))
                        background = rounded(pillBg, 10)
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    })
                }, lp(8))
            }

        fun row(l: View, r: View) = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(l, LinearLayout.LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(5) })
            addView(r, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = dp(5) })
        }

        val diffN = a.n - b.n
        val nSub = when {
            b.n == 0 && a.n == 0 -> Pair("sem dados $vs", MUTED)
            diffN == 0 -> Pair("= igual a $vs", MUTED)
            diffN > 0 -> Pair("▲ $diffN vs $vs", GREEN)
            else -> Pair("▼ ${-diffN} vs $vs", RED)
        }
        col.addView(row(
            tile("✓", GOLD, "Rotas analisadas", a.n.toString(), nSub),
            tile("R$", GREEN, "Valor médio", a.price?.let { m2(it) } ?: "—", delta(a.price, b.price, { m2(it) }, vs))
        ), lp(0))
        col.addView(row(
            tile("km", 0xFF42A5F5.toInt(), "R$/km médio", a.rpk?.let { m2(it) } ?: "—", delta(a.rpk, b.rpk, { m2(it) }, vs)),
            tile("▤", 0xFF4ADE80.toInt(), "Por pedido", a.per?.let { m2(it) } ?: "—", delta(a.per, b.per, { m2(it) }, vs))
        ), lp(10))
        return col
    }

    // ------------------------------------------------------------------ gráfico
    private class Tier(val name: String, val range: String, val color: Int, val count: Int, val avg: String?)

    private fun chartCard(
        title: String, sub: String,
        values: List<Double>, prevValues: List<Double>,
        bands: Bands?,
        fmtAvg: (Double) -> String, fmtLimit: (Double) -> String,
        what: String, emptyMsg: String, vs: String,
        since: Long?, unit: String
    ): View {
        val c = card()
        c.addView(tx(title, 17f, TXT, true))
        c.addView(tx(sub, 12.5f, MUTED), lp(2))

        if (values.isEmpty() || bands == null) {
            c.addView(tx(emptyMsg, 13f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(10)) }, lp(0))
            return c
        }
        val mid = bands.mid
        val high = bands.high

        val n = values.size
        val hi = values.count { it >= high }
        val md = values.count { it >= mid && it < high }
        val lo = n - hi - md
        // valor médio de cada faixa (ex.: 44% das rotas ficam em torno de R$ 10,00)
        fun avgOf(f: (Double) -> Boolean) = values.filter(f).takeIf { it.isNotEmpty() }?.average()?.let { "≈ " + fmtAvg(it) }
        val tiers = listOf(
            Tier("Alto", fmtLimit(high) + " ou mais", GREEN, hi, avgOf { it >= high }),
            Tier("Médio", fmtLimit(mid) + " a " + fmtLimit(high), AMBER, md, avgOf { it >= mid && it < high }),
            Tier("Baixo", "abaixo de " + fmtLimit(mid), RED, lo, avgOf { it < mid })
        )
        // o mais comum (em empate, o de maior valor)
        val top = tiers.indices.maxByOrNull { tiers[it].count * 10 + (2 - it) } ?: 0
        val pct = tiers.map { Math.round(it.count * 100f / n) }

        // rosca + legenda
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val donut = DonutView(act).apply {
            fracs = floatArrayOf(hi.toFloat() / n, md.toFloat() / n, lo.toFloat() / n)
            colors = intArrayOf(GREEN, AMBER, RED)
            big = "${pct[top]}%"
            bigColor = tiers[top].color
            small = tiers[top].avg ?: tiers[top].name.lowercase()
            animateIn()
        }
        row.addView(donut, LinearLayout.LayoutParams(dp(124), dp(124)))

        val legend = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        tiers.forEachIndexed { i, t ->
            legend.addView(tierRow(t, pct[i], i == top), lp(if (i == 0) 0 else 6))
        }
        row.addView(legend, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(14) })
        c.addView(row, lp(14))

        // frase de destaque (ex.: "78% das rotas pagam R$ 40 ou mais")
        val t = tiers[top]
        val phrase = when (top) {
            0 -> "${pct[top]}% $what com ${fmtLimit(high)} ou mais"
            1 -> "${pct[top]}% $what entre ${fmtLimit(mid)} e ${fmtLimit(high)}"
            else -> "${pct[top]}% $what abaixo de ${fmtLimit(mid)}"
        } + (t.avg?.let { " (em média ${it.removePrefix("≈ ")})" } ?: "")
        val sp = SpannableStringBuilder(phrase)
        val e = phrase.indexOf('%') + 1
        sp.setSpan(ForegroundColorSpan(t.color), 0, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(StyleSpan(Typeface.BOLD), 0, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        c.addView(
            tx(sp, 13.5f, TXT).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(CARD2, 12)
            }, lp(14)
        )

        // média / menor / maior
        val av = values.average()
        val dl = delta(av, prevValues.takeIf { it.isNotEmpty() }?.average(), fmtAvg, vs)
        val stats = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        fun cell(label: String, v: String, color: Int = TXT) = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(tx(label, 11.5f, MUTED))
            addView(tx(v, 15f, color, true), lp(2))
        }
        stats.addView(cell("Média", fmtAvg(av)), LinearLayout.LayoutParams(0, WRAP, 1f))
        stats.addView(cell("Menor", fmtAvg(values.min())), LinearLayout.LayoutParams(0, WRAP, 1f))
        stats.addView(cell("Maior", fmtAvg(values.max())), LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(stats, lp(14))
        c.addView(tx("Média ${dl.first}", 11.5f, dl.second), lp(8))

        // de onde vieram as faixas
        val desde = since?.let { " desde ${dayMonth(it)}" } ?: ""
        val note = "Faixas automáticas: calculadas com ${bands.n} $unit$desde " +
            "(de ${fmtAvg(bands.min)} a ${fmtAvg(bands.max)}). Se ajustam a cada nova rota." +
            if (bands.n < 10) " Ainda com poucos dados: ficam mais precisas com o uso." else ""
        c.addView(
            tx("⚙ $note", 11f, MUTED).apply {
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(CARD2, 10)
            }, lp(10)
        )
        return c
    }

    private fun tierRow(t: Tier, pct: Int, top: Boolean): View {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(7))
            if (top) background = rounded(CARD2, 10, t.color)
        }
        val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val dot = View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(t.color) } }
        line.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(7) })
        val names = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        names.addView(tx(t.name + if (top) "  ★" else "", 13f, TXT, true))
        names.addView(tx(t.range, 10.5f, MUTED))
        line.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f))
        val right = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        right.addView(tx("$pct%", if (top) 17f else 15f, if (top) t.color else TXT, true).apply { gravity = Gravity.END })
        if (t.avg != null) right.addView(tx(t.avg, 12f, if (top) t.color else TXT, true).apply { gravity = Gravity.END })
        right.addView(tx("${t.count} rota" + (if (t.count == 1) "" else "s"), 10.5f, MUTED).apply { gravity = Gravity.END })
        line.addView(right)
        col.addView(line)
        col.addView(bar(pct, t.color, 5), lp(5))
        return col
    }

    /** Barra de progresso fina (pct de 0 a 100). */
    private fun bar(pct: Int, color: Int, h: Int): View {
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(0xFF1B222A.toInt(), h)
        }
        val p = pct.coerceIn(0, 100)
        if (p > 0) r.addView(View(act).apply { background = rounded(color, h) }, LinearLayout.LayoutParams(0, dp(h), p.toFloat()))
        if (p < 100) r.addView(View(act), LinearLayout.LayoutParams(0, dp(h), (100 - p).toFloat()))
        return r
    }

    // ------------------------------------------------------------------ histórico mensal
    private fun history(all: List<Rec>): View {
        val c = card()
        c.addView(tx("Histórico mensal", 17f, TXT, true))
        c.addView(tx("Compare os meses guardados (mês atual + 2 anteriores)", 12.5f, MUTED), lp(2))

        val groups: Map<Int, List<Rec>> = all.groupBy { ReportStore.monthIdx(it.t) }
        if (groups.isEmpty()) {
            c.addView(tx("Ainda sem dados guardados.", 13f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, dp(8)) }, lp(0))
        } else {
            val idxs = groups.keys.sortedDescending()
            val st = idxs.map { stats(groups[it]!!) }
            val maxRpk = st.mapNotNull { it.rpk }.maxOrNull() ?: 1.0
            val curIdx = ReportStore.monthIdx(System.currentTimeMillis())
            idxs.forEachIndexed { i, idx ->
                val s = st[i]
                val older = st.getOrNull(i + 1)      // mês anterior (mais antigo) para comparar
                val box = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = rounded(CARD2, 14, if (idx == curIdx) GOLD else null)
                }
                val h = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                h.addView(
                    tx(MONTHS[idx % 12] + " " + (idx / 12), 14.5f, TXT, true),
                    LinearLayout.LayoutParams(0, WRAP, 1f)
                )
                h.addView(tx("${s.n} rotas", 11.5f, MUTED))
                if (idx == curIdx) h.addView(chip("ATUAL", GOLD, 0xFFFFFFFF.toInt()), LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
                box.addView(h)

                val m = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
                fun cell(label: String, v: Double?, o: Double?) = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(tx(label, 10.5f, MUTED))
                    addView(tx(v?.let { m2(it) } ?: "—", 14f, TXT, true), lp(2))
                    if (older != null && v != null && o != null) {
                        val d = diff(v, o, { m2(it) })
                        addView(tx(d.first, 10.5f, d.second), lp(1))
                    }
                }
                m.addView(cell("Valor médio", s.price, older?.price), LinearLayout.LayoutParams(0, WRAP, 1f))
                m.addView(cell("R$/km", s.rpk, older?.rpk), LinearLayout.LayoutParams(0, WRAP, 1f))
                m.addView(cell("Por pedido", s.per, older?.per), LinearLayout.LayoutParams(0, WRAP, 1f))
                box.addView(m, lp(10))
                s.rpk?.let { box.addView(bar(Math.round(it / maxRpk * 100).toInt(), GOLD, 4), lp(10)) }
                c.addView(box, lp(if (i == 0) 14 else 10))
            }
            if (idxs.size >= 2) {
                c.addView(tx("▲ ▼ comparam cada mês com o anterior.", 11f, MUTED), lp(10))
            }
        }
        c.addView(
            tx(
                "Os dados ficam só neste celular. Quando começa o 4º mês, o mês mais antigo é apagado para sempre e deixa de ocupar espaço.",
                11f, MUTED
            ), lp(10)
        )
        return c
    }

    // ------------------------------------------------------------------ rosca
    /** Gráfico de rosca: fatias alto (verde), médio (laranja) e baixo (vermelho), com animação de entrada. */
    private class DonutView(ctx: Context) : View(ctx) {
        var fracs = floatArrayOf(0f, 0f, 0f)
        var colors = intArrayOf(0, 0, 0)
        var big = ""
        var bigColor = 0xFFFFFFFF.toInt()
        var small = ""
        private var prog = 1f
        private val d = ctx.resources.displayMetrics.density
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF1B222A.toInt() }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
        private val bigP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val smallP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8B949E.toInt(); textAlign = Paint.Align.CENTER }
        private val rect = RectF()

        fun animateIn() {
            prog = 0f
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 800
                addUpdateListener { prog = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val sw = 15f * d
            track.strokeWidth = sw
            arc.strokeWidth = sw
            rect.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
            canvas.drawArc(rect, 0f, 360f, false, track)
            val nonZero = fracs.count { it > 0f }
            val gap = if (nonZero > 1) 3f else 0f
            var start = -90f
            for (i in fracs.indices) {
                val full = fracs[i] * 360f
                if (full <= 0f) continue
                val sweep = ((full - gap) * prog).coerceAtLeast(0f)
                arc.color = colors[i]
                canvas.drawArc(rect, start + gap / 2, sweep, false, arc)
                start += full
            }
            bigP.color = bigColor
            bigP.textSize = 24f * d
            smallP.textSize = 11f * d
            canvas.drawText(big, w / 2, h / 2 + 6f * d, bigP)
            canvas.drawText(small, w / 2, h / 2 + 22f * d, smallP)
        }
    }
}
