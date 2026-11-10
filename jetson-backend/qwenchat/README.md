# QwenChat — Qt UDP 服务端

Jetson 侧的 UDP 服务与聊天界面，Qt 5.15 / C++11。

## 文件说明

| 文件 | 说明 |
|------|------|
| `main.cpp` | 程序入口；`--selftest` 结束后打印 `SELFTEST_OK / SELFTEST_FAIL` |
| `chatwindow.*` | 微信风格气泡界面：用户右绿、助手左白、流式 token、系统消息、两个复选框 |
| `udpreceiver.*` | UDP 服务：握手应答、消息分发、语音分片重组、图片落盘、音频回传 |
| `asrclient.*` | ASR 桥接（QProcess），带识别队列 |
| `ttsclient.*` | TTS 桥接（QProcess），带合成队列，`cancelAll()` 用于打断 |
| `python/qwen_client.py` | LLM 桥接（仅标准库），维护多轮对话历史 |
| `QwenChat.pro` | qmake 工程，含部署路径宏 |

## 构建

依赖 Qt 5.15 的 `core gui widgets network` 模块。

```bash
mkdir -p build && cd build
/path/to/Qt/5.15.2/bin/qmake ../QwenChat.pro
make -j"$(nproc)"
```

## 部署路径宏

`QwenChat.pro` 通过 `DEFINES` 注入绝对路径，换机器部署时需要修改：

| 宏 | 用途 |
|----|------|
| `PYTHON_CLIENT_PATH` | `python/qwen_client.py` 绝对路径 |
| `RECEIVED_DIR` | 语音/图片落盘目录 |
| `ASR_PYTHON_PATH` / `ASR_SCRIPT_PATH` | ASR venv 解释器与 `run_asr.py` |
| `TTS_PYTHON_PATH` / `TTS_SCRIPT_PATH` | TTS venv 解释器与 `run_tts.py` |

## 运行参数

| 方式 | 说明 |
|------|------|
| `./QwenChat` | 默认监听 UDP `0.0.0.0:1050` |
| `QWEN_UDP_PORT=1052 ./QwenChat` | 环境变量覆盖监听端口 |
| `QT_QPA_PLATFORM=offscreen ./QwenChat --selftest` | 无界面全链路自测（聊天 → 握手 → 分片重组 → ASR → LLM） |
| `./QwenChat --speak "文本"` | 直接走一遍 TTS 链路（切句 → 分段 → 合成 → 回传/播放） |

## 界面开关

- **深度思考**（默认不勾）：控制 LLM 是否带思考链，快速模式会给请求加 `reasoning_effort: "none"`。
- **语音播报**（默认开）：回复自动 TTS 并按句播放 / 回传；打字发送的回复同样播报。

## 子进程协议（stdin/stdout，每行一条 JSON）

| 桥接 | 输入 | 输出 |
|------|------|------|
| LLM | `{"prompt": "...", "think": false}` / `{"clear": true}` | `{"type":"token","text":"..."}`、`{"type":"done"}`、`{"type":"error","message":"..."}` |
| ASR | `{"path": "...", "sample_rate": 16000}` | `{"type":"done","text":"..."}` / `{"type":"error","message":"..."}` |
| TTS | `{"text": "...", "engine": "matcha"}` | `{"type":"done","wav":"...","pcm16k":"..."}` / `{"type":"error","message":"..."}` |

## 落盘目录

```
received/<日期>_<对端IP>_<对端端口>/
├── voice_<接收毫秒>.pcm   # 收齐分片后拼接的完整语音
└── photo_NNN.jpg          # 照片
```

## 备注

- 子进程必须优雅关闭（`closeWriteChannel` → `waitForFinished` → `kill`），否则退出时段错误。
- 析构期间桥接进程的残留输出可能触发回调，代码里用 `m_shuttingDown` 标志统一拦截。
