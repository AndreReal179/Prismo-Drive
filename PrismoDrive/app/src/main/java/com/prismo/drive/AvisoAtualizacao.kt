package com.prismo.drive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Aviso de atualização: de manhã (8h), à tarde (13h) e à noite (19h), todos os dias, o app confere
 * sozinho no GitHub se saiu uma versão nova. Se saiu, mostra a notificação "Nova atualização disponível".
 * Quando a pessoa atualiza, a conferência passa a dizer que está em dia e o aviso para sozinho.
 */
object AvisoAtualizacao {
    private val HORAS = intArrayOf(8, 13, 19)
    private const val ID_NOTIF = 9150
    const val EXTRA_ATUALIZAR = "prismo_atualizar"

    fun agendar(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        HORAS.indices.forEach { runCatching { armar(app, it) } }
    }

    private fun proximo(hora: Int): Long {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hora); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= System.currentTimeMillis() + 30_000L) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun pend(ctx: Context, i: Int): PendingIntent = PendingIntent.getBroadcast(
        ctx, 9100 + i,
        Intent(ctx, AtualizacaoReceiver::class.java).putExtra("i", i),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun armar(ctx: Context, i: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, proximo(HORAS[i]), pend(ctx, i))
    }

    /** Chamado no horário: re-arma o próximo dia e confere se há versão nova. */
    fun disparar(ctx: Context, i: Int, fim: () -> Unit) {
        val app = ctx.applicationContext ?: ctx
        runCatching { if (i in HORAS.indices) armar(app, i) }
        if (Atualizador.verificando) { fim(); return }
        val feito = AtomicBoolean(false)
        try {
            Atualizador.verificar(app) { ev ->
                when (ev.optString("tipo")) {
                    "resultado" -> if (feito.compareAndSet(false, true)) {
                        runCatching {
                            if (ev.optBoolean("nova")) {
                                val v = ev.optString("versao").ifBlank { "nova" }
                                val abrir = Intent(app, MainActivity::class.java).apply {
                                    putExtra(EXTRA_ATUALIZAR, true)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                }
                                Notificacoes.mostrar(
                                    app, ID_NOTIF, "🚀 Nova atualização disponível",
                                    "A versão $v do Prismo Drive já está disponível. Toque para atualizar agora.",
                                    abrir, "Atualizar"
                                )
                            }
                        }
                        fim()
                    }
                    "erro" -> if (feito.compareAndSet(false, true)) fim()
                }
            }
        } catch (_: Throwable) {
            if (feito.compareAndSet(false, true)) fim()
        }
    }
}

class AtualizacaoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pr = goAsync()
        try {
            AvisoAtualizacao.disparar(context, intent.getIntExtra("i", -1)) { runCatching { pr.finish() } }
        } catch (_: Throwable) {
            runCatching { pr.finish() }
        }
    }
}
