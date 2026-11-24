package com.exemplo.novatv

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.content.Intent
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Botões do controle TCL que eram atendidos pela tela inicial do Google TV (desativada para o
 * Lume TV ser a tela inicial): Netflix, YouTube, engrenagem (Configurações) e TV (antena).
 * Como serviço de acessibilidade, funciona em qualquer tela da TV, não só no Lume TV.
 */
class TeclasService : AccessibilityService() {

    /** App que está na tela agora (para não roubar o botão TV dentro do próprio app de TV). */
    private var appNaTela: String? = null

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) e.packageName?.let { appNaTela = it.toString() }
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(e: KeyEvent): Boolean {
        val acao: (() -> Unit) = when (e.keyCode) {
            NETFLIX -> { { abrirPacote("com.netflix.ninja") } }
            YOUTUBE -> { { abrirPacote("com.google.android.youtube.tv") } }
            KeyEvent.KEYCODE_NOTIFICATION, KeyEvent.KEYCODE_SETTINGS -> { { abrir(Intent(Settings.ACTION_SETTINGS)) } }
            // Dentro do app de TV o botão continua sendo o guia de programação dele.
            TV -> if (appNaTela == PACOTE_TV) return false else { { abrirTv() } }
            else -> return false
        }
        // Age ao soltar o botão; o "apertar" também é consumido para nenhum outro app reagir.
        if (e.action == KeyEvent.ACTION_UP && e.repeatCount == 0) acao()
        return true
    }

    /** Abre o app na página inicial dele (encerra a instância em segundo plano antes, como os cards). */
    private fun abrirPacote(pacote: String) {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pacote)
            ?: packageManager.getLaunchIntentForPackage(pacote) ?: return
        try { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pacote) } catch (ex: Exception) { }
        abrir(i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    /** App de TV da TCL direto no sintonizador (antena digital), igual ao card TV Aberta. */
    private fun abrirTv() {
        val tm = getSystemService(TV_INPUT_SERVICE) as? TvInputManager
        val tuner = tm?.tvInputList?.firstOrNull { it.type == TvInputInfo.TYPE_TUNER && it.serviceInfo.packageName == "com.tcl.tvinput" }?.id
            ?: "com.tcl.tvinput/.TunerInputService/HW2"
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(TvContract.buildChannelsUriForInput(tuner), TvContract.Channels.CONTENT_TYPE)
            .setClassName(PACOTE_TV, "com.tcl.player.TVActivity")
        if (!abrir(i)) packageManager.getLeanbackLaunchIntentForPackage(PACOTE_TV)?.let { abrir(it) }
    }

    private fun abrir(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (ex: Exception) {
        false
    }

    companion object {
        // Códigos próprios da TCL (descobertos no controle desta TV).
        const val NETFLIX = 4062
        const val YOUTUBE = 4065
        const val TV = 4001
        const val PACOTE_TV = "com.tcl.tv"
    }
}
