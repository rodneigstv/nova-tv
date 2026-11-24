package com.exemplo.novatv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/**
 * Moldura comum de cards, pôsteres, destaques e do monitor: retângulo de cantos arredondados
 * dentro de uma folga (espaço para o aumento do foco) e, no foco, um anel sólido claro.
 * Sem desfoque, sombra ou bitmap extra: o foco aparece no mesmo quadro.
 */
class Vidro(private val ctx: Context) {
    val rect = RectF()
    var raio = 0f
        private set

    /** Folga em volta do card: o aumento do foco cresce dentro dela sem encostar no vizinho. */
    val folga: Float = ctx.dp(Espaco.S)

    private val pAnel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Cores.TEXTO
    }
    private val anel = RectF()

    fun montar(w: Int, h: Int, raio: Float) {
        rect.set(folga, folga, w - folga, h - folga)
        this.raio = raio
        pAnel.strokeWidth = ctx.dp(Foco.ANEL)
        val m = pAnel.strokeWidth / 2f
        anel.set(rect.left + m, rect.top + m, rect.right - m, rect.bottom - m)
    }

    /** Anel de foco (por dentro da borda, para não ser cortado). Chamar depois do conteúdo. */
    fun desenharBorda(c: Canvas, foco: Boolean) {
        if (!foco) return
        val r = raio - pAnel.strokeWidth / 2f
        c.drawRoundRect(anel, r, r, pAnel)
    }
}
