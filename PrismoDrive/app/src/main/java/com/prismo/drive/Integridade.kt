package com.prismo.drive

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.security.MessageDigest

/**
 * Proteção contra APK modificado.
 * Todo APK oficial é assinado com a chave da Prismo Lab. Quem descompila e altera o app precisa
 * assinar de novo com outra chave — então a assinatura deixa de bater e o app se recusa a abrir
 * (e o Pro, o assistente de rotas e os widgets ficam bloqueados).
 */
object Integridade {
    /** SHA-256 do certificado oficial (o mesmo que o GitHub Actions usa para assinar). */
    private val OFICIAIS = setOf(
        "9caa5c09b7c4060f6b14d122236674356eb44f90f6aea4bafe973c9b7ca0afe1",
    )
    const val PAGINA_OFICIAL = "https://github.com/AndreReal179/Prismo-Drive/releases/latest"

    @Volatile private var resultado = -1  // -1 ainda não conferido · 1 oficial · 0 modificado

    fun oficial(ctx: Context): Boolean {
        val r = resultado
        if (r >= 0) return r == 1
        val ok = try {
            val achadas = assinaturas(ctx)
            achadas.isNotEmpty() && achadas.all { it in OFICIAIS }
        } catch (_: Throwable) {
            false
        }
        resultado = if (ok) 1 else 0
        return ok
    }

    @Suppress("DEPRECATION")
    private fun assinaturas(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val nome = ctx.packageName
        val certs = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(nome, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return emptyList()
            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            pm.getPackageInfo(nome, PackageManager.GET_SIGNATURES).signatures
        } ?: return emptyList()
        val md = MessageDigest.getInstance("SHA-256")
        // com rotação de chave o histórico tem a chave antiga e a atual: basta a atual (última) ser a oficial
        val lista = certs.map { c -> md.digest(c.toByteArray()).joinToString("") { "%02x".format(it) } }
        return if (Build.VERSION.SDK_INT >= 28 && lista.size > 1) listOf(lista.last()) else lista
    }

    /** Tela mostrada no lugar do app quando a assinatura não é a oficial. */
    fun mostrarBloqueio(a: MainActivity, web: WebView) {
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun baixar() {
                runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PAGINA_OFICIAL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }, "PrismoOficial")
        val html = """<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#07110b;color:#e6edf3;font-family:system-ui,sans-serif;padding:24px;box-sizing:border-box}
.c{max-width:360px;text-align:center}.i{width:72px;height:72px;border-radius:22px;margin:0 auto 18px;display:flex;align-items:center;justify-content:center;background:rgba(248,81,73,.15);border:1px solid rgba(248,81,73,.45);font-size:36px}
h1{font-size:20px;margin:0 0 10px}p{font-size:14px;line-height:1.5;color:#9aa4ae;margin:0 0 22px}
button{width:100%;padding:14px;border:0;border-radius:14px;font-size:15px;font-weight:800;color:#04210d;background:linear-gradient(135deg,#2ea44f,#4ade80)}</style></head>
<body><div class="c"><div class="i">⚠️</div><h1>Este app foi modificado</h1>
<p>Esta cópia do Prismo Drive não é a oficial e pode colocar seus dados em risco. Desinstale e baixe a versão oficial, gratuita e segura.</p>
<button onclick="PrismoOficial.baixar()">Baixar a versão oficial</button></div></body></html>"""
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
}
