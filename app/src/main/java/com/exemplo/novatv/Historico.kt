package com.exemplo.novatv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Recomendações com base no que um perfil da Netflix já assistiu.
 * A Netflix não tem API para isso: a pessoa baixa o próprio histórico no site
 * (Conta → Perfil → Atividade de visualização → "Baixar tudo") e o arquivo CSV vai junto
 * no app (assets/netflix_historico.csv). Cada título é procurado uma vez no TMDB (cache sem validade).
 * O nome do perfil vem de NETFLIX_PERFIL no segredos.properties (sem ele: "você").
 */
object Historico {
    val PERFIL: String = BuildConfig.NETFLIX_PERFIL.ifBlank { "você" }
    private const val ARQUIVO = "netflix_historico.csv"
    private const val MAX_TITULOS = 60

    class Visto(val nome: String, val id: Int, val filme: Boolean, val generos: List<Int>)

    fun temHistorico(ctx: Context): Boolean = try {
        ctx.assets.open(ARQUIVO).close(); true
    } catch (e: Exception) {
        false
    }

    /** Títulos assistidos, do mais recente para o mais antigo (séries viram um título só). */
    private fun titulosDoCsv(ctx: Context): List<String> = try {
        ctx.assets.open(ARQUIVO).bufferedReader().readLines().drop(1).mapNotNull { linha ->
            val nome = if (linha.startsWith("\"")) linha.substring(1).substringBefore("\",") else linha.substringBefore(",")
            // "Série: Temporada 1: Episódio" → "Série"
            nome.substringBefore(": ").trim().takeIf { it.isNotEmpty() }
        }.distinct().take(MAX_TITULOS)
    } catch (e: Exception) {
        emptyList()
    }

    /** Chamar fora da linha principal. */
    fun vistos(ctx: Context): List<Visto> {
        if (!temHistorico(ctx) || !Catalogo.temChave()) return emptyList()
        val arq = File(ctx.filesDir, "historico_tmdb_v1.json")
        val cache = if (arq.exists()) JSONObject(arq.readText()) else JSONObject()
        var mudou = false
        val lista = titulosDoCsv(ctx).mapNotNull { nome ->
            val o = cache.optJSONObject(nome) ?: try {
                val r = JSONObject(Catalogo.getTmdb("https://api.themoviedb.org/3/search/multi?language=pt-BR&query=" +
                    URLEncoder.encode(nome, "UTF-8"))).getJSONArray("results")
                var achado = JSONObject().put("id", 0)
                for (i in 0 until r.length()) {
                    val x = r.getJSONObject(i)
                    val t = x.optString("media_type")
                    if (t == "movie" || t == "tv") {
                        achado = JSONObject().put("id", x.optInt("id")).put("filme", t == "movie")
                            .put("generos", x.optJSONArray("genre_ids") ?: JSONArray())
                        break
                    }
                }
                cache.put(nome, achado)
                mudou = true
                achado
            } catch (e: Exception) {
                null
            } ?: return@mapNotNull null
            val id = o.optInt("id")
            if (id == 0) return@mapNotNull null
            val g = o.optJSONArray("generos") ?: JSONArray()
            Visto(nome, id, o.optBoolean("filme"), (0 until g.length()).map { g.getInt(it) })
        }
        if (mudou) arq.writeText(cache.toString())
        return lista
    }

    /** Fileira "Recomendados para (perfil)": Netflix no Brasil, nos gêneros mais assistidos, sem o que já viu. */
    fun recomendados(ctx: Context, filme: Boolean, vistos: List<Visto>): List<Titulo> {
        val doTipo = vistos.filter { it.filme == filme }
        if (doTipo.isEmpty()) return emptyList()
        val generos = doTipo.flatMap { it.generos }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.take(3).map { it.key }
        if (generos.isEmpty()) return emptyList()
        val ja = doTipo.map { it.id }.toSet()
        val tipo = if (filme) "movie" else "tv"
        return Catalogo.secao(ctx, "rec_${tipo}_${generos.joinToString("_")}", tipo,
            "&sort_by=popularity.desc&with_genres=" + generos.joinToString("|"))
            .filter { it.tmdbId !in ja }
    }

    /**
     * Fileira "Porque você assistiu X": recomendações do TMDB para o título mais recente do tipo,
     * mantendo só os que estão na Netflix no Brasil. Cache de 24 h.
     */
    fun porqueAssistiu(ctx: Context, filme: Boolean, vistos: List<Visto>): Pair<String, List<Titulo>>? {
        val base = vistos.firstOrNull { it.filme == filme } ?: return null
        val tipo = if (filme) "movie" else "tv"
        val arq = File(ctx.filesDir, "tmdb_v2_porque_${tipo}_${base.id}.json")
        if (arq.exists() && System.currentTimeMillis() - arq.lastModified() < 24 * 60 * 60 * 1000L) {
            return base.nome to lerItens(arq.readText())
        }
        return try {
            val r = JSONObject(Catalogo.getTmdb("https://api.themoviedb.org/3/$tipo/${base.id}/recommendations?language=pt-BR&page=1"))
                .getJSONArray("results")
            val candidatos = Catalogo.itens(r, tipo, 20)
            val ja = vistos.map { it.id }.toSet()
            // Só os que estão na Netflix no Brasil (1 consulta por título, guardada por 24 h).
            val naNetflix = candidatos.filter { o ->
                o.optInt("id") !in ja && try {
                    val p = JSONObject(Catalogo.getTmdb("https://api.themoviedb.org/3/$tipo/${o.optInt("id")}/watch/providers?x=1"))
                        .optJSONObject("results")?.optJSONObject("BR")?.optJSONArray("flatrate")
                    p != null && (0 until p.length()).any { p.getJSONObject(it).optInt("provider_id") == Catalogo.NETFLIX }
                } catch (e: Exception) {
                    false
                }
            }
            val json = JSONArray(naNetflix).toString()
            arq.writeText(json)
            base.nome to lerItens(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun lerItens(json: String): List<Titulo> = try {
        val a = JSONArray(json)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Titulo(o.getString("nome"), o.optString("resumo"), o.optString("backdrop"), o.optString("poster"),
                o.optBoolean("filme"), tmdbId = o.optInt("id"))
        }
    } catch (e: Exception) {
        emptyList()
    }
}
