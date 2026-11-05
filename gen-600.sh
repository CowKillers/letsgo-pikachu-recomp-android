#!/bin/bash
# Regen Pikachu with guest base 0x6000000000 (v0004). The exefs must already be
# extracted (see BUILD.md Etapa 0) into extracted/exefs/ from YOUR OWN NSP.
# NEVER regen at 0x7000000000: the S23 cannot mmap that guest range (dalvik
# LinearAlloc + the APK's own libraries live there) and the game dies before
# main with "guest mmap failed: File exists" (v0004). The runtime's STACK_BASE
# is 0x6200000000 to match this base.
set -e
cd "$(dirname "$0")"
ROOT="$PWD"
GENOUT="$ROOT/generated-pikachu-aarch64-600"
if [ ! -x "$ROOT/gen-venv/bin/python" ]; then
    python3 -m venv "$ROOT/gen-venv"
    "$ROOT/gen-venv/bin/pip" install capstone lz4 unicorn
fi
PY="$ROOT/gen-venv/bin/python"
cd "$ROOT/src/tools"
"$PY" gen.py main "$ROOT/extracted/exefs/main" 0x6000000000 "$GENOUT/main" --simd asm
"$PY" gen.py sdk "$ROOT/extracted/exefs/sdk" 0x6004000000 "$GENOUT/sdk" --simd asm --hle "$ROOT/src/runtime/hle/hle_symbols.txt"
"$PY" gen.py subsdk0 "$ROOT/extracted/exefs/subsdk0" 0x6006000000 "$GENOUT/subsdk0" --simd asm
"$PY" gen.py subsdk1 "$ROOT/extracted/exefs/subsdk1" 0x6008000000 "$GENOUT/subsdk1" --simd asm
echo GEN600_OK
ls "$GENOUT/main"/*.c | wc -l
ls "$GENOUT/sdk"/*.c | wc -l
ls "$GENOUT/subsdk0"/*.c | wc -l
ls "$GENOUT/subsdk1"/*.c | wc -l
