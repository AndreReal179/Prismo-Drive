package com.prismo.drive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Dados do app guardados FORA da página (assistente de rotas, turno, fila do iFood, widgets, lembretes).
 * Vão junto com os dados da página para a conta, e são apagados ao sair da conta.
 */
object DadosConta {
    /** Arquivos com dados do motorista (em filesDir). */
    private val ARQUIVOS = listOf(
        "ganhos_ifood.jsonl", "ganhos_manuais.json", "ifood_ultimo.json", "ifood_pendentes.json",
        "turno.json", "lembrete_quitacao.json"
    )
    /** Arquivos passageiros: não vão para a conta, só são apagados ao sair. */
    private val PASSAGEIROS = listOf(
        "ifood_rota_inicio", "ifood_oferta.json", "ifood_rota_oferta", "ifood_rota_gps.json", "turno_fim",
        "widget_dados.json"
    )
    /** Ajustes do assistente de rotas e dos widgets. */
    private val PREFS = listOf("rotaguru", "rotaguru_best2")
    /** Só apagados ao sair (o widget é refeito pela página). */
    private val PREFS_LIMPAR = PREFS + "prismo_widget"
    private const val LIMITE_RELATORIO = 2_500_000

    private fun dir(ctx: Context) = (ctx.applicationContext ?: ctx).filesDir
    private fun relatorios(ctx: Context): List<File> =
        (File(dir(ctx), "reports").listFiles()?.filter { it.isFile && it.name.endsWith(".jsonl") } ?: emptyList())
            .sortedByDescending { it.name }

    /** Assinatura barata: muda quando algum dado nativo muda. */
    fun assinatura(ctx: Context): String {
        var h = 17L
        fun soma(f: File) { if (f.exists()) h = h * 31 + f.lastModified() * 7 + f.length() }
        ARQUIVOS.forEach { soma(File(dir(ctx), it)) }
        relatorios(ctx).forEach { soma(it) }
        PREFS.forEach { n -> h = h * 31 + ctx.getSharedPreferences(n, Context.MODE_PRIVATE).all.toString().hashCode() }
        return h.toString()
    }

    fun exportar(ctx: Context): String {
        val arq = JSONObject()
        ARQUIVOS.forEach { n -> runCatching { val f = File(dir(ctx), n); if (f.exists() && f.length() < 3_000_000) arq.put(n, f.readText()) } }
        var total = 0L
        for (f in relatorios(ctx)) {          // mais recentes primeiro, até ~2,5 MB
            if (total + f.length() > LIMITE_RELATORIO) break
            runCatching { arq.put("reports/" + f.name, f.readText()); total += f.length() }
        }
        val prefs = JSONObject()
        PREFS.forEach { n ->
            val o = JSONObject()
            ctx.getSharedPreferences(n, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                val t = when (v) {
                    is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is String -> "s"; is Set<*> -> "ss"; else -> null
                } ?: return@forEach
                o.put(k, JSONObject().put("t", t).put("v", if (v is Set<*>) JSONArray(v.map { it.toString() }) else v))
            }
            prefs.put(n, o)
        }
        return JSONObject().put("v", 1).put("arquivos", arq).put("prefs", prefs).toString()
    }

    /** Troca de dados (importar): apaga só os dados, mantém as cópias de segurança. */
    private fun limparDados(ctx: Context) {
        (ARQUIVOS + PASSAGEIROS).forEach { runCatching { File(dir(ctx), it).delete() } }
        relatorios(ctx).forEach { runCatching { it.delete() } }
        PREFS_LIMPAR.forEach { n -> runCatching { ctx.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear().commit() } }
    }

    // ── Cópias de segurança: antes de trocar os dados do celular, guarda os de antes (as 3 últimas) ──
    private const val PASTA_COPIAS = "copias"

    fun salvarCopia(ctx: Context, json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return try {
            val d = File(dir(ctx), PASTA_COPIAS).apply { mkdirs() }
            File(d, "copia_" + System.currentTimeMillis() + ".json").writeText(json)
            (d.listFiles()?.filter { it.name.startsWith("copia_") }?.sortedByDescending { it.name } ?: emptyList())
                .drop(3).forEach { it.delete() }
            true
        } catch (_: Throwable) { false }
    }

    /** [{"id":"copia_…","tam":bytes}] mais recentes primeiro (a data/motivo ficam dentro do arquivo). */
    fun listarCopias(ctx: Context): String {
        val a = JSONArray()
        (File(dir(ctx), PASTA_COPIAS).listFiles()?.filter { it.name.startsWith("copia_") }?.sortedByDescending { it.name } ?: emptyList())
            .forEach { f ->
                val info = runCatching {
                    // lê só o começo: {"em":…,"motivo":"…", …
                    val ini = f.bufferedReader().use { r -> val b = CharArray(400); val n = r.read(b); if (n > 0) String(b, 0, n) else "" }
                    JSONObject().put("id", f.name.removeSuffix(".json")).put("tam", f.length()).put("ini", ini)
                }.getOrNull()
                if (info != null) a.put(info)
            }
        return a.toString()
    }

    fun lerCopia(ctx: Context, id: String?): String {
        if (id.isNullOrBlank() || !Regex("^copia_\\d+$").matches(id)) return ""
        return runCatching { File(File(dir(ctx), PASTA_COPIAS), "$id.json").readText() }.getOrDefault("")
    }

    /** Apaga os dados do motorista deste celular (ao sair da conta). */
    fun limpar(ctx: Context) {
        (ARQUIVOS + PASSAGEIROS).forEach { runCatching { File(dir(ctx), it).delete() } }
        relatorios(ctx).forEach { runCatching { it.delete() } }
        PREFS_LIMPAR.forEach { n -> runCatching { ctx.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear().commit() } }
        runCatching { File(dir(ctx), PASTA_COPIAS).deleteRecursively() }
        runCatching { Lembretes.salvarQuitacao(ctx, null) }
        runCatching { WidgetUtil.atualizarTodos(ctx) }
    }

    /** Troca os dados deste celular pelos da conta. */
    fun importar(ctx: Context, json: String?): Boolean {
        if (json.isNullOrBlank()) return false
        return try {
            val o = JSONObject(json)
            limparDados(ctx)
            val arq = o.optJSONObject("arquivos") ?: JSONObject()
            arq.keys().forEach { n ->
                val permitido = n in ARQUIVOS || (n.startsWith("reports/") && n.endsWith(".jsonl") && !n.contains(".."))
                if (permitido) runCatching { File(dir(ctx), n).also { it.parentFile?.mkdirs() }.writeText(arq.optString(n)) }
            }
            val prefs = o.optJSONObject("prefs") ?: JSONObject()
            prefs.keys().forEach { n ->
                if (n !in PREFS) return@forEach
                val e = ctx.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear()
                val p = prefs.optJSONObject(n) ?: return@forEach
                p.keys().forEach { k ->
                    val it = p.optJSONObject(k) ?: return@forEach
                    when (it.optString("t")) {
                        "b" -> e.putBoolean(k, it.optBoolean("v"))
                        "i" -> e.putInt(k, it.optInt("v"))
                        "l" -> e.putLong(k, it.optLong("v"))
                        "f" -> e.putFloat(k, it.optDouble("v").toFloat())
                        "s" -> e.putString(k, it.optString("v"))
                        "ss" -> { val a = it.optJSONArray("v") ?: JSONArray(); e.putStringSet(k, (0 until a.length()).map { i -> a.optString(i) }.toSet()) }
                    }
                }
                e.commit()
            }
            runCatching { Lembretes.reagendar(ctx) }
            runCatching { WidgetUtil.atualizarTodos(ctx) }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
