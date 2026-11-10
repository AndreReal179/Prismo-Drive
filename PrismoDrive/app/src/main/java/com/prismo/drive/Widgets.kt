package com.prismo.drive

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/** Widget grande (4×2): ganho, meta, rotas, km, combustível e lucro do dia. */
class PrismoWidget4x2 : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetUtil.atualizar(context, manager, it, grande = true) }
        WidgetUtil.agendarMeiaNoite(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle?) {
        WidgetUtil.atualizar(context, manager, id, grande = true)
    }
}

/** Widget pequeno (2×2): ganho e lucro do dia. */
class PrismoWidget2x2 : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetUtil.atualizar(context, manager, it, grande = false) }
        WidgetUtil.agendarMeiaNoite(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle?) {
        WidgetUtil.atualizar(context, manager, id, grande = false)
    }
}

object WidgetUtil {
    private const val PREFS = "prismo_widget"
    private const val KEY = "dados"

    private const val VERDE = 0xFF3FB950.toInt()
    private const val VERMELHO = 0xFFF85149.toInt()
    private const val NEUTRO = 0xFFE6EDF3.toInt()
    private const val CHIP_OK = 0xFF58D68D.toInt()
    private const val CHIP_RUIM = 0xFFFF7B72.toInt()
    private val BR = Locale("pt", "BR")

    /** Chamado pela página (PrismoNative.widget) com o resumo do dia em JSON. */
    fun salvar(ctx: Context, json: String) {
        try {
            val o = JSONObject(json)
            o.put("ts", System.currentTimeMillis())
            prefs(ctx).edit().putString(KEY, o.toString()).apply()
            atualizarTodos(ctx)
            agendarMeiaNoite(ctx)
        } catch (_: Throwable) {
        }
    }

    fun atualizarTodos(context: Context) {
        try {
            val ctx = context.applicationContext ?: context
            val mgr = AppWidgetManager.getInstance(ctx)
            mgr.getAppWidgetIds(ComponentName(ctx, PrismoWidget4x2::class.java)).forEach {
                mgr.updateAppWidget(it, montar(ctx, true))
            }
            mgr.getAppWidgetIds(ComponentName(ctx, PrismoWidget2x2::class.java)).forEach {
                mgr.updateAppWidget(it, montar(ctx, false))
            }
        } catch (_: Throwable) {
        }
    }

    fun atualizar(ctx: Context, mgr: AppWidgetManager, id: Int, grande: Boolean) {
        try {
            mgr.updateAppWidget(id, montar(ctx, grande))
        } catch (_: Throwable) {
        }
    }

    /**
     * Virada do dia: redesenha os widgets logo depois da meia-noite, mesmo com o app fechado.
     * Os dados guardados são de "ontem", então ganho, rotas, km, combustível e lucro aparecem zerados.
     */
    fun agendarMeiaNoite(context: Context) {
        try {
            val ctx = context.applicationContext ?: context
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val c = java.util.Calendar.getInstance().apply {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 30); set(java.util.Calendar.MILLISECOND, 0)
            }
            val pi = PendingIntent.getBroadcast(
                ctx, 7701, Intent(ctx, WidgetMeiaNoite::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            if (android.os.Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(android.app.AlarmManager.RTC, c.timeInMillis, pi)
            else am.set(android.app.AlarmManager.RTC, c.timeInMillis, pi)
        } catch (_: Throwable) {
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun montar(ctx: Context, grande: Boolean): RemoteViews {
        val v = RemoteViews(ctx.packageName, if (grande) R.layout.widget_4x2 else R.layout.widget_2x2)

        val d: JSONObject? = try {
            prefs(ctx).getString(KEY, null)?.let { JSONObject(it) }
        } catch (_: Throwable) {
            null
        }
        val tem = d != null
        val hojeStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val hoje = d != null && hojeStr == d.optString("dia", "")

        val ganho = if (hoje) d!!.optDouble("ganho", 0.0) else 0.0
        val comb = if (hoje) d!!.optDouble("comb", 0.0) else 0.0
        val lucro = if (hoje) d!!.optDouble("lucro", ganho - comb) else 0.0
        val km = if (hoje) d!!.optDouble("km", 0.0) else 0.0
        val rotas = if (hoje) d!!.optInt("rotas", 0) else 0
        val pct = (if (hoje) d!!.optInt("pct", 0) else 0).coerceIn(0, 100)
        val meta = if (tem) d!!.optDouble("meta", 250.0) else 0.0

        v.setProgressBar(R.id.wdg_barra, 100, pct, false)

        if (!grande) {
            v.setTextViewText(R.id.wdg_ganho, "R$ " + inteiro(ganho))
            v.setTextViewText(
                R.id.wdg_lucro,
                if (lucro >= 0) "Lucro R$ " + num(lucro) else "Lucro -R$ " + num(-lucro)
            )
            v.setTextColor(R.id.wdg_lucro, if (lucro >= 0) VERDE else VERMELHO)
        } else {
            val atualizado = if (tem && hoje) {
                "atualizado " + SimpleDateFormat("HH:mm", Locale.US)
                    .format(Date(d!!.optLong("ts", System.currentTimeMillis())))
            } else {
                "toque para atualizar"
            }
            v.setTextViewText(R.id.wdg_atualizado, atualizado)
            v.setTextViewText(R.id.wdg_ganho, num(ganho))

            if (hoje && d!!.has("vs") && !d.isNull("vs")) {
                val vs = d.optDouble("vs", 0.0).roundToLong()
                val subiu = vs >= 0
                v.setTextViewText(R.id.wdg_chip, (if (vs > 0) "+" else "") + vs + "% vs ontem")
                v.setInt(
                    R.id.wdg_chip, "setBackgroundResource",
                    if (subiu) R.drawable.wdg_chip_up else R.drawable.wdg_chip_down
                )
                v.setTextColor(R.id.wdg_chip, if (subiu) CHIP_OK else CHIP_RUIM)
                v.setViewVisibility(R.id.wdg_chip, View.VISIBLE)
            } else {
                v.setViewVisibility(R.id.wdg_chip, View.GONE)
            }

            v.setTextViewText(
                R.id.wdg_meta,
                if (tem) "Meta diária R$ " + metaTxt(meta) else "Abra o app para sincronizar"
            )
            v.setTextViewText(R.id.wdg_pct, "$pct%")
            v.setTextViewText(R.id.wdg_rotas, rotas.toString())
            v.setTextViewText(R.id.wdg_km, kmTxt(km))
            if (comb > 0) {
                v.setTextViewText(R.id.wdg_comb, "-" + num(comb))
                v.setTextColor(R.id.wdg_comb, VERMELHO)
            } else {
                v.setTextViewText(R.id.wdg_comb, num(0.0))
                v.setTextColor(R.id.wdg_comb, NEUTRO)
            }
            v.setTextViewText(R.id.wdg_lucro, num(lucro))
            v.setTextColor(R.id.wdg_lucro, if (lucro >= 0) VERDE else VERMELHO)
        }

        // Toque no widget abre o app
        val abrir = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        val pi = PendingIntent.getActivity(
            ctx, if (grande) 41 else 22, abrir,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        v.setOnClickPendingIntent(R.id.wdg_root, pi)
        return v
    }

    private fun num(x: Double) = String.format(BR, "%,.2f", x)
    private fun inteiro(x: Double) = String.format(BR, "%,d", x.toLong())
    private fun kmTxt(x: Double) = String.format(BR, "%,.1f", x)
    private fun metaTxt(x: Double) = if (x != floor(x)) num(x) else inteiro(x)
}


/** Alarme da meia-noite: zera os widgets e já agenda a próxima virada. */
class WidgetMeiaNoite : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetUtil.atualizarTodos(context)
        WidgetUtil.agendarMeiaNoite(context)
    }
}
