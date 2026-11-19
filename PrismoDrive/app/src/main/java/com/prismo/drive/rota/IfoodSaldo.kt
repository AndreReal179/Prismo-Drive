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
 * - Lança na hora em que a tela aparece, UMA vez por tela (relida várias vezes não repete).
 * - Se o bônus aparecer depois do valor já lançado, só o bônus é lançado.
 * - A cada lançamento o botão flutuante comemora (moeda girando, "+R$" subindo e som de moedas).
 *
 * O serviço (processo ":rotas") grava os recebimentos numa fila em arquivo; a tela do Prismo Drive
 * (processo principal) busca a fila, lança e confirma.
 */
object IfoodSaldo {

    const val ACTION_NOVO = "com.prismo.drive.action.IFOOD_RECEBIMENTO"
    /** Botão flutuante / notificação "Parar rota": abre o app perguntando quanto ganhou. */
    const val ACAO_PARAR = "rota_parar"
    private const val ARQ_PARADA = "ifood_parada.json"          // rota parada à mão, esperando o valor na tela do app
    private const val ARQ_ULT_PARADA = "ifood_ult_parada.json"  // se a tela de ganho aparecer depois, junta nesta rota
    private const val JUNTAR_MS = 90 * 60_000L
    // depois de parar à mão, o "EM DESLOCAMENTO" que ainda está na tela não começa uma rota nova
    @Volatile private var ignorarDesloc = false
    @Volatile private var semDeslocDesde = 0L
    @Volatile private var ignorarAte = 0L

    private const val ARQ_ESTADO = "ifood_ultimo.json"
    private const val ARQ_FILA = "ifood_pendentes.json"
    private const val ARQ_LOCK = "ifood_saldo.lock"

    /** "R$ 60,00", "+ R$ 8,00", "R$ 1.234,56" (pode haver mais de um no mesmo texto). */
    private val VALOR = Regex("""(\+\s*)?R\$\s*(\d{1,3}(?:\.\d{3})*|\d+),(\d{2})""")

    private class Lido(val valor: Double, val mais: Boolean, val top: Int, val left: Int, val altura: Int)

    // sessão da tela "rota concluída" (só no processo do serviço)
    private var sessaoAtiva = false        // a tela está (ou estava há pouco) aberta
    private var ultPrincipal = 0.0
    private var ultBonus = 0.0
    private var sumiuDesde = 0L            // 1ª leitura do iFood SEM a tela de conclusão
    private var rotaId = ""                // liga valor + bônus que chegam separados à mesma rota

    private const val ARQ_INICIO = "ifood_rota_inicio"
    private const val ARQ_OFERTA = "ifood_oferta.json"          // última oferta de rota vista
    private const val ARQ_ROTA_OFERTA = "ifood_rota_oferta"     // km da oferta da rota em andamento
    @Volatile private var ultDeslocVisto = 0L
    private const val MAX_ROTA_MS = 5 * 3600_000L   // rota "esquecida" há mais de 5 h não conta tempo

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


    // ───────────────────────── duração da rota ─────────────────────────

    /** A tela "EM DESLOCAMENTO" do iFood está aberta? (rota iniciada) */
    fun emDeslocamento(root: AccessibilityNodeInfo): Boolean {
        val fila = ArrayDeque<AccessibilityNodeInfo>()
        fila.add(root)
        var vistos = 0
        while (fila.isNotEmpty() && vistos < 400) {
            val n = fila.removeFirst()
            vistos++
            val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "")
            if (t.contains("em deslocamento", true)) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let { fila.add(it) }
        }
        return false
    }

    /**
     * Marca o início da rota na 1ª vez que aparece "EM DESLOCAMENTO" (as próximas paradas não reiniciam)
     * e liga o GPS para medir os km. Guarda também o km da última oferta vista (reserva sem GPS).
     */
    fun marcarInicio(ctx: Context) {
        val agora = System.currentTimeMillis()
        if (ignorarDesloc && agora < ignorarAte) { semDeslocDesde = 0L; return }
        ignorarDesloc = false
        ultDeslocVisto = agora
        val ini = inicio(ctx)
        if (ini > 0 && agora - ini < MAX_ROTA_MS) {
            if (!KmGps.ativo) KmGps.iniciar(ctx, zerar = false)     // serviço reiniciado no meio da rota
            return
        }
        runCatching { File(dir(ctx), ARQ_INICIO).writeText(agora.toString()) }
        val of = ultimaOferta(ctx, 30 * 60_000L)
        runCatching { File(dir(ctx), ARQ_ROTA_OFERTA).writeText(of.toString()) }
        KmGps.iniciar(ctx, zerar = true)
    }

    /* ── v2.1.8 · Paradas do trajeto: cada vez que a tela "NO LOCAL" do iFood abre (coleta ou entrega) ── */
    @Volatile private var noLocal = false
    @Volatile private var foraDoLocalDesde = 0L

    /** Tela "NO LOCAL" (com "Entregar pedido"/"Sair do local"). Devolve o tipo ("entrega", "coleta", "parada") ou null. */
    fun telaNoLocal(root: AccessibilityNodeInfo): String? {
        val fila = ArrayDeque<AccessibilityNodeInfo>()
        fila.add(root)
        var vistos = 0
        var titulo = false; var entrega = false; var coleta = false; var sair = false
        while (fila.isNotEmpty() && vistos < 400) {
            val n = fila.removeFirst()
            vistos++
            val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").trim()
            if (t.equals("no local", true)) titulo = true
            if (t.contains("entregar pedido", true)) entrega = true
            if (t.contains("sair do local", true)) sair = true
            if (t.contains("coletar", true) || t.contains("retirar pedido", true) || t.contains("confirmar coleta", true) || t.contains("confirmar retirada", true)) coleta = true
            for (i in 0 until n.childCount) n.getChild(i)?.let { fila.add(it) }
        }
        if (!titulo || !(entrega || coleta || sair)) return null
        return if (entrega) "entrega" else if (coleta) "coleta" else "parada"
    }

    /** Leitura do iFood: marca a parada na 1ª vez que a tela "NO LOCAL" aparece; libera a próxima ao sair dela. */
    fun verNoLocal(ctx: Context, root: AccessibilityNodeInfo) {
        val tipo = runCatching { telaNoLocal(root) }.getOrNull()
        val agora = System.currentTimeMillis()
        if (tipo == null) {
            if (noLocal) {
                if (foraDoLocalDesde == 0L) foraDoLocalDesde = agora
                else if (agora - foraDoLocalDesde >= 8_000L) { noLocal = false; foraDoLocalDesde = 0L }
            }
            return
        }
        foraDoLocalDesde = 0L
        if (noLocal) return
        noLocal = true
        if (!emAndamento(ctx) && !KmGps.ativo) return
        val loc = KmGps.posicao()
        Trajeto.marcarParada(ctx, Trajeto.ATUAL, loc, tipo)
        // turno ligado: a parada também vai para o trajeto do turno
        runCatching {
            val e = Turno.estadoRapido(ctx)
            if (e.ativo) Trajeto.marcarParada(ctx, "turno_" + java.lang.Long.toString(e.ini, 36), loc, tipo)
        }
    }

    /** Leitura do iFood SEM "EM DESLOCAMENTO": 1 min fora dele libera a próxima rota depois de uma parada manual. */
    fun semDeslocamento() {
        if (!ignorarDesloc) return
        val agora = System.currentTimeMillis()
        if (semDeslocDesde == 0L) { semDeslocDesde = agora; return }
        if (agora - semDeslocDesde >= 60_000L) { ignorarDesloc = false; semDeslocDesde = 0L }
    }

    /** Há uma rota do iFood em andamento (o GPS está medindo)? Funciona em qualquer processo. */
    fun emAndamento(ctx: Context): Boolean {
        val ini = inicio(ctx)
        return ini > 0 && System.currentTimeMillis() - ini < MAX_ROTA_MS
    }

    /** Rota em andamento para a tela do app: {ativo, ini, km}. */
    fun atual(ctx: Context): String = runCatching {
        val ini = inicio(ctx)
        val ativo = ini > 0 && System.currentTimeMillis() - ini < MAX_ROTA_MS
        JSONObject().put("ativo", ativo).put("ini", if (ativo) ini else 0L)
            .put("km", if (ativo) Math.round(KmGps.kmArquivo(ctx) * 100) / 100.0 else 0.0).toString()
    }.getOrDefault("{\"ativo\":false}")

    /**
     * "Parar rota" confirmado na tela do app (roda no processo do assistente): fecha o km, o tempo e o
     * trajeto como se a rota tivesse sido concluída e deixa o resultado para a tela pedir o valor.
     */
    fun pararManual(ctx: Context) {
        val agora = System.currentTimeMillis()
        val ini = inicio(ctx)
        if (ini <= 0 && !KmGps.ativo) return
        val id = agora.toString(36)
        val (km, fonte) = runCatching { fecharKm(ctx) }.getOrDefault(Pair(0.0, ""))
        runCatching { Trajeto.fechar(ctx, Trajeto.ATUAL, id) }
        val dur = fecharDuracao(ctx)
        val o = JSONObject().put("id", id).put("km", km).put("kmFonte", fonte).put("dur", dur)
            .put("ini", if (ini > 0) ini else agora - dur).put("ts", agora).toString()
        runCatching { comTrava(ctx) { gravar(File(dir(ctx), ARQ_PARADA), o); gravar(File(dir(ctx), ARQ_ULT_PARADA), o) } }
        ignorarDesloc = true; semDeslocDesde = 0L; ignorarAte = agora + 3 * 3600_000L
        avisarApp(ctx)
    }

    /** Rota parada à mão esperando o valor ("" = nenhuma). Lido pela tela do app. */
    fun parada(ctx: Context): String = runCatching {
        comTrava(ctx) { lerJson(File(dir(ctx), ARQ_PARADA)) ?: "" }
    }.getOrDefault("")

    /** A tela lançou (ou descartou) a rota parada. */
    fun paradaOk(ctx: Context, id: String?) {
        runCatching {
            comTrava(ctx) {
                val f = File(dir(ctx), ARQ_PARADA)
                val o = lerJson(f)?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (o == null || id.isNullOrEmpty() || o.optString("id") == id) f.delete()
            }
        }
    }

    /** Última rota parada à mão há pouco (para juntar o ganho que aparecer depois). Consome. */
    private fun paradaRecente(ctx: Context): JSONObject? = runCatching {
        comTrava(ctx) {
            val f = File(dir(ctx), ARQ_ULT_PARADA)
            val o = lerJson(f)?.let { runCatching { JSONObject(it) }.getOrNull() }
            f.delete()
            if (o != null && System.currentTimeMillis() - o.optLong("ts") in 0..JUNTAR_MS) o else null
        }
    }.getOrNull()

    /** Batimento do serviço: retoma o GPS de uma rota em andamento ou desliga o de uma rota abandonada. */
    fun manterGps(ctx: Context) {
        val agora = System.currentTimeMillis()
        val ini = inicio(ctx)
        val emRota = ini > 0 && agora - ini < MAX_ROTA_MS
        if (KmGps.ativo) {
            // sem "EM DESLOCAMENTO" há mais de 2 h (rota cancelada): desliga o GPS, o km medido fica guardado
            if (!emRota || (ultDeslocVisto > 0 && agora - ultDeslocVisto > 2 * 3600_000L)) KmGps.parar(ctx)
        } else if (emRota && ultDeslocVisto == 0L) {
            ultDeslocVisto = agora
            KmGps.iniciar(ctx, zerar = false)
        }
    }

    // ───────────────────────── km da oferta (reserva) ─────────────────────────

    /** Oferta de rota do iFood aberta na tela (detalhe/popup com 1 rota): guarda o km total dela. */
    fun lembrarOferta(ctx: Context, km: Double) {
        if (km <= 0 || km > 300) return
        runCatching {
            File(dir(ctx), ARQ_OFERTA).writeText(JSONObject().put("km", km).put("ts", System.currentTimeMillis()).toString())
        }
    }

    private fun ultimaOferta(ctx: Context, validade: Long): Double = runCatching {
        val o = JSONObject(File(dir(ctx), ARQ_OFERTA).readText())
        if (System.currentTimeMillis() - o.optLong("ts") <= validade) o.optDouble("km", 0.0) else 0.0
    }.getOrDefault(0.0)

    /**
     * Fecha o km da rota. GPS primeiro; o km da oferta só entra quando não há permissão de localização
     * (ou o GPS não conseguiu medir nada). Devolve (km, "gps" | "estimado" | "").
     */
    private fun fecharKm(ctx: Context): Pair<Double, String> {
        val (gpsKm, pontos) = KmGps.parar(ctx)
        KmGps.limpar(ctx)
        val fOf = File(dir(ctx), ARQ_ROTA_OFERTA)
        var oferta = runCatching { fOf.readText().trim().toDouble() }.getOrDefault(0.0)
        runCatching { fOf.delete() }
        if (oferta <= 0) oferta = ultimaOferta(ctx, 3 * 3600_000L)
        ultDeslocVisto = 0L
        val r = when {
            KmGps.temPermissao(ctx) && gpsKm >= 0.2 && pontos >= 3 -> Pair(gpsKm, "gps")
            oferta > 0 -> Pair(oferta, "estimado")
            gpsKm >= 0.05 -> Pair(gpsKm, "gps")
            else -> Pair(0.0, "")
        }
        return Pair(Math.round(r.first * 100) / 100.0, r.second)
    }

    fun inicio(ctx: Context): Long =
        runCatching { File(dir(ctx), ARQ_INICIO).readText().trim().toLong() }.getOrDefault(0L)

    /** Fecha a contagem: devolve a duração (ms) e zera o início. */
    private fun fecharDuracao(ctx: Context): Long {
        val ini = inicio(ctx)
        runCatching { File(dir(ctx), ARQ_INICIO).delete() }
        val d = System.currentTimeMillis() - ini
        return if (ini > 0 && d in 1..MAX_ROTA_MS) d else 0L
    }

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

    /**
     * Chamado a cada leitura da tela de rota concluída. Lança NA HORA (sem esperar), uma vez por tela.
     * Se depois disso o valor ou o bônus aumentarem na mesma tela (animação, bônus que aparece
     * depois), lança só a diferença. Retorna o total lançado agora (null = nada novo).
     */
    fun observarConclusao(ctx: Context, principal: Double, bonus: Double): Double? {
        sumiuDesde = 0L
        if (sessaoAtiva) {
            val dv = Math.round((principal - ultPrincipal) * 100) / 100.0
            val db = Math.round((bonus - ultBonus) * 100) / 100.0
            if (dv <= 0.0 && db <= 0.0) return null
            ultPrincipal = maxOf(ultPrincipal, principal)
            ultBonus = maxOf(ultBonus, bonus)
            val v = maxOf(dv, 0.0); val b = maxOf(db, 0.0)
            enfileirar(ctx, v, b)
            return v + b
        }
        sessaoAtiva = true
        ultPrincipal = principal
        ultBonus = bonus
        // a pessoa parou a rota à mão e só agora o iFood mostrou o ganho: o valor vai para aquela rota
        // (o km, o tempo e o trajeto já foram fechados na parada)
        val par = if (inicio(ctx) <= 0 && !KmGps.ativo) paradaRecente(ctx) else null
        if (par != null) {
            rotaId = par.optString("id")
            ignorarDesloc = false
            enfileirar(ctx, principal, bonus, parada = true)
            return principal + bonus
        }
        runCatching { File(dir(ctx), ARQ_ULT_PARADA).delete() }
        rotaId = System.currentTimeMillis().toString(36)
        val (km, fonte) = runCatching { fecharKm(ctx) }.getOrDefault(Pair(0.0, ""))
        runCatching { Trajeto.fechar(ctx, Trajeto.ATUAL, rotaId) }   // o trajeto ganha o id da rota (Detalhes na Linha do tempo)
        enfileirar(ctx, principal, bonus, fecharDuracao(ctx), km, fonte)
        return principal + bonus
    }

    /**
     * Leitura do iFood em que a tela de conclusão NÃO está. Depois de ~1,2 s sem ela, a sessão
     * termina: a próxima "rota concluída" conta como nova. (Um tropeço de leitura com a tela ainda
     * aberta não encerra a sessão, então a mesma rota nunca é lançada duas vezes.)
     */
    fun telaSumiu() {
        if (!sessaoAtiva) return
        val agora = SystemClock.uptimeMillis()
        if (sumiuDesde == 0L) { sumiuDesde = agora; return }
        if (agora - sumiuDesde >= 1200) { sessaoAtiva = false; sumiuDesde = 0L }
    }

    private fun enfileirar(ctx: Context, valor: Double, bonus: Double, dur: Long = 0L, km: Double = 0.0, kmFonte: String = "", parada: Boolean = false) {
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
                    .put("ts", agora)
                    .put("rota", rotaId.ifEmpty { agora.toString(36) })
                    .put("app", "ifood")
                    .put("dur", dur)
                    .put("km", km)
                    .put("kmFonte", kmFonte)
                    .put("parada", parada))
                gravar(fFila, fila.toString())
                Ganhos.registrarIfood(ctx, agora, valor + bonus)   // rendimento do dia / calendário
                gravar(File(dir(ctx), ARQ_ESTADO), JSONObject()
                    .put("valor", valor).put("bonus", bonus).put("ts", agora)
                    .put("km", km).put("kmFonte", kmFonte).toString())
            }
        }
        avisarApp(ctx)
        // widget da tela inicial: soma a rota na hora, mesmo com o app fechado
        runCatching { com.prismo.drive.WidgetUtil.atualizarTodos(ctx) }
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
