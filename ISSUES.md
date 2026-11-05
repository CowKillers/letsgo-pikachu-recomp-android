# ISSUES.md — Problemas conhecidos do Let's Go Pikachu Android

Estado em **05/11/2026, v0015**. Tudo aqui foi verificado com logs do Galaxy S23
(Snapdragon 8 Gen 2, Adreno 740, Android 16) ou com prova no host. Nada aqui é palpite sem prova.

## 1. Contador de FPS — RESOLVIDO na v0015 (espelho da v0118 do Eevee)

**Era:** dois contadores ao mesmo tempo — na esquerda o `30 fps` vertical (janela ImGui
`##fps`, torta por causa da rotação de 90° do swapchain) e na direita o `30 fps 34 ms`
verde (TouchControls). O botão **Mostrar FPS** do launcher controlava só o da esquerda.

**Fix:** a janela da esquerda saiu do runtime; o Mostrar FPS agora controla o da direita
(novo getter JNI `nativeGetShowFps`, lido pelo TouchControls a cada tick); o botão FPS da
gaveta de toque foi removido — o do launcher é o único controle. **Teste pendente no S23:**
Mostrar FPS ligado → só o contador da direita; desligado → some também.

## 2. Importador: self-test de cripto falha no hardware do S23 (v0116) — contornado na v0117

**Sintoma:** a v0116 abortava o import no S23 com `crypto self-test failed`. O caminho de
hardware ARMv8 (AES/SHA-256/CRC32) só tinha sido validado no software em host x86_64.

**Fix (v0117):** se o self-test do hardware falhar, o importador desliga os caminhos de
hardware e **continua no software**; cada check (`aes-fips197-enc/dec`, `sha256-abc`,
`sha256-vec56`, `sha256-million`, `crc32`, `crc32-chain`, `aes-ctr`, `aes-ctr-tail`) loga
`OK`/`FAILED` com o primeiro byte divergente, para o próximo log no aparelho dizer onde
está o bug do hardware. **Prova no host com o NSP do Pikachu (05/11):** import fresco em
76 s, EXIT 0, 9/9 checks OK, `.bin` direto de 4.427.630.560 bytes, 27.820 arquivos,
conteúdo idêntico à extração solta (0 bytes divergentes). **Teste pendente:** import com
o NSP do Pikachu no S23, com o log dizendo qual check do hardware falhou.

## 3. Seletor de pastas bloqueado na raiz de Download (Samsung) — contornado na v0014

**Sintoma:** o seletor SAF mostra `Não é possível usar essa pasta` na raiz de Download,
botão USAR ESTA PASTA cinza; pasta criada não aparece na lista.

**Causa:** o Android bloqueia a raiz de Download (e `Android/data`, `Android/obb`) no
seletor de pastas desde o Android 11 — não é o launcher. O seletor também só lista pastas
(arquivos ficam ocultos) e muitas vezes não atualiza depois de criar uma pasta.

**Fix (v0014):** o launcher procura os dados já importados sozinho ao abrir; o submenu
Importação ganhou **Procurar dados já importados** e **Digitar pasta do jogo** (escreve o
caminho, sem seletor); o seletor abre numa subpasta válida. **Resolvido no S23 em 05/11:**
reiniciar o celular e desligar Shizuku e Brevent — primeiro passo quando o seletor travar,
antes de mexer em permissão ou build.

## 4. Segundo plano: tela preta ao voltar ao app (aberto, sem fix)

Ao fechar e reabrir, o app pode voltar direto para a activity do jogo (preta, sem frame
apresentado) em vez do launcher. Hipótese: activity do jogo é `singleTask` +
`alwaysRetainTaskState`. Só volta com forçar parada. Pendente: como o app é fechado e o
que aparece ao reabrir.

## 5. Picos de lentidão em troca de cena (parcialmente tratado)

Log_13: média 28,4 fps (teto 30), pior spike de 145 ms em troca de cena/área (compile de
shader/pipeline na demanda + upload sustentado + wait em fence). **Fix (v0011):** log
`[gcopy]` por frame desligado e cache de pipeline (`files/cache/vk_pipelines.bin`,
carrega no boot, salva por worker). **Teste pendente:** jogar numa cena nova completa e
mandar log novo — o log diz se o cache carregou e se os picos persistem. Resolução 1x
(720p) e SMAA desligado já foram confirmados como não-causa.

## 6. Teclado de nome — RESOLVIDO na v0006/v0009 (teste visual pendente)

O painel ImGui de nome abria virado/vertical, sem dar para ver o nome. Fix: diálogo
nativo Android (EditText + teclado do sistema, horizontal). A v0009 corrigiu o
`FindClass` da thread nativa (a classe agora é salva como global ref no `nativeSetup`).
**Teste pendente:** na tela de nome, conferir que abre o diálogo creme horizontal.

## 7. Sensor de rotação — RESOLVIDO na v0010

O sensor ativava ao jogar porque a activity setava `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`.
Fix: `SCREEN_ORIENTATION_LANDSCAPE` fixo. GyroAim continua opt-in (checkbox, pref
`gyro=false`). **Teste pendente:** abrir o jogo e conferir que o sensor não ativa mais.

## Como ajudar

Logs úteis: `[gcopy]`, `[vk] pipeline cache`, `[swkbd]`, `self-test`, `[perf]`.
Screenshot da tela de nome vertical: `screenshots/01-tela-nome-vertical.jpg`.
