package com.prismo.drive.rota

import com.prismo.drive.R
import com.prismo.drive.BuildConfig

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast

/**
 * Notificação fixa "Estamos executando em segundo plano" com o botão Desligar.
 * Fica na barra enquanto o serviço está ligado e só sai quando o usuário toca em Desligar
 * (se o Android deixar arrastar para o lado, ela volta na hora).
 */
object Notif {
    private const val CHANNEL = "prismo_rotas_status"
    private const val ID = 7701
    const val ACTION_OFF = "com.prismo.drive.rota.action.DESLIGAR"
    const val ACTION_DISMISSED = "com.prismo.drive.rota.action.NOTIF_REMOVIDA"
    const val ACTION_SHUTDOWN = "com.prismo.drive.rota.action.ENCERRAR"
    const val ACTION_TESTE_MOEDA = "com.prismo.drive.rota.action.TESTE_MOEDA"

    fun canPost(ctx: Context) =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun ensureChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "Assistente de Rotas em execução", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Aviso fixo enquanto o Assistente de Rotas está ligado, com o botão Desligar"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm(ctx).createNotificationChannel(ch)
    }

    fun show(ctx: Context) {
        val app = ctx.applicationContext
        if (!canPost(app)) return
        runCatching {
            ensureChannel(app)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val open = PendingIntent.getActivity(
                app, 0,
                Intent(app, RotasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                flags
            )
            val off = PendingIntent.getBroadcast(
                app, 1, Intent(app, PowerReceiver::class.java).setAction(ACTION_OFF), flags
            )
            val dismissed = PendingIntent.getBroadcast(
                app, 2, Intent(app, PowerReceiver::class.java).setAction(ACTION_DISMISSED), flags
            )
            val offAction = Notification.Action.Builder(
                Icon.createWithResource(app, R.drawable.ic_power), "Desligar", off
            ).build()

            val n = Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_rota)
                .setContentTitle("Assistente de Rotas")
                .setContentText("Estamos executando em segundo plano")
                .setSubText("Ligado")
                .setColor(0xFF2EA44F.toInt())
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                .setDeleteIntent(dismissed)
                .addAction(offAction)
                .build()
            n.flags = n.flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            nm(app).notify(ID, n)
        }
    }

    fun cancel(ctx: Context) {
        runCatching { nm(ctx.applicationContext).cancel(ID) }
    }
}

/** Recebe o toque em "Desligar" e repõe a notificação se ela for arrastada para fora. */
class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Notif.ACTION_OFF -> {
                Power.turnOff(ctx)
                Toast.makeText(
                    ctx.applicationContext,
                    "Assistente de Rotas desligado. Já pode abrir o app do banco.",
                    Toast.LENGTH_LONG
                ).show()
            }
            Notif.ACTION_TESTE_MOEDA -> RouteService.instance?.testarComemoracao()
            Notif.ACTION_SHUTDOWN -> {
                // pedido vindo da tela (outro processo): desliga sem repetir o aviso
                RouteService.instance?.shutdown()
            }
            Notif.ACTION_DISMISSED -> {
                // só sai da barra pelo botão Desligar
                if (RouteService.running) Notif.show(ctx)
            }
        }
    }
}
