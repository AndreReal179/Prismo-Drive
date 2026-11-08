package com.prismo.drive.rota

import com.prismo.drive.R
import com.prismo.drive.BuildConfig

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Painel premium de avaliação. Usa TYPE_ACCESSIBILITY_OVERLAY (não precisa de "Exibir sobre outros apps").
 *
 *  Lista   : um painel substitui as duas primeiras linhas de CADA card (R$/km e preço, que o painel
 *            já mostra), deixando o endereço visível. A camada deixa os toques passarem para o app.
 *  Detalhe : painel no topo. Toque curto expande/recolhe, toque longo abre as configurações.
 */
class Overlay(private val svc: AccessibilityService) {

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = Prefs(svc)
    private val BR = Locale("pt", "BR")
    private var bar: LinearLayout? = null      // modo detalhe
    private var layer: FrameLayout? = null     // modo lista
    private var expanded = true
    private var last: List<Result> = emptyList()
    private var lastSig = ""
    private val hideRunnable = Runnable { remove() }

    val isShowing: Boolean get() = bar != null || layer != null

    private val GOLD = 0xFF22C55E.toInt()      // destaque no roxo da marca
    private val MUTED = 0xFFA9AEB8.toInt()
    private val BASE = 0xFF080C10.toInt()

    private fun dp(v: Int) = (v * svc.resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * svc.resources.displayMetrics.density

    fun show(results: List<Result>) {
        handler.removeCallbacks(hideRunnable)
        hidePending = false
        last = results
        // não redesenha se nada mudou (evita piscar com a releitura periódica)
        val sig = results.joinToString("|") {
            "${it.offer.price}/${it.offer.headTop}/${it.offer.placeTop}/${it.offer.placeAbove}/${it.effRpk}/" +
                "${it.offer.pickupKm}/${it.offer.rating}/${it.offer.entregas}"
        } + expanded
        if (sig == lastSig && isShowing) return
        lastSig = sig
        render()
    }

    private var hidePending = false

    /** Esconde em ~1,2 s. Não reinicia a contagem a cada chamada (antes a releitura periódica adiava para sempre). */
    fun hideSoon() {
        if (hidePending) return
        hidePending = true
        handler.postDelayed(hideRunnable, 1200)
    }

    /** Chamado quando o painel sai da tela (o botão flutuante volta ao estado neutro). */
    var onHidden: (() -> Unit)? = null

    fun remove() {
        hidePending = false
        handler.removeCallbacks(hideRunnable)
        if (isShowing) runCatching { onHidden?.invoke() }
        bar?.let { runCatching { wm.removeView(it) } }
        layer?.let { runCatching { wm.removeView(it) } }
        bar = null
        layer = null
        lastSig = ""
    }

    private fun render() {
        if (last.isEmpty()) return
        val best = Evaluator.best(last)
        val listMode = last.all { it.offer.headTop != null }
        runCatching {
            if (listMode) {
                bar?.let { runCatching { wm.removeView(it) }; bar = null }
                renderList(best)
            } else {
                layer?.let { runCatching { wm.removeView(it) }; layer = null }
                renderBar(best)
            }
        }
    }

    // ---------------------------------------------------------------- lista
    private fun ensureLayer(): FrameLayout {
        layer?.let { return it }
        val f = FrameLayout(svc)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(f, lp)
        layer = f
        return f
    }

    private fun renderList(best: Result?) {
        val f = ensureLayer()
        f.removeAllViews()
        val multi = last.size > 1
        val width = svc.resources.displayMetrics.widthPixels - dp(66) - dp(40)
        for (r in last) {
            val route = r.offer.route
            val p = if (route) routePanel(r, r === best && multi) else panel(r, r === best && multi)
            val w = if (route) svc.resources.displayMetrics.widthPixels - dp(40) else width
            val above = r.offer.placeAbove
            val lp = FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START).apply {
                leftMargin = if (route) dp(20) else dp(66)
                topMargin = when {
                    above != null -> above                                // ajustado depois de medir (abaixo)
                    r.offer.placeTop != null -> r.offer.placeTop - dp(2)  // em cima do endereço da coleta
                    else -> (r.offer.headTop ?: 0) - dp(3)
                }
            }
            // detalhe/popup: o painel termina logo acima do valor (no vão acima do card do valor)
            p.tag = if (above != null) "above" else null
            f.addView(p, lp)
        }
        // corrige a diferença entre coordenadas da tela e da janela (barra de status etc.)
        f.post {
            val loc = IntArray(2)
            f.getLocationOnScreen(loc)
            for (i in 0 until f.childCount) {
                val c = f.getChildAt(i)
                val lift = if (c.tag == "above") c.height + dp(26) else 0
                c.translationY = (-loc[1] - lift).toFloat()
            }
        }
    }

    // -------------------------------------------------------------- detalhe
    private fun ensureBar(): LinearLayout {
        bar?.let { return it }
        val b = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(2))
            setOnClickListener { expanded = !expanded; lastSig = ""; render() }
            setOnLongClickListener {
                svc.startActivity(
                    Intent(svc, RotasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = dp(78)
        }
        wm.addView(b, lp)
        bar = b
        return b
    }

    private fun renderBar(best: Result?) {
        val b = ensureBar()
        b.removeAllViews()
        val rows = if (expanded) last else listOfNotNull(best ?: last.first())
        for (r in rows) {
            val v = panel(r, r === best && last.size > 1)
            v.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
            b.addView(v)
        }
    }

    // ---------------------------------------------------------------- painel
    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        val r = ch((a shr 16) and 0xFF, (b shr 16) and 0xFF)
        val g = ch((a shr 8) and 0xFF, (b shr 8) and 0xFF)
        val bl = ch(a and 0xFF, b and 0xFF)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    private fun tv(t: CharSequence, sp: Float, c: Int, bold: Boolean = false) = TextView(svc).apply {
        text = t
        textSize = sp
        setTextColor(c)
        includeFontPadding = false
        maxLines = 1
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun chip(t: String, bg: Int, fg: Int) = tv(t, 9.5f, fg, true).apply {
        setPadding(dp(6), dp(2), dp(6), dp(2))
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dpf(6f) }
    }

    /**
     *  ┌──────────────────────────────────────────────────┐
     *  │ (anel)   [ÓTIMO] ★ MELHOR                 R$ 42  │
     *  │ (R$/km)  R$ 28,16 líquido                 gas. R$ 10 │
     *  │          42 km · busca 1,2 · ★4,97       [PEÇA R$ 78] │
     *  └──────────────────────────────────────────────────┘
     */
    private fun panel(r: Result, isBest: Boolean): View {
        val color = r.level?.color ?: 0xFF6B7280.toInt()
        val frac = ((r.effRpk ?: 0.0) / prefs.otimo.toDouble()).coerceIn(0.0, 1.0).toFloat()
        val ring = Ring(
            svc, color, frac,
            r.effRpk?.let { String.format(BR, "%.2f", it) } ?: "—",
            svc.resources.displayMetrics.density
        )

        // ---- coluna do meio
        val rowA = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chip(Evaluator.labelOf(r), Evaluator.labelColor(r), 0xFFFFFFFF.toInt()))
            // nota do passageiro junto da classificação (cor pela nota: verde / laranja / vermelho)
            r.offer.rating?.let {
                addView(tv("  ★" + String.format(BR, "%.2f", it), 11f, Evaluator.ratingColor(it), true))
            }
            if (isBest) addView(tv("  ★ MELHOR", 9.5f, GOLD, true))
        }
        val net = SpannableStringBuilder()
        if (r.net != null) {
            net.append(Evaluator.money(r.net))
            net.setSpan(ForegroundColorSpan(Evaluator.netColor(r)), 0, net.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else net.append("—")
        val rowB = tv(net, 20f, 0xFFFFFFFF.toInt(), true)

        val sb = SpannableStringBuilder()
        fun add(t: String, c: Int) {
            if (sb.isNotEmpty()) sb.append("  ·  ")
            val st = sb.length
            sb.append(t)
            sb.setSpan(ForegroundColorSpan(c), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Evaluator.totalKmText(r)?.let { add(it, MUTED) }
        // onde era "busca": tempo total (busca + viagem) e tempo até o destino final.
        // Na lista o app não mostra tempos, então mantém a distância de busca.
        val timeTxt = Evaluator.timeText(r)
        if (timeTxt != null) add(timeTxt, 0xFFFFFFFF.toInt())
        else r.offer.pickupKm?.let { add("busca " + String.format(BR, "%.1f", it), Evaluator.pickupColor(it, prefs)) }
        val rowC = tv(sb, 11f, MUTED).apply {
            ellipsize = TextUtils.TruncateAt.END
            setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, TypedValue.COMPLEX_UNIT_SP)
        }

        val mid = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            addView(rowA)
            addView(rowB, lpTop(2))
            addView(rowC, lpTop(2))
        }

        // ---- coluna da direita
        val right = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(tv(Evaluator.money(r.offer.price), 16f, 0xFFFFFFFF.toInt(), true))
            r.fuel?.let { addView(tv("gasolina " + Evaluator.money(it), 10f, MUTED), lpTop(2)) }
            if (r.suggest != null) {
                addView(chip("PEÇA " + Evaluator.money(r.suggest), GOLD, 0xFFFFFFFF.toInt()), lpTop(3))
            } else if (r.effRpk != null) {
                addView(tv("✓ no mínimo", 10f, Evaluator.GREEN, true), lpTop(3))
            }
        }

        return LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(10), dp(6))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(blend(BASE, color, 0.30f), BASE, BASE)
            ).apply {
                cornerRadius = dpf(14f)
                if (isBest) setStroke(dp(2), GOLD) else setStroke(dp(1), blend(BASE, color, 0.65f))
            }
            addView(ring, LinearLayout.LayoutParams(dp(50), dp(50)))
            addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(8); rightMargin = dp(8) })
            addView(right, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * Painel compacto p/ cards de "Rotas disponíveis": só cobre a linha de preço/km do card
     * (a camada não recebe toques, então "Gerar Rota" continua funcionando normalmente).
     */
    private fun routePanel(r: Result, isBest: Boolean): View {
        val color = r.level?.color ?: 0xFF6B7280.toInt()
        val bright = when (r.level) {
            Level.OTIMO -> 0xFF4CD27C.toInt()
            Level.MEDIO -> 0xFFFFB300.toInt()
            else -> 0xFFEF5350.toInt()
        }
        val row1 = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(chip(Evaluator.labelOf(r), Evaluator.labelColor(r), 0xFFFFFFFF.toInt()))
            if (isBest) addView(tv(" ★", 12f, GOLD, true))
            addView(tv("  " + Evaluator.perKm(r), 17f, bright, true))
            addView(View(svc), LinearLayout.LayoutParams(0, 1, 1f))
            if (r.net != null) {
                addView(tv(Evaluator.money(r.net), 14f, Evaluator.netColor(r), true))
            }
        }
        val sb = SpannableStringBuilder()
        fun add(t: String) {
            if (sb.isNotEmpty()) sb.append("  ·  ")
            sb.append(t)
        }
        add(Evaluator.money(r.offer.price))
        r.totalKm?.let { add(String.format(BR, "%.2f km", it)) }
        r.totalMin?.let { add(Evaluator.minText(it)) }
        r.fuel?.let { add("gas. " + Evaluator.money(it)) }
        val pedidos = r.offer.entregas
        if (pedidos < 2) r.offer.stops?.let { add(it) }
        val left = tv(sb, 10.5f, MUTED).apply {
            ellipsize = TextUtils.TruncateAt.END
            setAutoSizeTextTypeUniformWithConfiguration(7, 11, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        // vários pedidos: divide o valor bruto entre as entregas
        val row2 = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (pedidos >= 2 || r.belowMinOrder) {
                val each = r.perOrder ?: (r.offer.price / pedidos.coerceAtLeast(1))
                var txt = if (pedidos >= 2) String.format(BR, "%d pedidos · %s cada", pedidos, Evaluator.money(each))
                else "1 pedido · " + Evaluator.money(each)
                if (r.belowMinOrder) txt += " · mín. " + Evaluator.money(prefs.ifoodMinPerOrder.toDouble())
                addView(
                    if (r.belowMinOrder) chip(txt, 0xFFC62828.toInt(), 0xFFFFFFFF.toInt())
                    else chip(txt, GOLD, 0xFFFFFFFF.toInt()),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .apply { leftMargin = dp(6) }
                )
            }
        }
        return LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(blend(BASE, color, 0.30f), BASE, BASE)
            ).apply {
                cornerRadius = dpf(12f)
                if (isBest) setStroke(dp(2), GOLD) else setStroke(dp(1), blend(BASE, color, 0.65f))
            }
            addView(row1)
            addView(row2, lpTop(1))
        }
    }

    private fun lpTop(v: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(v) }

    /** Anel de progresso: quanto do "ÓTIMO" a corrida atinge; no centro o R$/km. */
    private class Ring(
        ctx: Context, private val ringColor: Int, private val frac: Float,
        private val big: String, private val density: Float
    ) : View(ctx) {
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x33FFFFFF
        }
        private val prog = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = ringColor
        }
        private val bigP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val smallP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFA9AEB8.toInt(); textAlign = Paint.Align.CENTER
        }
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val sw = 4.5f * density
            track.strokeWidth = sw
            prog.strokeWidth = sw
            rect.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
            canvas.drawArc(rect, 135f, 270f, false, track)
            if (frac > 0f) canvas.drawArc(rect, 135f, 270f * frac, false, prog)
            bigP.textSize = 13f * density
            smallP.textSize = 7.5f * density
            canvas.drawText(big, w / 2, h / 2 + 2f * density, bigP)
            canvas.drawText("R$/km", w / 2, h / 2 + 12f * density, smallP)
        }
    }
}
