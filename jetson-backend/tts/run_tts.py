#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TTS 桥接进程（sherpa-onnx；Matcha-zh / Kokoro v1.1-zh 双引擎）
================================================================
与 run_asr.py 相同风格：stdin/stdout JSON-lines 协议，常驻进程，
供上层（Qt 界面 / agent）经 QProcess 调用。

输入协议（stdin，每行一条 JSON）：
  {"text": "要合成的文本",
   "engine": "matcha" | "kokoro"   # 默认 matcha（速度优先）；kokoro 音色更多更自然
   "sid": 0,                       # 说话人编号（kokoro 多音色；matcha 单人可忽略）
   "speed": 1.0,                   # 语速，>1 更快
   "out": "/abs/out.wav"}          # 可选，默认写入 ./out/tts_<ms>.wav

输出协议（stdout，每行一条 JSON）：
  {"type": "done", "wav": "/abs/out.wav", "pcm16k": "/abs/out.pcm16k",
   "seconds": 3.42, "elapsed": 0.31, "rtf": 0.091}
      wav=原采样率（18/24k，本地播放用）；pcm16k=16k 单声道 int16 裸 PCM（UDP 回传用）
  {"type": "done", "wav": "", "pcm16k": "", "seconds": 0.0, "elapsed": 0.01, "rtf": null}
      整句无可读 token（如 Matcha 遇纯英文/符号）：没有音频，调用方跳过即可
  {"type": "error", "message": "错误信息"}

另支持命令行单次模式：
  run_tts.py --text "你好，世界" [--engine kokoro] [--sid 3] [--speed 1.0] [--out x.wav]
"""
import argparse
import json
import os
import sys
import time
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

TTS_DIR = Path(__file__).resolve().parent
MODELS = TTS_DIR / "models"
OUT_DIR = TTS_DIR / "out"
# Orin 12 核；实测 8 线程是两种模型的吞吐拐点（matcha RTF 0.088 / kokoro 0.49，
# 12 线程无增益），留 4 核给 llama-server / ASR。可用环境变量 TTS_THREADS 覆盖
NUM_THREADS = int(os.environ.get("TTS_THREADS", "8"))

MATCHA_DIR = MODELS / "matcha-icefall-zh-baker"
VOCODER = MODELS / "sherpa-onnx-hifigan" / "hifigan_v2.onnx"
KOKORO_DIR = MODELS / "kokoro-multi-lang-v1_1"

_matcha_tts = None
_kokoro_tts = None


def _log(msg):
    print(msg, file=sys.stderr, flush=True)


def _rule_fsts(paths):
    """只带上实际存在的规范化 FST（数字/日期/电话读音）。"""
    existing = [str(p) for p in paths if p.exists()]
    return ",".join(existing)


def split_language(text):
    """把文本按语言切成 [(段, engine)]：拉丁字母段→kokoro（能读英文），
    中文/数字段→matcha（FST 会把数字读成中文）；保持原顺序，纯符号段忽略。"""
    out, seg, mode = [], [], 0  # mode: 0=未定 1=拉丁 2=中文

    def flush():
        s = "".join(seg).strip()
        del seg[:]
        if not s:
            return
        has_letter = any(("a" <= c <= "z") or ("A" <= c <= "Z") for c in s)
        has_readable = any(("一" <= c <= "鿿") or c.isdigit() for c in s)
        if not has_letter and not has_readable:
            return
        out.append((s, "kokoro" if has_letter else "matcha"))

    for c in text:
        if ("a" <= c <= "z") or ("A" <= c <= "Z"):
            if mode == 2:
                flush()
            mode = 1
        elif "一" <= c <= "鿿":
            if mode == 1:
                flush()
            mode = 2
        seg.append(c)  # 数字/标点/空白跟随当前段
    flush()
    return out


def load_matcha():
    global _matcha_tts
    if _matcha_tts is None:
        t0 = time.time()
        cfg = sherpa_onnx.OfflineTtsConfig(
            model=sherpa_onnx.OfflineTtsModelConfig(
                matcha=sherpa_onnx.OfflineTtsMatchaModelConfig(
                    acoustic_model=str(MATCHA_DIR / "model-steps-3.onnx"),
                    vocoder=str(VOCODER),
                    tokens=str(MATCHA_DIR / "tokens.txt"),
                    lexicon=str(MATCHA_DIR / "lexicon.txt"),
                    dict_dir=str(MATCHA_DIR / "dict"),
                ),
                num_threads=NUM_THREADS,
                provider="cpu",
            ),
            rule_fsts=_rule_fsts([MATCHA_DIR / "date.fst",
                                  MATCHA_DIR / "number.fst",
                                  MATCHA_DIR / "phone.fst"]),
            max_num_sentences=1,
        )
        _matcha_tts = sherpa_onnx.OfflineTts(cfg)
        _log("[tts] matcha 已加载，用时 %.1fs" % (time.time() - t0))
    return _matcha_tts


def load_kokoro():
    global _kokoro_tts
    if _kokoro_tts is None:
        t0 = time.time()
        cfg = sherpa_onnx.OfflineTtsConfig(
            model=sherpa_onnx.OfflineTtsModelConfig(
                kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
                    model=str(KOKORO_DIR / "model.onnx"),
                    voices=str(KOKORO_DIR / "voices.bin"),
                    tokens=str(KOKORO_DIR / "tokens.txt"),
                    data_dir=str(KOKORO_DIR / "espeak-ng-data"),
                    dict_dir=str(KOKORO_DIR / "dict"),
                    lexicon=",".join(str(KOKORO_DIR / x) for x in
                                     ("lexicon-zh.txt", "lexicon-us-en.txt",
                                      "lexicon-gb-en.txt")),
                ),
                num_threads=NUM_THREADS,
                provider="cpu",
            ),
            rule_fsts=_rule_fsts([KOKORO_DIR / "date-zh.fst",
                                  KOKORO_DIR / "number-zh.fst",
                                  KOKORO_DIR / "phone-zh.fst"]),
            max_num_sentences=1,
        )
        _kokoro_tts = sherpa_onnx.OfflineTts(cfg)
        _log("[tts] kokoro 已加载，用时 %.1fs" % (time.time() - t0))
    return _kokoro_tts


def synth(engine, text, sid, speed, out_path):
    """返回 (wav 路径, 音频秒数, 合成耗时秒)。"""
    tts = load_matcha() if engine == "matcha" else load_kokoro()
    t0 = time.time()
    audio = tts.generate(text, sid=sid, speed=speed)
    elapsed = time.time() - t0

    samples = np.asarray(audio.samples, dtype=np.float32)
    sr = int(audio.sample_rate)
    if sr <= 0 or samples.size == 0:
        # 整句无法转 token（如 Matcha 遇纯英文）：没有音频可产出，交调用方跳过
        return None, None, 0.0, elapsed
    seconds = len(samples) / sr

    pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16)
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(out_path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(pcm.tobytes())

    # 回传专用：统一重采样为 16k 单声道 int16 裸 PCM（与上行语音格式一致）
    if sr != 16000:
        n = max(1, int(round(len(samples) * 16000.0 / sr)))
        s16 = np.interp(np.linspace(0, len(samples) - 1, n),
                        np.arange(len(samples)), samples).astype(np.float32)
    else:
        s16 = samples
    pcm_path = out_path.with_suffix(".pcm16k")
    (np.clip(s16, -1.0, 1.0) * 32767).astype(np.int16).tofile(str(pcm_path))
    return out_path, pcm_path, seconds, elapsed


def send(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def handle(cmd):
    text = (cmd.get("text") or "").strip()
    if not text:
        send({"type": "error", "message": "缺少 text 字段"})
        return
    engine = cmd.get("engine", "matcha")
    if engine not in ("matcha", "kokoro"):
        send({"type": "error", "message": "engine 仅支持 matcha/kokoro"})
        return
    sid = int(cmd.get("sid", 0))
    speed = float(cmd.get("speed", 1.0))
    out = cmd.get("out") or str(OUT_DIR / ("tts_%d.wav" % int(time.time() * 1000)))
    try:
        path, pcm_path, seconds, elapsed = synth(engine, text, sid, speed, out)
        if path is None:
            send({"type": "done", "wav": "", "pcm16k": "", "seconds": 0.0,
                  "elapsed": round(elapsed, 3), "rtf": None})
            return
        send({"type": "done",
              "wav": str(path),
              "pcm16k": str(pcm_path),
              "seconds": round(seconds, 3),
              "elapsed": round(elapsed, 3),
              "rtf": round(elapsed / seconds, 4) if seconds else None})
    except Exception as e:
        send({"type": "error", "message": "%s: %s" % (type(e).__name__, e)})


def main():
    # 常驻模式：启动即预载两个引擎（中英分段混用时避免段中途的加载停顿；
    # 与 LLM 生成并行，用户第一次听到回复前通常已就绪）
    _log("[tts] 桥接启动，预载 matcha + kokoro …")
    load_matcha()
    load_kokoro()
    _log("[tts] 桥接就绪，等待 stdin JSON-lines…")
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            cmd = json.loads(line)
        except json.JSONDecodeError:
            continue
        handle(cmd)


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="sherpa-onnx TTS（Matcha-zh / Kokoro v1.1-zh）")
    ap.add_argument("--text", help="单次合成模式：直接输出结果 JSON")
    ap.add_argument("--engine", default="matcha", choices=["matcha", "kokoro"])
    ap.add_argument("--sid", type=int, default=0)
    ap.add_argument("--speed", type=float, default=1.0)
    ap.add_argument("--out", help="输出 wav 路径（默认 ./out/tts_<ms>.wav）")
    args = ap.parse_args()
    if args.text:
        cmd = {"text": args.text, "engine": args.engine,
               "sid": args.sid, "speed": args.speed}
        if args.out:
            cmd["out"] = args.out
        handle(cmd)
    else:
        main()
