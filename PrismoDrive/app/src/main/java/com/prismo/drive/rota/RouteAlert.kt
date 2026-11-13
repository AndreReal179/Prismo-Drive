package com.prismo.drive.rota

import com.prismo.drive.R
import com.prismo.drive.BuildConfig

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Alerta de rota nova: som + vibração.
 *
 * Usa o canal de ALARME do Android — é o que passa pelo "Não perturbe" e pelo modo silencioso.
 * v1.9.8: volume no máximo durante o alerta (e volta ao normal depois), som de BOM (ÓTIMO) e de RUIM,
 * e o toque se repete 2 s depois de terminar, até a oferta sumir, você tocar na tela ou passar 90 s.
 */
object RouteAlert {

    /** Sons disponíveis (0 = sem som). */
    val SOUNDS = listOf("Sem som", "Pulso", "Moeda", "Sino", "Radar")
    private val SOUND_RES = intArrayOf(0, R.raw.alerta_pulso, R.raw.alerta_moeda, R.raw.alerta_sino, R.raw.alerta_radar)
    /** Sons especiais (fora da lista de escolha): rota ÓTIMA e rota RUIM. */
    const val SOM_BOM = 100
    const val SOM_RUIM = 101

    private val h = Handler(Looper.getMainLooper())
    private var ciclo: Runnable? = null
    private var tocando: MediaPlayer? = null
    private var reforco: LoudnessEnhancer? = null
    private var volAntes = -1
    private var fimEm = 0L
    private const val REPETIR_A_CADA_MS = 2_000L
    private const val REPETIR_ATE_MS = 90_000L

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
        val som = if (prefs.alertPorNivel) somDoNivel(Evaluator.best(results), prefs.alertSound) else prefs.alertSound
        iniciar(ctx, som, prefs.alertVibe, prefs.alertRepetir, prefs.alertMaximo)
    }

    /** ÓTIMO → som de bom · RUIM (ou passageiro com nota baixa) → som de ruim · MÉDIO → o som escolhido. */
    private fun somDoNivel(r: Result?, escolhido: Int): Int = when {
        r == null || escolhido == 0 -> escolhido
        Evaluator.badRating(r) || r.level == Level.RUIM -> SOM_RUIM
        r.level == Level.OTIMO -> SOM_BOM
        else -> escolhido
    }

    /** Começa o alerta: toca, espera 2 s depois de terminar e toca de novo (se [repetir]). */
    fun iniciar(ctx: Context, sound: Int, vibe: Int, repetir: Boolean, maximo: Boolean) {
        val app = ctx.applicationContext
        parar(app)
        if (maximo && sound != 0) volumeMaximo(app)
        fimEm = SystemClock.uptimeMillis() + REPETIR_ATE_MS
        val r = object : Runnable {
            override fun run() {
                runCatching { vibrate(app, vibe) }
                val tocou = runCatching {
                    playSound(app, sound) {
                        if (repetir && ciclo === this && SystemClock.uptimeMillis() < fimEm) h.postDelayed(this, REPETIR_A_CADA_MS)
                        else if (ciclo === this) parar(app)
                    }
                }.getOrDefault(false)
                // sem som: repete só a vibração
                if (!tocou) {
                    if (repetir && ciclo === this && SystemClock.uptimeMillis() < fimEm) h.postDelayed(this, REPETIR_A_CADA_MS + 600)
                    else if (ciclo === this) parar(app)
                }
            }
        }
        ciclo = r
        h.post(r)
    }

    /** Para o alerta (a oferta sumiu ou você tocou na tela) e devolve o volume que estava antes. */
    fun parar(ctx: Context) {
        ciclo?.let { h.removeCallbacks(it) }
        ciclo = null
        runCatching { tocando?.stop() }
        runCatching { tocando?.release() }
        tocando = null
        runCatching { reforco?.release() }
        reforco = null
        if (volAntes >= 0) {
            runCatching {
                (ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .setStreamVolume(AudioManager.STREAM_ALARM, volAntes, 0)
            }
            volAntes = -1
        }
    }

    val ativo: Boolean get() = ciclo != null

    private fun volumeMaximo(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val atual = am.getStreamVolume(AudioManager.STREAM_ALARM)
            if (atual < max) {
                volAntes = atual
                am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            }
        }
    }

    /** Som de moedas + vibração curta: valor da rota concluída lançado no Prismo Drive. */
    fun moedas(ctx: Context) {
        val app = ctx.applicationContext
        runCatching { playSound(app, 2) }          // alerta_moeda
        runCatching {
            val v = vibrator(app) ?: return@runCatching
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40, 60, 90), -1))
            }
        }
    }

    /** Toca o som [sound] e a vibração [vibe] uma vez (botão "Testar"), no volume máximo se ligado. */
    fun play(ctx: Context, sound: Int, vibe: Int) {
        val app = ctx.applicationContext
        iniciar(app, sound, vibe, repetir = false, maximo = sound != 0 && Prefs(app).alertMaximo)
    }

    /** Toca uma vez; [fim] é chamado quando o som termina. Devolve false se não há som. */
    private fun playSound(ctx: Context, sound: Int, fim: (() -> Unit)? = null): Boolean {
        val res = when (sound) {
            SOM_BOM -> R.raw.alerta_bom
            SOM_RUIM -> R.raw.alerta_ruim
            else -> SOUND_RES.getOrNull(sound) ?: 0
        }
        if (res == 0) return false
        val afd = ctx.resources.openRawResourceFd(res) ?: return false
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(ALARM_AUDIO)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.setOnCompletionListener {
                if (tocando === it) tocando = null
                runCatching { it.release() }
                fim?.invoke()
            }
            mp.setOnErrorListener { p, _, _ -> if (tocando === p) tocando = null; p.release(); fim?.invoke(); true }
            mp.prepare()
            // reforço de volume (+12 dB) por cima do volume de alarme
            runCatching {
                reforco?.release()
                reforco = LoudnessEnhancer(mp.audioSessionId).apply { setTargetGain(1200); enabled = true }
            }
            if (fim != null) tocando = mp
            mp.start()
            return true
        } catch (t: Throwable) {
            runCatching { afd.close() }
            mp.release()
            return false
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
