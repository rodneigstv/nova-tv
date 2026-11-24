package com.exemplo.novatv

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
import android.text.TextPaint
import android.text.TextUtils
import android.view.View

/**
 * Card de app: superfície neutra com o logo no centro, podendo alternar com imagens
 * (filmes, vídeos ou jogos). Foco: anel claro + leve aumento. Fica numa camada de hardware:
 * ao mudar o foco a placa de vídeo só amplia a imagem pronta.
 */
class CartaoView(ctx: Context, private val pequeno: Boolean) : View(ctx) {

    var app: AppInfo? = null
        set(v) { field = v; invalidate() }
    /** Card especial sem app (ex.: "Todos os Apps"). */
    var icone: Icone? = null
    var titulo: String = ""
    /** Linha menor embaixo do título (cards de apps que ainda não estão instalados). */
    var subtitulo: String = ""
    /** Logo para card sem app instalado. */
    var logoAvulso: Bitmap? = null
    var acao: (() -> Unit)? = null

    private val vidro = Vidro(ctx)
    private val pSuperficie = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.CARTAO }
    private val pLogo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pTexto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Cores.TEXTO
        typeface = Tipo.MEDIO
        textAlign = Paint.Align.CENTER
    }
    private val pIcone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Cores.TEXTO; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dst = RectF()

    /** Imagens que o card alterna com o logo (filmes, vídeos ou jogos). */
    var midias: List<Bitmap> = emptyList()
    /** false = não desenha o logo pequeno no canto da imagem (a arte já diz o que é). */
    var comSelo = true
    private var midiaIdx = -1
    private var midia: Bitmap? = null
    /** 0 = logo; 1 = imagem. Anda entre os dois na troca. */
    private var mistura = 0f
    private var animMidia: ValueAnimator? = null
    // Imagem com cantos arredondados por BitmapShader (liso e mais leve que recortar com clipPath).
    private val pMidia = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val pVeuMidia = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val matriz = Matrix()
    private var shaderDe: Bitmap? = null
    private var logoMiniPronto: Bitmap? = null

    /** Troca entre o logo e a próxima imagem (logo → imagem → logo → imagem seguinte…). */
    fun alternar() {
        if (midias.isEmpty()) return
        val paraMidia = mistura < 0.5f
        if (paraMidia) {
            midiaIdx = (midiaIdx + 1) % midias.size
            midia = midias[midiaIdx]
        }
        animMidia?.cancel()
        animMidia = ValueAnimator.ofFloat(mistura, if (paraMidia) 1f else 0f).apply {
            duration = Tempo.TROCA
            addUpdateListener { mistura = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /** Mostra a primeira imagem de vez, sem alternar com o logo (card de imagem fixa). */
    fun fixarMidia() {
        if (midias.isEmpty()) return
        animMidia?.cancel()
        midiaIdx = 0
        midia = midias[0]
        mistura = 1f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        animMidia?.cancel()
        super.onDetachedFromWindow()
    }

    init {
        isFocusable = true
        isClickable = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
        setOnClickListener { acao?.invoke() }
        setOnFocusChangeListener { _, foco ->
            val s = if (foco) Foco.ESCALA else 1f
            animate().scaleX(s).scaleY(s).setDuration(Tempo.FOCO).withLayer().start()
            translationZ = if (foco) 2f else 0f
            (parent as? View)?.translationZ = if (foco) 2f else 0f
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        vidro.montar(w, h, context.dp(Raio.SUPERFICIE))
        val r = vidro.rect
        // Escurece só a base da imagem, onde fica o logo pequeno.
        pVeuMidia.shader = LinearGradient(0f, r.top, 0f, r.bottom,
            intArrayOf(0, 0, Cores.comAlfa(Cores.FUNDO, 200)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        pTexto.textSize = context.dp(if (pequeno) Tipo.CORPO else Tipo.SUBTITULO)
        pIcone.strokeWidth = context.dp(Foco.TRACO)
        shaderDe = null
    }

    override fun onDraw(c: Canvas) {
        val foco = isFocused
        val r = vidro.rect
        val raio = vidro.raio
        c.drawRoundRect(r, raio, raio, pSuperficie)

        val a = app
        val ic = icone
        val largTexto = r.width() - context.dp(Espaco.L)
        when {
            ic != null -> {
                val s = r.height() * 0.3f
                Icones.desenhar(c, ic, r.centerX(), r.centerY() - s * 0.35f, s, pIcone)
                escrever(c, titulo, r.centerY() + s * 0.75f + pTexto.textSize * 0.4f, largTexto)
            }
            a?.logo != null -> desenharLogo(c, a.logo, r, true)
            a?.banner != null -> desenharLogo(c, a.banner, r, false)
            a == null && logoAvulso != null -> {
                desenharLogo(c, logoAvulso!!, r, true, -r.height() * 0.08f, 0.82f)
                pTexto.color = Cores.TEXTO_SUAVE
                escrever(c, subtitulo, r.bottom - r.height() * 0.13f, largTexto)
                pTexto.color = Cores.TEXTO
            }
            a == null && titulo.isNotEmpty() -> {
                val t = pTexto.textSize
                pTexto.textSize = t * 2f
                escrever(c, titulo, r.centerY() + t * 0.3f, largTexto)
                pTexto.textSize = t
                pTexto.color = Cores.TEXTO_SUAVE
                escrever(c, subtitulo, r.centerY() + t * 2.1f, largTexto)
                pTexto.color = Cores.TEXTO
            }
            a?.icone != null -> {
                val s = r.height() * 0.42f
                dst.set(r.centerX() - s / 2, r.centerY() - s * 0.7f, r.centerX() + s / 2, r.centerY() + s * 0.3f)
                c.drawBitmap(a.icone, null, dst, pLogo)
                escrever(c, a.nome, dst.bottom + pTexto.textSize * 1.3f, largTexto)
            }
        }
        if (mistura > 0f) desenharMidia(c, r, raio)
        vidro.desenharBorda(c, foco)
    }

    /** Texto centralizado numa linha, cortado com "…" se não couber no card. */
    private fun escrever(c: Canvas, t: String, y: Float, largura: Float) {
        if (t.isEmpty()) return
        val cabe = TextUtils.ellipsize(t, pTexto, largura, TextUtils.TruncateAt.END)
        c.drawText(cabe, 0, cabe.length, width / 2f, y, pTexto)
    }

    /** Imagem no card inteiro, com um logo pequeno no canto para saber de qual app é. */
    private fun desenharMidia(c: Canvas, r: RectF, raio: Float) {
        val b = midia ?: return
        if (b.isRecycled) return
        val alfa = (mistura * 255).toInt()
        if (shaderDe !== b) {
            // Corta a imagem para preencher o card sem distorcer.
            val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val esc = maxOf(r.width() / b.width, r.height() / b.height)
            matriz.setScale(esc, esc)
            matriz.postTranslate(r.left + (r.width() - b.width * esc) / 2f, r.top + (r.height() - b.height * esc) / 2f)
            sh.setLocalMatrix(matriz)
            pMidia.shader = sh
            shaderDe = b
        }
        pMidia.alpha = alfa
        c.drawRoundRect(r, raio, raio, pMidia)
        pVeuMidia.alpha = alfa
        c.drawRoundRect(r, raio, raio, pVeuMidia)
        if (!comSelo) return
        val logo = app?.logo ?: logoAvulso ?: return
        val h = Math.round(context.dp(16f))
        val w = Math.round(logo.width * h / logo.height.toFloat())
        var mini = logoMiniPronto
        if (mini == null || mini.height != h) {
            mini = reduzirComQualidade(logo, w, h)
            logoMiniPronto = mini
        }
        pLogo.alpha = alfa
        c.drawBitmap(mini, Math.round(r.left + context.dp(Espaco.M)).toFloat(), Math.round(r.bottom - context.dp(Espaco.M) - h).toFloat(), pLogo)
        pLogo.alpha = 255
    }

    private fun desenharLogo(c: Canvas, b: Bitmap, r: RectF, solta: Boolean, dy: Float = 0f, escala: Float = 1f) {
        // Logo solta (sem fundo) fica maior que o banner inteiro.
        val maxW = r.width() * (if (solta) (if (pequeno) 0.78f else 0.62f) else (if (pequeno) 0.68f else 0.5f))
        val maxH = r.height() * (if (solta) (if (pequeno) 0.58f else 0.5f) else (if (pequeno) 0.5f else 0.45f))
        val esc = minOf(maxW / b.width, maxH / b.height) * escala
        val w = Math.round(b.width * esc); val h = Math.round(b.height * esc)
        // Usa uma cópia já reduzida no tamanho exato e desenha 1:1, alinhada ao pixel.
        var pronto = logoPronto
        if (pronto == null || logoOrigem !== b || pronto.width != w || pronto.height != h) {
            pronto = reduzirComQualidade(b, w, h)
            logoPronto = pronto
            logoOrigem = b
        }
        val x = Math.round(r.centerX() - w / 2f).toFloat()
        val y = Math.round(r.centerY() - h / 2f + dy).toFloat()
        c.drawBitmap(pronto, x, y, pLogo)
    }

    private var logoPronto: Bitmap? = null
    private var logoOrigem: Bitmap? = null
}
