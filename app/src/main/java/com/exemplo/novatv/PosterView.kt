package com.exemplo.novatv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/** Pôster de filme ou série (páginas Filmes e Séries): superfície neutra; no foco, anel claro, aumento e nome. */
class PosterView(ctx: Context, private val titulo: Titulo) : View(ctx) {
    var imagem: Bitmap? = null
        set(v) { field = v; invalidate() }
    var acao: (() -> Unit)? = null
    /** Chamado quando o pôster recebe foco (a página carrega as imagens da fileira). */
    var aoFocar: (() -> Unit)? = null
    /** true = não escreve o nome por cima (card da Netflix, que já tem o logo). */
    var semNome = false

    private val vidro = Vidro(ctx)
    private val pImg = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pVeu = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val pNome = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.TEXTO; typeface = Tipo.MEDIO }
    private val pVazio = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.CARTAO }
    private val matriz = Matrix()
    private var shaderDe: Bitmap? = null
    /** Nome já cortado com "…" para a largura do pôster (calculado uma vez, não a cada quadro). */
    private var nomeCabe: CharSequence = ""

    init {
        isFocusable = true
        isClickable = true
        setOnClickListener { acao?.invoke() }
        setOnFocusChangeListener { _, foco ->
            val s = if (foco) Foco.ESCALA else 1f
            animate().scaleX(s).scaleY(s).setDuration(Tempo.FOCO).withLayer().start()
            translationZ = if (foco) 2f else 0f
            if (foco) aoFocar?.invoke()
            invalidate()
        }
    }

    /** Tira a imagem e libera a memória dela (a fileira ficou longe do foco). */
    fun soltar() {
        val b = imagem ?: return
        imagem = null
        shaderDe = null
        pImg.shader = null
        postDelayed({ b.recycle() }, 500)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        vidro.montar(w, h, context.dp(Raio.SUPERFICIE))
        shaderDe = null
        val r = vidro.rect
        pVeu.shader = LinearGradient(0f, r.bottom, 0f, r.bottom - r.height() * 0.35f,
            Cores.comAlfa(Cores.FUNDO, 230), Cores.comAlfa(Cores.FUNDO, 0), Shader.TileMode.CLAMP)
        pNome.textSize = context.dp(Tipo.LEGENDA)
        nomeCabe = TextUtils.ellipsize(titulo.nome, pNome, r.width() - context.dp(Espaco.M + 2f), TextUtils.TruncateAt.END)
    }

    override fun onDraw(c: Canvas) {
        val foco = isFocused
        val r = vidro.rect
        val raio = vidro.raio
        // Cantos arredondados com BitmapShader (bem mais leve na TV que recortar com clipPath).
        val b = imagem
        if (b != null && !b.isRecycled) {
            if (shaderDe !== b) {
                val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val esc = maxOf(r.width() / b.width, r.height() / b.height)
                matriz.setScale(esc, esc)
                matriz.postTranslate(r.left + (r.width() - b.width * esc) / 2f, r.top + (r.height() - b.height * esc) / 2f)
                sh.setLocalMatrix(matriz)
                pImg.shader = sh
                shaderDe = b
            }
            c.drawRoundRect(r, raio, raio, pImg)
        } else {
            c.drawRoundRect(r, raio, raio, pVazio)
        }
        if (!semNome && (foco || b == null)) {
            c.drawRoundRect(r, raio, raio, pVeu)
            c.drawText(nomeCabe, 0, nomeCabe.length, r.left + context.dp(7f), r.bottom - context.dp(Espaco.S), pNome)
        }
        vidro.desenharBorda(c, foco)
    }
}
