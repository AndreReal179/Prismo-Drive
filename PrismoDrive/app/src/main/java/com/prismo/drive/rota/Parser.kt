package com.prismo.drive.rota

import com.prismo.drive.R
import com.prismo.drive.BuildConfig

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lê a árvore de acessibilidade do app de corridas e extrai as ofertas.
 *
 * Tela de lista  : vários cards, cada um com "R$ x/km", "~y km", "R$ preço".
 * Tela de detalhe: botão "Aceitar por R$ X" + balões do mapa (tempo / km).
 */
object Parser {

    private class T(val text: String, val rect: Rect, val node: AccessibilityNodeInfo)

    private val RPK = Regex("""R\$\s*([\d.,]+)\s*/\s*km""")
    private val PICK = Regex("""~\s*([\d.,]+)\s*km""")
    private val PRICE = Regex("""R\$\s*([\d.,]+)""")
    private val ACCEPT = Regex("""Aceitar por\s*R\$\s*([\d.,]+)""", RegexOption.IGNORE_CASE)
    private val BUBBLE = Regex("""(\d+)\s*min\D{0,6}([\d.,]+)\s*km""", RegexOption.DOT_MATCHES_ALL)
    private val ONLY_KM = Regex("""^\s*([\d.,]+)\s*km\s*$""")
    private val RATING = Regex("""^\s*(\d[.,]\d{1,2})\s*(\(\d+\))?\s*$""")
    // layout novo do InDrive: "★ 4.34 (259)" na mesma linha do nome, ou "4.34" sozinho
    private val RATING_STAR = Regex("""(?:★|☆|⭐)\s*(\d[.,]\d{1,2})|(?:^|\s)(\d[.,]\d{1,2})\s*\(\d+\)""")

    fun num(s: String): Double? {
        var t = s.trim().trimEnd('.', ',')
        if (t.contains(',')) t = t.replace(".", "").replace(',', '.')
        return t.toDoubleOrNull()
    }

    // "R$ 11,08 • 2,96 km em 29 min"  (também "1 h 05 min")
    private val ROUTE_HEAD = Regex("""R\$\s*([\d.,]+)\s*[•·|]\s*([\d.,]+)\s*km\s*em\s*(?:(\d+)\s*h\s*)?(?:(\d+)\s*min)?""", RegexOption.IGNORE_CASE)
    // "Coleta (0.7 km)", "Entrega (1.9 km)" e também numeradas: "Entrega 3 (1.2 km)"
    private val ROUTE_LEG = Regex("""(Coleta|Entrega)s?\s*(\d+)?\s*\(\s*([\d.,]+)\s*km\s*\)""", RegexOption.IGNORE_CASE)
    // detalhe da rota: "1 coleta, 1 entrega • 3,28 km • 15 min"
    private val ROUTE_DETAIL = Regex("""(\d+)\s*coletas?\s*[,·•e]?\s*(\d+)\s*entregas?\s*[•·|,]\s*([\d.,]+)\s*km(?:\s*[•·|,]\s*(?:(\d+)\s*h\s*)?(?:(\d+)\s*min)?)?""", RegexOption.IGNORE_CASE)
    private val NUM_COLETA = Regex("""Coleta\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val NUM_ENTREGA = Regex("""Entrega\s+(\d+)""", RegexOption.IGNORE_CASE)
    private val POP_DIST = Regex("""Dist[âa]ncia\s+total\s+([\d.,]+)\s*km""", RegexOption.IGNORE_CASE)
    private val POP_TIME = Regex("""Tempo\s+aproximado(?:\s+de\s+rota)?\s+(?:(\d+)\s*h)?\s*(?:(\d+)\s*min)?""", RegexOption.IGNORE_CASE)
    private val POP_PRICE = Regex("""^\s*R\$\s*([\d.,]+)\s*$""")
    private val POP_STOPS = Regex("""(\d+)\s*Paradas?""", RegexOption.IGNORE_CASE)
    private val ONLY_MIN = Regex("""^\s*(\d+)\s*min\.?\s*$""", RegexOption.IGNORE_CASE)

    /** hidden = true: inclui também nós "não visíveis" e lê text + contentDescription (balões do mapa). */
    private fun flatten(n: AccessibilityNodeInfo?, out: MutableList<T>, depth: Int = 0, hidden: Boolean = false) {
        if (n == null || depth > 40) return
        if (hidden || n.isVisibleToUser) {
            val texts = if (hidden) listOfNotNull(n.text, n.contentDescription) else listOfNotNull(n.text ?: n.contentDescription)
            val r = Rect()
            n.getBoundsInScreen(r)
            for (c in texts.map { it.toString().trim() }.filter { it.isNotBlank() }.distinct()) {
                out.add(T(c, r, n))
            }
        }
        for (i in 0 until n.childCount) flatten(n.getChild(i), out, depth + 1, hidden)
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        var guard = 0
        while (cur != null && guard++ < 10) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    /** Primeiro "R$ n" que NÃO seja seguido de "/km". */
    private fun firstPrice(text: String): Double? {
        for (m in PRICE.findAll(text)) {
            val after = text.substring(m.range.last + 1).trimStart()
            if (after.startsWith("/km") || after.startsWith("/ km")) continue
            return num(m.groupValues[1])
        }
        return null
    }

    fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

    /** "2 coletas · 3 entregas" (null se não souber nenhum dos dois). */
    fun stopsText(coletas: Int, entregas: Int): String? = listOf(
        if (coletas > 0) plural(coletas, "coleta", "coletas") else null,
        if (entregas > 0) plural(entregas, "entrega", "entregas") else null
    ).filterNotNull().joinToString(" · ").ifEmpty { null }

    /** Maior "Coleta N" / "Entrega N" em qualquer texto (cards do carrossel, balões do mapa…). */
    private fun numbered(texts: List<T>): Pair<Int, Int> {
        var c = 0
        var e = 0
        for (t in texts) {
            NUM_COLETA.findAll(t.text).forEach { m -> m.groupValues[1].toIntOrNull()?.let { if (it in 1..30) c = maxOf(c, it) } }
            NUM_ENTREGA.findAll(t.text).forEach { m -> m.groupValues[1].toIntOrNull()?.let { if (it in 1..30) e = maxOf(e, it) } }
        }
        return Pair(c, e)
    }

    fun parse(root: AccessibilityNodeInfo): List<Offer> {
        val items = mutableListOf<T>()
        flatten(root, items)
        if (items.isEmpty()) return emptyList()
        // fonte ampla (inclui itens fora da tela e contentDescription): só monta se precisar
        val wideList by lazy { mutableListOf<T>().also { flatten(root, it, hidden = true) } }

        // ---------- Tela de detalhe ----------
        val acc = items.firstOrNull { ACCEPT.containsMatchIn(it.text) }
        if (acc != null) {
            val price = num(ACCEPT.find(acc.text)!!.groupValues[1]) ?: return emptyList()
            val all = items.joinToString("\n") { it.text }
            val rpk = RPK.find(all)?.let { num(it.groupValues[1]) }
            var pickup = PICK.find(all)?.let { num(it.groupValues[1]) }

            // fonte ampla: inclui nós não visíveis e contentDescription (balões do mapa)
            val wide = mutableListOf<T>()
            flatten(root, wide, hidden = true)
            val src = if (wide.isEmpty()) items else wide
            val screenW = Rect().also { root.getBoundsInScreen(it) }.width().coerceAtLeast(1)

            // balões do mapa: "6 min 2,1 km" e "38 min 37,7 km"
            val bubbles = src.flatMap { t ->
                BUBBLE.findAll(t.text).mapNotNull { m ->
                    val min = m.groupValues[1].toDoubleOrNull()
                    val km = num(m.groupValues[2])
                    if (min != null && km != null) Pair(min, km) else null
                }.toList()
            }
            var tripKm: Double? = null
            var tripMin: Double? = null
            var pickupMin: Double? = null
            if (bubbles.isNotEmpty()) {
                val trip = bubbles.maxByOrNull { it.second }!!
                tripKm = trip.second
                tripMin = trip.first
                val other = bubbles.firstOrNull { it !== trip }
                if (other != null) {
                    pickupMin = other.first
                    // km exato da rota até o passageiro (mapa) tem prioridade sobre o "~x km" arredondado
                    pickup = other.second
                }
            }

            // balões separados em "N min" e "x km" (ou só o km, como o balão azul da busca)
            if (tripKm == null) {
                var kms = src.mapNotNull { ONLY_KM.find(it.text)?.let { m -> num(m.groupValues[1]) } }
                if (pickup != null) kms = kms.filter { Math.abs(it - pickup!!) > 0.05 }
                if (kms.isNotEmpty()) tripKm = kms.maxOrNull()
            }
            // "9 min." na coluna esquerda do card = tempo até o passageiro; o resto = viagem
            val mins = src.mapNotNull { t ->
                ONLY_MIN.find(t.text)?.let { m -> m.groupValues[1].toDoubleOrNull()?.let { v -> Pair(v, t.rect.left < screenW * 0.3) } }
            }
            if (pickupMin == null) pickupMin = mins.firstOrNull { it.second }?.first
            if (tripMin == null) tripMin = mins.filter { !it.second }.maxOfOrNull { it.first }
            val rating = items.firstNotNullOfOrNull { RATING.find(it.text)?.let { m -> num(m.groupValues[1]) } }
            return listOf(
                Offer(price, rpk, pickup, tripKm, tripMin, pickupMin, rating, acceptNode = acc.node)
            )
        }

        // ---------- Rotas disponíveis (coletas + entregas) ----------
        val routes = parseRoutes(items) { wideList }
        if (routes.isNotEmpty()) return routes

        // ---------- Detalhe da rota ("R$ 10,83 / loja / 1 coleta, 1 entrega • 3,28 km • 15 min") ----------
        val detail = parseRouteDetail(items) { wideList }
        if (detail.isNotEmpty()) return detail

        // ---------- Rota oferecida (popup "R$ 39,42 / Distância total / Tempo aproximado / Aceitar") ----------
        val popup = parseOfferPopup(items) { wideList }
        if (popup.isNotEmpty()) return popup
        // a janela ainda está animando e os textos vêm como "não visíveis": tenta com a fonte ampla
        if (items.any { it.text.startsWith("Aceitar", true) || it.text.startsWith("Rejeitar", true) }) {
            val again = parseOfferPopup(wideList.filter { !it.rect.isEmpty }) { wideList }
            if (again.isNotEmpty()) return again
        }

        // ---------- Tela de lista ----------
        val heads = items.filter { RPK.containsMatchIn(it.text) }.sortedBy { it.rect.top }
        val offers = mutableListOf<Offer>()
        heads.forEachIndexed { i, h ->
            // o preço grande ("R$20") fica na mesma linha do "R$ 1,1/km", mas começa um pouco mais acima
            val folga = maxOf(40, h.rect.height())
            val prevBottom = heads.getOrNull(i - 1)?.rect?.bottom ?: Int.MIN_VALUE
            val top = maxOf(h.rect.top - folga, prevBottom + 1)
            val bottom = heads.getOrNull(i + 1)?.let { it.rect.top - maxOf(40, it.rect.height()) } ?: Int.MAX_VALUE
            val region = items.filter { it.rect.top in top until bottom || (it.rect.top < top && it.rect.bottom > h.rect.top) }
                .sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
            val joined = region.joinToString("\n") { it.text }

            val price = firstPrice(joined) ?: return@forEachIndexed
            val rpk = RPK.find(joined)?.let { num(it.groupValues[1]) }
            // layout antigo: "~2,7 km" até o passageiro · layout novo: "2,7 km" (embarque) e "15,9 km" (destino)
            var pickup = PICK.find(joined)?.let { num(it.groupValues[1]) }
            var trip: Double? = null
            val kms = region.filter { it.rect.top > h.rect.top - 2 }
                .mapNotNull { t -> ONLY_KM.find(t.text)?.let { m -> num(m.groupValues[1]) } }
            if (pickup == null && kms.size >= 2) { pickup = kms[0]; trip = kms[1] }
            else if (pickup != null && kms.isNotEmpty()) trip = kms.filter { Math.abs(it - pickup!!) > 0.05 }.maxOrNull()
            else if (pickup == null && kms.size == 1) pickup = kms[0]
            val rating = region.firstNotNullOfOrNull { RATING.find(it.text)?.let { m -> num(m.groupValues[1]) } }
                ?: region.firstNotNullOfOrNull { t -> RATING_STAR.find(t.text)?.let { m -> num(m.groupValues[1].ifEmpty { m.groupValues[2] }) } }

            // ponto de toque de reserva: meio do card, a partir da coluna do texto
            val cardBottom = region.maxOfOrNull { it.rect.bottom } ?: (h.rect.bottom + 200)
            offers.add(
                Offer(
                    price, rpk, pickup, trip, null, null, rating,
                    cardNode = clickableAncestor(h.node),
                    tapX = h.rect.left + 200,
                    tapY = (h.rect.top + cardBottom) / 2,
                    headTop = h.rect.top,
                    headBottom = h.rect.bottom
                )
            )
        }
        return offers
    }

    /** Agrupa os textos da mesma linha (mesmo y) e junta da esquerda p/ a direita. */
    private fun lines(items: List<T>): List<Triple<String, Int, Int>> {
        val sorted = items.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        val out = mutableListOf<Triple<String, Int, Int>>()
        var cur = mutableListOf<T>()
        fun flush() {
            if (cur.isEmpty()) return
            val txt = cur.joinToString(" ") { it.text }.replace('\u00A0', ' ')
            out.add(Triple(txt, cur.minOf { it.rect.top }, cur.maxOf { it.rect.bottom }))
            cur = mutableListOf()
        }
        for (t in sorted) {
            if (cur.isNotEmpty() && Math.abs(t.rect.top - cur[0].rect.top) > 18) flush()
            cur.add(t)
        }
        flush()
        return out
    }

    /**
     * Tela "ROTAS DISPONÍVEIS": cada card tem "R$ preço • x km em N min" e várias linhas
     * "Coleta (a km)" / "Entrega (b km)". Km total = o maior entre o km do cabeçalho e a soma das etapas.
     */
    private fun parseRoutes(items: List<T>, wide: () -> List<T>): List<Offer> {
        val ls = lines(items)
        val heads = ls.filter { ROUTE_HEAD.containsMatchIn(it.first) }
        if (heads.isEmpty()) return emptyList()
        val offers = mutableListOf<Offer>()
        heads.forEachIndexed { i, h ->
            val m = ROUTE_HEAD.find(h.first)!!
            val price = num(m.groupValues[1]) ?: return@forEachIndexed
            val headKm = num(m.groupValues[2]) ?: return@forEachIndexed
            val hh = m.groupValues[3].toDoubleOrNull() ?: 0.0
            val mm = m.groupValues[4].toDoubleOrNull() ?: 0.0
            val minutes = hh * 60 + mm
            val bottom = heads.getOrNull(i + 1)?.second ?: Int.MAX_VALUE
            var legKm = 0.0
            var coletas = 0
            var entregas = 0
            var firstLegTop: Int? = null
            for (l in ls) {
                if (l.second <= h.second || l.second >= bottom) continue
                for (lm in ROUTE_LEG.findAll(l.first)) {
                    val km = num(lm.groupValues[3]) ?: continue
                    legKm += km
                    if (firstLegTop == null) firstLegTop = l.second
                    val n = lm.groupValues[2].toIntOrNull() ?: 0
                    if (lm.groupValues[1].equals("Coleta", true)) coletas = maxOf(coletas + 1, n)
                    else entregas = maxOf(entregas + 1, n)
                }
            }
            // um card só: as etapas que ficaram fora da tela (lista rolável dentro do card) também contam
            if (heads.size == 1) {
                var wc = 0
                var we = 0
                var wKm = 0.0
                val seen = HashSet<String>()
                for (t in wide()) {
                    for (lm in ROUTE_LEG.findAll(t.text)) {
                        if (!seen.add(lm.value.lowercase())) continue
                        val km = num(lm.groupValues[3]) ?: continue
                        wKm += km
                        val n = lm.groupValues[2].toIntOrNull() ?: 0
                        if (lm.groupValues[1].equals("Coleta", true)) wc = maxOf(wc + 1, n) else we = maxOf(we + 1, n)
                    }
                }
                coletas = maxOf(coletas, wc)
                entregas = maxOf(entregas, we)
                legKm = maxOf(legKm, wKm)
            }
            val total = maxOf(headKm, legKm)
            offers.add(
                Offer(
                    price = price, rpkApp = null, pickupKm = null,
                    tripKm = total, tripMin = if (minutes > 0) minutes else null, pickupMin = if (minutes > 0) 0.0 else null,
                    rating = null,
                    headTop = h.second, headBottom = h.third,
                    route = true, stops = stopsText(coletas, entregas), coletas = coletas, entregas = entregas,
                    // painel em cima do endereço da coleta: o valor e a loja ficam à vista
                    placeTop = firstLegTop ?: (h.third + 4)
                )
            )
        }
        return offers
    }

    /**
     * Detalhe da rota (depois de tocar num card de Rotas disponíveis):
     *   R$ 10,83 / Etanois Bebidas / "1 coleta, 1 entrega • 3,28 km • 15 min" / Coleta (1.4 km) / Entrega (1.8 km) / Gerar Rota
     * O painel fica acima do valor, sem cobri-lo.
     */
    private fun parseRouteDetail(items: List<T>, wide: () -> List<T>): List<Offer> {
        val ls = lines(items)
        val idx = ls.indexOfFirst { ROUTE_DETAIL.containsMatchIn(it.first) }
        if (idx < 0) return emptyList()
        val m = ROUTE_DETAIL.find(ls[idx].first)!!
        var coletas = m.groupValues[1].toIntOrNull() ?: 0
        var entregas = m.groupValues[2].toIntOrNull() ?: 0
        val headKm = num(m.groupValues[3]) ?: return emptyList()
        val minutes = (m.groupValues[4].toDoubleOrNull() ?: 0.0) * 60 + (m.groupValues[5].toDoubleOrNull() ?: 0.0)
        // preço: a linha "R$ x" mais próxima acima
        val priceLine = (idx - 1 downTo maxOf(0, idx - 4)).map { ls[it] }
            .firstOrNull { POP_PRICE.containsMatchIn(it.first) || PRICE.containsMatchIn(it.first) } ?: return emptyList()
        val price = firstPrice(priceLine.first) ?: return emptyList()
        var legKm = 0.0
        for (l in ls.drop(idx + 1)) for (lm in ROUTE_LEG.findAll(l.first)) {
            legKm += num(lm.groupValues[3]) ?: 0.0
            val n = lm.groupValues[2].toIntOrNull() ?: 0
            if (lm.groupValues[1].equals("Coleta", true)) coletas = maxOf(coletas, n) else entregas = maxOf(entregas, n)
        }
        val (nc, ne) = numbered(wide())
        coletas = maxOf(coletas, nc)
        entregas = maxOf(entregas, ne)
        return listOf(
            Offer(
                price = price, rpkApp = null, pickupKm = null,
                tripKm = maxOf(headKm, legKm), tripMin = if (minutes > 0) minutes else null,
                pickupMin = if (minutes > 0) 0.0 else null,
                rating = null,
                headTop = priceLine.second, headBottom = priceLine.third,
                route = true, stops = stopsText(coletas, entregas), coletas = coletas, entregas = entregas,
                placeAbove = priceLine.second
            )
        )
    }

    /**
     * Popup de rota oferecida: preço grande, "Distância total x km", "Tempo aproximado de rota 1h 59 min"
     * e botões Rejeitar / Aceitar. O painel fica no lugar da linha do preço (não cobre os botões).
     */
    private fun parseOfferPopup(items: List<T>, wide: () -> List<T>): List<Offer> {
        if (items.none { it.text.startsWith("Aceitar", true) || it.text.startsWith("Rejeitar", true) }) return emptyList()
        val all = items.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
            .joinToString("\n") { it.text.replace('\u00A0', ' ') }
        val km = POP_DIST.find(all)?.let { num(it.groupValues[1]) } ?: return emptyList()
        val tm = POP_TIME.find(all)
        val minutes = tm?.let { (it.groupValues[1].toDoubleOrNull() ?: 0.0) * 60 + (it.groupValues[2].toDoubleOrNull() ?: 0.0) } ?: 0.0
        val distNode = items.firstOrNull {
            it.text.contains("Dist", true) && it.text.contains("total", true)
        }
        val prices = items.filter { POP_PRICE.containsMatchIn(it.text) }
        val node = (if (distNode != null) prices.filter { it.rect.top < distNode.rect.top } else prices)
            .maxByOrNull { it.rect.top }
        // valor grande às vezes vem quebrado em 2 textos ("R$" + "19,44"): junta a linha
        val priceLine: Triple<String, Int, Int> = if (node != null) Triple(node.text, node.rect.top, node.rect.bottom)
        else lines(items).filter { POP_PRICE.containsMatchIn(it.first) && (distNode == null || it.second < distNode.rect.top) }
            .maxByOrNull { it.second } ?: return emptyList()
        val price = num(POP_PRICE.find(priceLine.first)!!.groupValues[1]) ?: return emptyList()
        // "Coleta 1", "Entrega 1" ... "Entrega 3": o maior número = quantidade.
        // Procura também fora da tela (cards do carrossel que ainda não apareceram, balões do mapa).
        val (vc, ve) = numbered(items)
        val (wc, we) = numbered(wide())
        val coletas = maxOf(vc, wc)
        val entregas = maxOf(ve, we)
        val stops = if (coletas > 0 || entregas > 0) stopsText(coletas, entregas)
        else POP_STOPS.find(all)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: 0
            if (n > 0) (if (n == 1) "1 parada" else "$n paradas") else null
        }
        return listOf(
            Offer(
                price = price, rpkApp = null, pickupKm = null,
                tripKm = km, tripMin = if (minutes > 0) minutes else null,
                pickupMin = if (minutes > 0) 0.0 else null,
                rating = null,
                headTop = priceLine.second, headBottom = priceLine.third,
                route = true, stops = stops, coletas = coletas, entregas = entregas,
                // painel no vão entre o card da coleta e o card do valor (não cobre o valor)
                placeAbove = priceLine.second
            )
        )
    }
}
