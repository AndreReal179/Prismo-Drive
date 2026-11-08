package com.prismo.drive.rota

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Recebimento iFood: quando aparece a tela "Oba, mais uma rota concluída! · Você ganhou: R$ 60,00"
 * (às vezes com "+ R$ 8,00" em verde, que é o bônus de promoção do próprio iFood), o valor vira um
 * lançamento no Prismo Drive: entra no Faturamento do dia e no Saldo em conta da Carteira, com
 * "Recebimento iFood" (e "Bônus iFood" separado) no extrato.
 *
 * - Cada tela de rota concluída é lançada UMA vez (a mesma tela relida várias vezes não repete).
 * - Os valores precisam ficar iguais por ~1,5 s antes de lançar (o bônus às vezes aparece depois).
 * - Se o bônus aparecer depois do valor já lançado, só o bônus é lançado.
 *
 * O serviço (processo ":rotas") grava os recebimentos numa fila em arquivo; a tela do Prismo Drive
 * (processo principal) busca a fila, lança e confirma.
 */
object IfoodSaldo {

    const val ACTION_NOVO = "com.prismo.drive.action.IFOOD_RECEBIMENTO"

    private const val ARQ_ESTADO = "ifood_ultimo.json"
    private const val ARQ_FILA = "ifood_pendentes.json"
    private const val ARQ_LOCK = "ifood_saldo.lock"

    /** "R$ 60,00", "+ R$ 8,00", "R$ 1.234,56" (pode haver mais de um no mesmo texto). */
    private val VALOR = Regex("""(\+\s*)?R\$\s*(\d{1,3}(?:\.\d{3})*|\d+),(\d{2})""")

    private class Lido(val valor: Double, val mais: Boolean, val top: Int, val left: Int, val altura: Int)

    // estabilidade e anti-repetição (só no processo do serviço)
    private var candKey = ""
    private var candDesde = 0L
    private var ultPrincipal = -1.0
    private var ultBonus = 0.0
    private var ultVisto = 0L

    private fun dir(ctx: Context) = (ctx.applicationContext ?: ctx).filesDir

    private inline fun <T> comTrava(ctx: Context, bloco: () -> T): T {
        RandomAccessFile(File(dir(ctx), ARQ_LOCK), "rw").use { raf ->
            raf.channel.lock().use { return bloco() }
        }
    }

    private fun lerJson(f: File): String? = if (f.exists()) runCatching { f.readText() }.getOrNull() else null

    private fun gravar(f: File, txt: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(txt)
        if (!tmp.renameTo(f)) { f.writeText(txt); tmp.delete() }
    }

    private fun num(m: MatchResult): Double? =
        (m.groupValues[2].replace(".", "") + "." + m.groupValues[3]).toDoubleOrNull()

    // ───────────────────────── leitura da tela (serviço) ─────────────────────────

    /**
     * Procura a tela de rota concluída. Retorna (valor da rota, bônus) ou null se não for essa tela.
     * Usa só o que está ABAIXO de "Você ganhou" (o saldo do topo da tela não entra).
     */
    fun lerConclusao(root: AccessibilityNodeInfo): Pair<Double, Double>? {
        var concluida = false
        var ganhouBottom = -1
        var limiteBaixo = Int.MAX_VALUE
        val valores = ArrayList<Lido>()
        val r = Rect()
        val fila = ArrayDeque<AccessibilityNodeInfo>()
        fila.add(root)
        var vistos = 0
        while (fila.isNotEmpty() && vistos < 800) {
            val n = fila.removeFirst()
            vistos++
            val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").replace('\u00A0', ' ').trim()
            if (t.isNotEmpty()) {
                if (t.contains("rota concluída", true) || t.contains("rota concluida", true)) concluida = true
                if (t.contains("Você ganhou", true) || t.contains("Voce ganhou", true)) {
                    n.getBoundsInScreen(r); ganhouBottom = maxOf(ganhouBottom, r.top)
                }
                if (t.contains("Avaliar a rota", true) || t.contains("Sair sem avaliar", true)) {
                    n.getBoundsInScreen(r); limiteBaixo = minOf(limiteBaixo, r.top)
                }
                val achados = VALOR.findAll(t).toList()
                if (achados.isNotEmpty()) {
                    n.getBoundsInScreen(r)
                    achados.forEachIndexed { i, m ->
                        num(m)?.let { v ->
                            valores.add(Lido(v, m.groupValues[1].isNotEmpty(), r.top, r.left + i, r.height()))
                        }
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { fila.add(it) }
        }
        if (!concluida || ganhouBottom < 0) return null
        val abaixo = valores.filter { it.top >= ganhouBottom && it.top < limiteBaixo }
            .sortedWith(compareBy<Lido>({ it.top }, { it.left }))
        val principal = abaixo.firstOrNull { !it.mais } ?: return null
        // bônus: valor com "+" ou outro valor na MESMA linha, à direita do principal
        val bonus = abaixo.filter { it !== principal }.firstOrNull { b ->
            b.mais || (Math.abs(b.top - principal.top) <= maxOf(principal.altura, 24) && b.left > principal.left)
        }
        if (principal.valor <= 0) return null
        return Pair(principal.valor, bonus?.valor ?: 0.0)
    }

    /** Chamado a cada leitura da tela de rota concluída. */
    fun observarConclusao(ctx: Context, principal: Double, bonus: Double) {
        val agora = SystemClock.uptimeMillis()
        val key = String.format(java.util.Locale.US, "%.2f|%.2f", principal, bonus)
        if (key != candKey) { candKey = key; candDesde = agora; return }
        if (agora - candDesde < 1500) return

        val mesmaTela = Math.abs(principal - ultPrincipal) < 0.005 && agora - ultVisto < 120_000
        if (mesmaTela) {
            ultVisto = agora
            if (bonus > ultBonus + 0.005) {          // bônus apareceu depois: lança só ele
                enfileirar(ctx, 0.0, Math.round((bonus - ultBonus) * 100) / 100.0)
                ultBonus = bonus
            }
            return
        }
        ultPrincipal = principal
        ultBonus = bonus
        ultVisto = agora
        enfileirar(ctx, principal, bonus)
    }

    private fun enfileirar(ctx: Context, valor: Double, bonus: Double) {
        if (valor <= 0 && bonus <= 0) return
        runCatching {
            comTrava(ctx) {
                val agora = System.currentTimeMillis()
                val fFila = File(dir(ctx), ARQ_FILA)
                val fila = lerJson(fFila)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
                fila.put(JSONObject()
                    .put("id", agora.toString(36) + (Math.random() * 1e6).toLong().toString(36))
                    .put("valor", valor)
                    .put("bonus", bonus)
                    .put("ts", agora))
                gravar(fFila, fila.toString())
                gravar(File(dir(ctx), ARQ_ESTADO), JSONObject()
                    .put("valor", valor).put("bonus", bonus).put("ts", agora).toString())
            }
        }
        avisarApp(ctx)
    }

    /** Acorda a tela do Prismo Drive (se estiver aberta em segundo plano) para lançar na hora. */
    private fun avisarApp(ctx: Context) {
        runCatching {
            val app = ctx.applicationContext ?: ctx
            app.sendBroadcast(Intent(ACTION_NOVO).setPackage(app.packageName))
        }
    }

    // ───────────────────────── fila (tela do Prismo Drive) ─────────────────────────

    fun pendentes(ctx: Context): String = runCatching {
        comTrava(ctx) { lerJson(File(dir(ctx), ARQ_FILA)) ?: "[]" }
    }.getOrDefault("[]")

    /** Remove da fila os recebimentos que a tela já lançou. */
    fun confirmar(ctx: Context, idsJson: String) {
        runCatching {
            val ids = JSONArray(idsJson).let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
            if (ids.isEmpty()) return
            comTrava(ctx) {
                val f = File(dir(ctx), ARQ_FILA)
                val fila = lerJson(f)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
                val resto = JSONArray()
                for (i in 0 until fila.length()) {
                    val o = fila.optJSONObject(i) ?: continue
                    if (o.optString("id") !in ids) resto.put(o)
                }
                gravar(f, resto.toString())
            }
        }
    }

    /** Situação para a tela do assistente: último recebimento lançado e quando. */
    fun estado(ctx: Context): JSONObject = runCatching {
        lerJson(File(dir(ctx), ARQ_ESTADO))?.let { JSONObject(it) }
    }.getOrNull() ?: JSONObject()
}
