#!/usr/bin/env python3
"""构造"多人同时说话"的合成测试音频：把两个单人语音按时间偏移混成单声道。

用法:
  python3 make_overlap.py <wavA> <wavB> <B相对A的起始秒> <out.wav> [--gain 0.5]
输出始终是 16k/单声道/int16。
"""
import argparse
import wave

import numpy as np


def read_wav(path):
    with wave.open(path, "rb") as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2, path
        data = w.readframes(w.getnframes())
    return np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0


def write_wav(path, samples):
    samples = np.clip(samples, -1.0, 1.0)
    with wave.open(path, "wb") as w:
        w.setframerate(16000)
        w.setnchannels(1)
        w.setsampwidth(2)
        w.writeframes((samples * 32767).astype(np.int16).tobytes())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("wavA")
    ap.add_argument("wavB")
    ap.add_argument("offset_s", type=float)
    ap.add_argument("out")
    ap.add_argument("--gain", type=float, default=0.5)
    a = ap.parse_args()
    A = read_wav(a.wavA)
    B = read_wav(a.wavB)
    off = int(a.offset_s * 16000)
    total = max(len(A), off + len(B))
    mix = np.zeros(total, dtype=np.float32)
    mix[: len(A)] += A * a.gain
    mix[off : off + len(B)] += B * a.gain
    write_wav(a.out, mix)
    print(f"out={a.out} dur={total/16000:.1f}s overlap={a.offset_s:.1f}s~{min(len(A), off+len(B))/16000:.1f}s")


if __name__ == "__main__":
    main()
