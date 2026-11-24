package com.exemplo.novatv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import java.util.concurrent.Executors

/**
 * Fundo da tela: preto com um brilho vermelho suave, em movimento lento (etapa 08).
 * O brilho é desenhado UMA vez, fora da linha principal, numa imagem em meia resolução (gradientes
 * lisos ampliam sem perda): na tela é uma textura só. A View é 8% maior que a tela e só é DESLOCADA
 * (translationX/Y) dentro dessa sobra, com duas senoides lentas (40 s e 55 s) e uma paralaxe suave
 * na direção do card em foco: a placa de vídeo só move. Atualiza ~10 vezes por segundo.
 */
class FundoView(ctx: Context) : View(ctx) {
    private var imagem: Bitmap? = null
    private val pImg = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var sobraX = 0f
    private var sobraY = 0f
    private var ligado = false
    private val inicio = SystemClock.uptimeMillis()
    private var ultimoPasso = 0L
    // Paralaxe: alvo (-1..1) vindo do foco e posição atual, que chega devagar no alvo.
    private var alvoX = 0f
    private var alvoY = 0f
    private var parX = 0f
    private var parY = 0f

    private val passo = object : Runnable {
        override fun run() {
            if (!ligado) return
            mover()
            postDelayed(this, INTERVALO_MS)
        }
    }

    /** Mede 8% a mais que o espaço dado: a sobra é o caminho do movimento. */
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = MeasureSpec.getSize(hSpec)
        setMeasuredDimension((w * 1.08f).toInt(), (h * 1.08f).toInt())
        sobraX = w * 0.08f
        sobraY = h * 0.08f
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (w <= 0 || h <= 0) return
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        executor.execute {
            val b = montar(w / REDUCAO, h / REDUCAO)
            post {
                imagem?.recycle()
                imagem = b
                invalidate()
            }
        }
        mover()
    }

    /** Liga o movimento (app na tela). */
    fun ligar() {
        if (ligado) return
        ligado = true
        ultimoPasso = 0L
        removeCallbacks(passo)
        post(passo)
    }

    /** Para o movimento (app saiu da tela): nada roda em segundo plano. */
    fun desligar() {
        ligado = false
        removeCallbacks(passo)
    }

    /** Foco mudou: [nx], [ny] de -1 (esquerda/topo) a 1 (direita/baixo), relativo ao centro da tela. */
    fun focoEm(nx: Float, ny: Float) {
        alvoX = nx.coerceIn(-1f, 1f)
        alvoY = ny.coerceIn(-1f, 1f)
    }

    override fun onDetachedFromWindow() {
        desligar()
        super.onDetachedFromWindow()
    }

    private fun mover() {
        val agora = SystemClock.uptimeMillis()
        // Paralaxe chega no alvo em ~3 s, independente de quantos passos por segundo.
        val dt = if (ultimoPasso == 0L) INTERVALO_MS else (agora - ultimoPasso).coerceIn(1L, 500L)
        ultimoPasso = agora
        val k = 1f - Math.pow(1.0 - SUAVIZACAO_50MS, dt / 50.0).toFloat()
        parX += (alvoX - parX) * k
        parY += (alvoY - parY) * k
        val t = (agora - inicio).toDouble()
        // Movimento contínuo: centro da sobra ± 40% na horizontal e ± 25% na vertical, em senoides defasadas.
        var x = 0.5f + 0.4f * Math.sin(2 * Math.PI * t / 40_000.0).toFloat()
        var y = 0.5f + 0.25f * Math.sin(2 * Math.PI * t / 55_000.0 + 1.3).toFloat()
        // Paralaxe: no máximo 10% da sobra, na direção do card em foco.
        x += 0.1f * parX
        y += 0.1f * parY
        translationX = -sobraX * x.coerceIn(0f, 1f)
        translationY = -sobraY * y.coerceIn(0f, 1f)
    }

    override fun onDraw(c: Canvas) {
        val b = imagem
        if (b == null) c.drawColor(Cores.FUNDO) else c.drawBitmap(b, null, dst, pImg)
    }

    /** Preto + dois brilhos vermelhos (alto à direita e canto de baixo à esquerda), com dither contra degraus. */
    private fun montar(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val wf = w.toFloat(); val hf = h.toFloat()
        c.drawColor(Cores.FUNDO)
        val p = Paint(Paint.DITHER_FLAG)
        p.shader = RadialGradient(wf * 0.78f, hf * 0.12f, wf * 0.6f,
            Cores.comAlfa(Cores.VERMELHO, 46), Cores.comAlfa(Cores.VERMELHO, 0), Shader.TileMode.CLAMP)
        c.drawPaint(p)
        p.shader = RadialGradient(wf * 0.08f, hf * 0.95f, wf * 0.45f,
            Cores.comAlfa(Cores.VERMELHO, 24), Cores.comAlfa(Cores.VERMELHO, 0), Shader.TileMode.CLAMP)
        c.drawPaint(p)
        b.setHasAlpha(false) // opaca: a placa desenha sem mistura
        return b
    }

    companion object {
        /** Linha de trabalho em segundo plano também usada para ler a lista de apps. */
        val executor = Executors.newSingleThreadExecutor()
        private const val INTERVALO_MS = 100L // ~10 passos por segundo (o brilho é suave: não dá degraus)
        private const val SUAVIZACAO_50MS = 0.025 // mesma velocidade da paralaxe de antes (por passo de 50 ms)
        private const val REDUCAO = 2 // imagem do brilho em meia resolução
    }
}
