package com.exemplo.novatv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout

/** O que uma vitrine mostra: o título em destaque, a imagem larga dele e os pôsteres do serviço. */
class DadosVitrine(
    val chamada: String,
    val logo: Bitmap?,
    val titulo: Titulo,
    val imagem: Bitmap?,
    val posters: List<Bitmap>,
)

/**
 * Vitrine automática (etapa 12): destaque grande em cima (64%) e uma fileira de 9 pôsteres
 * do mesmo serviço embaixo (36%). Não recebe foco: qualquer tecla a fecha.
 */
class VitrineView(ctx: Context) : LinearLayout(ctx) {
    private val destaque = DestaqueView(ctx).apply { isFocusable = false; isClickable = false }
    private val posters = PostersView(ctx)

    init {
        orientation = VERTICAL
        clipChildren = false
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        addView(destaque, LayoutParams(-1, 0, 64f))
        addView(posters, LayoutParams(-1, 0, 36f))
    }

    fun preparar(d: DadosVitrine) {
        // As camadas prontas na placa de vídeo só existem enquanto a vitrine está em uso.
        destaque.setLayerType(LAYER_TYPE_HARDWARE, null)
        posters.setLayerType(LAYER_TYPE_HARDWARE, null)
        destaque.chamada = d.chamada
        destaque.logo = d.logo
        destaque.mostrar(d.titulo, d.imagem)
        posters.mostrar(d.posters)
    }

    /** Solta as imagens e as camadas da placa de vídeo quando a vitrine sai da tela. */
    fun limpar() {
        destaque.limpar()
        posters.soltar()
        destaque.setLayerType(LAYER_TYPE_NONE, null)
        posters.setLayerType(LAYER_TYPE_NONE, null)
    }
}

/** Fileira de pôsteres com cantos arredondados, numa camada pronta. */
class PostersView(ctx: Context) : View(ctx) {
    private var lista: List<Bitmap> = emptyList()
    private val pImg = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val pVazio = Paint().apply { color = Cores.CARTAO }
    private val caixa = RectF()
    private val src = Rect()
    private val path = Path()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun mostrar(l: List<Bitmap>) {
        lista = l
        invalidate()
    }

    /** Tira os pôsteres da tela e libera a memória deles (são só da vitrine). */
    fun soltar() {
        val velhos = lista
        mostrar(emptyList())
        postDelayed({ velhos.forEach { it.recycle() } }, 500)
    }

    override fun onDraw(c: Canvas) {
        val qtd = 9
        val folga = context.dp(Espaco.S)
        val gap = context.dp(Espaco.M)
        val h = height - folga * 2 - context.dp(6f)
        val wPoster = h * 2f / 3f
        // Os 9 pôsteres ocupam a largura toda; a altura se ajusta se não couberem.
        val larguraTotal = qtd * wPoster + (qtd - 1) * gap
        val esc = minOf(1f, (width - folga * 2) / larguraTotal)
        val pw = wPoster * esc; val ph = h * esc
        val g = (width - folga * 2 - qtd * pw) / (qtd - 1)
        val raio = context.dp(Raio.SUPERFICIE)
        for (i in 0 until qtd) {
            val x = folga + i * (pw + g)
            caixa.set(x, folga + context.dp(6f), x + pw, folga + context.dp(6f) + ph)
            path.reset()
            path.addRoundRect(caixa, raio, raio, Path.Direction.CW)
            c.save()
            c.clipPath(path)
            val b = lista.getOrNull(i)
            if (b != null) {
                // Corta o pôster para preencher a caixa sem distorcer.
                val escB = maxOf(caixa.width() / b.width, caixa.height() / b.height)
                val sw = (caixa.width() / escB).toInt(); val sh = (caixa.height() / escB).toInt()
                val sx = (b.width - sw) / 2; val sy = (b.height - sh) / 2
                src.set(sx, sy, sx + sw, sy + sh)
                c.drawBitmap(b, src, caixa, pImg)
            } else {
                c.drawRect(caixa, pVazio)
            }
            c.restore()
        }
    }
}
