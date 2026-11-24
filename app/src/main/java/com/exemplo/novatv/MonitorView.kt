package com.exemplo.novatv

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import java.io.File
import java.net.Inet4Address

/**
 * Monitor do sistema (card de tela toda, à direita no Início): CPU, memória, armazenamento,
 * temperatura (estado térmico do Android), FPS da interface, rede e sistema. Só atualiza
 * enquanto está na tela. Em Android 12 o app não pode ler /proc/stat nem os sensores de
 * temperatura: a carga da CPU é estimada pela frequência dos núcleos.
 */
class MonitorView(ctx: Context) : View(ctx) {
    private val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val vidro = Vidro(ctx)
    private val pFundo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.comAlfa(Cores.FUNDO, 245) }
    private val pTile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.CARTAO }
    private val pAnelFundo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Cores.comAlfa(Cores.TEXTO, 30) }
    private val pAnel = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Cores.VERMELHO }
    private val pBarra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.VERMELHO }
    private val pBarraFundo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.comAlfa(Cores.TEXTO, 30) }
    private val pTitulo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.TEXTO; typeface = Tipo.FORTE }
    private val pRotulo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.TEXTO_SUAVE; letterSpacing = 0.1f; typeface = Tipo.MEDIO }
    private val pValor = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.TEXTO; typeface = Tipo.NUMERO }
    private val pFps = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Tipo.NUMERO }
    private val pTexto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cores.TEXTO_SUAVE; typeface = Tipo.REGULAR }
    private val r = RectF()
    private val arco = RectF()

    // Textos prontos: montados 1x por segundo em ler(), não a cada quadro (evita lixo de memória a 60 qps).
    private val cabecalho = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} · ${Build.HARDWARE.uppercase()} · Android ${Build.VERSION.RELEASE}  ·  ◀ voltar"
    private val rotulos = arrayOf("CPU", "MEMÓRIA RAM", "ARMAZENAMENTO", "TEMPERATURA")
    private var detalhes: Array<Array<String>> = Array(4) { arrayOf("") }
    private var tela = ""
    private var taxaTela = 60
    private var linhasRede = arrayOf<String>()
    private var linhasSistema = arrayOf<String>()

    // Leituras (atualizadas 1x por segundo).
    private var cpuCarga = 0f
    private var cpuFreqs = FloatArray(0)
    private var cpuMax = FloatArray(0)
    private var cpuReal = false
    private var appCpu = 0f
    private var ramUsada = 0L; private var ramTotal = 1L
    private var discoUsado = 0L; private var discoTotal = 1L
    private var termico = 0
    private var folgaTermica = Float.NaN
    private var rede = "—"; private var ip = "—"; private var sinal = ""
    private var appMem = 0L
    private var fps = 0
    // Valores mostrados (andam suave até as leituras).
    private val mostrado = FloatArray(4)

    private var ligado = false
    private var quadros = 0
    private var inicioJanela = 0L
    private var ultimoStat: LongArray? = null
    private var ultimoCpuApp = 0L
    private var ultimoTempo = 0L
    private var leituras = 0

    private val porQuadro = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            if (!ligado) return
            quadros++
            val agora = SystemClock.uptimeMillis()
            if (agora - inicioJanela >= 1000) {
                fps = (quadros * 1000 / (agora - inicioJanela)).toInt()
                quadros = 0
                inicioJanela = agora
                ler()
            }
            invalidate() // redesenha a cada quadro: o FPS medido é o que a interface consegue entregar
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        setLayerType(LAYER_TYPE_NONE, null)
    }

    fun ligar() {
        if (ligado) return
        ligado = true
        inicioJanela = SystemClock.uptimeMillis()
        quadros = 0
        ler()
        Choreographer.getInstance().postFrameCallback(porQuadro)
    }

    fun desligar() {
        ligado = false
        Choreographer.getInstance().removeFrameCallback(porQuadro)
    }

    override fun onDetachedFromWindow() {
        desligar()
        super.onDetachedFromWindow()
    }

    // ---------- Leituras ----------

    private fun ler() {
        lerCpu()
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        ramTotal = mi.totalMem
        ramUsada = mi.totalMem - mi.availMem
        try {
            val st = StatFs(Environment.getDataDirectory().path)
            discoTotal = st.totalBytes
            discoUsado = st.totalBytes - st.availableBytes
        } catch (e: Exception) { }
        termico = pm.currentThermalStatus
        folgaTermica = try { pm.getThermalHeadroom(0) } catch (e: Exception) { Float.NaN }
        lerRede()
        if (leituras++ % 3 == 0) appMem = Debug.getPss() * 1024L // medir a própria memória custa alguns ms: a cada 3 s
        montarTextos()
    }

    private fun montarTextos() {
        rotulos[0] = if (cpuReal) "CPU" else "CPU (estimada)"
        detalhes = arrayOf(
            arrayOf("${cpuFreqs.size} núcleos · ${cpuFreqs.maxOrNull()?.toInt() ?: 0} MHz", "Lume TV ${(appCpu * 100).toInt()}%"),
            arrayOf("${gb(ramUsada)} de ${gb(ramTotal)} em uso"),
            arrayOf("${gb(discoUsado)} de ${gb(discoTotal)} usados"),
            arrayOf(if (folgaTermica.isNaN()) "Estado térmico do Android" else "Folga até limitar: ${((1 - folgaTermica) * 100).toInt().coerceIn(0, 100)}%"),
        )
        taxaTela = display?.refreshRate?.toInt() ?: 60
        val modo = display?.mode
        tela = "Tela: $taxaTela Hz · ${modo?.physicalWidth ?: 0}x${modo?.physicalHeight ?: 0}"
        linhasRede = arrayOf(rede, "IP: $ip", sinal)
        linhasSistema = arrayOf(
            "Ligada há ${tempoLigada()}",
            "Interface: ${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}",
            "Lume TV usa ${mb(appMem)}",
        )
    }

    private fun lerCpu() {
        val n = (File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) }?.size ?: 0)
            .coerceAtLeast(Runtime.getRuntime().availableProcessors())
        if (cpuFreqs.size != n) { cpuFreqs = FloatArray(n); cpuMax = FloatArray(n) }
        for (i in 0 until n) {
            cpuFreqs[i] = lerNumero("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq") / 1000f
            cpuMax[i] = lerNumero("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq") / 1000f
        }
        // Uso real, se o sistema deixar ler /proc/stat; senão, estimativa pela frequência dos núcleos.
        val stat = try {
            File("/proc/stat").bufferedReader().use { it.readLine() }.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }.toLongArray()
        } catch (e: Exception) { null }
        if (stat != null && stat.size >= 4) {
            val ant = ultimoStat
            if (ant != null) {
                val total = stat.sum() - ant.sum()
                val ocioso = (stat[3] + stat.getOrElse(4) { 0 }) - (ant[3] + ant.getOrElse(4) { 0 })
                if (total > 0) cpuCarga = (1f - ocioso.toFloat() / total).coerceIn(0f, 1f)
            }
            ultimoStat = stat
            cpuReal = true
        } else {
            val validos = cpuFreqs.indices.filter { cpuMax[it] > 0 }
            cpuCarga = if (validos.isEmpty()) 0f else validos.map { cpuFreqs[it] / cpuMax[it] }.average().toFloat()
            cpuReal = false
        }
        // CPU usada pelo próprio Lume TV.
        val cpuApp = Process.getElapsedCpuTime()
        val agora = SystemClock.elapsedRealtime()
        if (ultimoTempo > 0) {
            appCpu = ((cpuApp - ultimoCpuApp).toFloat() / ((agora - ultimoTempo) * n)).coerceIn(0f, 1f)
        }
        ultimoCpuApp = cpuApp
        ultimoTempo = agora
    }

    private fun lerNumero(caminho: String): Float = try {
        File(caminho).readText().trim().toFloat()
    } catch (e: Exception) { 0f }

    private fun lerRede() {
        val n = cm.activeNetwork
        val caps = n?.let { cm.getNetworkCapabilities(it) }
        rede = when {
            caps == null -> "Sem conexão"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Cabo (Ethernet)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            else -> "Conectado"
        }
        ip = n?.let { cm.getLinkProperties(it) }?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress ?: "—"
        sinal = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
            @Suppress("DEPRECATION")
            val rssi = wm.connectionInfo?.rssi ?: -127
            if (rssi > -127) "Sinal ${WifiManager.calculateSignalLevel(rssi, 5) * 25}% ($rssi dBm)" else ""
        } else ""
    }

    // ---------- Desenho ----------

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        vidro.montar(w, h, context.dp(Raio.SUPERFICIE))
        pTitulo.textSize = context.dp(Tipo.TITULO)
        pRotulo.textSize = context.dp(Tipo.LEGENDA)
        pValor.textSize = context.dp(Tipo.TITULO)
        pFps.textSize = context.dp(40f)
        pTexto.textSize = context.dp(Tipo.LEGENDA)
        pAnel.strokeWidth = context.dp(7f)
        pAnelFundo.strokeWidth = context.dp(7f)
    }

    override fun onDraw(c: Canvas) {
        val area = vidro.rect
        val raioSup = vidro.raio
        c.drawRoundRect(area, raioSup, raioSup, pFundo)

        val m = context.dp(Espaco.XL)
        val gap = context.dp(Espaco.M)
        val pad = context.dp(Espaco.L)
        val x = area.left + m
        val y = area.top + m + pTitulo.textSize
        c.drawText("MONITOR DO SISTEMA", x, y, pTitulo)
        pTexto.textAlign = Paint.Align.RIGHT
        c.drawText(cabecalho, area.right - m, y, pTexto)
        pTexto.textAlign = Paint.Align.LEFT

        // Valores animados até a leitura atual.
        val alvos = floatArrayOf(cpuCarga, ramUsada.toFloat() / ramTotal, discoUsado.toFloat() / discoTotal,
            if (folgaTermica.isNaN()) termico / 6f else folgaTermica.coerceIn(0f, 1f))
        for (i in 0..3) mostrado[i] += (alvos[i] - mostrado[i]) * 0.12f

        // Linha 1: quatro medidores circulares.
        val topo1 = y + context.dp(Espaco.L)
        val alt1 = (area.height() - (topo1 - area.top) - m) * 0.5f
        val larg = (area.width() - m * 2 - gap * 3) / 4f
        for (i in 0..3) {
            val tx = area.left + m + i * (larg + gap)
            r.set(tx, topo1, tx + larg, topo1 + alt1)
            tile(c)
            c.drawText(rotulos[i], tx + pad, topo1 + pad + pRotulo.textSize, pRotulo)
            val raio = minOf(larg, alt1) * 0.27f
            val cx = tx + larg / 2f; val cy = topo1 + alt1 * 0.5f
            arco.set(cx - raio, cy - raio, cx + raio, cy + raio)
            c.drawArc(arco, 135f, 270f, false, pAnelFundo)
            pAnel.color = corPorNivel(mostrado[i])
            c.drawArc(arco, 135f, 270f * mostrado[i].coerceIn(0f, 1f), false, pAnel)
            pValor.textAlign = Paint.Align.CENTER
            val valor = if (i == 3) estadoTermico() else PCT[(mostrado[i] * 100).toInt().coerceIn(0, 100)]
            c.drawText(valor, cx, cy + pValor.textSize * 0.35f, pValor)
            pValor.textAlign = Paint.Align.LEFT
            // Detalhe em até duas linhas, sem passar da largura do quadro.
            pTexto.textAlign = Paint.Align.CENTER
            val linhasDet = detalhes[i]
            var ly = topo1 + alt1 - pad - (linhasDet.size - 1) * pTexto.textSize * 1.4f
            for (l in linhasDet) { c.drawText(l, cx, ly, pTexto); ly += pTexto.textSize * 1.4f }
            pTexto.textAlign = Paint.Align.LEFT
        }

        // Linha 2: núcleos da CPU, FPS, rede e sistema.
        val topo2 = topo1 + alt1 + gap
        val alt2 = area.bottom - m - topo2
        var tx = area.left + m

        r.set(tx, topo2, tx + larg, topo2 + alt2); tile(c)
        c.drawText("NÚCLEOS DA CPU", tx + pad, topo2 + pad + pRotulo.textSize, pRotulo)
        val n = cpuFreqs.size.coerceAtLeast(1)
        val hb = (alt2 - context.dp(44f)) / n
        val raioBarra = context.dp(3f)
        for (i in 0 until cpuFreqs.size) {
            val by = topo2 + context.dp(34f) + i * hb
            val frac = if (cpuMax[i] > 0) cpuFreqs[i] / cpuMax[i] else 0f
            val bx = tx + context.dp(70f); val bw = larg - context.dp(70f) - pad
            c.drawText(NUCLEOS[i.coerceAtMost(NUCLEOS.size - 1)], tx + pad, by + hb * 0.6f, pTexto)
            c.drawRoundRect(bx, by + hb * 0.25f, bx + bw, by + hb * 0.65f, raioBarra, raioBarra, pBarraFundo)
            pBarra.color = corPorNivel(frac)
            c.drawRoundRect(bx, by + hb * 0.25f, bx + bw * frac.coerceIn(0.02f, 1f), by + hb * 0.65f, raioBarra, raioBarra, pBarra)
        }

        tx += larg + gap
        r.set(tx, topo2, tx + larg, topo2 + alt2); tile(c)
        c.drawText("FPS DA INTERFACE", tx + pad, topo2 + pad + pRotulo.textSize, pRotulo)
        pFps.color = corPorNivel(1f - (fps.toFloat() / taxaTela).coerceIn(0f, 1f))
        c.drawText(if (fps in 0..240) NUM[fps] else "$fps", tx + pad, topo2 + alt2 * 0.62f, pFps)
        c.drawText(tela, tx + pad, topo2 + alt2 - pad, pTexto)

        tx += larg + gap
        r.set(tx, topo2, tx + larg, topo2 + alt2); tile(c)
        c.drawText("REDE", tx + pad, topo2 + pad + pRotulo.textSize, pRotulo)
        linhas(c, tx, topo2, linhasRede)

        tx += larg + gap
        r.set(tx, topo2, tx + larg, topo2 + alt2); tile(c)
        c.drawText("SISTEMA", tx + pad, topo2 + pad + pRotulo.textSize, pRotulo)
        linhas(c, tx, topo2, linhasSistema)
    }

    private fun tile(c: Canvas) {
        val rr = context.dp(Raio.SUPERFICIE)
        c.drawRoundRect(r, rr, rr, pTile)
    }

    private fun linhas(c: Canvas, tx: Float, topo: Float, ls: Array<String>) {
        var y = topo + context.dp(Espaco.L) + pRotulo.textSize + context.dp(28f)
        for (l in ls) {
            if (l.isEmpty()) continue
            c.drawText(l, tx + context.dp(Espaco.L), y, pTexto)
            y += pTexto.textSize * 1.9f
        }
    }

    /** Níveis dentro da paleta: neutro (tranquilo), vermelho fraco (atenção), vermelho (alto). */
    private fun corPorNivel(f: Float): Int = when {
        f < 0.6f -> Cores.TEXTO
        f < 0.85f -> Cores.comAlfa(Cores.VERMELHO, 150)
        else -> Cores.VERMELHO
    }

    private fun estadoTermico() = when (termico) {
        PowerManager.THERMAL_STATUS_NONE -> "Normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "Morna"
        PowerManager.THERMAL_STATUS_MODERATE -> "Quente"
        PowerManager.THERMAL_STATUS_SEVERE -> "Muito quente"
        else -> "Crítica"
    }

    private fun gb(b: Long) = String.format("%.1f GB", b / 1_073_741_824.0)
    private fun mb(b: Long) = "${b / 1_048_576} MB"

    private fun tempoLigada(): String {
        val s = SystemClock.elapsedRealtime() / 1000
        val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
        return if (d > 0) "${d}d ${h}h ${m}min" else "${h}h ${m}min"
    }

    companion object {
        private val PCT = Array(101) { "$it%" }
        private val NUM = Array(241) { "$it" }
        private val NUCLEOS = Array(16) { "Núcleo $it" }
    }
}
