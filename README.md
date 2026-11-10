# AI 眼镜控制系统（Android + Jetson 后端）

一套端到端的智能眼镜系统：**Android App 通过 BLE 控制眼镜**，完成拍照、图库、录音与 WiFi 媒体同步；
同时作为 UDP 客户端把眼镜采集的语音送到 **Jetson AGX Orin 本地后端**，由后端完成
**语音识别（ASR）→ 大模型对话（LLM）→ 语音合成（TTS）**，再把语音回复流式回传给手机播放。

全链路在本地区域网内运行，不依赖云端服务。

```
┌────────────┐  BLE 指令 / WiFi P2P   ┌──────────────┐   UDP :1050   ┌──────────────────────────┐
│  AI 眼镜   │ ─────── PCM / 媒体 ───→│  Android App │ ────────────→ │  Jetson AGX Orin         │
│ CY01_0799  │ ←────── 设备事件 ──────│  UDP 客户端  │ ←──────────── │  QwenChat（Qt 5.15）      │
└────────────┘                        └──────────────┘  0x32 语音    │  ASR / LLM / TTS 子进程   │
                                                                     └──────────────────────────┘
```

## 功能特性

### 眼镜控制与媒体

- **BLE 连接**：设备扫描、配对、连接状态与电量监听
- **拍照与图库**：相机控制、缩略图预览、3 列图库、滑动查看、删除、保存到系统相册
- **录音与播放**：录音管理、音频列表、播放控制（MediaPlayer，裸 PCM/OPUS 自动转 WAV）
- **WiFi 媒体同步**：拍照/录音后经 SDK 的 P2P 通道自动下载，按设备名归档到 `picture` / `video` / `audio`
- **BT 经典蓝牙配对**：BLE 连接后可配对 A2DP，让眼镜播放手机音频
- **动态权限**：蓝牙 / 定位 / 存储 / 录音 / WiFi（XXPermissions）

### AI 语音助手

- **后端连接管理**：输入 IP/端口（默认 `1050`）、应用层握手校验、历史地址记忆、主界面状态显示
- **语音对话**：眼镜麦克风 PCM 自动采集、分片上传，后端识别、思考并合成语音回传
- **流式回复**：后端 LLM 流式输出 → 按句合成 → 逐段回传，手机边收边播，降低首字延迟
- **语音打断**：用户再次开口立即停播并清空队列，同时向后端发送打断请求
- **智能拍照上传**：智能拍照的 JPEG 可通过 `0x20` 上传至后端并落盘；后端 LLM 已具备视觉能力，接入对话链路后可扩展为多模态问答
- **隐私友好**：对话 PCM 仅作 ASR 中转，上传结束后立即删除，不进入用户媒体库

### 工程化能力

- **零界面自测**：后端 `--selftest` 无需手机即可跑通「握手 → 分片 → 重组 → ASR → LLM」
- **单链路验证**：后端 `--speak "文本"` 可单独验证 TTS 切句、合成与回传
- **真机调试脚本**：`android/tools/` 提供网络接口、P2P、路由与后端可达性探针

## 系统架构

系统分为三层，职责边界清晰：

| 层 | 运行位置 | 职责 |
|----|----------|------|
| 设备层 | AI 眼镜 CY01_0799 | 麦克风采集、摄像头/录音、BLE GATT 服务、WiFi P2P 文件服务 |
| 应用层 | Android 手机 | 控制眼镜、媒体同步与展示、语音上传、回复播放、后端配置 |
| 服务层 | Jetson AGX Orin | UDP 服务、语音识别、大模型对话、语音合成、气泡聊天界面 |

**为什么手机是 UDP 客户端、Jetson 是服务端**：眼镜使用 WiFi Direct 时手机的网络与 IP 可能变化，
而 Jetson 在局域网内地址固定，因此由固定端监听端口、手机主动连接并上报自己的临时端口。

## AI 对话实现流程

```
眼镜麦克风
   │ ① SDK voiceFromGlasses 逐帧回调（PCM）
   ▼
Android：写入 temp 分片 → 300ms 无新帧判定说完 → 拼接
   │ ② UDP 0x10，16KB/片，16 字节头含 seq/total
   ▼
Jetson：收齐分片 → 按序拼接 → SenseVoice-Small 识别
   │ ③ 识别文本
   ▼
Jetson：llama-server 流式生成 → 按句切分 → TTS 合成（16k PCM）
   │ ④ UDP 0x32，逐段分片回传
   ▼
Android：按 seq 重组 → AudioTrack 连续播放
```

1. **连接与握手**：UDP 无连接，`connect()` 成功不代表对端可达。App 发送 16 字节 `PROBE`
   （魔数 `GLSY` + 版本 + 类型 + 时间戳），超时 800ms、最多 3 次；收到 `PROBE_ACK` 才算连接成功。
2. **语音采集**：`AiDialogueManager` 把 SDK 回调的每帧 PCM 写成带序号的分片文件，
   后台每 100ms 轮询；连续 300ms 无新分片即判定说话结束，在子线程按序拼接。
3. **分片上传**：拼接结果放在应用缓存目录，`AiUploader` 以 16KB 为一片、带 `seq`/`total`
   经 `0x10` 发送。上传流程结束（成功/失败/断线）后立即删除本地 PCM。
4. **语音识别**：Jetson 侧收齐分片后整体送入 SenseVoice-Small（sherpa-onnx，CPU int8 推理，
   RTF ≈ 0.038）。
5. **大模型对话**：识别文本经 `qwen_client.py` 送入 `llama-server`（Qwen3.6-35B-A3B MoE，Q4_K_M，
   激活约 3B），流式返回 token，解码约 30 tok/s。
6. **分句合成**：按标点切句，再按语言分段——中文/数字用 Matcha，拉丁字母段用 Kokoro；
   FIFO 保证合成与播放顺序，每段同时产出 wav 与 16k 单声道 PCM。
7. **回传播放**：每段 PCM 以 `0x32` 分片回传，`seq==0` 表示新一段开始；手机按 `seq` 重组后写入
   `AudioTrack`。设备不支持 16kHz 时降级到 48kHz 并重采样。
8. **打断**：用户再次开口时手机停播、清空队列并发送 `0x40`；后端收到后取消 TTS 合成与播放队列、抑制本轮剩余播报，并回
   `0x33`。收到 `0x33` 时手机只停播、不再回 `0x40`，避免两端互相打断形成风暴。

## Android 端实现

采用 **Activity + Manager** 分层，SDK 回调与设备事件通过 EventBus 和回调接口分发到各 Manager：

| 模块 | 主要类 | 职责 |
|------|--------|------|
| 设备控制 | `BleConnectionManager` | 扫描、配对、连接状态、电量、BLE 指令 |
| 媒体系 | `PhotoManager` / `RecordingManager` / `AudioPlaybackManager` | 拍照、录音、本地音频播放 |
| 媒体同步 | `MediaSyncManager` | 监听设备事件、触发 SDK P2P 下载、按扩展名归档（中心枢纽） |
| AI 采集 | `AiDialogueManager` | PCM 分片落盘、静默判定、拼接 |
| AI 网络 | `AiUdpManager` | UDP 单例、握手、收发、监听器分发 |
| AI 上传 | `AiUploader` | 16KB 分片上传、进度与失败分类、一次性数据清理 |
| AI 播放 | `AiReplyAudioManager` | `0x32` 重组、`AudioTrack` 播放、打断 |
| 配置 | `BackendConfig` / `BackendConfigStore` | 后端地址历史 JSON 持久化（最多 10 条） |
| 界面 | `MainActivity` / `DeviceScanActivity` / `AiConnectionActivity` / `GalleryActivity` / `PhotoViewerActivity` | 主界面、扫描、AI 连接、图库、查看器 |

关键实现取舍：

- **不用 `MediaPlayer` 播回复**：回复是流式到达的裸 PCM，没有文件头也不是完整文件，
  因此直接写 `AudioTrack`；本地录音/音频仍由 ExoPlayer 播放，两者互不影响。
- **不在主线程发网络包**：SDK 的设备事件回调在主线程，UDP 发送会触发
  `NetworkOnMainThreadException`，因此网络操作统一切到工作线程。
- **AI PCM 放缓存目录**：既不进入音频列表，也可被系统在存储紧张时回收；上传后立即删除。
- **配置读写异步**：`BackendConfigStore` 是同步文件 I/O，调用方确保不在主线程执行。

存储布局：

```
<externalFilesDir>/<设备名>/
├── picture/    # 照片（长期保存）
├── video/      # 视频
├── audio/      # 录音；AI 对话分片在 audio/temp/，拼接后清理
└── （缓存目录） # AI 拼接结果，上传后删除
<externalFilesDir>/ai_backend_config.json   # 后端地址历史
```

## Jetson 后端实现

`qwenchat/` 是 Qt 5.15 编写的 UDP 服务端，同时作为进程编排器与聊天界面，用 `QProcess`
以 **stdin/stdout JSON-lines** 协议管理三个常驻 Python 子进程：

| 组件 | 实现 | 接口 / 性能 |
|------|------|-------------|
| `qwenchat/` | Qt 5.15 + C++11：握手应答、分片重组、调度、音频回传、气泡界面 | UDP `0.0.0.0:1050`（`QWEN_UDP_PORT` 可覆盖） |
| ASR | `asr/run_asr.py`：sherpa-onnx + SenseVoice-Small int8，CPU 4 线程 | RTF ≈ 0.038（约 26× 实时） |
| LLM | `llm/`：llama.cpp `llama-server`，Qwen3.6-35B-A3B UD-Q4_K_M + mmproj-F16 | OpenAI 兼容 API `:8080`，解码约 30 tok/s |
| TTS | `tts/run_tts.py`：sherpa-onnx，Matcha-zh / Kokoro v1.1-zh | Matcha RTF ≈ 0.088；Kokoro RTF ≈ 0.49 |

实现要点：

- **分片重组**：`0x10` / `0x32` 都带 `seq`/`total`，收齐后按序拼接；缺片会提示并丢弃该段。
- **按句流式**：LLM token 流 → 标点切句 → 语言分段 → 合成队列 → 每段完成立即回传，不必等全文生成。
- **打断**：`ttsclient.cancelAll()` 清空合成队列；`0x40`、键盘输入、用户新语音均可触发。
- **进程收敛**：子进程按 `closeWriteChannel → waitForFinished → kill` 优雅关闭，退出阶段用
  `m_shuttingDown` 标志拦截残留输出，避免析构期崩溃。
- **自测**：`QT_QPA_PLATFORM=offscreen ./QwenChat --selftest` 无界面跑通全链路；端口占用会以退出码 6 结束。
- **落盘**：`received/<日期>_<对端IP>_<端口>/voice_<毫秒>.pcm` 与 `photo_NNN.jpg`。

## 通信协议

- 传输：**UDP（IPv4）**，Jetson 监听 `0.0.0.0:1050`，手机源端口由系统随机分配。
- 所有报文以 16 字节头开始：魔数 `GLSY` + 版本 `1` + 消息类型；握手报文与分片报文共用头部，
  分片报文中偏移 6~15 重新定义为载荷长度、`seq`、`total`。
- 单片载荷 **16KB**，语音与回复音频使用同一套分片格式。

| 类型 | 方向 | 含义 |
|------|------|------|
| `0x01` / `0x02` | App ↔ 后端 | PROBE / PROBE_ACK |
| `0x10` | App → 后端 | 语音 PCM 分片 |
| `0x11` | App → 后端 | 语音元数据（JSON） |
| `0x20` | App → 后端 | 照片 JPEG |
| `0x30` | 后端 → App | 回复文本（预留） |
| `0x31` | 后端 → App | 拍照指令（预留） |
| `0x32` | 后端 → App | 回复音频分片（16kHz / 16bit / 单声道 / 小端） |
| `0x33` | 后端 → App | 停止播报 |
| `0x40` | App → 后端 | 打断请求 |
| `0x7F` | 双向 | 心跳（协议预留） |

> 字节布局已冻结，修改任何字段都会导致 App 与后端无法互通。
> 完整设计见 [`jetson-backend/docs/udp-protocol-design.md`](jetson-backend/docs/udp-protocol-design.md)。

## 仓库结构

```
.
├── android/                 # Android App（BLE 控制、媒体、AI 对话客户端）
│   ├── app/src/main/java/com/sdk/glassessdksample/
│   ├── tools/               # 真机网络/PCM 调试脚本
│   └── README.md            # Android 构建、使用与协议摘要
└── jetson-backend/          # Jetson 后端
    ├── qwenchat/            # Qt UDP 服务端 + 气泡界面
    ├── asr/                 # SenseVoice 识别桥接
    ├── llm/                 # llama-server 部署（模型、systemd）
    ├── tts/                 # Matcha / Kokoro 合成桥接
    ├── docs/                # UDP 协议设计
    └── README.md            # 部署与构建
```

## 快速开始

### Android 端

1. 用 Android Studio 打开 [`android/`](android/) 目录。
2. 从厂商渠道获取 `LIB_GLASSES_SDK-release_3.aar`，放入 `android/app/libs/`。
3. `./gradlew assembleDebug` 或在 IDE 中直接 Run。

要求：compileSdk / targetSdk 34、minSdk 24、Gradle 8.5（wrapper 自带）、ABI `armeabi-v7a` / `arm64-v8a`。
详见 [`android/README.md`](android/README.md)。

### Jetson 后端

1. 按 [`jetson-backend/README.md`](jetson-backend/README.md) 准备 JetPack 5.1.2、Qt 5.15.2、
   Python 3.8 venv 与 `alsa-utils`。
2. 部署 LLM：按 [`jetson-backend/llm/README.md`](jetson-backend/llm/README.md) 下载 GGUF 模型并安装
   `llama-server` systemd 服务。
3. 安装 ASR/TTS 依赖并下载模型（见各子目录 README）。
4. 修改 `qwenchat/QwenChat.pro` 中的部署路径宏后编译运行：

```bash
cd jetson-backend/qwenchat/build
/path/to/Qt/5.15.2/bin/qmake ../QwenChat.pro
make -j"$(nproc)"
./QwenChat                       # 默认监听 UDP 1050
```

5. 手机与 Jetson 接入同一局域网，在 App「AI 交互模式」中填写 Jetson IP 与端口即可。

## 已知限制

- **UDP 无 ACK / 重传**：`seq`/`total` 已预留，后端补齐 ACK 后可扩展可靠传输。
- **无自动重连**：连接断开后需在「AI 交互模式」页手动重连。
- **局域网明文通信**：UDP 通道无鉴权、无加密，仅适用于可信局域网，请勿暴露到公网。
- **专有内容不入库**：厂商 SDK 二进制、模型权重、录音/照片等运行数据均不随仓库分发。
- SenseVoice 模型权重的商业使用需阿里授权。

## 文档索引

- Android 构建与使用：[`android/README.md`](android/README.md)
- 后端部署与构建：[`jetson-backend/README.md`](jetson-backend/README.md)
- UDP 协议设计：[`jetson-backend/docs/udp-protocol-design.md`](jetson-backend/docs/udp-protocol-design.md)
- LLM 部署：[`jetson-backend/llm/README.md`](jetson-backend/llm/README.md)
- ASR / TTS 桥接：[`jetson-backend/asr/README.md`](jetson-backend/asr/README.md)、[`jetson-backend/tts/README.md`](jetson-backend/tts/README.md)
- 服务端实现：[`jetson-backend/qwenchat/README.md`](jetson-backend/qwenchat/README.md)
