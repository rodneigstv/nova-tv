package com.exemplo.novatv

import android.content.Context

/** Paleta: 3 neutros + 1 destaque (vermelho vivo do São Paulo F.C.). Variações só por transparência. */
object Cores {
    const val VERMELHO = 0xFFFF1414.toInt() // destaque: foco de controles, item atual, chamadas
    const val FUNDO = 0xFF070707.toInt()    // neutro 1: fundo da tela
    const val CARTAO = 0xFF1A1A1A.toInt()   // neutro 2: cards, pôsteres, painéis, botões
    const val TEXTO = 0xFFF2F2F2.toInt()    // neutro 3: texto, ícones e anel de foco
    const val TEXTO_SUAVE = 0xB3F2F2F2.toInt() // texto secundário (neutro 3 a 70%)

    fun comAlfa(cor: Int, alfa: Int): Int = (cor and 0x00FFFFFF) or (alfa.coerceIn(0, 255) shl 24)
}

/** Serviços da referência, na ordem em que aparecem na tela. */
class Servico(val id: String, val nome: String, val pacotes: List<String>, val cor: Int)

object Servicos {
    val lista = listOf(
        Servico("netflix", "Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient"), 0xFFE50914.toInt()),
        Servico("prime", "Prime Video", listOf("com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient"), 0xFF1A98FF.toInt()),
        Servico("youtube", "YouTube", listOf("com.google.android.youtube.tv", "com.google.android.youtube"), 0xFFFF1E3C.toInt()),
        Servico("disney", "Disney+", listOf("com.disney.disneyplus"), 0xFF2E6BFF.toInt()),
        Servico("max", "Max", listOf("com.wbd.stream", "com.hbo.hbonow", "com.hbo.go"), 0xFF5B4BFF.toInt()),
        Servico("globoplay", "Globoplay", listOf("com.globo.globotv"), 0xFFFF5A1F.toInt()),
        Servico("ytmusic", "YouTube Music", listOf("com.google.android.youtube.tvmusic"), 0xFFFF1E3C.toInt()),
        Servico("spotify", "Spotify", listOf("com.spotify.tv.android"), 0xFF1ED760.toInt()),
        Servico("appletv", "Apple TV", listOf("com.apple.atve.androidtv.appletv"), 0xFFB8C0CC.toInt()),
        Servico("paramount", "Paramount+", listOf("com.cbs.ca", "com.cbs.app", "com.cbs.ott"), 0xFF2F7BFF.toInt()),
        Servico("crunchyroll", "Crunchyroll", listOf("com.crunchyroll.crunchyroid"), 0xFFFF7A1A.toInt()),
        Servico("playstore", "Play Store", listOf("com.android.vending"), 0xFF34A853.toInt()),
        Servico("playgames", "Play Games", listOf("com.google.android.play.games"), 0xFF34A853.toInt()),
        Servico("geforcenow", "GeForce NOW", listOf("com.nvidia.geforcenow"), 0xFF76B900.toInt()),
        Servico("twitch", "Twitch", listOf("tv.twitch.android.app"), 0xFF9146FF.toInt()),
        Servico("googletv", "Google TV", listOf("com.google.android.videos"), 0xFF4285F4.toInt()),
        Servico("tclhome", "TCL Home", listOf("com.tcl.waterfall.overseas"), 0xFFE31E24.toInt()),
        // TV aberta pela antena: abre o app de TV da TCL direto no sintonizador (card com arte própria).
        Servico("tvaberta", "TV Aberta", listOf("com.tcl.tv"), 0xFFFF1414.toInt()),
        Servico("xbox", "Xbox Game Pass", listOf("com.gamepass", "com.microsoft.xcloud", "com.microsoft.xboxone.smartglass"), 0xFF107C10.toInt()),
    )

    /** Cards da página inicial, na ordem pedida pela pessoa. */
    val inicio = listOf("tvaberta", "geforcenow", "playstore", "ytmusic")

    fun porId(id: String): Servico? = lista.firstOrNull { it.id == id }

    private val musica = setOf("com.google.android.youtube.tvmusic", "com.spotify.tv.android", "com.deezer.android.tv", "com.amazon.music.tv")

    fun porPacote(pacote: String): Servico? = lista.firstOrNull { pacote in it.pacotes }
    fun ehMusica(pacote: String) = pacote in musica
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
