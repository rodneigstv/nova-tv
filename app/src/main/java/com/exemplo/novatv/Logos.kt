package com.exemplo.novatv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color

/**
 * Logos oficiais (Wikimedia Commons, domínio público) em res/drawable-nodpi.
 * As marcas pertencem aos seus titulares; uso pessoal.
 */
object Logos {
    /** id do serviço -> (recurso, clarear partes cinza/pretas). */
    private val arquivos = mapOf(
        "netflix" to (R.drawable.logo_netflix to false),
        "youtube" to (R.drawable.logo_youtube to true),
        "geforcenow" to (R.drawable.logo_geforcenow to false),
        "xbox" to (R.drawable.logo_xbox to false),
        "playstore" to (R.drawable.logo_playstore to true),
        "ytmusic" to (R.drawable.logo_ytmusic to false),
        "twitch" to (R.drawable.logo_twitch to false),
        "googletv" to (R.drawable.logo_googletv to true),
        "playgames" to (R.drawable.logo_playgames to false),
        "tclhome" to (R.drawable.logo_tcl to false),
    )
    private val cache = HashMap<String, Bitmap?>()

    /** Chamar fora da linha principal. */
    @Synchronized
    fun carregar(ctx: Context, id: String): Bitmap? = cache.getOrPut(id) {
        val (res, clarear) = arquivos[id] ?: return@getOrPut null
        val b = BitmapFactory.decodeResource(ctx.resources, res, BitmapFactory.Options().apply { inMutable = true })
            ?: return@getOrPut null
        if (clarear) clarearCinzas(b)
        // O logo do Play Games é só o ícone: junta o nome ao lado para ficar como os outros.
        if (id == "playgames") return@getOrPut iconeComNome(b, "Play Games").also { b.recycle() }
        // O maior uso na tela é ~520x230 px: guardar o original de 960 px só gastaria memória.
        val esc = minOf(1f, 600f / b.width, 260f / b.height)
        val pronto = if (esc < 1f) reduzirComQualidade(b, (b.width * esc).toInt(), (b.height * esc).toInt()).also { if (it !== b) b.recycle() } else b
        pronto.setHasMipMap(true)
        pronto
    }

    /** Pixels com pouca saturação viram brancos, mantendo a transparência. */
    private fun clarearCinzas(b: Bitmap) {
        val w = b.width; val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
            val max = maxOf(r, g, bl); val min = minOf(r, g, bl)
            val sat = if (max == 0) 0f else (max - min) / max.toFloat()
            if (sat < 0.25f) px[i] = (c and 0xFF000000.toInt()) or 0xFFFFFF
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }
}

/**
 * Reduz um logo grande para o tamanho exato em que vai ser desenhado, em etapas de metade
 * (cada etapa com filtro), para o contorno ficar liso. Desenhar 1:1 evita o serrilhado da TV.
 */
fun reduzirComQualidade(b: Bitmap, w: Int, h: Int): Bitmap {
    var atual = b
    while (atual.width / 2 >= w && atual.height / 2 >= h) {
        val prox = Bitmap.createScaledBitmap(atual, atual.width / 2, atual.height / 2, true)
        if (atual !== b) atual.recycle()
        atual = prox
    }
    if (atual.width == w && atual.height == h) return atual
    val fim = Bitmap.createScaledBitmap(atual, w.coerceAtLeast(1), h.coerceAtLeast(1), true)
    if (atual !== b) atual.recycle()
    return fim
}

/** Ícone + nome em branco ao lado, num só bitmap (para logos que são só o símbolo). */
private fun iconeComNome(icone: Bitmap, nome: String): Bitmap {
    val h = 240
    val iw = icone.width * h / icone.height
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = h * 0.42f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    val gap = h / 5
    val tw = p.measureText(nome).toInt()
    val b = Bitmap.createBitmap(iw + gap + tw + 4, h, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    c.drawBitmap(icone, null, android.graphics.Rect(0, 0, iw, h), p)
    c.drawText(nome, (iw + gap).toFloat(), h / 2f - (p.ascent() + p.descent()) / 2f, p)
    b.setHasMipMap(true)
    return b
}
