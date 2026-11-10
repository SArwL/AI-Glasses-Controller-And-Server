#!/usr/bin/env bash
# 网络状态快照 — 用于验证「手机能否同时保持 WiFi Direct 与局域网」
#
# 用法：  bash tools/netstate.sh <标签> [Jetson局域网IP]
# 示例：  bash tools/netstate.sh baseline
#         bash tools/netstate.sh dialogue 192.168.1.100
#
# 标签只用于日志分隔，任意字符串均可。

ADB="D:/Android/Android-SDK/platform-tools/adb.exe"
LABEL="${1:-snapshot}"
JETSON_IP="$2"

echo ""
echo "════════════════════════════════════════════════════"
echo "  快照 [$LABEL]"
echo "════════════════════════════════════════════════════"

echo ""
echo "【1】所有网络接口与地址"
"$ADB" shell ip -o addr 2>/dev/null | grep -v "inet6"

echo ""
echo "【2】默认路由 —— 全部策略路由表（★最关键）"
# 注意：Android 16 把路由拆进策略路由表，裸 ip route 读不到，
# 必须 show table all 才能看到 wlan0 那条 default via 192.168.0.1 table 1024
"$ADB" shell ip route show table all 2>/dev/null | grep -E "default|192\.168|49\." | grep -v inet6

echo ""
echo "【3】all-ifaces P2P 状态"
"$ADB" shell "dumpsys wifi | grep -iE 'p2p|wifi.direct' | head -10" 2>/dev/null

# 如果给了 Jetson IP，测一下通不通
if [ -n "$JETSON_IP" ]; then
  echo ""
  echo "【4】ping Jetson ($JETSON_IP) — 3 个包"
  "$ADB" shell "ping -c 3 -W 1 $JETSON_IP" 2>/dev/null | tail -4
fi

echo ""
echo "【5】WiFi 状态摘要"
"$ADB" shell "dumpsys wifi" 2>/dev/null | grep -iE "mWifiInfo|Supplicant state|SSID:" | head -5

echo ""
echo "════════════════════════════════════════════════════"
echo ""
