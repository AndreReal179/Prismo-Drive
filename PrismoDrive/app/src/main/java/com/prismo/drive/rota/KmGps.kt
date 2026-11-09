package com.prismo.drive.rota

import com.prismo.drive.R

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Km da rota pelo GPS: liga em "EM DESLOCAMENTO" e desliga em "Oba, mais uma rota concluída!".
 *
 * - Só lê o GPS durante a rota (fora dela fica desligado — não gasta bateria).
 * - Descarta leituras imprecisas (> 35 m), saltos impossíveis (> 180 km/h) e o "tremido" de quando
 *   o carro está parado, para não inflar o km.
 * - O km acumulado fica salvo em arquivo: se o Android reiniciar o serviço no meio da rota, continua de onde parou.
 *
 * Roda no processo ":rotas" (o mesmo do serviço de acessibilidade).
 */
object KmGps {
    private const val ARQ = "ifood_rota_gps.json"
    private const val PRECISAO_MAX = 35f          // metros
    private const val VEL_MAX = 50.0              // m/s ≈ 180 km/h
    private const val PASSO_MIN = 10.0            // metros entre pontos somados

    @Volatile var ativo = false
        private set
    private var lm: LocationManager? = null
    private var ouvinte: LocationListener? = null
    private var app: Context? = null

    private var km = 0.0
    private var pontos = 0
    private var ult: Location? = null
    private var rejeitados = 0
    private var ultNotif = -1.0

    // ───────────── permissão ─────────────

    fun temPermissao(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Localização "o tempo todo" (Android 10+). Sem ela o GPS pode parar quando a tela apaga. */
    fun temSempre(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 0 = sem permissão (usa km estimado da oferta) · 1 = só enquanto usa · 2 = o tempo todo */
    fun nivel(ctx: Context): Int = when {
        !temPermissao(ctx) -> 0
        !temSempre(ctx) -> 1
        else -> 2
    }

    // ───────────── ciclo ─────────────

    private fun arq(ctx: Context) = File((ctx.applicationContext ?: ctx).filesDir, ARQ)

    /** Começa (ou retoma) a medição. zerar = rota nova. */
    fun iniciar(ctx: Context, zerar: Boolean) {
        val a = ctx.applicationContext ?: ctx
        app = a
        if (zerar) {
            km = 0.0; pontos = 0; ult = null; rejeitados = 0; ultNotif = -1.0
            salvar()
        } else if (!ativo) carregar(a)
        if (ativo) return
        if (!temPermissao(a)) return
        val m = a.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) { runCatching { ponto(loc) } }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 0f, l, Looper.getMainLooper())
        } catch (_: Throwable) {
            return
        }
        lm = m; ouvinte = l; ativo = true
        LocalizacaoService.iniciar(a)
    }

    /** Para de medir. Devolve (km, nº de pontos). O arquivo fica até a rota ser fechada. */
    fun parar(ctx: Context): Pair<Double, Int> {
        val a = ctx.applicationContext ?: ctx
        runCatching { ouvinte?.let { lm?.removeUpdates(it) } }
        ouvinte = null; lm = null
        val estava = ativo
        ativo = false
        if (!estava) carregar(a)
        LocalizacaoService.parar(a)
        return Pair(km, pontos)
    }

    /** Rota fechada: apaga o que foi medido. */
    fun limpar(ctx: Context) {
        km = 0.0; pontos = 0; ult = null
        runCatching { arq(ctx).delete() }
    }

    fun kmAtual(): Double = km

    // ───────────── pontos ─────────────

    private fun ponto(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > PRECISAO_MAX) return
        val u = ult
        if (u == null) { ult = loc; pontos++; salvar(); return }
        val d = u.distanceTo(loc).toDouble()
        val dt = maxOf(1.0, (loc.time - u.time) / 1000.0)
        // parado: o GPS "anda" alguns metros sozinho
        if (loc.hasSpeed() && loc.speed < 0.8f && d < 25) return
        if (d < PASSO_MIN) return
        if (d / dt > VEL_MAX) {
            // salto impossível; se repetir 3 vezes, o ponto antigo é que estava errado
            if (++rejeitados >= 3) { ult = loc; rejeitados = 0 }
            return
        }
        rejeitados = 0
        km += d / 1000.0
        ult = loc
        pontos++
        salvar()
        if (km - ultNotif >= 0.1) { ultNotif = km; app?.let { LocalizacaoService.atualizar(it, km) } }
    }

    private fun salvar() {
        val a = app ?: return
        runCatching {
            val o = JSONObject().put("km", km).put("pontos", pontos)
            ult?.let { o.put("lat", it.latitude).put("lng", it.longitude).put("t", it.time).put("acc", it.accuracy.toDouble()) }
            arq(a).writeText(o.toString())
        }
    }

    private fun carregar(ctx: Context) {
        runCatching {
            val f = arq(ctx)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            km = o.optDouble("km", 0.0)
            pontos = o.optInt("pontos", 0)
            ult = if (o.has("lat")) Location("salvo").apply {
                latitude = o.optDouble("lat"); longitude = o.optDouble("lng")
                time = o.optLong("t"); accuracy = o.optDouble("acc", 10.0).toFloat()
            } else null
        }
    }

    fun fmtKm(v: Double) = String.format(Locale("pt", "BR"), "%.1f km", v)
}

/**
 * Serviço em primeiro plano só durante a rota: mantém o GPS funcionando com a tela apagada e mostra
 * "Medindo km da rota · 3,4 km". Se o Android não deixar abrir (sem localização "o tempo todo"),
 * a medição segue pelo serviço de acessibilidade enquanto a tela estiver ligada.
 */
class LocalizacaoService : Service() {

    companion object {
        private const val CANAL = "prismo_rota_km"
        private const val ID = 7702

        fun iniciar(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            if (!KmGps.temSempre(ctx)) return     // sem "o tempo todo" o Android recusa em segundo plano
            runCatching { ctx.startForegroundService(Intent(ctx, LocalizacaoService::class.java)) }
        }

        fun parar(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, LocalizacaoService::class.java)) }
            runCatching { (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) }
        }

        fun atualizar(ctx: Context, km: Double) {
            if (!KmGps.ativo || Build.VERSION.SDK_INT < 26) return
            runCatching {
                (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notificacao(ctx, km))
            }
        }

        private fun notificacao(ctx: Context, km: Double): Notification {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CANAL, "Km da rota (GPS)", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aparece só durante a rota, enquanto o GPS mede os km"
                setShowBadge(false); enableVibration(false); setSound(null, null)
            })
            val abrir = PendingIntent.getActivity(
                ctx, 3,
                Intent(ctx, RotasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return Notification.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_stat_rota)
                .setContentTitle("Medindo km da rota")
                .setContentText("📍 " + KmGps.fmtKm(km) + " pelo GPS")
                .setColor(0xFF2EA44F.toInt())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(abrir)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = runCatching {
            val n = notificacao(this, KmGps.kmAtual())
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(ID, n)
        }.isSuccess
        if (!ok || !KmGps.ativo) { stopSelf(); return START_NOT_STICKY }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }
}
