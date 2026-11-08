package com.prismo.drive.rota

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

/**
 * Ganhos por dia, para o "rendimento do dia" e o calendário do Relatório.
 *
 * - iFood: cada "rota concluída" lida pelo assistente (valor + bônus) — gravado aqui pelo serviço.
 * - Manuais: o que foi lançado no Prismo Drive (rotas, extras e diárias que NÃO vieram do iFood),
 *   enviado pela tela do app (PrismoNative.sincronizarGanhos) como { "2026-11-08": 120.0, ... }.
 *   No filtro InDrive aparecem esses lançamentos manuais (InDrive e outros apps).
 */
object Ganhos {
    private const val ARQ_IFOOD = "ganhos_ifood.jsonl"
    private const val ARQ_MANUAIS = "ganhos_manuais.json"
    private const val KEEP_DIAS = 100

    private fun dir(ctx: Context) = (ctx.applicationContext ?: ctx).filesDir

    fun iso(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Serviço: rota concluída no iFood (chamado dentro da trava do IfoodSaldo). */
    fun registrarIfood(ctx: Context, t: Long, valor: Double) {
        if (valor <= 0) return
        runCatching {
            val f = File(dir(ctx), ARQ_IFOOD)
            f.appendText(JSONObject().put("t", t).put("v", valor).toString() + "\n")
            // limpa o que passou de ~100 dias (de vez em quando)
            if (f.length() > 200_000) {
                val lim = System.currentTimeMillis() - KEEP_DIAS * 86_400_000L
                val linhas = f.readLines().filter { l -> runCatching { JSONObject(l).optLong("t") >= lim }.getOrDefault(false) }
                f.writeText(linhas.joinToString("\n", postfix = if (linhas.isEmpty()) "" else "\n"))
            }
        }
    }

    /** Tela do app: total manual por dia (substitui o que havia). */
    fun salvarManuais(ctx: Context, json: String) {
        runCatching {
            JSONObject(json)   // valida
            File(dir(ctx), ARQ_MANUAIS).writeText(json)
        }
    }

    fun ifoodPorDia(ctx: Context): Map<String, Double> {
        val out = HashMap<String, Double>()
        runCatching {
            val f = File(dir(ctx), ARQ_IFOOD)
            if (!f.exists()) return out
            f.forEachLine { l ->
                runCatching {
                    val o = JSONObject(l)
                    val k = iso(o.optLong("t"))
                    out[k] = (out[k] ?: 0.0) + o.optDouble("v", 0.0)
                }
            }
        }
        return out
    }

    fun manuaisPorDia(ctx: Context): Map<String, Double> {
        val out = HashMap<String, Double>()
        runCatching {
            val f = File(dir(ctx), ARQ_MANUAIS)
            if (!f.exists()) return out
            val o = JSONObject(f.readText())
            o.keys().forEach { k -> out[k] = o.optDouble(k, 0.0) }
        }
        return out
    }

    /** app: 0 = todos, 1 = iFood, 2 = InDrive / lançamentos manuais. */
    fun porDia(ctx: Context, app: Int): Map<String, Double> {
        val i = if (app == 2) emptyMap() else ifoodPorDia(ctx)
        val m = if (app == 1) emptyMap() else manuaisPorDia(ctx)
        val out = HashMap<String, Double>(i)
        m.forEach { (k, v) -> out[k] = (out[k] ?: 0.0) + v }
        return out
    }

    /** Soma dos dias no intervalo [ini, fim). */
    fun soma(mapa: Map<String, Double>, ini: Long, fim: Long): Double {
        var s = 0.0
        val c = Calendar.getInstance().apply { timeInMillis = ini }
        while (c.timeInMillis < fim) {
            s += mapa[iso(c.timeInMillis)] ?: 0.0
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return s
    }
}
