#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TTS 基准测试：短/中/长/中英混合句 × matcha/kokoro，测量 RTF，wav 输出到 out/ 试听。
用法：/mnt/ssd/jetson/asr/venv/bin/python bench_tts.py
它通过 run_tts.py 的 JSON-lines 协议（stdin/stdout）驱动，与将来 Qt 客户端用法一致。
"""
import json
import subprocess
import sys
from pathlib import Path

TTS_DIR = Path(__file__).resolve().parent
PY = "/mnt/ssd/jetson/asr/venv/bin/python"

SENTENCES = [
    ("短句", "你好，我是一个运行在 Jetson 上的语音助手。"),
    ("中句", "今天的室内温度是 24 度，湿度大约百分之六十，天气还不错。"),
    ("长句", "根据你的问题，我建议先检查一下网络连接，然后重启路由器试试；"
             "如果还是不行，把设备日志发给我，我来帮你分析原因。"),
    ("中英混合", "这个模型的 RTF 大约 0.1，首句延迟只有 200 毫秒，体验很流畅。"),
]


def request(proc, text, engine, sid=0):
    proc.stdin.write(json.dumps({"text": text, "engine": engine, "sid": sid}) + "\n")
    proc.stdin.flush()
    line = proc.stdout.readline()
    return json.loads(line) if line else {"type": "error", "message": "进程无输出"}


def main():
    proc = subprocess.Popen([PY, str(TTS_DIR / "run_tts.py")],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            text=True, bufsize=1)
    print("%-10s %-8s %8s %9s %8s   %s" % ("句子", "引擎", "音频s", "耗时s", "RTF", "wav"))
    print("-" * 78)
    for engine in ("matcha", "kokoro"):
        # 预热一次：首次 generate 可能包含惰性初始化，不计入统计
        warm = request(proc, "预热", engine)
        if warm.get("type") != "done":
            print("%-10s %-8s 加载失败: %s" % ("-", engine, warm.get("message")))
            continue
        for name, text in SENTENCES:
            r = request(proc, text, engine)
            if r.get("type") == "done":
                print("%-10s %-8s %8.2f %9.3f %8.3f   %s" % (
                    name, engine, r["seconds"], r["elapsed"], r["rtf"],
                    Path(r["wav"]).name))
            else:
                print("%-10s %-8s 失败: %s" % (name, engine, r.get("message")))
    proc.stdin.close()
    proc.wait()


if __name__ == "__main__":
    main()
