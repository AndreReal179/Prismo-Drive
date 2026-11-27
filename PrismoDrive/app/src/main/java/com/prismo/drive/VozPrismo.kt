package com.prismo.drive

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.WebView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * v2.3 · Assistente de voz "Ei Prismo!" — só com o app aberto na tela.
 *
 * • Palavra de ativação: Vosk (reconhecimento offline, no próprio celular) com uma gramática fechada
 *   ("ei prisma", "oi prisma"...). Nada de áudio sai do celular nessa escuta.
 *   O modelo em português (~31 MB) é baixado uma vez, quando a pessoa liga o assistente.
 * • O pedido em si ("acabei de receber 5 mil"): reconhecedor de fala do Android (pt-BR).
 * • A resposta: voz do Android (TextToSpeech pt-BR).
 * O app pausa a escuta quando sai da tela (onPause) e enquanto o assistente está aberto.
 */
class VozPrismo(private val act: Activity, private val web: WebView) {

    companion object {
        const val REQ_MIC = 4301
        private const val PREFS = "prismo_voz"
        private const val URL_MODELO = "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"
        private const val PASTA = "vosk-pt"
        /* "prismo" não está no vocabulário do modelo: "prisma" é o som mais próximo */
        private const val GRAMATICA = "[\"ei prisma\", \"oi prisma\", \"e prisma\", \"é prisma\", \"hei prisma\", \"ei prismo\", \"[unk]\"]"
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var modelo: org.vosk.Model? = null
    private var servico: org.vosk.android.SpeechService? = null
    private var carregando = false
    @Volatile private var baixando = false
    private var naTela = false
    private var dormindo = false          // o assistente está aberto (ou falando): sem escuta da palavra
    private var reconhecedor: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsPronto = false

    private fun pastaModelo() = File(act.filesDir, PASTA)
    /* o modelo instalado: marcador ".ok" + o arquivo principal (final.mdl) em am/ ou na raiz (modelos antigos) */
    fun temModelo() = File(pastaModelo(), ".ok").exists() &&
        (File(pastaModelo(), "am/final.mdl").exists() || File(pastaModelo(), "final.mdl").exists())

    /** Acha, dentro do que foi descompactado, a pasta que o Vosk espera (a que tem am/final.mdl, ou final.mdl na raiz). */
    private fun raizModelo(dir: File): File? {
        val mdl = dir.walkTopDown().maxDepth(5).firstOrNull { it.isFile && it.name == "final.mdl" } ?: return null
        val pai = mdl.parentFile ?: return null
        return if (pai.name == "am") pai.parentFile else pai
    }
    fun temMic() = Build.VERSION.SDK_INT < 23 || act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var ativo: Boolean
        get() = prefs.getBoolean("ativo", false)
        set(v) { prefs.edit().putBoolean("ativo", v).apply() }

    private fun js(ev: String, detalhe: JSONObject? = null) {
        val d = detalhe?.toString() ?: "null"
        main.post {
            runCatching { web.evaluateJavascript("try{window.dispatchEvent(new CustomEvent('$ev',{detail:$d}));}catch(e){}", null) }
        }
    }

    fun estado(): String = JSONObject()
        .put("ativo", ativo).put("modelo", temModelo()).put("baixando", baixando).put("mic", temMic())
        .put("escutando", servico != null).put("falaOk", SpeechRecognizer.isRecognitionAvailable(act))
        .toString()

    // ───────────────────── ciclo de vida da tela ─────────────────────

    fun naTela(v: Boolean) {
        naTela = v
        if (v) retomar() else { pararEscuta(); pararReconhecedor(); runCatching { tts?.stop() }; pararAudio() }
    }

    fun destruir() {
        pararEscuta(); pararReconhecedor(); pararAudio()
        runCatching { modelo?.close() }; modelo = null
        runCatching { tts?.shutdown() }; tts = null
    }

    /** Liga ou desliga o "Ei Prismo" (configuração). */
    fun ligar(on: Boolean) { main.post { ativo = on; if (on) { dormindo = false; retomar() } else pararEscuta() } }

    /** O assistente abriu (true) ou fechou (false). */
    fun dormir(v: Boolean) {
        dormindo = v
        if (v) pararEscuta() else main.postDelayed({ retomar() }, 600)
    }

    private fun retomar() {
        if (!naTela || dormindo || !ativo || !temMic() || !temModelo() || servico != null) return
        if (modelo == null) { carregarModelo(); return }
        iniciarEscuta()
    }

    // ───────────────────── modelo (download uma vez) ─────────────────────

    fun baixarModelo() {
        if (baixando || temModelo()) { if (temModelo()) js("prismo-voz-modelo", JSONObject().put("ok", true)); return }
        baixando = true
        Thread {
            val tmp = File(act.cacheDir, "vosk-pt.zip")
            var ok = false
            var erro = ""
            try {
                val c = (URL(URL_MODELO).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = true
                }
                c.connect()
                if (c.responseCode != 200) throw Exception("HTTP " + c.responseCode)
                val total = c.contentLengthLong
                var lido = 0L
                var ultimo = -1
                c.inputStream.use { inp ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); lido += n
                            if (lido > 120L * 1024 * 1024) throw Exception("arquivo grande demais")
                            val p = if (total > 0) (lido * 100 / total).toInt() else -1
                            if (p != ultimo && (p - ultimo >= 2 || p == 100)) { ultimo = p; js("prismo-voz-modelo", JSONObject().put("pct", p)) }
                        }
                    }
                }
                // descompacta numa pasta nova e só troca no fim (com ou sem pasta raiz dentro do zip)
                val novo = File(act.filesDir, "$PASTA.novo"); novo.deleteRecursively(); novo.mkdirs()
                val base = novo.canonicalPath + File.separator
                if (lido < 5L * 1024 * 1024) throw Exception("download incompleto (" + (lido / 1024) + " KB) · tente de novo no Wi-Fi")
                var arquivos = 0
                ZipInputStream(tmp.inputStream().buffered()).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val nome = e.name.replace('\\', '/').trimStart('/').removePrefix("./")
                        if (nome.isEmpty() || nome.contains("__MACOSX")) continue
                        val alvo = File(novo, nome)
                        if (!alvo.canonicalPath.startsWith(base)) throw Exception("zip inválido")   // zip slip
                        if (e.isDirectory) alvo.mkdirs() else { alvo.parentFile?.mkdirs(); FileOutputStream(alvo).use { z.copyTo(it) }; arquivos++ }
                    }
                }
                if (arquivos == 0) throw Exception("o arquivo baixado não é a voz (site fora do ar?)")
                val raiz = raizModelo(novo) ?: throw Exception("arquivo da voz sem o modelo (final.mdl)")
                pastaModelo().deleteRecursively()
                if (!raiz.renameTo(pastaModelo())) throw Exception("não deu para salvar")
                novo.deleteRecursively()
                File(pastaModelo(), ".ok").writeText(System.currentTimeMillis().toString())
                ok = true
            } catch (t: Throwable) {
                erro = t.message ?: "erro"
            } finally {
                runCatching { tmp.delete() }
                baixando = false
            }
            js("prismo-voz-modelo", JSONObject().put("ok", ok).put("erro", erro))
            if (ok) main.post { retomar() }
        }.start()
    }

    private fun carregarModelo() {
        if (carregando) return
        carregando = true
        Thread {
            val m = runCatching { org.vosk.Model(pastaModelo().absolutePath) }.getOrNull()
            main.post {
                carregando = false
                if (m == null) { js("prismo-voz-erro", JSONObject().put("erro", "modelo")); return@post }
                modelo = m
                retomar()
            }
        }.start()
    }

    // ───────────────────── escuta da palavra "Ei Prismo" ─────────────────────

    private fun iniciarEscuta() {
        val m = modelo ?: return
        runCatching {
            val rec = org.vosk.Recognizer(m, 16000.0f, GRAMATICA)
            val s = org.vosk.android.SpeechService(rec, 16000.0f)
            s.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(h: String?) { ver(h, "partial") }
                override fun onResult(h: String?) { ver(h, "text") }
                override fun onFinalResult(h: String?) { ver(h, "text") }
                override fun onError(e: Exception?) { pararEscuta(); main.postDelayed({ retomar() }, 3000) }
                override fun onTimeout() { pararEscuta(); main.postDelayed({ retomar() }, 500) }
            })
            servico = s
            js("prismo-voz-escuta", JSONObject().put("on", true))
        }.onFailure { servico = null }
    }

    private fun ver(h: String?, campo: String) {
        if (h == null || servico == null) return
        val t = runCatching { JSONObject(h).optString(campo) }.getOrDefault("").lowercase(Locale("pt", "BR"))
        if (t.contains("prism") && Regex("\\b(ei|oi|e|é|hei|ê)\\s+prism").containsMatchIn(t)) {
            pararEscuta()
            dormindo = true
            js("prismo-voz-acordou")
        }
    }

    private fun pararEscuta() {
        val s = servico ?: return
        servico = null
        runCatching { s.stop() }; runCatching { s.shutdown() }
        js("prismo-voz-escuta", JSONObject().put("on", false))
    }

    // ───────────────────── o pedido (fala → texto) ─────────────────────

    fun ouvir() {
        main.post {
            if (!temMic()) { js("prismo-voz-texto", JSONObject().put("erro", "mic")); return@post }
            if (!SpeechRecognizer.isRecognitionAvailable(act)) { js("prismo-voz-texto", JSONObject().put("erro", "indisponivel")); return@post }
            pararEscuta(); runCatching { tts?.stop() }
            pararReconhecedor()
            val r = SpeechRecognizer.createSpeechRecognizer(act)
            reconhecedor = r
            r.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) { js("prismo-voz-ouvindo", JSONObject().put("on", true)) }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() { js("prismo-voz-ouvindo", JSONObject().put("on", false)) }
                override fun onError(e: Int) {
                    pararReconhecedor()
                    val q = when (e) { SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "nada"
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "rede"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "mic"; else -> "erro" }
                    js("prismo-voz-texto", JSONObject().put("erro", q))
                }
                override fun onResults(b: Bundle?) {
                    val l = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    pararReconhecedor()
                    js("prismo-voz-texto", JSONObject().put("texto", l?.firstOrNull() ?: ""))
                }
                override fun onPartialResults(b: Bundle?) {
                    val l = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    js("prismo-voz-parcial", JSONObject().put("texto", l?.firstOrNull() ?: ""))
                }
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            runCatching { r.startListening(i) }.onFailure { js("prismo-voz-texto", JSONObject().put("erro", "erro")) }
        }
    }

    fun pararOuvir() { main.post { pararReconhecedor(); js("prismo-voz-ouvindo", JSONObject().put("on", false)) } }

    private fun pararReconhecedor() {
        val r = reconhecedor ?: return
        reconhecedor = null
        runCatching { r.cancel() }; runCatching { r.destroy() }
    }

    // ───────────────────── a resposta falada ─────────────────────

    fun falar(texto: String, id: String) {
        main.post {
            val t = tts
            if (t == null) {
                tts = TextToSpeech(act.applicationContext) { st ->
                    ttsPronto = st == TextToSpeech.SUCCESS
                    if (ttsPronto) {
                        runCatching { tts?.language = Locale("pt", "BR") }
                        // a voz mais natural que o celular tiver em português do Brasil
                        runCatching {
                            val melhor = tts?.voices?.filter { v -> v.locale.language == "pt" && (v.locale.country == "BR" || v.locale.country.isEmpty()) && !v.features.contains("notInstalled") }
                                ?.maxWithOrNull(compareBy({ it.quality }, { if (it.isNetworkConnectionRequired) 0 else 1 }))
                            if (melhor != null) tts?.voice = melhor
                        }
                        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(u: String?) {}
                            override fun onDone(u: String?) { js("prismo-voz-falou", JSONObject().put("id", u ?: "")) }
                            @Deprecated("API antiga") override fun onError(u: String?) { js("prismo-voz-falou", JSONObject().put("id", u ?: "")) }
                        })
                        falar(texto, id)
                    } else js("prismo-voz-falou", JSONObject().put("id", id).put("erro", true))
                }
                return@post
            }
            if (!ttsPronto) { js("prismo-voz-falou", JSONObject().put("id", id).put("erro", true)); return@post }
            pararEscuta()
            t.speak(texto.take(400), TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    fun calar() { main.post { runCatching { tts?.stop() }; pararAudio() } }

    // ───────────────────── voz humanizada (áudio pronto, vindo do servidor) ─────────────────────
    private var player: android.media.MediaPlayer? = null
    private fun pararAudio() { val p = player ?: return; player = null; runCatching { p.stop() }; runCatching { p.release() } }

    fun tocar(b64: String, id: String) {
        main.post {
            pararAudio(); runCatching { tts?.stop() }
            val ok = runCatching {
                val f = File(act.cacheDir, "voz_resposta.mp3")
                f.writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                pararEscuta()
                val mp = android.media.MediaPlayer()
                mp.setDataSource(f.absolutePath)
                mp.setOnCompletionListener { if (player === mp) pararAudio(); js("prismo-voz-falou", JSONObject().put("id", id)) }
                mp.setOnErrorListener { _, _, _ -> if (player === mp) pararAudio(); js("prismo-voz-falou", JSONObject().put("id", id).put("erro", true)); true }
                mp.prepare(); mp.start()
                player = mp
            }.isSuccess
            if (!ok) js("prismo-voz-falou", JSONObject().put("id", id).put("erro", true))
        }
    }

    fun pedirMic() {
        if (Build.VERSION.SDK_INT >= 23) main.post { runCatching { act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC) } }
    }

    fun micRespondeu() {
        js("prismo-voz-mic", JSONObject().put("ok", temMic()))
        main.postDelayed({ retomar() }, 300)
    }
}
