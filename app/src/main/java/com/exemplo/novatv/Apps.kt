package com.exemplo.novatv

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import kotlin.math.abs

/** Um app instalado que pode virar card. */
class AppInfo(
    val pacote: String,
    val nome: String,
    /** Banner inteiro; só fica guardado quando não deu para tirar uma logo solta dele. */
    val banner: Bitmap?,
    val icone: Bitmap?,
    val intent: Intent,
    val jogo: Boolean,
    val musica: Boolean,
    /** Logo solta: a oficial quando existe; senão o banner sem o fundo. */
    val logo: Bitmap?,
) {
    val servico: Servico? = Servicos.porPacote(pacote)
    val cor: Int get() = servico?.cor ?: Cores.VERMELHO
}

object Apps {

    /** Lê os apps instalados. Chamar fora da linha principal. */
    fun carregar(ctx: Context): List<AppInfo> {
        val pm = ctx.packageManager
        val achados = LinkedHashMap<String, Pair<ResolveInfo, Boolean>>()
        val lb = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        for (ri in pm.queryIntentActivities(lb, 0)) achados.putIfAbsent(ri.activityInfo.packageName, ri to true)
        val normal = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        for (ri in pm.queryIntentActivities(normal, 0)) achados.putIfAbsent(ri.activityInfo.packageName, ri to false)
        achados.remove(ctx.packageName)

        val lista = ArrayList<AppInfo>()
        for ((pacote, par) in achados) {
            val (ri, leanback) = par
            val ai = ri.activityInfo
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(if (leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
                .setClassName(pacote, ai.name)
                // CLEAR_TASK: o app sempre abre na página inicial dele, e não onde parou (ex.: no meio de um vídeo).
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            // Banner e ícone na maior densidade que o app oferece: o logo fica nítido mesmo ampliado no card.
            val bannerCru = (nitido(pm, ai.applicationInfo, if (ai.banner != 0) ai.banner else ai.applicationInfo.banner)
                ?: ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm))?.let { paraBitmap(it, 640, 360) }
            val icone = if (bannerCru == null) paraBitmap(nitido(pm, ai.applicationInfo, ai.iconResource) ?: ai.loadIcon(pm), 192, 192) else null
            val app = ai.applicationInfo
            val jogo = app.category == ApplicationInfo.CATEGORY_GAME ||
                @Suppress("DEPRECATION") (app.flags and ApplicationInfo.FLAG_IS_GAME != 0)
            val musica = app.category == ApplicationInfo.CATEGORY_AUDIO || Servicos.ehMusica(pacote)
            val logo = Servicos.porPacote(pacote)?.let { Logos.carregar(ctx, it.id) } ?: bannerCru?.let { limparLogo(it) }
            // Com a logo pronta, o banner inteiro não é mais desenhado: libera a memória dele.
            // Sem logo solta (banner com foto), guarda o banner num tamanho menor, que basta para os cards.
            val banner = if (logo == null) bannerCru?.let { b -> encolher(b, 400, 225).also { if (it !== b) b.recycle() } } else { bannerCru?.recycle(); null }
            lista += AppInfo(pacote, ri.loadLabel(pm).toString(), banner, icone, intent, jogo, musica, logo)
        }
        // Serviços da referência primeiro, na ordem dela; depois o resto em ordem alfabética.
        val ordem = Servicos.lista
        return lista.sortedWith(compareBy<AppInfo>({ a -> a.servico?.let { ordem.indexOf(it) } ?: Int.MAX_VALUE }, { it.nome.lowercase() }))
    }

    /** Desenho do app na densidade xxxhdpi (a maior); null se o app não tiver esse recurso. */
    private fun nitido(pm: PackageManager, app: ApplicationInfo, resId: Int): Drawable? {
        if (resId == 0) return null
        return try {
            pm.getResourcesForApplication(app).getDrawableForDensity(resId, DisplayMetrics.DENSITY_XXXHIGH, null)
        } catch (e: Exception) {
            null
        }
    }

    /** Reduz (com qualidade) para caber em w x h, mantendo a proporção; nunca amplia. */
    private fun encolher(b: Bitmap, w: Int, h: Int): Bitmap {
        val esc = minOf(1f, w / b.width.toFloat(), h / b.height.toFloat())
        if (esc >= 1f) return b
        return reduzirComQualidade(b, (b.width * esc).toInt().coerceAtLeast(1), (b.height * esc).toInt().coerceAtLeast(1))
    }

    fun paraBitmap(d: Drawable, larguraPadrao: Int, alturaPadrao: Int): Bitmap {
        // Imagem pronta (PNG): usa os pixels originais e só reduz, com qualidade.
        if (d is BitmapDrawable && d.bitmap != null) {
            val orig = d.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            return encolher(orig, larguraPadrao, alturaPadrao).also { if (it !== orig) orig.recycle() }
        }
        // Desenho vetorial: é desenhado direto no tamanho final, sem perder nitidez.
        val iw = if (d.intrinsicWidth > 0) d.intrinsicWidth else larguraPadrao
        val ih = if (d.intrinsicHeight > 0) d.intrinsicHeight else alturaPadrao
        val esc = minOf(larguraPadrao / iw.toFloat(), alturaPadrao / ih.toFloat())
        val w = (iw * esc).toInt().coerceAtLeast(1)
        val h = (ih * esc).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, w, h)
        d.draw(c)
        return b
    }

    /**
     * Tira o fundo liso do banner: apaga, a partir das bordas, os pixels parecidos com a cor
     * dos cantos; recorta só o logo; e deixa brancas as partes cinza/pretas, que sumiriam no fundo escuro.
     * Devolve null quando o fundo não é liso (banner com foto).
     */
    fun limparLogo(b: Bitmap): Bitmap? {
        val w = b.width; val h = b.height
        if (w < 8 || h < 8) return null
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)

        val cantos = intArrayOf(px[0], px[w - 1], px[(h - 1) * w], px[h * w - 1])
        val fr = cantos.sumOf { (it shr 16) and 255 } / 4
        val fg = cantos.sumOf { (it shr 8) and 255 } / 4
        val fb = cantos.sumOf { it and 255 } / 4
        if (cantos.any { Color.alpha(it) < 200 || distancia(it, fr, fg, fb) > 40 }) return null

        val limite = 70
        val visitado = BooleanArray(w * h)
        val fila = IntArray(w * h)
        var ini = 0; var fim = 0
        fun semente(i: Int) {
            if (!visitado[i] && distancia(px[i], fr, fg, fb) < limite) { visitado[i] = true; fila[fim++] = i }
        }
        for (x in 0 until w) { semente(x); semente((h - 1) * w + x) }
        for (y in 0 until h) { semente(y * w); semente(y * w + w - 1) }
        while (ini < fim) {
            val i = fila[ini++]
            val x = i % w; val y = i / w
            if (x > 0) semente(i - 1)
            if (x < w - 1) semente(i + 1)
            if (y > 0) semente(i - w)
            if (y < h - 1) semente(i + w)
        }
        if (fim < w * h * 0.25f) return null

        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (i in px.indices) {
            val c = px[i]
            if (visitado[i]) {
                px[i] = 0
                continue
            }
            // Borda do logo: quanto mais parecido com o fundo, mais transparente (sem serrilhado).
            val d = distancia(c, fr, fg, fb)
            var a = Color.alpha(c)
            if (d < limite * 2) a = a * (d - limite / 2).coerceAtLeast(0) / (limite * 3 / 2)
            var r = (c shr 16) and 255; var g = (c shr 8) and 255; var bl = c and 255
            val max = maxOf(r, g, bl); val min = minOf(r, g, bl)
            val sat = if (max == 0) 0f else (max - min) / max.toFloat()
            if (sat < 0.25f) { r = 255; g = 255; bl = 255 }
            px[i] = Color.argb(a, r, g, bl)
            if (a > 40) {
                val x = i % w; val y = i / w
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
            }
        }
        if (x1 < x0 || y1 < y0) return null
        val m = 2
        x0 = (x0 - m).coerceAtLeast(0); y0 = (y0 - m).coerceAtLeast(0)
        x1 = (x1 + m).coerceAtMost(w - 1); y1 = (y1 + m).coerceAtMost(h - 1)
        val saida = Bitmap.createBitmap(x1 - x0 + 1, y1 - y0 + 1, Bitmap.Config.ARGB_8888)
        saida.setPixels(px, y0 * w + x0, w, 0, 0, saida.width, saida.height)
        return saida
    }

    private fun distancia(c: Int, r: Int, g: Int, b: Int): Int =
        abs(((c shr 16) and 255) - r) + abs(((c shr 8) and 255) - g) + abs((c and 255) - b)

    /** Miniatura bem pequena do banner: ampliada, vira um fundo borrado. */
    fun miniatura(b: Bitmap): Bitmap = Bitmap.createScaledBitmap(b, 12, 7, true)
}
