package com.exemplo.novatv

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/**
 * Destaque grande da página inicial: imagem do título, logo do serviço, chamada, nome e resumo.
 * Troca de título com esmaecimento.
 * [compacto]: versão para dois destaques lado a lado (imagem no card inteiro, textos embaixo);
 * a versão larga (vitrine) põe a imagem à direita e os textos à esquerda.
 */
class DestaqueView(ctx: Context, private val compacto: Boolean = false) : View(ctx) {
    var logo: Bitmap? = null
        set(v) { field = v; invalidate() }
    var chamada: String = "EM ALTA NA NETFLIX"
    var credito: String = "Dados e imagens: TMDB"
    /** Título que está na tela agora (null quando mostra um aviso). */
    val atual: Titulo? get() = titulo
    var acao: (() -> Unit)? = null

    private var titulo: Titulo? = null
    private var imagem: Bitmap? = null
    private var imagemAnterior: Bitmap? = null
    private var transicao = 1f
    private var aviso: String? = null

    private val vidro = Vidro(ctx)
    private val pSuperficie = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.CARTAO }
    // Imagens com cantos arredondados por BitmapShader (liso e mais leve que recortar com clipPath).
    private val pImg = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val pImgAnterior = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var shaderDe: Bitmap? = null
    private var shaderAnteriorDe: Bitmap? = null
    private val matriz = Matrix()
    // Véus só onde há texto por cima da imagem.
    private val pEsmaecer = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val pBaixo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val pLogo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pChamada = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cores.VERMELHO; letterSpacing = 0.12f; typeface = Tipo.MEDIO
    }
    private val pNome = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.TEXTO; typeface = Tipo.FORTE }
    private val pResumo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.comAlfa(Cores.TEXTO, 217); typeface = Tipo.REGULAR }
    private val pCredito = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.TEXTO_SUAVE; typeface = Tipo.REGULAR }
    private var resumo: StaticLayout? = null
    private val dst = RectF()
    private var animador: ValueAnimator? = null
    private var logoPronto: Bitmap? = null
    private var logoOrigem: Bitmap? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null) // textos e imagem viram uma textura pronta
        isFocusable = true
        isClickable = true
        setOnClickListener { acao?.invoke() }
        setOnFocusChangeListener { _, foco ->
            val s = if (foco) Foco.ESCALA_GRANDE else 1f
            animate().scaleX(s).scaleY(s).setDuration(Tempo.FOCO).withLayer().start()
            invalidate()
        }
    }

    /** Mostra um título (a imagem já carregada) com esmaecimento. */
    fun mostrar(t: Titulo, img: Bitmap?) {
        aviso = null
        imagemAnterior = imagem
        titulo = t
        imagem = img
        montarResumo()
        animador?.cancel()
        animador = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Tempo.TROCA
            addUpdateListener { transicao = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                // Terminada a troca, a imagem antiga não aparece mais: solta a referência.
                override fun onAnimationEnd(a: Animator) {
                    val velha = imagemAnterior
                    imagemAnterior = null
                    // A imagem antiga só era usada aqui: libera a memória (e a textura na placa) na hora.
                    if (velha != null && velha !== imagem) postDelayed({ velha.recycle() }, 500)
                }
            })
            start()
        }
    }

    /** Solta as imagens (usado pela vitrine quando sai da tela). */
    fun limpar() {
        animador?.cancel()
        val soltar = listOfNotNull(imagem, imagemAnterior)
        imagem = null
        imagemAnterior = null
        titulo = null
        // As imagens da vitrine são só dela: recicla depois do último quadro.
        postDelayed({ soltar.forEach { it.recycle() } }, 500)
    }

    /** Texto mostrado quando ainda não há títulos (sem chave do TMDB ou sem internet). */
    fun mostrarAviso(texto: String) {
        aviso = texto
        titulo = null
        montarResumo()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animador?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        vidro.montar(w, h, context.dp(Raio.SUPERFICIE))
        val r = vidro.rect
        val escuro = Cores.FUNDO
        if (compacto) {
            // Imagem no card inteiro: escurece o alto (logo e crédito) e a base (textos).
            pEsmaecer.shader = LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.35f,
                Cores.comAlfa(escuro, 185), Cores.comAlfa(escuro, 0), Shader.TileMode.CLAMP)
            // Base firme sob os textos (contraste a 3 m mesmo com miniaturas claras), sumindo até 70% da altura.
            pBaixo.shader = LinearGradient(0f, r.bottom, 0f, r.bottom - r.height() * 0.7f,
                intArrayOf(Cores.comAlfa(escuro, 245), Cores.comAlfa(escuro, 215), Cores.comAlfa(escuro, 0)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        } else {
            pEsmaecer.shader = LinearGradient(r.left + r.width() * 0.3f, 0f, r.left + r.width() * 0.62f, 0f,
                Cores.CARTAO, Cores.comAlfa(Cores.CARTAO, 0), Shader.TileMode.CLAMP)
            pBaixo.shader = LinearGradient(0f, r.bottom, 0f, r.bottom - r.height() * 0.35f,
                Cores.comAlfa(escuro, 160), Cores.comAlfa(escuro, 0), Shader.TileMode.CLAMP)
        }
        pChamada.textSize = context.dp(Tipo.LEGENDA)
        pNome.textSize = context.dp(if (compacto) Tipo.TITULO else Tipo.DISPLAY)
        pResumo.textSize = context.dp(Tipo.CORPO)
        pCredito.textSize = context.dp(Tipo.NOTA)
        shaderDe = null
        shaderAnteriorDe = null
        montarResumo()
    }

    private fun montarResumo() {
        val texto = titulo?.resumo ?: aviso ?: ""
        val largura = (vidro.rect.width() * (if (compacto) 0.8f else 0.42f)).toInt()
        if (largura <= 0) return
        resumo = StaticLayout.Builder.obtain(texto, 0, texto.length, pResumo, largura)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(context.dp(2f), 1f)
            .setMaxLines(if (compacto) 2 else 3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    override fun onDraw(c: Canvas) {
        val foco = isFocused
        val r = vidro.rect
        val raio = vidro.raio
        c.drawRoundRect(r, raio, raio, pSuperficie)
        imagemAnterior?.let { if (transicao < 1f) desenharImagem(c, it, 255, anterior = true) }
        imagem?.let { desenharImagem(c, it, (transicao * 255).toInt(), anterior = false) }
        c.drawRoundRect(r, raio, raio, pEsmaecer)
        c.drawRoundRect(r, raio, raio, pBaixo)
        if (compacto) desenharTextosCompactos(c, r) else desenharTextosLargos(c, r)
        vidro.desenharBorda(c, foco)
    }

    /** Versão larga (vitrine): logo, chamada, nome e resumo à esquerda; crédito embaixo à direita. */
    private fun desenharTextosLargos(c: Canvas, r: RectF) {
        val x = r.left + context.dp(Espaco.XL)
        var y = r.top + context.dp(Espaco.XL)
        logo?.let {
            desenharLogo(c, it, x, y, context.dp(30f))
            y += Math.round(context.dp(30f)) + context.dp(Espaco.L)
        }
        val nome = titulo?.nome ?: (if (aviso != null) "Lume TV" else "")
        if (nome.isNotEmpty()) {
            c.drawText(titulo?.selo?.takeIf { it.isNotEmpty() } ?: chamada, x, y + pChamada.textSize, pChamada)
            y += pChamada.textSize + context.dp(Espaco.M)
            val cabe = TextUtils.ellipsize(nome, pNome, r.width() * 0.5f, TextUtils.TruncateAt.END).toString()
            c.drawText(cabe, x, y + pNome.textSize * 0.92f, pNome)
            y += pNome.textSize + context.dp(Espaco.M)
        }
        resumo?.let {
            c.save()
            c.translate(x, y)
            it.draw(c)
            c.restore()
        }
        if (titulo != null) {
            pCredito.textAlign = Paint.Align.RIGHT
            c.drawText(credito, r.right - context.dp(Espaco.M), r.bottom - context.dp(Espaco.S), pCredito)
        }
    }

    /** Versão compacta: logo no alto à esquerda; chamada, nome e resumo de baixo para cima. */
    private fun desenharTextosCompactos(c: Canvas, r: RectF) {
        val x = r.left + context.dp(Espaco.L)
        logo?.let { desenharLogo(c, it, x, r.top + context.dp(Espaco.L), context.dp(22f)) }
        var y = r.bottom - context.dp(Espaco.L)
        resumo?.let {
            y -= it.height
            c.save()
            c.translate(x, y)
            it.draw(c)
            c.restore()
            y -= context.dp(Espaco.S)
        }
        val nome = titulo?.nome ?: (if (aviso != null) chamadaAviso else "")
        if (nome.isNotEmpty()) {
            val cabe = TextUtils.ellipsize(nome, pNome, r.width() - context.dp(Espaco.L * 2), TextUtils.TruncateAt.END).toString()
            c.drawText(cabe, x, y - pNome.descent(), pNome)
            y -= pNome.textSize + context.dp(Espaco.S)
            c.drawText(titulo?.selo?.takeIf { it.isNotEmpty() } ?: chamada, x, y - pChamada.descent(), pChamada)
        }
        if (titulo != null && credito.isNotEmpty()) {
            pCredito.textAlign = Paint.Align.RIGHT
            c.drawText(credito, r.right - context.dp(Espaco.L), r.top + context.dp(Espaco.L) + pCredito.textSize, pCredito)
        }
    }

    /** Nome mostrado junto do aviso (versão compacta). */
    var chamadaAviso: String = "Lume TV"

    private fun desenharLogo(c: Canvas, b: Bitmap, x: Float, y: Float, altura: Float) {
        val h = Math.round(altura)
        val w = Math.round(b.width * h / b.height.toFloat())
        var pronto = logoPronto
        if (pronto == null || logoOrigem !== b || pronto.height != h) {
            pronto = reduzirComQualidade(b, w, h)
            logoPronto = pronto
            logoOrigem = b
        }
        c.drawBitmap(pronto, Math.round(x).toFloat(), Math.round(y).toFloat(), pLogo)
    }

    /** Imagem no card inteiro (compacto) ou em ~68% à direita (vitrine), cortada para preencher. */
    private fun desenharImagem(c: Canvas, b: Bitmap, alfa: Int, anterior: Boolean) {
        if (b.isRecycled) return
        val p = if (anterior) pImgAnterior else pImg
        if ((if (anterior) shaderAnteriorDe else shaderDe) !== b) {
            val r = vidro.rect
            if (compacto) dst.set(r) else dst.set(r.left + r.width() * 0.32f, r.top, r.right, r.bottom)
            val esc = maxOf(dst.width() / b.width, dst.height() / b.height)
            matriz.setScale(esc, esc)
            // Centraliza na horizontal e mostra mais o terço de cima (onde costumam estar os rostos).
            matriz.postTranslate(dst.left + (dst.width() - b.width * esc) / 2f, dst.top + (dst.height() - b.height * esc) / 3f)
            val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            sh.setLocalMatrix(matriz)
            p.shader = sh
            if (anterior) shaderAnteriorDe = b else shaderDe = b
        }
        p.alpha = alfa
        c.drawRoundRect(vidro.rect, vidro.raio, vidro.raio, p)
    }
}
