# Let's Go Pikachu — Port Android

Port não-oficial de **Pokémon Let's Go, Pikachu!** (Nintendo Switch) para Android, baseado no
[letsgo-recomp](https://github.com/raulzrrs/letsgo-recomp) (recompilação estática, por raulzrrs).
Testado no Galaxy S23 (Snapdragon 8 Gen 2, Adreno 740, Android 16).

> **Uso pessoal. Este repositório NÃO contém o jogo, nem código do jogo, nem chaves.**
> Para buildar você precisa do seu próprio dump legalmente obtido (ver [BUILD.md](BUILD.md)).
>
> 📺 Canal no YouTube: https://youtube.com/@emuucheck

## O que este repositório contém

| Pasta/arquivo | Conteúdo |
|---|---|
| `src/` | Código-fonte completo: `runtime/` (NVN→Vulkan, HLE, SDL3), `tools/` (geração: `gen.py`, `lifter.py` e mais scripts), `Makefile`, `third_party/` (imgui, smaa, vma, fontes), `docs/` (docs upstream) |
| `apk/` | App Android: launcher, importador in-app, overlay de controles, manifest |
| `importer/` | Importador de NSP no aparelho (lê o dump do usuário e extrai `exefs`/`romfs`) |
| scripts na raiz | Scripts de build (`build-runtime.sh`, `link-runtime.sh`, `build-apk.sh`, `build-gamelib-pikachu-600-j4.sh`, `gen-600.sh`, etc.) |
| `docs/` | Documentação do projeto: mapa de performance do log_13 |
| `screenshots/` | Captura do jogo rodando no S23 (tela de nome, v0005) |
| `BUILD.md` | **Tutorial completo de build**: o que você precisa, passo a passo |
| `ISSUES.md` | **Problemas conhecidos**: o que funciona, o que não funciona, e o que já foi investigado |

## Estado atual (v0015, 05/11/2026)

- **O jogo abre e roda no S23** (tela de criação de personagem, 30 fps). Testado com o data
  importado pelo launcher.
- **Importador otimizado** (v0117 do Eevee): `.bin` direto do NSP, cripto por hardware ARMv8
  com fallback para software se o self-test falhar, e log dizendo qual check falhou.
  Provado no host com o NSP do Pikachu: import fresco em 76 s, `.bin` de 4.427.630.560 bytes,
  27.820 arquivos, conteúdo idêntico à extração solta.
- **FPS só da direita** (v0015, espelho da v0118 do Eevee): a janela ImGui da esquerda foi
  removida do runtime; o **Mostrar FPS** do launcher liga e desliga o contador da direita
  (`XX fps  YY ms`). O botão FPS da gaveta de toque não existe mais.
- **Teclado de nome nativo Android** (v0006/v0009): diálogo horizontal do sistema em vez do
  painel ImGui vertical.
- **Launcher personalizado**: arte bege, botões Config / Controles / Sobre, botão do canal
  `@emuucheck`, Configurações em 4 submenus (Imagem / Jogo / Driver GPU / Importação),
  detecção automática dos dados já importados e digitação do caminho da pasta (v0014).
- **Pendentes de teste no S23**: visual da v0015 (Mostrar FPS ligado → só o contador da
  direita) e import com o NSP do Pikachu no aparelho. Ver [ISSUES.md](ISSUES.md).

## Runtime compartilhado com o Eevee

O `src/` é o **mesmo runtime** do repositório
[letsgo-recomp-android](https://github.com/CowKillers/letsgo-recomp-android) (Let's Go, Eevee!),
espelhado aqui na v0015 (runtime `d2bdbea5…`, importador `ebeeb4ef…`, gamelib `51796ad9…`).
Correções no runtime valem para os dois jogos — manter os dois repositórios sincronizados.

## O que este repositório NÃO contém (de propósito)

- ❌ O código recompilado do jogo (`generated-pikachu-aarch64-600/`) — derivado do binário da Nintendo
- ❌ O NSP, `prod.keys`, `title.keys` ou qualquer dump — use o seu próprio
- ❌ Assets do jogo (texturas, modelos, fontes, áudio) e a arte do launcher
- ❌ APK pronto, data pack, shaders ou saves — build você mesmo com o seu dump
