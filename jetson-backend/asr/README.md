# ASR — SenseVoice-Small 识别桥接

`run_asr.py` 是常驻子进程，由 QwenChat 通过 QProcess 启动，双方用 stdin/stdout 的
JSON-lines 协议通信；底层为 sherpa-onnx 1.13.8 + SenseVoice-Small int8 模型（CPU 推理）。

## 安装

```bash
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

模型目录固定为脚本同级的 `models/sensevoice/`，需要两个文件：

```bash
mkdir -p models/sensevoice
export HF_ENDPOINT=https://hf-mirror.com
BASE=https://hf-mirror.com/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main
curl -fL -o models/sensevoice/model.int8.onnx "$BASE/model.int8.onnx"
curl -fL -o models/sensevoice/tokens.txt     "$BASE/tokens.txt"
```

> 国内访问 HuggingFace 走 `hf-mirror.com`；模型约 229MB。
> SenseVoice 模型权重的商业使用需阿里授权。

## 用法

常驻模式（供 QwenChat 调用）：

```bash
./venv/bin/python run_asr.py
```

stdin 输入：

```json
{"path": "/abs/path/audio.pcm", "sample_rate": 16000}
```

- `.wav` 自动读取头部采样率；
- `.pcm` 按 16k / 16bit / 单声道 / 小端裸数据解析，可用 `sample_rate` 覆盖。

stdout 输出：

```json
{"type": "done", "text": "识别结果"}
{"type": "error", "message": "错误信息"}
```

单次识别模式：

```bash
./venv/bin/python run_asr.py --file test.wav
```

## 性能

Jetson AGX Orin 64GB、CPU 4 线程：RTF ≈ 0.038（约 26× 实时），留出核心给 llama-server。

## 备注

- 识别语言自动检测（`language="auto"`），不启用逆文本正则化（`use_itn=False`）。
- 模型路径相对脚本解析，整体移动目录后无需改代码。
