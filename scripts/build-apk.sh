#!/bin/bash
# 打包听幕两个发行版——每个版本只内置一个模型，职责单一：
#   bilingual → 听幕-双语版.apk：arm64 专属（-Plite=true）+ x-asr 中英双语模型，约 170MB
#               可识别英文(ABC/PDF)与标点；速度慢约 2.5-3 倍，适合 64 位大内存手机
#   fast      → 听幕-快速版.apk：双架构（arm64+v7a，32 位老机可用）+ small-ctc 25MB，约 72MB
#               快/省电/发热低；不支持英文，v0.9 起 <unk> 不再显示
# 用法: scripts/build-apk.sh bilingual|fast
# 环境变量: OUT=<输出apk路径> EXTRA_ABIS="-PextraAbis=x86_64"（模拟器验证用）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/android/Tingmu"
ASSETS=app/src/main/assets/models
STAGE=$(mktemp -d /tmp/tingmu-stage.XXXXXX)
restore() {
  rm -rf "$ASSETS"/*/ 2>/dev/null || true
  mv "$STAGE"/*/ "$ASSETS"/ 2>/dev/null || true
  rm -rf "$STAGE"
  # 图标恢复为默认的快速版（res 里常驻默认集）
  python3 "$ROOT/scripts/gen_icon.py" fast "$ROOT/android/Tingmu/app/src/main/res" >/dev/null 2>&1 || true
}
trap restore EXIT
mkdir -p "$ASSETS"
# 模型统一从仓库根 models/ 来（首次构建自动下载）
[ -f "$ROOT/models/small-ctc-zh-2025/tokens.txt" ] && [ -f "$ROOT/models/x-asr-480ms-zh-en-punct-int8/tokens.txt" ] && [ -f "$ROOT/models/sense-voice-int8/tokens.txt" ] \
  || bash "$ROOT/scripts/fetch-models.sh"
for d in "$ASSETS"/*/; do [ -d "$d" ] && mv "$d" "$STAGE/"; done

# 输出文件名带版本号（从 build.gradle.kts 读 versionName)，避免来回覆盖分不清
VER=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)"/\1/')
echo "versionName=$VER"

case "${1:-}" in
  bilingual|fast|dialect)
    # 每个版本生成自己的 launcher 图标（徽章字 快/双）
    python3 "$ROOT/scripts/gen_icon.py" "$1" app/src/main/res
    ;;
esac

case "${1:-}" in
  bilingual)
    # 只打包模型本体，不带 test_wavs/README/test_onnx.py（assets 里子目录会炸 ensureBundled，见 §十五）
    mkdir -p "$ASSETS/x-asr-480ms-zh-en-punct-int8"
    for f in encoder.int8.onnx decoder.onnx joiner.int8.onnx tokens.txt bpe.model; do
      cp "$ROOT/models/x-asr-480ms-zh-en-punct-int8/$f" "$ASSETS/x-asr-480ms-zh-en-punct-int8/"
    done
    ./gradlew :app:assembleDebug -Plite=true -Pvariant=bilingual ${EXTRA_ABIS:-} --no-daemon -q
    cp app/build/outputs/apk/debug/app-debug.apk "${OUT:-$ROOT/听幕-双语版-v${VER}.apk}"
    echo "== 双语版已出: ${OUT:-$ROOT/听幕-双语版-v${VER}.apk}"
    ;;
  dialect)
    # 离线方言模型（SenseVoice）：只拷模型本体
    mkdir -p "$ASSETS/sense-voice-int8"
    cp "$ROOT/models/sense-voice-int8/model.int8.onnx" "$ASSETS/sense-voice-int8/"
    cp "$ROOT/models/sense-voice-int8/tokens.txt" "$ASSETS/sense-voice-int8/"
    ./gradlew :app:assembleDebug -Plite=true -Pvariant=dialect ${EXTRA_ABIS:-} --no-daemon -q
    cp app/build/outputs/apk/debug/app-debug.apk "${OUT:-$ROOT/听幕-方言版-v${VER}.apk}"
    echo "== 方言版已出: ${OUT:-$ROOT/听幕-方言版-v${VER}.apk}"
    ;;
  fast)
    cp -r "$ROOT/models/small-ctc-zh-2025" "$ASSETS/"
    ./gradlew :app:assembleDebug -Pvariant=fast ${EXTRA_ABIS:-} --no-daemon -q
    cp app/build/outputs/apk/debug/app-debug.apk "${OUT:-$ROOT/听幕-快速版-v${VER}.apk}"
    echo "== 快速版已出: ${OUT:-$ROOT/听幕-快速版-v${VER}.apk}"
    ;;
  *) echo "用法: $0 bilingual|fast" >&2; exit 1 ;;
esac
