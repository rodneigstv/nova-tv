package com.exemplo.novatv

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val PARADA = 0
        private const val PREPARANDO = 1
        private const val NA_TELA = 2
        private const val FECHANDO = 3
        private const val OCIOSO_MS = 10_000L
        private const val DESLIZE_MS = Tempo.DESLIZE
        private const val EXIBICAO_MS = 5_000L
        private const val TROCA_CARD_MS = 3_500L
        /** Vitrine automática (etapa 12) desligada a pedido: a tela inicial fica sempre como está. */
        private const val VITRINE_LIGADA = false
    }

    enum class Pagina { INICIO, FILMES, SERIES, APPS, JOGOS, MUSICA, PERFIL }

    private val principal = Handler(Looper.getMainLooper())
    private lateinit var conteudo: FrameLayout
    private lateinit var fundo: FundoView
    private lateinit var hora: TextView
    private lateinit var data: TextView
    private lateinit var saudacao: TextView
    private val itensMenu = HashMap<Pagina, MenuItemView>()
    private var apps: List<AppInfo> = emptyList()
    private var pagina = Pagina.INICIO
    private var abertoComoHome = false

    // Dois destaques lado a lado: YouTube (seus vídeos) e Netflix (filmes e séries do TMDB).
    // Cada destaque troca de título a cada 9 s; o do YouTube começa 4,5 s depois, para trocarem alternados.
    // (A vitrine que troca a tela inteira continua desligada.)
    private val rede = Executors.newSingleThreadExecutor()
    private val netflix by lazy { Carrossel(applicationContext, principal, rede, 9000) }
    private val youtube by lazy { Carrossel(applicationContext, principal, rede, 9000, defasagem = 4500) }

    // Cards de baixo: cada um alterna entre o logo e imagens (seus vídeos, filmes ou jogos).
    private val cardsBaixo = HashMap<String, CartaoView>()
    private val midias = HashMap<String, List<Bitmap>>()
    private var vezCard = 0
    private var logoNetflix: Bitmap? = null
    private var logoYoutube: Bitmap? = null
    /** Login do YouTube em andamento (código mostrado na TV). */
    private var codigoYoutube: Videos.Codigo? = null
    private var avisoYoutube: String? = null
    /** Já mostrou o código de reconexão nesta abertura do app (não insiste toda hora). */
    private var loginOferecido = false

    // Vitrine automática (etapa 12).
    private var gradeInicio: View? = null
    private val vitrines = ArrayList<VitrineView>(2)
    private var estadoVitrine = PARADA
    private var tokenVitrine = 0
    private var rodadaVitrine = 0
    private var engolirTecla = false
    private val passosVitrine = ArrayList<Runnable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(null) // o FundoView pinta a tela inteira; evita pintar duas vezes
        abertoComoHome = intent?.hasCategory(Intent.CATEGORY_HOME) == true
        setContentView(montarTela())
        carregarApps()
        registerReceiver(mudouPacote, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    override fun onDestroy() {
        unregisterReceiver(mudouPacote)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            abertoComoHome = true
            // O botão Home cancela a vitrine e religa a contagem dos 10 s.
            fecharVitrine(rapido = true)
            fecharMonitor(rapido = true)
            if (pagina != Pagina.INICIO) mostrar(Pagina.INICIO)
            reiniciarOcioso()
        }
    }

    override fun onStart() {
        super.onStart()
        atualizarRelogio()
        netflix.ligar()
        youtube.ligar()
        carregarCatalogo()
        carregarVideos()
        carregarMidias()
        // Voltou para o Lume TV com um código de login na tela: continua esperando a confirmação.
        if (codigoYoutube != null) { youtube.pausar(); principal.postDelayed(verificarLogin, 1000) }
        principal.removeCallbacks(alternarCards)
        principal.postDelayed(alternarCards, TROCA_CARD_MS)
        reiniciarOcioso()
        if (pagina == Pagina.INICIO) fundo.ligar()
    }

    override fun onStop() {
        principal.removeCallbacks(tique)
        netflix.pausar()
        youtube.pausar()
        principal.removeCallbacks(verificarLogin)
        principal.removeCallbacks(alternarCards)
        principal.removeCallbacks(ocioso)
        pararVitrine()
        fecharMonitor(rapido = false)
        fundo.desligar()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Só a partir de MODERATE: os avisos RUNNING_* chegam mesmo com o app na tela.
        if (level >= TRIM_MEMORY_MODERATE) {
            netflix.soltarPreCarga()
            youtube.soltarPreCarga()
        }
    }

    /** Qualquer tecla durante a vitrine volta na hora para os cards, e essa tecla é ignorada. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Monitor do sistema aberto: esquerda ou Voltar fecham; as outras teclas ficam paradas.
        if (monitorAberto) {
            if (e.action == KeyEvent.ACTION_DOWN && (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || e.keyCode == KeyEvent.KEYCODE_BACK)) fecharMonitor(rapido = true)
            return true
        }
        // Direita no último card do Início: o "carrossel manual" passa para o monitor.
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && pagina == Pagina.INICIO && estadoVitrine == PARADA) {
            val f = currentFocus
            if (f != null && (f === cardsBaixo[Servicos.inicio.last()] || f === netflix.view)) {
                abrirMonitor()
                return true
            }
        }
        if (e.action == KeyEvent.ACTION_DOWN && estadoVitrine == NA_TELA) {
            engolirTecla = true
            fecharVitrine(rapido = true)
            return true
        }
        if (engolirTecla) {
            if (e.action == KeyEvent.ACTION_UP) engolirTecla = false
            return true
        }
        if (estadoVitrine == PREPARANDO) cancelarPreparo()
        reiniciarOcioso()
        // O menu lateral só recebe o foco pela esquerda: seta para baixo no fim do conteúdo
        // (ex.: botões do Perfil) não pula mais para um item do menu nem abre a página dele.
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            val f = currentFocus
            if (f != null && f !is MenuItemView && f.focusSearch(View.FOCUS_DOWN) is MenuItemView) return true
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            pagina != Pagina.INICIO -> mostrar(Pagina.INICIO)
            abertoComoHome -> Unit // tela inicial: Voltar não faz nada
            else -> finish()
        }
    }

    private val mudouPacote = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = carregarApps()
    }

    private fun carregarApps() {
        FundoView.executor.execute {
            val lista = Apps.carregar(applicationContext)
            principal.post {
                val primeira = apps.isEmpty()
                apps = lista
                mostrar(pagina, manterFoco = !primeira)
                if (primeira) carregarMidias() // cartazes dos apps (Google Play e Twitch) precisam da lista
            }
        }
    }

    // ---------- Montagem da tela ----------

    private fun montarTela(): View {
        val raiz = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        fundo = FundoView(this)
        raiz.addView(fundo, FrameLayout.LayoutParams(-1, -1))
        // Paralaxe: o fundo anda um pouco na direção do que está em foco.
        raiz.viewTreeObserver.addOnGlobalFocusChangeListener { antigo, novo ->
            novo?.let { avisarFundo(it) }
            // Entrando no menu vindo do conteúdo: o foco vai para o item da página aberta (não troca de página sem querer).
            if (novo is MenuItemView && antigo != null && antigo !is MenuItemView) {
                val atual = itensMenu[pagina]
                if (atual != null && atual !== novo) atual.requestFocus()
            }
        }

        // Tudo dentro da área segura da TV (~5%): trilho do menu à esquerda, conteúdo à direita.
        val trilho = 80f
        val inicioConteudo = Espaco.SEGURO_H + trilho + Espaco.L

        hora = texto(Tipo.DISPLAY, Tipo.NUMERO).apply { letterSpacing = 0.04f }
        data = texto(Tipo.LEGENDA, Tipo.REGULAR, Cores.TEXTO_SUAVE)
        val relogio = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(hora); addView(data)
        }
        raiz.addView(relogio, FrameLayout.LayoutParams(dpi(trilho), -2).apply {
            leftMargin = dpi(Espaco.SEGURO_H); topMargin = dpi(Espaco.SEGURO_V)
        })

        // Menu lateral (sem painel nem borda: só os itens).
        val painel = FrameLayout(this)
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val itens = listOf(
            Triple(Icone.INICIO, "Início", { mostrar(Pagina.INICIO) }),
            Triple(Icone.FILMES, "Filmes", { mostrar(Pagina.FILMES) }),
            Triple(Icone.SERIES, "Séries", { mostrar(Pagina.SERIES) }),
            Triple(Icone.APPS, "Apps", { mostrar(Pagina.APPS) }),
            Triple(Icone.JOGOS, "Jogos", { mostrar(Pagina.JOGOS) }),
            Triple(Icone.MUSICA, "Música", { mostrar(Pagina.MUSICA) }),
            Triple(Icone.CONFIG, "Config.", { abrirConfiguracoes() }),
            Triple(Icone.BUSCA, "Pesquisar", { abrirPesquisa() }),
        )
        val paginas = Pagina.values()
        itens.forEachIndexed { i, (ic, nome, acao) ->
            val item = MenuItemView(this, ic, nome)
            item.acao = acao
            if (i < 6) {
                itensMenu[paginas[i]] = item
                val p = paginas[i]
                item.aoFocar = { trocarPeloMenu(p) } // menu dinâmico: passar o foco já abre a página
            }
            menu.addView(item, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        painel.addView(menu, FrameLayout.LayoutParams(-1, -1))
        raiz.addView(painel, FrameLayout.LayoutParams(dpi(trilho), -1).apply {
            leftMargin = dpi(Espaco.SEGURO_H); topMargin = dpi(Espaco.SEGURO_V + 46f + Espaco.L); bottomMargin = dpi(Espaco.SEGURO_V)
        })

        // Cabeçalho: saudação discreta e um título.
        saudacao = texto(Tipo.CORPO, Tipo.REGULAR, Cores.TEXTO_SUAVE)
        val titulo = texto(Tipo.TITULO, Tipo.FORTE).apply { text = "O que vamos assistir hoje?" }
        val cabecalho = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(saudacao); addView(titulo)
        }
        raiz.addView(cabecalho, FrameLayout.LayoutParams(-2, -2).apply {
            leftMargin = dpi(inicioConteudo); topMargin = dpi(Espaco.SEGURO_V)
        })
        atualizarSaudacao()

        val atalhos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun atalho(ic: Icone, circulo: Boolean, acao: () -> Unit) {
            val b = BotaoIcone(this, ic, circulo)
            b.acao = acao
            atalhos.addView(b, LinearLayout.LayoutParams(dpi(40f), dpi(40f)).apply { leftMargin = dpi(Espaco.M) })
        }
        atalho(Icone.BUSCA, false) { abrirPesquisa() }
        atalho(Icone.WIFI, false) { abrir(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        atalho(Icone.CONFIG, false) { abrirConfiguracoes() }
        atalho(Icone.PERFIL, true) { mostrar(Pagina.PERFIL) }
        raiz.addView(atalhos, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            rightMargin = dpi(Espaco.SEGURO_H); topMargin = dpi(Espaco.SEGURO_V)
        })

        // Área dos cards: cada card tem uma folga interna (Vidro.folga), descontada aqui
        // para a borda visível dos cards ficar alinhada com a área segura.
        val folga = Espaco.S
        conteudo = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        raiz.addView(conteudo, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = dpi(inicioConteudo - folga); topMargin = dpi(Espaco.SEGURO_V + 41f + Espaco.L - folga)
            rightMargin = dpi(Espaco.SEGURO_H - folga); bottomMargin = dpi(Espaco.SEGURO_V - folga)
        })
        return raiz
    }

    private fun mostrar(p: Pagina, manterFoco: Boolean = false) {
        val tinhaFocoNoConteudo = currentFocus?.let { ehDescendente(it, conteudo) } == true
        pararVitrine()
        fecharMonitor(rapido = false)
        monitor = null
        pagina = p
        itensMenu.forEach { (k, v) -> v.selecionado = k == p }
        soltarFileiras()
        conteudo.removeAllViews()
        netflix.view = null
        youtube.view = null
        gradeInicio = null
        vitrines.clear()
        val primeiro = when (p) {
            Pagina.INICIO -> montarInicio()
            Pagina.FILMES -> montarNetflix(filmes = true)
            Pagina.SERIES -> montarNetflix(filmes = false)
            Pagina.APPS -> montarPagina("Todos os apps", null, apps)
            Pagina.JOGOS -> montarPagina("Jogos", null, apps.filter { it.jogo || it.servico?.id == "playgames" })
            Pagina.MUSICA -> montarPagina("Música", null, apps.filter { it.musica })
            Pagina.PERFIL -> montarPerfil()
        }
        if (!manterFoco || tinhaFocoNoConteudo || currentFocus == null) primeiro?.let { v -> v.post { v.requestFocus() } }
        // O fundo só se mexe na página inicial: nas páginas cheias de pôsteres ele forçaria redesenhar tudo.
        if (p == Pagina.INICIO) fundo.ligar() else fundo.desligar()
        reiniciarOcioso()
    }

    /**
     * Página inicial: dois destaques grandes lado a lado (YouTube com seus vídeos e Netflix com
     * filmes e séries, trocando alternados) e os 4 serviços escolhidos numa fileira embaixo.
     */
    private fun montarInicio(): View? {
        val coluna = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        val topo = linha()

        val yt = DestaqueView(this, compacto = true)
        yt.logo = logoYoutube
        yt.chamada = "SEUS VÍDEOS NO YOUTUBE"
        yt.credito = ""
        yt.chamadaAviso = "Seus vídeos no YouTube"
        yt.acao = { acaoYoutube() }
        if (youtube.atual == null) yt.mostrarAviso(avisoDoYoutube())
        youtube.view = yt
        if (codigoYoutube != null) avisoYoutube?.let { yt.mostrarAviso(it) } // login em andamento: o código continua visível
        topo.addView(yt, LinearLayout.LayoutParams(0, -1, 1f))

        val nf = DestaqueView(this, compacto = true)
        nf.logo = logoNetflix
        nf.chamadaAviso = "Em alta na Netflix"
        nf.acao = { apps.firstOrNull { it.servico?.id == "netflix" }?.let { abrirApp(it) } ?: abrirPlayStore("Netflix") }
        if (netflix.atual == null) nf.mostrarAviso(avisoNetflix())
        netflix.view = nf
        topo.addView(nf, LinearLayout.LayoutParams(0, -1, 1f))
        coluna.addView(topo, LinearLayout.LayoutParams(-1, 0, 262f))

        val l = linha()
        cardsBaixo.clear()
        for (id in Servicos.inicio) {
            val card = CartaoView(this, true)
            card.midias = midias[id] ?: emptyList()
            if (id in cardsFixos && card.midias.isNotEmpty()) card.fixarMidia()
            cardsBaixo[id] = card
            val a = apps.firstOrNull { it.servico?.id == id }
            if (a != null) {
                card.app = a
                card.acao = { abrirApp(a) }
            } else {
                // Ainda não instalado: o OK leva à busca da Play Store.
                val s = Servicos.porId(id)
                card.titulo = s?.nome ?: id
                card.subtitulo = "Não instalado · OK abre a Play Store"
                card.logoAvulso = Logos.carregar(this, id)
                card.acao = { abrirPlayStore(s?.nome ?: id) }
            }
            if (id == "tvaberta") { card.acao = { abrirTvAberta() }; card.comSelo = false }
            l.addView(card, LinearLayout.LayoutParams(0, -1, 1f))
        }
        coluna.addView(l, LinearLayout.LayoutParams(-1, 0, 170f).apply { topMargin = dpi(2f) })
        conteudo.addView(coluna, FrameLayout.LayoutParams(-1, -1))
        gradeInicio = coluna

        // Duas vitrines que se revezam, escondidas até a pessoa ficar 10 s sem mexer no controle.
        // Com a vitrine desligada, nem são criadas (menos Views e memória na tela inicial).
        if (VITRINE_LIGADA) repeat(2) {
            val v = VitrineView(this).apply { visibility = View.GONE }
            vitrines += v
            conteudo.addView(v, FrameLayout.LayoutParams(-1, -1))
        }
        // Monitor do sistema: escondido à direita, aparece com a seta para a direita no último card.
        monitor = MonitorView(this).apply { visibility = View.GONE }
        conteudo.addView(monitor, FrameLayout.LayoutParams(-1, -1))
        return yt
    }

    private fun avisoNetflix() =
        if (Catalogo.temChave()) "Carregando os filmes e séries em alta…"
        else "Os filmes e séries em alta aparecem aqui depois que a chave do TMDB for adicionada."

    private fun avisoDoYoutube(): String = avisoYoutube ?: when {
        !Videos.temCredenciais() -> "Falta criar o acesso do Google (Client ID) para mostrar seus vídeos. OK abre o YouTube."
        !Videos.conectado(this) -> "Aperte OK para conectar sua conta do YouTube."
        else -> "Carregando seus vídeos…"
    }

    /** [forcar]: mostra mesmo com um vídeo no destaque (login em andamento). */
    private fun mostrarAvisoYoutube(texto: String, forcar: Boolean = false) {
        avisoYoutube = texto
        if (forcar || youtube.atual == null) youtube.view?.mostrarAviso(texto)
    }

    /** Fim do login (deu certo, falhou ou expirou): o destaque volta a trocar os vídeos. */
    private fun encerrarLoginYoutube() {
        codigoYoutube = null
        principal.removeCallbacks(verificarLogin)
        youtube.ligar()
    }

    /** OK no destaque do YouTube: abre o app na aba Início (não o vídeo da tela); conecta a conta se faltar. */
    private fun acaoYoutube() {
        // Sempre abre o YouTube, mesmo com o login do Lume TV expirado (o login aparece no próprio destaque).
        apps.firstOrNull { it.servico?.id == "youtube" }?.let { abrirApp(it) } ?: abrirPlayStore("YouTube")
    }

    // ---------- Login do YouTube pela TV ----------

    private fun iniciarLoginYoutube() {
        if (codigoYoutube != null) return // já tem um código na tela
        youtube.pausar() // o código fica parado na tela até a pessoa confirmar
        mostrarAvisoYoutube("Login do YouTube expirou. Gerando um código para reconectar…", forcar = true)
        rede.execute {
            val c = try { Videos.pedirCodigo() } catch (e: Exception) { null }
            principal.post {
                if (c == null) {
                    mostrarAvisoYoutube("Não foi possível falar com o Google agora. Reconecte pelo Perfil → Conectar YouTube.", forcar = true)
                    encerrarLoginYoutube()
                    return@post
                }
                codigoYoutube = c
                mostrarAvisoYoutube("Para voltar a ver seus vídeos aqui: no celular, abra ${c.endereco} e digite o código ${c.usuario}", forcar = true)
                principal.postDelayed(verificarLogin, c.intervaloS * 1000L)
            }
        }
    }

    private val verificarLogin: Runnable = Runnable {
        val c = codigoYoutube ?: return@Runnable
        if (System.currentTimeMillis() > c.expiraEm) {
            mostrarAvisoYoutube("O código expirou. Para reconectar: Perfil → Conectar YouTube.", forcar = true)
            encerrarLoginYoutube()
            return@Runnable
        }
        rede.execute {
            val resultado: Any = try { Videos.verificar(applicationContext, c) } catch (e: Videos.FalhaLogin) { e.message ?: "Falha no login." }
            principal.post {
                when (resultado) {
                    true -> {
                        mostrarAvisoYoutube("Conta conectada! Carregando seus vídeos…", forcar = true)
                        avisoYoutube = null
                        encerrarLoginYoutube()
                        youtube.reiniciar()
                        Videos.expirar(this)
                        carregarVideos()
                    }
                    false -> principal.postDelayed(verificarLogin, c.intervaloS * 1000L)
                    else -> { mostrarAvisoYoutube(resultado.toString(), forcar = true); encerrarLoginYoutube() }
                }
            }
        }
    }

    private fun carregarCatalogo() {
        rede.execute {
            val logo = Logos.carregar(applicationContext, "netflix")
            val lista = Catalogo.carregar(applicationContext, Catalogo.NETFLIX)
            principal.post {
                logoNetflix = logo
                netflix.view?.logo = logo
                if (lista.isEmpty()) {
                    if (netflix.atual == null) netflix.view?.mostrarAviso(
                        if (Catalogo.temChave()) "Sem conexão com o TMDB agora. Tento de novo mais tarde." else avisoNetflix())
                } else {
                    netflix.definir(lista)
                }
            }
        }
    }

    private fun carregarVideos() {
        rede.execute {
            val logo = Logos.carregar(applicationContext, "youtube")
            val lista = Videos.carregar(applicationContext)
            principal.post {
                logoYoutube = logo
                youtube.view?.logo = logo
                if (lista.isEmpty()) {
                    if (youtube.atual == null) youtube.view?.mostrarAviso(avisoDoYoutube())
                } else {
                    if (codigoYoutube == null) avisoYoutube = null
                    youtube.definir(lista)
                    carregarMusicas()
                }
                // Login expirado (vale 7 dias enquanto o app do Google está em teste): mostra o código sozinho, 1x por abertura.
                if (Videos.temCredenciais() && !Videos.conectado(this) && codigoYoutube == null && !loginOferecido) {
                    loginOferecido = true
                    iniciarLoginYoutube()
                }
            }
        }
    }

    /**
     * Imagens dos cards de baixo (uma vez por abertura do app): filmes e séries em alta no Brasil
     * para Play Store e YouTube Music, e COD, Fortnite e Forza para o GeForce NOW.
     */
    /**
     * Imagens dos cards de baixo (uma vez por abertura do app):
     * GeForce NOW → COD MW4, Forza Horizon 6 e Crimson Desert; Google Play → cartazes dos apps instalados;
     * TV Aberta → arte fixa (não alterna). O YouTube Music vem de [carregarMusicas].
     */
    private fun carregarMidias() {
        val instalados = apps
        rede.execute {
            val novas = HashMap<String, List<Bitmap>>()
            if (midias["geforcenow"].isNullOrEmpty()) novas["geforcenow"] = Jogos.carregar(applicationContext)
            if (midias["tvaberta"].isNullOrEmpty()) novas["tvaberta"] = listOf(Artes.tvAberta())
            if (instalados.isNotEmpty()) {
                if (midias["playstore"].isNullOrEmpty()) {
                    novas["playstore"] = listOf("netflix", "youtube", "geforcenow", "twitch", "ytmusic", "playgames").mapNotNull { id ->
                        val a = instalados.firstOrNull { it.servico?.id == id } ?: return@mapNotNull null
                        val logo = a.logo ?: return@mapNotNull null
                        Artes.cartaz(logo, a.cor)
                    }
                }
            }
            principal.post { novas.forEach { (id, l) -> if (l.isNotEmpty()) usarMidias(id, l) } }
        }
        carregarMusicas()
    }

    /** YouTube Music: miniaturas das músicas que você curtiu (samba e pagode primeiro). */
    private fun carregarMusicas() {
        if (!midias["ytmusic"].isNullOrEmpty()) return
        rede.execute {
            val l = Videos.musicas(applicationContext).take(4).mapNotNull {
                Catalogo.imagem(applicationContext, it.backdrop, formato = Bitmap.Config.RGB_565, reduzir = 2)
            }
            principal.post { if (l.isNotEmpty()) usarMidias("ytmusic", l) }
        }
    }

    /** Cards que mostram uma imagem fixa, sem alternar com o logo. */
    private val cardsFixos = setOf("tvaberta")

    private fun usarMidias(id: String, l: List<Bitmap>) {
        midias[id] = l
        val card = cardsBaixo[id] ?: return
        card.midias = l
        if (id in cardsFixos) card.fixarMidia()
    }

    /** A cada 3,5 s, UM card de baixo troca (logo → imagem ou imagem → logo), um de cada vez. */
    private val alternarCards: Runnable = object : Runnable {
        override fun run() {
            val ids = Servicos.inicio.filter { it !in cardsFixos && !midias[it].isNullOrEmpty() && cardsBaixo.containsKey(it) }
            if (ids.isNotEmpty() && pagina == Pagina.INICIO && estadoVitrine == PARADA && !monitorAberto) {
                cardsBaixo[ids[vezCard % ids.size]]?.alternar()
                vezCard++
            }
            principal.postDelayed(this, TROCA_CARD_MS)
        }
    }

    // ---------- Vitrine automática ----------

    private val ocioso = Runnable { prepararVitrine() }
    private val desistir = Runnable { if (estadoVitrine == PREPARANDO) { cancelarPreparo(); reiniciarOcioso() } }

    /** Recomeça a contagem de 10 s sem mexer no controle (só na página inicial e com o TMDB). */
    private fun reiniciarOcioso() {
        principal.removeCallbacks(ocioso)
        if (VITRINE_LIGADA && pagina == Pagina.INICIO && estadoVitrine == PARADA && Catalogo.temChave() && vitrines.size == 2) {
            principal.postDelayed(ocioso, OCIOSO_MS)
        }
    }

    /**
     * Carrega fora da linha principal as duas vitrines da rodada (Netflix e "Em alta no Brasil").
     * Só entra quando as imagens grandes estiverem prontas; desiste depois de 2,5 s
     * (o download continua e fica no cache para a próxima vez).
     */
    private fun prepararVitrine() {
        if (pagina != Pagina.INICIO || estadoVitrine != PARADA || vitrines.size < 2) return
        estadoVitrine = PREPARANDO
        val pedido = ++tokenVitrine
        val rodada = rodadaVitrine++
        val atual = netflix.atual?.nome
        val logo = logoNetflix
        principal.postDelayed(desistir, 2500)
        rede.execute {
            val servicos = listOf(Catalogo.NETFLIX to "EM ALTA NA NETFLIX", Catalogo.BRASIL to "EM ALTA NO BRASIL")
            val dados = servicos.mapNotNull { (prov, chamada) ->
                val lista = Catalogo.carregar(applicationContext, prov)
                if (lista.isEmpty()) return@mapNotNull null
                // Varia o título a cada rodada e evita repetir o que já está no destaque.
                var t = lista[(rodada * 3 + 1) % lista.size]
                if (t.nome == atual) t = lista[(rodada * 3 + 2) % lista.size]
                val img = Catalogo.imagem(applicationContext, t.backdrop) ?: return@mapNotNull null
                val posters = lista.asSequence().filter { it.poster.isNotEmpty() }.take(9)
                    .mapNotNull { Catalogo.imagem(applicationContext, it.poster, "w185", Bitmap.Config.RGB_565) }.toList()
                DadosVitrine(chamada, if (prov == Catalogo.NETFLIX) logo else null, t, img, posters)
            }
            principal.post {
                if (pedido != tokenVitrine || estadoVitrine != PREPARANDO) return@post
                principal.removeCallbacks(desistir)
                if (dados.isEmpty()) { estadoVitrine = PARADA; reiniciarOcioso() } else mostrarVitrine(dados)
            }
        }
    }

    private fun cancelarPreparo() {
        principal.removeCallbacks(desistir)
        tokenVitrine++
        if (estadoVitrine == PREPARANDO) estadoVitrine = PARADA
    }

    /** Cards saem pela esquerda e a vitrine entra pela direita; depois a próxima; depois os cards voltam. */
    private fun mostrarVitrine(dados: List<DadosVitrine>) {
        val grade = gradeInicio
        if (grade == null || vitrines.size < 2 || conteudo.width == 0) { estadoVitrine = PARADA; return }
        estadoVitrine = NA_TELA
        netflix.pausar()
        youtube.pausar()
        // Recorta a área só durante a vitrine: sem isso os cards que saem passam por cima do menu.
        conteudo.clipChildren = true
        conteudo.clipBounds = Rect(0, 0, conteudo.width, conteudo.height)
        val w = conteudo.width.toFloat()

        val v1 = vitrines[0]
        v1.preparar(dados[0])
        deslizar(grade, v1, w)
        var fim = DESLIZE_MS + EXIBICAO_MS
        if (dados.size > 1) {
            val v2 = vitrines[1]
            passo(fim) { v2.preparar(dados[1]); deslizar(v1, v2, w) }
            fim += DESLIZE_MS + EXIBICAO_MS
            passo(fim) { deslizar(v2, grade, w) }
        } else {
            passo(fim) { deslizar(v1, grade, w) }
        }
        passo(fim + DESLIZE_MS) { terminarVitrine() }
    }

    private fun passo(atraso: Long, acao: () -> Unit) {
        val r = Runnable(acao)
        passosVitrine += r
        principal.postDelayed(r, atraso)
    }

    private fun deslizar(sai: View, entra: View, w: Float, duracao: Long = DESLIZE_MS) {
        sai.animate().cancel()
        entra.animate().cancel()
        entra.visibility = View.VISIBLE
        entra.translationX = w
        sai.animate().translationX(-w).setDuration(duracao).setInterpolator(DecelerateInterpolator(1.6f)).start()
        entra.animate().translationX(0f).setDuration(duracao).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    // ---------- Monitor do sistema (à direita no Início) ----------

    private var monitor: MonitorView? = null
    private var monitorAberto = false

    private fun abrirMonitor() {
        val m = monitor ?: return
        val grade = gradeInicio ?: return
        if (conteudo.width == 0) return
        monitorAberto = true
        netflix.pausar()
        youtube.pausar()
        conteudo.clipChildren = true
        conteudo.clipBounds = Rect(0, 0, conteudo.width, conteudo.height)
        m.ligar()
        deslizar(grade, m, conteudo.width.toFloat(), Tempo.DESLIZE)
    }

    /** Volta para os cards: deslizando (tecla) ou na hora (sair da página ou do app). */
    private fun fecharMonitor(rapido: Boolean) {
        if (!monitorAberto) return
        monitorAberto = false
        val m = monitor
        val grade = gradeInicio
        val fim = {
            m?.desligar()
            m?.visibility = View.GONE
            conteudo.clipBounds = null
            conteudo.clipChildren = false
            netflix.ligar()
            youtube.ligar()
        }
        if (!rapido || m == null || grade == null) {
            grade?.translationX = 0f
            fim()
            return
        }
        val w = conteudo.width.toFloat()
        m.animate().cancel()
        grade.animate().cancel()
        grade.translationX = -w
        m.animate().translationX(w).setDuration(Tempo.DESLIZE).setInterpolator(DecelerateInterpolator(1.6f)).start()
        grade.animate().translationX(0f).setDuration(Tempo.DESLIZE).setInterpolator(DecelerateInterpolator(1.6f)).withEndAction { fim() }.start()
    }

    /** Volta para os cards: rápido (tecla ou Home) ou na hora (ao sair da tela). */
    private fun fecharVitrine(rapido: Boolean) {
        passosVitrine.forEach { principal.removeCallbacks(it) }
        passosVitrine.clear()
        if (estadoVitrine == PREPARANDO) cancelarPreparo()
        if (estadoVitrine != NA_TELA) return
        estadoVitrine = FECHANDO
        val grade = gradeInicio
        if (!rapido || grade == null) { terminarVitrine(); return }
        val w = conteudo.width.toFloat()
        for (v in vitrines) if (v.visibility == View.VISIBLE) {
            v.animate().cancel()
            v.animate().translationX(w).setDuration(250).start()
        }
        grade.animate().cancel()
        grade.animate().translationX(0f).setDuration(250).withEndAction { terminarVitrine() }.start()
    }

    private fun terminarVitrine() {
        passosVitrine.forEach { principal.removeCallbacks(it) }
        passosVitrine.clear()
        for (v in vitrines) {
            v.animate().cancel()
            v.visibility = View.GONE
            v.translationX = 0f
            v.limpar()
        }
        gradeInicio?.let { it.animate().cancel(); it.translationX = 0f }
        conteudo.clipBounds = null
        conteudo.clipChildren = false
        estadoVitrine = PARADA
        netflix.ligar()
        youtube.ligar()
        reiniciarOcioso()
    }

    /** Para tudo sem animação (trocar de página ou sair do app). */
    private fun pararVitrine() {
        principal.removeCallbacks(ocioso)
        if (estadoVitrine == PREPARANDO) cancelarPreparo()
        if (estadoVitrine == NA_TELA || estadoVitrine == FECHANDO) terminarVitrine()
        passosVitrine.forEach { principal.removeCallbacks(it) }
        passosVitrine.clear()
    }

    /** Abre o app de TV da TCL direto no sintonizador (antena digital). */
    private fun abrirTvAberta() {
        val tm = getSystemService(TV_INPUT_SERVICE) as? TvInputManager
        val tuner = tm?.tvInputList?.firstOrNull { it.type == TvInputInfo.TYPE_TUNER && it.serviceInfo.packageName == "com.tcl.tvinput" }?.id
            ?: "com.tcl.tvinput/.TunerInputService/HW2"
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(TvContract.buildChannelsUriForInput(tuner), TvContract.Channels.CONTENT_TYPE)
            .setClassName("com.tcl.tv", "com.tcl.player.TVActivity")
        if (!abrir(i, avisar = false)) apps.firstOrNull { it.pacote == "com.tcl.tv" }?.let { abrirApp(it) }
    }

    private fun abrirPlayStore(busca: String) {
        abrir(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(busca))).setPackage("com.android.vending"))
    }

    /** Página com título e grade de cards pequenos (4 por linha). */
    private fun montarPagina(titulo: String, aviso: String?, lista: List<AppInfo>): View? {
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(dpi(Espaco.S), dpi(Espaco.S), 0, dpi(Espaco.L))
        }
        coluna.addView(texto(Tipo.TITULO, Tipo.FORTE).apply { text = titulo; setPadding(0, 0, 0, dpi(Espaco.XS)) })
        if (aviso != null) coluna.addView(texto(Tipo.CORPO, Tipo.REGULAR, Cores.TEXTO_SUAVE).apply { text = aviso; setPadding(0, dpi(2f), 0, 0) })
        var primeiro: View? = null
        if (lista.isEmpty()) {
            coluna.addView(texto(Tipo.CORPO, Tipo.REGULAR, Cores.TEXTO_SUAVE).apply { text = "Nenhum app aqui ainda."; setPadding(0, dpi(Espaco.L), 0, 0) })
        }
        lista.chunked(4).forEach { grupo ->
            val l = linha()
            for (i in 0 until 4) {
                val a = grupo.getOrNull(i)
                // Sem camada de vídeo fixa: dezenas de cards gastariam ~1 MB de memória gráfica cada
                // (a camada só existe durante a animação do foco).
                val card = CartaoView(this, true).apply { setLayerType(View.LAYER_TYPE_NONE, null) }
                if (a != null) { card.app = a; card.acao = { abrirApp(a) } } else { card.isFocusable = false; card.visibility = View.INVISIBLE }
                l.addView(card, LinearLayout.LayoutParams(0, -1, 1f))
                if (primeiro == null && a != null) primeiro = card
            }
            coluna.addView(l, LinearLayout.LayoutParams(-1, dpi(120f)).apply { topMargin = dpi(2f) })
        }
        val rolagem = ScrollView(this).apply {
            clipChildren = false; clipToPadding = false; isVerticalScrollBarEnabled = false
            isFocusable = false
            clipBounds = recorteRolagem()
            addView(coluna, ViewGroup.LayoutParams(-1, -2))
        }
        conteudo.addView(rolagem, FrameLayout.LayoutParams(-1, -1))
        return primeiro
    }

    // ---------- Filmes e Séries (só Netflix) ----------

    /** Fileiras de pôsteres da página aberta: imagens carregadas só perto de onde está o foco. */
    private class Fileira(val posters: List<PosterView>, val titulos: List<Titulo>) {
        var carregada = false
        var token = 0
    }
    private val fileiras = ArrayList<Fileira>()
    private var tokenPagina = 0
    private val imagensPool = Executors.newFixedThreadPool(2)
    private var netflixEmPe: Bitmap? = null

    /**
     * Páginas Filmes e Séries: só Netflix, em fileiras que rolam para o lado, como na Netflix.
     * Em alta, Recomendados para (perfil) e Porque você assistiu… (pelo histórico exportado),
     * mais bem avaliados, lançamentos e gêneros. Tudo filtrado para o que está na Netflix no Brasil.
     */
    private fun montarNetflix(filmes: Boolean): View? {
        val token = ++tokenPagina
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(dpi(Espaco.S), dpi(Espaco.S), dpi(Espaco.S), dpi(Espaco.XL))
        }
        coluna.addView(texto(Tipo.TITULO, Tipo.FORTE).apply { text = if (filmes) "Filmes" else "Séries" })
        coluna.addView(texto(Tipo.CORPO, Tipo.REGULAR, Cores.TEXTO_SUAVE).apply {
            text = "Tudo o que aparece aqui está na Netflix no Brasil (dados do TMDB; a lista pode não estar completa)."
            setPadding(0, dpi(Espaco.XS), 0, dpi(Espaco.XS))
        })
        val abrirNetflix = { apps.firstOrNull { it.servico?.id == "netflix" }?.let { abrirApp(it) } ?: abrirPlayStore("Netflix") }

        // Card da Netflix em pé, no começo da primeira fileira.
        val arte = netflixEmPe ?: Artes.netflixEmPe(logoNetflix).also { netflixEmPe = it }
        val cartao = PosterView(this, Titulo("Netflix", "", "", "", filmes)).apply {
            imagem = arte
            semNome = true
            acao = abrirNetflix
        }

        val rolagem = ScrollView(this).apply {
            clipChildren = false; clipToPadding = false; isVerticalScrollBarEnabled = false
            isFocusable = false
            clipBounds = recorteRolagem()
            addView(coluna, ViewGroup.LayoutParams(-1, -2))
        }
        conteudo.addView(rolagem, FrameLayout.LayoutParams(-1, -1))

        val tipo = if (filmes) "movie" else "tv"
        val hoje = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val generos = if (filmes) listOf("Ação" to 28, "Comédia" to 35, "Suspense" to 53, "Terror" to 27, "Animação" to 16, "Drama" to 18, "Ficção científica" to 878)
            else listOf("Ação e aventura" to 10759, "Comédia" to 35, "Crime" to 80, "Drama" to 18, "Animação" to 16, "Ficção e fantasia" to 10765, "Documentários" to 99)

        // Cada fileira aparece assim que seus dados chegam (do cache, na hora; da internet, 1 vez por dia).
        rede.execute {
            fun entregar(titulo: String, lista: List<Titulo>, comCartao: Boolean = false) {
                if (lista.isEmpty() && !comCartao) return
                principal.post { if (token == tokenPagina) adicionarFileira(coluna, titulo, lista, if (comCartao) cartao else null, abrirNetflix) }
            }
            val ctx = applicationContext
            entregar("Em alta na Netflix", Catalogo.secao(ctx, "pop_$tipo", tipo, "&sort_by=popularity.desc"), comCartao = true)
            val vistos = Historico.vistos(ctx)
            entregar("Recomendados para ${Historico.PERFIL}", Historico.recomendados(ctx, filmes, vistos))
            Historico.porqueAssistiu(ctx, filmes, vistos)?.let { (nome, l) -> entregar("Porque você assistiu $nome", l) }
            entregar("Mais bem avaliados", Catalogo.secao(ctx, "top_$tipo", tipo,
                "&sort_by=vote_average.desc&vote_count.gte=" + if (filmes) 800 else 300))
            entregar("Lançamentos", Catalogo.secao(ctx, "novos_$tipo", tipo,
                if (filmes) "&sort_by=primary_release_date.desc&vote_count.gte=30&primary_release_date.lte=$hoje"
                else "&sort_by=first_air_date.desc&vote_count.gte=20&first_air_date.lte=$hoje"))
            for ((nome, id) in generos) entregar(nome, Catalogo.secao(ctx, "g${id}_$tipo", tipo, "&sort_by=popularity.desc&with_genres=$id"))
        }
        return cartao
    }

    private fun adicionarFileira(coluna: LinearLayout, titulo: String, lista: List<Titulo>, cartao: PosterView?, acao: () -> Unit) {
        val indice = fileiras.size
        coluna.addView(texto(Tipo.SUBTITULO, Tipo.MEDIO).apply {
            text = titulo
            setPadding(dpi(2f), dpi(Espaco.M), 0, dpi(Espaco.XS))
        })
        val linhaPosters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
            setPadding(0, dpi(2f), dpi(8f), dpi(2f))
        }
        cartao?.let {
            it.aoFocar = { verFileira(indice) }
            linhaPosters.addView(it, LinearLayout.LayoutParams(dpi(122f), dpi(183f)))
        }
        val posters = lista.map { t ->
            PosterView(this, t).apply {
                this.acao = acao
                aoFocar = { verFileira(indice) }
                linhaPosters.addView(this, LinearLayout.LayoutParams(dpi(122f), dpi(183f)))
            }
        }
        val lado = android.widget.HorizontalScrollView(this).apply {
            clipChildren = false; clipToPadding = false; isHorizontalScrollBarEnabled = false
            isFocusable = false
            addView(linhaPosters)
        }
        coluna.addView(lado, LinearLayout.LayoutParams(-1, -2))
        fileiras += Fileira(posters, lista)
        // As duas primeiras fileiras já carregam; as outras, quando o foco chegar perto.
        if (indice <= 1) carregarFileira(fileiras[indice])
    }

    /** Foco numa fileira: carrega ela e as vizinhas; solta as imagens das que ficaram longe. */
    private fun verFileira(i: Int) {
        fileiras.forEachIndexed { j, f ->
            if (j in (i - 1)..(i + 1)) carregarFileira(f)
            else if (Math.abs(j - i) >= 3) soltarFileira(f)
        }
    }

    private fun carregarFileira(f: Fileira) {
        if (f.carregada) return
        f.carregada = true
        val token = ++f.token
        f.posters.forEachIndexed { k, p ->
            val t = f.titulos[k]
            imagensPool.execute {
                // Reduz para o tamanho em que o pôster aparece (metade da memória e da textura na placa).
                val img = Catalogo.imagem(applicationContext, t.poster, "w342", Bitmap.Config.RGB_565)?.let { o ->
                    reduzirComQualidade(o, dpi(116f), dpi(174f)).also { if (it !== o) o.recycle() }
                }
                principal.post { if (f.token == token && f.carregada) p.imagem = img else img?.recycle() }
            }
        }
    }

    private fun soltarFileira(f: Fileira) {
        if (!f.carregada) return
        f.carregada = false
        f.token++
        f.posters.forEach { it.soltar() }
    }

    /** Sai da página: solta todas as imagens das fileiras. */
    private fun soltarFileiras() {
        tokenPagina++
        fileiras.forEach { soltarFileira(it) }
        fileiras.clear()
    }

    /** Página Perfil (avatar): nome da saudação, estado do destaque, botões e créditos. */
    private fun montarPerfil(): View? {
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(Espaco.S), dpi(Espaco.S), dpi(Espaco.L), dpi(Espaco.L))
        }
        coluna.addView(texto(Tipo.TITULO, Tipo.FORTE).apply { text = "Perfil" })

        coluna.addView(texto(Tipo.CORPO, Tipo.REGULAR, Cores.TEXTO_SUAVE).apply { text = "Seu nome (aparece em \"Olá, nome\")"; setPadding(0, dpi(Espaco.L), 0, dpi(Espaco.S)) })
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val nome = EditText(this).apply {
            setText(prefs.getString("nome", ""))
            setTextColor(Cores.TEXTO)
            setHintTextColor(Cores.TEXTO_SUAVE)
            hint = "Digite seu nome"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Tipo.SUBTITULO)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = fundoBotao(campo = true)
            setPadding(dpi(Espaco.M), dpi(Espaco.S + 2f), dpi(Espaco.M), dpi(Espaco.S + 2f))
        }
        fun salvarNome() {
            prefs.edit().putString("nome", nome.text.toString().trim()).apply()
            atualizarSaudacao()
        }
        nome.setOnEditorActionListener { _, _, _ -> salvarNome(); false }
        nome.setOnFocusChangeListener { _, f -> if (!f) salvarNome() }
        coluna.addView(nome, LinearLayout.LayoutParams(dpi(320f), -2))

        val estado = when {
            !Catalogo.temChave() -> "Destaque: sem chave do TMDB (segredos.properties)."
            netflix.lista.isEmpty() -> "Destaque: nenhum título carregado ainda (sem internet ou TMDB fora do ar)."
            else -> "Destaque: ${netflix.lista.size} filmes e séries em alta na Netflix · atualizado em " +
                java.text.SimpleDateFormat("dd/MM 'às' HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(Catalogo.atualizadoEm(this, Catalogo.NETFLIX)))
        }
        coluna.addView(texto(Tipo.CORPO, Tipo.REGULAR, Cores.TEXTO_SUAVE).apply { text = estado; setPadding(0, dpi(Espaco.XL), 0, 0) })
        val estadoYt = when {
            !Videos.temCredenciais() -> "YouTube: falta o acesso do Google (YT_CLIENT_ID e YT_CLIENT_SECRET no segredos.properties)."
            !Videos.conectado(this) -> "YouTube: conta não conectada."
            else -> "YouTube: conta conectada · ${youtube.lista.size} vídeos (curtidos e novos dos seus canais)."
        }
        coluna.addView(texto(Tipo.CORPO, Tipo.REGULAR, Cores.TEXTO_SUAVE).apply { text = estadoYt; setPadding(0, dpi(Espaco.XS), 0, 0) })

        val botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(Espaco.L), 0, 0) }
        fun botao(rotulo: String, acao: () -> Unit) = texto(Tipo.CORPO, Tipo.MEDIO).apply {
            text = rotulo
            isFocusable = true
            isClickable = true
            background = fundoBotao()
            setPadding(dpi(Espaco.L), dpi(Espaco.M), dpi(Espaco.L), dpi(Espaco.M))
            setOnClickListener { acao() }
            botoes.addView(this, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dpi(Espaco.M) })
        }
        val atualizar = botao("Atualizar agora") {
            Toast.makeText(this, "Buscando os títulos em alta…", Toast.LENGTH_SHORT).show()
            Catalogo.expirar(this, Catalogo.NETFLIX)
            netflix.reiniciar()
            carregarCatalogo()
            Videos.expirar(this)
            youtube.reiniciar()
            carregarVideos()
        }
        botao("Configurações do Android") { abrirConfiguracoes() }
        if (Videos.temCredenciais()) {
            if (Videos.conectado(this)) botao("Desconectar YouTube") {
                Videos.desconectar(this)
                youtube.reiniciar()
                youtube.definir(emptyList())
                Toast.makeText(this, "Conta do YouTube desconectada.", Toast.LENGTH_SHORT).show()
                mostrar(Pagina.PERFIL)
            } else botao("Conectar YouTube") {
                // Espera o botão soltar antes de trocar de página: senão o mesmo toque/OK cai no destaque do YouTube e abre o app.
                principal.postDelayed({
                    mostrar(Pagina.INICIO)
                    loginOferecido = true
                    iniciarLoginYoutube()
                }, 300)
            }
        }
        coluna.addView(botoes)

        coluna.addView(texto(Tipo.LEGENDA, Tipo.REGULAR, Cores.TEXTO_SUAVE).apply {
            text = "Créditos\n" +
                "Este produto usa a API do TMDB, mas não é endossado nem certificado pelo TMDB.\n" +
                "Logos: Wikimedia Commons (domínio público). As marcas pertencem aos seus titulares; uso pessoal.\n" +
                "Lume TV ${BuildConfig.VERSION_NAME}"
            setLineSpacing(dpi(Espaco.XS).toFloat(), 1f)
            setPadding(0, dpi(Espaco.XL), 0, 0)
        })

        val rolagem = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(coluna, ViewGroup.LayoutParams(-1, -2))
        }
        conteudo.addView(rolagem, FrameLayout.LayoutParams(-1, -1))
        return atualizar
    }

    /**
     * Fundo de botão/campo: superfície neutra; no foco, vermelho cheio (óbvio a 3 m).
     * [campo]: no foco o campo de texto ganha só um contorno vermelho (o cursor continua visível).
     */
    private fun fundoBotao(campo: Boolean = false): StateListDrawable {
        fun forma(cor: Int, contorno: Int = 0) = GradientDrawable().apply {
            cornerRadius = dp(Raio.CONTROLE)
            setColor(cor)
            if (contorno != 0) setStroke(dpi(2f), contorno)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                if (campo) forma(Cores.CARTAO, Cores.VERMELHO) else forma(Cores.VERMELHO))
            addState(intArrayOf(), forma(Cores.CARTAO))
        }
    }

    /** Recorte do conteúdo que rola: não invade o cabeçalho nem o menu (o anel de foco já fica dentro da folga dos cards). */
    private fun recorteRolagem() = Rect(0, 0, 100_000, 100_000)

    private fun linha() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }

    // ---------- Ações ----------

    /**
     * Abre o app sempre na página inicial dele. Muitos apps de TV (YouTube, Netflix…) reabrem
     * a última tela (ex.: um vídeo pausado); encerrar o processo em segundo plano antes faz
     * eles começarem do zero, na aba Início da conta. Não mexe em dados nem em login.
     */
    private fun abrirApp(a: AppInfo) {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        try { am.killBackgroundProcesses(a.pacote) } catch (e: Exception) { }
        abrir(a.intent)
    }

    private fun abrirConfiguracoes() { abrir(Intent(Settings.ACTION_SETTINGS)) }

    private fun abrirPesquisa() {
        if (!abrir(Intent("android.search.action.GLOBAL_SEARCH"), avisar = false)) abrir(Intent(Intent.ACTION_ASSIST))
    }

    private fun abrir(i: Intent, avisar: Boolean = true): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        if (avisar) Toast.makeText(this, "Não foi possível abrir.", Toast.LENGTH_SHORT).show()
        false
    }

    // ---------- Relógio e saudação ----------

    private val tique = Runnable { atualizarRelogio() }

    private fun atualizarRelogio() {
        val c = Calendar.getInstance()
        hora.text = String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        val dias = arrayOf("Dom", "Seg", "Ter", "Qua", "Qui", "Sex", "Sáb")
        val meses = arrayOf("Jan", "Fev", "Mar", "Abr", "Mai", "Jun", "Jul", "Ago", "Set", "Out", "Nov", "Dez")
        data.text = "${dias[c.get(Calendar.DAY_OF_WEEK) - 1]}, ${c.get(Calendar.DAY_OF_MONTH)} de ${meses[c.get(Calendar.MONTH)]}"
        principal.removeCallbacks(tique)
        principal.postDelayed(tique, 60_000L - (c.get(Calendar.SECOND) * 1000L + c.get(Calendar.MILLISECOND)) + 50)
    }

    private fun atualizarSaudacao() {
        val nome = getSharedPreferences("prefs", MODE_PRIVATE).getString("nome", "").orEmpty().trim()
        saudacao.text = if (nome.isEmpty()) "Olá," else "Olá, $nome"
    }

    // ---------- Utilidades ----------

    private fun texto(sp: Float, tf: Typeface, cor: Int = Cores.TEXTO) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = tf
        setTextColor(cor)
        includeFontPadding = false
    }

    private val posFoco = IntArray(2)

    private fun avisarFundo(v: View) {
        val raiz = window.decorView
        if (raiz.width == 0) return
        v.getLocationOnScreen(posFoco)
        val cx = posFoco[0] + v.width / 2f
        val cy = posFoco[1] + v.height / 2f
        fundo.focoEm(cx / raiz.width * 2f - 1f, cy / raiz.height * 2f - 1f)
    }

    // ---------- Menu dinâmico ----------

    private var paginaPendente: Pagina? = null
    private val trocaMenu = Runnable {
        val p = paginaPendente ?: return@Runnable
        // Só troca se o foco ainda estiver NESSE item (a pessoa parou nele; passar por ele não conta).
        if (p != pagina && currentFocus === itensMenu[p]) mostrar(p, manterFoco = true)
    }

    /** Foco num item do menu: abre a página depois de 0,2 s parado ali (passar rápido não monta cada página). */
    private fun trocarPeloMenu(p: Pagina) {
        paginaPendente = p
        principal.removeCallbacks(trocaMenu)
        principal.postDelayed(trocaMenu, 200)
    }

    private fun ehDescendente(v: View, pai: ViewGroup): Boolean {
        var p = v.parent
        while (p != null) { if (p === pai) return true; p = p.parent }
        return false
    }
}
