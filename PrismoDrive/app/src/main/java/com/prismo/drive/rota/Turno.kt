package com.prismo.drive.rota

import com.prismo.drive.R

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Turno: o motorista toca em "Iniciar turno" e o GPS mede os km e o tempo até "Finalizar turno".
 *
 * - Funciona sem o Assistente de rotas (acessibilidade): só precisa da localização.
 * - Com o assistente ligado, o botão flutuante vira um contador de km fora do iFood/InDrive.
 * - O estado fica em arquivo (turno.json): a tela do app (outro processo) e o botão flutuante leem dali,
 *   e se o Android reiniciar o serviço no meio do turno, continua de onde parou.
 */
object Turno {
    private const val ARQ = "turno.json"
    private const val ARQ_FIM = "turno_fim"
    private const val PRECISAO_MAX = 35f
    private const val VEL_MAX = 50.0
    private const val PASSO_MIN = 10.0

    /** Ação que abre o app já na tela de finalizar o turno (botão flutuante e notificação). */
    const val EXTRA_ACAO = "prismo_acao"
    const val ACAO_FINALIZAR = "turno_finalizar"

    class Estado(val ativo: Boolean, val ini: Long, val km: Double, val pontos: Int)

    private fun arq(ctx: Context) = File((ctx.applicationContext ?: ctx).filesDir, ARQ)
    private fun arqFim(ctx: Context) = File((ctx.applicationContext ?: ctx).filesDir, ARQ_FIM)

    // cache p/ o botão flutuante (lê a cada batida do serviço)
    private var cacheT = 0L
    private var cache: Estado? = null

    fun estado(ctx: Context): Estado {
        val f = arq(ctx)
        val o = runCatching { if (f.exists()) JSONObject(f.readText()) else null }.getOrNull()
        val e = if (o == null || !o.optBoolean("ativo")) Estado(false, 0, 0.0, 0)
        else Estado(true, o.optLong("ini"), o.optDouble("km", 0.0), o.optInt("pontos"))
        cache = e; cacheT = System.currentTimeMillis()
        return e
    }

    /** Estado com cache de 2 s (o botão flutuante pergunta a cada ~0,9 s). */
    fun estadoRapido(ctx: Context): Estado {
        val c = cache
        if (c != null && System.currentTimeMillis() - cacheT < 2000) return c
        return estado(ctx)
    }

    fun ativo(ctx: Context) = estadoRapido(ctx).ativo

    fun json(ctx: Context): String {
        val e = estado(ctx)
        return JSONObject().put("ativo", e.ativo).put("ini", e.ini).put("km", Math.round(e.km * 100) / 100.0)
            .put("pontos", e.pontos).put("gps", KmGps.temPermissao(ctx))
            .put("dur", if (e.ativo) System.currentTimeMillis() - e.ini else 0L).toString()
    }

    /** Começa um turno novo (o anterior, se havia, é descartado). Precisa da permissão de localização. */
    fun iniciar(ctx: Context): Boolean {
        if (!KmGps.temPermissao(ctx)) return false
        runCatching { arqFim(ctx).delete() }
        val o = JSONObject().put("ativo", true).put("ini", System.currentTimeMillis()).put("km", 0.0).put("pontos", 0)
        runCatching { arq(ctx).writeText(o.toString()) }
        cache = null
        TurnoService.iniciar(ctx)
        return true
    }

    /** Encerra: para o GPS e devolve o que foi medido ({ini, fim, km, pontos, dur}). */
    fun finalizar(ctx: Context): String {
        runCatching { arqFim(ctx).writeText("1") }     // o serviço para de gravar pontos
        val e = estado(ctx)
        TurnoService.parar(ctx)
        val fim = System.currentTimeMillis()
        runCatching { arq(ctx).delete() }
        cache = null
        if (!e.ativo) return JSONObject().put("ativo", false).toString()
        return JSONObject().put("ativo", true).put("ini", e.ini).put("fim", fim)
            .put("km", Math.round(e.km * 100) / 100.0).put("pontos", e.pontos)
            .put("dur", (fim - e.ini).coerceAtLeast(0)).toString()
    }

    fun encerrado(ctx: Context) = arqFim(ctx).exists()

    /** Grava o km acumulado (chamado pelo serviço a cada ponto válido). */
    internal fun gravar(ctx: Context, ini: Long, km: Double, pontos: Int, ult: Location?) {
        if (encerrado(ctx)) return
        runCatching {
            val o = JSONObject().put("ativo", true).put("ini", ini).put("km", km).put("pontos", pontos)
            ult?.let { o.put("lat", it.latitude).put("lng", it.longitude).put("t", it.time) }
            val f = arq(ctx)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) f.writeText(o.toString())
        }
        cache = null
    }

    internal fun lerBruto(ctx: Context): JSONObject? =
        runCatching { arq(ctx).takeIf { it.exists() }?.let { JSONObject(it.readText()) } }.getOrNull()

    /** Mesmo filtro do km da rota: descarta leitura imprecisa, salto impossível e o "tremido" parado. */
    internal fun valido(u: Location?, loc: Location): Double? {
        if (loc.hasAccuracy() && loc.accuracy > PRECISAO_MAX) return null
        if (u == null) return 0.0
        val d = u.distanceTo(loc).toDouble()
        val dt = maxOf(1.0, (loc.time - u.time) / 1000.0)
        if (loc.hasSpeed() && loc.speed < 0.8f && d < 25) return null
        if (d < PASSO_MIN) return null
        if (d / dt > VEL_MAX) return -1.0
        return d
    }

    fun fmtKm(v: Double) = String.format(Locale("pt", "BR"), "%.1f km", v)
}

/**
 * Serviço em primeiro plano do turno: mantém o GPS ligado com a tela apagada e mostra
 * "Turno em andamento · 12,4 km" com o cronômetro e o botão "Finalizar turno".
 */
class TurnoService : Service() {

    companion object {
        private const val CANAL = "prismo_turno"
        private const val ID = 7703

        fun iniciar(ctx: Context) {
            val i = Intent(ctx, TurnoService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            }
        }

        fun parar(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, TurnoService::class.java)) }
            runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) }
        }
    }

    private var lm: LocationManager? = null
    private var ouvinte: LocationListener? = null
    private var ini = 0L
    private var km = 0.0
    private var pontos = 0
    private var ult: Location? = null
    private var rejeitados = 0
    private var ultNotif = -1.0
    private val handler = Handler(Looper.getMainLooper())

    // ── turno esquecido aberto ──
    private val TURNO_LONGO = 10 * 3600_000L        // aberto há mais de 10 h
    private val PARADO = 2 * 3600_000L              // sem sair do lugar há 2 h
    private var avisouLongo = false
    private var avisouParado = false
    private val verificar = object : Runnable {
        override fun run() {
            runCatching { checarEsquecido() }
            handler.postDelayed(this, 10 * 60_000L)
        }
    }

    private fun hm(ms: Long): String { val m = (ms / 60000).coerceAtLeast(0); return "${m / 60}h" + String.format(Locale.US, "%02d", m % 60) }

    private fun checarEsquecido() {
        if (Turno.encerrado(this) || ini <= 0) return
        val agora = System.currentTimeMillis()
        val dur = agora - ini
        val ultMov = ult?.time?.takeIf { it > 0 } ?: ini
        val finalizar = Intent(this, com.prismo.drive.MainActivity::class.java)
            .putExtra(Turno.EXTRA_ACAO, Turno.ACAO_FINALIZAR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (!avisouLongo && dur >= TURNO_LONGO) {
            avisouLongo = true
            com.prismo.drive.Notificacoes.mostrar(this, 9200, "Seu turno ainda está aberto (" + hm(dur) + ")",
                "Já terminou? Finalize para salvar o km e o valor certos. " + Turno.fmtKm(km) + " até agora.", finalizar, "Finalizar turno")
        } else if (!avisouParado && dur >= PARADO && agora - ultMov >= PARADO) {
            avisouParado = true
            com.prismo.drive.Notificacoes.mostrar(this, 9201, "Turno parado há " + hm(agora - ultMov),
                "Você não se moveu nas últimas horas. Esqueceu o turno aberto? " + Turno.fmtKm(km) + " até agora.", finalizar, "Finalizar turno")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val o = Turno.lerBruto(this)
        if (o == null || !o.optBoolean("ativo") || Turno.encerrado(this)) { pararTudo(); return START_NOT_STICKY }
        ini = o.optLong("ini")
        km = o.optDouble("km", 0.0)
        pontos = o.optInt("pontos")
        ult = if (o.has("lat")) Location("salvo").apply {
            latitude = o.optDouble("lat"); longitude = o.optDouble("lng"); time = o.optLong("t")
        } else null
        val ok = runCatching {
            val n = notificacao()
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID, n)
        }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        ligarGps()
        handler.removeCallbacks(verificar)
        handler.postDelayed(verificar, 60_000L)
        return START_STICKY       // se o Android derrubar, volta e continua o turno
    }

    private fun ligarGps() {
        if (ouvinte != null) return
        if (!KmGps.temPermissao(this)) return
        val m = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) { runCatching { ponto(loc) } }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, l, Looper.getMainLooper())
        } catch (_: Throwable) { return }
        lm = m; ouvinte = l
    }

    private fun ponto(loc: Location) {
        if (Turno.encerrado(this)) { pararTudo(); return }
        val d = Turno.valido(ult, loc) ?: return
        if (d < 0) {                       // salto impossível; 3 seguidos = o ponto antigo é que estava errado
            if (++rejeitados >= 3) { ult = loc; rejeitados = 0 }
            return
        }
        rejeitados = 0
        km += d / 1000.0
        ult = loc
        avisouParado = false     // voltou a andar: pode avisar de novo se parar outra vez
        pontos++
        Turno.gravar(this, ini, km, pontos, ult)
        if (km - ultNotif >= 0.1) {
            ultNotif = km
            runCatching { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notificacao()) }
        }
    }

    private fun notificacao(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CANAL, "Turno (km pelo GPS)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aparece só enquanto o turno está em andamento"
                setShowBadge(false); enableVibration(false); setSound(null, null)
            })
        val abrir = PendingIntent.getActivity(
            this, 11,
            Intent(this, com.prismo.drive.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val finalizar = PendingIntent.getActivity(
            this, 12,
            Intent(this, com.prismo.drive.MainActivity::class.java)
                .putExtra(Turno.EXTRA_ACAO, Turno.ACAO_FINALIZAR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CANAL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat_rota)
            .setContentTitle("Turno em andamento · " + Turno.fmtKm(km))
            .setContentText("📍 Km e tempo medidos pelo GPS")
            .setColor(0xFF2EA44F.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(ini)
            .setUsesChronometer(true)
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "Finalizar turno", finalizar).build())
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun pararTudo() {
        runCatching { ouvinte?.let { lm?.removeUpdates(it) } }
        ouvinte = null; lm = null
        runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { ouvinte?.let { lm?.removeUpdates(it) } }
        ouvinte = null; lm = null
        handler.removeCallbacksAndMessages(null)
        runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }
}
