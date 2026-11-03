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

# 方言版标点模型：CT-Transformer zh-en int8（75MB，SenseVoice 本身不输出标点，第二遍后处理补标点）
if [ ! -f "$M/punct-ct-transformer-zh-en/model.int8.onnx" ]; then
  dl "https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2" "$TMP/punct.tar.bz2"
  tar -xf "$TMP/punct.tar.bz2" -C "$TMP"
  rm -rf "$M/punct-ct-transformer-zh-en"
  mv "$TMP/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8" "$M/punct-ct-transformer-zh-en"
fi

echo "models ready under $M:"
ls "$M"
