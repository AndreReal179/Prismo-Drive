package com.prismo.drive

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File

/**
 * Relógio do Pro: NUNCA usa a data/hora do celular.
 *
 * A hora vem do servidor (banco de dados) a cada consulta da conta. Entre uma consulta e outra o tempo
 * anda pelo relógio interno do Android (elapsedRealtime), que não muda quando a pessoa altera a data/hora.
 * Se o celular reiniciar, continua da última hora conhecida + o tempo desde que ligou (nunca volta para trás).
 * Assim, mudar a data do celular não estende o Pro. Sem falar com o servidor por mais de 7 dias, o Pro
 * pede internet para confirmar.
 */
object Relogio {
    private const val ARQ = "relogio.json"
    const val SEM_SERVIDOR_MAX = 7L * 24 * 60 * 60 * 1000

    private fun arq(ctx: Context) = File((ctx.applicationContext ?: ctx).filesDir, ARQ)

    private fun ler(ctx: Context): JSONObject? =
        runCatching { arq(ctx).takeIf { it.exists() }?.let { JSONObject(it.readText()) } }.getOrNull()

    private fun gravar(ctx: Context, o: JSONObject) {
        runCatching {
            val f = arq(ctx); val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString()); if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
        }
    }

    /** Hora oficial recebida do servidor (milissegundos). */
    fun sincronizar(ctx: Context, servidorMs: Long) {
        if (servidorMs <= 0) return
        val antes = ler(ctx)
        val max = maxOf(servidorMs, antes?.optLong("max", 0L) ?: 0L)
        gravar(ctx, JSONObject()
            .put("t", servidorMs)
            .put("e", SystemClock.elapsedRealtime())
            .put("max", max)
            .put("srv", servidorMs))
    }

    /** Hora atual estimada (ms) sem depender do relógio do celular. -1 = nunca falou com o servidor. */
    fun agora(ctx: Context): Long {
        val o = ler(ctx) ?: return -1L
        val t = o.optLong("t", 0L)
        if (t <= 0L) return -1L
        val e = o.optLong("e", 0L)
        val max = o.optLong("max", t)
        val el = SystemClock.elapsedRealtime()
        val agora: Long
        if (el >= e) {
            agora = t + (el - e)
            if (agora - max > 60_000L) gravar(ctx, o.put("max", agora))   // guarda o maior tempo visto
        } else {
            // o celular reiniciou: a hora real é, no mínimo, a maior já vista + o tempo desde que ligou
            agora = max + el
            gravar(ctx, o.put("t", agora).put("e", el).put("max", agora))
        }
        return maxOf(agora, max)
    }

    /** Última hora recebida do servidor. */
    fun ultimoServidor(ctx: Context): Long = ler(ctx)?.optLong("srv", 0L) ?: 0L

    /** Faz muito tempo sem confirmar com o servidor (precisa de internet para manter o Pro). */
    fun precisaConfirmar(ctx: Context): Boolean {
        val a = agora(ctx); val s = ultimoServidor(ctx)
        return a < 0 || s <= 0 || a - s > SEM_SERVIDOR_MAX
    }

    fun limpar(ctx: Context) { runCatching { arq(ctx).delete() } }
}
