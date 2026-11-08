package com.prismo.drive

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat

/**
 * Tela principal do Prismo Drive.
 * Toda a interface (painel, rotas, carteira, manutenção, Rota Inteligente…) fica
 * em assets/index.html, 100% offline dentro do APK. Esta Activity hospeda a página
 * e expõe a ponte nativa "PrismoNative" (notificações, alarmes, widgets, compartilhar).
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
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
