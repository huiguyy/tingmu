#!/usr/bin/env python3
"""离线模型测试（SenseVoice / paraformer-Chuan 等只支持 OfflineRecognizer 的模型）。

用法: python3 bench_offline.py --model-dir <目录> --wav <16k wav> [--type sense_voice|paraformer] [--language zh|auto]
"""
import argparse
import os
import time
import wave

import numpy as np
import sherpa_onnx


def read_wav(path):
    with wave.open(path, "rb") as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        data = w.readframes(w.getnframes())
    return np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--wav", required=True)
    ap.add_argument("--type", default="sense_voice", choices=["sense_voice", "paraformer"])
    ap.add_argument("--language", default="zh")
    ap.add_argument("--threads", type=int, default=2)
    a = ap.parse_args()
    d = a.model_dir
    tokens = os.path.join(d, "tokens.txt")
    if a.type == "sense_voice":
        rec = sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=os.path.join(d, "model.int8.onnx"), tokens=tokens,
            num_threads=a.threads, language=a.language, use_itn=True)
    else:
        rec = sherpa_onnx.OfflineRecognizer.from_paraformer(
            paraformer=os.path.join(d, "model.int8.onnx"), tokens=tokens, num_threads=a.threads)
    samples = read_wav(a.wav)
    st = rec.create_stream()
    st.accept_waveform(16000, samples)
    t0 = time.perf_counter()
    rec.decode_stream(st)
    ms = (time.perf_counter() - t0) * 1000
    dur = len(samples) / 16000
    print(f"RTF={ms/1000/dur:.3f} decode={ms:.0f}ms")
    print("FINAL:", st.result.text)


if __name__ == "__main__":
    main()
