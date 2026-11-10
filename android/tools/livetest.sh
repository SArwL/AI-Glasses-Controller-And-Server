#!/system/bin/sh
# AI 对话期间的端到端连通性探针
# 每秒记录：网络接口状态 + 对 Jetson 的 HTTP 可达性 + PCM 流事件
# 输出写入 /data/local/tmp/livetest.txt，adb 掉线也不会丢

JETSON=192.168.0.194
PORT=9999
OUT=/data/local/tmp/livetest.txt

: > $OUT
echo "=== 开始 $(date +%H:%M:%S)  目标=$JETSON:$PORT ===" >> $OUT

# 后台记录 PCM 事件
logcat -c 2>/dev/null
( logcat -s AiDialogueManager:I '*:S' | while read -r line; do
    case "$line" in
      *"开始说话"*|*"说话完毕"*|*"拼接完成"*) echo "PCM $(date +%H:%M:%S) | $line" >> $OUT ;;
    esac
  done ) &

n=0
while [ $n -lt 150 ]; do
  TS=$(date +%H:%M:%S)

  # 网络接口（是否有 p2p 接口拿到 IP）
  IFACES=$(ip -o addr 2>/dev/null | grep -v inet6 | grep -vE "1: lo|rmnet" \
           | awk '{print $2"="$4}' | tr '\n' ' ')

  # 默认路由是否仍在 wlan0
  if ip route show table 1024 2>/dev/null | grep -q '^default'; then
    WR="wlan0_route=YES"
  else
    WR="wlan0_route=NO"
  fi

  # 对 Jetson 的 HTTP 可达性
  HTTP=$(curl -s -m 2 -o /dev/null -w '%{http_code}' http://$JETSON:$PORT/ 2>/dev/null)
  [ -z "$HTTP" ] && HTTP="ERR"

  echo "NET $TS | $IFACES|$WR|http=$HTTP" >> $OUT
  n=$((n+1))
  sleep 1
done

echo "=== 结束 $(date +%H:%M:%S) samples=$n ===" >> $OUT
