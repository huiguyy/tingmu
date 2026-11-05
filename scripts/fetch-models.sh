#!/bin/bash
# 下载构建所需的 sherpa-onnx 官方模型到 models/（各模型许可各见上游 release，不随本仓库分发）
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
M="$ROOT/models"
TMP=$(mktemp -d /tmp/tingmu-fetch.XXXXXX)
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$M"
GH="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"
dl() { curl -sL --retry 3 --max-time 900 -o "$2" "$1"; }

# Silero VAD（632KB，也可直接放进 assets，构建时 build-apk.sh 会留在 assets 里）
if [ ! -f "$M/vad/silero_vad.onnx" ]; then
  mkdir -p "$M/vad"
  dl "$GH/silero_vad.onnx" "$M/vad/silero_vad.onnx"
fi

# 快速版模型：streaming zipformer2 small CTC zh int8（25MB）
if [ ! -f "$M/small-ctc-zh-2025/tokens.txt" ]; then
  dl "$GH/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01.tar.bz2" "$TMP/small.tar.bz2"
  tar -xf "$TMP/small.tar.bz2" -C "$TMP"
  rm -rf "$M/small-ctc-zh-2025"
  mv "$TMP/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01" "$M/small-ctc-zh-2025"
fi

# 双语版模型：x-asr 480ms zh-en-punct int8（162MB）
if [ ! -f "$M/x-asr-480ms-zh-en-punct-int8/tokens.txt" ]; then
  dl "$GH/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05.tar.bz2" "$TMP/xasr.tar.bz2"
  tar -xf "$TMP/xasr.tar.bz2" -C "$TMP"
  rm -rf "$M/x-asr-480ms-zh-en-punct-int8"
  mv "$TMP/sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05" "$M/x-asr-480ms-zh-en-punct-int8"
fi

# 方言版模型：SenseVoice（233MB，覆盖方言+普通话+英文+粤语）
if [ ! -f "$M/sense-voice-int8/tokens.txt" ]; then
  dl "$GH/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2" "$TMP/sv.tar.bz2"
  tar -xf "$TMP/sv.tar.bz2" -C "$TMP"
  rm -rf "$M/sense-voice-int8"
  mv "$TMP/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09" "$M/sense-voice-int8"
fi

# 听幕·屏 KWS 哨兵模型：zipformer wenetspeech 3.3M mobile（int8 三件套 ~5MB）
# 仅 GitHub release tag kws-models 提供，国内网络可能要重试
if [ ! -f "$M/kws-wenetspeech-mobile/tokens.txt" ]; then
  dl "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile.tar.bz2" "$TMP/kws.tar.bz2"
  [ -s "$TMP/kws.tar.bz2" ] || { echo "KWS 模型下载失败：手动从 https://github.com/k2-fsa/sherpa-onnx/releases/tag/kws-models 下载后解包到 $M/kws-wenetspeech-mobile/" >&2; exit 1; }
  tar -xf "$TMP/kws.tar.bz2" -C "$TMP"
  rm -rf "$M/kws-wenetspeech-mobile"
  mkdir -p "$M/kws-wenetspeech-mobile"
  K="sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile"
  cp "$TMP/$K/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
     "$TMP/$K/decoder-epoch-12-avg-2-chunk-16-left-64.onnx" \
     "$TMP/$K/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx" \
     "$TMP/$K/tokens.txt" "$M/kws-wenetspeech-mobile/"
fi

echo "models ready under $M:"
ls "$M"
