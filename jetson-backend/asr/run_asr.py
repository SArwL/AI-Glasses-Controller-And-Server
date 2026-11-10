#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SenseVoice-Small ASR 桥接进程
==============================
与 QwenChat 的 qwen_client.py 相同风格：stdin/stdout JSON-lines 协议，
常驻进程，供上层（Qt 界面 / agent）经 QProcess 调用。

输入协议（stdin，每行一条 JSON）：
  {"path": "/abs/path/audio.pcm", "sample_rate": 16000}
      .wav 文件自动读取头部采样率；.pcm 按 raw int16 小端单声道处理，
      默认 16kHz（可用 sample_rate 覆盖）。

输出协议（stdout，每行一条 JSON）：
  {"type": "done", "text": "识别结果"}
  {"type": "error", "message": "错误信息"}

另支持命令行单次模式：
  run_asr.py --file /path/audio.wav
"""
import argparse
import json
import sys
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

MODEL_DIR = Path(__file__).resolve().parent / "models" / "sensevoice"
DEFAULT_SR = 16000
NUM_THREADS = 4  # Orin 12 核，留余量给 llama-server


def load_recognizer():
    # 优先用 int8 量化模型（CPU 推理更快）
    model_file = MODEL_DIR / "model.int8.onnx"
    if not model_file.exists():
        model_file = MODEL_DIR / "model.onnx"
    if not model_file.exists():
        raise RuntimeError("模型文件不存在: {}".format(MODEL_DIR))
    return sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=str(model_file),
        tokens=str(MODEL_DIR / "tokens.txt"),
        num_threads=NUM_THREADS,
        use_itn=False,
        language="auto",
    )


def load_audio(path, sample_rate):
    """返回 (float32 归一化样本, 实际采样率)。"""
    path = str(path)
    if path.lower().endswith(".wav"):
        with wave.open(path, "rb") as wf:
            sr = wf.getframerate()
            data = np.frombuffer(wf.readframes(wf.getnframes()), dtype=np.int16)
        return data.astype(np.float32) / 32768.0, sr
    # raw PCM：int16 小端、单声道
    data = np.fromfile(path, dtype=np.int16)
    return data.astype(np.float32) / 32768.0, sample_rate or DEFAULT_SR


def send(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def recognize_once(recognizer, path, sample_rate):
    samples, sr = load_audio(path, sample_rate)
    s = recognizer.create_stream()
    s.accept_waveform(sr, samples)
    recognizer.decode_stream(s)
    return s.result.text.strip()


def main():
    recognizer = load_recognizer()
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            cmd = json.loads(line)
        except json.JSONDecodeError:
            continue
        path = cmd.get("path") or cmd.get("file")
        if not path:
            send({"type": "error", "message": "缺少 path 字段"})
            continue
        try:
            text = recognize_once(recognizer, path,
                                  cmd.get("sample_rate", DEFAULT_SR))
            send({"type": "done", "text": text})
        except Exception as e:
            send({"type": "error", "message": "识别失败: {}".format(e)})


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="SenseVoice-Small ASR")
    ap.add_argument("--file", help="单次识别模式：直接输出文本")
    ap.add_argument("--sample-rate", type=int, default=DEFAULT_SR)
    args = ap.parse_args()
    if args.file:
        rec = load_recognizer()
        print(recognize_once(rec, args.file, args.sample_rate))
    else:
        main()
