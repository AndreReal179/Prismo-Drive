package com.prismo.drive

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

/**
 * Ponte JavaScript ↔ Android, exposta na página como window.PrismoNative.
 * Os nomes dos métodos são os mesmos da versão 1.6 — a página não precisa mudar.
 */
class PrismoBridge(private val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())

    /** Agenda o plano completo de lembretes (registros separados por \u0002, campos por \u0001). */
    @JavascriptInterface
    fun agendar(plano: String?) {
        try {
            Notificacoes.agendar(activity, plano ?: "")
        } catch (_: Throwable) {
        }
    }

    /** Mostra uma notificação imediatamente. */
    @JavascriptInterface
    fun notificar(id: Int, titulo: String?, texto: String?) {
        try {
            Notificacoes.mostrar(activity, id, titulo ?: "", texto ?: "")
        } catch (_: Throwable) {
        }
    }

    @JavascriptInterface
    fun temPermissao(): Boolean = try {
        val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.areNotificationsEnabled()
    } catch (_: Throwable) {
        true
    }

    @JavascriptInterface
    fun pedirPermissao() {
        main.post { pedirPermissaoNotificacao(activity) }
    }

    @JavascriptInterface
    fun abrirAjustes() {
        main.post { abrirAjustesNotificacao(activity) }
    }

    @JavascriptInterface
    fun versao(): String = try {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: ""
    } catch (_: Throwable) {
        ""
    }

    /** Recebe o resumo do dia (JSON) e atualiza os widgets da tela inicial. */
    @JavascriptInterface
    fun widget(json: String?) {
        try {
            if (json != null) WidgetUtil.salvar(activity, json)
        } catch (_: Throwable) {
        }
    }

    // ── Atualização automática (aba Atualizações) ──

    @JavascriptInterface
    fun verificarAtualizacao() {
        val a = activity as? MainActivity ?: return
        main.post { a.verificarAtualizacao(automatico = false) }
    }

    @JavascriptInterface
    fun baixarAtualizacao() {
        val a = activity as? MainActivity ?: return
        main.post { a.baixarAtualizacao() }
    }

    @JavascriptInterface
    fun instalarAtualizacao() {
        val a = activity as? MainActivity ?: return
        main.post { a.instalarAtualizacao() }
    }

    /** Estado atual para a página desenhar a aba ao abrir. */
    @JavascriptInterface
    fun estadoAtualizacao(): String = try {
        JSONObject()
            .put("app", Atualizador.infoApp())
            .put("ultimo", Atualizador.ultimo ?: JSONObject.NULL)
            .put("verificando", Atualizador.verificando)
            .put("baixando", Atualizador.baixando)
            .put("pendente", Atualizador.apkPendente?.exists() == true)
            .toString()
    } catch (_: Throwable) {
        "{}"
    }

    // ── Assistente de rotas (avaliador de corridas iFood / InDrive) ──

    /** Abre a tela nativa do Assistente de rotas. */
    @JavascriptInterface
    fun abrirAssistenteRotas(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        main.post {
            try {
                activity.startActivity(Intent(activity, com.prismo.drive.rota.RotasActivity::class.java))
            } catch (_: Throwable) {
            }
        }
        return true
    }

    /** Liga ou desliga o serviço. Retorna o estado novo (JSON). */
    @JavascriptInterface
    fun rotasLigarDesligar(): String {
        if (Build.VERSION.SDK_INT < 26) return rotasEstado()
        main.post {
            try {
                if (com.prismo.drive.rota.RouteService.running) com.prismo.drive.rota.Power.turnOff(activity)
                else com.prismo.drive.rota.Power.turnOn(activity)
            } catch (_: Throwable) {
            }
        }
        return rotasEstado()
    }

    /** Situação do assistente + resumo de hoje, para a aba do menu. */
    @JavascriptInterface
    fun rotasEstado(): String = try {
        val o = JSONObject().put("suportado", Build.VERSION.SDK_INT >= 26)
        if (Build.VERSION.SDK_INT >= 26) {
            val ligado = com.prismo.drive.rota.RouteService.running
            val ativado = com.prismo.drive.rota.Power.isEnabled(activity)
            o.put("ligado", ligado).put("ativado", ativado).put("travado", ativado && !ligado)
            val ini = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val hoje = com.prismo.drive.rota.ReportStore.load(activity).filter { it.t >= ini }
            val rpks = hoje.mapNotNull { it.rpk }
            o.put("hoje", hoje.size)
                .put("ifood", hoje.count { it.route })
                .put("indrive", hoje.count { !it.route })
                .put("rpkMedio", if (rpks.isEmpty()) JSONObject.NULL else rpks.average())
                .put("melhor", hoje.maxOfOrNull { it.price } ?: JSONObject.NULL)
        }
        o.toString()
    } catch (_: Throwable) {
        "{\"suportado\":true,\"ligado\":false}"
    }

    /** Compartilha a imagem do fechamento (PNG em base64) pelo WhatsApp, etc. */
    @JavascriptInterface
    fun compartilharImagem(base64: String?, nome: String?): Boolean {
        return try {
            if (base64.isNullOrEmpty()) return false
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.isEmpty()) return false

            val pasta = File(activity.cacheDir, "compartilhar").apply { mkdirs() }
            pasta.listFiles()?.forEach { it.delete() }
            var arquivo = (nome ?: "fechamento-prismo-drive")
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .trim('-')
                .ifEmpty { "fechamento-prismo-drive" }
            if (!arquivo.endsWith(".png", ignoreCase = true)) arquivo += ".png"
            val f = File(pasta, arquivo)
            f.writeBytes(bytes)

            val uri: Uri = FileProvider.getUriForFile(
                activity, activity.packageName + ".arquivos", f
            )
            val envio = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(envio, "Compartilhar fechamento").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            main.post {
                try {
                    activity.startActivity(chooser)
                } catch (_: Throwable) {
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        const val REQ_NOTIF = 7

        fun pedirPermissaoNotificacao(activity: Activity) {
            try {
                if (Build.VERSION.SDK_INT >= 33 &&
                    activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                }
            } catch (_: Throwable) {
            }
        }

        fun abrirAjustesNotificacao(activity: Activity) {
            try {
                val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                activity.startActivity(i)
            } catch (_: Throwable) {
                try {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", activity.packageName, null)
                        )
                    )
                } catch (_: Throwable) {
                }
            }
        }
    }
}
