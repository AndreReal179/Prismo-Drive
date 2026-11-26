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

    // ── v2.3 · assistente de voz "Ei Prismo!" ──
    private fun voz(): VozPrismo? = (activity as? MainActivity)?.voz
    @JavascriptInterface fun vozEstado(): String = voz()?.estado() ?: "{}"
    @JavascriptInterface fun vozAtivar(on: Boolean) { voz()?.ligar(on) }
    @JavascriptInterface fun vozBaixarModelo() { voz()?.baixarModelo() }
    @JavascriptInterface fun vozPedirMic() { voz()?.pedirMic() }
    @JavascriptInterface fun vozDormir(on: Boolean) { val v = voz() ?: return; main.post { v.dormir(on) } }
    @JavascriptInterface fun vozOuvir() { voz()?.ouvir() }
    @JavascriptInterface fun vozPararOuvir() { voz()?.pararOuvir() }
    @JavascriptInterface fun vozFalar(texto: String, id: String) { voz()?.falar(texto, id) }
    @JavascriptInterface fun vozCalar() { voz()?.calar() }

    // ── v2.3 · aviso de permissões na tela inicial (sem contar a acessibilidade) ──

    /** {notif, gps, gpsSempre, bolha, bateria}: true = ligada. */
    @JavascriptInterface
    fun permissoesApp(): String = try {
        val nm = activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pm = activity.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        org.json.JSONObject()
            .put("notif", nm.areNotificationsEnabled())
            .put("gps", com.prismo.drive.rota.KmGps.temPermissao(activity))
            .put("gpsSempre", com.prismo.drive.rota.KmGps.temPermissao(activity) && com.prismo.drive.rota.KmGps.temSempre(activity))
            .put("bolha", com.prismo.drive.rota.TurnoBolha.podeMostrar(activity))
            .put("bateria", android.os.Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(activity.packageName))
            .toString()
    } catch (_: Throwable) { "{}" }

    /** Leva para o lugar certo do Android para ligar cada uma. */
    @JavascriptInterface
    fun permissaoAbrir(qual: String) {
        main.post {
            runCatching {
                when (qual) {
                    "notif" -> pedirPermissaoNotificacao(activity)
                    "gps" -> activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC_PERM)
                    "gpsSempre" -> {
                        if (!com.prismo.drive.rota.KmGps.temPermissao(activity))
                            activity.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC_PERM)
                        else  // Android 11+: "Permitir o tempo todo" só pela tela de permissões do app
                            activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:" + activity.packageName)))
                    }
                    "bolha" -> turnoPedirBolha()
                    "bateria" -> activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }.onFailure {
                runCatching { activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + activity.packageName))) }
            }
        }
    }

    // ── v2.3 · permissões do turno e rota com taxa de entrega ──

    /** {gps, bolha}: o que falta para o turno (localização e "aparecer sobre outros apps"). */
    @JavascriptInterface
    fun turnoPermissoes(): String = try {
        org.json.JSONObject().put("gps", com.prismo.drive.rota.KmGps.temPermissao(activity))
            .put("bolha", com.prismo.drive.rota.TurnoBolha.podeMostrar(activity)).toString()
    } catch (_: Throwable) { "{\"gps\":false,\"bolha\":false}" }

    /** Pede só a localização (a página recebe o evento prismo-turno-perm). */
    @JavascriptInterface
    fun turnoPedirGps() {
        main.post {
            runCatching {
                activity.requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC_PERM)
            }
        }
    }

    /** Abre a tela do Android "Aparecer sobre outros apps" do Prismo Drive. */
    @JavascriptInterface
    fun turnoPedirBolha() {
        main.post {
            runCatching {
                activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + activity.packageName)))
            }.onFailure {
                runCatching { activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
            }
        }
    }

    /** Fecha a rota com taxa de entrega aberta (antes do resumo do turno). Devolve o valor lançado. */
    @JavascriptInterface
    fun turnoRotaFechar(): String = try {
        com.prismo.drive.rota.TurnoRota.finalizar(activity).toString()
    } catch (_: Throwable) { "0" }

    // ── Trajeto da rota (v2.1 · Pro) ──
    /** Dos ids da Linha do tempo, quais têm trajeto gravado. */
    @JavascriptInterface
    fun trajetos(ids: String?): String = com.prismo.drive.rota.Trajeto.quais(activity, ids)

    @JavascriptInterface
    fun trajeto(id: String?): String = if (id.isNullOrBlank()) "" else com.prismo.drive.rota.Trajeto.ler(activity, id)

    /** v2.1.8 · Compartilha um print da tela atual (Detalhes do trajeto). Responde em window.__prismoPrint(ok). */
    @JavascriptInterface
    fun compartilharTela(nome: String?, titulo: String?) {
        val act = activity as? MainActivity ?: return
        main.post {
            act.printTela(nome ?: "rota-prismo-drive") { f ->
                var ok = false
                if (f != null) runCatching {
                    val uri: Uri = FileProvider.getUriForFile(act, act.packageName + ".arquivos", f)
                    val envio = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    act.startActivity(Intent.createChooser(envio, titulo ?: "Compartilhar rota").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    ok = true
                }
                act.rodarJs("try{window.__prismoPrint&&window.__prismoPrint($ok);}catch(e){}")
            }
        }
    }

    /** v2.3 · Alerta de rota (sons de bom, normal e ruim) agora se ajusta no app: Configurações › Notificação. */
    @JavascriptInterface
    fun alertaLer(): String = runCatching {
        val p = com.prismo.drive.rota.Prefs(activity)
        val sons = org.json.JSONArray(); com.prismo.drive.rota.RouteAlert.SOUNDS.forEach { sons.put(it) }
        val vibes = org.json.JSONArray(); com.prismo.drive.rota.RouteAlert.VIBES.forEach { vibes.put(it) }
        JSONObject().put("on", p.alertOn).put("som", p.alertSound).put("vibe", p.alertVibe)
            .put("porNivel", p.alertPorNivel).put("maximo", p.alertMaximo).put("repetir", p.alertRepetir)
            .put("volBom", p.volBom).put("volMedio", p.volMedio).put("volRuim", p.volRuim)
            .put("sons", sons).put("vibes", vibes).toString()
    }.getOrDefault("{}")

    @JavascriptInterface
    fun alertaSalvar(chave: String, valor: String) {
        runCatching {
            val p = com.prismo.drive.rota.Prefs(activity)
            val b = valor == "true" || valor == "1"
            val n = valor.toDoubleOrNull()?.toInt() ?: 0
            when (chave) {
                "on" -> p.alertOn = b
                "porNivel" -> p.alertPorNivel = b
                "maximo" -> p.alertMaximo = b
                "repetir" -> p.alertRepetir = b
                "som" -> if (n in com.prismo.drive.rota.RouteAlert.SOUNDS.indices) p.alertSound = n
                "vibe" -> if (n in com.prismo.drive.rota.RouteAlert.VIBES.indices) p.alertVibe = n
                "volBom" -> p.volBom = n
                "volMedio" -> p.volMedio = n
                "volRuim" -> p.volRuim = n
            }
        }
    }

    /** Toca uma vez: tipo = "bom", "ruim", "medio" (som escolhido) ou "vibe". */
    @JavascriptInterface
    fun alertaTestar(tipo: String) {
        main.post {
            runCatching {
                val p = com.prismo.drive.rota.Prefs(activity)
                val A = com.prismo.drive.rota.RouteAlert
                when (tipo) {
                    "bom" -> A.play(activity, A.SOM_BOM, 0)
                    "ruim" -> A.play(activity, A.SOM_RUIM, 0)
                    "vibe" -> A.play(activity, 0, p.alertVibe)
                    else -> A.play(activity, p.alertSound, 0)
                }
            }
        }
    }

    /** v2.3 · Veículo: preço da gasolina e consumo usados pelo assistente de rotas (um lugar só: Menu › Veículo). */
    @JavascriptInterface
    fun veiculoLer(): String = runCatching {
        val p = com.prismo.drive.rota.Prefs(activity)
        JSONObject().put("preco", p.fuelPrice.toDouble()).put("consumo", p.kmPerLiter.toDouble()).toString()
    }.getOrDefault("{}")

    @JavascriptInterface
    fun veiculoSalvar(preco: Double, consumo: Double) {
        runCatching {
            val p = com.prismo.drive.rota.Prefs(activity)
            if (preco in 0.5..50.0) p.fuelPrice = preco.toFloat()
            if (consumo in 0.5..100.0) p.kmPerLiter = consumo.toFloat()
        }
    }

    /** v2.1.8 · Rota do iFood em andamento ({ativo, ini, km}). */
    @JavascriptInterface
    fun rotaAtual(): String = com.prismo.drive.rota.IfoodSaldo.atual(activity)

    /** v2.1.8 · Pede ao assistente para encerrar a rota em andamento (a tela de ganho do iFood não apareceu). */
    @JavascriptInterface
    fun rotaParar(): Boolean = com.prismo.drive.rota.LocalizacaoService.pedirParada(activity)

    /** v2.1.8 · Rota parada à mão esperando o valor ("" = nenhuma). */
    @JavascriptInterface
    fun rotaParada(): String = com.prismo.drive.rota.IfoodSaldo.parada(activity)

    @JavascriptInterface
    fun rotaParadaOk(id: String?) = com.prismo.drive.rota.IfoodSaldo.paradaOk(activity, id)

    @JavascriptInterface
    fun trajetoApagar(id: String?): Boolean = !id.isNullOrBlank() && com.prismo.drive.rota.Trajeto.apagar(activity, id)

    /** Endereços (rua, número, bairro) dos pontos do trajeto, pelo Geocoder do Android. Responde em window.__prismoEnd(cb, lista). */
    @JavascriptInterface
    fun enderecos(json: String?, cb: String?) {
        val act = activity as? MainActivity ?: return
        val pedido = runCatching { org.json.JSONArray(json ?: "[]") }.getOrNull() ?: return
        Thread {
            val r = org.json.JSONArray()
            val geo = runCatching { if (android.location.Geocoder.isPresent()) android.location.Geocoder(act, java.util.Locale("pt", "BR")) else null }.getOrNull()
            for (i in 0 until minOf(pedido.length(), 12)) {
                val p = pedido.optJSONArray(i)
                var txt = ""
                if (geo != null && p != null) runCatching {
                    @Suppress("DEPRECATION")
                    val a = geo.getFromLocation(p.optDouble(0), p.optDouble(1), 1)?.firstOrNull()
                    if (a != null) {
                        val rua = a.thoroughfare ?: a.featureName ?: ""
                        val num = a.subThoroughfare?.let { ", $it" } ?: ""
                        val bairro = a.subLocality ?: a.locality ?: ""
                        txt = listOf(rua + num, bairro).filter { it.isNotBlank() }.joinToString(" · ")
                    }
                }
                r.put(txt)
            }
            act.rodarJs("try{window.__prismoEnd&&window.__prismoEnd(" + JSONObject.quote(cb ?: "") + "," + r.toString() + ");}catch(e){}")
        }.start()
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

    /** Compartilha um texto (resumo da rota) pelo WhatsApp, etc. */
    @JavascriptInterface
    fun compartilharTexto(texto: String?): Boolean = try {
        if (texto.isNullOrBlank()) false else {
            val envio = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, texto.take(4000)) }
            activity.startActivity(Intent.createChooser(envio, "Compartilhar rota").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    } catch (_: Throwable) {
        false
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
        const val REQ_LOC_PERM = 4203

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
