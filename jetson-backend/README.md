# AI 眼镜 Jetson 后端

本目录是 AI 眼镜（CY01_0799）的 **Jetson AGX Orin 服务端**，与仓库根目录的 Android App
通过局域网 UDP 通信：接收语音与图片、调用本地 ASR / LLM / TTS，并把语音回复回传给手机。

## 系统架构

```
┌──────────────┐  BLE / WiFi  ┌───────────────┐    UDP :1050    ┌───────────────────────────────┐
│   AI 眼镜     │ ───PCM────→ │  Android App  │ ─────────────→ │ QwenChat（Qt 5.15，本目录）    │
│  CY01_0799   │             │  UDP 客户端    │ ←───────────── │ UDP 服务端 + 气泡界面          │
└──────────────┘             └───────────────┘  0x32 语音回传  └──────────────┬────────────────┘
                                                                            │ stdin/stdout JSON-lines
                                                       ┌────────────────────┼──────────────────────┐
                                                       ▼                    ▼                      ▼
                                                 run_asr.py          qwen_client.py          run_tts.py
                                                SenseVoice-Small     llama-server (:8080)    Matcha-zh / Kokoro
```

## 目录结构

```
jetson-backend/
├── docs/
│   └── udp-protocol-design.md   # UDP 协议设计（字节布局已冻结）
├── qwenchat/                    # Qt 5.15 UDP 服务端 + 气泡 UI
│   ├── QwenChat.pro
│   ├── main.cpp / chatwindow.* / udpreceiver.* / asrclient.* / ttsclient.*
│   └── python/qwen_client.py    # LLM 桥接：llama-server OpenAI 兼容 API
├── asr/
│   ├── run_asr.py               # SenseVoice-Small 识别桥接
│   └── requirements.txt
├── tts/
│   ├── run_tts.py               # 合成桥接：同时输出 .wav 与 16k PCM
│   ├── bench_tts.py             # 性能基准
│   ├── demo_llm_tts.py          # LLM 流式 → 分句 → 合成 → 播放示例
│   ├── download_models.sh
│   └── requirements.txt
└── llm/
    ├── README.md                # llama-server 部署与 API 说明
    └── scripts/                 # systemd unit、模型下载、冒烟测试
```

## 组件说明

| 组件 | 职责 | 接口 |
|------|------|------|
| `qwenchat/` | 握手应答、语音/图片接收、分片重组、调度 ASR/LLM/TTS、音频回传 | UDP `0.0.0.0:1050`（`QWEN_UDP_PORT` 可覆盖） |
| `qwenchat/python/qwen_client.py` | 多轮对话、流式转发 LLM 输出 | 子进程 stdin/stdout JSON-lines → llama-server `:8080` |
| `asr/run_asr.py` | 语音转文字 | 子进程 JSON-lines；16k/16bit/单声道 PCM 或 wav |
| `tts/run_tts.py` | 文字转语音 | 子进程 JSON-lines；输出原采样率 wav + 16k 裸 PCM |
| `llm/` | 本地大模型推理服务 | HTTP OpenAI 兼容 API `0.0.0.0:8080` |

## 处理链路

1. App 发送 `PROBE`（16 字节头，800ms 超时 × 3 次重试），Jetson 回 `PROBE_ACK`。
2. 语音以 `0x10 VOICE_PCM` 分片上传（16384 B/片，16 字节头内含 `seq/total`），Jetson 收齐后按序拼接并整体送 ASR。
3. 识别文本送 LLM（流式 token），按句切分后送 TTS。
4. 每段合成音频通过 `0x32 REPLY_AUDIO` 分片回传手机；无有效对端时回退本地 `aplay` 播放。
5. 用户打断时 Jetson 发 `0x33 REPLY_AUDIO_STOP`；手机也可以 `0x40 ABORT_REQ` 请求打断。

协议细节与字节布局见 [`docs/udp-protocol-design.md`](docs/udp-protocol-design.md)。
**协议已冻结**：修改任何字节布局都会导致 Android App 无法连接。

## 部署步骤

### 1. 环境

- JetPack 5.1.2（Ubuntu 20.04，aarch64）
- Qt 5.15.2（官方无 Linux ARM64 预编译包，需源码编译；使用 `core gui widgets network` 模块）
- Python 3.8 + venv，`sherpa-onnx==1.13.8`、`numpy==1.24.4`
- `alsa-utils`（本地播放依赖 `aplay`）
- llama.cpp 编译出的 `llama-server`

### 2. LLM

按 [`llm/README.md`](llm/README.md) 下载 GGUF 模型并安装 systemd 服务（默认监听 `:8080`）。

### 3. ASR / TTS

```bash
python3 -m venv venv
source venv/bin/activate
pip install -r asr/requirements.txt
# 按 asr/README.md 下载 SenseVoice 模型到 asr/models/sensevoice/

bash tts/download_models.sh all   # 先按 tts/README.md 修改脚本内的绝对路径
```

### 4. 构建 QwenChat

先在 [`qwenchat/QwenChat.pro`](qwenchat/QwenChat.pro) 中把以下绝对路径宏改成实际部署路径：
`PYTHON_CLIENT_PATH`、`RECEIVED_DIR`、`ASR_PYTHON_PATH`、`ASR_SCRIPT_PATH`、
`TTS_PYTHON_PATH`、`TTS_SCRIPT_PATH`。

```bash
mkdir -p qwenchat/build && cd qwenchat/build
/path/to/Qt/5.15.2/bin/qmake ../QwenChat.pro
make -j"$(nproc)"
```

### 5. 运行

```bash
./QwenChat                       # 默认监听 UDP 1050
QWEN_UDP_PORT=1052 ./QwenChat    # 自定义端口（多实例 / 联调）
```

## 自测

无需手机即可跑通「聊天 → 握手 → 语音分片 → 重组 → ASR → LLM」全链路：

```bash
cd qwenchat/build
QT_QPA_PLATFORM=offscreen ./QwenChat --selftest
```

成功时退出码为 0 并输出 `SELFTEST_OK`；端口被占用会立刻以退出码 6 结束（防止误判）。

单独验证 TTS 播报链路：

```bash
QT_QPA_PLATFORM=offscreen ./QwenChat --speak "这个 RTF 是 0.09"
```

## 声明

- UDP 通道无鉴权、无加密，仅用于可信局域网，请勿暴露到公网。
- 模型权重不随仓库分发，按各子目录 README 自行下载；SenseVoice 模型权重商用需阿里授权。
