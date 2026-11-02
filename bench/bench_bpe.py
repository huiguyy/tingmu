#!/usr/bin/env python3
"""测 BPE 词表的 zipformer2 transducer 流式模型（如 x-asr zh-en）。

用法:
  python3 bench_bpe.py --model-dir <目录> --wav <16k wav> [--threads 2] [--model-type zipformer2]
"""
import argparse
import os
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
    ap.add_argument("--threads", type=int, default=2)
    ap.add_argument("--model-type", default="zipformer2")
    a = ap.parse_args()
    d = a.model_dir

    def pick(base):
        c = [f for f in os.listdir(d) if f.startswith(base)]
        i8 = [f for f in c if "int8" in f]
        return os.path.join(d, (i8 or c)[0])

    rec = sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=os.path.join(d, "tokens.txt"),
        encoder=pick("encoder"),
        decoder=pick("decoder"),
        joiner=pick("joiner"),
        bpe_vocab=os.path.join(d, "bpe.model") if os.path.exists(os.path.join(d, "bpe.model")) else "",
        num_threads=a.threads,
        model_type=a.model_type,
        enable_endpoint_detection=True,
    )
    samples = read_wav(a.wav)
    st = rec.create_stream()
    chunk = 1600
    for i in range(0, len(samples), chunk):
        st.accept_waveform(16000, samples[i : i + chunk])
        while rec.is_ready(st):
            rec.decode_stream(st)
        if rec.is_endpoint(st):
            print("[EP]", rec.get_result(st))
            rec.reset(st)
    st.input_finished()
    while rec.is_ready(st):
        rec.decode_stream(st)
    print("FINAL:", rec.get_result(st))


if __name__ == "__main__":
    main()
