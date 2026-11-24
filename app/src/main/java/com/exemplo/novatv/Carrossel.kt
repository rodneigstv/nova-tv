package com.exemplo.novatv

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import java.util.concurrent.ExecutorService

/**
 * Faz um destaque trocar de título sozinho a cada [intervalo] ms, já carregando a imagem
 * do próximo para a troca não esperar a rede. [defasagem] atrasa só a primeira troca:
 * assim dois destaques lado a lado trocam alternados, não ao mesmo tempo.
 */
class Carrossel(
    private val ctx: Context,
    private val principal: Handler,
    private val rede: ExecutorService,
    private val intervalo: Long,
    private val defasagem: Long = 0,
    /** false = destaque fixo: mostra o primeiro título e não troca sozinho. */
    private val girar: Boolean = true,
) {
    /** O destaque na tela (troca quando a página é remontada). */
    var view: DestaqueView? = null
        set(v) {
            field = v
            val t = atual
            if (v != null && t != null) v.mostrar(t, imagemAtual)
        }

    var lista: List<Titulo> = emptyList()
        private set
    var atual: Titulo? = null
        private set
    private var imagemAtual: Bitmap? = null
    private var indice = -1
    private var preCarga: Pair<Int, Bitmap?>? = null
    private var ativo = false
    private var primeira = true
    private val trocar = Runnable { proximo() }

    /** Nova lista de títulos: mostra o primeiro na hora se ainda não há nada na tela. */
    fun definir(l: List<Titulo>) {
        lista = l
        preCarga = null
        if (l.isEmpty()) return
        // Destaque fixo: com lista nova (do dia), volta para o primeiro título.
        if (!girar) {
            if (atual?.backdrop == l[0].backdrop) return // mesmo título: não recarrega a imagem
            indice = -1
            proximo()
            return
        }
        if (atual == null) proximo() else agendar(intervalo)
    }

    /** Esquece o título atual (ex.: "Atualizar agora"). */
    fun reiniciar() {
        atual = null
        indice = -1
        preCarga = null
        primeira = true
    }

    fun ligar() {
        ativo = true
        if (atual != null) agendar(intervalo)
    }

    fun pausar() {
        ativo = false
        principal.removeCallbacks(trocar)
    }

    fun soltarPreCarga() {
        preCarga = null
    }

    private fun agendar(atraso: Long) {
        principal.removeCallbacks(trocar)
        if (girar && ativo && lista.size > 1) principal.postDelayed(trocar, atraso)
    }

    private fun proximo() {
        val l = lista
        if (l.isEmpty()) return
        val i = (indice + 1) % l.size
        val pronta = preCarga
        preCarga = null
        if (pronta != null && pronta.first == i) {
            exibir(l, i, pronta.second)
            return
        }
        rede.execute {
            val img = Catalogo.imagem(ctx, l[i].backdrop)
            principal.post { if (lista === l) exibir(l, i, img) }
        }
    }

    private fun exibir(l: List<Titulo>, i: Int, img: Bitmap?) {
        indice = i
        atual = l[i]
        imagemAtual = img
        view?.mostrar(l[i], img)
        agendar(if (primeira) intervalo + defasagem else intervalo)
        primeira = false
        val j = (i + 1) % l.size
        if (girar && j != i) rede.execute {
            val prox = Catalogo.imagem(ctx, l[j].backdrop)
            principal.post { if (lista === l) preCarga = j to prox }
        }
    }
}
