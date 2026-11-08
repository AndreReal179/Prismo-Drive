package com.prismo.drive.rota

import com.prismo.drive.R
import com.prismo.drive.BuildConfig

import android.content.Context

class Prefs(ctx: Context) {
    private val app: Context = ctx.applicationContext ?: ctx

    /**
     * O serviço roda num processo separado (":rotas") para não morrer quando o Prismo Drive é
     * fechado. MODE_MULTI_PROCESS faz cada leitura conferir se o outro processo mudou o arquivo,
     * então o que você ajusta na tela vale na hora para o botão flutuante e os selos.
     */
    @Suppress("DEPRECATION")
    private val sp get() = app.getSharedPreferences("rotaguru", Context.MODE_MULTI_PROCESS)

    /** Lança cada "rota concluída" do iFood (valor + bônus) como "Recebimento iFood". */
    var ifoodSaldoAuto: Boolean
        get() = sp.getBoolean("ifoodSaldoAuto", true)
        set(v) { sp.edit().putBoolean("ifoodSaldoAuto", v).apply() }

    /** Pacote do app de entregas visto mostrando "Rotas disponíveis" (iFood). */
    var ifoodPkg: String
        get() = sp.getString("ifoodPkg", "") ?: ""
        set(v) { if (v != ifoodPkg) sp.edit().putString("ifoodPkg", v).apply() }

    /** Pacote do app de corridas visto mostrando ofertas de corrida (InDrive). */
    var indrivePkg: String
        get() = sp.getString("indrivePkg", "") ?: ""
        set(v) { if (v != indrivePkg) sp.edit().putString("indrivePkg", v).apply() }

    /** R$/km a partir do qual a rota é "Médio" */
    var medio: Float
        get() = sp.getFloat("medio", 1.5f)
        set(v) { sp.edit().putFloat("medio", v).apply() }

    /** R$/km a partir do qual a rota é "Ótimo" */
    var otimo: Float
        get() = sp.getFloat("otimo", 2.0f)
        set(v) { sp.edit().putFloat("otimo", v).apply() }

    /** iFood (Rotas disponíveis): R$/km a partir do qual a rota é "Médio" (começa igual ao do InDrive) */
    var ifoodMedio: Float
        get() = sp.getFloat("ifood_medio", sp.getFloat("medio", 1.5f))
        set(v) { sp.edit().putFloat("ifood_medio", v).apply() }

    /** iFood: R$/km a partir do qual a rota é "Ótimo" */
    var ifoodOtimo: Float
        get() = sp.getFloat("ifood_otimo", sp.getFloat("otimo", 2.0f))
        set(v) { sp.edit().putFloat("ifood_otimo", v).apply() }

    /** iFood: valor bruto mínimo POR PEDIDO (R$). Abaixo disso a rota vira RUIM. 0 = sem mínimo */
    var ifoodMinPerOrder: Float
        get() = sp.getFloat("ifood_minPedido", 0f)
        set(v) { sp.edit().putFloat("ifood_minPedido", v).apply() }

    /** Soma a distância até o passageiro no cálculo (recomendado) */
    var includePickup: Boolean
        get() = sp.getBoolean("pickup", true)
        set(v) { sp.edit().putBoolean("pickup", v).apply() }

    // ---- Alerta de rota nova (som + vibração)
    var alertOn: Boolean
        get() = sp.getBoolean("alertOn", true)
        set(v) { sp.edit().putBoolean("alertOn", v).apply() }

    /** 0 = sem som, 1..4 = sons (ver RouteAlert.SOUNDS) */
    var alertSound: Int
        get() = sp.getInt("alertSound", 1)
        set(v) { sp.edit().putInt("alertSound", v).apply() }

    /** 0 = sem vibração, 1..5 = tipos (ver RouteAlert.VIBES) */
    var alertVibe: Int
        get() = sp.getInt("alertVibe", 2)
        set(v) { sp.edit().putInt("alertVibe", v).apply() }

    /** Aceita sozinho as rotas classificadas como Ótimo */
    var autoAccept: Boolean
        get() = sp.getBoolean("auto", false)
        set(v) { sp.edit().putBoolean("auto", v).apply() }

    /** Nota mínima do passageiro para o aceite automático */
    var minRating: Float
        get() = sp.getFloat("minRating", 4.7f)
        set(v) { sp.edit().putFloat("minRating", v).apply() }

    /** Preço da gasolina (R$ por litro) */
    var fuelPrice: Float
        get() = sp.getFloat("fuelPrice", 6.0f)
        set(v) { sp.edit().putFloat("fuelPrice", v).apply() }

    /** Consumo do carro (km por litro) */
    var kmPerLiter: Float
        get() = sp.getFloat("kmPerLiter", 10.0f)
        set(v) { sp.edit().putFloat("kmPerLiter", v).apply() }

    /** R$/km mínimo: abaixo disso o app sugere um valor para pedir ao passageiro */
    var minRpk: Float
        get() = sp.getFloat("minRpk", 1.7f)
        set(v) { sp.edit().putFloat("minRpk", v).apply() }

    /** Aceite automático: só aceita corridas com R$/km (total, busca + viagem) a partir deste valor */
    var autoMinRpk: Float
        get() = sp.getFloat("autoMinRpk", 2.0f)
        set(v) { sp.edit().putFloat("autoMinRpk", v).apply() }

    /** Aceite automático: valor bruto mínimo da corrida (R$). 0 = sem mínimo */
    var autoMinPrice: Float
        get() = sp.getFloat("autoMinPrice", 0f)
        set(v) { sp.edit().putFloat("autoMinPrice", v).apply() }

    /** Velocidade média (km/h) usada p/ estimar o tempo quando o app de corridas não mostra */
    var avgSpeed: Float
        get() = sp.getFloat("avgSpeed", 35f)
        set(v) { sp.edit().putFloat("avgSpeed", v).apply() }

    /** Posição do botão flutuante (-1 = padrão) */
    var fabX: Int
        get() = sp.getInt("fabX", -1)
        set(v) { sp.edit().putInt("fabX", v).apply() }
    var fabY: Int
        get() = sp.getInt("fabY", -1)
        set(v) { sp.edit().putInt("fabY", v).apply() }

    /** Pacotes dos apps de corridas/rotas (descobertos ao ler a primeira oferta de cada um), separados por "|" */
    var ridePkg: String
        get() = sp.getString("ridePkg", "") ?: ""
        set(v) { sp.edit().putString("ridePkg", v).apply() }

    val ridePkgs: Set<String>
        get() = ridePkg.split("|").filter { it.isNotEmpty() }.toSet()

    fun addRidePkg(p: String) {
        val cur = ridePkgs
        if (p.isNotEmpty() && p !in cur) ridePkg = (cur + p).joinToString("|")
    }

    /** Busca até este valor (km) = verde */
    var pickupGood: Float
        get() = sp.getFloat("pickupGood", 2.0f)
        set(v) { sp.edit().putFloat("pickupGood", v).apply() }

    /** Busca a partir deste valor (km) = vermelho (entre os dois = laranja) */
    var pickupBad: Float
        get() = sp.getFloat("pickupBad", 4.0f)
        set(v) { sp.edit().putFloat("pickupBad", v).apply() }

    // ---- Relatório: faixas dos 3 gráficos (baixo < médio ≤ ... < alto)
    var repPriceMid: Float
        get() = sp.getFloat("repPriceMid", 20f)
        set(v) { sp.edit().putFloat("repPriceMid", v).apply() }
    var repPriceHigh: Float
        get() = sp.getFloat("repPriceHigh", 40f)
        set(v) { sp.edit().putFloat("repPriceHigh", v).apply() }
    var repRpkMid: Float
        get() = sp.getFloat("repRpkMid", 2.0f)
        set(v) { sp.edit().putFloat("repRpkMid", v).apply() }
    var repRpkHigh: Float
        get() = sp.getFloat("repRpkHigh", 3.5f)
        set(v) { sp.edit().putFloat("repRpkHigh", v).apply() }
    var repPerMid: Float
        get() = sp.getFloat("repPerMid", 7f)
        set(v) { sp.edit().putFloat("repPerMid", v).apply() }
    var repPerHigh: Float
        get() = sp.getFloat("repPerHigh", 10f)
        set(v) { sp.edit().putFloat("repPerHigh", v).apply() }
}
