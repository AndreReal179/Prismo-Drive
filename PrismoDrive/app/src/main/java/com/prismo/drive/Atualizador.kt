package com.prismo.drive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Atualização automática pelo GitHub Releases.
 *
 * O workflow publica cada build num Release com o APK anexado e o comentário
 * <!-- versionCode: N --> nas notas. O app consulta /releases/latest, compara o
 * versionCode com o instalado e, se houver versão nova, baixa e abre o instalador.
 */
object Atualizador {

    private const val PREFS = "prismo_update"

    /** Último resultado da verificação (JSON enviado para a página). */
    @Volatile var ultimo: JSONObject? = null
        private set

    @Volatile var verificando = false
        private set

    @Volatile var baixando = false
        private set

    /** APK baixado esperando o usuário liberar "instalar apps desconhecidos". */
    @Volatile var apkPendente: File? = null

    val repo: String get() = BuildConfig.UPDATE_REPO.trim()

    fun infoApp(): JSONObject = JSONObject()
        .put("versao", BuildConfig.VERSION_NAME)
        .put("codigo", BuildConfig.VERSION_CODE)
        .put("repo", repo)
        .put("configurado", repo.isNotEmpty())

    // ───────────────────────── Verificar ─────────────────────────

    fun verificar(ctx: Context, cb: (JSONObject) -> Unit) {
        if (verificando) return
        if (repo.isEmpty()) {
            cb(erro("Atualização automática não configurada neste APK."))
            return
        }
        verificando = true
        cb(JSONObject().put("tipo", "verificando"))
        val app = ctx.applicationContext
        thread(name = "prismo-verificar") {
            val r = try {
                consultarRelease()
            } catch (e: Throwable) {
                erro(mensagemErro(e))
            }
            verificando = false
            if (r.optString("tipo") == "resultado") {
                ultimo = r
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putLong("ultima_verificacao", System.currentTimeMillis()).apply()
            }
            cb(r)
        }
    }

    private fun consultarRelease(): JSONObject {
        val con = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PrismoDrive-Android")
        }
        try {
            val code = con.responseCode
            if (code == 404) return erro("Nenhuma versão publicada ainda.")
            if (code == 403) return erro("Muitas consultas seguidas. Tente de novo em alguns minutos.")
            if (code !in 200..299) return erro("Servidor de atualização respondeu $code.")
            val json = JSONObject(con.inputStream.bufferedReader().use { it.readText() })

            val corpo = json.optString("body", "")
            val tag = json.optString("tag_name", "")
            var url = ""
            var tamanho = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", true)) {
                        url = a.optString("browser_download_url")
                        tamanho = a.optLong("size", 0)
                        break
                    }
                }
            }
            val codigoRemoto = Regex("versionCode\\s*[:=]\\s*(\\d+)").find(corpo)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val nomeRemoto = Regex("versionName\\s*[:=]\\s*([0-9A-Za-z._-]+)").find(corpo)?.groupValues?.get(1)
                ?: tag.removePrefix("v").substringBefore("-b")
            val nova = url.isNotEmpty() && codigoRemoto > BuildConfig.VERSION_CODE

            val notas = corpo
                .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
                .lines().joinToString("\n") { it.trimEnd() }
                .trim()

            return JSONObject()
                .put("tipo", "resultado")
                .put("nova", nova)
                .put("atual", BuildConfig.VERSION_NAME)
                .put("atualCodigo", BuildConfig.VERSION_CODE)
                .put("versao", nomeRemoto)
                .put("codigo", codigoRemoto)
                .put("titulo", json.optString("name", tag))
                .put("notas", notas)
                .put("data", json.optString("published_at", ""))
                .put("url", url)
                .put("tamanho", tamanho)
        } finally {
            con.disconnect()
        }
    }

    // ───────────────────────── Baixar ─────────────────────────

    fun baixar(ctx: Context, cb: (JSONObject) -> Unit) {
        val info = ultimo
        val url = info?.optString("url").orEmpty()
        if (baixando) return
        if (info == null || !info.optBoolean("nova") || url.isEmpty()) {
            cb(erro("Nenhuma atualização para baixar. Toque em verificar."))
            return
        }
        baixando = true
        val app = ctx.applicationContext
        thread(name = "prismo-baixar") {
            try {
                val pasta = File(app.cacheDir, "atualizacao").apply { mkdirs() }
                pasta.listFiles()?.forEach { it.delete() }
                val destino = File(pasta, "PrismoDrive.apk")
                val tmp = File(pasta, "PrismoDrive.apk.part")

                val con = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "PrismoDrive-Android")
                }
                try {
                    if (con.responseCode !in 200..299) throw IllegalStateException("Download respondeu ${con.responseCode}")
                    val total = con.contentLengthLong.takeIf { it > 0 } ?: info.optLong("tamanho", 0)
                    var lido = 0L
                    var ultimoPct = -1
                    con.inputStream.use { ins ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                lido += n
                                val pct = if (total > 0) ((lido * 100) / total).toInt() else -1
                                if (pct != ultimoPct && (pct < 0 || pct - ultimoPct >= 2 || pct == 100)) {
                                    ultimoPct = pct
                                    cb(JSONObject().put("tipo", "progresso").put("pct", pct)
                                        .put("baixado", lido).put("total", total))
                                }
                            }
                        }
                    }
                } finally {
                    con.disconnect()
                }
                if (!tmp.renameTo(destino)) throw IllegalStateException("Não foi possível salvar o arquivo")

                // Confere se é mesmo o Prismo Drive e se é mais novo
                val pi = app.packageManager.getPackageArchiveInfo(destino.absolutePath, 0)
                    ?: throw IllegalStateException("Arquivo baixado está corrompido")
                if (pi.packageName != app.packageName) throw IllegalStateException("Arquivo não é do Prismo Drive")
                val codigoBaixado = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                if (codigoBaixado <= BuildConfig.VERSION_CODE) throw IllegalStateException("A versão baixada não é mais nova")

                baixando = false
                cb(JSONObject().put("tipo", "baixado").put("arquivo", destino.absolutePath))
            } catch (e: Throwable) {
                baixando = false
                cb(erro(mensagemErro(e)))
            }
        }
    }

    // ───────────────────────── Instalar ─────────────────────────

    /** Abre o instalador do Android. Se faltar permissão, abre a tela para liberar e retorna false. */
    fun instalar(activity: Activity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            apkPendente = apk
            try {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.packageName))
                )
            } catch (_: Throwable) {
            }
            return false
        }
        apkPendente = null
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".arquivos", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(i)
        return true
    }

    // ───────────────────────── Lembrete "Depois" ─────────────────────────

    /** Pode mostrar o aviso desta versão? (respeita o "Depois" por 24 h) */
    fun podeAvisar(ctx: Context, codigo: Long): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val adiado = p.getLong("adiado_codigo", 0)
        val quando = p.getLong("adiado_em", 0)
        return !(adiado == codigo && System.currentTimeMillis() - quando < 24L * 3600 * 1000)
    }

    fun adiar(ctx: Context, codigo: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("adiado_codigo", codigo)
            .putLong("adiado_em", System.currentTimeMillis())
            .apply()
    }

    private fun erro(msg: String) = JSONObject().put("tipo", "erro").put("msg", msg)

    private fun mensagemErro(e: Throwable): String = when (e) {
        is java.net.UnknownHostException -> "Sem internet. Conecte-se e tente de novo."
        is java.net.SocketTimeoutException -> "A conexão demorou demais. Tente de novo."
        is javax.net.ssl.SSLException -> "Falha de conexão segura. Tente de novo."
        else -> e.message ?: "Erro inesperado"
    }
}
