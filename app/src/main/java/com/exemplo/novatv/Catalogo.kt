package com.exemplo.novatv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Um filme ou série em alta (dados do TMDB). */
class Titulo(
    val nome: String,
    val resumo: String,
    /** Imagem larga: caminho do TMDB ("/abc.jpg") ou endereço completo (miniatura do YouTube). */
    val backdrop: String,
    val poster: String,
    val filme: Boolean,
    /** Vídeo do YouTube (vazio para filmes e séries). */
    val videoId: String = "",
    /** Texto pequeno acima do nome (ex.: "VOCÊ CURTIU"); vazio usa a chamada do destaque. */
    val selo: String = "",
    /** Id no TMDB (0 quando não se sabe). */
    val tmdbId: Int = 0,
)

/**
 * Títulos populares no Brasil por provedor, pela API oficial do TMDB.
 * "Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB."
 * Cache em JSON (24 h) e imagens em disco (até ~25 MB, os mais antigos saem primeiro).
 */
object Catalogo {
    const val NETFLIX = 8
    /** Sem filtro de provedor: o que está em alta no Brasil em geral. */
    const val BRASIL = 0
    private const val VALIDADE = 24 * 60 * 60 * 1000L
    private const val LIMITE_IMAGENS = 25L * 1024 * 1024

    fun temChave() = BuildConfig.TMDB_KEY.isNotBlank()

    /** Quando a lista do provedor foi buscada pela última vez (0 se nunca). */
    fun atualizadoEm(ctx: Context, provedor: Int): Long = arquivo(ctx, provedor).lastModified()

    /** Faz a próxima chamada a [carregar] buscar de novo, sem apagar o cache atual. */
    fun expirar(ctx: Context, provedor: Int) {
        arquivo(ctx, provedor).takeIf { it.exists() }?.setLastModified(0)
    }

    // v2: guarda também o pôster (trocar o nome força uma busca nova quando o formato muda).
    private fun arquivo(ctx: Context, provedor: Int) = File(ctx.filesDir, "tmdb_v2_$provedor.json")

    /** Chamar fora da linha principal. Mostra o cache na hora; só busca de novo depois de 24 h. */
    fun carregar(ctx: Context, provedor: Int): List<Titulo> {
        val arq = arquivo(ctx, provedor)
        val cache = if (arq.exists()) ler(arq.readText()) else emptyList()
        if (!temChave()) return cache
        if (cache.isNotEmpty() && System.currentTimeMillis() - arq.lastModified() < VALIDADE) return cache
        return try {
            val filmes = buscar("movie", provedor)
            val series = buscar("tv", provedor)
            val juntos = ArrayList<JSONObject>()
            for (i in 0 until maxOf(filmes.size, series.size)) {
                filmes.getOrNull(i)?.let { juntos += it }
                series.getOrNull(i)?.let { juntos += it }
            }
            val json = JSONArray(juntos).toString()
            arq.writeText(json)
            ler(json)
        } catch (e: Exception) {
            cache
        }
    }

    /**
     * Uma fileira das páginas Filmes e Séries: até 20 títulos da Netflix no Brasil ([tipo] "movie" ou "tv"),
     * com os [filtros] extras do discover (ordem, gênero…). Cache de 24 h por fileira.
     */
    fun secao(ctx: Context, chave: String, tipo: String, filtros: String): List<Titulo> {
        val arq = File(ctx.filesDir, "tmdb_v2_sec_$chave.json")
        val cache = if (arq.exists()) ler(arq.readText()) else emptyList()
        if (!temChave()) return cache
        if (cache.isNotEmpty() && System.currentTimeMillis() - arq.lastModified() < VALIDADE) return cache
        return try {
            val url = "https://api.themoviedb.org/3/discover/$tipo?language=pt-BR&watch_region=BR" +
                "&with_watch_providers=$NETFLIX&page=1$filtros"
            val json = JSONArray(itens(JSONObject(get(url)).getJSONArray("results"), tipo, 20)).toString()
            arq.writeText(json)
            ler(json)
        } catch (e: Exception) {
            cache
        }
    }

    /** Converte resultados do TMDB em itens guardáveis (precisa ter pôster). */
    fun itens(r: JSONArray, tipoPadrao: String, max: Int): List<JSONObject> {
        val lista = ArrayList<JSONObject>()
        for (i in 0 until r.length()) {
            val o = r.getJSONObject(i)
            val tipo = o.optString("media_type").ifEmpty { tipoPadrao }
            val poster = o.optString("poster_path", "")
            if (poster.isEmpty() || poster == "null") continue
            lista += JSONObject()
                .put("nome", o.optString(if (tipo == "movie") "title" else "name"))
                .put("resumo", o.optString("overview", ""))
                .put("backdrop", o.optString("backdrop_path", "").takeIf { it != "null" } ?: "")
                .put("poster", poster)
                .put("filme", tipo == "movie")
                .put("id", o.optInt("id"))
            if (lista.size == max) break
        }
        return lista
    }

    /** GET autenticado no TMDB (para outros módulos, como o histórico). */
    fun getTmdb(url: String): String = get(url)

    private fun buscar(tipo: String, provedor: Int): List<JSONObject> {
        val url = "https://api.themoviedb.org/3/discover/$tipo?language=pt-BR&watch_region=BR" +
            (if (provedor != BRASIL) "&with_watch_providers=$provedor" else "") + "&sort_by=popularity.desc&page=1"
        val r = JSONObject(get(url)).getJSONArray("results")
        val lista = ArrayList<JSONObject>()
        for (i in 0 until r.length()) {
            val o = r.getJSONObject(i)
            val backdrop = o.optString("backdrop_path", "")
            val resumo = o.optString("overview", "")
            if (backdrop.isEmpty() || backdrop == "null" || resumo.isBlank()) continue
            lista += JSONObject()
                .put("nome", o.optString(if (tipo == "movie") "title" else "name"))
                .put("resumo", resumo)
                .put("backdrop", backdrop)
                .put("poster", o.optString("poster_path", "").takeIf { it != "null" } ?: "")
                .put("filme", tipo == "movie")
            if (lista.size == 12) break
        }
        return lista
    }

    private fun ler(json: String): List<Titulo> = try {
        val a = JSONArray(json)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Titulo(o.getString("nome"), o.optString("resumo"), o.optString("backdrop"), o.optString("poster"), o.optBoolean("filme"), tmdbId = o.optInt("id"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun get(endereco: String): String {
        val chave = BuildConfig.TMDB_KEY.trim()
        // Aceita a "Chave da API" (32 caracteres) ou o token de leitura (começa com "eyJ").
        val url = if (chave.startsWith("eyJ")) endereco else "$endereco&api_key=$chave"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/json")
        if (chave.startsWith("eyJ")) c.setRequestProperty("Authorization", "Bearer $chave")
        try {
            if (c.responseCode != 200) throw IllegalStateException("TMDB respondeu ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Imagem larga (backdrop). Chamar fora da linha principal. ARGB_8888 para não fazer faixas. */
    fun imagem(
        ctx: Context,
        caminho: String,
        tamanho: String = "w1280",
        formato: Bitmap.Config = Bitmap.Config.ARGB_8888,
        /** 2 = metade da largura e da altura (para cards menores). */
        reduzir: Int = 1,
    ): Bitmap? {
        if (caminho.isEmpty()) return null
        val pasta = File(ctx.cacheDir, "tmdb").apply { mkdirs() }
        val completo = caminho.startsWith("http")
        val endereco = if (completo) caminho else "https://image.tmdb.org/t/p/$tamanho$caminho"
        val nome = if (completo) "u" + Integer.toHexString(caminho.hashCode()) + ".jpg" else tamanho + caminho.replace('/', '_')
        val arq = File(pasta, nome)
        if (!arq.exists()) {
            try {
                val c = URL(endereco).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 10000
                try {
                    if (c.responseCode != 200) return null
                    val tmp = File(pasta, arq.name + ".tmp")
                    c.inputStream.use { ent -> tmp.outputStream().use { ent.copyTo(it) } }
                    tmp.renameTo(arq)
                } finally {
                    c.disconnect()
                }
                limpar(pasta)
            } catch (e: Exception) {
                return null
            }
        } else {
            arq.setLastModified(System.currentTimeMillis())
        }
        return BitmapFactory.decodeFile(arq.path, BitmapFactory.Options().apply { inPreferredConfig = formato; inSampleSize = reduzir })
    }

    private fun limpar(pasta: File) {
        val arquivos = pasta.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = arquivos.sumOf { it.length() }
        for (f in arquivos) {
            if (total <= LIMITE_IMAGENS) break
            total -= f.length()
            f.delete()
        }
    }
}
