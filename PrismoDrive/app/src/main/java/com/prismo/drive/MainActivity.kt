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
    private var dlgProgresso: AlertDialog? = null
    private var barraProgresso: ProgressBar? = null
    private var textoProgresso: TextView? = null

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

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        }
        if (web.url == null) {
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
            dlgProgresso?.dismiss()
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
                val pct = ev.optInt("pct", -1)
                barraProgresso?.isIndeterminate = pct < 0
                if (pct >= 0) barraProgresso?.progress = pct
                val mb = ev.optLong("baixado") / 1048576.0
                val totalMb = ev.optLong("total") / 1048576.0
                textoProgresso?.text = if (totalMb > 0)
                    String.format("Baixando… %.1f de %.1f MB", mb, totalMb)
                else String.format("Baixando… %.1f MB", mb)
            }
            "baixado" -> {
                fecharProgresso()
                instalarAtualizacao()
            }
            "erro" -> {
                if (dlgProgresso != null) {
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

    private fun perguntarAtualizar(ev: JSONObject) {
        val notas = ev.optString("notas").trim()
        val msg = buildString {
            append("A versão ").append(ev.optString("versao")).append(" do Prismo Drive está disponível.")
            append("\nVocê está na ").append(BuildConfig.VERSION_NAME).append(".")
            if (notas.isNotEmpty()) append("\n\nNovidades:\n").append(notas.take(700))
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Nova atualização disponível")
            .setMessage(msg)
            .setPositiveButton("Atualizar agora") { _, _ -> baixarAtualizacao() }
            .setNegativeButton("Depois") { _, _ -> Atualizador.adiar(this, ev.optLong("codigo")) }
            .setCancelable(true)
            .show()
    }

    private fun mostrarProgresso() {
        if (dlgProgresso?.isShowing == true) return
        val d = resources.displayMetrics.density
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
        }
        val txt = TextView(this).apply {
            text = "Preparando download…"
            gravity = Gravity.START
        }
        val barra = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        caixa.addView(txt)
        caixa.addView(barra, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (20 * d).toInt()).apply {
            topMargin = (12 * d).toInt()
        })
        textoProgresso = txt
        barraProgresso = barra
        dlgProgresso = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Atualizando o Prismo Drive")
            .setView(caixa)
            .setCancelable(false)
            .setNegativeButton("Segundo plano") { _, _ -> fecharProgresso() }
            .show()
    }

    private fun fecharProgresso() {
        try {
            dlgProgresso?.dismiss()
        } catch (_: Throwable) {
        }
        dlgProgresso = null
        barraProgresso = null
        textoProgresso = null
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
 var c=el('carteira-app');
 if(c&&c.style.display==='block'&&typeof fecharCarteira==='function'){fecharCarteira();return '1';}
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
