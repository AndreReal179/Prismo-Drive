package com.prismo.drive.rota

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * v2.3 · Rota do turno com a taxa de entrega digitada no botão flutuante.
 * Estado em arquivo (turno_rota.json): o botão (processo :rotas) e a tela do app leem dali.
 */
object TurnoRota {
    private const val ARQ = "turno_rota.json"
    private fun arq(ctx: Context) = File((ctx.applicationContext ?: ctx).filesDir, ARQ)

    fun estado(ctx: Context): JSONObject? = runCatching {
        val f = arq(ctx)
        if (!f.exists()) null else JSONObject(f.readText()).takeIf { it.optBoolean("ativo") }
    }.getOrNull()

    fun ativa(ctx: Context) = estado(ctx) != null
    fun valor(ctx: Context) = estado(ctx)?.optDouble("valor", 0.0) ?: 0.0

    private fun gravar(ctx: Context, o: JSONObject) {
        runCatching {
            val f = arq(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) f.writeText(o.toString())
        }
    }

    /** Começa a rota com a taxa de entrega (só com turno ligado). */
    fun iniciar(ctx: Context, valor: Double): Boolean {
        val t = Turno.estado(ctx)
        if (!t.ativo || valor <= 0 || valor > 5000) return false
        gravar(ctx, JSONObject().put("ativo", true).put("valor", Math.round(valor * 100) / 100.0)
            .put("ini", System.currentTimeMillis()).put("kmIni", t.km))
        return true
    }

    fun corrigir(ctx: Context, valor: Double) {
        val o = estado(ctx) ?: return
        if (valor <= 0 || valor > 5000) return
        gravar(ctx, o.put("valor", Math.round(valor * 100) / 100.0))
    }

    fun cancelar(ctx: Context) { runCatching { arq(ctx).delete() } }

    /** Chegou no cliente: a rota entra no faturamento (fila da tela do app). Devolve o valor lançado. */
    fun finalizar(ctx: Context): Double {
        val o = estado(ctx) ?: return 0.0
        val v = o.optDouble("valor", 0.0)
        val ini = o.optLong("ini")
        val km = (Turno.estado(ctx).km - o.optDouble("kmIni", 0.0)).coerceAtLeast(0.0)
        val dur = (System.currentTimeMillis() - ini).coerceIn(0L, 5 * 3600_000L)
        cancelar(ctx)
        IfoodSaldo.enfileirarEntrega(ctx, v, ini, dur, km)
        return v
    }

    /** Para a tela do app (Turno.json). */
    fun json(ctx: Context): JSONObject? = estado(ctx)?.let {
        JSONObject().put("valor", it.optDouble("valor", 0.0)).put("ini", it.optLong("ini"))
            .put("km", Math.round((Turno.estado(ctx).km - it.optDouble("kmIni", 0.0)).coerceAtLeast(0.0) * 100) / 100.0)
    }
}

/**
 * v2.3 · Botão flutuante do turno (permissão "aparecer sobre outros apps"; não depende do assistente de rotas).
 * Vermelho = rodando sem rota · Verde = em rota. Toque: taxa de entrega → Iniciar rota → Finalizar rota.
 * Roda dentro do TurnoService (processo :rotas).
 */
class TurnoBolha(private val ctx: Context) {

    companion object {
        /** Instância viva (o botão do assistente usa para abrir o painel dentro do iFood/InDrive). */
        @Volatile var atual: TurnoBolha? = null
        @Volatile private var fabAte = 0L
        @Volatile private var vivaAte = 0L

        fun podeMostrar(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

        /** O botão do assistente está na tela (iFood/InDrive): este se esconde para não ficar dois. */
        fun marcarFab() { fabAte = SystemClock.uptimeMillis() + 2500 }

        /** Este botão está funcionando (o do assistente não precisa virar contador de km). */
        fun visivel() = SystemClock.uptimeMillis() < vivaAte
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(ctx)
    private val BR = Locale("pt", "BR")
    private val density = ctx.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val TXT = 0xFFFFFFFF.toInt()
    private val MUTED = 0xFF8B949E.toInt()
    private val PANEL = 0xF70B0F14.toInt()
    private val SURFACE = 0xFF11161B.toInt()
    private val SURFACE_HI = 0xFF1A2027.toInt()
    private val STROKE = 0x1FFFFFFF
    private val VERDE = 0xFF4ADE80.toInt()

    private val BTN = 70
    private val CIRCLE = 58

    private var botao: FrameLayout? = null
    private var circulo: FrameLayout? = null
    private var texto: TextView? = null
    private var lpBotao: WindowManager.LayoutParams? = null
    private var painel: FrameLayout? = null
    private var painelFechadoEm = 0L
    private var chave = ""
    private var editando = false

    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density
    private fun telaW() = ctx.resources.displayMetrics.widthPixels
    private fun telaH() = ctx.resources.displayMetrics.heightPixels
    private fun tipo() = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private val batida = object : Runnable {
        override fun run() {
            runCatching { atualizar() }
            handler.postDelayed(this, 1000)
        }
    }

    fun iniciar() {
        atual = this
        handler.removeCallbacks(batida)
        handler.post(batida)
    }

    fun parar() {
        handler.removeCallbacksAndMessages(null)
        fecharPainel(false)
        tirarBotao()
        if (atual === this) atual = null
        vivaAte = 0L
    }

    private fun atualizar() {
        val t = Turno.estadoRapido(ctx)
        if (!t.ativo || !podeMostrar(ctx)) { fecharPainel(false); tirarBotao(); vivaAte = 0L; return }
        vivaAte = SystemClock.uptimeMillis() + 3000
        val esconder = SystemClock.uptimeMillis() < fabAte && painel == null
        if (esconder) { tirarBotao(); return }
        if (botao == null || botao?.isAttachedToWindow == false) { tirarBotao(); mostrarBotao() }
        pintar(t)
    }

    private fun pintar(t: Turno.Estado) {
        val c = circulo ?: return
        val tv = texto ?: return
        val emRota = TurnoRota.ativa(ctx)
        val k = (if (emRota) "v" else "r") + String.format(BR, "%.1f", t.km)
        if (k == chave) return
        chave = k
        val sb = SpannableStringBuilder(String.format(BR, if (t.km < 100) "%.1f" else "%.0f", t.km))
        val st = sb.length
        sb.append("\nkm")
        sb.setSpan(RelativeSizeSpan(0.6f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.text = sb
        c.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (emRota) intArrayOf(0xFF3EE07A.toInt(), 0xFF178A43.toInt()) else intArrayOf(0xFFFF5A5A.toInt(), 0xFFB3141C.toInt())
        ).apply { shape = GradientDrawable.OVAL; setStroke(dp(2), 0xB3FFFFFF.toInt()) }
    }

    private fun tirarBotao() {
        botao?.let { runCatching { wm.removeView(it) } }
        botao = null; circulo = null; texto = null; lpBotao = null; chave = ""
    }

    private fun mostrarBotao() {
        val tam = dp(BTN)
        val tv = TextView(ctx).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(TXT)
            textSize = 15f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setShadowLayer(dpf(2f), 0f, dpf(1f), 0x66000000)
        }
        val c = FrameLayout(ctx).apply {
            elevation = dpf(6f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val b = FrameLayout(ctx).apply {
            clipChildren = false
            addView(c, FrameLayout.LayoutParams(dp(CIRCLE), dp(CIRCLE), Gravity.CENTER))
        }
        val lp = WindowManager.LayoutParams(
            tam, tam, tipo(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.fabX >= 0) prefs.fabX.coerceAtMost(telaW() - tam) else telaW() - tam
            y = if (prefs.fabY >= 0) prefs.fabY.coerceAtMost(telaH() - tam) else dp(300)
        }
        var dx = 0f; var dy = 0f; var sx = 0; var sy = 0; var moveu = false
        b.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    dx = ev.rawX; dy = ev.rawY; sx = lp.x; sy = lp.y; moveu = false
                    c.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start(); true
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = ev.rawX - dx; val my = ev.rawY - dy
                    if (Math.abs(mx) > dp(6) || Math.abs(my) > dp(6)) moveu = true
                    if (moveu) {
                        lp.x = (sx + mx).toInt().coerceIn(0, (telaW() - tam).coerceAtLeast(0))
                        lp.y = (sy + my).toInt().coerceIn(0, (telaH() - tam).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(b, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    c.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (ev.action == MotionEvent.ACTION_UP) {
                        if (moveu) { prefs.fabX = lp.x; prefs.fabY = lp.y }
                        else if (painel != null) fecharPainel()
                        else if (SystemClock.uptimeMillis() - painelFechadoEm > 350) abrirPainel()
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(b, lp) }.onSuccess {
            botao = b; circulo = c; texto = tv; lpBotao = lp; chave = ""
            pintar(Turno.estadoRapido(ctx))
        }
    }

    // ───────────────────────────── painel ─────────────────────────────

    private fun rotulo(t: CharSequence, sp: Float, cor: Int, negrito: Boolean = false) = TextView(ctx).apply {
        text = t; textSize = sp; setTextColor(cor); includeFontPadding = false
        if (negrito) setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
    }

    private fun fundo(cor: Int, raio: Float, borda: Int? = null) = GradientDrawable().apply {
        setColor(cor); cornerRadius = dpf(raio); if (borda != null) setStroke(dp(1), borda)
    }

    private fun botaoAcao(t: String, verde: Boolean, aoTocar: () -> Unit) = rotulo(t, if (verde) 15.5f else 13.5f, if (verde) TXT else 0xFFC9D1D9.toInt(), true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(if (verde) 14 else 11), dp(12), dp(if (verde) 14 else 11))
        background = if (verde) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF1A7A3A.toInt(), 0xFF2EA44F.toInt()))
            .apply { cornerRadius = dpf(13f) } else fundo(SURFACE_HI, 13f)
        setOnClickListener { aoTocar() }
    }

    private fun moeda(v: Double) = "R$ " + String.format(BR, "%,.2f", v)
    private fun lerValor(s: String): Double = s.trim().replace("R$", "").replace(" ", "").let {
        if (it.contains(",")) it.replace(".", "").replace(",", ".") else it
    }.toDoubleOrNull() ?: 0.0

    private fun duracao(ms: Long): String { val m = (ms / 60000L).coerceAtLeast(0); return if (m < 60) "$m min" else "${m / 60}h" + String.format(BR, "%02d", m % 60) }
    private fun hm(ts: Long) = java.text.SimpleDateFormat("HH:mm", BR).format(java.util.Date(ts))

    fun abrirPainel() {
        handler.post { runCatching { montarPainel() } }
    }

    private fun montarPainel() {
        fecharPainel(false)
        val t = Turno.estado(ctx)
        if (!t.ativo) return
        val rota = TurnoRota.estado(ctx)
        val emRota = rota != null
        editando = !emRota

        val corBorda = if (emRota) 0x664ADE80 else 0x59F85149
        val icone = rotulo(if (emRota) "📦" else "🛵", 19f, TXT).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (emRota) intArrayOf(0xFF1A7A3A.toInt(), 0xFF2EA44F.toInt()) else intArrayOf(0xFF8E1519.toInt(), 0xFFF85149.toInt()))
                .apply { cornerRadius = dpf(12f) }
        }
        val sub = if (emRota) "desde " + hm(rota!!.optLong("ini")) + " · " + duracao(System.currentTimeMillis() - rota.optLong("ini")) +
            " · " + Turno.fmtKm((t.km - rota.optDouble("kmIni", 0.0)).coerceAtLeast(0.0))
            else "GPS medindo · desde " + hm(t.ini)
        val tit = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(rotulo(if (emRota) "Em rota" else "Turno · sem rota", 16f, TXT, true))
            addView(rotulo(sub, 11.5f, MUTED).apply { setPadding(0, dp(3), 0, 0) })
        }
        val fechar = rotulo("✕", 13f, MUTED, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SURFACE_HI) }
            setOnClickListener { fecharPainel() }
        }
        val topo = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(icone, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
            addView(tit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(fechar, LinearLayout.LayoutParams(dp(30), dp(30)))
        }
        val conteudo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            addView(topo)
        }
        fun mp(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

        var campo: EditText? = null
        if (!emRota) {
            fun bloco(v: String, r: String) = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = fundo(SURFACE, 14f, STROKE)
                addView(rotulo(r, 10.5f, MUTED, true))
                addView(rotulo(v, 22f, TXT, true).apply { setPadding(0, dp(4), 0, 0) })
            }
            conteudo.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(bloco(Turno.fmtKm(t.km), "RODADOS"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
                addView(bloco(duracao(System.currentTimeMillis() - t.ini), "TEMPO"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
            }, mp(14))
        } else {
            val v = rota!!.optDouble("valor", 0.0)
            conteudo.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = fundo(0x144ADE80, 14f, 0x4D4ADE80)
                addView(rotulo("VOCÊ VAI RECEBER", 11f, MUTED, true))
                addView(rotulo(moeda(v), 32f, VERDE, true).apply { setPadding(0, dp(6), 0, 0) })
            }, mp(14))
        }

        // campo da taxa (sem rota, ou "Corrigir valor")
        val areaCampo = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val acoes = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        conteudo.addView(areaCampo, mp(0))
        conteudo.addView(acoes, mp(0))

        fun montarCampo(inicial: Double, textoBtn: (Double) -> String, aoConfirmar: (Double) -> Unit) {
            areaCampo.removeAllViews(); acoes.removeAllViews()
            val ed = EditText(ctx).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                imeOptions = EditorInfo.IME_ACTION_DONE
                setTextColor(TXT); textSize = 26f; setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
                setHintTextColor(0x55FFFFFF); hint = "0,00"
                background = null; setPadding(0, 0, 0, 0)
                if (inicial > 0) setText(String.format(BR, "%.2f", inicial))
                keyListener = android.text.method.DigitsKeyListener.getInstance("0123456789,.")
            }
            campo = ed
            val linha = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = fundo(SURFACE, 14f).apply { setStroke(dp(2), VERDE) }
                addView(rotulo("R$", 18f, MUTED, true), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
                addView(ed, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            areaCampo.addView(rotulo("TAXA DE ENTREGA", 10.5f, 0xFF7EE2A0.toInt(), true).apply { setPadding(dp(2), 0, 0, dp(6)) }, mp(14))
            areaCampo.addView(linha)
            val ok = botaoAcao(textoBtn(inicial), true) {}
            fun repintar() {
                val v = lerValor(ed.text.toString())
                ok.text = textoBtn(v)
                ok.alpha = if (v > 0) 1f else 0.45f
            }
            val chips = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            listOf(1.0, 5.0, 10.0).forEachIndexed { i, s ->
                chips.addView(rotulo("+ R$ " + s.toInt(), 13f, 0xFFC9D1D9.toInt(), true).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(8))
                    background = fundo(SURFACE_HI, 10f)
                    setOnClickListener {
                        val v = lerValor(ed.text.toString()) + s
                        ed.setText(String.format(BR, "%.2f", v)); ed.setSelection(ed.text.length); repintar()
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
            areaCampo.addView(chips, mp(8))
            ed.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { repintar() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            val confirmar = {
                val v = lerValor(ed.text.toString())
                if (v > 0) { esconderTeclado(ed); aoConfirmar(v) }
            }
            ok.setOnClickListener { confirmar() }
            ed.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { confirmar(); true } else false }
            acoes.addView(ok, mp(12))
            repintar()
            handler.postDelayed({ runCatching { ed.requestFocus(); (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(ed, 0) } }, 220)
        }

        fun acoesSemRota() {
            montarCampo(0.0, { v -> if (v > 0) "▶  Iniciar rota · " + moeda(v) else "▶  Iniciar rota" }) { v ->
                if (TurnoRota.iniciar(ctx, v)) { vibrar(); chave = ""; fecharPainel(); runCatching { atualizar() } }
            }
            acoes.addView(botaoAcao("Finalizar turno", false) { abrirFinalizarTurno() }, mp(8))
        }

        fun acoesEmRota() {
            val v = rota!!.optDouble("valor", 0.0)
            areaCampo.removeAllViews(); acoes.removeAllViews()
            acoes.addView(botaoAcao("✓  Finalizar rota · " + moeda(v), true) {
                val lancado = TurnoRota.finalizar(ctx)
                vibrar(); chave = ""; fecharPainel(); runCatching { atualizar() }
                if (lancado > 0) balao("+ " + moeda(lancado) + " · no faturamento")
            }, mp(12))
            acoes.addView(botaoAcao("✎  Corrigir valor", false) {
                editando = true
                trocarFoco(true)
                montarCampo(v, { nv -> "Salvar valor · " + moeda(nv) }) { nv ->
                    TurnoRota.corrigir(ctx, nv); fecharPainel(); abrirPainel()
                }
                acoes.addView(botaoAcao("Cancelar esta rota", false) {
                    TurnoRota.cancelar(ctx); chave = ""; fecharPainel(); runCatching { atualizar() }
                }, mp(8))
            }, mp(8))
            acoes.addView(rotulo("Ao finalizar, a rota entra no faturamento do dia e o turno continua.", 10.5f, MUTED).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
            }, mp(0))
        }

        if (emRota) acoesEmRota() else acoesSemRota()

        val caixa = FrameLayout(ctx).apply {
            background = fundo(PANEL, 22f, corBorda)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = dpf(12f)
            addView(conteudo)
        }
        val pad = dp(10)
        val raiz = FrameLayout(ctx).apply {
            setPadding(pad, pad, pad, pad)
            clipToPadding = false
            addView(caixa, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            setOnTouchListener { _, ev -> if (ev.action == MotionEvent.ACTION_OUTSIDE) { fecharPainel(); true } else false }
        }
        val w = dp(330).coerceAtMost(telaW() - dp(12))
        val lp = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT, tipo(),
            (if (editando) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // fica na parte de cima: o teclado abre embaixo sem cobrir o campo
            val by = lpBotao?.y ?: dp(300)
            y = if (by < telaH() / 3) (by + dp(BTN)).coerceAtMost(telaH() / 3) else dp(56)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }
        raiz.layoutParams = lp
        runCatching { wm.addView(raiz, lp) }.onSuccess {
            painel = raiz
            caixa.alpha = 0f; caixa.scaleX = 0.94f; caixa.scaleY = 0.94f
            caixa.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170).start()
        }
    }

    /** O painel aberto sem teclado ("Em rota") passa a aceitar o teclado para corrigir o valor. */
    private fun trocarFoco(focavel: Boolean) {
        val p = painel ?: return
        val lp = p.layoutParams as? WindowManager.LayoutParams ?: return
        lp.flags = if (focavel) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { wm.updateViewLayout(p, lp) }
    }

    private fun esconderTeclado(v: View) {
        runCatching { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) }
    }

    fun fecharPainel(animar: Boolean = true) {
        val p = painel ?: return
        painel = null
        painelFechadoEm = SystemClock.uptimeMillis()
        esconderTeclado(p)
        if (!animar) { runCatching { wm.removeView(p) }; return }
        p.animate().alpha(0f).setDuration(120).withEndAction { runCatching { wm.removeView(p) } }.start()
    }

    private fun abrirFinalizarTurno() {
        fecharPainel(false)
        runCatching {
            ctx.startActivity(Intent(ctx, com.prismo.drive.MainActivity::class.java)
                .putExtra(Turno.EXTRA_ACAO, Turno.ACAO_FINALIZAR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun vibrar() {
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= 31)
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator)
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(android.os.VibrationEffect.createOneShot(40, 180))
            else @Suppress("DEPRECATION") vib.vibrate(40)
        }
    }

    /** Balão "+ R$ 8,50 · no faturamento" que sobe do botão e some. */
    private fun balao(msg: String) {
        val lpB = lpBotao ?: return
        val tv = rotulo(msg, 13.5f, TXT, true).apply {
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = fundo(0xF2178A43.toInt(), 20f)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, tipo(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(8)
            y = (lpB.y - dp(44)).coerceAtLeast(dp(24))
        }
        runCatching { wm.addView(tv, lp) }.onSuccess {
            tv.alpha = 0f
            tv.animate().alpha(1f).setDuration(200).withEndAction {
                tv.animate().alpha(0f).translationY(-dpf(30f)).setStartDelay(2600).setDuration(500)
                    .withEndAction { runCatching { wm.removeView(tv) } }.start()
            }.start()
        }
    }
}
