package com.prismo.drive

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.Locale

/**
 * Janelas nativas de atualização no visual do Prismo Drive (v2.1):
 * "Atualização disponível" (versões, tamanho e o que há de novo) e "Baixando atualização"
 * (anel em volta do ícone, porcentagem grande, barra, MB, velocidade e tempo restante).
 */
object JanelaAtualizacao {
    private const val VERDE = 0xFF4ADE80.toInt()
    private const val VERDE_ESC = 0xFF1A7A3A.toInt()
    private const val VERDE_MEIO = 0xFF2EA44F.toInt()
    private const val FUNDO = 0xFF0F151C.toInt()
    private const val CINZA = 0xFF9AA4AE.toInt()
    private const val CINZA_ESC = 0xFF6E7781.toInt()
    private val BR = Locale("pt", "BR")

    private fun Context.dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    private fun negrito() = Typeface.create("sans-serif-medium", Typeface.BOLD)
    private fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    private fun cheio() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    private fun fundo(cor: Int, raio: Float, borda: Int = 0, ctx: Context) = GradientDrawable().apply {
        setColor(cor); cornerRadius = ctx.dp(raio).toFloat()
        if (borda != 0) setStroke(ctx.dp(1f), borda)
    }

    // ───────── estrutura comum ─────────

    private class Base(val dlg: Dialog, val hero: LinearLayout, val corpo: LinearLayout, val raiz: LinearLayout)

    private fun base(a: Activity, cancelavel: Boolean, rodape: String): Base {
        val dlg = Dialog(a)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val raio = a.dp(26f).toFloat()
        val raiz = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(FUNDO); cornerRadius = raio; setStroke(a.dp(1f), 0x554ADE80) }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, raio) }
            }
        }
        val hero = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(a.dp(20f), a.dp(24f), a.dp(20f), a.dp(16f))
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(0xFF1D5C33.toInt(), 0xFF12301E.toInt(), FUNDO)
                setGradientCenter(0.5f, 0f)
                gradientRadius = a.resources.displayMetrics.widthPixels * 0.62f
            }
        }
        val corpo = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(a.dp(20f), a.dp(2f), a.dp(20f), a.dp(16f))
        }
        raiz.addView(hero, cheio())
        raiz.addView(corpo, cheio())
        // rodapé com linha
        raiz.addView(View(a).apply { setBackgroundColor(0xFF1A222C.toInt()) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, a.dp(1f)))
        raiz.addView(TextView(a).apply {
            text = rodape; textSize = 11.5f; setTextColor(CINZA_ESC); gravity = Gravity.CENTER
            setPadding(a.dp(12f), a.dp(11f), a.dp(12f), a.dp(13f))
        }, cheio())

        dlg.setContentView(raiz)
        dlg.setCancelable(cancelavel)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val larg = minOf((a.resources.displayMetrics.widthPixels * 0.88f).toInt(), a.dp(360f))
            w.setLayout(larg, WindowManager.LayoutParams.WRAP_CONTENT)
            w.setDimAmount(0.72f)
        }
        return Base(dlg, hero, corpo, raiz)
    }

    /** Ícone do app arredondado, com moldura verde e brilho. */
    private fun icone(a: Activity, tam: Float): View {
        val moldura = FrameLayout(a).apply {
            setPadding(a.dp(3.5f), a.dp(3.5f), a.dp(3.5f), a.dp(3.5f))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(VERDE, VERDE_ESC)).apply { cornerRadius = a.dp(tam * 0.29f).toFloat() }
            elevation = a.dp(14f).toFloat()
            if (Build.VERSION.SDK_INT >= 28) { outlineAmbientShadowColor = VERDE; outlineSpotShadowColor = VERDE }
        }
        val img = ImageView(a).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, a.dp(tam * 0.24f).toFloat()) }
            }
        }
        moldura.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return moldura
    }

    private fun titulo(a: Activity, t: String, topo: Float) = TextView(a).apply {
        text = t; textSize = 20f; setTextColor(Color.WHITE); typeface = negrito(); gravity = Gravity.CENTER
        layoutParams = cheio().apply { topMargin = a.dp(topo) }
    }

    private fun chip(a: Activity, t: String, novo: Boolean) = TextView(a).apply {
        text = t; textSize = 12.5f; typeface = negrito()
        setTextColor(if (novo) VERDE else CINZA)
        setPadding(a.dp(10f), a.dp(5f), a.dp(10f), a.dp(5f))
        background = fundo(if (novo) 0x144ADE80 else 0xFF161D26.toInt(), 10f, if (novo) 0x734ADE80 else 0xFF263040.toInt(), a)
    }

    private fun botao(a: Activity, t: String, tipo: Int, onClick: () -> Unit) = TextView(a).apply {
        // tipo: 0 principal (verde) · 1 contorno · 2 só texto
        text = t; gravity = Gravity.CENTER; typeface = negrito()
        textSize = if (tipo == 0) 16f else 14.5f
        setTextColor(when (tipo) { 0 -> Color.WHITE; 1 -> 0xFFC9D1D9.toInt(); else -> CINZA })
        background = when (tipo) {
            0 -> GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(VERDE_ESC, VERDE_MEIO, 0xFF3FBF63.toInt())).apply { cornerRadius = a.dp(16f).toFloat() }
            1 -> fundo(0xFF141B23.toInt(), 14f, 0xFF2A3542.toInt(), a)
            else -> null
        }
        if (tipo == 0) {
            elevation = a.dp(8f).toFloat()
            if (Build.VERSION.SDK_INT >= 28) { outlineAmbientShadowColor = VERDE_MEIO; outlineSpotShadowColor = VERDE_MEIO }
        }
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, a.dp(if (tipo == 0) 52f else 46f)).apply {
            topMargin = a.dp(if (tipo == 2) 6f else 0f)
        }
    }

    // ───────── novidades vindas das notas da versão ─────────

    /** As primeiras novidades da versão (as mais recentes), em frases curtas (sem emoji e sem a explicação depois de ":"). */
    fun novidades(notas: String, max: Int = 3): Pair<List<String>, Int> {
        val todas = notas.lines().mapNotNull { l ->
            val s = l.trim()
            if (s.startsWith("<!--")) return@mapNotNull null
            val m = Regex("^[•\\-*]\\s+(.*)$").find(s) ?: return@mapNotNull null
            var t = m.groupValues[1].replace(Regex("^[^\\p{L}\\p{N}]+"), "").trim()
            val corte = listOf(t.indexOf(':'), t.indexOf(',')).filter { it in 12..70 }.minOrNull()
            if (corte != null) t = t.substring(0, corte)
            if (t.length > 64) t = t.substring(0, 62).trimEnd() + "…"
            t.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
        }
        // desde a v2.1 as notas de cada versão vêm só com o que é novo, o mais recente primeiro
        val primeiras = todas.take(max)
        return Pair(primeiras, todas.size - primeiras.size)
    }

    // ───────── "Atualização disponível" ─────────

    fun perguntar(a: Activity, ev: JSONObject, baixar: () -> Unit, depois: () -> Unit) {
        val b = base(a, true, "🔒  Seus dados continuam salvos")
        b.hero.addView(icone(a, 76f), LinearLayout.LayoutParams(a.dp(76f), a.dp(76f)).apply { topMargin = a.dp(4f) })
        b.hero.addView(TextView(a).apply {
            text = "✦  NOVA VERSÃO"; textSize = 10.5f; typeface = negrito(); letterSpacing = 0.12f
            setTextColor(0xFF7EE2A0.toInt())
            setPadding(a.dp(11f), a.dp(4f), a.dp(11f), a.dp(4f))
            background = fundo(0x294ADE80, 99f, 0x734ADE80, a)
        }, wrap().apply { topMargin = a.dp(14f) })

        b.corpo.addView(titulo(a, "Atualização disponível", 8f))

        val linha = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val atual = ev.optString("atual").ifBlank { BuildConfig.VERSION_NAME }.substringBefore('-')
        val nova = ev.optString("versao")
        if (atual.isNotBlank() && atual != nova) {
            linha.addView(chip(a, atual, false))
            linha.addView(TextView(a).apply { text = "→"; textSize = 15f; typeface = negrito(); setTextColor(0xFF4B5563.toInt()); setPadding(a.dp(8f), 0, a.dp(8f), 0) })
        }
        linha.addView(chip(a, nova.ifBlank { "nova" }, true))
        val mb = ev.optLong("tamanho") / 1048576.0
        if (mb > 0) linha.addView(chip(a, String.format(BR, "%.1f MB", mb), false), wrap().apply { leftMargin = a.dp(8f) })
        b.corpo.addView(linha, cheio().apply { topMargin = a.dp(10f) })

        val (itens, resto) = novidades(ev.optString("notas"))
        if (itens.isNotEmpty()) {
            val caixa = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(a.dp(14f), a.dp(12f), a.dp(14f), a.dp(10f))
                background = fundo(0xFF131A22.toInt(), 16f, 0xFF1F2933.toInt(), a)
            }
            caixa.addView(TextView(a).apply {
                text = "O QUE HÁ DE NOVO"; textSize = 11f; typeface = negrito(); letterSpacing = 0.08f; setTextColor(0xFF8B949E.toInt())
            })
            itens.forEach { t ->
                val l = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
                l.addView(TextView(a).apply {
                    text = "✓"; textSize = 11f; typeface = negrito(); gravity = Gravity.CENTER; setTextColor(VERDE)
                    background = fundo(0x264ADE80, 6f, 0, a)
                }, LinearLayout.LayoutParams(a.dp(18f), a.dp(18f)).apply { topMargin = a.dp(1f) })
                l.addView(TextView(a).apply {
                    text = t; textSize = 13f; setTextColor(0xFFD0D7DE.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.1f)
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = a.dp(9f) })
                caixa.addView(l, cheio().apply { topMargin = a.dp(8f) })
            }
            if (resto > 0) caixa.addView(TextView(a).apply {
                text = "e mais $resto " + (if (resto == 1) "novidade" else "novidades") + " na aba Novidades"
                textSize = 11.5f; setTextColor(CINZA_ESC)
            }, cheio().apply { topMargin = a.dp(8f) })
            b.corpo.addView(caixa, cheio().apply { topMargin = a.dp(14f) })
        }

        b.corpo.addView(botao(a, "⬇   Baixar agora", 0) { b.dlg.dismiss(); baixar() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, a.dp(52f)).apply { topMargin = a.dp(16f) })
        b.corpo.addView(botao(a, "Mais tarde", 2) { b.dlg.dismiss(); depois() })
        b.dlg.setOnCancelListener { depois() }
        b.dlg.show()
    }

    // ───────── "Baixando atualização" ─────────

    /** Anel de progresso em volta do ícone (gira sozinho enquanto não sabe o tamanho). */
    class Anel(ctx: Context) : View(ctx) {
        private val esp = 8f * ctx.resources.displayMetrics.density
        private val trilho = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = esp; color = 0xFF1D2A22.toInt() }
        private val arco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = esp; strokeCap = Paint.Cap.ROUND }
        private val r = RectF()
        var pct = -1f
            set(v) { field = v; if (v >= 0) giro.cancel() else if (!giro.isStarted) giro.start(); invalidate() }
        private var rot = 0f
        private val giro = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { rot = it.animatedValue as Float; invalidate() }
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            r.set(esp / 2, esp / 2, w - esp / 2, h - esp / 2)
            arco.shader = SweepGradient(w / 2f, h / 2f, intArrayOf(VERDE_ESC, VERDE, VERDE_ESC), floatArrayOf(0f, 0.5f, 1f))
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); if (pct < 0) giro.start() }
        override fun onDetachedFromWindow() { giro.cancel(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            c.drawArc(r, 0f, 360f, false, trilho)
            if (pct < 0) c.drawArc(r, rot - 90f, 90f, false, arco)
            else c.drawArc(r, -90f, 360f * (pct / 100f).coerceIn(0f, 1f), false, arco)
        }
    }

    /** Barra verde com brilho (faixa que corre enquanto não sabe o tamanho). */
    class Barra(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val trilho = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A222C.toInt() }
        private val borda = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = d; color = 0xFF222C38.toInt() }
        private val enche = Paint(Paint.ANTI_ALIAS_FLAG)
        private val brilho = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x554ADE80; maskFilter = android.graphics.BlurMaskFilter(6f * d, android.graphics.BlurMaskFilter.Blur.NORMAL) }
        private val r = RectF(); private val f = RectF()
        var pct = -1f
            set(v) { field = v; if (v >= 0) corre.cancel() else if (!corre.isStarted) corre.start(); invalidate() }
        private var pos = 0f
        private val corre = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            addUpdateListener { pos = it.animatedValue as Float; invalidate() }
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            enche.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(VERDE_ESC, VERDE_MEIO, VERDE), null, Shader.TileMode.CLAMP)
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); if (pct < 0) corre.start() }
        override fun onDetachedFromWindow() { corre.cancel(); super.onDetachedFromWindow() }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val m = 4f * d  // espaço para o brilho
            r.set(0f, m, w, h - m)
            val rr = r.height() / 2
            c.drawRoundRect(r, rr, rr, trilho)
            if (pct < 0) {
                val seg = w * 0.3f; val x = -seg + (w + seg) * pos
                f.set(maxOf(0f, x), r.top, minOf(w, x + seg), r.bottom)
            } else f.set(0f, r.top, w * (pct / 100f).coerceIn(0f, 1f), r.bottom)
            if (f.width() > 0) {
                c.drawRoundRect(f, rr, rr, brilho)
                c.drawRoundRect(f, rr, rr, enche)
            }
            c.drawRoundRect(r, rr, rr, borda)
        }
    }

    class Progresso(val dlg: Dialog, private val anel: Anel, private val barra: Barra,
                    private val pctTxt: TextView, private val mbTxt: TextView, private val velTxt: TextView) {
        private var t0 = 0L; private var b0 = 0L
        private var ultimaVel = 0.0

        private fun vel(t: String) {
            val s = SpannableString("●  $t")
            s.setSpan(ForegroundColorSpan(VERDE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            velTxt.text = s
        }

        fun atualizar(pct: Int, baixado: Long, total: Long, aviso: String?) {
            val p = if (pct >= 0) pct else if (total > 0) (baixado * 100 / total).toInt() else -1
            anel.pct = p.toFloat(); barra.pct = p.toFloat()
            if (p >= 0) {
                val s = SpannableString("$p%")
                val i = s.length - 1
                s.setSpan(RelativeSizeSpan(0.5f), i, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.setSpan(ForegroundColorSpan(VERDE), i, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                pctTxt.text = s
            }
            val mb = baixado / 1048576.0
            val totMb = total / 1048576.0
            mbTxt.text = if (totMb > 0) String.format(BR, "%.1f de %.1f MB", mb, totMb) else String.format(BR, "%.1f MB", mb)

            if (aviso != null) { velTxt.text = aviso; return }
            val agora = SystemClock.elapsedRealtime()
            if (t0 == 0L || baixado < b0) { t0 = agora; b0 = baixado; vel("Conectando…"); return }
            val dt = (agora - t0) / 1000.0
            if (dt < 0.8) return
            val vel = (baixado - b0) / dt  // bytes/s desde o começo
            ultimaVel = if (ultimaVel <= 0) vel else ultimaVel * 0.6 + vel * 0.4
            val velTexto = if (ultimaVel >= 1048576) String.format(BR, "%.1f MB/s", ultimaVel / 1048576) else String.format(BR, "%.0f KB/s", ultimaVel / 1024)
            val falta = if (total > 0 && ultimaVel > 0) ((total - baixado) / ultimaVel).toLong() else -1
            val faltaTexto = when {
                falta < 0 -> ""
                falta < 60 -> " · faltam ${maxOf(1, falta)} s"
                else -> " · faltam ${(falta + 59) / 60} min"
            }
            vel("$velTexto$faltaTexto")
        }
    }

    fun progresso(a: Activity, segundoPlano: () -> Unit): Progresso {
        val b = base(a, false, "🔔  Avisamos quando estiver pronto para instalar")
        b.hero.setPadding(a.dp(20f), a.dp(22f), a.dp(20f), a.dp(6f))
        val caixa = FrameLayout(a)
        val anel = Anel(a)
        caixa.addView(anel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        caixa.addView(icone(a, 70f), FrameLayout.LayoutParams(a.dp(70f), a.dp(70f), Gravity.CENTER))
        b.hero.addView(caixa, LinearLayout.LayoutParams(a.dp(128f), a.dp(128f)))

        b.corpo.addView(titulo(a, "Baixando atualização", 6f))
        val pct = TextView(a).apply {
            text = "…"; textSize = 38f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            letterSpacing = -0.02f
        }
        b.corpo.addView(pct, cheio().apply { topMargin = a.dp(2f) })
        val barra = Barra(a)
        b.corpo.addView(barra, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, a.dp(18f)).apply { topMargin = a.dp(8f) })

        val linha = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mb = TextView(a).apply { text = "Preparando…"; textSize = 12.5f; setTextColor(0xFFE6EDF3.toInt()); typeface = negrito() }
        val vel = TextView(a).apply { text = ""; textSize = 12.5f; setTextColor(CINZA); gravity = Gravity.END; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        linha.addView(mb, wrap())
        linha.addView(vel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = a.dp(8f) })
        b.corpo.addView(linha, cheio().apply { topMargin = a.dp(6f) })

        b.corpo.addView(botao(a, "Continuar em segundo plano", 1) { segundoPlano() }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, a.dp(48f)).apply { topMargin = a.dp(16f) })
        val p = Progresso(b.dlg, anel, barra, pct, mb, vel)
        anel.pct = -1f; barra.pct = -1f
        b.dlg.show()
        return p
    }
}
