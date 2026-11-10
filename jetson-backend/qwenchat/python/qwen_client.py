#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Qwen 聊天桥接进程
=================
C++ 侧（QwenChat）通过 QProcess 启动本脚本，双方用 stdin/stdout 的
JSON-lines 协议通信；本脚本负责调用 llama-server 的 OpenAI 兼容接口。
仅使用 Python 3 标准库，无需安装任何第三方依赖。

输入协议（stdin，每行一条 JSON）：
  {"prompt": "用户说的话", "think": true/false}
                             发送一条用户消息并流式生成回复；
                             think=false 时关闭思考链（低延迟路径）
  {"clear": true}            清空对话历史（保留系统提示词）

输出协议（stdout，每行一条 JSON，立即 flush）：
  {"type": "token", "text": "..."}    生成的正文片段（思考内容已过滤）
  {"type": "done"}                    本条回复生成结束
  {"type": "error", "message": "..."} 出错（服务不可达等）
"""
import json
import sys
import urllib.request

SERVER_URL = "http://127.0.0.1:8080/v1/chat/completions"
MODEL = "qwen3.6-35b-a3b"
SYSTEM_PROMPT = (
    "你是「AI 智能眼镜」的语音助手，你的回答会被朗读给用户听。请遵守：\n"
    "1. 口语化：像面对面聊天一样自然，不要书面腔；\n"
    "2. 简洁：一般一两句话讲完，只有用户明确要求时才展开；\n"
    "3. 禁止任何结构化格式：不要编号列表（1. 2. 3.）、不要标题（#、####）、"
    "不要项目符号（-、*）、不要表格、不要表情符号；\n"
    "4. 需要列举多个要点时，用口语把它们融进一句话，"
    "例如「主要有三点，第一…第二…第三…」；\n"
    "5. 直接回答，不要客套话，也不要解释自己的回答方式。\n"
    "\n"
    "风格示例：\n"
    "用户：今天天气怎么样？\n"
    "助手：今天多云，二十四度左右，挺舒服的，出门带件薄外套就行。\n"
    "用户：怎么重启路由器？\n"
    "助手：先把路由器电源拔掉，等十秒再插回去，等指示灯稳定下来就重启好了。\n"
    "用户：有哪些水果对健康比较好？\n"
    "助手：苹果、蓝莓和橙子都不错，苹果补纤维，蓝莓抗氧化，橙子的维生素 C 也多。\n"
)
# 思考型模型：思考会消耗 token 预算，max_tokens 要给足
MAX_TOKENS = 2048
TEMPERATURE = 0.7


def send(obj):
    sys.stdout.write(json.dumps(obj, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def generate(messages, think):
    """流式生成回复，逐段把正文发到 stdout，返回完整回复文本。"""
    payload = {
        "model": MODEL,
        "messages": messages,
        "stream": True,
        "max_tokens": MAX_TOKENS,
        "temperature": TEMPERATURE,
    }
    if not think:
        # 关闭思考链（OpenAI 兼容字段，README 已验证生效）
        payload["reasoning_effort"] = "none"
    req = urllib.request.Request(
        SERVER_URL,
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    full = []
    with urllib.request.urlopen(req, timeout=600) as resp:
        for raw in resp:
            line = raw.decode("utf-8").strip()
            if not line.startswith("data:"):
                continue
            data = line[5:].strip()
            if data == "[DONE]":
                break
            try:
                chunk = json.loads(data)
            except json.JSONDecodeError:
                continue
            choices = chunk.get("choices") or []
            if not choices:
                continue
            delta = choices[0].get("delta") or {}
            # 思考型模型会先输出 reasoning_content，这里过滤掉，只显示正文
            content = delta.get("content")
            if content:
                full.append(content)
                send({"type": "token", "text": content})
    return "".join(full)


def main():
    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            cmd = json.loads(line)
        except json.JSONDecodeError:
            continue

        if cmd.get("clear"):
            messages = [{"role": "system", "content": SYSTEM_PROMPT}]
            send({"type": "done"})
            continue

        prompt = (cmd.get("prompt") or "").strip()
        if not prompt:
            continue

        messages.append({"role": "user", "content": prompt})
        try:
            reply = generate(messages, bool(cmd.get("think", True)))
            if reply:
                # 记录历史，保证多轮对话上下文
                messages.append({"role": "assistant", "content": reply})
            else:
                messages.pop()  # 模型无输出，移除本次用户消息避免上下文错乱
            send({"type": "done"})
        except Exception as e:  # 网络错误、服务重启等
            messages.pop()
            send({"type": "error", "message": "模型调用失败: {}".format(e)})


if __name__ == "__main__":
    main()
