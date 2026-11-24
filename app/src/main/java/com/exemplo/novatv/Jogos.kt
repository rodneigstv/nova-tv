package com.exemplo.novatv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Imagens dos jogos do card do GeForce NOW, pela API pública da loja Steam (capturas oficiais):
 * Call of Duty: Modern Warfare 4, Forza Horizon 6 e Crimson Desert. Marcas dos seus titulares; uso pessoal.
 */
object Jogos {
    private val steam = listOf(4435490, 2483190, 3321460)
    private const val VALIDADE = 7 * 24 * 60 * 60 * 1000L

    /** Chamar fora da linha principal. Ordem: COD MW4, Forza Horizon 6, Crimson Desert. */
    fun carregar(ctx: Context): List<Bitmap> =
        enderecos(ctx).mapNotNull { Catalogo.imagem(ctx, it, formato = Bitmap.Config.RGB_565, reduzir = 2) }

    /** Endereço da primeira captura de cada jogo (guardado por 7 dias). */
    private fun enderecos(ctx: Context): List<String> {
        val arq = File(ctx.filesDir, "jogos_v2.txt")
        if (arq.exists() && System.currentTimeMillis() - arq.lastModified() < VALIDADE) return arq.readLines().filter { it.isNotBlank() }
        val lista = steam.mapNotNull { id ->
            try {
                val c = URL("https://store.steampowered.com/api/appdetails?appids=$id&filters=screenshots").openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                val json = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
                JSONObject(json).getJSONObject(id.toString()).getJSONObject("data")
                    .getJSONArray("screenshots").getJSONObject(0).getString("path_full").substringBefore('?')
            } catch (e: Exception) {
                null
            }
        }
        if (lista.size == steam.size) arq.writeText(lista.joinToString("\n"))
        return lista.ifEmpty { if (arq.exists()) arq.readLines().filter { it.isNotBlank() } else emptyList() }
    }
}

/** Artes montadas no app (sem baixar nada): fundo na cor da marca, com brilho, e o logo no meio. */
object Artes {
    fun cartaz(logo: Bitmap, cor: Int): Bitmap {
        val w = 640; val h = 400
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), escurecer(cor, 0.55f), escurecer(cor, 0.18f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.5f, h * 0.42f, w * 0.6f, Cores.comAlfa(cor, 150), Cores.comAlfa(cor, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        // Logo no meio, estreito o bastante para não ser cortado pelo card (que é mais "quadrado").
        val esc = minOf(w * 0.5f / logo.width, h * 0.4f / logo.height)
        val lw = (logo.width * esc).toInt(); val lh = (logo.height * esc).toInt()
        val x = (w - lw) / 2; val y = (h - lh) / 2 - h / 20
        c.drawBitmap(logo, null, Rect(x, y, x + lw, y + lh), p)
        return b
    }

    /** Card "TV Aberta": antena com ondas de sinal, título e as emissoras abertas embaixo. */
    fun tvAberta(): Bitmap {
        // Mesma proporção do card (~1,27), para nada ser cortado nas laterais.
        val w = 560; val h = 440
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF140404.toInt(), 0xFF5A0606.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.26f, h * 0.42f, h * 0.55f, Cores.comAlfa(Cores.VERMELHO, 140), Cores.comAlfa(Cores.VERMELHO, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null

        // Antena: mastro, duas hastes em V e ondas de sinal.
        val ax = w * 0.24f; val topo = h * 0.40f
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0xFFFFFFFF.toInt()
        p.strokeWidth = 10f
        c.drawLine(ax, topo, ax, h * 0.70f, p)
        c.drawLine(ax, topo, ax - 44f, topo - 60f, p)
        c.drawLine(ax, topo, ax + 44f, topo - 60f, p)
        c.drawLine(ax - 40f, h * 0.70f, ax + 40f, h * 0.70f, p)
        p.style = Paint.Style.FILL
        c.drawCircle(ax, topo, 13f, p)
        p.style = Paint.Style.STROKE
        for (i in 1..3) {
            val r = 28f + i * 26f
            p.color = Cores.comAlfa(Cores.VERMELHO, 255 - i * 55)
            p.strokeWidth = 8f
            val o = android.graphics.RectF(ax - r, topo - r, ax + r, topo + r)
            c.drawArc(o, -150f, 50f, false, p)
            c.drawArc(o, -80f, 50f, false, p)
        }
        p.style = Paint.Style.FILL

        // Textos.
        val x = w * 0.46f
        p.color = 0xFFFFFFFF.toInt()
        p.typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
        p.textSize = 70f
        c.drawText("TV", x, h * 0.36f, p)
        c.drawText("ABERTA", x, h * 0.36f + 66f, p)
        p.color = Cores.VERMELHO
        p.textSize = 19f
        p.letterSpacing = 0.2f
        p.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        c.drawText("ANTENA DIGITAL", x + 2f, h * 0.36f + 102f, p)
        p.letterSpacing = 0.08f
        p.color = Cores.comAlfa(0xFFFFFFFF.toInt(), 170)
        p.textSize = 19f
        val emissoras = "GLOBO · SBT · RECORD · BAND · CULTURA"
        c.drawText(emissoras, (w - p.measureText(emissoras)) / 2f, h * 0.88f, p)
        return b
    }

    /** Card da Netflix em pé (formato de pôster) para as páginas Filmes e Séries. */
    fun netflixEmPe(logo: Bitmap?): Bitmap {
        val w = 400; val h = 600
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF0A0202.toInt(), 0xFF4A0405.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.5f, h * 0.45f, w * 0.7f, Cores.comAlfa(0xFFE50914.toInt(), 110), Cores.comAlfa(0xFFE50914.toInt(), 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        if (logo != null) {
            val esc = w * 0.78f / logo.width
            val lw = (logo.width * esc).toInt(); val lh = (logo.height * esc).toInt()
            val x = (w - lw) / 2; val y = (h * 0.45f - lh / 2f).toInt()
            c.drawBitmap(logo, null, Rect(x, y, x + lw, y + lh), p)
        }
        p.color = Cores.comAlfa(0xFFFFFFFF.toInt(), 200)
        p.textSize = 26f
        p.letterSpacing = 0.2f
        p.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        val t = "ABRIR O APP"
        c.drawText(t, (w - p.measureText(t)) / 2f, h * 0.88f, p)
        return b
    }

    private fun escurecer(cor: Int, f: Float): Int {
        val r = ((cor shr 16) and 255) * f; val g = ((cor shr 8) and 255) * f; val bl = (cor and 255) * f
        return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or bl.toInt()
    }
}
