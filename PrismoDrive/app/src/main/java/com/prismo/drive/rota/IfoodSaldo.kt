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
 * Lê o saldo mostrado na tela inicial do iFood (ex.: "R$ 151,73", no topo, ao lado do olho)
 * e transforma cada AUMENTO do saldo num "Recebimento iFood" no Prismo Drive.
 *
 * - O saldo do iFood é acumulado (vai somando até o repasse/saque). Por isso só a DIFERENÇA
 *   desde a última leitura vira recebimento — o mesmo dinheiro nunca entra duas vezes.
 * - Primeira leitura: vira só o ponto de partida (o que já estava no saldo não é lançado de novo).
 * - Saldo caiu (repasse, saque): só atualiza o ponto de partida, sem lançar nada.
 * - O valor precisa aparecer igual em 2 leituras seguidas (≥ 1,5 s) para evitar leituras pela metade.
 * - Segurança: um salto de mais de R$ 400 entre duas leituras não é lançado (só reposiciona).
 *
 * O serviço (processo ":rotas") grava os recebimentos numa fila em arquivo; a tela do Prismo Drive
 * (processo principal) busca a fila, lança no faturamento do dia e na carteira, e confirma.
 */
object IfoodSaldo {

    const val ACTION_NOVO = "com.prismo.drive.action.IFOOD_RECEBIMENTO"

    private const val ARQ_ESTADO = "ifood_saldo.json"
    private const val ARQ_FILA = "ifood_pendentes.json"
    private const val ARQ_LOCK = "ifood_saldo.lock"

    private val VALOR = Regex("""^R\$\s*(\d{1,3}(?:\.\d{3})*|\d+),(\d{2})$""")

    // estabilidade da leitura (só no processo do serviço)
    private var candidato = -1.0
    private var candidatoDesde = 0L

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

    // ───────────────────────── leitura da tela (serviço) ─────────────────────────

    /** É a tela inicial do iFood? Procura o saldo no topo; null se não achar. */
    fun lerDaTela(root: AccessibilityNodeInfo, alturaTela: Int): Double? {
        var marcador = false
        var achado: Double? = null
        var achadoTop = Int.MAX_VALUE
        val r = Rect()
        val fila = ArrayDeque<AccessibilityNodeInfo>()
        fila.add(root)
        var vistos = 0
        while (fila.isNotEmpty() && vistos < 600) {
            val n = fila.removeFirst()
            vistos++
            val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
            if (t.isNotEmpty()) {
                // marcadores da TELA INICIAL (evita confundir com o valor de uma rota em andamento)
                if (t.contains("Rotas pra entregar", true) || t.contains("conferir as Rotas", true)) marcador = true
                val m = VALOR.find(t.replace(' ', ' '))
                if (m != null) {
                    n.getBoundsInScreen(r)
                    // o saldo fica no topo da tela inicial (primeiros ~22% da altura)
                    if (r.top >= 0 && r.top < alturaTela * 0.22 && r.top < achadoTop) {
                        val inteiro = m.groupValues[1].replace(".", "")
                        achado = "$inteiro.${m.groupValues[2]}".toDoubleOrNull()
                        achadoTop = r.top
                    }
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { fila.add(it) }
        }
        return if (marcador) achado else null
    }

    /** Chamado a cada leitura da tela inicial do iFood. Retorna o valor creditado (ou null). */
    fun observar(ctx: Context, saldo: Double): Double? {
        val agora = SystemClock.uptimeMillis()
        if (Math.abs(saldo - candidato) > 0.001) {
            candidato = saldo
            candidatoDesde = agora
            return null
        }
        if (agora - candidatoDesde < 1500) return null
        return registrar(ctx, saldo)
    }

    private fun registrar(ctx: Context, saldo: Double): Double? = runCatching {
        comTrava(ctx) {
            val fEst = File(dir(ctx), ARQ_ESTADO)
            val est = lerJson(fEst)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            val ultimo = if (est.has("ultimo")) est.optDouble("ultimo", -1.0) else -1.0
            val agora = System.currentTimeMillis()
            var credito: Double? = null
            when {
                ultimo < 0 -> { /* primeira leitura: só ponto de partida */ }
                // aumento acima de R$ 400 de uma vez é leitura errada ou app sem abrir há dias:
                // só reposiciona a referência, para nunca lançar valor indevido
                saldo > ultimo + 400.0 -> { }
                saldo > ultimo + 0.009 -> credito = Math.round((saldo - ultimo) * 100) / 100.0
                saldo < ultimo - 0.009 -> { /* repasse/saque: só reposiciona */ }
                else -> return@comTrava null   // igual: nada a fazer
            }
            est.put("ultimo", saldo).put("ts", agora)
            gravar(fEst, est.toString())
            if (credito != null && credito > 0) {
                val fFila = File(dir(ctx), ARQ_FILA)
                val fila = lerJson(fFila)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
                fila.put(JSONObject()
                    .put("id", agora.toString(36) + (Math.random() * 1e6).toLong().toString(36))
                    .put("valor", credito)
                    .put("saldo", saldo)
                    .put("ts", agora))
                gravar(fFila, fila.toString())
            }
            credito
        }
    }.getOrNull()?.also { avisarApp(ctx) }

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

    /** Situação para a tela do assistente: último saldo lido e quando. */
    fun estado(ctx: Context): JSONObject = runCatching {
        lerJson(File(dir(ctx), ARQ_ESTADO))?.let { JSONObject(it) }
    }.getOrNull() ?: JSONObject()
}
