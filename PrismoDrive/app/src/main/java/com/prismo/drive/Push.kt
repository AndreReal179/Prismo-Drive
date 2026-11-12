package com.prismo.drive

import android.app.Application
import android.content.Context
import android.provider.Settings
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest

/**
 * Notificações em tempo real (Firebase Cloud Messaging).
 * A configuração do Firebase (pública) vem do servidor e fica guardada no aparelho,
 * então o app não precisa de google-services.json e o push liga sozinho quando o Firebase é configurado.
 */
object Push {
    private const val PREFS = "prismo_push"
    private val TOPICOS = listOf("todos", "free", "pro", "usuario")
    @Volatile private var iniciado = false

    private fun sp(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Liga o Firebase com a configuração salva (Application.onCreate e quando chega configuração nova). */
    @Synchronized
    fun iniciar(ctx: Context): Boolean {
        if (iniciado) return true
        val cfg = runCatching { JSONObject(sp(ctx).getString("config", "") ?: "") }.getOrNull() ?: return false
        val appId = cfg.optString("appId")
        val chave = cfg.optString("apiKey")
        val projeto = cfg.optString("projectId")
        val remetente = cfg.optString("senderId")
        if (appId.isBlank() || chave.isBlank() || projeto.isBlank() || remetente.isBlank()) return false
        return try {
            if (FirebaseApp.getApps(ctx).isEmpty()) {
                val op = FirebaseOptions.Builder().setApplicationId(appId).setApiKey(chave).setProjectId(projeto).setGcmSenderId(remetente).build()
                FirebaseApp.initializeApp(ctx.applicationContext, op)
            }
            iniciado = true
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Recebe a configuração do Firebase do servidor; devolve o token (ou vazio) em window.__prismoPush. */
    fun configurar(a: MainActivity, json: String?) {
        val novo = json?.trim().orEmpty()
        if (novo.isEmpty()) return
        val antigo = sp(a).getString("config", "") ?: ""
        if (novo != antigo) sp(a).edit().putString("config", novo).apply()
        if (!iniciar(a)) return
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                val tk = if (t.isSuccessful) t.result ?: "" else ""
                if (tk.isNotEmpty()) sp(a).edit().putString("token", tk).apply()
                a.rodarJs("try{window.__prismoPush&&window.__prismoPush(" + JSONObject.quote(tk) + ");}catch(e){}")
            }
        }
    }

    fun token(ctx: Context): String = sp(ctx).getString("token", "") ?: ""

    fun salvarToken(ctx: Context, tk: String) { sp(ctx).edit().putString("token", tk).apply() }

    /** Inscreve o aparelho nos tópicos dos anúncios (todos + o plano atual) e sai dos outros. */
    fun topicos(ctx: Context, lista: String?) {
        if (!iniciar(ctx)) return
        val quero = (lista ?: "").split(',').map { it.trim() }.filter { it in TOPICOS }.toSet()
        if (sp(ctx).getString("topicos", "") == quero.sorted().joinToString(",")) return
        runCatching {
            val fm = FirebaseMessaging.getInstance()
            TOPICOS.forEach { if (it in quero) fm.subscribeToTopic(it) else fm.unsubscribeFromTopic(it) }
            sp(ctx).edit().putString("topicos", quero.sorted().joinToString(",")).apply()
        }
    }

    // ── notificação mostrada pelo push: o app não repete o mesmo aviso logo depois ──
    fun marcarVista(ctx: Context, nid: Int) {
        sp(ctx).edit().putLong("visto_$nid", System.currentTimeMillis()).apply()
    }

    fun vistaRecente(ctx: Context, nid: Int, janelaMs: Long): Boolean =
        System.currentTimeMillis() - sp(ctx).getLong("visto_$nid", 0L) < janelaMs

    /** Identificador anônimo do aparelho (só o resumo SHA-256), usado contra indicações falsas. */
    fun aparelho(ctx: Context): String = try {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        if (id.isBlank()) "" else MessageDigest.getInstance("SHA-256")
            .digest(("prismo-drive:" + id).toByteArray()).joinToString("") { "%02x".format(it) }
    } catch (_: Throwable) {
        ""
    }
}

/** Recebe o push e mostra com o mesmo canal, som e número dos lembretes do app. */
class PrismoMensagens : FirebaseMessagingService() {
    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        val nid = d["nid"]?.toIntOrNull() ?: 9210
        val titulo = d["titulo"] ?: msg.notification?.title ?: return
        val texto = d["texto"] ?: msg.notification?.body ?: ""
        Push.marcarVista(this, nid)
        Notificacoes.mostrar(this, nid, titulo, texto)
    }

    override fun onNewToken(token: String) {
        Push.salvarToken(this, token)
    }
}

/** Erros do Android: guarda o motivo quando o app fecha sozinho; o app envia ao suporte na próxima vez que abrir. */
object ErrosNativos {
    private fun pasta(ctx: Context) = File(ctx.filesDir, "erros").apply { mkdirs() }

    fun instalar(app: Application) {
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val o = JSONObject()
                    .put("mensagem", (e.javaClass.simpleName + ": " + (e.message ?: "")).take(500))
                    .put("pilha", sw.toString().take(4000))
                    .put("tela", "thread " + t.name)
                    .put("processo", processo(app))
                    .put("quando", System.currentTimeMillis())
                val f = pasta(app)
                val lista = f.listFiles()?.sortedBy { it.name } ?: emptyList()
                if (lista.size >= 5) lista.first().delete()
                File(f, "crash_" + System.currentTimeMillis() + ".json").writeText(o.toString())
            }
            anterior?.uncaughtException(t, e)
        }
    }

    /** Devolve os erros guardados e apaga os arquivos. */
    fun pendentes(ctx: Context): String {
        val arr = JSONArray()
        runCatching {
            pasta(ctx).listFiles()?.sortedBy { it.name }?.forEach { f ->
                runCatching { arr.put(JSONObject(f.readText())) }
                f.delete()
            }
        }
        return arr.toString()
    }

    fun processo(ctx: Context): String = try {
        if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
        else {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName ?: ""
        }
    } catch (_: Throwable) { "" }
}

class PrismoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { ErrosNativos.instalar(this) }
        // o Firebase só roda no processo principal (o assistente de rotas fica em ":rotas")
        if (!ErrosNativos.processo(this).contains(':')) runCatching { Push.iniciar(this) }
    }
}
