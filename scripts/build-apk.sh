#!/bin/bash
# 打包听幕家族发行版——每个版本一个 applicationId，可同机共存：
#   bilingual → 听幕-双语版.apk：arm64 专属（-Plite=true）+ x-asr 中英双语模型，约 170MB
#               可识别英文(ABC/PDF)；v1.7 起字幕不显示标点（停顿用空格/换行断句）；速度慢约 2.5-3 倍
#   fast      → 听幕-快速版.apk：双架构（arm64+v7a，32 位老机可用）+ small-ctc 25MB，约 72MB
#               快/省电/发热低；不支持英文，v0.9 起 <unk> 不再显示
#   dialect   → 听幕-方言版.apk：arm64 + small-ctc 25MB（第一遍流式出字）
#               + SenseVoice 离线方言模型 233MB（第二遍整句纠错），约 215MB；无标点
# 用法: scripts/build-apk.sh bilingual|fast|dialect
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
[ -f "$ROOT/models/small-ctc-zh-2025/tokens.txt" ] && [ -f "$ROOT/models/x-asr-480ms-zh-en-punct-int8/tokens.txt" ] && [ -f "$ROOT/models/sense-voice-int8/tokens.txt" ] && [ -f "$ROOT/models/kws-wenetspeech-mobile/tokens.txt" ] \
  || bash "$ROOT/scripts/fetch-models.sh"
for d in "$ASSETS"/*/; do [ -d "$d" ] && mv "$d" "$STAGE/"; done

# 输出文件名带版本号（从 build.gradle.kts 读 versionName)，避免来回覆盖分不清
# 听幕·屏有独立版本线（if 分支在前），按变体取对应分支
VER_RAW=$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts)
if [ "$1" = "screen" ]; then
  VER=$(echo "$VER_RAW" | head -1 | sed 's/.*"\(.*\)"/\1/')
else
  VER=$(echo "$VER_RAW" | tail -1 | sed 's/.*"\(.*\)"/\1/')
fi
echo "versionName=$VER"

case "${1:-}" in
  bilingual|fast|dialect|screen)
    # 每个版本生成自己的 launcher 图标（徽章字 快/双/方/屏）
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
    # 双通道：流式小模型（第一遍实时出字）+ SenseVoice 离线方言模型（第二遍整句纠错）
    # v1.7 起不打包标点模型：产品要求字幕不显示标点，停顿处用空格/换行断句
    mkdir -p "$ASSETS/sense-voice-int8"
    cp "$ROOT/models/sense-voice-int8/model.int8.onnx" "$ASSETS/sense-voice-int8/"
    cp "$ROOT/models/sense-voice-int8/tokens.txt" "$ASSETS/sense-voice-int8/"
    cp -r "$ROOT/models/small-ctc-zh-2025" "$ASSETS/"
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
  screen)
    # 听幕·屏：KWS 哨兵模型（int8 三件套+tokens+构建期生成的 keywords.txt，~5MB）
    # + small-ctc 25MB（命中后短窗 ASR 提取站名）+ 报站/对话两段测试音频
    mkdir -p "$ASSETS/kws-wenetspeech-mobile"
    for f in encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx decoder-epoch-12-avg-2-chunk-16-left-64.onnx joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx tokens.txt keywords.txt; do
      cp "$ROOT/models/kws-wenetspeech-mobile/$f" "$ASSETS/kws-wenetspeech-mobile/"
    done
    cp -r "$ROOT/models/small-ctc-zh-2025" "$ASSETS/"
    ./gradlew :app:assembleDebug -Plite=true -Pvariant=screen ${EXTRA_ABIS:-} --no-daemon -q
    cp app/build/outputs/apk/debug/app-debug.apk "${OUT:-$ROOT/听幕·屏-v${VER}.apk}"
    echo "== 听幕·屏已出: ${OUT:-$ROOT/听幕·屏-v${VER}.apk}"
    ;;
  *) echo "用法: $0 bilingual|fast|dialect|screen" >&2; exit 1 ;;
esac
