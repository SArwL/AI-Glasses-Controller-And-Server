# TTS — Matcha / Kokoro 合成桥接

`run_tts.py` 是常驻子进程，由 QwenChat 通过 QProcess 启动，底层为 sherpa-onnx 1.13.8。
每次合成 **同时产出两份文件**：

- `.wav`：原采样率，供本地 `aplay` 播放；
- `.pcm16k`：16k / 16bit / 单声道裸 PCM，供 UDP `0x32` 回传手机。

## 引擎

| engine | 模型 | 特点 | 输出采样率 | 实测 RTF |
|--------|------|------|-----------|----------|
| `matcha`（默认） | Matcha-zh + hifigan_v2 声码器 | 速度快；英文单词会被丢弃 | 22.05 kHz | ≈ 0.088（8 线程） |
| `kokoro` | Kokoro v1.1-zh | 中英混读完整，103 个音色（`sid` 0–102） | 24 kHz | ≈ 0.49（8 线程） |

QwenChat 会按句内语言自动分段：中文/数字走 Matcha，拉丁字母段走 Kokoro，FIFO 保证按序合成与播放。

## 安装

```bash
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

模型下载（约 512MB，走 `hf-mirror.com`）：

```bash
bash download_models.sh all       # 或 matcha / kokoro
```

> ⚠️ `download_models.sh` 内的 `BASE=/mnt/ssd/jetson/tts` 与
> `PY=/mnt/ssd/jetson/asr/venv/bin/python` 是原部署机的绝对路径，迁移时先修改。
> Kokoro 仓库含 348 个 espeak-ng-data 小文件，脚本用 git 克隆小文件、再对 LFS
> 大文件单独 `curl`（必须先删 LFS 指针文件再续传，否则模型会损坏）。

## 用法

常驻模式（供 QwenChat 调用）：

```bash
./venv/bin/python run_tts.py
```

stdin 输入：

```json
{"text": "你好，世界", "engine": "matcha", "sid": 0, "speed": 1.0}
```

stdout 输出：

```json
{"type": "done", "wav": "/abs/out.wav", "pcm16k": "/abs/out.pcm16k",
 "seconds": 1.2, "elapsed": 0.1, "rtf": 0.083}
{"type": "error", "message": "错误信息"}
```

不可朗读的纯符号段会返回空路径（`wav`/`pcm16k` 为空、`seconds: 0`），调用方应静默跳过。

单次合成：

```bash
./venv/bin/python run_tts.py --text "你好，世界" --engine kokoro --sid 3 \
    --speed 1.0 --out /tmp/demo.wav
```

## 其他脚本

| 脚本 | 说明 |
|------|------|
| `bench_tts.py` | 两个引擎的 RTF 基准测试 |
| `demo_llm_tts.py` | LLM 流式回复 → 按标点分句 → 语言分段 → 合成 → `aplay` 播放 |
| `download_models.sh` | 模型下载 / 补齐（`all` / `matcha` / `kokoro`） |

## 环境变量

- `TTS_THREADS`：合成线程数，默认 `8`（Orin 12 核，留 4 核给 llama-server / ASR）。

## 系统依赖

本地播放使用 `aplay`，需安装 `alsa-utils`。
