#!/system/bin/sh
# 高频网络接口探针 —— 每 200ms 采样一次，只在状态变化时输出
# 用途：验证 AI 对话期间眼镜的 PCM 流是否影响家用 WiFi 连接
#
# 关键判据：是否有新的 p2p 接口出现，以及 wlan0 是否保住 192.168.0.167

prev=""
n=0
while [ $n -lt 280 ]; do
  # 接口列表（排除 lo 和移动数据）
  cur=$(ip -o addr 2>/dev/null | grep -v inet6 | grep -vE "1: lo|rmnet" | awk '{print $2"=" $4}' | tr '\n' ' ')

  # wlan0 是否仍持有默认路由（table 1024）
  if ip route show table 1024 2>/dev/null | grep -q '^default'; then
    wr="YES"
  else
    wr="NO"
  fi

  # 是否存在任何 p2p 接口
  if ip -o addr 2>/dev/null | grep -qE 'p2p'; then
    pp="YES"
  else
    pp="NO"
  fi

  state="$cur|route:$wr|p2p:$pp"
  if [ "$state" != "$prev" ]; then
    echo "$(date +%H:%M:%S) $state"
    prev="$state"
  fi
  n=$((n+1))
  sleep 0.2
done
echo "PROBE_END samples=$n"
