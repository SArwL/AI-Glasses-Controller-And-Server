# AI Glasses Controller (Android)

基于厂商 SDK 的**智能眼镜控制 Android 应用**，通过 BLE 连接眼镜设备，实现拍照、图库、录音、音频播放、AI 对话等能力。

## 功能特性

- 🔵 **BLE 连接管理**：设备扫描、配对、连接状态监听（`BleConnectionManager`）
- 📷 **拍照与图库**：相机控制、照片同步、图片查看（`PhotoManager` / `GalleryActivity`）
- 🎙️ **录音与播放**：录音管理、音频文件浏览、播放控制（`RecordingManager` / `AudioPlaybackManager`）
- 🤖 **AI 对话**：与眼镜端 AI 语音助手对话（`AiDialogueManager`）
- 📡 **WiFi Direct**：声明 `wifi.direct` 硬件特性
- 🔐 **权限管理**：基于 XXPermissions 的动态权限请求（蓝牙 / 定位 / 存储 / 录音）

## 环境要求

| 项 | 版本 |
|----|------|
| Android Studio | 最新稳定版（内置 JBR 21） |
| compileSdk | 34 |
| minSdk | 24 |
| Gradle | 8.5（wrapper 自带） |

## ⚠️ 构建前必读

厂商 SDK 二进制（`LIB_GLASSES_SDK-release_3.aar`）为**专有内容，未包含在本仓库**。要编译运行，请：

1. 从厂商渠道获取 `LIB_GLASSES_SDK-release_3.aar`
2. 放入 `app/libs/` 目录

```bash
app/libs/LIB_GLASSES_SDK-release_3.aar   # 需自行放置
```

## 构建

```bash
# 打开项目后直接 Sync / Run 即可；或命令行：
./gradlew assembleDebug
```

首次构建建议配置国内镜像（`settings.gradle.kts` 已内置 Aliyun + JitPack 镜像）。

## 项目结构

```
app/src/main/java/com/sdk/glassessdksample/
├── GlassApp.java               # Application
├── activities/                 # 主界面 / 设备扫描 / 图库 / 照片查看
├── managers/                   # BLE / 拍照 / 录音 / 音频 / AI 对话 / 媒体同步
├── receivers/                  # 蓝牙广播接收
├── events/                     # EventBus 事件
└── utils/                      # 文件 / PCM / 权限工具
```

## 配套服务器端（Jetson）

除 Android App 外，本仓库还包含运行在 Jetson AGX Orin 上的配套后端：[`jetson-backend/`](jetson-backend/)。

- UDP 服务端（Qt 5.15）：握手应答、语音/图片接收、分片重组、音频回传
- 三路 Python 桥接：SenseVoice ASR / Qwen LLM（llama-server）/ Matcha + Kokoro TTS
- 协议设计文档（字节布局已冻结）：[`jetson-backend/docs/udp-protocol-design.md`](jetson-backend/docs/udp-protocol-design.md)

部署与构建说明见 [`jetson-backend/README.md`](jetson-backend/README.md)。
