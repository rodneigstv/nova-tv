package com.exemplo.novatv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Seus vídeos do YouTube, pela YouTube Data API v3 oficial (só leitura):
 * os que você curtiu e os mais novos dos canais em que você é inscrito.
 * Login pelo fluxo de TV do Google: a TV mostra um código e você confirma no celular.
 * Não existe API oficial para o histórico ("mais assistidos"); isso é o mais perto possível.
 */
object Videos {
    private const val ESCOPO = "https://www.googleapis.com/auth/youtube.readonly"
    private const val VALIDADE = 6 * 60 * 60 * 1000L
    private const val API = "https://www.googleapis.com/youtube/v3"

    private var tokenAcesso: String? = null
    private var tokenExpiraEm = 0L

    /** Código que a pessoa digita em google.com/device. */
    class Codigo(val dispositivo: String, val usuario: String, val endereco: String, val intervaloS: Int, val expiraEm: Long)

    class FalhaLogin(msg: String) : Exception(msg)

    fun temCredenciais() = BuildConfig.YT_CLIENT_ID.isNotBlank() && BuildConfig.YT_CLIENT_SECRET.isNotBlank()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("youtube", Context.MODE_PRIVATE)
    private fun cache(ctx: Context) = File(ctx.filesDir, "youtube_v1.json")

    fun conectado(ctx: Context) = prefs(ctx).getString("refresh", "").orEmpty().isNotEmpty()

    fun desconectar(ctx: Context) {
        prefs(ctx).edit().remove("refresh").apply()
        cache(ctx).delete()
        tokenAcesso = null
    }

    /** Faz a próxima chamada a [carregar] buscar de novo. */
    fun expirar(ctx: Context) {
        cache(ctx).takeIf { it.exists() }?.setLastModified(0)
    }

    // ---------- Login (fluxo para TVs) ----------

    /** Chamar fora da linha principal. */
    fun pedirCodigo(): Codigo {
        val r = JSONObject(post("https://oauth2.googleapis.com/device/code",
            mapOf("client_id" to BuildConfig.YT_CLIENT_ID, "scope" to ESCOPO)).second)
        return Codigo(
            r.getString("device_code"), r.getString("user_code"),
            r.optString("verification_url", "google.com/device"),
            r.optInt("interval", 5), System.currentTimeMillis() + r.optInt("expires_in", 1800) * 1000L,
        )
    }

    /** Pergunta uma vez se a pessoa já confirmou. true = conectado; false = ainda esperando. */
    fun verificar(ctx: Context, c: Codigo): Boolean {
        val (status, corpo) = post("https://oauth2.googleapis.com/token", mapOf(
            "client_id" to BuildConfig.YT_CLIENT_ID,
            "client_secret" to BuildConfig.YT_CLIENT_SECRET,
            "device_code" to c.dispositivo,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        ))
        val r = JSONObject(corpo)
        if (status == 200) {
            prefs(ctx).edit().putString("refresh", r.getString("refresh_token")).apply()
            tokenAcesso = r.getString("access_token")
            tokenExpiraEm = System.currentTimeMillis() + (r.optInt("expires_in", 3600) - 60) * 1000L
            return true
        }
        return when (r.optString("error")) {
            "authorization_pending", "slow_down" -> false
            "access_denied" -> throw FalhaLogin("O acesso foi recusado no celular.")
            "expired_token" -> throw FalhaLogin("O código expirou. Aperte OK para gerar outro.")
            else -> throw FalhaLogin("O Google recusou o login (${r.optString("error")}).")
        }
    }

    private fun token(ctx: Context): String {
        tokenAcesso?.let { if (System.currentTimeMillis() < tokenExpiraEm) return it }
        val refresh = prefs(ctx).getString("refresh", "").orEmpty()
        if (refresh.isEmpty()) throw FalhaLogin("Conta do YouTube não conectada.")
        val (status, corpo) = post("https://oauth2.googleapis.com/token", mapOf(
            "client_id" to BuildConfig.YT_CLIENT_ID,
            "client_secret" to BuildConfig.YT_CLIENT_SECRET,
            "refresh_token" to refresh,
            "grant_type" to "refresh_token",
        ))
        val r = JSONObject(corpo)
        if (status != 200) {
            // "invalid_grant": o acesso foi revogado ou expirou; é preciso conectar de novo.
            if (r.optString("error") == "invalid_grant") prefs(ctx).edit().remove("refresh").apply()
            throw FalhaLogin("Não foi possível renovar o acesso (${r.optString("error")}).")
        }
        tokenAcesso = r.getString("access_token")
        tokenExpiraEm = System.currentTimeMillis() + (r.optInt("expires_in", 3600) - 60) * 1000L
        return tokenAcesso!!
    }

    // ---------- Vídeos ----------

    /** Chamar fora da linha principal. Usa o cache por até 6 h. */
    fun carregar(ctx: Context): List<Titulo> {
        val arq = cache(ctx)
        val guardados = if (arq.exists()) ler(arq.readText()) else emptyList()
        if (!temCredenciais() || !conectado(ctx)) return guardados
        if (guardados.isNotEmpty() && System.currentTimeMillis() - arq.lastModified() < VALIDADE) return guardados
        return try {
            val t = token(ctx)
            val curtidos = curtidos(t)
            val novos = dosCanais(t)
            val juntos = LinkedHashMap<String, JSONObject>()
            for (i in 0 until maxOf(curtidos.size, novos.size)) {
                curtidos.getOrNull(i)?.let { juntos.putIfAbsent(it.getString("id"), it) }
                novos.getOrNull(i)?.let { juntos.putIfAbsent(it.getString("id"), it) }
            }
            val json = JSONArray(juntos.values.take(24)).toString()
            arq.writeText(json)
            ler(json)
        } catch (e: Exception) {
            guardados
        }
    }

    /**
     * Músicas para o card do YouTube Music: vídeos de música (categoria 10) que você curtiu,
     * com samba e pagode primeiro. Sem nenhuma curtida de música, cai em sambas populares no Brasil.
     * (Não existe API pública do YouTube Music; a curtida é a mesma conta.) Cache de 12 h.
     */
    fun musicas(ctx: Context): List<Titulo> {
        val arq = File(ctx.filesDir, "youtube_musica_v1.json")
        val guardados = if (arq.exists()) ler(arq.readText()) else emptyList()
        if (!temCredenciais() || !conectado(ctx)) return guardados
        if (guardados.isNotEmpty() && System.currentTimeMillis() - arq.lastModified() < 2 * VALIDADE) return guardados
        return try {
            val t = token(ctx)
            val r = JSONObject(get("$API/videos?part=snippet&myRating=like&maxResults=50", t)).optJSONArray("items")
            val samba = Regex("samba|pagode|roda de|partido alto", RegexOption.IGNORE_CASE)
            var lista = (0 until (r?.length() ?: 0)).map { r!!.getJSONObject(it) }
                .filter { it.getJSONObject("snippet").optString("categoryId") == "10" }
                .sortedByDescending { val sn = it.getJSONObject("snippet"); samba.containsMatchIn(sn.optString("title") + " " + sn.optString("channelTitle")) }
                .mapNotNull { item(it.getString("id"), it.getJSONObject("snippet"), "SUA MÚSICA") }
            if (lista.isEmpty()) {
                val s = JSONObject(get("$API/search?part=snippet&type=video&videoCategoryId=10&regionCode=BR&relevanceLanguage=pt&maxResults=10&q=samba", t))
                    .optJSONArray("items")
                lista = (0 until (s?.length() ?: 0)).mapNotNull {
                    val o = s!!.getJSONObject(it)
                    item(o.getJSONObject("id").optString("videoId"), o.getJSONObject("snippet"), "SAMBA")
                }
            }
            val json = JSONArray(lista.take(10)).toString()
            arq.writeText(json)
            ler(json)
        } catch (e: Exception) {
            guardados
        }
    }

    private fun curtidos(t: String): List<JSONObject> {
        val r = JSONObject(get("$API/videos?part=snippet&myRating=like&maxResults=15", t)).optJSONArray("items") ?: return emptyList()
        return (0 until r.length()).mapNotNull { i ->
            val o = r.getJSONObject(i)
            item(o.getString("id"), o.getJSONObject("snippet"), "VOCÊ CURTIU NO YOUTUBE")
        }
    }

    /** Os 2 vídeos mais novos de até 10 canais em que você é inscrito (~12 unidades da cota por dia). */
    private fun dosCanais(t: String): List<JSONObject> {
        val subs = JSONObject(get("$API/subscriptions?part=snippet&mine=true&maxResults=10&order=relevance", t)).optJSONArray("items")
            ?: return emptyList()
        val canais = (0 until subs.length()).map { subs.getJSONObject(it).getJSONObject("snippet").getJSONObject("resourceId").getString("channelId") }
        if (canais.isEmpty()) return emptyList()
        val ch = JSONObject(get("$API/channels?part=contentDetails&maxResults=10&id=" + canais.joinToString(","), t)).optJSONArray("items")
            ?: return emptyList()
        val lista = ArrayList<JSONObject>()
        for (i in 0 until ch.length()) {
            val uploads = ch.getJSONObject(i).getJSONObject("contentDetails").getJSONObject("relatedPlaylists").optString("uploads")
            if (uploads.isEmpty()) continue
            val pl = JSONObject(get("$API/playlistItems?part=snippet&maxResults=2&playlistId=$uploads", t)).optJSONArray("items") ?: continue
            for (j in 0 until pl.length()) {
                val sn = pl.getJSONObject(j).getJSONObject("snippet")
                val id = sn.getJSONObject("resourceId").optString("videoId")
                if (id.isNotEmpty()) item(id, sn, "NOVO NOS SEUS CANAIS")?.let { lista += it }
            }
        }
        return lista
    }

    private fun item(id: String, sn: JSONObject, selo: String): JSONObject? {
        val th = sn.optJSONObject("thumbnails") ?: return null
        val url = listOf("maxres", "standard", "high", "medium").firstNotNullOfOrNull { th.optJSONObject(it)?.optString("url") } ?: return null
        return JSONObject()
            .put("id", id)
            .put("nome", sn.optString("title"))
            .put("canal", sn.optString("videoOwnerChannelTitle").ifEmpty { sn.optString("channelTitle") })
            .put("imagem", url)
            .put("selo", selo)
    }

    private fun ler(json: String): List<Titulo> = try {
        val a = JSONArray(json)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Titulo(o.getString("nome"), o.optString("canal"), o.getString("imagem"), "", false,
                videoId = o.getString("id"), selo = o.optString("selo"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    // ---------- HTTP ----------

    private fun get(url: String, token: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (c.responseCode != 200) throw IllegalStateException("YouTube respondeu ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** POST de formulário; devolve (código HTTP, corpo), inclusive nos erros. */
    private fun post(url: String, campos: Map<String, String>): Pair<Int, String> {
        val corpo = campos.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            c.outputStream.use { it.write(corpo.toByteArray()) }
            val status = c.responseCode
            val s = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            return status to s
        } finally {
            c.disconnect()
        }
    }
}
