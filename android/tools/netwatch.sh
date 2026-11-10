#!/system/bin/sh
# 把网络接口状态写入文件，供主机轮询读取。
# 用 nohup 脱离 adb shell 会话，避免会话断开导致采样中断。
OUT=/data/local/tmp/netwatch.txt
: > $OUT

n=0
while [ $n -lt 400 ]; do
  TS=$(date +%H:%M:%S)
  # 接口 + IP（排除 lo 和移动数据）
  IFACES=$(ip -o addr 2>/dev/null | grep -v inet6 | grep -vE "1: lo|rmnet" \
           | awk '{print $2"="$4}' | tr '\n' ' ')
  # p2p 接口的 operstate
  P2P0=$(cat /sys/class/net/p2p0/operstate 2>/dev/null)
  P2P1=$(cat /sys/class/net/p2p1/operstate 2>/dev/null)
  # wlan0 是否仍持有默认路由
  if ip route show table 1024 2>/dev/null | grep -q '^default'; then
    WR="default_wlan0=YES"
  else
    WR="default_wlan0=NO"
  fi

  echo "$TS | $IFACES|p2p0=$P2P0,p2p1=$P2P1|$WR" >> $OUT
  n=$((n+1))
  sleep 1
done
echo "WATCH_END n=$n" >> $OUT
