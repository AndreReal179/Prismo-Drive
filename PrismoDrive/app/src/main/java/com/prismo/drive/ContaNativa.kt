package com.prismo.drive

import android.os.CancellationSignal
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Parte nativa da conta:
 *  - chamadas HTTP ao Supabase feitas pelo Android (a página roda em file://, sem depender de CORS);
 *  - "Entrar com Google" nativo (Credential Manager). O Google não permite login dentro de WebView.
 * As respostas voltam para a página por JavaScript.
 */
object ContaNativa {

    private val pool = Executors.newFixedThreadPool(3)

    /**
     * Requisição ao Supabase. Só aceita o endereço do projeto do app.
     * Resposta: window.__prismoHttp({"id":…,"status":200,"corpo":"…"}). status 0 = sem internet.
     */
    fun http(activity: MainActivity, id: String, metodo: String, url: String, cabecalhos: String?, corpo: String?) {
        if (!url.startsWith(Conta.SUPABASE_URL + "/")) {
            responder(activity, id, -1, "{\"msg\":\"endereço não permitido\"}")
            return
        }
        val m = metodo.uppercase()
        if (m !in setOf("GET", "POST", "PUT", "DELETE")) {
            responder(activity, id, -1, "{\"msg\":\"método não permitido\"}")
            return
        }
        pool.execute {
            var status = 0
            var texto = ""
            var con: HttpURLConnection? = null
            try {
                con = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = m
                    connectTimeout = 15000
                    readTimeout = 30000
                    useCaches = false
                    setRequestProperty("Accept", "application/json")
                    if (!cabecalhos.isNullOrBlank()) {
                        val h = JSONObject(cabecalhos)
                        h.keys().forEach { k -> setRequestProperty(k, h.optString(k)) }
                    }
                }
                if (corpo != null && m != "GET") {
                    con.doOutput = true
                    if (con.getRequestProperty("Content-Type") == null) con.setRequestProperty("Content-Type", "application/json")
                    con.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
                }
                status = con.responseCode
                val ent = if (status >= 400) con.errorStream else con.inputStream
                texto = ent?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            } catch (_: Throwable) {
                status = 0
                texto = ""
            } finally {
                runCatching { con?.disconnect() }
            }
            responder(activity, id, status, texto)
        }
    }

    private fun responder(activity: MainActivity, id: String, status: Int, corpo: String) {
        val r = JSONObject().put("id", id).put("status", status).put("corpo", corpo)
        activity.rodarJs("try{window.__prismoHttp&&window.__prismoHttp(" + r.toString() + ");}catch(e){}")
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /**
     * Abre a escolha de conta do Google. Resultado no evento 'prismo-google' da página:
     * {ok:true,idToken,nonce,nome,email,foto} ou {ok:false,erro:"cancelado"|"sem_conta"|"falhou",msg}.
     * O nonce enviado ao Google é o SHA-256; o Supabase recebe o nonce original para conferir.
     */
    fun entrarGoogle(activity: MainActivity) {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val nonce = bytes.joinToString("") { "%02x".format(it) }
        try {
            val opcao = GetSignInWithGoogleOption.Builder(Conta.GOOGLE_WEB_CLIENT_ID)
                .setNonce(sha256Hex(nonce))
                .build()
            val pedido = GetCredentialRequest.Builder().addCredentialOption(opcao).build()
            CredentialManager.create(activity).getCredentialAsync(
                activity, pedido, CancellationSignal(), pool,
                object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(result: GetCredentialResponse) {
                        val c = result.credential
                        if (c is CustomCredential && c.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                            try {
                                val g = GoogleIdTokenCredential.createFrom(c.data)
                                eventoGoogle(activity, JSONObject()
                                    .put("ok", true)
                                    .put("idToken", g.idToken)
                                    .put("nonce", nonce)
                                    .put("nome", g.displayName ?: "")
                                    .put("email", g.id)
                                    .put("foto", g.profilePictureUri?.toString() ?: ""))
                            } catch (e: Throwable) {
                                eventoGoogle(activity, erro("falhou", "Resposta do Google inválida"))
                            }
                        } else {
                            eventoGoogle(activity, erro("falhou", "Tipo de login inesperado"))
                        }
                    }

                    override fun onError(e: GetCredentialException) {
                        val r = when (e) {
                            is GetCredentialCancellationException -> erro("cancelado", "")
                            is NoCredentialException -> erro("sem_conta", "Nenhuma conta Google neste celular")
                            else -> erro("falhou", e.message ?: "Não foi possível entrar com o Google")
                        }
                        eventoGoogle(activity, r)
                    }
                }
            )
        } catch (e: Throwable) {
            eventoGoogle(activity, erro("falhou", "Login com Google indisponível neste celular"))
        }
    }

    private fun erro(tipo: String, msg: String) = JSONObject().put("ok", false).put("erro", tipo).put("msg", msg)

    private fun eventoGoogle(activity: MainActivity, o: JSONObject) {
        activity.rodarJs("try{window.dispatchEvent(new CustomEvent('prismo-google',{detail:" + o.toString() + "}));}catch(e){}")
    }
}
