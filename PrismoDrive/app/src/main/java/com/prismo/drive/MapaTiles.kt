package com.prismo.drive

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Imagens do mapa (OpenStreetMap) para a tela "Detalhes" do trajeto.
 * Só passa por aqui o endereço exato dos azulejos do OSM; o resto da página segue normal.
 * Guarda cada imagem no cache do app (30 dias), então rever uma rota quase não gasta internet.
 */
object MapaTiles {
    private val CAMINHO = Regex("^/(\\d{1,2})/(\\d{1,7})/(\\d{1,7})\\.png$")
    private const val VALIDADE = 30L * 86_400_000L

    fun interceptar(ctx: Context, uri: Uri?): WebResourceResponse? {
        if (uri == null || uri.scheme != "https" || uri.host != "tile.openstreetmap.org") return null
        val m = CAMINHO.find(uri.path ?: "") ?: return null
        val (z, x, y) = m.destructured
        if ((z.toIntOrNull() ?: 99) > 19) return null
        return try {
            val dir = File(ctx.cacheDir, "mapa").apply { mkdirs() }
            val f = File(dir, "${z}_${x}_${y}.png")
            val bytes = if (f.exists() && System.currentTimeMillis() - f.lastModified() < VALIDADE) f.readBytes() else {
                val c = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 10000
                c.setRequestProperty("User-Agent", "PrismoDrive/" + BuildConfig.VERSION_NAME + " (Android; github.com/AndreReal179/Prismo-Drive)")
                c.setRequestProperty("Referer", "https://github.com/AndreReal179/Prismo-Drive")
                try {
                    if (c.responseCode != 200) return erro()
                    val b = c.inputStream.use { it.readBytes() }
                    runCatching { f.writeBytes(b) }
                    b
                } finally { c.disconnect() }
            }
            WebResourceResponse("image/png", null, 200, "OK", mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=2592000"), ByteArrayInputStream(bytes))
        } catch (_: Throwable) {
            erro()
        }
    }

    private fun erro() = WebResourceResponse("image/png", null, 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}
