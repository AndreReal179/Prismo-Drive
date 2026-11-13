package com.prismo.drive.rota

import com.prismo.drive.R
import com.prismo.drive.BuildConfig

import android.app.Activity
import android.app.AlertDialog
import android.widget.ProgressBar
import android.app.NotificationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class RotasActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var statusDot: View
    private lateinit var statusIcon: android.widget.ImageView
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var activateBtn: TextView
    private lateinit var notifWarn: LinearLayout
    private lateinit var sampleBox: LinearLayout

    private enum class Page { HOME, REPORT, CONFIG }
    private val REQ_LOC = 21
    private val REQ_LOC_SEMPRE = 22
    private var page = Page.REPORT          // o Relatório é a tela inicial
    private var sampleKind = 0            // 0 = sem simulação, 1 = iFood, 2 = InDrive
    private var homeShown = false
    private var drawerOpen = false
    private val report by lazy { ReportScreen(this) }
    private lateinit var body: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var drawer: LinearLayout
    private lateinit var scrim: View

    private val BG = 0xFF080C10.toInt()
    private val CARD = 0xFF0D1117.toInt()
    private val LINE = 0xFF21262D.toInt()
    private val INPUT = 0xFF080C10.toInt()
    private val TXT = 0xFFFFFFFF.toInt()
    private val MUTED = 0xFF8B949E.toInt()
    private val GREEN = 0xFF4CAF50.toInt()
    private val AMBER = 0xFFFFB300.toInt()
    private val GOLD = 0xFF4ADE80.toInt()      // destaque (verde do Prismo Drive)
    private val BLUE = 0xFF58A6FF.toInt()      // azul da marca
    private val RED = 0xFFEF5350.toInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    /** Degradê verde do Prismo Drive. */
    private fun brandGradient(radius: Int) = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF1A7A3A.toInt(), 0xFF2EA44F.toInt())
    ).apply { cornerRadius = dp(radius).toFloat() }

    /** Pinta o texto com o degradê da marca. */
    private fun gradientText(t: TextView) {
        t.post {
            val w = t.paint.measureText(t.text.toString())
            t.paint.shader = android.graphics.LinearGradient(0f, 0f, w, 0f,
                intArrayOf(0xFF86EFAC.toInt(), 0xFF2EA44F.toInt(), 0xFF4ADE80.toInt()), null,
                android.graphics.Shader.TileMode.CLAMP)
            t.invalidate()
        }
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun tx(t: CharSequence, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(CARD, 18, LINE)
    }

    /** Título de cartão: selo com ícone (opcional) + título + subtítulo. */
    private fun title(t: String, sub: String? = null, icon: String? = null, accent: Int = GOLD): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (icon != null) addView(badge(icon, accent, 38), LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(12) })
            val col = LinearLayout(this@RotasActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tx(t, 16.5f, TXT, true))
            if (sub != null) col.addView(tx(sub, 12.5f, MUTED), lp(2))
            addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        }

    /** Selo quadrado arredondado com ícone, no tom da cor [accent]. */
    private fun badge(icon: String, accent: Int, size: Int) = tx(icon, size * 0.42f, accent, true).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = rounded((accent and 0x00FFFFFF) or 0x22000000, size / 3, (accent and 0x00FFFFFF) or 0x3D000000)
    }

    /** Cabeçalho de página: faixa com degradê suave verde, ícone grande e subtítulo. */
    private fun pageHeader(icon: String, t: String, sub: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x3D1A7A3A, 0x172EA44F)).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), 0x472EA44F)
        }
        addView(tx(icon, 22f, TXT).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = brandGradient(15)
        }, LinearLayout.LayoutParams(dp(50), dp(50)).apply { rightMargin = dp(14) })
        val col = LinearLayout(this@RotasActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tx(t, 19f, TXT, true))
        col.addView(tx(sub, 12.5f, 0xFFC9D1D9.toInt()), lp(3))
        addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    /** Rótulo pequeno em maiúsculas para separar blocos dentro de um cartão. */
    private fun caps(t: String) = tx(t.uppercase(), 10.5f, MUTED, true).apply { letterSpacing = 0.1f }

    private fun divider() = View(this).apply { setBackgroundColor(LINE) }

    /** Grade de opções (3 por linha); a escolhida fica com o degradê da marca. */
    private fun optionGrid(items: List<String>, sel: Int, onPick: (Int) -> Unit): LinearLayout {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val views = ArrayList<TextView>()
        var row: LinearLayout? = null
        items.forEachIndexed { i, label ->
            if (i % 3 == 0) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                row = r
                grid.addView(r, lp(if (i == 0) 0 else 8))
            }
            val chip = tx(label, 13f, MUTED, true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dp(6), dp(11), dp(6), dp(11))
            }
            views.add(chip)
            row!!.addView(chip, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i % 3 != 0) leftMargin = dp(8) })
        }
        val rem = items.size % 3
        if (rem != 0) repeat(3 - rem) {
            row!!.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { leftMargin = dp(8) })
        }
        fun paint(s: Int) {
            views.forEachIndexed { i, v ->
                val on = i == s
                v.setTextColor(if (on) TXT else MUTED)
                v.background = if (on) brandGradient(11) else rounded(INPUT, 11, LINE)
            }
        }
        paint(sel)
        views.forEachIndexed { i, v -> v.setOnClickListener { paint(i); onPick(i) } }
        return grid
    }

    private fun styleButton(b: TextView, primary: Boolean) {
        b.setTextColor(TXT)
        b.background = if (primary) brandGradient(12) else rounded(0xFF161B22.toInt(), 12, LINE)
    }

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        styleButton(this, primary)
        setOnClickListener { onClick() }
    }

    private fun step(n: Int, head: String, body: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val num = tx(n.toString(), 12.5f, TXT, true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1A7A3A.toInt(), 0xFF2EA44F.toInt())).apply { shape = GradientDrawable.OVAL }
        }
        addView(num, LinearLayout.LayoutParams(dp(26), dp(26)).apply { topMargin = dp(1) })
        val col = LinearLayout(this@RotasActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(tx(head, 14.5f, TXT, true))
            addView(tx(body, 13f, MUTED), lp(2))
        }
        addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(12) })
    }

    private fun field(label: String, value: Float, onChange: (Float) -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tx(label, 12.5f, MUTED))
            val et = EditText(this@RotasActivity).apply {
                setText(value.toString().replace('.', ','))
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setTextColor(TXT)
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                background = rounded(INPUT, 10, LINE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setSelectAllOnFocus(true)
                addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        s?.toString()?.replace(',', '.')?.toFloatOrNull()?.let {
                            onChange(it); refreshSample()
                        }
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            addView(et, lp(6))
        }

    private fun pair(a: View, b: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(a, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(6) })
        addView(b, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(6) })
    }

    private fun switchRow(head: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val col = LinearLayout(this@RotasActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(tx(head, 14.5f, TXT, true))
                addView(tx(sub, 12.5f, MUTED), lp(2))
            }
            addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            addView(Switch(this@RotasActivity).apply {
                isChecked = checked
                thumbTintList = android.content.res.ColorStateList(states, intArrayOf(0xFFFFFFFF.toInt(), 0xFFB1BAC4.toInt()))
                trackTintList = android.content.res.ColorStateList(states, intArrayOf(0xFF2EA44F.toInt(), 0xFF30363D.toInt()))
                setOnCheckedChangeListener { _, v -> onChange(v); refreshSample() }
            })
        }

    /** Cartão com cabeçalho que abre/fecha o conteúdo. */
    private fun collapsible(head: String, sub: String, open: Boolean, content: LinearLayout, icon: String? = null): LinearLayout {
        val c = card()
        val arrow = tx(if (open) "▾" else "▸", 18f, MUTED)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title(head, sub, icon), LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(arrow)
        }
        content.visibility = if (open) View.VISIBLE else View.GONE
        header.setOnClickListener {
            val show = content.visibility != View.VISIBLE
            content.visibility = if (show) View.VISIBLE else View.GONE
            arrow.text = if (show) "▾" else "▸"
        }
        c.addView(header)
        c.addView(content, lp(14))
        return c
    }

    private fun stack(vararg rows: View, gap: Int = 14) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        rows.forEachIndexed { i, v -> addView(v, lp(if (i == 0) 0 else gap)) }
    }

    // ------------------------------------------------------------------ estrutura (menu lateral)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 26) {
            Toast.makeText(this, "O Assistente de rotas precisa do Android 8 ou mais novo", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        prefs = Prefs(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = FrameLayout(this).apply { setBackgroundColor(BG) }

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(34), dp(16), dp(8))
            setBackgroundColor(BG)
        }
        val burger = tx("☰", 24f, TXT, true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { setDrawer(true) }
        }
        titleView = tx("Assistente de Rotas", 20f, TXT, true)
        bar.addView(burger)
        bar.addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
        content.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(40))
        }
        content.addView(
            ScrollView(this).apply { isFillViewport = true; addView(body) },
            LinearLayout.LayoutParams(MATCH, 0, 1f)
        )
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        scrim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
            alpha = 0f
            setOnClickListener { setDrawer(false) }
        }
        root.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        drawer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0F1A14.toInt(), 0xFF0B1210.toInt(), 0xFF080C10.toInt()))
            setPadding(dp(14), dp(44), dp(14), dp(16))
            visibility = View.GONE
            translationX = -dp(290).toFloat()
            isClickable = true
        }
        root.addView(drawer, FrameLayout.LayoutParams(dp(280), MATCH))

        setContentView(root)
        showPage()
        askNotifications(false)
    }

    override fun onDestroy() {
        verPulse?.cancel()
        super.onDestroy()
    }

    private fun setDrawer(open: Boolean) {
        drawerOpen = open
        if (open) {
            buildDrawer()
            scrim.visibility = View.VISIBLE
            drawer.visibility = View.VISIBLE
            scrim.animate().alpha(1f).setDuration(180).start()
            drawer.animate().translationX(0f).setDuration(200).start()
        } else {
            scrim.animate().alpha(0f).setDuration(160).withEndAction { scrim.visibility = View.GONE }.start()
            drawer.animate().translationX(-dp(290).toFloat()).setDuration(180)
                .withEndAction { drawer.visibility = View.GONE }.start()
        }
    }

    private fun drawerRow(
        icon: String, text: String, selected: Boolean, indent: Int,
        trailing: CharSequence? = null, trailingColor: Int = MUTED, onClick: () -> Unit
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12 + indent), dp(12), dp(12), dp(12))
            if (selected) background = rounded(0x262EA44F, 12, 0x3D2EA44F)
            addView(tx(icon, if (indent > 0) 14f else 16f, TXT).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(26), WRAP).apply { rightMargin = dp(10) })
            addView(tx(text, if (indent > 0) 15f else 16f, if (selected) GOLD else TXT, selected || indent == 0),
                LinearLayout.LayoutParams(0, WRAP, 1f))
            if (trailing != null) addView(tx(trailing, 12.5f, trailingColor, true))
            setOnClickListener { onClick() }
        }

    private var verPulse: android.animation.Animator? = null

    /** Versão do Prismo Drive numa pílula discreta. */
    private fun versionBlock(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tx("Prismo Drive v${BuildConfig.VERSION_NAME}", 11.5f, 0xFFC9D1D9.toInt(), true).apply {
            setPadding(dp(9), dp(3), dp(9), dp(4))
            background = rounded(0xFF161B22.toInt(), 10, LINE)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        return col
    }

    private fun buildDrawer() {
        verPulse?.cancel()
        verPulse = null
        drawer.removeAllViews()
        val name = tx("Assistente de Rotas", 22f, TXT, true)
        gradientText(name)
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(name)
            addView(versionBlock(), lp(2))
        }
        name.setOnClickListener { navigate(Page.REPORT) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(12), 0, dp(4), dp(16))
            addView(names, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(powerButton(), LinearLayout.LayoutParams(dp(42), dp(42)))
        }
        drawer.addView(head, lp())
        drawer.addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)))

        drawer.addView(drawerRow("📊", "Relatório", page == Page.REPORT, 0) { navigate(Page.REPORT) }, lp(10))
        drawer.addView(drawerRow("⚙️", "Configurações", page == Page.CONFIG, 0) { navigate(Page.CONFIG) }, lp(4))
        drawer.addView(drawerRow("❓", "Ajuda", page == Page.HOME, 0) { navigate(Page.HOME) }, lp(4))
        drawer.addView(divider(), LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(12) })
        drawer.addView(drawerRow("🏠", "Voltar ao Prismo Drive", false, 0) { finish() }, lp(10))
    }

    /**
     * Botão liga/desliga ao lado do nome no menu: verde = ligado, vermelho = desligado.
     * Toque liga ou desliga o serviço na hora.
     */
    private fun powerButton(): View {
        val on = RotasStatus.ligado(this)
        val color = if (on) GREEN else RED
        return FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor((color and 0x00FFFFFF) or 0x26000000)
                setStroke(dp(1), (color and 0x00FFFFFF) or 0x8C000000.toInt())
            }
            addView(android.widget.ImageView(this@RotasActivity).apply {
                setImageResource(R.drawable.ic_power)
                imageTintList = android.content.res.ColorStateList.valueOf(color)
            }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            contentDescription = if (on) "Desligar Assistente de Rotas" else "Ligar Assistente de Rotas"
            setOnClickListener { onPowerClick() }
        }
    }

    private fun navigate(p: Page) {
        page = p
        setDrawer(false)
        showPage()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            drawerOpen -> setDrawer(false)
            page != Page.REPORT -> navigate(Page.REPORT)
            else -> super.onBackPressed()
        }
    }

    private fun showPage() {
        body.removeAllViews()
        sampleKind = 0
        homeShown = false
        when (page) {
            Page.HOME -> { titleView.text = "Ajuda"; buildHome() }
            Page.REPORT -> { titleView.text = "Relatório"; report.build(body) }
            Page.CONFIG -> { titleView.text = "Configurações"; buildConfig() }
        }
    }

    // ------------------------------------------------------------------ início
    private fun buildHome() {
        val col = body
        col.addView(pageHeader("❓", "Ajuda", "Como usar o Assistente de Rotas · versão ${BuildConfig.VERSION_NAME}"), lp(4))

        // status do serviço
        val status = card()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        statusIcon = android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_power) }
        statusDot = FrameLayout(this).apply {
            addView(statusIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        }
        top.addView(statusDot, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(14) })
        val st = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statusTitle = tx("", 17f, TXT, true)
        statusSub = tx("", 13f, MUTED)
        st.addView(statusTitle)
        st.addView(statusSub, lp(2))
        top.addView(st, LinearLayout.LayoutParams(0, WRAP, 1f))
        status.addView(top)
        // botão único: Ligar (desligado/travado) ou Desligar (ligado)
        activateBtn = button("", true) { onPowerClick() }
        status.addView(activateBtn, lp(16))

        // aviso quando as notificações estão bloqueadas (sem elas o botão Desligar não aparece na barra)
        notifWarn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = rounded(0x1FFFB300, 12, 0x55FFB300)
            addView(tx("Notificações bloqueadas", 13.5f, AMBER, true))
            addView(tx("Permita as notificações para o botão \"Desligar\" ficar na barra e para receber os avisos de atualização.", 12.5f, MUTED), lp(2))
            addView(button("Permitir notificações", false) { askNotifications(true) }, lp(10))
        }
        status.addView(notifWarn, lp(12))
        col.addView(status, lp(12))

        val bank = card().apply {
            addView(title("Vai abrir o app do banco?", "Desligue antes, senão o banco não abre", "🏦", AMBER))
            addView(tx("Toque em \"Desligar\" no aviso da barra de notificações, no botão vermelho/verde do menu ☰ " +
                "ou no menu do botão flutuante. Com o Assistente de Rotas desligado os apps de banco abrem normalmente. " +
                "Para voltar a rodar, ligue de novo pelo mesmo botão.",
                13f, MUTED), lp(12))
        }
        col.addView(bank, lp(12))

        val guide = stack(
            step(1, "Ligue o Assistente de Rotas",
                "Toque em \"Ligar Assistente de Rotas\" acima. Na primeira vez abre a acessibilidade: escolha Assistente de Rotas e ligue a chave."),
            step(2, "Ajuste seus valores",
                "Menu ☰ → Configurações → aba iFood ou InDrive: gasolina, consumo e os R$/km. O quadro \"No mês\" mostra valores de referência."),
            step(3, "Escolha o alerta",
                "Menu ☰ → Configurações: escolha o som e a vibração que tocam a cada rota nova."),
            step(4, "Abra o app de corridas",
                "O selo aparece em cada corrida/rota. Na tela de detalhe: toque curto recolhe, toque longo abre o app."),
            step(5, "Acompanhe no Relatório",
                "Tudo o que foi analisado fica no Relatório (tela inicial), com gráficos por dia, semana e mês."),
            step(6, "Terminou? Desligue",
                "Desligue antes de usar o app do banco.")
        )
        col.addView(collapsible("Como começar", "6 passos rápidos", !Power.isEnabled(this), guide, "🚀"), lp(12))

        val restricted = stack(
            tx("No Android 13 ou mais novo, apps instalados fora da Play Store vêm com a chave bloqueada " +
                "(aparece \"Configuração restrita\"). Para liberar:", 13f, MUTED),
            step(1, "Tente ativar uma vez",
                "Toque em \"Ligar Assistente de Rotas\" e na chave do Assistente de Rotas. O aviso de configuração restrita aparece."),
            step(2, "Abra as informações do app",
                "Toque no botão abaixo (ou segure o ícone do app → Informações do app)."),
            step(3, "Permita as configurações restritas",
                "Toque nos 3 pontinhos ⋮ no canto superior direito e escolha \"Permitir configurações restritas\". Confirme com senha ou digital."),
            step(4, "Volte e ative",
                "Acessibilidade → Apps instalados → Assistente de Rotas → ligar."),
            button("Abrir informações do app", false) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        )
        col.addView(collapsible("Não consigo ativar", "Chave cinza ou \"Configuração restrita\"", false, restricted, "🔒"), lp(12))

        val trouble = stack(
            tx("• Desligue e ligue de novo a chave do serviço.\n" +
                "• Tire o app da economia de bateria (botão abaixo) e deixe como \"Sem restrições\".\n" +
                "• Mantenha o app de corridas aberto na lista ou na tela de detalhe da corrida.", 13f, MUTED),
            button("Ajustes de bateria", false) {
                runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
        )
        col.addView(collapsible("O selo não aparece ou some", "Bateria e permissões", false, trouble, "🛠️"), lp(12))

        val adb = stack(
            tx("Libere uma única vez pelo computador, com a depuração USB ligada:", 13f, MUTED),
            tx("adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS", 12.5f, 0xFF7EE2A0.toInt()).apply {
                typeface = Typeface.MONOSPACE
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(INPUT, 10, LINE)
                setTextIsSelectable(true)
            },
            tx("Depois disso o botão liga o serviço direto, sem abrir a acessibilidade.", 13f, MUTED)
        )
        col.addView(collapsible("Ligar com 1 toque", "Opcional · pelo computador", false, adb, "⚡"), lp(12))

        homeShown = true
        updateStatus()
    }

    // ------------------------------------------------------------------ referência do mês (vinda do Relatório)
    private fun money(v: Double) = String.format(java.util.Locale("pt", "BR"), "R$ %.2f", v)

    private fun monthName(): String {
        val m = arrayOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho",
            "agosto", "setembro", "outubro", "novembro", "dezembro")
        return m[java.util.Calendar.getInstance().get(java.util.Calendar.MONTH)]
    }

    /** Quadro discreto "No mês": base para os campos RUIM / ÓTIMO, com atalho "Usar". */
    private fun refBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(0xFF0B0F14.toInt(), 12, LINE)
    }

    private fun refHeader(box: LinearLayout, n: Int, unit: String) {
        box.addView(tx("📊  NO MÊS · ${monthName().uppercase()} · $n $unit", 10.5f, MUTED, true).apply { letterSpacing = 0.06f })
    }

    private fun refLine(label: String, value: String, detail: String, color: Int, onUse: (() -> Unit)?): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val dot = View(this@RotasActivity).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            }
            addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(9) })
            val col = LinearLayout(this@RotasActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tx(android.text.SpannableStringBuilder().apply {
                append(label + "  ")
                val st = length
                append(value)
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), st, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(TXT), st, length, 0)
            }, 13f, MUTED))
            col.addView(tx(detail, 11.5f, MUTED), lp(1))
            addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (onUse != null) addView(tx("Usar", 12.5f, GOLD, true).apply {
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener {
                    onUse()
                    Toast.makeText(this@RotasActivity, "$label $value aplicado", Toast.LENGTH_SHORT).show()
                    showPage()
                }
            })
        }

    private fun pctOf(t: MonthRef.Tier, unit: String) =
        "${t.pct}% das $unit" + (t.avg?.let { " · em média ${money(it)}" } ?: "")

    /** Referência para RUIM abaixo de / ÓTIMO a partir de (R$/km). */
    private fun classRef(r: MonthRef.Ref?, unit: String, setRuim: (Float) -> Unit, setOtimo: (Float) -> Unit): View {
        val box = refBox()
        if (r == null) {
            refHeader(box, 0, unit)
            box.addView(tx("Ainda sem $unit este mês. Os valores aparecem aqui conforme o Relatório for registrando.", 12.5f, MUTED), lp(6))
            return box
        }
        refHeader(box, r.n, unit)
        box.addView(refLine("RUIM abaixo de", money(r.mid) + "/km", pctOf(r.low, unit), 0xFFEF5350.toInt()) {
            setRuim(r.mid.toFloat())
        }, lp(10))
        box.addView(refLine("Médio", money(r.mid) + " a " + money(r.high), pctOf(r.medium, unit), AMBER, null), lp(8))
        box.addView(refLine("ÓTIMO a partir de", money(r.high) + "/km", pctOf(r.top, unit), GREEN) {
            setOtimo(r.high.toFloat())
        }, lp(8))
        if (r.n < 10) box.addView(tx("Poucos dados ainda: os valores ficam mais precisos com o uso.", 11f, MUTED), lp(8))
        return box
    }

    /** Referência do valor por pedido: a faixa com mais pedidos no mês. */
    private fun orderRef(r: MonthRef.Ref?): View {
        val box = refBox()
        if (r == null) {
            refHeader(box, 0, "rotas")
            box.addView(tx("Ainda sem rotas do iFood este mês.", 12.5f, MUTED), lp(6))
            return box
        }
        refHeader(box, r.n, "rotas")
        val i = r.topIdx
        val t = r.tier(i)
        val (name, range, color) = when (i) {
            0 -> Triple("Baixo", "abaixo de ${money(r.mid)}", 0xFFEF5350.toInt())
            1 -> Triple("Médio", "${money(r.mid)} a ${money(r.high)}", AMBER)
            else -> Triple("Alto", "${money(r.high)} ou mais", GREEN)
        }
        val big = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(tx("${t.pct}%", 24f, color, true))
            addView(tx("  dos pedidos ficam em", 12.5f, MUTED).apply { setPadding(0, 0, 0, dp(4)) })
        }
        box.addView(big, lp(8))
        box.addView(tx(t.avg?.let { "≈ ${money(it)} por pedido" } ?: "—", 17f, TXT, true), lp(0))
        box.addView(tx("Faixa $name: $range", 11.5f, MUTED), lp(2))
        box.addView(View(this).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = dp(10) })
        box.addView(refLine("Baixo", "abaixo de ${money(r.mid)}", pctOf(r.low, "rotas"), 0xFFEF5350.toInt(), null), lp(10))
        box.addView(refLine("Médio", "${money(r.mid)} a ${money(r.high)}", pctOf(r.medium, "rotas"), AMBER, null), lp(8))
        box.addView(refLine("Alto", "${money(r.high)} ou mais", pctOf(r.top, "rotas"), GREEN, null), lp(8))
        return box
    }

    /** Gasolina e lucro: preço e consumo valem para todos os apps. */
    // ------------------------------------------------------------------ configurações
    private var cfgApp = 0                       // 0 = iFood, 1 = InDrive
    private val secAbertas = HashSet<String>()   // seções abertas (continuam abertas ao trocar de aba)

    private fun br(v: Float) = String.format(java.util.Locale("pt", "BR"), "R$ %.2f", v)
    private fun num1(v: Float) = String.format(java.util.Locale("pt", "BR"), "%.1f", v)

    /** Abas iFood / InDrive no topo das Configurações. */
    private fun abasApp(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = rounded(0xFF0B0F14.toInt(), 16, LINE)
        }
        listOf("🍔  iFood", "🚗  InDrive").forEachIndexed { i, nome ->
            val on = i == cfgApp
            box.addView(tx(nome, 14f, if (on) TXT else MUTED, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(12), 0, dp(12))
                background = if (on) brandGradient(12) else null
                setOnClickListener { if (cfgApp != i) { cfgApp = i; showPage() } }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i == 0) rightMargin = dp(2) else leftMargin = dp(2) })
        }
        return box
    }

    /**
     * Seção que abre e fecha: selo com ícone, título, resumo da configuração atual e seta.
     * O conteúdo só é montado quando abre (mais leve).
     */
    private fun secao(key: String, icon: String, accent: Int, head: String, resumo: String, build: (LinearLayout) -> Unit): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(CARD, 18, LINE)
        }
        val seta = tx("⌄", 20f, MUTED, true).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(12), dp(14))
            addView(badge(icon, accent, 40), LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(12) })
            val col = LinearLayout(this@RotasActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tx(head, 15.5f, TXT, true))
            col.addView(tx(resumo, 12f, MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, lp(2))
            addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(seta, LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        val conteudo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        var montado = false
        fun aplicar(aberta: Boolean, animar: Boolean) {
            if (aberta && !montado) {
                conteudo.addView(divider(), LinearLayout.LayoutParams(MATCH, 1).apply { bottomMargin = dp(14) })
                build(conteudo); montado = true
            }
            conteudo.visibility = if (aberta) View.VISIBLE else View.GONE
            c.background = rounded(CARD, 18, if (aberta) (accent and 0x00FFFFFF) or 0x66000000 else LINE)
            val rot = if (aberta) 0f else -90f
            if (animar) seta.animate().rotation(rot).setDuration(180).start() else seta.rotation = rot
            if (animar && aberta) { conteudo.alpha = 0f; conteudo.animate().alpha(1f).setDuration(220).start() }
        }
        header.setOnClickListener {
            val abrir = !secAbertas.contains(key)
            if (abrir) secAbertas.add(key) else secAbertas.remove(key)
            aplicar(abrir, true)
        }
        c.addView(header)
        c.addView(conteudo)
        aplicar(secAbertas.contains(key), false)
        return c
    }

    private fun grupo(t: String) = tx(t.uppercase(), 11f, 0xFF7EE2A0.toInt(), true).apply {
        letterSpacing = 0.14f
        setPadding(dp(4), 0, 0, 0)
    }

    private fun conteudoGasolina(c: LinearLayout, withMinRpk: Boolean) {
        c.addView(tx("Gasto = km ÷ consumo × preço do litro · vale para os dois apps", 12f, MUTED))
        c.addView(pair(
            field("Gasolina (R$/litro)", prefs.fuelPrice) { prefs.fuelPrice = it },
            field("Consumo (km/litro)", prefs.kmPerLiter) { prefs.kmPerLiter = it }
        ), lp(12))
        if (withMinRpk) c.addView(field("R$/km mínimo (abaixo disso sugere valor)", prefs.minRpk) { prefs.minRpk = it }, lp(12))
    }

    private fun buildConfig() {
        body.addView(pageHeader("⚙️", "Configurações", "Ajustes do Assistente de rotas para cada app"), lp(4))
        body.addView(abasApp(), lp(14))

        if (cfgApp == 0) {
            // ───────── iFood
            val ifRecs = MonthRef.monthRecs(this, route = true)
            body.addView(grupo("iFood"), lp(18))
            val est = IfoodSaldo.estado(this)
            body.addView(secao("if_rec", "💰", GREEN, "Recebimento automático",
                if (prefs.ifoodSaldoAuto) "Ligado" + (if (est.has("ts")) " · último ${money(est.optDouble("valor", 0.0) + est.optDouble("bonus", 0.0))}" else "") else "Desligado"
            ) { c ->
                c.addView(switchRow("Lançar rotas concluídas", "\"Você ganhou R$ …\" vira \"Recebimento iFood\" no faturamento e na carteira", prefs.ifoodSaldoAuto) {
                    prefs.ifoodSaldoAuto = it
                })
                val txtEst = if (est.has("ts")) {
                    val quando = java.text.SimpleDateFormat("dd/MM 'às' HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(est.optLong("ts")))
                    val bn = est.optDouble("bonus", 0.0)
                    "Último lançado: ${money(est.optDouble("valor", 0.0))}" + (if (bn > 0) " + ${money(bn)} de bônus" else "") + " · $quando"
                } else "Ainda nenhuma rota concluída lançada."
                c.addView(tx(txtEst, 12f, MUTED), lp(12))
                c.addView(button("💰 Testar comemoração", false) {
                    if (!RotasStatus.ligado(this)) {
                        Toast.makeText(this, "Ligue o assistente para ver o botão flutuante", Toast.LENGTH_SHORT).show()
                    } else {
                        sendBroadcast(Intent(this, PowerReceiver::class.java).setAction(Notif.ACTION_TESTE_MOEDA))
                        Toast.makeText(this, "Olhe o botão flutuante 👀", Toast.LENGTH_SHORT).show()
                    }
                }, lp(12))
                c.addView(tx("O tempo da rota conta de \"EM DESLOCAMENTO\" até \"Oba, mais uma rota concluída!\". O bônus verde (+ R$) entra junto. Cada tela é lançada uma vez só.", 11.5f, MUTED), lp(10))
            }, lp(8))
            val nv = KmGps.nivel(this)
            body.addView(secao("if_km", "📍", BLUE, "Km da rota (GPS)",
                when (nv) { 2 -> "GPS ativo · mede os km de cada rota"; 1 -> "GPS só com a tela ligada · toque para ajustar"; else -> "Sem localização · usando km estimado da oferta" }
            ) { c ->
                c.addView(tx("Em \"EM DESLOCAMENTO\" o GPS começa a medir e em \"Oba, mais uma rota concluída!\" ele para. Os km vão para a Linha do tempo com o selo GPS e entram no consumo de combustível. O GPS fica desligado fora das rotas.", 12f, MUTED))
                val (cor, txt) = when (nv) {
                    2 -> Pair(GREEN, "✅ Localização permitida o tempo todo")
                    1 -> Pair(AMBER, "⚠️ Permitida só enquanto o app está em uso: com a tela apagada o GPS pode parar. Escolha \"Permitir o tempo todo\".")
                    else -> Pair(AMBER, "⚠️ Sem permissão: os km vêm da oferta do iFood (selo ESTIMADO), sem contar desvios.")
                }
                c.addView(tx(txt, 12.5f, cor, true), lp(12))
                if (nv < 2) c.addView(button(if (nv == 0) "📍 Permitir localização" else "📍 Permitir o tempo todo", true) { pedirLocalizacao() }, lp(12))
                if (est.optDouble("km", 0.0) > 0) {
                    val f = if (est.optString("kmFonte") == "gps") "GPS" else "estimado"
                    c.addView(tx("Última rota: ${KmGps.fmtKm(est.optDouble("km", 0.0))} ($f)", 12f, MUTED), lp(10))
                }
                c.addView(tx("Sem permissão de localização o app usa o km que o iFood mostra na oferta (só como último caso).", 11.5f, MUTED), lp(10))
            }, lp(10))
            body.addView(secao("if_cls", "🎯", GOLD, "Classificação",
                "RUIM abaixo de ${br(prefs.ifoodMedio)} · ÓTIMO a partir de ${br(prefs.ifoodOtimo)}"
            ) { c ->
                c.addView(tx("R$ por km da rota inteira (coleta + entregas)", 12f, MUTED))
                c.addView(pair(
                    field("RUIM abaixo de", prefs.ifoodMedio) { prefs.ifoodMedio = it },
                    field("ÓTIMO a partir de", prefs.ifoodOtimo) { prefs.ifoodOtimo = it }
                ), lp(12))
                c.addView(classRef(MonthRef.of(ifRecs.mapNotNull { it.rpk }), "rotas", { prefs.ifoodMedio = it }, { prefs.ifoodOtimo = it }), lp(14))
            }, lp(10))
            body.addView(secao("if_ped", "📦", BLUE, "Valor por pedido",
                if (prefs.ifoodMinPerOrder > 0) "Mínimo ${br(prefs.ifoodMinPerOrder)} por pedido" else "Sem mínimo"
            ) { c ->
                c.addView(tx("Valor bruto ÷ nº de entregas. Abaixo do mínimo = RUIM.", 12f, MUTED))
                c.addView(field("Valor mínimo por pedido (R$, 0 = sem mínimo)", prefs.ifoodMinPerOrder) { prefs.ifoodMinPerOrder = it }, lp(12))
                c.addView(orderRef(MonthRef.of(ifRecs.mapNotNull { it.per })), lp(14))
            }, lp(10))
            body.addView(secao("if_gas", "⛽", GREEN, "Gasolina e lucro",
                "${br(prefs.fuelPrice)}/L · ${num1(prefs.kmPerLiter)} km/L"
            ) { c -> conteudoGasolina(c, false) }, lp(10))
        } else {
            // ───────── InDrive
            body.addView(grupo("InDrive"), lp(18))
            body.addView(secao("in_cls", "🎯", GOLD, "Classificação",
                "RUIM abaixo de ${br(prefs.medio)} · ÓTIMO a partir de ${br(prefs.otimo)}"
            ) { c ->
                c.addView(tx("R$ por km rodado (busca + corrida)", 12f, MUTED))
                c.addView(pair(
                    field("RUIM abaixo de", prefs.medio) { prefs.medio = it },
                    field("ÓTIMO a partir de", prefs.otimo) { prefs.otimo = it }
                ), lp(12))
                c.addView(classRef(MonthRef.of(MonthRef.monthRecs(this, route = false).mapNotNull { it.rpk }), "corridas",
                    { prefs.medio = it }, { prefs.otimo = it }), lp(14))
                c.addView(divider(), LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = dp(14) })
                c.addView(switchRow("Somar a busca", "Conta a distância até o passageiro na nota", prefs.includePickup) {
                    prefs.includePickup = it
                }, lp(12))
            }, lp(8))
            body.addView(secao("in_auto", "⚡", AMBER, "Aceite automático",
                if (prefs.autoAccept) "Ligado · mín. ${br(prefs.autoMinPrice)} · ${br(prefs.autoMinRpk)}/km" else "Desligado"
            ) { c ->
                c.addView(switchRow("Aceitar corridas automaticamente", "Só aceita quando bate os 3 mínimos · também liga pelo botão flutuante", prefs.autoAccept) {
                    prefs.autoAccept = it
                })
                c.addView(field("Valor mínimo bruto (R$, 0 = sem mínimo)", prefs.autoMinPrice) { prefs.autoMinPrice = it }, lp(14))
                c.addView(pair(
                    field("R$/km mínimo", prefs.autoMinRpk) { prefs.autoMinRpk = it },
                    field("Nota mínima", prefs.minRating) { prefs.minRating = it }
                ), lp(12))
                c.addView(tx("⚠️ Aceitar sozinho pode ir contra as regras do app de corridas. Use com cuidado.", 11.5f, AMBER), lp(12))
            }, lp(10))
            body.addView(secao("in_busca", "📍", BLUE, "Cores da busca",
                "Verde até ${num1(prefs.pickupGood)} km · vermelho a partir de ${num1(prefs.pickupBad)} km"
            ) { c ->
                c.addView(tx("Distância até o passageiro", 12f, MUTED))
                c.addView(pair(
                    field("Verde até (km)", prefs.pickupGood) { prefs.pickupGood = it },
                    field("Vermelho a partir de (km)", prefs.pickupBad) { prefs.pickupBad = it }
                ), lp(12))
            }, lp(10))
            body.addView(secao("in_gas", "⛽", GREEN, "Gasolina e lucro",
                "${br(prefs.fuelPrice)}/L · ${num1(prefs.kmPerLiter)} km/L · mín. ${br(prefs.minRpk)}/km"
            ) { c -> conteudoGasolina(c, true) }, lp(10))
        }

        // ───────── geral (vale para os dois)
        body.addView(grupo("Geral"), lp(22))
        body.addView(secao("g_alerta", "🔔", GOLD, "Alerta de rota",
            if (prefs.alertOn) "Ligado · som ${RouteAlert.SOUNDS.getOrNull(prefs.alertSound) ?: ""}" else "Desligado"
        ) { c ->
            c.addView(switchRow("Avisar a cada rota nova", "Toca mesmo no Não perturbe e no silencioso", prefs.alertOn) {
                prefs.alertOn = it
            })
            c.addView(caps("Som"), lp(16))
            c.addView(optionGrid(RouteAlert.SOUNDS, prefs.alertSound) { i ->
                prefs.alertSound = i
                RouteAlert.play(this, i, 0)
            }, lp(10))
            c.addView(caps("Vibração"), lp(18))
            c.addView(optionGrid(RouteAlert.VIBES, prefs.alertVibe) { i ->
                prefs.alertVibe = i
                RouteAlert.play(this, 0, i)
            }, lp(10))
            c.addView(switchRow("Som de bom e de ruim", "ÓTIMO toca um som alegre, RUIM um som grave. MÉDIO usa o som escolhido acima", prefs.alertPorNivel) {
                prefs.alertPorNivel = it
            }, lp(16))
            c.addView(switchRow("Volume no máximo", "Sobe o volume de alarme só durante o aviso e depois volta ao que estava", prefs.alertMaximo) {
                prefs.alertMaximo = it
            }, lp(10))
            c.addView(switchRow("Repetir o toque", "Toca de novo 2 s depois de terminar, até a oferta sumir ou você tocar na tela (máx. 90 s)", prefs.alertRepetir) {
                prefs.alertRepetir = it
            }, lp(10))
            c.addView(button("▶  Testar alerta", false) { RouteAlert.play(this, prefs.alertSound, prefs.alertVibe) }, lp(16))
            c.addView(button("▶  Testar som de bom (ÓTIMO)", false) { RouteAlert.play(this, RouteAlert.SOM_BOM, prefs.alertVibe) }, lp(8))
            c.addView(button("▶  Testar som de ruim (RUIM)", false) { RouteAlert.play(this, RouteAlert.SOM_RUIM, prefs.alertVibe) }, lp(8))
            c.addView(tx("O som usa o volume de ALARME do celular, por isso toca mesmo no silencioso.", 11.5f, MUTED), lp(10))
        }, lp(8))
        body.addView(secao("g_calc", "🧮", BLUE, "Cálculo",
            "Velocidade média ${num1(prefs.avgSpeed)} km/h"
        ) { c ->
            c.addView(tx("Usado quando o app de corridas não mostra o tempo", 12f, MUTED))
            c.addView(field("Velocidade média (km/h)", prefs.avgSpeed) { prefs.avgSpeed = it.coerceIn(5f, 120f) }, lp(12))
        }, lp(10))
        body.addView(secao("g_sobre", "ℹ️", 0xFF7EE2A0.toInt(), "Sobre",
            "Prismo Drive ${BuildConfig.VERSION_NAME} · " + (if (RotasStatus.ligado(this)) "ligado" else "desligado")
        ) { c ->
            fun infoRow(k: String, v: String) = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(tx(k, 13.5f, MUTED), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(tx(v, 13.5f, TXT, true))
            }
            c.addView(infoRow("Versão", "Prismo Drive " + BuildConfig.VERSION_NAME))
            c.addView(infoRow("Serviço", if (RotasStatus.ligado(this)) "Ligado" else "Desligado"), lp(8))
            c.addView(infoRow("Rotas no relatório", ReportStore.load(this).size.toString()), lp(8))
        }, lp(10))
        body.addView(tx("Toque numa opção para abrir. Os valores são salvos na hora.", 11.5f, MUTED).apply { gravity = Gravity.CENTER }, lp(18))
    }


    /** Linha com marcador redondo no degradê da marca. */
    private fun bullet(t: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(View(this@RotasActivity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF4ADE80.toInt(), 0xFF58A6FF.toInt())).apply { shape = GradientDrawable.OVAL }
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { topMargin = dp(7); rightMargin = dp(11) })
        addView(tx(t, 13.5f, 0xFFE6EDF3.toInt()), LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    // ------------------------------------------------------------------ status
    override fun onResume() {
        super.onResume()
        // voltou dos Ajustes com a localização alterada: atualiza a tela de Configurações
        val nl = KmGps.nivel(this)
        if (nivelLocVisto >= 0 && nl != nivelLocVisto && page == Page.CONFIG) showPage()
        nivelLocVisto = nl
        if (RotasStatus.ligado(this)) Notif.show(this)   // garante o aviso fixo na barra
        if (homeShown) updateStatus()
        if (page == Page.REPORT) report.refresh()
        if (drawerOpen) buildDrawer()                // ícone liga/desliga sempre com o estado real
        refreshSample()
    }

    /** Localização para o km da rota: primeiro "durante o uso", depois "o tempo todo" (Android 10+). */
    private fun pedirLocalizacao() {
        val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
        when (KmGps.nivel(this)) {
            0 -> {
                if (pedidoLocNegado && !shouldShowRequestPermissionRationale(fine)) abrirAjustesApp()
                else requestPermissions(arrayOf(fine, android.Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
            }
            1 -> {
                if (Build.VERSION.SDK_INT >= 30) Toast.makeText(this, "Em Localização, escolha \"Permitir o tempo todo\"", Toast.LENGTH_LONG).show()
                if (Build.VERSION.SDK_INT >= 29) requestPermissions(arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_LOC_SEMPRE)
            }
        }
    }

    private fun abrirAjustesApp() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", packageName, null)))
            Toast.makeText(this, "Toque em Permissões → Localização", Toast.LENGTH_LONG).show()
        }
    }

    private var pedidoLocNegado = false
    private var nivelLocVisto = -1

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == REQ_LOC || code == REQ_LOC_SEMPRE) {
            if (code == REQ_LOC && res.none { it == PackageManager.PERMISSION_GRANTED }) pedidoLocNegado = true
            // concedeu "durante o uso": já emenda o pedido de "o tempo todo"
            if (code == REQ_LOC && KmGps.nivel(this) == 1 && Build.VERSION.SDK_INT == 29) pedirLocalizacao()
            nivelLocVisto = KmGps.nivel(this)
            if (page == Page.CONFIG) showPage()
        }
        if (RotasStatus.ligado(this)) Notif.show(this)
        if (homeShown) updateStatus()
    }

    /** Android 13+: pede permissão de notificação (o botão Desligar mora na notificação). */
    private fun askNotifications(openSettingsIfDenied: Boolean) {
        if (Notif.canPost(this) && notificationsOn()) return
        if (!openSettingsIfDenied) {
            if (Build.VERSION.SDK_INT >= 33 && !Notif.canPost(this))
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !Notif.canPost(this) &&
            shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
            return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun notificationsOn() =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).areNotificationsEnabled()

    private fun onPowerClick() {
        if (RotasStatus.ligado(this)) {
            Power.turnOff(this)
            Toast.makeText(this, "Assistente de Rotas desligado. Já pode abrir o app do banco.", Toast.LENGTH_LONG).show()
        } else {
            askNotifications(false)
            if (Power.turnOn(this)) Toast.makeText(this, "Ligando o Assistente de Rotas…", Toast.LENGTH_SHORT).show()
        }
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({ if (homeShown) updateStatus(); if (drawerOpen) buildDrawer() }, 500)
        h.postDelayed({ if (homeShown) updateStatus(); if (drawerOpen) buildDrawer() }, 1500)
    }

    private fun updateStatus() {
        val enabled = Power.isEnabled(this)
        val on = RotasStatus.ligado(this)
        val stuck = enabled && !on
        val c = if (stuck) AMBER else if (on) GREEN else RED
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor((c and 0x00FFFFFF) or 0x26000000)
            setStroke(dp(1), (c and 0x00FFFFFF) or 0x8C000000.toInt())
        }
        statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(c)
        statusTitle.text = if (stuck) "Serviço parado" else if (on) "Assistente de Rotas ligado" else "Assistente de Rotas desligado"
        statusSub.text = if (stuck) "Toque em \"Ligar Assistente de Rotas\" para reiniciar o serviço."
        else if (on) "Lendo as corridas. Antes de abrir o banco, toque em Desligar."
        else "Apps de banco funcionam normalmente. Ligue quando for rodar."
        if (on) {
            activateBtn.text = "Desligar Assistente de Rotas"
            activateBtn.setTextColor(0xFFFF6B6B.toInt())
            activateBtn.background = rounded(0x1FEF5350, 12, 0x66EF5350)
        } else {
            activateBtn.text = "Ligar Assistente de Rotas"
            styleButton(activateBtn, true)
        }
        notifWarn.visibility = if (Notif.canPost(this) && notificationsOn()) View.GONE else View.VISIBLE
    }

    private fun sampleRow(name: String, r: Result) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(BG, 12, r.level?.color ?: LINE)
        }
        box.addView(tx(name, 12.5f, MUTED))
        box.addView(tx(Evaluator.headline(r), 15f, TXT, true), lp(2))
        box.addView(tx(Evaluator.detail(r), 12.5f, MUTED), lp(2))
        val fuelTxt = Evaluator.fuelLine(r)
        if (fuelTxt.isNotEmpty()) box.addView(tx(fuelTxt, 12.5f, Evaluator.netColor(r), true), lp(2))
        if (r.offer.entregas >= 2 || r.belowMinOrder) {
            val each = r.perOrder ?: r.offer.price
            val n = r.offer.entregas
            val base = if (n >= 2) "$n pedidos · ${Evaluator.money(each)} cada" else "1 pedido · ${Evaluator.money(each)}"
            box.addView(
                tx(if (r.belowMinOrder) "$base — abaixo do mínimo (RUIM)" else base, 12.5f,
                    if (r.belowMinOrder) Evaluator.RED else GOLD, true), lp(2)
            )
        }
        Evaluator.suggestLine(r)?.let { if (!r.offer.route) box.addView(tx(it, 12.5f, GOLD, true), lp(2)) }
        sampleBox.addView(box, lp(if (sampleBox.childCount == 0) 0 else 8))
    }

    private fun refreshSample() {
        if (sampleKind == 0 || !::sampleBox.isInitialized) return
        sampleBox.removeAllViews()
        if (sampleKind == 1) {
            sampleRow("Rota com 3 entregas", Evaluator.evaluate(
                Offer(39.42, null, null, 9.83, 119.0, 0.0, null, route = true, coletas = 1, entregas = 3), prefs))
            sampleRow("Rota com 5 entregas", Evaluator.evaluate(
                Offer(30.0, null, null, 8.0, 50.0, 0.0, null, route = true, coletas = 1, entregas = 5), prefs))
            sampleRow("Rota com 1 entrega", Evaluator.evaluate(
                Offer(11.08, null, null, 2.96, 29.0, 0.0, null, route = true, coletas = 1, entregas = 1), prefs))
        } else {
            sampleRow("Aline", Evaluator.evaluate(Offer(39.0, 1.0, 2.1, 37.7, 38.0, 6.0, 4.97), prefs))
            sampleRow("Manuel", Evaluator.evaluate(Offer(25.5, 1.4, 3.1, null, null, null, 4.8), prefs))
        }
    }
}
