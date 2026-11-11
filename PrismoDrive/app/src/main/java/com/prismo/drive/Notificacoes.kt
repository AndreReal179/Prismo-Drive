package com.prismo.drive

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Notificações e lembretes agendados (vencimentos, manutenção, metas…).
 * Mantém os mesmos IDs de canal e de preferências da v1.6, então os avisos
 * já agendados continuam valendo depois da atualização.
 */
object Notificacoes {

    private const val PREFS = "prismo_notif"
    private const val KEY_PLANO = "plano"
    private const val CANAL = "prismo_avisos_vib_v3"
    private const val CANAL_ANTIGO = "prismo_avisos_som_v2"
    private const val SEP_REG = '\u0002'
    private const val SEP_CAMPO = '\u0001'
    private const val COR = 0xFF2EA44F.toInt()

    private val PADRAO_VIBRACAO = longArrayOf(0, 600, 300, 600, 300, 600)

    private fun som(ctx: Context): Uri =
        Uri.parse("android.resource://${ctx.packageName}/raw/prismo_notification")

    fun criarCanal(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.deleteNotificationChannel(CANAL_ANTIGO)
        } catch (_: Throwable) {
        }
        if (nm.getNotificationChannel(CANAL) != null) return
        val canal = NotificationChannel(
            CANAL, ctx.getString(R.string.canal_nome), NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = ctx.getString(R.string.canal_desc)
            setSound(
                som(ctx),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            enableVibration(true)
            vibrationPattern = PADRAO_VIBRACAO
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(canal)
    }

    fun mostrar(ctx: Context, id: Int, titulo: String, texto: String, abrirIntent: Intent? = null, acao: String? = null) {
        criarCanal(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val b: Notification.Builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(ctx, CANAL)
        } else {
            Notification.Builder(ctx).apply {
                setPriority(Notification.PRIORITY_HIGH)
                setSound(som(ctx))
                setVibrate(PADRAO_VIBRACAO)
            }
        }

        val abrir = abrirIntent ?: Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        val pi = PendingIntent.getActivity(
            ctx, if (abrirIntent != null) id else 0, abrir, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        if (acao != null) runCatching { b.addAction(Notification.Action.Builder(null, acao, pi).build()) }

        b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setAutoCancel(true)
            .setColor(COR)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(pi)
        try {
            BitmapFactory.decodeResource(ctx.resources, R.mipmap.ic_launcher)?.let { b.setLargeIcon(it) }
        } catch (_: Throwable) {
        }

        try {
            nm.notify(id, b.build())
        } catch (_: SecurityException) {
            return // sem permissão de notificação
        }
        vibrar(ctx)
    }

    private fun vibrar(ctx: Context) {
        try {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vib.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createWaveform(PADRAO_VIBRACAO, -1))
            } else {
                vib.vibrate(PADRAO_VIBRACAO, -1)
            }
        } catch (_: Throwable) {
        }
    }

    // ───────────────────────── Agendamento ─────────────────────────

    /** Substitui o plano inteiro: cancela os alarmes antigos e arma os novos. */
    fun agendar(ctx: Context, plano: String) {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val antigo = prefs.getString(KEY_PLANO, "") ?: ""
        antigo.split(SEP_REG).forEach { cancelarRegistro(ctx, it) }
        prefs.edit().putString(KEY_PLANO, plano).apply()
        aplicar(ctx, plano)
    }

    /** Re-arma o plano salvo (usado no boot e depois de atualizar o app). */
    fun reaplicarSalvo(ctx: Context) {
        val plano = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PLANO, "") ?: ""
        aplicar(ctx, plano)
    }

    private fun aplicar(ctx: Context, plano: String) {
        val agora = System.currentTimeMillis()
        plano.split(SEP_REG).forEach { reg ->
            try {
                armarRegistro(ctx, reg, agora)
            } catch (_: Throwable) {
            }
        }
    }

    private fun armarRegistro(ctx: Context, reg: String, agora: Long) {
        val c = reg.split(SEP_CAMPO)
        if (c.size < 5) return
        val id = c[0].trim().toInt()
        var at = c[1].trim().toLong()
        val rep = c[2].trim().toLong()
        if (at <= agora) {
            if (rep <= 0) return
            while (at <= agora) at += rep
        }
        armar(ctx, id, c[3], c[4], at, rep)
    }

    private fun cancelarRegistro(ctx: Context, reg: String) {
        try {
            val c = reg.split(SEP_CAMPO)
            if (c.size >= 5) cancelar(ctx, c[0].trim().toInt())
        } catch (_: Throwable) {
        }
    }

    fun armar(ctx: Context, id: Int, titulo: String, texto: String, at: Long, rep: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pend(ctx, id, titulo, texto, at, rep))
    }

    private fun cancelar(ctx: Context, id: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pend(ctx, id, "", "", 0, 0))
    }

    private fun pend(ctx: Context, id: Int, t: String, b: String, at: Long, rep: Long): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            putExtra("id", id)
            putExtra("t", t)
            putExtra("b", b)
            putExtra("at", at)
            putExtra("rep", rep)
        }
        return PendingIntent.getBroadcast(
            ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
