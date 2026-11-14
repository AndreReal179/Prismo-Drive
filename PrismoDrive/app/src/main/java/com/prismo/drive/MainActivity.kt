package com.prismo.drive

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File

/**
 * Tela principal do Prismo Drive.
 * Toda a interface (painel, rotas, carteira, manutenção, Rota Inteligente…) fica
 * em assets/index.html, 100% offline dentro do APK. Esta Activity hospeda a página
 * e expõe a ponte nativa "PrismoNative" (notificações, alarmes, widgets, compartilhar).
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var progresso: JanelaAtualizacao.Progresso? = null

    /** Recebimento novo do iFood lido pelo assistente (outro processo): lança na hora. */
    private val receptorIfood = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { sincronizarIfood() }
    }

    private fun sincronizarIfood() {
        try {
            web.evaluateJavascript("try{window.PrismoIfood&&PrismoIfood.sincronizar();}catch(e){}", null)
        } catch (_: Throwable) {
        }
    }

    /** Ação pedida pela notificação/botão flutuante (ex.: finalizar o turno), entregue à página uma vez. */
    @Volatile private var acaoPendente: String? = null

    fun consumirAcao(): String { val a = acaoPendente ?: ""; acaoPendente = null; return a }

    /** Tocou na notificação "Nova atualização disponível": já começa a baixar. */
    private fun lerAtualizar(i: Intent?) {
        if (i?.getBooleanExtra(AvisoAtualizacao.EXTRA_ATUALIZAR, false) != true) return
        i.removeExtra(AvisoAtualizacao.EXTRA_ATUALIZAR)
        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (Atualizador.ultimo?.optBoolean("nova") == true) baixarAtualizacao()
            else Atualizador.verificar(this) { ev ->
                runOnUiThread {
                    tratarAtualizacao(ev, false)
                    if (ev.optString("tipo") == "resultado" && ev.optBoolean("nova")) baixarAtualizacao()
                }
            }
        }, 1500)
    }

    private fun lerAcao(i: Intent?) {
        val a = i?.getStringExtra(com.prismo.drive.rota.Turno.EXTRA_ACAO) ?: return
        i.removeExtra(com.prismo.drive.rota.Turno.EXTRA_ACAO)
        acaoPendente = a
        if (::web.isInitialized) handler.postDelayed({
            runCatching { web.evaluateJavascript("try{window.PrismoTurno&&PrismoTurno.verAcao();}catch(e){}", null) }
        }, 600)
    }

    /** Link do e-mail da conta (confirmar cadastro / nova senha): com.prismo.drive://auth#… */
    @Volatile private var linkConta: String? = null

    fun consumirLinkConta(): String { val l = linkConta ?: ""; linkConta = null; return l }

    private fun lerLinkConta(i: Intent?) {
        val d = i?.data ?: return
        if (!d.scheme.equals("com.prismo.drive", true)) return
        if (d.host.equals("pagamento", true)) {
            // volta do Mercado Pago (pagina de retorno): a pagina confere a conta e mostra o Pro
            val st = (d.getQueryParameter("s") ?: "").filter { it.isLetter() }.take(12)
            i.data = null
            handler.postDelayed({ rodarJs("try{window.PrismoConta&&PrismoConta.voltouPagamento('$st');}catch(e){}") }, 900)
            return
        }
        if (!d.host.equals("auth", true)) return
        linkConta = d.toString()
        i.data = null
        if (::web.isInitialized) handler.postDelayed({
            runCatching { web.evaluateJavascript("try{window.PrismoConta&&PrismoConta.verLink();}catch(e){}", null) }
        }, 600)
    }

    /** Roda um trecho de JavaScript na página (de qualquer thread). */
    fun rodarJs(js: String) {
        handler.post {
            if (!isDestroyed && ::web.isInitialized) runCatching { web.evaluateJavascript(js, null) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lerAcao(intent)
        lerAtualizar(intent)
        lerLinkConta(intent)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lerAcao(intent)
        lerAtualizar(intent)
        runCatching { AvisoAtualizacao.agendar(this) }
        lerLinkConta(intent)

        web = WebView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.bg_dark))
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                allowFileAccess = true
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = false
            }
            webViewClient = Cliente()
            webChromeClient = Chrome()
        }

        try {
            web.addJavascriptInterface(PrismoBridge(this), "PrismoNative")
            Notificacoes.criarCanal(this)
            PrismoBridge.pedirPermissaoNotificacao(this)
        } catch (_: Throwable) {
        }

        setContentView(web)

        if (savedInstanceState != null && Integridade.oficial(this)) {
            web.restoreState(savedInstanceState)
        }
        if (!Integridade.oficial(this)) {
            // APK modificado / reassinado: não abre o app
            Integridade.mostrarBloqueio(this, web)
        } else if (web.url == null) {
            web.loadUrl(PAGINA)
        }

        try {
            ContextCompat.registerReceiver(
                this, receptorIfood,
                android.content.IntentFilter(com.prismo.drive.rota.IfoodSaldo.ACTION_NOVO),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (_: Throwable) {
        }

        // Procura atualização sozinho alguns segundos depois de abrir (depois da tela de carregamento)
        if (savedInstanceState == null) {
            handler.postDelayed({ verificarAtualizacao(automatico = true) }, 6000)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        handler.postDelayed({ sincronizarIfood() }, 1500)
        // Voltou da tela "instalar apps desconhecidos": se liberou, instala o APK já baixado
        val pendente = Atualizador.apkPendente
        if (pendente != null && pendente.exists() &&
            (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())
        ) {
            handler.postDelayed({ instalarAtualizacao() }, 400)
        }
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(receptorIfood)
        } catch (_: Throwable) {
        }
        try {
            progresso?.dlg?.dismiss()
        } catch (_: Throwable) {
        }
        try {
            web.removeJavascriptInterface("PrismoNative")
            web.destroy()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    /** Botão voltar: fecha a janela aberta (carteira, guia, modais, menu); senão minimiza o app. */
    @Deprecated("Mantido para compatibilidade com Activity simples")
    override fun onBackPressed() {
        web.evaluateJavascript(JS_VOLTAR) { r ->
            if (r == null || !r.contains("1")) {
                moveTaskToBack(true)
            }
        }
    }

    @Deprecated("API de Activity simples")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ARQUIVO) {
            val cb = fileCallback
            fileCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PrismoBridge.REQ_LOC_TURNO) {
            val ok = grantResults.isNotEmpty() && grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED } &&
                com.prismo.drive.rota.KmGps.temPermissao(this)
            val r = if (ok && com.prismo.drive.rota.Turno.iniciar(this)) "ok" else "negada"
            web.evaluateJavascript(
                "try{window.dispatchEvent(new CustomEvent('prismo-turno',{detail:'$r'}));}catch(e){}", null
            )
        }
        if (requestCode == PrismoBridge.REQ_NOTIF) {
            // Avisa a página para atualizar o status das notificações, se ela tiver o gancho.
            web.evaluateJavascript(
                "try{window.dispatchEvent(new Event('prismo-permissao'));}catch(e){}", null
            )
        }
    }

    // ───────────────────── Atualização automática (GitHub Releases) ─────────────────────

    fun verificarAtualizacao(automatico: Boolean) {
        Atualizador.verificar(this) { ev -> runOnUiThread { tratarAtualizacao(ev, automatico) } }
    }

    fun baixarAtualizacao() {
        if (Atualizador.baixando) {
            mostrarProgresso()
            return
        }
        mostrarProgresso()
        Atualizador.baixar(this) { ev -> runOnUiThread { tratarAtualizacao(ev, false) } }
    }

    fun instalarAtualizacao() {
        val f = Atualizador.apkPendente ?: File(cacheDir, "atualizacao/PrismoDrive.apk")
        if (!f.exists()) {
            baixarAtualizacao()
            return
        }
        try {
            if (Atualizador.instalar(this, f)) {
                enviarParaPagina(JSONObject().put("tipo", "instalando"))
            } else {
                enviarParaPagina(JSONObject().put("tipo", "permissao"))
                Toast.makeText(this, "Ative \"Permitir desta fonte\" e volte ao app", Toast.LENGTH_LONG).show()
            }
        } catch (e: Throwable) {
            enviarParaPagina(JSONObject().put("tipo", "erro").put("msg", "Não foi possível abrir o instalador"))
        }
    }

    private fun tratarAtualizacao(ev: JSONObject, automatico: Boolean) {
        if (isFinishing || isDestroyed) return
        enviarParaPagina(ev)
        when (ev.optString("tipo")) {
            "resultado" -> {
                if (automatico && ev.optBoolean("nova") && Atualizador.podeAvisar(this, ev.optLong("codigo"))) {
                    perguntarAtualizar(ev)
                }
            }
            "progresso" -> {
                progresso?.atualizar(ev.optInt("pct", -1), ev.optLong("baixado"), ev.optLong("total"),
                    if (ev.has("msg")) ev.optString("msg") else null)
            }
            "baixado" -> {
                fecharProgresso()
                instalarAtualizacao()
            }
            "erro" -> {
                if (progresso != null) {
                    fecharProgresso()
                    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Não foi possível atualizar")
                        .setMessage(ev.optString("msg"))
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    // ───────────── janelas de atualização no visual do Prismo Drive (JanelaAtualizacao.kt) ─────────────

    private fun perguntarAtualizar(ev: JSONObject) {
        JanelaAtualizacao.perguntar(this, ev,
            baixar = { baixarAtualizacao() },
            depois = { Atualizador.adiar(this, ev.optLong("codigo")) })
    }

    private fun mostrarProgresso() {
        if (progresso?.dlg?.isShowing == true) return
        progresso = JanelaAtualizacao.progresso(this) { fecharProgresso() }
    }

    private fun fecharProgresso() {
        try {
            progresso?.dlg?.dismiss()
        } catch (_: Throwable) {
        }
        progresso = null
    }

    /** Entrega o evento para a aba Atualizações da página. */
    private fun enviarParaPagina(ev: JSONObject) {
        try {
            web.evaluateJavascript(
                "try{window.prismoAtualizacao&&window.prismoAtualizacao(" + ev.toString() + ");}catch(e){}", null
            )
        } catch (_: Throwable) {
        }
    }

    // ── Links: tudo que não é a página local abre fora (WhatsApp, navegador, telefone…) ──
    private inner class Cliente : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            abrirExterno(request.url)

        @Deprecated("Usado só em WebViews antigos")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            abrirExterno(Uri.parse(url))
        /** Mapa do trajeto (v2.1): as imagens do OpenStreetMap são pedidas pelo app com a identificação
         *  exigida pela política de uso deles (User-Agent do app), e ficam em cache por 30 dias. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? =
            MapaTiles.interceptar(this@MainActivity, request.url)
    }

    private fun abrirExterno(uri: Uri): Boolean {
        val esquema = (uri.scheme ?: "").lowercase()
        if (esquema == "file" || esquema == "about" || esquema == "data" || esquema == "blob" ||
            esquema == "javascript" || esquema.isEmpty()
        ) return false
        try {
            val intent = if (esquema == "intent") {
                Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
            } else {
                Intent(Intent.ACTION_VIEW, uri)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
        } catch (_: Throwable) {
        }
        return true
    }

    // ── Seleção de foto da galeria (foto de perfil) ──
    private inner class Chrome : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                startActivityForResult(fileChooserParams.createIntent(), REQ_ARQUIVO)
                true
            } catch (_: Throwable) {
                fileCallback = null
                filePathCallback.onReceiveValue(null)
                true
            }
        }
    }

    companion object {
        private const val PAGINA = "file:///android_asset/index.html"
        private const val REQ_ARQUIVO = 500

        /** Fecha a camada visível de cima para baixo. Retorna '1' se fechou algo. */
        private const val JS_VOLTAR = """
(function(){try{
 function el(i){return document.getElementById(i);}
 function vis(e){if(!e)return false;if(e.classList&&e.classList.contains('hidden'))return false;return getComputedStyle(e).display!=='none';}
 var pcv=window.PrismoConta?PrismoConta.voltar():false;
 if(pcv==='min')return '0';
 if(pcv)return '1';
 var c=el('carteira-app');
 if(c&&c.style.display==='block'&&typeof fecharCarteira==='function'){fecharCarteira();return '1';}
 if(vis(el('modal-turno-fim'))&&typeof PrismoTurno!=='undefined'){PrismoTurno.fecharFim();return '1';}
 if(vis(el('modal-contas-semana'))&&typeof fecharContasSemana==='function'){fecharContasSemana();return '1';}
 if(vis(el('guia-app'))&&typeof fecharGuia==='function'){fecharGuia();return '1';}
 if(vis(el('manut-wizard'))&&typeof mwVoltar==='function'){mwVoltar();return '1';}
 if(vis(el('modal-peca-form'))&&typeof fecharModalPecaForm==='function'){fecharModalPecaForm();return '1';}
 if(vis(el('modal-novidades'))&&typeof fecharNovidades==='function'){fecharNovidades();return '1';}
 if(vis(el('modal-tanque'))&&typeof fecharModalTanque==='function'){fecharModalTanque();return '1';}
 if(vis(el('modal-manutencao'))&&typeof fecharModalManutencao==='function'){fecharModalManutencao();return '1';}
 if(vis(el('cal-detalhe-dia'))&&typeof fecharDetalheDia==='function'){fecharDetalheDia();return '1';}
 var abertos=false;
 try{(typeof MODAIS!=='undefined'?MODAIS:[]).forEach(function(i){if(vis(el(i)))abertos=true;});}catch(e){}
 if(vis(el('modal-calendario')))abertos=true;
 if(abertos&&typeof fecharModais==='function'){fecharModais();return '1';}
 if(vis(el('drawer-menu'))&&typeof fecharDrawer==='function'){fecharDrawer();return '1';}
 return '0';
}catch(e){return '0';}})()
"""
    }
}
