package com.prismo.drive.rota

import com.prismo.drive.R
import com.prismo.drive.BuildConfig

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Alerta de rota nova: som + vibração.
 *
 * Usa o canal de ALARME do Android — é o que passa pelo "Não perturbe" e pelo modo silencioso
 * (o volume é o de alarme do celular). A mesma oferta não toca de novo enquanto continua na tela.
 */
object RouteAlert {

    /** Sons disponíveis (0 = sem som). */
    val SOUNDS = listOf("Sem som", "Pulso", "Moeda", "Sino", "Radar")
    private val SOUND_RES = intArrayOf(0, R.raw.alerta_pulso, R.raw.alerta_moeda, R.raw.alerta_sino, R.raw.alerta_radar)

    /** Vibrações disponíveis (0 = sem vibração). */
    val VIBES = listOf("Sem vibração", "Curta", "Dupla", "Longa", "Batida", "Tripla")
    private val VIBE_PATTERNS = arrayOf(
        longArrayOf(),
        longArrayOf(0, 120),                          // curta
        longArrayOf(0, 110, 90, 110),                 // dupla
        longArrayOf(0, 600),                          // longa
        longArrayOf(0, 70, 80, 200),                  // batida (tum-TUM)
        longArrayOf(0, 90, 70, 90, 70, 90)            // tripla
    )
    private val VIBE_AMPS = arrayOf(
        intArrayOf(),
        intArrayOf(0, 255),
        intArrayOf(0, 255, 0, 255),
        intArrayOf(0, 220),
        intArrayOf(0, 150, 0, 255),
        intArrayOf(0, 255, 0, 255, 0, 255)
    )

    private val ALARM_AUDIO: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    // ofertas que já tocaram (chave → última vez vista na tela)
    private val seen = HashMap<String, Long>()
    private const val FORGET_MS = 3 * 60_000L       // sumiu da tela há 3 min: se voltar, toca de novo
    private const val MIN_GAP_MS = 1_500L           // nunca toca 2× em menos de 1,5 s
    private var lastFire = 0L

    private fun keyOf(r: Result): String {
        val o = r.offer
        val cents = Math.round(o.price * 100)
        return if (o.route) "R|$cents|${o.entregas}" else "D|$cents|${Math.round((o.rating ?: 0.0) * 100)}"
    }

    /** Chamado a cada leitura da tela: toca se apareceu alguma oferta nova. */
    fun onResults(ctx: Context, results: List<Result>) {
        val prefs = Prefs(ctx)
        val now = SystemClock.uptimeMillis()
        var fresh = false
        for (r in results) {
            if (r.offer.price <= 0) continue
            val k = keyOf(r)
            val last = seen[k]
            if (last == null || now - last > FORGET_MS) fresh = true
            seen[k] = now
        }
        if (seen.size > 200) seen.entries.removeAll { now - it.value > FORGET_MS }
        if (!fresh || !prefs.alertOn || now - lastFire < MIN_GAP_MS) return
        lastFire = now
        play(ctx, prefs.alertSound, prefs.alertVibe)
    }

    /** Toca o som [sound] e a vibração [vibe] (também usado no botão "Testar"). */
    fun play(ctx: Context, sound: Int, vibe: Int) {
        val app = ctx.applicationContext
        runCatching { playSound(app, sound) }
        runCatching { vibrate(app, vibe) }
    }

    private fun playSound(ctx: Context, sound: Int) {
        val res = SOUND_RES.getOrNull(sound) ?: 0
        if (res == 0) return
        val afd = ctx.resources.openRawResourceFd(res) ?: return
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(ALARM_AUDIO)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { p, _, _ -> p.release(); true }
            mp.prepare()
            mp.start()
        } catch (t: Throwable) {
            runCatching { afd.close() }
            mp.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(ctx: Context): Vibrator? {
        if (Build.VERSION.SDK_INT >= 31) {
            return (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        }
        return ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ctx: Context, vibe: Int) {
        val pattern = VIBE_PATTERNS.getOrNull(vibe) ?: return
        if (pattern.isEmpty()) return
        val v = vibrator(ctx) ?: return
        if (!v.hasVibrator()) return
        val effect = if (v.hasAmplitudeControl())
            VibrationEffect.createWaveform(pattern, VIBE_AMPS[vibe], -1)
        else VibrationEffect.createWaveform(pattern, -1)
        if (Build.VERSION.SDK_INT >= 33) {
            // "alarme": vibra mesmo no Não perturbe / modo silencioso
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            v.vibrate(effect, ALARM_AUDIO)
        }
    }
}
