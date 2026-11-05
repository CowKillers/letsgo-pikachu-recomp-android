# Mapa de desempenho — Let's Go Pikachu, log_13 (05/11/2026 08:03)

Log: `letsgo-log_13_n7qi.txt`, 2683 linhas, t=529–563 s, frames 15897–16866.
Jogo roda a 30 fps nativo (teto 30). Média no log: 28,4 fps, min 23,0, max 30,0.
Orçamento por frame a 30 fps: 33,3 ms.

## O que o log prova (números, por janela de 2 s)

| janela | fps | max ms | draws | passes | upload | wait | compile |
|---|---|---|---|---|---|---|---|
| t=529–551 (cena pesada) | 28–30 | 38–51 | 27–31k | 2784–2925 | 930–1173 MB | 253–430 ms | 0 |
| t=537–539 (spike shader) | 23,0–23,8 | 80–82 | 22–27k, 43–61 pulados | 2256–2304 | 890–1000 MB | 344–359 ms | 53–65 shaders, pipelines 320–934 ms, draws waited 155–348 ms |
| t=551,3 (PIOR spike) | 25,5 | **145** | 7846, **869 pulados** | 1845 | 240 MB | 223 ms | **168 shaders, translate 395 ms, spirv 433 ms, pipelines 1645 ms, draws waited 213 ms, compiling 407, pipe-building 462** |
| t=553–563 (cena leve) | 29,5–30 | 34–72 | 13–17k | 2400–2714 | 252–308 MB | 7–21 ms | 0–87 (só t=553) |

Totais em 19 janelas: upload 13.504 MB (média 711 MB/2 s), wait 4.449 ms (média 234 ms/2 s).
Janelas com max > 60 ms: 6 de 19. Max sem compilar shader: 68–82 ms — ou seja, há picos mesmo sem shader.

## Causas, em ordem de impacto

### 1. Compilação de shader/pipeline na hora (pico #1, 145 ms)
Evidência: t=551,3 — 168 shaders novos, 1645 ms de criação de pipeline em 2 s,
869 draws pulados, fps cai para 25,5, max 145 ms. Coincide com `[hid]
SetSupportedNpadStyleSet` + `[nvn] texture pool: slots left 8192` — troca de
cena/área, shaders novos que nunca tinham aparecido.
Por que acontece: pipeline cache (`cache/vk_pipelines.bin`) só é gravado numa
saída limpa; crash/morte do processo perde tudo. SPIRV cache existe, mas a
criação do pipeline Vulkan em si ainda custa. Não há pré-aquecimento
(pre-warm) de shaders ao entrar numa área nova — o primeiro draw que usa o
shader trava esperando o pipeline ficar pronto (`draws waited`, `pipe-building`).
Fixes possíveis: (a) pré-compilar / aquecer shaders na troca de cena;
(b) garantir que o cache de pipeline está sendo salvo/carregado (conferir
tamanho/data de `cache/vk_pipelines.bin` no aparelho); (c) criação
assíncrona de pipeline (não bloquear o draw). Risco: médio/alto, mexe no
núcleo Vulkan.

### 2. Upload para a GPU, sustentado (custo base, não pico)
Evidência: 711 MB/2 s em média, 1.173 MB no pior, ~18 MB por frame na cena
pesada. `stat_upload_bytes` conta `vk_transient` (vértice/índice/textura
re-enviados por frame) + `upload_texture`. Na cena leve cai para ~260 MB/2 s.
Por que: texturas dinâmicas são re-enviadas todo frame (`upload_texture`:
`!dynamic` só checa a cada 8 frames; dinâmicas, todo frame), e o ring
transiente re-copia vértices/índices por draw.
Fixes: (a) baixar resolução interna (menos fill/post, upload de textura
não cai tanto, mas GPU total cai); (b) auditar quais texturas são marcadas
dinâmicas sem precisar (font atlas, etc.); (c) nada a fazer no curto prazo
sem medir por textura (`LARECOMP_VK_PROF=1` dá `tex full MB, dynamic`).
Risco: baixo para (a), médio para (b).

### 3. Espera na GPU / fence (wait 234 ms/2 s, 12–21% do tempo)
Evidência: wait 253–430 ms/2 s na cena pesada, 7–21 ms na cena leve.
É consequência de 1+2+4: GPU saturada, CPU trava no fence esperando o
frame anterior terminar. Não é uma causa isolada — some se a carga da GPU cair.
Fix: mesmo de 2 e 4.

### 4. Pós-processamento por frame (SMAA + FSR)
Evidência em código: `exec.c` post passes — SMAA 1x são 3 passes
(edge/weight/blend) no tamanho da imagem do jogo, depois FSR 1 (EASU+RCAS)
quando há upscale. Ou seja, 5 passes extras por frame, antes do present.
Se SMAA estiver ligado (checkbox em Configurações, marcado por padrão no
código do launcher) e a resolução interna for menor que a da tela, o FSR
também roda.
Fix: desligar SMAA (custo: serrilhado volta) ou baixar resolução interna
e deixar só o FSR. Risco: baixo, é só uma opção. Ganho: depende da
resolução — medir com SMAA on/off no mesmo lugar.

### 5. Resolução interna (não está no log_13)
Padrão do launcher: 0,6667 = 720p interno (1x). Opções: 0,4444 (480p),
0,5 (540p), 0,6667 (720p), 1,0 (1080p). O log não diz qual ele usa.
Se estiver em 1,0 (1080p), a GPU faz ~2,25x mais fill que em 720p, e os
picos de 68–82 ms sem shader fazem sentido. Perguntar antes de mexer.
Fix: 0,5x ou 0,6667x. Risco: nenhum, é opção do launcher.

### 6. Draws/passes do jogo (carga base, não é bug)
450–500 draws/frame, ~47 passes/frame, 2 flushes/frame, 2 dispatches/frame
na cena pesada; 220–290 draws/frame na cena leve. É o conteúdo da cena
(mundo aberto vs interior/menu). Não dá para cortar sem mudar o jogo.
A cena pesada (antes de t=551) é ~2x mais cara que a leve (depois) — os
picos acontecem na cena pesada, não em todo lugar.

### 7. Ruído de log e serviços (menor, mas soma)
- `[gcopy]` 2.344 linhas em 34 s (~69/s), cada uma é uma escrita no logcat.
- `[time] ITimeZoneService cmd 4` 102x, ~1 a cada 333 ms.
- `[sm] GetService hwopus` + `close session IHardwareOpusDecoder` 131x,
  ~4/s — o decodificador Opus de áudio abre/fecha sessão sem parar.
- `[gcopy-diag]` 12x, diagnóstico de estabilidade.
Nenhum deles explica um pico de 145 ms sozinho, mas logcat em volume
alto + IPC de áudio em loop custam CPU na thread do jogo. Fix: desligar
log verboso de `[gcopy]` e auditar o churn do hwopus. Risco: baixo.

### 8. Driver (não está no log_13)
O log começa em t=529, sem a linha de boot com o driver. Turnip v27 vs
driver do sistema (Adreno 8 Gen 2) têm custo de pipeline diferente — a
compilação de 168 shaders pode ser mais lenta num que no outro. Perguntar
qual ele usa.

## O que falta saber (perguntar a ele)
1. Resolução interna em Configurações (0,5x / 0,75x / 1x / 2x)?
2. Driver: Sistema ou Turnip? Qual versão do Turnip?
3. Onde os picos acontecem (mundo aberto, Viridian Forest, batalha, menu)?
4. 60 fps experimental ligado ou não? (log mostra 30 fps, então provavelmente não)

## Próximo passo proposto (aguardando aval)
1. Responder as 4 perguntas acima.
2. Medir no mesmo lugar, com `LARECOMP_VK_PROF=1`, SMAA on/off, para separar
   custo de post de custo de upload/shader.
3. Primeiro fix a aprovar: desligar log `[gcopy]` + conferir cache de pipeline
   (baixo risco) vs. pré-aquecimento de shaders (maior ganho nos picos,
   maior risco).
