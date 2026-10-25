#!/bin/bash
# 网络邻居 —— 模拟器安装+启动+截屏 一条龙
# 用法: bash tools/test-on-emulator.sh [截图保存目录]
set -e
export MSYS_NO_PATHCONV=1
ADB="I:\\Android\\Sdk\\platform-tools\\adb.exe"
APK='D:\\tv\\smb\\app\\build\\outputs\\apk\\release\\app-release.apk'
OUT="${1:-C:\\Users\\admin\\AppData\\Local\\Temp\\nn-shots}"
OUT_UNIX="${1:-/c/Users/admin/AppData/Local/Temp/nn-shots}"
mkdir -p "$OUT_UNIX"

"$ADB" devices | grep -q emulator || { echo "没有在跑的模拟器"; exit 1; }
"$ADB" install -r "$APK"
"$ADB" shell am force-stop com.tv.neighbor 2>/dev/null || true
"$ADB" shell am start -n com.tv.neighbor/.MainActivity
sleep 3
SHOT="$OUT\\nn-$(date +%H%M%S).png"
"$ADB" shell screencap -p /sdcard/nn-shot.png
"$ADB" pull /sdcard/nn-shot.png "$SHOT" >/dev/null
"$ADB" shell rm /sdcard/nn-shot.png
echo "截图: ${SHOT}"
