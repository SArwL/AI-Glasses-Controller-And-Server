#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
样例：LLM 回复 → TTS → 本地播放（句级流式）
==============================================
链路：qwen_client.py（与 QwenChat 同款 LLM 桥接，llama-server:8080）
      → 按标点切句 → sherpa-onnx TTS（run_tts，auto：句含英文自动切 Kokoro）
      → aplay 本地播放。
句级流式：第 1 句播出的同时，LLM 还在生成后面的句子（首声 ≈ 首句生成 + 合成时间）。

用法：
  /mnt/ssd/jetson/asr/venv/bin/python demo_llm_tts.py "用一句话介绍你自己"
  ... demo_llm_tts.py "讲个关于眼镜的笑话" --engine kokoro --sid 2
  ... demo_llm_tts.py "你好" --no-play      # 只合成不播放（文件名会打印出来）
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

TTS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TTS_DIR))
import run_tts  # noqa: E402  复用模型加载与合成（进程内缓存，避免重复加载）

QWEN_CLIENT = "/mnt/ssd/jetson/qt_workspace/QwenChat/python/qwen_client.py"
OUT_DIR = TTS_DIR / "out"
TERMINATORS = "。！？!?；;\n"
MAX_SENT = 80  # 过长的句子在逗号处再切，避免单句合成等待过久


def pick_segments(text, forced):
    """引擎选择：auto=句内按语言切段（中文/数字→matcha，字母段→kokoro），否则整句指定引擎。"""
    if forced != "auto":
        return [(text, forced)]
    return run_tts.split_language(text) or [(text, "matcha")]


def clean_for_tts(text):
    # 轻量清理：去掉 markdown 装饰符，避免读出怪符号
    return re.sub(r"[*#`>\[\]]", "", text).strip()


def split_sentences(buf):
    """从缓冲中切出所有完整句子，返回 (句子列表, 剩余缓冲)。"""
    out, start = [], 0
    for i, ch in enumerate(buf):
        if ch in TERMINATORS:
            s = buf[start:i + 1].strip()
            if s:
                out.append(s)
            start = i + 1
        elif i - start >= MAX_SENT and ch in "，,":  # 超长句在逗号处断
            s = buf[start:i + 1].strip()
            if s:
                out.append(s)
            start = i + 1
    return out, buf[start:]


def speak(sentence, engine, sid, play_audio, idx, timeline):
    text = clean_for_tts(sentence)
    if not text:
        return
    wav = OUT_DIR / ("demo_%03d.wav" % idx)
    t0 = time.time()
    path, _pcm16k, seconds, elapsed = run_tts.synth(engine, text, sid, 1.0, wav)
    print("  [%02d] %s\n      引擎=%s 合成 %.2fs / 音频 %.2fs -> %s" % (
        idx, text, engine, elapsed, seconds, path.name))
    if timeline["first_audio"] is None:
        timeline["first_audio"] = time.time() - timeline["t_start"]
    if play_audio:
        r = subprocess.run(["aplay", "-q", str(path)])
        if r.returncode != 0:
            print("      [播放失败] aplay 返回 %d（wav 已保留，可手动播放）" % r.returncode)


def main():
    ap = argparse.ArgumentParser(description="LLM 回复 → TTS → 本地播放样例")
    ap.add_argument("prompt", nargs="?", default="用一句话介绍你自己")
    ap.add_argument("--engine", default="auto", choices=["auto", "matcha", "kokoro"])
    ap.add_argument("--sid", type=int, default=0, help="Kokoro 音色编号")
    ap.add_argument("--think", action="store_true", help="开启深度思考（默认快速模式）")
    ap.add_argument("--no-play", action="store_true", help="只合成不播放")
    args = ap.parse_args()

    timeline = {"t_start": time.time(), "first_audio": None}

    # 预热：auto 模式两个引擎都可能用到，都预载，避免句中途懒加载停顿
    if args.engine in ("auto", "matcha"):
        t0 = time.time()
        run_tts.load_matcha()
        print("matcha 预热 %.1fs" % (time.time() - t0))
    if args.engine in ("auto", "kokoro"):
        t0 = time.time()
        run_tts.load_kokoro()
        print("kokoro 预热 %.1fs" % (time.time() - t0))

    print("提问：%s（引擎=%s）" % (args.prompt, args.engine))
    proc = subprocess.Popen(["python3", QWEN_CLIENT],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            text=True, bufsize=1)
    proc.stdin.write(json.dumps({"prompt": args.prompt, "think": args.think}) + "\n")
    proc.stdin.flush()

    buf, idx, t_first_token, reply = "", 0, None, []
    for line in proc.stdout:
        line = line.strip()
        if not line:
            continue
        msg = json.loads(line)
        if msg.get("type") == "token":
            if t_first_token is None:
                t_first_token = time.time() - timeline["t_start"]
            buf += msg["text"]
            reply.append(msg["text"])
            sents, buf = split_sentences(buf)
            for s in sents:
                for seg_text, eng in pick_segments(s, args.engine):
                    idx += 1
                    speak(seg_text, eng, args.sid, not args.no_play, idx, timeline)
        elif msg.get("type") == "done":
            break
        elif msg.get("type") == "error":
            print("LLM 出错：%s" % msg.get("message"))
            proc.kill()
            sys.exit(1)

    rest = buf
    if clean_for_tts(rest):
        for seg_text, eng in pick_segments(rest, args.engine):
            idx += 1
            speak(seg_text, eng, args.sid, not args.no_play, idx, timeline)
    proc.stdin.close()
    proc.wait()

    print("—— 小结 ——")
    print("回复：%s" % "".join(reply))
    print("首 token %.2fs ｜ 首声 %.2fs ｜ 共 %d 句" % (
        t_first_token if t_first_token is not None else -1,
        timeline["first_audio"] if timeline["first_audio"] is not None else -1,
        idx))


if __name__ == "__main__":
    main()
