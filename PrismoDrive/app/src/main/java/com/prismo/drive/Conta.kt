package com.prismo.drive

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Estado da conta (Supabase) visível para todos os processos do app.
 *
 * A página (index.html) faz o login e grava aqui só o essencial: se há conta conectada,
 * o ID fixo da conta e o nome. O assistente de rotas (processo :rotas) e os widgets leem
 * este arquivo para liberar ou bloquear os recursos que exigem conta.
 * Tokens de acesso NÃO ficam aqui.
 */
object Conta {
    const val SUPABASE_URL = "https://xzdqvdrwvozoedmjwwhm.supabase.co"

    /** ID do cliente OAuth "Aplicativo da Web" (é público; o login nativo do Google usa ele como serverClientId). */
    const val GOOGLE_WEB_CLIENT_ID = "467655846502-dn4r44d9pjnvedvc4ckig18n3ifk8fua.apps.googleusercontent.com"

    private const val ARQ = "conta.json"

    @Volatile private var cacheMod = -1L
    @Volatile private var cacheLogado = false

    private fun arq(ctx: Context) = File((ctx.applicationContext ?: ctx).filesDir, ARQ)

    /** Grava o estado vindo da página: {"logado":true,"contaId":"12345678","nome":"…"}. */
    fun salvar(ctx: Context, json: String?) {
        runCatching {
            val o = if (json.isNullOrBlank()) JSONObject() else JSONObject(json)
            val limpo = JSONObject()
                .put("logado", o.optBoolean("logado", false))
                .put("contaId", o.optString("contaId", ""))
                .put("nome", o.optString("nome", ""))
            arq(ctx).writeText(limpo.toString())
            cacheMod = -1L
        }
        runCatching { WidgetUtil.atualizarTodos(ctx) }
    }

    /** true quando há uma conta conectada. Barato: só relê o arquivo quando ele muda. */
    fun logado(ctx: Context): Boolean {
        return try {
            val f = arq(ctx)
            val mod = if (f.exists()) f.lastModified() * 31 + f.length() else 0L
            if (mod != cacheMod) {
                cacheLogado = mod > 0L && JSONObject(f.readText()).optBoolean("logado", false)
                cacheMod = mod
            }
            cacheLogado
        } catch (_: Throwable) {
            false
        }
    }
}
