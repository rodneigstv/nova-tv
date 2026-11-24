package com.exemplo.novatv

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Ícones de linha desenhados à mão (sem arquivos de imagem). */
enum class Icone { INICIO, FILMES, SERIES, APPS, JOGOS, MUSICA, CONFIG, BUSCA, WIFI, PERFIL }

object Icones {
    private val path = Path()
    private val r = RectF()

    /** Desenha o ícone num quadrado de lado [s] centrado em (cx, cy). [p] deve ser STROKE. */
    fun desenhar(c: Canvas, ic: Icone, cx: Float, cy: Float, s: Float, p: Paint) {
        val h = s / 2f
        val l = cx - h; val t = cy - h; val rr = cx + h; val b = cy + h
        path.reset()
        when (ic) {
            Icone.INICIO -> {
                path.moveTo(l + s * 0.08f, cy - s * 0.02f)
                path.lineTo(cx, t + s * 0.1f)
                path.lineTo(rr - s * 0.08f, cy - s * 0.02f)
                path.moveTo(l + s * 0.2f, cy - s * 0.12f)
                path.lineTo(l + s * 0.2f, b - s * 0.08f)
                path.lineTo(rr - s * 0.2f, b - s * 0.08f)
                path.lineTo(rr - s * 0.2f, cy - s * 0.12f)
                path.moveTo(cx - s * 0.1f, b - s * 0.08f)
                path.lineTo(cx - s * 0.1f, cy + s * 0.14f)
                path.lineTo(cx + s * 0.1f, cy + s * 0.14f)
                path.lineTo(cx + s * 0.1f, b - s * 0.08f)
                c.drawPath(path, p)
            }
            Icone.FILMES -> {
                r.set(l + s * 0.08f, t + s * 0.16f, rr - s * 0.08f, b - s * 0.16f)
                c.drawRoundRect(r, s * 0.06f, s * 0.06f, p)
                c.drawLine(r.left, r.top + s * 0.16f, r.right, r.top + s * 0.16f, p)
                c.drawLine(r.left, r.bottom - s * 0.16f, r.right, r.bottom - s * 0.16f, p)
                var x = r.left + s * 0.17f
                while (x < r.right - s * 0.05f) {
                    c.drawLine(x, r.top, x, r.top + s * 0.16f, p)
                    c.drawLine(x, r.bottom - s * 0.16f, x, r.bottom, p)
                    x += s * 0.17f
                }
            }
            Icone.SERIES -> {
                r.set(l + s * 0.08f, t + s * 0.28f, rr - s * 0.08f, b - s * 0.1f)
                c.drawRoundRect(r, s * 0.07f, s * 0.07f, p)
                c.drawLine(cx, t + s * 0.28f, cx - s * 0.16f, t + s * 0.08f, p)
                c.drawLine(cx, t + s * 0.28f, cx + s * 0.16f, t + s * 0.08f, p)
            }
            Icone.APPS -> {
                val q = s * 0.34f; val g = s * 0.1f
                for (i in 0..1) for (j in 0..1) {
                    val x0 = l + s * 0.06f + i * (q + g); val y0 = t + s * 0.06f + j * (q + g)
                    r.set(x0, y0, x0 + q, y0 + q)
                    c.drawRoundRect(r, s * 0.07f, s * 0.07f, p)
                }
            }
            Icone.JOGOS -> {
                r.set(l + s * 0.04f, t + s * 0.24f, rr - s * 0.04f, b - s * 0.18f)
                c.drawRoundRect(r, s * 0.2f, s * 0.2f, p)
                val dx = l + s * 0.3f; val dy = cy + s * 0.02f; val d = s * 0.1f
                c.drawLine(dx - d, dy, dx + d, dy, p)
                c.drawLine(dx, dy - d, dx, dy + d, p)
                c.drawCircle(rr - s * 0.3f, dy - s * 0.06f, s * 0.03f, p)
                c.drawCircle(rr - s * 0.2f, dy + s * 0.05f, s * 0.03f, p)
            }
            Icone.MUSICA -> {
                c.drawCircle(l + s * 0.26f, b - s * 0.2f, s * 0.11f, p)
                c.drawCircle(rr - s * 0.2f, b - s * 0.28f, s * 0.11f, p)
                path.moveTo(l + s * 0.37f, b - s * 0.2f)
                path.lineTo(l + s * 0.37f, t + s * 0.18f)
                path.lineTo(rr - s * 0.09f, t + s * 0.08f)
                path.lineTo(rr - s * 0.09f, b - s * 0.28f)
                path.moveTo(l + s * 0.37f, t + s * 0.34f)
                path.lineTo(rr - s * 0.09f, t + s * 0.24f)
                c.drawPath(path, p)
            }
            Icone.CONFIG -> {
                val ro = s * 0.38f; val ri = s * 0.28f
                for (i in 0 until 16) {
                    val a = Math.toRadians(i * 22.5 - 11.25)
                    val raio = if ((i / 2) % 2 == 0) ro else ri
                    val a2 = Math.toRadians(i * 22.5 + 11.25)
                    val x1 = cx + raio * Math.cos(a).toFloat(); val y1 = cy + raio * Math.sin(a).toFloat()
                    val x2 = cx + raio * Math.cos(a2).toFloat(); val y2 = cy + raio * Math.sin(a2).toFloat()
                    if (i == 0) path.moveTo(x1, y1) else path.lineTo(x1, y1)
                    path.lineTo(x2, y2)
                }
                path.close()
                c.drawPath(path, p)
                c.drawCircle(cx, cy, s * 0.12f, p)
            }
            Icone.BUSCA -> {
                c.drawCircle(cx - s * 0.08f, cy - s * 0.08f, s * 0.26f, p)
                c.drawLine(cx + s * 0.11f, cy + s * 0.11f, rr - s * 0.08f, b - s * 0.08f, p)
            }
            Icone.WIFI -> {
                for (i in 1..3) {
                    val rad = s * 0.15f * i
                    r.set(cx - rad, b - s * 0.18f - rad, cx + rad, b - s * 0.18f + rad)
                    c.drawArc(r, 225f, 90f, false, p)
                }
                val f = p.style
                p.style = Paint.Style.FILL
                c.drawCircle(cx, b - s * 0.16f, s * 0.05f, p)
                p.style = f
            }
            Icone.PERFIL -> {
                c.drawCircle(cx, cy - s * 0.12f, s * 0.16f, p)
                r.set(cx - s * 0.32f, cy + s * 0.1f, cx + s * 0.32f, cy + s * 0.62f)
                c.drawArc(r, 200f, 140f, false, p)
            }
        }
    }
}
