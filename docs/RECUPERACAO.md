# Como voltar para a tela inicial do Google TV

Testado numa TCL P7 de 50" (Google TV, Android 12). Troque `IP_DA_TV` pelo IP da sua TV e conecte antes com `adb connect IP_DA_TV:5555`.

## O que muda para o Lume TV ser a tela inicial

Dois apps do sistema são **desativados (não desinstalados)**:

| Pacote | O que é |
|---|---|
| `com.google.android.apps.tv.launcherx` | A tela inicial original do Google TV |
| `com.google.android.tungsten.setupwraith` | O configurador do Google TV. A "tela de recuperação" dele assume o botão Home quando a tela inicial está desativada, e a TV não deixa desativar só essa tela |

Nada é desinstalado e não é preciso root. Uma restauração de fábrica reativa tudo.

## Voltar pelo computador

```
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
```

Para deixar o Lume TV como tela inicial de novo:

```
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell cmd package set-home-activity com.exemplo.novatv/.MainActivity
```

## Voltar pela própria TV (sem computador)

No Lume TV, abra **Config.** no menu lateral e depois **Apps → Ver todos os apps → Mostrar apps do sistema**. Em cada um dos dois apps abaixo, escolha **Ativar**:

1. **Google TV Home** (ou "Android TV Home")
2. O **configurador do Google TV** (setupwraith; o nome que aparece na tela pode variar)

Depois aperte Home. Se a TV perguntar qual tela inicial usar, escolha o Google TV.

## Serviço "Lume TV – botões do controle"

Faz os botões Netflix, YouTube, engrenagem e TV do controle funcionarem.

- **Desligar pela TV:** Configurações → Acessibilidade → **Lume TV – botões do controle** → Desativar.
- **Desligar pelo computador** (desliga todos os serviços de acessibilidade):
  ```
  adb shell settings put secure enabled_accessibility_services ""
  adb shell settings put secure accessibility_enabled 0
  ```
- **Religar pelo computador:**
  ```
  adb shell settings put secure enabled_accessibility_services com.exemplo.novatv/com.exemplo.novatv.TeclasService
  adb shell settings put secure accessibility_enabled 1
  ```

> [!WARNING]
> **"Forçar parada" do Lume TV desliga o serviço** (regra do Android). Atualizar ou reinstalar o app não desliga. Se os botões pararem, religue como acima.

## Testes feitos na TCL P7

- Home a partir das Configurações e de dentro do YouTube: abriu o Lume TV.
- Volta com `pm enable`: o Home voltou para o Google TV. Depois, os dois apps foram desativados de novo.
- Reinício da TV (`adb reboot`): o ADB voltou em 28 s, o Lume TV abriu sozinho e os dois apps continuaram desativados.
