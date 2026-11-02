#!/usr/bin/env python3
"""听幕 P0 Mac 冒烟基准：流式 ASR 模拟麦克风分帧喂入，实测逐帧解码耗时 / RTF / 出字。

用法:
  python3 bench_stream.py --model-dir ../models/zipformer-zh-14M-int8 [--wav audio/test_zh_16k.wav]
                          [--chunk-ms 100] [--threads 2] [--method greedy_search]
"""
import argparse
import glob
import json
import os
import statistics
import time
import wave

import numpy as np
import sherpa_onnx


def read_wav(path: str) -> np.ndarray:
    with wave.open(path, "rb") as w:
        rate, ch, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
        if rate != 16000 or ch != 1 or width != 2:
            raise SystemExit(f"期望 16k/单声道/int16，实际 {rate}Hz/{ch}ch/{width*8}bit: {path}")
        data = w.readframes(w.getnframes())
    return np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0


def pick(model_dir: str, kind: str) -> str:
    """优先 int8，回退 fp32。kind: encoder/decoder/joiner"""
    hits = sorted(glob.glob(os.path.join(model_dir, f"*{kind}*.onnx")))
    int8 = [p for p in hits if "int8" in p]
    if not hits:
        raise SystemExit(f"{model_dir} 里没有 {kind}*.onnx")
    return (int8 or hits)[0]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--wav", default=os.path.join(os.path.dirname(__file__), "audio", "test_zh_16k.wav"))
    ap.add_argument("--chunk-ms", type=int, default=100)
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--method", default="greedy_search")
    ap.add_argument("--ctc", action="store_true", help="zipformer2 CTC 单模型目录")
    a = ap.parse_args()

    if a.ctc:
        rec = sherpa_onnx.OnlineRecognizer.from_zipformer2_ctc(
            tokens=os.path.join(a.model_dir, "tokens.txt"),
            model=os.path.join(a.model_dir, "model.int8.onnx"),
            num_threads=a.threads,
            enable_endpoint_detection=True,
            decoding_method=a.method,
        )
    else:
        rec = sherpa_onnx.OnlineRecognizer.from_transducer(
            tokens=os.path.join(a.model_dir, "tokens.txt"),
            encoder=pick(a.model_dir, "encoder"),
            decoder=pick(a.model_dir, "decoder"),
            joiner=pick(a.model_dir, "joiner"),
            num_threads=a.threads,
            decoding_method=a.method,
        )
    samples = read_wav(a.wav)
    dur = len(samples) / 16000.0
    chunk = 16000 * a.chunk_ms // 1000

    stream = rec.create_stream()
    decode_ms: list[float] = []
    partials: list[tuple[float, str]] = []  # (音频时刻, 该时刻可见文本)
    t_wall = time.perf_counter()

    def drain(t_audio: float) -> None:
        while rec.is_ready(stream):
            t0 = time.perf_counter()
            rec.decode_stream(stream)
            decode_ms.append((time.perf_counter() - t0) * 1000)
        partials.append((round(t_audio, 2), rec.get_result(stream)))

    for i in range(0, len(samples), chunk):
        t_audio = (i + chunk) / 16000.0
        stream.accept_waveform(16000, samples[i : i + chunk])
        drain(min(t_audio, dur))
    stream.input_finished()
    drain(dur)
    wall = time.perf_counter() - t_wall

    ms = decode_ms
    # 尾字延迟近似: 最后一次“可见文本发生变化”的音频时刻与音频结束的差值
    last_change = 0.0
    prev = ""
    for t, txt in partials:
        if txt != prev:
            last_change, prev = t, txt
    out = {
        "model": os.path.basename(a.model_dir.rstrip("/")),
        "wav": os.path.basename(a.wav),
        "wav_dur_s": round(dur, 2),
        "chunk_ms": a.chunk_ms,
        "threads": a.threads,
        "method": a.method,
        "decode_calls": len(ms),
        "decode_p50_ms": round(statistics.median(ms), 1),
        "decode_p95_ms": round(sorted(ms)[max(0, int(len(ms) * 0.95) - 1)], 1),
        "decode_max_ms": round(max(ms), 1),
        "total_decode_s": round(sum(decode_ms) / 1000, 3),
        "RTF_cpu": round(sum(decode_ms) / 1000 / dur, 4),
        "RTF_wall": round(wall / dur, 4),
        "尾字稳定点_s": round(last_change, 2),
        "final_text": rec.get_result(stream),
    }
    print(json.dumps(out, ensure_ascii=False, indent=2))
    print("--- 可见文本轨迹(部分) ---")
    for t, txt in partials[:: max(1, len(partials) // 12)]:
        print(f"  {t:6.2f}s  {txt}")


if __name__ == "__main__":
    main()
