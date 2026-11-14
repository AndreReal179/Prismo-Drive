package com.prismo.drive.rota

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Trajeto da rota (v2.1 · Pro): guarda os MESMOS pontos de GPS que já são usados para medir os km
 * (não liga o GPS a mais, então não gasta mais bateria). Fica só no celular, em filesDir/trajetos.
 *
 * - Rota do iFood: grava em "rota_atual" e, quando a rota é concluída, vira o id da rota.
 * - Turno: grava em "turno_<início em base 36>" (o mesmo id da Linha do tempo).
 * Cada linha do arquivo: lat,lng,hora(ms).
 */
object Trajeto {
    const val ATUAL = "rota_atual"
    private const val PASTA = "trajetos"
    private const val DIAS = 120L                 // mesmo prazo da Linha do tempo
    private const val MAX_BYTES = 3_000_000L      // ~80 mil pontos: nunca enche o celular
    private const val PARADA_MS = 120_000L        // 2 min sem andar = parada
    private val NOME = Regex("^[a-z0-9_]{1,48}$")

    private fun dir(ctx: Context) = File((ctx.applicationContext ?: ctx).filesDir, PASTA).apply { mkdirs() }
    private fun arq(ctx: Context, id: String): File? = if (NOME.matches(id)) File(dir(ctx), "$id.trk") else null

    @Synchronized
    fun add(ctx: Context, id: String, loc: Location) {
        runCatching {
            val f = arq(ctx, id) ?: return
            if (f.exists() && f.length() > MAX_BYTES) return
            f.appendText(String.format(Locale.US, "%.6f,%.6f,%d\n", loc.latitude, loc.longitude, loc.time))
        }
    }

    fun limpar(ctx: Context, id: String) { runCatching { arq(ctx, id)?.delete() } }

    /** Rota concluída: o trajeto em andamento passa a ter o id da rota (com menos de 2 pontos, é descartado). */
    @Synchronized
    fun fechar(ctx: Context, de: String, para: String) {
        runCatching {
            val a = arq(ctx, de) ?: return
            val b = arq(ctx, para) ?: return
            if (!a.exists()) return
            if (a.readLines().count { it.isNotBlank() } < 2) { a.delete(); return }
            b.delete()
            if (!a.renameTo(b)) { b.writeText(a.readText()); a.delete() }
        }
        limparAntigos(ctx)
    }

    fun existe(ctx: Context, id: String) = arq(ctx, id)?.exists() == true

    fun apagar(ctx: Context, id: String): Boolean = runCatching { arq(ctx, id)?.delete() == true }.getOrDefault(false)

    /** Dos ids pedidos, quais têm trajeto gravado. */
    fun quais(ctx: Context, idsJson: String?): String = runCatching {
        val a = JSONArray(idsJson ?: "[]")
        val r = JSONArray()
        for (i in 0 until a.length()) { val id = a.optString(i); if (existe(ctx, id)) r.put(id) }
        r.toString()
    }.getOrDefault("[]")

    fun limparAntigos(ctx: Context) {
        runCatching {
            val lim = System.currentTimeMillis() - DIAS * 86_400_000L
            dir(ctx).listFiles()?.forEach { if (it.name != "$ATUAL.trk" && it.lastModified() < lim) it.delete() }
        }
    }

    /**
     * Lê o trajeto para o mapa: pontos simplificados (o desenho fica igual, com bem menos pontos),
     * paradas (2 min ou mais sem andar), início/fim e km pelo traçado.
     */
    fun ler(ctx: Context, id: String): String = runCatching {
        val f = arq(ctx, id) ?: return@runCatching ""
        if (!f.exists()) return@runCatching ""
        val lat = ArrayList<Double>(); val lng = ArrayList<Double>(); val t = ArrayList<Long>()
        f.forEachLine { l ->
            val p = l.split(',')
            if (p.size >= 3) {
                val a = p[0].toDoubleOrNull(); val b = p[1].toDoubleOrNull(); val c = p[2].toLongOrNull()
                if (a != null && b != null && c != null && a in -90.0..90.0 && b in -180.0..180.0) { lat.add(a); lng.add(b); t.add(c) }
            }
        }
        val n = lat.size
        if (n < 2) return@runCatching ""
        // paradas: intervalo grande entre dois pontos seguidos (o GPS não grava enquanto o carro está parado)
        val paradas = JSONArray()
        for (i in 1 until n) {
            val gap = t[i] - t[i - 1]
            if (gap >= PARADA_MS) paradas.put(JSONArray().put(lat[i - 1]).put(lng[i - 1]).put(t[i - 1]).put(t[i]))
        }
        var m = 0.0
        val r = FloatArray(1)
        for (i in 1 until n) { Location.distanceBetween(lat[i - 1], lng[i - 1], lat[i], lng[i], r); m += r[0] }
        val manter = simplificar(lat, lng, if (n > 4000) 12.0 else 5.0)
        val pts = JSONArray()
        for (i in 0 until n) if (manter[i]) pts.put(JSONArray().put(round6(lat[i])).put(round6(lng[i])).put(t[i]))
        JSONObject().put("id", id).put("pts", pts).put("paradas", paradas)
            .put("ini", t.first()).put("fim", t.last()).put("km", Math.round(m / 10.0) / 100.0).put("brutos", n).toString()
    }.getOrDefault("")

    private fun round6(v: Double) = Math.round(v * 1e6) / 1e6

    /** Douglas-Peucker (sem recursão) numa projeção plana local, tolerância em metros. */
    private fun simplificar(lat: List<Double>, lng: List<Double>, tol: Double): BooleanArray {
        val n = lat.size
        val manter = BooleanArray(n)
        if (n <= 2) { manter.fill(true); return manter }
        val lat0 = Math.toRadians(lat[0])
        val kx = 111_320.0 * Math.cos(lat0); val ky = 110_540.0
        val x = DoubleArray(n) { lng[it] * kx }; val y = DoubleArray(n) { lat[it] * ky }
        manter[0] = true; manter[n - 1] = true
        val pilha = ArrayDeque<IntArray>()
        pilha.add(intArrayOf(0, n - 1))
        while (pilha.isNotEmpty()) {
            val (a, b) = pilha.removeLast().let { it[0] to it[1] }
            var maxD = 0.0; var idx = -1
            val dx = x[b] - x[a]; val dy = y[b] - y[a]; val len2 = dx * dx + dy * dy
            for (i in a + 1 until b) {
                val d = if (len2 == 0.0) Math.hypot(x[i] - x[a], y[i] - y[a]) else {
                    val u = (((x[i] - x[a]) * dx + (y[i] - y[a]) * dy) / len2).coerceIn(0.0, 1.0)
                    Math.hypot(x[i] - (x[a] + u * dx), y[i] - (y[a] + u * dy))
                }
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > tol) { manter[idx] = true; pilha.add(intArrayOf(a, idx)); pilha.add(intArrayOf(idx, b)) }
        }
        return manter
    }
}
