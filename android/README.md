# AI Glasses Controller (Android)

> 本目录为 Android 端工程；配套的 Jetson 后端见仓库根目录的 [`../jetson-backend/`](../jetson-backend/)。

基于厂商 SDK 的**智能眼镜控制 Android 应用**：通过 BLE 连接眼镜，完成设备扫描/配对、拍照与图库、WiFi 媒体同步、录音与音频播放；并通过 **UDP 接入 Jetson 后端的 AI 语音对话链路**（语音上传 → ASR → LLM → TTS → 手机播放）。

## 功能特性

- 🔵 **BLE 连接管理**：设备扫描、配对、连接状态与电量监听（`BleConnectionManager`）
- 📷 **拍照与图库**：相机控制、照片同步、缩略图预览、图库浏览与查看（`PhotoManager` / `GalleryActivity` / `PhotoViewerActivity`）
- 🎙️ **录音与播放**：录音管理、音频文件浏览、播放控制（`RecordingManager` / `AudioPlaybackManager`）
- 📥 **WiFi 媒体同步**：拍照/录音完成后经 SDK P2P 通道下载，按设备名归档到 `picture` / `video` / `audio`（`MediaSyncManager`）
- 🤖 **AI 语音对话**：PCM 分片上传、流式语音回复、说话打断（`AiDialogueManager` / `AiUdpManager` / `AiUploader` / `AiReplyAudioManager`）
- 🌐 **后端连接管理**：IP/端口输入、握手校验、历史地址记忆、主界面连接状态显示（`AiConnectionActivity` / `BackendConfigStore`）
- 🔐 **权限管理**：基于 XXPermissions 的动态权限请求（蓝牙 / 定位 / 存储 / 录音 / WiFi）
- 🧰 **调试脚本**：网络与 PCM 事件探针，见 [`tools/`](tools/)

## AI 对话链路

```
眼镜麦克风 ──BLE/WiFi PCM──→ AiDialogueManager（分片拼接）
                                      │
                                      │ UDP 0x10 语音分片
                                      ▼
                              Jetson 后端（ASR → LLM → TTS）
                                      │
              UDP 0x32 音频分片 / 0x33 停止播报
                                      ▼
                        AiReplyAudioManager（重组 → AudioTrack 播放）
```

1. **采集**：眼镜端进入 AI 对话模式后，麦克风 PCM 经 SDK `voiceFromGlasses` 回调逐帧到达；`AiDialogueManager` 将每帧写入 temp 分片，连续 300ms 无新帧即判定说话结束并拼接。
2. **上传**：拼接结果放在应用缓存目录，`AiUploader` 以 16KB 分片经 UDP 上传（`0x10`）。**无论成败，上传流程结束后立即删除本地 PCM**——它只是送给后端做语音识别的过路数据，不属于用户媒体。
3. **回复**：后端回传的 PCM 分片（`0x32`）由 `AiReplyAudioManager` 按 `seq` 重组，交 `AudioTrack` 连续播放；`0x33` 表示后端要求停止播报。
4. **打断**：用户再次开口时，App 立即停播、清空队列，并向后端发送 `0x40` 打断请求。

> 普通录音/照片/录像走的是 WiFi 媒体同步链路，长期保存在本地媒体目录，与 AI 对话的临时 PCM 完全分开。

## 环境要求

| 项 | 版本 / 说明 |
|----|-------------|
| Android Studio | 最新稳定版（内置 JetBrains Runtime） |
| compileSdk / targetSdk | 34 |
| minSdk | 24 |
| Gradle | 8.5（wrapper 自带） |
| ABI | `armeabi-v7a`、`arm64-v8a` |
| 网络 | 手机与 Jetson 处于同一局域网（UDP 默认端口 **1050**） |

## ⚠️ 构建前必读

厂商 SDK 二进制（`LIB_GLASSES_SDK-release_3.aar`）为**专有内容，未包含在本仓库**。要编译运行，请：

1. 从厂商渠道获取 `LIB_GLASSES_SDK-release_3.aar`
2. 放入 `app/libs/` 目录

```bash
app/libs/LIB_GLASSES_SDK-release_3.aar   # 需自行放置
```

## 构建

```bash
# 用 Android Studio 打开本目录（android/）后 Sync / Run；或在 android/ 下执行：
./gradlew assembleDebug
```

首次构建建议配置国内镜像（`settings.gradle.kts` 已内置 Aliyun + JitPack 镜像）。

## 使用说明

1. **连接眼镜**：主界面 →「扫描设备」→ 选择设备完成 BLE 连接（可选「连接音频」做 BT 经典蓝牙配对，让眼镜播放手机音频）。
2. **配置 AI 后端**：主界面 →「AI 交互模式」→ 输入 Jetson 的 IPv4 地址与端口（默认 `1050`）→「连接」。
   - 应用会自动尝试验证握手（16 字节 PROBE / PROBE_ACK）；成功后自动记住地址，下次进入自动重连最近一条。
   - 后端尚未实现握手回包时，可勾选「跳过握手校验」用于联调。
   - 离开页面后 UDP 连接保持；主界面底部实时显示 `● 已连接` / `○ 未连接`。
3. **发起对话**：在眼镜端进入 AI 对话模式并说话，语音会自动上传；后端回复的语音由手机 `AudioTrack` 播放。
4. **媒体同步与图库**：拍照/录音后自动触发 WiFi 同步；主界面可浏览音频列表，图库中可查看/删除照片。

## UDP 协议摘要

所有报文均以魔数 `GLSY`（`0x47 0x4C 0x53 0x59`）+ 协议版本 `1` 开头。

**握手报文（16 字节定长）**

| 偏移 | 长度 | 含义 |
|------|------|------|
| 0 | 4 | 魔数 `GLSY` |
| 4 | 1 | 协议版本 = 1 |
| 5 | 1 | 消息类型：`0x01` PROBE / `0x02` PROBE_ACK |
| 6 | 2 | 保留 |
| 8 | 8 | 发送端时间戳（epoch millis，大端） |

**主要消息类型**

| 类型 | 方向 | 含义 |
|------|------|------|
| `0x01` / `0x02` | App ↔ 后端 | 握手探测 / 应答 |
| `0x10` | App → 后端 | 语音 PCM 分片 |
| `0x11` | App → 后端 | 语音元数据 |
| `0x20` | App → 后端 | 智能拍照 JPEG（缩略图） |
| `0x30` | 后端 → App | 回复文本 |
| `0x31` | 后端 → App | 拍照指令 |
| `0x32` | 后端 → App | 回复音频分片（16kHz / 16bit / 单声道 / 小端） |
| `0x33` | 后端 → App | 停止播报 |
| `0x40` | App → 后端 | 打断请求 |
| `0x7F` | — | 心跳（预留） |

**数据分片（16 字节头 + 载荷）**

| 偏移 | 长度 | 含义 |
|------|------|------|
| 0 | 4 | 魔数 `GLSY` |
| 4 | 1 | 协议版本 = 1 |
| 5 | 1 | 消息类型 |
| 6 | 2 | 载荷长度（大端） |
| 8 | 2 | 分片序号 `seq`（从 0 开始） |
| 10 | 2 | 总片数 `total` |
| 12 | 4 | 保留 |
| 16 | N | 载荷（单片最大 16KB） |

完整的协议设计与服务端实现见 [`../jetson-backend/docs/udp-protocol-design.md`](../jetson-backend/docs/udp-protocol-design.md)。

## 项目结构

```
app/src/main/java/com/sdk/glassessdksample/
├── GlassApp.java                    # Application：设备目录、BLE 广播注册
├── activities/
│   ├── MainActivity.java            # 主界面：连接/拍照/录音/同步/AI 入口
│   ├── DeviceScanActivity.java      # BLE 设备扫描
│   ├── AiConnectionActivity.java    # AI 后端连接页（IP/端口、握手、历史）
│   ├── GalleryActivity.java         # 照片图库
│   └── PhotoViewerActivity.java     # 照片查看/滑动翻页
├── managers/
│   ├── BleConnectionManager.java    # BLE 连接、状态与电量
│   ├── MediaSyncManager.java        # WiFi 媒体同步、设备事件分发（中心枢纽）
│   ├── PhotoManager.java            # 拍照与缩略图
│   ├── RecordingManager.java        # 录音
│   ├── AudioPlaybackManager.java    # 本地音频播放
│   ├── AiDialogueManager.java       # AI 语音 PCM 采集与拼接
│   ├── AiUdpManager.java            # UDP 连接/握手/收发（单例）
│   ├── AiUploader.java              # 分片上传（PCM/JPEG）
│   ├── AiReplyAudioManager.java     # 回复音频重组与 AudioTrack 播放
│   ├── BackendConfig.java           # 后端配置条目
│   ├── BackendConfigStore.java      # 配置历史 JSON 持久化
│   ├── AudioFileAdapter.java / AudioFileItem.java / OnCaptureCompleteListener.java
├── receivers/                       # 蓝牙广播接收
├── events/                          # EventBus 事件
└── utils/                           # FileUtils / PcmUtils / PermissionHelper

tools/                               # 网络与 PCM 调试脚本
```

## 数据存储

| 内容 | 位置 | 说明 |
|------|------|------|
| 照片/视频/音频 | `<externalFilesDir>/<设备名>/{picture,video,audio}` | 按设备名分目录；首次连接自动创建 |
| AI 对话 PCM 分片 | `<audioDir>/temp/` | 拼接完成后清理 |
| AI 对话拼接结果 | `<cacheDir>/<中转目录>/` | 上传结束后立即删除 |
| 后端地址历史 | `<externalFilesDir>/ai_backend_config.json` | 最多 10 条，最近使用置顶 |

App 允许局域网明文流量（`usesCleartextTraffic` + `network_security_config`），以便访问内网 Jetson 服务。

## 调试工具

`tools/` 下提供了真机排查脚本，按运行位置分为两类：

| 脚本 | 运行位置 | 作用 |
|------|----------|------|
| `livetest.sh` | 眼镜/手机（`sh`） | 每秒记录网络接口、路由与后端 HTTP 可达性，结果写入 `/data/local/tmp/livetest.txt` |
| `netprobe.sh` | 眼镜/手机（`sh`） | 每 200ms 采样网络接口，仅在状态变化时输出（验证 PCM 流是否影响 WiFi） |
| `netwatch.sh` | 眼镜/手机（`sh`） | 每秒记录接口/p2p 状态与 `wlan0` 默认路由，写入 `/data/local/tmp/netwatch.txt` |
| `netstate.sh` | PC（`bash`） | 通过 adb 抓取一次完整的网络状态快照，可附带 ping 后端 |

> 脚本中的 Jetson IP（`192.168.0.194`）和 adb 路径（`D:/Android/Android-SDK/platform-tools/adb.exe`）是示例值，使用前请按实际环境修改。

## 已知限制

- UDP 分片**无 ACK / 重传**：`seq`/`total` 已预留，后端补齐 ACK 后可扩展可靠传输。
- **无自动重连**：连接断开后需在「AI 交互模式」页手动重连。
- 握手依赖后端回 `PROBE_ACK`；后端未实现时可勾选「跳过握手校验」联调。
- 厂商 SDK 二进制与模型权重不随仓库分发，需自行获取。
