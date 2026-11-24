package com.exemplo.novatv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * Item do menu lateral: ícone em cima, nome embaixo.
 * Foco = pílula vermelha cheia; página aberta = pílula neutra com ícone e nome em vermelho.
 */
class MenuItemView(ctx: Context, val icone: Icone, val nome: String) : View(ctx) {
    var selecionado = false
        set(v) { field = v; invalidate() }
    var acao: (() -> Unit)? = null
    /** Chamado ao receber o foco (menu dinâmico: troca de página sem apertar OK). */
    var aoFocar: (() -> Unit)? = null

    private val pIcone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Tipo.MEDIO
    }
    private val pCaixa = Paint(Paint.ANTI_ALIAS_FLAG)
    private val caixa = RectF()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isFocusable = true
        isClickable = true
        setOnClickListener { acao?.invoke() }
        // Entrando no menu pela lateral, o foco é redirecionado para o item da página aberta, mas o Android
        // ainda avisa "ganhou foco" ao item por onde ele passou: só abre a página se o foco continuar aqui.
        setOnFocusChangeListener { _, f -> invalidate(); if (f && isFocused) aoFocar?.invoke() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val m = context.dp(Espaco.XS)
        caixa.set(m, m * 0.6f, w - m, h - m * 0.6f)
        pIcone.strokeWidth = context.dp(Foco.TRACO)
        pTexto.textSize = context.dp(Tipo.LEGENDA)
    }

    override fun onDraw(c: Canvas) {
        val foco = isFocused
        val raio = context.dp(Raio.CONTROLE)
        if (foco || selecionado) {
            pCaixa.color = if (foco) Cores.VERMELHO else Cores.CARTAO
            c.drawRoundRect(caixa, raio, raio, pCaixa)
        }
        val cor = when {
            foco -> Cores.TEXTO
            selecionado -> Cores.VERMELHO
            else -> Cores.TEXTO_SUAVE
        }
        pIcone.color = cor
        pTexto.color = cor
        val s = height * 0.34f
        Icones.desenhar(c, icone, width / 2f, height * 0.4f, s, pIcone)
        c.drawText(nome, width / 2f, height * 0.4f + s * 0.5f + pTexto.textSize * 1.35f, pTexto)
    }
}

/**
 * Botão redondo do cabeçalho (pesquisa, Wi-Fi, configurações, perfil).
 * Foco = círculo vermelho cheio + leve aumento; [circulo] (perfil) tem um círculo neutro por baixo.
 */
class BotaoIcone(ctx: Context, private val icone: Icone, private val circulo: Boolean) : View(ctx) {
    var acao: (() -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val pDisco = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isFocusable = true
        isClickable = true
        setOnClickListener { acao?.invoke() }
        setOnFocusChangeListener { _, f ->
            val s = if (f) Foco.ESCALA_ICONE else 1f
            animate().scaleX(s).scaleY(s).setDuration(Tempo.FOCO).start()
            invalidate()
        }
    }

    override fun onDraw(c: Canvas) {
        val foco = isFocused
        val cx = width / 2f; val cy = height / 2f; val r = minOf(width, height) / 2f - context.dp(2f)
        if (circulo || foco) {
            pDisco.color = if (foco) Cores.VERMELHO else Cores.CARTAO
            c.drawCircle(cx, cy, r, pDisco)
        }
        p.color = if (foco) Cores.TEXTO else Cores.comAlfa(Cores.TEXTO, 220)
        p.strokeWidth = context.dp(Foco.TRACO)
        Icones.desenhar(c, icone, cx, cy, r * (if (circulo) 1.1f else 1.15f), p)
    }
}
