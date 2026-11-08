package com.prismo.drive.rota

import com.prismo.drive.R
import com.prismo.drive.BuildConfig

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Liga e desliga o serviço de acessibilidade do Assistente de Rotas.
 *
 * Desligado de verdade = o serviço sai da lista de acessibilidade do sistema. É isso que os apps de
 * banco verificam; com o Assistente de Rotas desligado eles abrem normalmente.
 *
 * - Desligar: funciona sempre (o próprio serviço chama disableSelf()).
 * - Ligar com 1 toque: precisa da permissão WRITE_SECURE_SETTINGS (concedida uma única vez por ADB).
 *   Sem ela, abre a tela de acessibilidade para ligar a chave.
 */
object Power {

    private const val WSS = "android.permission.WRITE_SECURE_SETTINGS"

    fun component(ctx: Context) = ComponentName(ctx, RouteService::class.java)

    fun hasSecure(ctx: Context) = ctx.checkSelfPermission(WSS) == PackageManager.PERMISSION_GRANTED

    private fun enabledList(ctx: Context): List<String> =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').filter { it.isNotBlank() }

    private fun isMine(ctx: Context, s: String) = ComponentName.unflattenFromString(s) == component(ctx)

    /** O serviço está marcado como ligado nas configurações do sistema. */
    fun isEnabled(ctx: Context) = enabledList(ctx).any { isMine(ctx, it) }

    private fun writeList(ctx: Context, list: List<String>) {
        Settings.Secure.putString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
    }

    /** Desliga o Assistente de Rotas por completo (some o botão, o painel e a notificação). */
    fun turnOff(ctx: Context) {
        val app = ctx.applicationContext
        val inst = RouteService.instance
        if (inst != null) inst.shutdown()
        else runCatching {   // serviço está no processo ":rotas": pede para ele se desligar
            app.sendBroadcast(Intent(app, PowerReceiver::class.java).setAction(Notif.ACTION_SHUTDOWN))
        }
        RotasStatus.limpar(app)
        // reforço: se tiver a permissão, também tira da lista do sistema
        if (hasSecure(app)) runCatching { writeList(app, enabledList(app).filterNot { isMine(app, it) }) }
        Notif.cancel(app)
    }

    /**
     * Liga o Assistente de Rotas. Retorna true se ligou direto; false se abriu a tela de acessibilidade
     * para o usuário ligar a chave manualmente.
     */
    fun turnOn(ctx: Context): Boolean {
        val app = ctx.applicationContext
        if (hasSecure(app)) {
            val ok = runCatching {
                val others = enabledList(app).filterNot { isMine(app, it) }
                val me = component(app).flattenToString()
                if (isEnabled(app) && !RotasStatus.ligado(app)) {
                    // marcado como ligado mas travado: desliga e liga de novo
                    writeList(app, others)
                    Handler(Looper.getMainLooper()).postDelayed({
                        runCatching {
                            writeList(app, others + me)
                            Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                        }
                    }, 600)
                } else {
                    writeList(app, others + me)
                    Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                }
            }.isSuccess
            if (ok) return true
        }
        openAccessibilitySettings(ctx)
        return false
    }

    fun openAccessibilitySettings(ctx: Context) {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val opened = runCatching {
            ctx.startActivity(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, component(ctx).flattenToString())
                    .addFlags(flags)
            )
        }.isSuccess
        if (!opened) runCatching {
            ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(flags))
        }
    }
}
