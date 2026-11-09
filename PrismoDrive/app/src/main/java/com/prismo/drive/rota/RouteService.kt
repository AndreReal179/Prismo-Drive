package com.prismo.drive.rota

import com.prismo.drive.R
import com.prismo.drive.BuildConfig

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

class RouteService : AccessibilityService() {

    companion object {
        /** true enquanto o serviço está conectado (a tela inicial usa p/ avisar quando travou) */
        @Volatile var running = false
        /** serviço conectado agora (usado pelo botão Desligar) */
        @Volatile var instance: RouteService? = null
    }

    private lateinit var prefs: Prefs
    private lateinit var overlay: Overlay
    private lateinit var fab: Fab
    private lateinit var store: ReportStore
    private var lastPkg = ""
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var lastClick = 0L
    private var lastEvent = 0L
    private var ticking = false

    /**
     * Batimento: enquanto houve atividade recente (ou o selo está na tela) relê a tela a cada ~900 ms.
     * Resolve o selo que "sumia" quando a lista ficava parada e nenhum evento novo chegava.
     */
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val now = SystemClock.uptimeMillis()
                if (now - lastEvent < 60_000 || overlay.isShowing || lastPkg.contains("ifood", true)) scan()
            } catch (_: Throwable) {
            }
            // botão flutuante: aparece no app de corridas (ou em qualquer app até descobrirmos qual é)
            try {
                // app em primeiro plano agora (não depende só de eventos)
                val ap = rootInActiveWindow?.packageName?.toString().orEmpty()
                if (ap.isNotEmpty() && ap != packageName && !ignoredPkg(ap)) lastPkg = ap
                val rides = prefs.ridePkgs
                fab.pkgAtual = lastPkg
                fab.setVisible(rides.isEmpty() || appDeCorrida(lastPkg, rides))
            } catch (_: Throwable) {
            }
            // relatório: grava as ofertas que já saíram da tela
            try { store.flush() } catch (_: Throwable) {}
            // batimento: avisa o processo principal que o serviço está vivo
            RotasStatus.batida(this@RouteService)
            handler.postDelayed(this, 900)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (android.os.Build.VERSION.SDK_INT < 26) { runCatching { disableSelf() }; return }
        running = true
        instance = this
        prefs = Prefs(this)
        overlay = Overlay(this)
        fab = Fab(this)
        store = ReportStore(this)
        overlay.onHidden = { fab.update(null) }
        handler.removeCallbacks(tick)
        ticking = false
        if (!ticking) {
            ticking = true
            handler.postDelayed(tick, 900)
        }
        Notif.show(this)    // notificação fixa com o botão Desligar
        RotasStatus.batida(this)
    }

    /**
     * Desliga o Assistente de Rotas de verdade: tira o botão/painel da tela, remove a notificação e sai da lista de
     * acessibilidade do sistema (disableSelf). Com isso os apps de banco voltam a abrir.
     */
    fun shutdown() {
        if (::store.isInitialized) runCatching { store.flush(true) }   // salva o relatório antes de desligar
        handler.removeCallbacks(tick)
        ticking = false
        if (::overlay.isInitialized) runCatching { overlay.remove() }
        if (::fab.isInitialized) runCatching { fab.remove() }
        running = false
        instance = null
        RotasStatus.limpar(this)
        Notif.cancel(this)
        runCatching { disableSelf() }
    }

    /** Salva o relatório pendente (antes de o app ser atualizado/reiniciado). */
    fun saveState() {
        if (::store.isInitialized) runCatching { store.flush(true) }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.packageName == packageName || !::overlay.isInitialized) return
        val pkg = e.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && !ignoredPkg(pkg)) lastPkg = pkg
        lastEvent = SystemClock.uptimeMillis()
        if (scheduled) return
        scheduled = true
        handler.postDelayed({
            scheduled = false
            try { scan() } catch (_: Throwable) {}
        }, 200)
    }

    /**
     * Todas as janelas onde pode haver oferta: a ativa primeiro e depois as outras janelas de app
     * (o popup de rota que chega por notificação às vezes abre numa janela que não é a "ativa").
     */
    private fun candidateRoots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { if (it.packageName != packageName) out.add(it) }
        runCatching {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val rr = w.root ?: continue
                if (rr.packageName == packageName) continue
                if (out.any { it.windowId == rr.windowId }) continue
                out.add(rr)
            }
        }
        return out
    }

    // nº de pedidos já lido para cada oferta (às vezes a leitura seguinte enxerga menos itens)
    private val stopMemo = HashMap<String, Triple<Int, Int, Long>>()

    /** Mantém o maior nº de coletas/entregas já visto para a mesma oferta (mesmo valor + km). */
    private fun keepStops(o: Offer) {
        if (!o.route) return
        val key = "${Math.round(o.price * 100)}|${o.tripKm?.let { Math.round(it * 10) }}"
        val now = SystemClock.uptimeMillis()
        val old = stopMemo[key]
        if (old != null && now - old.third < 10 * 60_000L) {
            if (old.first > o.coletas || old.second > o.entregas) {
                o.coletas = maxOf(o.coletas, old.first)
                o.entregas = maxOf(o.entregas, old.second)
                o.stops = Parser.stopsText(o.coletas, o.entregas)
            }
        }
        stopMemo[key] = Triple(o.coletas, o.entregas, now)
        if (stopMemo.size > 300) stopMemo.entries.removeAll { now - it.value.third > 10 * 60_000L }
    }

    /** Janela ativa; se o sistema não entregar, procura entre as janelas de aplicativo. */
    private fun findRoot(): AccessibilityNodeInfo? {
        val r = rootInActiveWindow
        if (r != null) return if (r.packageName == packageName) null else r
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val rr = w.root ?: continue
            if (rr.packageName != packageName) return rr
        }
        return null
    }

    /**
     * O app em primeiro plano é um app de corridas/entregas? Além dos pacotes já aprendidos (onde uma
     * oferta foi lida), reconhece o iFood e o InDrive pelo nome — assim o botão aparece no iFood
     * mesmo sem nenhuma rota na tela — e outros pacotes da mesma empresa (ex.: br.com.ifood.*).
     */
    private fun appDeCorrida(p: String, rides: Set<String>): Boolean {
        if (p.isEmpty()) return false
        if (p in rides) return true
        val l = p.lowercase()
        if (l.contains("ifood") || l.contains("indrive") || l.contains("indriver")) return true
        val prefixo = p.split('.').take(3).joinToString(".")
        return rides.any { it.split('.').take(3).joinToString(".") == prefixo }
    }

    private fun ignoredPkg(p: String) =
        p.contains("systemui") || p.contains("inputmethod") || p.contains("keyboard")

    private fun scan() {
        if (!running) return
        val roots = runCatching { candidateRoots() }.getOrDefault(emptyList())
        if (roots.isEmpty()) {         // app minimizado / sem janela: some com os cards
            overlay.hideSoon()
            return
        }
        var root: AccessibilityNodeInfo? = null
        var offers: List<Offer> = emptyList()
        for (r in roots) {
            val o = runCatching { Parser.parse(r) }.getOrDefault(emptyList())
            if (o.isNotEmpty()) { root = r; offers = o; break }
        }
        if (root == null || offers.isEmpty()) {
            overlay.hideSoon()
            runCatching { lerSaldoIfood(roots) }
            return
        }
        root.packageName?.toString()?.let { p ->
            prefs.addRidePkg(p)
            // aprende qual app é qual: rotas com coletas/entregas = iFood; corridas = InDrive
            if (offers.any { it.route }) prefs.ifoodPkg = p else prefs.indrivePkg = p
        }
        offers.forEach { runCatching { keepStops(it) } }
        val results = offers.map { Evaluator.evaluate(it, prefs) }
        runCatching { store.observe(results) }   // relatório
        runCatching { RouteAlert.onResults(this, results) }   // som + vibração a cada rota nova
        fab.update(Evaluator.best(results))
        runCatching { overlay.show(results) }
        runCatching { maybeAutoAccept(results) }
    }

    /** Botão "Testar" da tela do assistente: mostra a comemoração sem lançar nada. */
    fun testarComemoracao() {
        handler.post {
            runCatching { fab.setVisible(true); fab.celebrar(68.0) }
            runCatching { RouteAlert.moedas(this) }
        }
    }

    /** Tela "Oba, mais uma rota concluída!" do iFood: lança o valor ganho (+ bônus) no Prismo Drive. */
    private fun lerSaldoIfood(roots: List<AccessibilityNodeInfo>) {
        if (!prefs.ifoodSaldoAuto) return
        for (r in roots) {
            val p = r.packageName?.toString().orEmpty()
            val ehIfood = p.contains("ifood", true) || (p.isNotEmpty() && p == prefs.ifoodPkg)
            if (!ehIfood) continue
            val lido = IfoodSaldo.lerConclusao(r)
            if (lido == null) { IfoodSaldo.telaSumiu(); continue }
            val lancado = IfoodSaldo.observarConclusao(this, lido.first, lido.second)
            if (lancado != null && lancado > 0) {
                runCatching { fab.celebrar(lancado) }
                runCatching { RouteAlert.moedas(this) }
            }
            return
        }
    }

    private fun maybeAutoAccept(results: List<Result>) {
        if (!prefs.autoAccept) return
        val now = SystemClock.uptimeMillis()
        if (now - lastClick < 3000) return

        val candidate = results
            .filter { !it.offer.route }   // "Rotas disponíveis": nunca toca em "Gerar Rota" sozinho
            .filter { it.offer.price >= prefs.autoMinPrice }          // valor bruto mínimo
            .filter { (it.effRpk ?: 0.0) >= prefs.autoMinRpk }
            .filter { r -> r.offer.rating?.let { it >= prefs.minRating } ?: true }
            .maxByOrNull { it.effRpk ?: 0.0 } ?: return

        val o = candidate.offer
        val ok = when {
            o.acceptNode != null -> click(o.acceptNode)          // tela de detalhe
            o.cardNode != null -> click(o.cardNode)              // lista: abre o card
            else -> tap(o.tapX, o.tapY)
        }
        if (ok) lastClick = now
    }

    private fun click(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var guard = 0
        while (cur != null && guard++ < 10) {
            if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
        }
        return false
    }

    private fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(g, null, null)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        running = false
        RotasStatus.limpar(this)
        if (instance === this) instance = null
        Notif.cancel(this)
        handler.removeCallbacks(tick)
        ticking = false
        if (::store.isInitialized) runCatching { store.flush(true) }
        if (::overlay.isInitialized) overlay.remove()
        if (::fab.isInitialized) fab.remove()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.remove()
    }

    override fun onDestroy() {
        if (::store.isInitialized) runCatching { store.flush(true) }
        handler.removeCallbacks(tick)
        ticking = false
        if (::overlay.isInitialized) overlay.remove()
        if (::fab.isInitialized) fab.remove()
        running = false
        if (instance === this) instance = null
        Notif.cancel(this)
        super.onDestroy()
    }
}
