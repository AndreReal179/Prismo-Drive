package com.prismo.drive

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Bloqueio do app da conta (Configurações › Segurança): biometria OU PIN de 4 a 6 dígitos.
 * O PIN nunca é guardado: só um hash PBKDF2 com sal, no armazenamento privado do app.
 * Depois de 5 PINs errados o bloqueio exige entrar de novo com a senha da conta.
 */
object Seguranca {
    private const val PREFS = "prismo_seguranca"
    private const val MAX_ERROS = 5

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** "pin", "bio" ou "" (desligado). */
    fun metodo(ctx: Context): String = sp(ctx).getString("metodo", "") ?: ""

    fun estado(ctx: Context): String {
        val s = sp(ctx)
        return JSONObject()
            .put("metodo", metodo(ctx))
            .put("temPin", !s.getString("hash", null).isNullOrEmpty())
            .put("tamPin", s.getInt("tam", 0))
            .put("erros", s.getInt("erros", 0))
            .put("maxErros", MAX_ERROS)
            .put("bio", bioDisponivel(ctx))
            .toString()
    }

    private fun hash(pin: String, sal: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), sal, 60000, 256)
        val k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return k.joinToString("") { "%02x".format(it) }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun deHex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Grava o PIN (4 a 6 números) e liga o bloqueio por PIN. */
    fun definirPin(ctx: Context, pin: String?): Boolean {
        if (pin == null || !Regex("^\\d{4,6}$").matches(pin)) return false
        val sal = ByteArray(16).also { SecureRandom().nextBytes(it) }
        sp(ctx).edit()
            .putString("sal", hex(sal))
            .putString("hash", hash(pin, sal))
            .putInt("tam", pin.length)
            .putInt("erros", 0)
            .putString("metodo", "pin")
            .apply()
        return true
    }

    /** "ok", "erro:<restantes>" ou "bloqueado" (errou demais: precisa da senha da conta). */
    fun conferirPin(ctx: Context, pin: String?): String {
        val s = sp(ctx)
        val h = s.getString("hash", null) ?: return "bloqueado"
        val sal = s.getString("sal", null) ?: return "bloqueado"
        val erros = s.getInt("erros", 0)
        if (erros >= MAX_ERROS) return "bloqueado"
        if (pin != null && hash(pin, deHex(sal)) == h) {
            s.edit().putInt("erros", 0).apply()
            return "ok"
        }
        val n = erros + 1
        s.edit().putInt("erros", n).apply()
        return if (n >= MAX_ERROS) "bloqueado" else "erro:" + (MAX_ERROS - n)
    }

    /** Liga a biometria (o PIN, se existir, é apagado: o cliente escolhe um dos dois). */
    fun usarBiometria(ctx: Context): Boolean {
        if (!bioDisponivel(ctx)) return false
        sp(ctx).edit().remove("hash").remove("sal").remove("tam").putInt("erros", 0).putString("metodo", "bio").apply()
        return true
    }

    /** Desliga o bloqueio e apaga o PIN (ao sair/excluir a conta ou desligar). */
    fun limpar(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }

    fun bioDisponivel(ctx: Context): Boolean = try {
        when {
            Build.VERSION.SDK_INT >= 30 -> {
                val bm = ctx.getSystemService(BiometricManager::class.java)
                bm != null && bm.canAuthenticate(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
                ) == BiometricManager.BIOMETRIC_SUCCESS
            }
            Build.VERSION.SDK_INT == 29 -> {
                val bm = ctx.getSystemService(BiometricManager::class.java)
                @Suppress("DEPRECATION")
                bm != null && bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
            }
            Build.VERSION.SDK_INT == 28 -> {
                @Suppress("DEPRECATION")
                val fm = ctx.getSystemService(Context.FINGERPRINT_SERVICE) as? android.hardware.fingerprint.FingerprintManager
                ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) && fm != null && fm.isHardwareDetected && fm.hasEnrolledFingerprints()
            }
            else -> false
        }
    } catch (_: Throwable) {
        false
    }

    /** Abre a digital / rosto do Android. Resultado no evento 'prismo-bio' da página: {ok} ou {ok:false,erro,msg}. */
    fun autenticar(activity: MainActivity, titulo: String) {
        if (Build.VERSION.SDK_INT < 28 || !bioDisponivel(activity)) {
            evento(activity, JSONObject().put("ok", false).put("erro", "indisponivel").put("msg", "Biometria indisponível neste celular"))
            return
        }
        try {
            val exec = activity.mainExecutor
            val b = BiometricPrompt.Builder(activity)
                .setTitle(titulo)
                .setSubtitle("Prismo Drive")
                .setNegativeButton("Cancelar", exec) { _, _ ->
                    evento(activity, JSONObject().put("ok", false).put("erro", "cancelado").put("msg", ""))
                }
            if (Build.VERSION.SDK_INT >= 30) {
                b.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
                )
            }
            if (Build.VERSION.SDK_INT >= 29) b.setConfirmationRequired(false)
            b.build().authenticate(CancellationSignal(), exec, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    evento(activity, JSONObject().put("ok", true))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    val cancel = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    evento(activity, JSONObject().put("ok", false)
                        .put("erro", if (cancel) "cancelado" else "falhou")
                        .put("msg", errString?.toString() ?: ""))
                }
            })
        } catch (e: Throwable) {
            evento(activity, JSONObject().put("ok", false).put("erro", "falhou").put("msg", "Não foi possível abrir a biometria"))
        }
    }

    private fun evento(activity: MainActivity, o: JSONObject) {
        activity.rodarJs("try{window.dispatchEvent(new CustomEvent('prismo-bio',{detail:" + o.toString() + "}));}catch(e){}")
    }
}
