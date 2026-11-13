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
    private val GOLD_BG = 0xFFEAB308.toInt()   // "PEÇA" e borda da melhor oferta
    private val GOLD_TXT = 0xFFFACC15.toInt()
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
        val width = svc.resources.displayMetrics.widthPixels - dp(24)
        for (r in last) {
            val route = r.offer.route
            val p = if (route) routePanel(r, r === best && multi) else panel(r, r === best && multi)
            val w = if (route) svc.resources.displayMetrics.widthPixels - dp(40) else width
            val above = r.offer.placeAbove
            val lp = FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START).apply {
                leftMargin = if (route) dp(20) else dp(12)
                topMargin = when {
                    above != null -> above                                // ajustado depois de medir (abaixo)
                    r.offer.placeTop != null -> r.offer.placeTop - dp(2)  // em cima do endereço da coleta
                    else -> (r.offer.headTop ?: 0) - (if (route) dp(3) else dp(13))
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

    // ---------------------------------------------------------------- visual v1.9.8
    /** Cor viva do selo (anel, faixa e selo). */
    private fun vivid(r: Result): Int = when {
        Evaluator.badRating(r) -> 0xFFF97316.toInt()
        r.level == Level.OTIMO -> 0xFF22C55E.toInt()
        r.level == Level.MEDIO -> 0xFFF59E0B.toInt()
        r.level == Level.RUIM -> 0xFFEF4444.toInt()
        else -> 0xFF6B7280.toInt()
    }

    /** Cor do lucro (um tom mais claro do selo). */
    private fun soft(r: Result): Int = when {
        Evaluator.badRating(r) -> 0xFFFDBA74.toInt()
        r.level == Level.OTIMO -> 0xFF4ADE80.toInt()
        r.level == Level.MEDIO -> 0xFFFBBF24.toInt()
        r.level == Level.RUIM -> 0xFFFF8A80.toInt()
        else -> 0xFFD1D5DB.toInt()
    }

    private fun pill(t: CharSequence, bg: Int, fg: Int, sp: Float = 10.5f, stroke: Int? = null) = tv(t, sp, fg, true).apply {
        setPadding(dp(8), dp(3), dp(8), dp(3))
        letterSpacing = 0.04f
        background = GradientDrawable().apply {
            setColor(bg); cornerRadius = dpf(99f)
            stroke?.let { setStroke(dp(1), it) }
        }
    }

    /** Etiqueta com ícone: km, tempo, gasolina, busca. */
    private fun tag(icon: Int, t: String, iconColor: Int = 0xFFB9C4BE.toInt()) = tv(t, 10.5f, 0xFFC7D0CB.toInt(), true).apply {
        setPadding(dp(6), dp(3), dp(7), dp(3))
        val d = svc.getDrawable(icon)?.mutate()
        d?.setTint(iconColor)
        setCompoundDrawablesRelativeWithIntrinsicBounds(d, null, null, null)
        compoundDrawablePadding = dp(4)
        background = GradientDrawable().apply {
            setColor(0x0DFFFFFF); cornerRadius = dpf(8f); setStroke(dp(1), 0x12FFFFFF)
        }
    }

    private fun km1(v: Double) = String.format(BR, "%.1f km", v)

    /**
     * Painel v1.9.8 (InDrive e iFood):
     *  ┌──────────────────────────────────────────────────────┐
     *  │ (1,08)  [RUIM] ★4,34            LUCRO                │
     *  │ R$/KM   [18,6 km] [R$ 11,50] [2,7 km]   R$ 8,50      │
     *  │                                      [PEÇA R$ 37]    │
     *  └──────────────────────────────────────────────────────┘
     */
    private fun card(r: Result, isBest: Boolean, top: List<View>, tags: List<View>, compacto: Boolean = false): View {
        val c = vivid(r)
        val frac = ((r.effRpk ?: 0.0) / prefs.otimo.toDouble()).coerceIn(0.0, 1.0).toFloat()
        val ring = Ring(svc, c, frac, r.effRpk?.let { String.format(BR, "%.2f", it) } ?: "—",
            svc.resources.displayMetrics.density)

        val row1 = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val escuro = r.level == Level.OTIMO || r.level == Level.MEDIO
            addView(pill(Evaluator.labelOf(r), c, if (escuro && !Evaluator.badRating(r)) 0xFF07120B.toInt() else 0xFFFFFFFF.toInt(), 10.5f))
            for (v in top) addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(5) })
            if (isBest) addView(tv("★ MELHOR", 9.5f, GOLD_TXT, true).apply { letterSpacing = 0.08f },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
        }
        val row2 = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tags.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply { if (i > 0) leftMargin = dp(4) })
            }
        }
        val mid = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            addView(row1)
            addView(row2, lpTop(if (compacto) 4 else 6))
        }

        val right = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            if (!compacto) addView(tv("LUCRO", 8.5f, 0xFF7D8A83.toInt(), true).apply { letterSpacing = 0.14f })
            addView(tv(r.net?.let { (if (compacto) "lucro " else "") + Evaluator.money(it) } ?: "—",
                if (compacto) 15f else 18f, soft(r), true), lpTop(1))
            if (r.suggest != null) {
                addView(pill("PEÇA " + Evaluator.money(r.suggest), GOLD_BG, 0xFF1A1300.toInt(), if (compacto) 9.5f else 10f), lpTop(if (compacto) 3 else 4))
            } else if (r.effRpk != null) {
                addView(tv("✓ no mínimo", if (compacto) 9.5f else 10f, 0xFF4ADE80.toInt(), true), lpTop(if (compacto) 3 else 4))
            }
        }

        return LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = if (compacto) dp(48) else dp(66)
            if (compacto) setPadding(dp(7), dp(4), dp(10), dp(4)) else setPadding(dp(9), dp(7), dp(11), dp(7))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(blend(BASE, c, 0.26f), BASE, BASE)
            ).apply {
                cornerRadius = dpf(if (compacto) 14f else 18f)
                if (isBest) setStroke(dp(2), GOLD_BG) else setStroke(dp(1), blend(BASE, c, 0.5f))
            }
            val rs = if (compacto) dp(40) else dp(52)
            addView(ring, LinearLayout.LayoutParams(rs, rs))
            addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = if (compacto) dp(8) else dp(10); rightMargin = dp(6) })
            addView(right, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    /** InDrive (lista e detalhe): nota do passageiro, km total, tempo ou busca, gasolina. */
    private fun panel(r: Result, isBest: Boolean): View {
        val top = mutableListOf<View>()
        r.offer.rating?.let {
            val sb = SpannableStringBuilder("★ ")
            sb.setSpan(ForegroundColorSpan(Evaluator.ratingColor(it)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(String.format(BR, "%.2f", it))
            top.add(pill(sb, 0x12FFFFFF, 0xFFE6EDF3.toInt(), 11f))
        }
        // iFood (oferta que toca): vários pedidos na mesma rota
        if (r.offer.entregas >= 2) {
            val each = r.perOrder ?: (r.offer.price / r.offer.entregas)
            top.add(pill(String.format(BR, "%d pedidos · %s cada", r.offer.entregas, Evaluator.money(each)),
                if (r.belowMinOrder) 0x33EF4444 else 0x12FFFFFF,
                if (r.belowMinOrder) 0xFFFF8A80.toInt() else 0xFFFBBF24.toInt(), 10.5f))
        }
        val tags = mutableListOf<View>()
        r.totalKm?.let { tags.add(tag(R.drawable.ic_tag_estrada, (if (r.estimated) "~" else "") + km1(it))) }
        val tempo = Evaluator.timeText(r)
        if (tempo != null) tags.add(tag(R.drawable.ic_tag_relogio, tempo))
        else r.offer.pickupKm?.let { tags.add(tag(R.drawable.ic_tag_local, km1(it), Evaluator.pickupColor(it, prefs))) }
        r.fuel?.let { tags.add(tag(R.drawable.ic_tag_gasolina, Evaluator.money(it))) }
        return card(r, isBest, top, tags)
    }

    /** iFood "Rotas disponíveis": valor da rota (ou pedidos), km, tempo e gasolina. */
    private fun routePanel(r: Result, isBest: Boolean): View {
        val top = mutableListOf<View>()
        val pedidos = r.offer.entregas
        if (pedidos >= 2 || r.belowMinOrder) {
            val each = r.perOrder ?: (r.offer.price / pedidos.coerceAtLeast(1))
            var txt = if (pedidos >= 2) String.format(BR, "%d pedidos · %s cada", pedidos, Evaluator.money(each))
            else "1 pedido · " + Evaluator.money(each)
            if (r.belowMinOrder) txt += " · mín. " + Evaluator.money(prefs.ifoodMinPerOrder.toDouble())
            top.add(
                if (r.belowMinOrder) pill(txt, 0x33EF4444, 0xFFFF8A80.toInt(), 10.5f)
                else pill(txt, 0x12FFFFFF, 0xFFFBBF24.toInt(), 10.5f)
            )
        } else {
            top.add(pill(Evaluator.money(r.offer.price), 0x12FFFFFF, 0xFFE6EDF3.toInt(), 11f))
        }
        val tags = mutableListOf<View>()
        r.totalKm?.let { tags.add(tag(R.drawable.ic_tag_estrada, String.format(BR, "%.1f km", it))) }
        r.totalMin?.let { tags.add(tag(R.drawable.ic_tag_relogio, Evaluator.minText(it))) }
        r.fuel?.let { tags.add(tag(R.drawable.ic_tag_gasolina, Evaluator.money(it))) }
        return card(r, isBest, top, tags, compacto = true)
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
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x17FFFFFF
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
            val sw = 5f * density
            track.strokeWidth = sw
            prog.strokeWidth = sw
            rect.set(sw / 2, sw / 2, w - sw / 2, h - sw / 2)
            canvas.drawArc(rect, 0f, 360f, false, track)
            if (frac > 0f) canvas.drawArc(rect, -90f, 360f * frac, false, prog)
            val k = (w / (52f * density)).coerceIn(0.7f, 1.2f)
            bigP.textSize = 14f * density * k
            smallP.textSize = 7.5f * density * k
            smallP.isFakeBoldText = true
            canvas.drawText(big, w / 2, h / 2 + 2.5f * density * k, bigP)
            canvas.drawText("R$/KM", w / 2, h / 2 + 12f * density * k, smallP)
        }
    }
}
