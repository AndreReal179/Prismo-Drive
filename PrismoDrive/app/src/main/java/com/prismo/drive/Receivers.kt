package com.prismo.drive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Disparado pelo AlarmManager: mostra o lembrete e, se for repetitivo, agenda o próximo. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val id = intent.getIntExtra("id", 0)
            val titulo = intent.getStringExtra("t") ?: ""
            val texto = intent.getStringExtra("b") ?: ""
            val at = intent.getLongExtra("at", 0L)
            val rep = intent.getLongExtra("rep", 0L)
            if (rep > 0) {
                var prox = at + rep
                val agora = System.currentTimeMillis()
                while (prox <= agora) prox += rep
                Notificacoes.armar(context, id, titulo, texto, prox, rep)
            }
            Notificacoes.mostrar(context, id, titulo, texto)
        } catch (_: Throwable) {
        }
    }
}

/** Depois de reiniciar o celular ou atualizar o app, re-arma os lembretes e os widgets. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            Notificacoes.reaplicarSalvo(context)
        } catch (_: Throwable) {
        }
        try {
            WidgetUtil.atualizarTodos(context)
            WidgetUtil.agendarMeiaNoite(context)
        } catch (_: Throwable) {
        }
    }
}
