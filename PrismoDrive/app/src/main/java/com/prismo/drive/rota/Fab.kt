package com.prismo.drive.rota

import com.prismo.drive.R
import com.prismo.drive.BuildConfig

import android.accessibilityservice.AccessibilityService
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Botão flutuante (arrastável). Toque abre o painel de opções rápidas:
 * aceite automático e seus mínimos, maior valor do dia, abrir o app e Desligar.
 * Usa TYPE_ACCESSIBILITY_OVERLAY (não precisa de "Exibir sobre outros apps").
 */
class Fab(private val svc: AccessibilityService) {

    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = Prefs(svc)
    private val BR = Locale("pt", "BR")
    private val density = svc.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private var button: FrameLayout? = null     // janela (maior que o círculo p/ sombra e animação)
    private var circle: FrameLayout? = null     // o círculo visível
    private var valueText: TextView? = null
    private var icon: ImageView? = null
    private var pulse: ObjectAnimator? = null
    private var lastBest: Result? = null
    private var buttonLp: WindowManager.LayoutParams? = null
    private var menu: FrameLayout? = null
    private var menuClosedAt = 0L

    // paleta
    private val GOLD = 0xFF4ADE80.toInt()
    private val PANEL = 0xF70B0F14.toInt()
    private val SURFACE = 0xFF11161B.toInt()
    private val SURFACE_HI = 0xFF1A2027.toInt()
    private val STROKE = 0x1FFFFFFF
    private val TXT = 0xFFFFFFFF.toInt()
    private val MUTED = 0xFF8B949E.toInt()
    private val GREEN = 0xFF2ECC71.toInt()
    private val RED = 0xFFFF6B6B.toInt()

    private val BTN = 64      // janela do botão (dp)
    private val CIRCLE = 52   // círculo (dp)

    private fun dp(v: Int) = (v * density).toInt()
    private fun dpf(v: Float) = v * density
    private fun screenW() = svc.resources.displayMetrics.widthPixels
    private fun screenH() = svc.resources.displayMetrics.heightPixels

    /** App em primeiro plano agora (o serviço atualiza a cada batida). */
    @Volatile var pkgAtual: String = ""

    /** Turno em andamento (null = sem turno) e se o app aberto NÃO é o iFood/InDrive (o serviço atualiza). */
    @Volatile var turno: Turno.Estado? = null
    @Volatile var foraDoApp: Boolean = false

    /** Fora do iFood/InDrive com turno ativo: o botão vira um contador de km do turno. */
    private fun modoTurno() = turno != null && foraDoApp

    /** Batida do serviço: redesenha o contador do turno (km muda). */
    fun tickTurno() { applyBest() }

    private fun turnoTexto(): CharSequence {
        val km = turno?.km ?: 0.0
        val sb = SpannableStringBuilder(String.format(BR, if (km < 100) "%.1f" else "%.0f", km))
        val st = sb.length
        sb.append("\nkm")
        sb.setSpan(RelativeSizeSpan(0.6f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun turnoDuracao(): String {
        val ini = turno?.ini ?: return ""
        val m = ((System.currentTimeMillis() - ini) / 60000L).coerceAtLeast(0)
        return if (m < 60) "${m} min" else "${m / 60}h" + String.format(BR, "%02d", m % 60)
    }

    /** Abre o Prismo Drive direto na tela de finalizar o turno (pergunta o valor recebido). */
    private fun abrirFinalizarTurno() {
        closeMenu(animate = false)
        runCatching {
            svc.startActivity(
                Intent(svc, com.prismo.drive.MainActivity::class.java)
                    .putExtra(Turno.EXTRA_ACAO, Turno.ACAO_FINALIZAR)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    /** O app aberto é o iFood? (pelo nome do pacote, pelo que já foi aprendido ou pela última oferta) */
    private fun modoIfood(): Boolean {
        val p = pkgAtual
        if (p.contains("ifood", ignoreCase = true)) return true
        if (p.contains("indrive", ignoreCase = true) || p.contains("indriver", ignoreCase = true)) return false
        if (p.isNotEmpty() && p == prefs.ifoodPkg) return true
        if (p.isNotEmpty() && p == prefs.indrivePkg) return false
        return lastBest?.offer?.route == true
    }

    fun setVisible(v: Boolean) {
        if (v) { showButton(); return }
        // durante a comemoração a moeda fica na tela mesmo fora do iFood (ex.: "Testar comemoração")
        if (SystemClock.uptimeMillis() < comemorandoAte) return
        remove()
    }

    fun remove() {
        closeMenu(animate = false)
        stopPulse()
        shownKey = ""
        button?.let { runCatching { wm.removeView(it) } }
        button = null
        circle = null
        valueText = null
        icon = null
        buttonLp = null
    }

    /** Mostra dentro do botão a melhor opção do momento (R$/km + classificação); null = nada na tela. */
    fun update(best: Result?) {
        lastBest = best
        applyBest()
    }

    private var shownKey = ""
    private var blink: ValueAnimator? = null
    private var comemorandoAte = 0L

    /**
     * Comemoração quando uma rota concluída do iFood entra na carteira: o botão vira uma moeda
     * dourada que gira com "+R$", dá um pulo e solta um balão "+R$ 68,00 · na carteira" que sobe e some.
     */
    fun celebrar(valor: Double) {
        if (button == null) showButton()
        val c = circle ?: return
        val t = valueText ?: return
        val ic = icon ?: return
        comemorandoAte = SystemClock.uptimeMillis() + 3700   // moeda visível por ~3 s depois do giro
        stopPulse()
        c.animate().cancel()
        // moeda
        c.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFFE08A.toInt(), 0xFFF5B301.toInt(), 0xFFB97800.toInt())).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), 0xFFFFF4C2.toInt())
        }
        ic.visibility = View.GONE
        t.visibility = View.VISIBLE
        t.setTextColor(0xFF3A2500.toInt())
        t.setShadowLayer(0f, 0f, 0f, 0)
        t.textSize = if (valor >= 100) 11f else 13f
        t.text = "+" + String.format(BR, "%.0f", valor)
        c.scaleX = 0.6f; c.scaleY = 0.6f; c.rotationY = 0f
        c.animate().scaleX(1.25f).scaleY(1.25f).rotationY(720f)
            .setDuration(650).setInterpolator(android.view.animation.OvershootInterpolator(2.2f))
            .withEndAction {
                c.animate().scaleX(1f).scaleY(1f).setDuration(260).start()
            }.start()
        balaoValor(valor)
        // volta ao normal depois da comemoração
        handler.postDelayed({
            if (SystemClock.uptimeMillis() < comemorandoAte) return@postDelayed
            val cc = circle ?: return@postDelayed
            cc.rotationY = 0f
            valueText?.setTextColor(TXT)
            valueText?.setShadowLayer(dpf(2f), 0f, dpf(1f), 0x66000000)
            valueText?.textSize = 14f
            shownKey = "~"          // força redesenhar o estado atual
            applyBest()
        }, 3800)
    }

    /** Balão "+R$ 68,00 · na carteira" que sobe ao lado do botão e desaparece. */
    private fun balaoValor(valor: Double) {
        val blp = buttonLp ?: return
        val txt = TextView(svc).apply {
            text = "💰 +" + String.format(BR, "R$ %.2f", valor) + "  ·  na carteira"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF1A7A3A.toInt(), 0xFF2EA44F.toInt())).apply {
                cornerRadius = dpf(20f)
                setStroke(dp(1), 0x66FFFFFF)
            }
            elevation = dpf(8f)
            alpha = 0f
        }
        val w = FrameLayout(svc).apply {
            clipChildren = false
            addView(txt, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val largura = dp(250)
        val noLadoDireito = blp.x > screenW() / 2
        val lp = WindowManager.LayoutParams(
            largura, dp(110),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (noLadoDireito) (blp.x + dp(BTN) - largura).coerceAtLeast(0) else blp.x.coerceAtMost(screenW() - largura)
            y = (blp.y - dp(100)).coerceAtLeast(0)
        }
        runCatching { wm.addView(w, lp) }.onSuccess {
            txt.translationY = dpf(40f)
            txt.scaleX = 0.8f; txt.scaleY = 0.8f
            txt.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(380)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.6f))
                .withEndAction {
                    txt.animate().alpha(0f).translationY(-dpf(36f)).setStartDelay(3000).setDuration(600)
                        .withEndAction { runCatching { wm.removeView(w) } }.start()
                }.start()
        }
    }

    private fun idleBackground() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1C2B22.toInt(), 0xFF0B0F14.toInt())
    ).apply {
        shape = GradientDrawable.OVAL
        setStroke(dp(2), GOLD)
    }

    private fun applyBest() {
        val c = circle ?: return
        val t = valueText ?: return
        val ic = icon ?: return
        if (modoTurno()) {
            if (SystemClock.uptimeMillis() < comemorandoAte) return
            val tk = "turno|" + String.format(BR, "%.1f", turno?.km ?: 0.0)
            if (tk == shownKey) return
            shownKey = tk
            stopPulse()
            t.text = turnoTexto()
            t.visibility = View.VISIBLE
            ic.visibility = View.GONE
            c.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2F81F7.toInt(), 0xFF1554B8.toInt())
            ).apply { shape = GradientDrawable.OVAL; setStroke(dp(2), 0xAAFFFFFF.toInt()) }
            return
        }
        val r = lastBest
        val eff = r?.effRpk
        val key = if (r == null || eff == null) "idle" else
            String.format(BR, "%.2f", eff) + "|" + Evaluator.labelOf(r) + "|" + r.level
        if (SystemClock.uptimeMillis() < comemorandoAte) return   // deixa a comemoração terminar
        if (key == shownKey) return          // nada mudou: não reinicia a animação
        shownKey = key
        stopPulse()
        if (r == null || eff == null) {
            t.visibility = View.GONE
            ic.visibility = View.VISIBLE
            c.background = idleBackground()
            return
        }
        val lvl = r.level ?: Level.RUIM
        val caution = Evaluator.badRating(r)
        val col = if (caution) Evaluator.CAUTION else when (lvl) {
            Level.OTIMO -> 0xFF1FB45A.toInt()
            Level.MEDIO -> 0xFFEF8F00.toInt()
            Level.RUIM -> 0xFFC62828.toInt()
        }
        val label = Evaluator.labelOf(r)
        val sb = SpannableStringBuilder(String.format(BR, "%.2f", eff))
        val st = sb.length
        sb.append("\n").append(label)
        sb.setSpan(RelativeSizeSpan(if (label.length > 6) 0.5f else 0.58f), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        t.text = sb
        t.visibility = View.VISIBLE
        ic.visibility = View.GONE
        val bg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(lighten(col), col)
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), 0x88FFFFFF.toInt())
        }
        c.background = bg
        if (lvl == Level.OTIMO && !caution) startPulse(bg)
    }

    private fun lighten(c: Int): Int {
        val f = 0.22f
        val r = Color.red(c) + ((255 - Color.red(c)) * f).toInt()
        val g = Color.green(c) + ((255 - Color.green(c)) * f).toInt()
        val b = Color.blue(c) + ((255 - Color.blue(c)) * f).toInt()
        return Color.argb(255, r, g, b)
    }

    /** ÓTIMO: o balão cresce e diminui e a borda pisca (grossa/clara <-> fina/branca). */
    private fun startPulse(bg: GradientDrawable) {
        val v = circle ?: return
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f)
        ).apply {
            duration = 500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        blink = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val color = Color.argb((255 - 150 * t).toInt(), 255 - (70 * t).toInt(), 255, 255 - (55 * t).toInt())
                bg.setStroke(dp(2) + (dp(3) * t).toInt(), color)
            }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        blink?.cancel()
        blink = null
        circle?.scaleX = 1f
        circle?.scaleY = 1f
    }

    // ------------------------------------------------------------------ botão
    private var addedAt = 0L

    private fun showButton() {
        // verificador: se o sistema derrubou a janela do botão (ex.: ao fechar o app), recria
        button?.let {
            if (it.isAttachedToWindow || SystemClock.uptimeMillis() - addedAt < 2500) return
            runCatching { wm.removeView(it) }
            stopPulse()
            button = null
            circle = null
            valueText = null
            icon = null
            buttonLp = null
            closeMenu(animate = false)
        }
        val size = dp(BTN)

        val t = TextView(svc).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(TXT)
            textSize = 14f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setShadowLayer(dpf(2f), 0f, dpf(1f), 0x66000000)
            visibility = View.GONE
        }
        val ic = ImageView(svc).apply {
            setImageResource(R.drawable.ic_stat_rota)
            imageTintList = ColorStateList.valueOf(GOLD)
        }
        val c = FrameLayout(svc).apply {
            background = idleBackground()
            elevation = dpf(6f)
            outlineProvider = ViewOutlineProvider.BACKGROUND
            addView(t, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(ic, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
        }
        // pontinho verde = serviço ativo
        val dot = View(svc).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(GREEN)
                setStroke(dp(2), 0xFF0B0F14.toInt())
            }
            elevation = dpf(7f)
        }
        val b = FrameLayout(svc).apply {
            clipChildren = false
            clipToPadding = false
            addView(c, FrameLayout.LayoutParams(dp(CIRCLE), dp(CIRCLE), Gravity.CENTER))
            addView(dot, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(7); rightMargin = dp(7)
            })
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (prefs.fabX >= 0) prefs.fabX else screenW() - size
            y = if (prefs.fabY >= 0) prefs.fabY else dp(300)
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        b.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    c.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = ev.rawX - downX
                    val my = ev.rawY - downY
                    if (Math.abs(mx) > dp(6) || Math.abs(my) > dp(6)) moved = true
                    if (moved) {
                        lp.x = (startX + mx).toInt().coerceIn(0, (screenW() - size).coerceAtLeast(0))
                        lp.y = (startY + my).toInt().coerceIn(0, (screenH() - size).coerceAtLeast(0))
                        runCatching { wm.updateViewLayout(b, lp) }
                        if (menu != null) positionMenu()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    c.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    if (ev.action == MotionEvent.ACTION_UP) {
                        if (moved) {
                            prefs.fabX = lp.x
                            prefs.fabY = lp.y
                        } else toggleMenu()
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(b, lp) }.onSuccess {
            button = b
            circle = c
            valueText = t
            icon = ic
            shownKey = ""
            buttonLp = lp
            addedAt = SystemClock.uptimeMillis()
            applyBest()
        }
    }

    // ------------------------------------------------------------------- menu
    private fun toggleMenu() {
        when {
            menu != null -> closeMenu()
            // o toque fora do painel (no próprio botão) acabou de fechar: não reabre
            SystemClock.uptimeMillis() - menuClosedAt < 350 -> Unit
            modoTurno() -> openMenuTurno()
            else -> openMenu()
        }
    }

    private fun closeMenu(animate: Boolean = true) {
        val m = menu ?: return
        menu = null
        menuClosedAt = SystemClock.uptimeMillis()
        val panel = m.getChildAt(0)
        if (animate && panel != null) {
            panel.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setDuration(110)
                .withEndAction { runCatching { wm.removeView(m) } }.start()
        } else runCatching { wm.removeView(m) }
    }

    private fun menuWidth() = dp(300).coerceAtMost(screenW() - dp(16))

    /** Abre ao lado do botão (lado com mais espaço) sem sair da tela. */
    private fun positionMenu() {
        val m = menu ?: return
        val blp = buttonLp ?: return
        val lp = m.layoutParams as? WindowManager.LayoutParams ?: return
        val w = menuWidth()
        val h = m.measuredHeight.takeIf { it > 0 } ?: lp.height.coerceAtLeast(dp(300))
        val onRight = blp.x + dp(BTN) / 2 > screenW() / 2
        lp.x = (if (onRight) blp.x - w - dp(4) else blp.x + dp(BTN) + dp(4))
            .coerceIn(dp(8), (screenW() - w - dp(8)).coerceAtLeast(dp(8)))
        lp.y = blp.y.coerceIn(dp(24), (screenH() - h - dp(24)).coerceAtLeast(dp(24)))
        runCatching { wm.updateViewLayout(m, lp) }
        m.getChildAt(0)?.apply {
            pivotX = if (onRight) w.toFloat() else 0f
            pivotY = (blp.y - lp.y + dp(BTN) / 2).toFloat().coerceAtLeast(0f)
        }
    }

    // ---- peças visuais do painel
    private fun label(t: CharSequence, sp: Float, c: Int, bold: Boolean = false) = TextView(svc).apply {
        text = t
        textSize = sp
        setTextColor(c)
        if (bold) setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radius)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun section(t: String) = label(t.uppercase(BR), 10.5f, 0xCC7EE2A0.toInt(), true).apply {
        letterSpacing = 0.1f
        setPadding(dp(4), dp(16), 0, dp(8))
    }

    private fun divider() = View(svc).apply { setBackgroundColor(STROKE) }

    private fun group(vararg rows: View) = LinearLayout(svc).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(SURFACE, 14f, STROKE)
        rows.forEachIndexed { i, v ->
            if (i > 0) addView(divider(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = dp(14); rightMargin = dp(14)
            })
            addView(v)
        }
    }

    /** Chave liga/desliga desenhada (trilho + bolinha), com animação. */
    private fun toggle(initial: Boolean, onChange: (Boolean) -> Unit): FrameLayout {
        var on = initial
        val track = GradientDrawable().apply { cornerRadius = dpf(14f) }
        val thumb = View(svc).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(TXT) }
            elevation = dpf(2f)
        }
        val travel = dpf(20f)
        fun paint(animate: Boolean) {
            track.setColor(if (on) GREEN else 0xFF30363D.toInt())
            val tx = if (on) travel else 0f
            if (animate) thumb.animate().translationX(tx).setDuration(150).start() else thumb.translationX = tx
        }
        return FrameLayout(svc).apply {
            background = track
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(thumb, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.START or Gravity.CENTER_VERTICAL))
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(28))
            paint(false)
            setOnClickListener {
                on = !on
                paint(true)
                onChange(on)
            }
        }
    }

    private fun roundBtn(t: String, onTap: () -> Unit) = label(t, 18f, TXT, true).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SURFACE_HI); setStroke(dp(1), STROKE) }
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        // toque = 1 passo; segurar = repete
        var repeating = false
        val rep = object : Runnable {
            override fun run() {
                if (!repeating) return
                onTap()
                handler.postDelayed(this, 90)
            }
        }
        setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.6f
                    onTap()
                    repeating = true
                    handler.postDelayed(rep, 420)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    repeating = false
                    handler.removeCallbacks(rep)
                }
            }
            true
        }
    }

    private fun stepper(
        title: String,
        sub: String?,
        get: () -> Float,
        set: (Float) -> Unit,
        step: Float,
        min: Float,
        max: Float,
        fmt: (Float) -> String
    ): LinearLayout {
        val value = label(fmt(get()), 14f, TXT, true).apply {
            gravity = Gravity.CENTER
            minWidth = dp(72)
        }
        fun change(delta: Float) {
            val n = (Math.round((get() + delta) * 100f) / 100f).coerceIn(min, max)
            set(n)
            value.text = fmt(n)
        }
        val col = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title, 13f, TXT))
            if (sub != null) addView(label(sub, 10.5f, MUTED))
        }
        return LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(roundBtn("−") { change(-step) })
            addView(value)
            addView(roundBtn("+") { change(step) })
        }
    }

    private fun actionBtn(text: String, iconRes: Int?, fg: Int, bg: GradientDrawable, onTap: () -> Unit) =
        LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = bg
            setPadding(dp(10), dp(12), dp(10), dp(12))
            if (iconRes != null) addView(ImageView(svc).apply {
                setImageResource(iconRes)
                imageTintList = ColorStateList.valueOf(fg)
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) })
            addView(label(text, 14f, fg, true))
            setOnClickListener { onTap() }
        }

    private fun openMenu() {
        if (menu != null) return

        // ---------- cabeçalho
        val logo = FrameLayout(svc).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1A7A3A.toInt(), 0xFF2EA44F.toInt())
            ).apply { cornerRadius = dpf(12f) }
            addView(ImageView(svc).apply {
                setImageResource(R.drawable.ic_stat_rota)
                imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }
        val statusDot = View(svc).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN) }
        }
        val statusRow = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(6) })
            addView(label(if (modoIfood()) "Ativo · iFood" else "Ativo · InDrive", 11.5f, MUTED))
        }
        val titleCol = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Assistente de Rotas", 16f, TXT, true))
            addView(statusRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        val close = label("✕", 13f, MUTED, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SURFACE_HI) }
            setOnClickListener { closeMenu() }
        }
        val header = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
            addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(dp(30), dp(30)))
        }

        // ---------- aceite automático
        val limits = group(
            stepper("Valor mínimo", "bruto da corrida",
                { prefs.autoMinPrice }, { prefs.autoMinPrice = it }, 1f, 0f, 300f
            ) { if (it <= 0f) "sem mín." else String.format(BR, "R$ %.0f", it) },
            stepper("R$/km mínimo", "busca + viagem",
                { prefs.autoMinRpk }, { prefs.autoMinRpk = it }, 0.10f, 0.5f, 6f
            ) { String.format(BR, "R$ %.2f", it) },
            stepper("Nota mínima", "do passageiro",
                { prefs.minRating }, { prefs.minRating = it }, 0.05f, 3f, 5f
            ) { String.format(BR, "%.2f", it) }
        )
        fun dim(on: Boolean) { limits.animate().alpha(if (on) 1f else 0.45f).setDuration(150).start() }
        limits.alpha = if (prefs.autoAccept) 1f else 0.45f

        val autoRow = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(SURFACE, 14f, STROKE)
            val col = LinearLayout(svc).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("Aceitar automaticamente", 13.5f, TXT, true))
                addView(label("Só corridas que batem os 3 mínimos", 11f, MUTED))
            }
            addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle(prefs.autoAccept) { prefs.autoAccept = it; dim(it) })
        }

        // ---------- iFood: configurações rápidas (mesmos valores da aba iFood do assistente)
        val ifood = modoIfood()
        val brl = { v: Float -> String.format(BR, "R$ %.2f", v) }
        val ifoodGroup = group(
            stepper("RUIM abaixo de", "R$ por km da rota",
                { prefs.ifoodMedio }, { prefs.ifoodMedio = it }, 0.10f, 0.3f, 6f, brl),
            stepper("ÓTIMO a partir de", "R$ por km da rota",
                { prefs.ifoodOtimo }, { prefs.ifoodOtimo = it }, 0.10f, 0.3f, 8f, brl),
            stepper("Mínimo por pedido", "valor ÷ entregas · abaixo = RUIM",
                { prefs.ifoodMinPerOrder }, { prefs.ifoodMinPerOrder = it }, 0.5f, 0f, 60f
            ) { if (it <= 0f) "sem mín." else String.format(BR, "R$ %.2f", it) }
        )

        // ---------- maior valor do dia (atualiza sozinho enquanto o painel está aberto)
        val bestValue = label("—", 22f, GOLD, true)
        val bestInfo = label("", 11.5f, MUTED)
        val bestCard = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(SURFACE, 14f, STROKE)
            addView(bestValue)
            addView(bestInfo, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }
        var shownT = -1L
        fun paintBest() {
            val ifood = modoIfood()
            val b = runCatching { DayBest.get(svc, ifood) }.getOrNull()   // só do app aberto
            val key = b?.let { it.t xor java.lang.Double.doubleToLongBits(it.price) } ?: 0L
            if (key == shownT) return
            val first = shownT == -1L
            shownT = key
            if (b == null) {
                bestValue.text = "—"
                bestValue.setTextColor(MUTED)
                bestInfo.text = if (modoIfood()) "Nenhuma rota do iFood analisada hoje" else "Nenhuma corrida do InDrive analisada hoje"
                return
            }
            bestValue.setTextColor(GOLD)
            bestValue.text = String.format(BR, "R$ %.2f", b.price)
            val parts = ArrayList<String>()
            parts.add(if (b.route) "iFood" else "InDrive")
            if (b.route && b.orders > 0) parts.add(if (b.orders == 1) "1 pedido" else "${b.orders} pedidos")
            b.km?.let { parts.add(String.format(BR, "%.1f km", it)) }
            b.rpk?.let { parts.add(String.format(BR, "R$ %.2f/km", it)) }
            parts.add(java.text.SimpleDateFormat("HH:mm", BR).format(java.util.Date(b.t)))
            bestInfo.text = parts.joinToString(" · ")
            if (!first) {   // novo recorde: pisca de leve
                bestValue.scaleX = 1.15f; bestValue.scaleY = 1.15f
                bestValue.pivotX = 0f
                bestValue.animate().scaleX(1f).scaleY(1f).setDuration(350).start()
            }
        }
        paintBest()
        val bestTick = object : Runnable {
            override fun run() {
                if (menu == null) return
                paintBest()
                handler.postDelayed(this, 1000)
            }
        }

        // ---------- ações
        val openApp = actionBtn("Abrir app", null, TXT, rounded(SURFACE_HI, 12f, STROKE)) {
            closeMenu(animate = false)
            runCatching {
                svc.startActivity(Intent(svc, RotasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val turnOff = actionBtn("Desligar", R.drawable.ic_power, RED, rounded(0x24FF5252, 12f, 0x66FF5252)) {
            val ctx = svc.applicationContext
            closeMenu(animate = false)
            Toast.makeText(ctx, "Assistente de Rotas desligado. Já pode abrir o app do banco.", Toast.LENGTH_LONG).show()
            Power.turnOff(ctx)
        }
        val actions = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(openApp, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
            addView(turnOff, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
        }

        // ---------- montagem
        val content = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            addView(header)
            if (turno != null) {
                addView(section("Turno em andamento"))
                addView(turnoLinha())
            }
            if (ifood) {
                addView(section("Configurações rápidas · iFood"))
                addView(ifoodGroup)
            } else {
                addView(section("Aceite automático"))
                addView(autoRow)
                addView(limits, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
            addView(section(if (ifood) "Maior valor de hoje · iFood" else "Maior valor de hoje · InDrive"))
            addView(bestCard)
            addView(actions, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(label("Desligue antes de abrir apps de banco.", 10.5f, MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val panel = ScrollView(svc).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            background = rounded(PANEL, 22f, 0x4022C55E)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = dpf(12f)
            addView(content)
        }

        // janela: um pouco maior que o painel p/ a sombra; toque fora fecha
        val pad = dp(10)
        val root = FrameLayout(svc).apply {
            setPadding(pad, pad, pad, pad)
            clipToPadding = false
            addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_OUTSIDE) { closeMenu(); true } else false
            }
        }

        val w = menuWidth()
        val maxH = screenH() - dp(48)
        content.measure(
            View.MeasureSpec.makeMeasureSpec(w - 2 * pad, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = (content.measuredHeight + 2 * pad).coerceAtMost(maxH)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )

        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        panel.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h - 2 * pad)
        root.layoutParams = lp

        runCatching { wm.addView(root, lp) }.onSuccess {
            menu = root
            handler.postDelayed(bestTick, 1000)
            positionMenu()
            panel.alpha = 0f
            panel.scaleX = 0.92f
            panel.scaleY = 0.92f
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }

    // ------------------------------------------------------------------ turno

    /** Linha compacta "12,4 km · 1h05  [Finalizar]" (painel do iFood/InDrive durante o turno). */
    private fun turnoLinha(): View {
        val info = label(Turno.fmtKm(turno?.km ?: 0.0) + " · " + turnoDuracao(), 14f, TXT, true)
        val fim = label("Finalizar", 12.5f, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(0xFF2EA44F.toInt(), 10f)
            setOnClickListener { abrirFinalizarTurno() }
        }
        return LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = rounded(0x1A2F81F7, 14f, 0x552F81F7)
            addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(fim)
        }
    }

    /** Painel do contador do turno (fora do iFood/InDrive): km, tempo e "Finalizar turno". */
    private fun openMenuTurno() {
        if (menu != null) return
        val logo = FrameLayout(svc).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1554B8.toInt(), 0xFF2F81F7.toInt())
            ).apply { cornerRadius = dpf(12f) }
            addView(ImageView(svc).apply {
                setImageResource(R.drawable.ic_stat_rota)
                imageTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }
        val desde = turno?.ini?.let { java.text.SimpleDateFormat("HH:mm", BR).format(java.util.Date(it)) } ?: "--:--"
        val titleCol = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Turno em andamento", 16f, TXT, true))
            addView(label("GPS medindo · desde $desde", 11.5f, MUTED))
        }
        val close = label("✕", 13f, MUTED, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(SURFACE_HI) }
            setOnClickListener { closeMenu() }
        }
        val header = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(logo, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
            addView(titleCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(dp(30), dp(30)))
        }
        val kmVal = label(Turno.fmtKm(turno?.km ?: 0.0), 30f, 0xFF79B8FF.toInt(), true)
        val durVal = label(turnoDuracao(), 30f, TXT, true)
        fun bloco(v: TextView, t: String) = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(SURFACE, 14f, STROKE)
            addView(label(t.uppercase(BR), 10.5f, MUTED, true))
            addView(v)
        }
        val numeros = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(bloco(kmVal, "Rodados"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(5) })
            addView(bloco(durVal, "Tempo"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(5) })
        }
        val tick = object : Runnable {
            override fun run() {
                if (menu == null) return
                kmVal.text = Turno.fmtKm(turno?.km ?: 0.0)
                durVal.text = turnoDuracao()
                handler.postDelayed(this, 1000)
            }
        }
        val finalizar = actionBtn("Finalizar turno", null, 0xFFFFFFFF.toInt(),
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF1A7A3A.toInt(), 0xFF2EA44F.toInt()))
                .apply { cornerRadius = dpf(12f) }) { abrirFinalizarTurno() }
        val content = LinearLayout(svc).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(14))
            addView(header)
            addView(numeros, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
            addView(finalizar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
            addView(label("No iFood e no InDrive o assistente funciona normal; fora deles, conta os km do turno.", 10.5f, MUTED).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val panel = FrameLayout(svc).apply {
            background = rounded(PANEL, 22f, 0x552F81F7)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            elevation = dpf(12f)
            addView(content)
        }
        val pad = dp(10)
        val root = FrameLayout(svc).apply {
            setPadding(pad, pad, pad, pad)
            clipToPadding = false
            addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            setOnTouchListener { _, ev ->
                if (ev.action == MotionEvent.ACTION_OUTSIDE) { closeMenu(); true } else false
            }
        }
        val w = menuWidth()
        content.measure(
            View.MeasureSpec.makeMeasureSpec(w - 2 * pad, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = (content.measuredHeight + 2 * pad).coerceAtMost(screenH() - dp(48))
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        root.layoutParams = lp
        runCatching { wm.addView(root, lp) }.onSuccess {
            menu = root
            handler.postDelayed(tick, 1000)
            positionMenu()
            panel.alpha = 0f
            panel.scaleX = 0.92f
            panel.scaleY = 0.92f
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170)
                .setInterpolator(DecelerateInterpolator()).start()
        }
    }
}
