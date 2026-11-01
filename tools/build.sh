#!/usr/bin/env bash
# 一键编译 + 打补丁。用法:
#   ./build.sh neoforge /path/to/neoforge-universal.jar.orig
#   ./build.sh viaproxy /path/to/ViaProxy.jar.orig
#
# 依赖: JDK 21+、asm-9.10.1.jar（同目录或 $ASM_JAR 指定）
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ASM_JAR="${ASM_JAR:-$HERE/asm-9.10.1.jar}"

if [[ ! -f "$ASM_JAR" ]]; then
  echo "缺 ASM: $ASM_JAR" >&2
  echo "下载: https://repo1.maven.org/maven2/org/ow2/asm/asm/9.10.1/asm-9.10.1.jar" >&2
  exit 1
fi

TARGET="${1:-}"
SRC="${2:-}"

if [[ -z "$TARGET" || -z "$SRC" ]]; then
  echo "用法: $0 {neoforge|viaproxy} <原始jar>" >&2
  exit 1
fi

if [[ ! -f "$SRC" ]]; then
  echo "找不到: $SRC" >&2
  exit 1
fi

OUT="${SRC%.jar}-patched.jar"

case "$TARGET" in
  neoforge)
    echo "== 编译 PatchNeoForge + VerifyPatch =="
    javac -nowarn -encoding UTF-8 -cp "$ASM_JAR" -d "$HERE" \
      "$HERE/PatchNeoForge.java" "$HERE/VerifyPatch.java"
    echo "== 打补丁 =="
    java -cp "$ASM_JAR:$HERE" PatchNeoForge "$SRC" "$OUT"
    echo "== 产物: $OUT =="
    ls -l "$OUT"
    echo
    echo "下一步: 自检（注意 cp 末尾的 .）"
    echo "  java -Xverify:all -cp \"$OUT:\$(find libraries -name '*.jar' | tr '\\n' ':').\" VerifyPatch"
    ;;
  viaproxy)
    echo "== 编译 PatchViaProxy =="
    javac -nowarn -encoding UTF-8 -cp "$ASM_JAR" -d "$HERE" \
      "$HERE/PatchViaProxy.java"
    echo "== 打补丁 =="
    java -cp "$ASM_JAR:$HERE" PatchViaProxy "$SRC" "$OUT"
    echo "== 产物: $OUT =="
    ls -l "$OUT"
    ;;
  *)
    echo "未知目标: $TARGET（应为 neoforge 或 viaproxy）" >&2
    exit 1
    ;;
esac
