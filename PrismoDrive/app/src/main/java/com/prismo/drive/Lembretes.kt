package com.prismo.drive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Lembrete da meta de quitação (Carteira › Planos), às 20h.
 *
 * A decisão é tomada na hora, no celular, mesmo com o app fechado:
 * - só avisa em DIA TRABALHADO (teve ganho hoje: rotas lançadas, iFood automático, turno…);
 * - não avisa se você já guardou algo para a meta hoje;
 * - dia de folga (sem ganho) não tem aviso.
 * A página manda os dados da meta (nome, valor por dia, quanto falta e os dias com depósito).
 */
object Lembretes {
    private const val ARQ = "lembrete_quitacao.json"
    private const val ID_NOTIF = 9100
    private const val REQ = 9101
    private const val HORA = 20
    private val BR = Locale("pt", "BR")

    private fun arq(ctx: Context) = File((ctx.applicationContext ?: ctx).filesDir, ARQ)

    /** json vazio = sem meta de quitação ativa (cancela o lembrete). */
    fun salvarQuitacao(ctx: Context, json: String?) {
        runCatching {
            if (json.isNullOrBlank()) { arq(ctx).delete(); cancelar(ctx); return }
            JSONObject(json)  // valida
            arq(ctx).writeText(json)
            agendar(ctx)
        }
    }

    fun reagendar(ctx: Context) {
        runCatching { if (arq(ctx).exists()) agendar(ctx) }
    }

    private fun pend(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ, Intent(ctx, LembreteReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun agendar(ctx: Context) {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HORA); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis() + 30_000) add(Calendar.DAY_OF_YEAR, 1)
        }
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, c.timeInMillis, pend(ctx))
    }

    private fun cancelar(ctx: Context) {
        runCatching { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pend(ctx)) }
    }

    private fun moeda(v: Double) = "R$ " + String.format(BR, "%,.2f", v)

    /** Hora do lembrete: decide se avisa e já arma o de amanhã. */
    fun disparar(ctx: Context) {
        runCatching {
            val f = arq(ctx)
            if (!f.exists()) return
            val o = JSONObject(f.readText())
            agendar(ctx)
            val hoje = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val dias = o.optJSONArray("depDias")
            if (dias != null) for (i in 0 until dias.length()) if (dias.optString(i) == hoje) return  // já guardou hoje
            val falta = o.optDouble("falta", 0.0)
            if (falta <= 0) return
            val ganho = WidgetUtil.ganhoHoje(ctx)
            if (ganho <= 0) return                                                          // folga: sem aviso
            val dia = o.optDouble("valorDia", 0.0)
            val nome = o.optString("nome", "a sua meta")
            Notificacoes.mostrar(
                ctx, ID_NOTIF,
                "Hoje você fez " + moeda(ganho) + " 💪",
                "Separar " + moeda(dia) + " para " + nome + "? Faltam " + moeda(falta) + " para quitar."
            )
        }
    }
}

class LembreteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Lembretes.disparar(context)
    }
}
