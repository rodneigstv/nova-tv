package com.exemplo.novatv

import android.graphics.Typeface

/** Escala única de texto (dp), pensada para leitura a ~3 m numa TV de 50". */
object Tipo {
    const val DISPLAY = 28f   // relógio, título grande
    const val TITULO = 22f    // títulos de página e nome no destaque
    const val SUBTITULO = 16f // títulos de fileira, campos
    const val CORPO = 13f     // texto corrido, botões, rótulos de card
    const val LEGENDA = 11f   // menu, chamadas, datas (mínimo legível a 3 m)
    const val NOTA = 10f      // créditos obrigatórios

    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIO: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val FORTE: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val NUMERO: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
}

/** Espaçamentos (dp). */
object Espaco {
    const val XS = 4f
    const val S = 8f
    const val M = 12f
    const val L = 16f
    const val XL = 24f

    /** Área segura da TV (~5% da tela de 960x540 dp): nada importante fica fora dela. */
    const val SEGURO_H = 48f
    const val SEGURO_V = 27f
}

/** Raios de canto (dp): um para superfícies (cards, pôsteres, painéis) e um para controles. */
object Raio {
    const val SUPERFICIE = 12f
    const val CONTROLE = 8f
}

/** Foco: anel sólido claro + leve aumento, sem desfoque nem sombra. */
object Foco {
    const val ANEL = 3f            // dp
    const val ESCALA = 1.05f       // cards e pôsteres
    const val ESCALA_GRANDE = 1.03f // destaques
    const val ESCALA_ICONE = 1.1f  // botões redondos
    const val TRACO = 2f           // espessura dos ícones de linha (dp)
}

/** Durações de animação (ms): só transformação e opacidade, no máximo 300 ms. */
object Tempo {
    const val FOCO = 120L
    const val TROCA = 250L
    const val DESLIZE = 300L
}
