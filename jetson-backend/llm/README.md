# Jetson AGX Orin 本地 LLM 后端（智能眼镜）

- 硬件：Jetson AGX Orin 64GB（JetPack 5.1.2，MAXN 模式）
- 模型：Qwen3.6-35B-A3B UD-Q4_K_M（MoE，激活 3B，约 22GB）+ mmproj-F16 视觉编码器
- 服务：llama.cpp `llama-server`，OpenAI 兼容 API，端口 8080
- 实测性能：解码 **~30 tok/s**；图像+提示 prefill ~346 tok/s（TTFT ~0.9s）

## 模型下载

GGUF 权重不随仓库分发。使用 `scripts/download_model_v2.sh`（断点续传分片下载，推荐）
从 ModelScope 获取 `Qwen3.6-35B-A3B-UD-Q4_K_M.gguf` 与 `mmproj-F16.gguf`，放到 `models/`。

> 脚本内 `cd /mnt/ssd/jetson/Qwen_LLM/models` 为原部署机的绝对路径，迁移时请修改。

## 服务安装（systemd 常驻）

在仓库根目录执行：

```bash
sudo cp jetson-backend/llm/scripts/llama-server.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now llama-server
journalctl -u llama-server -f   # 查看日志
```

> `llama-server.service` 的 `ExecStart` 中也包含模型与 `llama-server` 可执行文件的绝对路径，
> 部署到其他机器前请一并检查修改。

## API 用法（agent / 眼镜端直接调用）

### 1. 文本聊天

```bash
curl http://127.0.0.1:8080/v1/chat/completions -H "Content-Type: application/json" -d '{
  "model": "qwen3.6-35b-a3b",
  "messages": [{"role": "user", "content": "你好"}],
  "stream": false
}'
```

### 2. 图像识别（base64 data URI）

```json
{
  "model": "qwen3.6-35b-a3b",
  "messages": [{"role": "user", "content": [
    {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,<BASE64>"}},
    {"type": "text", "text": "描述这张图片"}
  ]}],
  "stream": true
}
```

### 3. 硬件控制（工具调用）

```json
{
  "model": "qwen3.6-35b-a3b",
  "messages": [{"role": "user", "content": "把风扇开到最高档"}],
  "tools": [{
    "type": "function",
    "function": {
      "name": "set_fan_speed",
      "description": "Set the fan speed",
      "parameters": {
        "type": "object",
        "properties": {"level": {"type": "string", "enum": ["low", "medium", "high"]}},
        "required": ["level"]
      }
    }
  }]
}
```

响应中的 `choices[0].message.tool_calls` 即 agent 要执行的函数调用；执行完把结果以
`{"role": "tool", "tool_call_id": "...", "content": "执行结果"}` 追加回 messages 继续对话。

### 4. 流式输出（眼镜端推荐，降低首字感知延迟）

`"stream": true` 返回 SSE：`data: {"choices":[{"delta":{"content":"..."}}]}`

## 延迟优化提示

- **关闭思考（低延迟路径）**：请求体加 `"reasoning_effort": "none"`（OpenAI 兼容字段，已验证生效）。图像识别、硬件控制等简单任务建议开启；复杂多步 agent 任务保持默认思考模式，但 `max_tokens` 要给足（≥2048，思考会消耗 token 预算）
- 图像识别建议固定 prompt 模板（"一句话回答"），限制 `max_tokens`
- 眼镜端优先流式 + 边收边 TTS
- 若视觉任务需要空间位置理解（如"杯子在桌子左边"），服务端可加 `--image-min-tokens 1024`（略增 prefill 时间）

## 安全提示

服务监听 `0.0.0.0:8080` 且默认无鉴权。若局域网内有不可信设备，建议加 API key：在 unit 文件的 ExecStart 追加 `--api-key <你的密钥>`，客户端请求头加 `Authorization: Bearer <密钥>`。

## 常用运维

```bash
systemctl status llama-server        # 服务状态
journalctl -u llama-server -f        # 实时日志
curl http://127.0.0.1:8080/health    # 健康检查 {"status":"ok"}
```

调参位置：`jetson-backend/llm/scripts/llama-server.service`（上下文长度、并发数等）

## 目录结构

```
llm/
├── README.md
├── scripts/         # systemd unit、模型下载/测试脚本
└── models/          # （不入库）GGUF 权重 + mmproj，按上方说明下载
```

推理引擎 llama.cpp 为独立第三方项目，需另行编译出 `llama-server`
（在 Jetson 上通常使用 MAXN 模式并开启 CUDA 后端）。
