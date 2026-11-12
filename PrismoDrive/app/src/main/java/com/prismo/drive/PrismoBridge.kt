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

    /** ID único da conta (8 dígitos), gerado uma vez e guardado no aparelho. */
    @JavascriptInterface
    fun contaId(): String = try {
        val sp = activity.getSharedPreferences("prismo_conta", Context.MODE_PRIVATE)
        sp.getString("id", null)?.takeIf { it.length == 8 && it.all(Char::isDigit) } ?: run {
            val novo = (10_000_000 + java.security.SecureRandom().nextInt(90_000_000)).toString()
            sp.edit().putString("id", novo).apply()
            novo
        }
    } catch (_: Throwable) {
        ""
    }

    // ───────────── Conta (Supabase) ─────────────

    /** HTTP para o Supabase feito pelo Android; a resposta chega em window.__prismoHttp. */
    @JavascriptInterface
    fun http(id: String?, metodo: String?, url: String?, cabecalhos: String?, corpo: String?) {
        val a = activity as? MainActivity ?: return
        if (id.isNullOrEmpty() || metodo.isNullOrEmpty() || url.isNullOrEmpty()) return
        ContaNativa.http(a, id, metodo, url, cabecalhos, corpo)
    }

    /** Abre o "Entrar com Google" nativo; o resultado chega no evento 'prismo-google'. */
    @JavascriptInterface
    fun googleEntrar() {
        val a = activity as? MainActivity ?: return
        main.post { ContaNativa.entrarGoogle(a) }
    }

    /** A página informa se há conta conectada (o assistente e os widgets usam). */
    @JavascriptInterface
    fun contaEstado(json: String?) {
        Conta.salvar(activity, json)
    }

    // ── Dados da conta (assistente, turno, iFood, widgets) ──

    @JavascriptInterface
    fun dadosAssinatura(): String = try { DadosConta.assinatura(activity) } catch (_: Throwable) { "" }

    @JavascriptInterface
    fun dadosExportar(): String = try { DadosConta.exportar(activity) } catch (_: Throwable) { "{}" }

    @JavascriptInterface
    fun dadosImportar(json: String?): Boolean = try { DadosConta.importar(activity, json) } catch (_: Throwable) { false }

    @JavascriptInterface
    fun dadosLimpar() {
        try { DadosConta.limpar(activity) } catch (_: Throwable) {}
    }

    @JavascriptInterface
    fun copiaSalvar(json: String?): Boolean = try { DadosConta.salvarCopia(activity, json) } catch (_: Throwable) { false }

    @JavascriptInterface
    fun copiaListar(): String = try { DadosConta.listarCopias(activity) } catch (_: Throwable) { "[]" }

    @JavascriptInterface
    fun copiaLer(id: String?): String = try { DadosConta.lerCopia(activity, id) } catch (_: Throwable) { "" }

    // ── Relógio do Pro: hora do servidor + relógio interno (ignora a data/hora do celular) ──

    @JavascriptInterface
    fun relogioSincronizar(servidorMs: String?) {
        try { Relogio.sincronizar(activity, servidorMs?.toLongOrNull() ?: 0L) } catch (_: Throwable) {}
    }

    /** {"agora":ms|-1,"servidor":ms,"confirmar":bool} */
    @JavascriptInterface
    fun relogioEstado(): String = try {
        JSONObject().put("agora", Relogio.agora(activity)).put("servidor", Relogio.ultimoServidor(activity))
            .put("confirmar", Relogio.precisaConfirmar(activity)).toString()
    } catch (_: Throwable) { "{}" }

    // ── Segurança: bloqueio do app por biometria ou PIN ──

    @JavascriptInterface
    fun segurancaEstado(): String = try { Seguranca.estado(activity) } catch (_: Throwable) { "{}" }

    @JavascriptInterface
    fun pinDefinir(pin: String?): Boolean = try { Seguranca.definirPin(activity, pin) } catch (_: Throwable) { false }

    @JavascriptInterface
    fun pinConferir(pin: String?): String = try { Seguranca.conferirPin(activity, pin) } catch (_: Throwable) { "erro:0" }

    @JavascriptInterface
    fun bioUsar(): Boolean = try { Seguranca.usarBiometria(activity) } catch (_: Throwable) { false }

    /** Abre a biometria do Android; resultado no evento 'prismo-bio'. */
    @JavascriptInterface
    fun bioAutenticar(titulo: String?) {
        val a = activity as? MainActivity ?: return
        main.post { Seguranca.autenticar(a, titulo ?: "Desbloquear") }
    }

    @JavascriptInterface
    fun segurancaLimpar() {
        try { Seguranca.limpar(activity) } catch (_: Throwable) {}
    }

    /** Link do e-mail de confirmação / nova senha que abriu o app (lê uma vez). */
    @JavascriptInterface
    fun contaLink(): String = (activity as? MainActivity)?.consumirLinkConta() ?: ""

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
                if (com.prismo.drive.rota.RotasStatus.ligado(activity)) com.prismo.drive.rota.Power.turnOff(activity)
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
            val ligado = com.prismo.drive.rota.RotasStatus.ligado(activity)
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

    // ── Recebimentos do iFood lidos pelo assistente (fila entre processos) ──

    /** Meta de quitação da Carteira (lembrete das 20h em dia trabalhado). Vazio = sem meta. */
    @JavascriptInterface
    fun lembreteQuitacao(json: String?) {
        try { Lembretes.salvarQuitacao(activity, json) } catch (_: Throwable) {}
    }

    // ───────────── Turno (km e tempo pelo GPS) ─────────────

    @JavascriptInterface
    fun turnoEstado(): String = try {
        com.prismo.drive.rota.Turno.json(activity)
    } catch (_: Throwable) {
        "{}"
    }

    /** "ok" = começou · "permissao" = pediu a localização (a página recebe o evento prismo-turno) · "erro" */
    @JavascriptInterface
    fun turnoIniciar(): String = try {
        if (com.prismo.drive.rota.KmGps.temPermissao(activity)) {
            if (com.prismo.drive.rota.Turno.iniciar(activity)) "ok" else "erro"
        } else {
            main.post {
                runCatching {
                    activity.requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        REQ_LOC_TURNO
                    )
                }
            }
            "permissao"
        }
    } catch (_: Throwable) {
        "erro"
    }

    @JavascriptInterface
    fun turnoFinalizar(): String = try {
        com.prismo.drive.rota.Turno.finalizar(activity)
    } catch (_: Throwable) {
        "{}"
    }

    /** Ação pendente vinda da notificação ou do botão flutuante (ex.: "turno_finalizar"); lê uma vez. */
    @JavascriptInterface
    fun turnoAcao(): String = (activity as? MainActivity)?.consumirAcao() ?: ""

    @JavascriptInterface
    fun ifoodPendentes(): String = com.prismo.drive.rota.IfoodSaldo.pendentes(activity)

    @JavascriptInterface
    fun ifoodConfirmar(ids: String?) {
        if (!ids.isNullOrEmpty()) com.prismo.drive.rota.IfoodSaldo.confirmar(activity, ids)
    }

    /** Totais por dia lançados no app (sem o iFood automático), para o Relatório do assistente. */
    @JavascriptInterface
    fun sincronizarGanhos(json: String?) {
        if (!json.isNullOrEmpty()) com.prismo.drive.rota.Ganhos.salvarManuais(activity, json)
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
        const val REQ_LOC_TURNO = 4202

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
    // ───────────── Push, aparelho e erros (v1.9.8) ─────────────

    /** Configuração pública do Firebase vinda do servidor; o token chega em window.__prismoPush. */
    @JavascriptInterface
    fun pushConfigurar(json: String?) {
        val a = activity as? MainActivity ?: return
        runCatching { Push.configurar(a, json) }
    }

    @JavascriptInterface
    fun pushToken(): String = try { Push.token(activity) } catch (_: Throwable) { "" }

    /** Tópicos dos anúncios: "todos,pro" etc. */
    @JavascriptInterface
    fun pushTopicos(lista: String?) {
        runCatching { Push.topicos(activity, lista) }
    }

    /** true se o push já mostrou este aviso há pouco (o app não repete). */
    @JavascriptInterface
    fun notificadoRecente(id: Int, janelaMs: Long): Boolean = try { Push.vistaRecente(activity, id, janelaMs) } catch (_: Throwable) { false }

    /** Resumo anônimo do aparelho (SHA-256). */
    @JavascriptInterface
    fun aparelhoId(): String = if (Integridade.oficial(activity)) Push.aparelho(activity) else ""

    /** true só no APK oficial (assinatura da Prismo Lab). */
    @JavascriptInterface
    fun oficial(): Boolean = Integridade.oficial(activity)

    /** Erros do Android guardados quando o app fechou sozinho (lista JSON; apaga depois de ler). */
    @JavascriptInterface
    fun errosNativos(): String = try { ErrosNativos.pendentes(activity) } catch (_: Throwable) { "[]" }
}
