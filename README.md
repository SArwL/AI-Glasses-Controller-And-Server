# AI 眼镜（Android + Jetson 后端）

智能眼镜项目的前后端代码：Android App 负责 BLE 控制眼镜、录音/拍照与 AI 对话；
Jetson AGX Orin 后端负责语音识别、大模型对话与语音合成。

## 仓库结构

| 目录 | 内容 | 说明 |
|------|------|------|
| [`android/`](android/) | Android App | BLE 连接、拍照/图库、录音播放、AI 对话（需要厂商 SDK 二进制） |
| [`jetson-backend/`](jetson-backend/) | Jetson 后端 | Qt 5.15 UDP 服务、SenseVoice ASR、Qwen LLM、Matcha/Kokoro TTS |

## 数据流

```
AI 眼镜 ──BLE/WiFi PCM──→ android/ ──UDP :1050──→ jetson-backend/
                                                    ├─ ASR（SenseVoice）
                                                    ├─ LLM（llama-server）
                                                    └─ TTS（Matcha/Kokoro）──0x32──→ 手机播放
```

## 快速开始

- **Android 端**：用 Android Studio 打开 [`android/`](android/) 目录；厂商 SDK 二进制
  `LIB_GLASSES_SDK-release_3.aar` 需自行放入 `android/app/libs/`。详见 [`android/README.md`](android/README.md)。
- **Jetson 后端**：部署与构建见 [`jetson-backend/README.md`](jetson-backend/README.md)。
- **通信协议**（字节布局已冻结）：[`jetson-backend/docs/udp-protocol-design.md`](jetson-backend/docs/udp-protocol-design.md)。

> 本仓库不包含模型权重、厂商 SDK 二进制与录音/照片等运行数据。
