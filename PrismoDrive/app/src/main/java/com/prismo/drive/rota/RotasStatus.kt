package com.prismo.drive.rota

import android.content.Context
import java.io.File

/**
 * Situação do serviço vista de QUALQUER processo do app.
 * O RouteService (processo ":rotas") grava um "batimento" a cada ~2 s; a tela do Prismo Drive e a do
 * assistente (processo principal) leem esse arquivo para saber se ele está vivo.
 */
object RotasStatus {
    private const val ARQ = "rotas_vivo"
    private const val VIVO_MS = 6000L
    @Volatile private var ultimaGravacao = 0L

    private fun f(ctx: Context) = File((ctx.applicationContext ?: ctx).filesDir, ARQ)

    /** Chamado pelo serviço a cada batida. */
    fun batida(ctx: Context) {
        val agora = System.currentTimeMillis()
        if (agora - ultimaGravacao < 2000) return
        ultimaGravacao = agora
        runCatching { f(ctx).writeText(agora.toString()) }
    }

    fun limpar(ctx: Context) {
        ultimaGravacao = 0L
        runCatching { f(ctx).delete() }
    }

    /** true = serviço rodando agora (no mesmo processo ou batendo no arquivo há poucos segundos). */
    fun ligado(ctx: Context): Boolean {
        if (RouteService.running) return true
        val t = runCatching { f(ctx).readText().trim().toLong() }.getOrDefault(0L)
        return t > 0 && System.currentTimeMillis() - t < VIVO_MS
    }
}
