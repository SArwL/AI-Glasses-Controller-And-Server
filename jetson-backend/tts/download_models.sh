#!/bin/bash
# 下载/补齐 TTS 模型到 SSD（走 hf-mirror，缓存也放 SSD）
# 用法：download_models.sh [all|matcha|kokoro]
set -u
BASE=/mnt/ssd/jetson/tts
export HF_ENDPOINT=https://hf-mirror.com
export HF_HOME=$BASE/.hf-cache
export GIT_LFS_SKIP_SMUDGE=1   # git 克隆时跳过 LFS 大文件，稍后单独 curl
PY=/mnt/ssd/jetson/asr/venv/bin/python
STEP=${1:-all}
mkdir -p "$BASE/models"

fetch_matcha() {
    echo "[$(date +%H:%M:%S)] Matcha-zh 声学模型 + hifigan_v2 声码器（约 90MB）"
    $PY - <<'PYEOF'
from huggingface_hub import snapshot_download
snapshot_download("csukuangfj/matcha-icefall-zh-baker",
                  local_dir="/mnt/ssd/jetson/tts/models/matcha-icefall-zh-baker",
                  max_workers=4)
snapshot_download("csukuangfj/sherpa-onnx-hifigan",
                  local_dir="/mnt/ssd/jetson/tts/models/sherpa-onnx-hifigan",
                  allow_patterns=["hifigan_v2.onnx"],
                  max_workers=2)
print("MATCHA_DONE")
PYEOF
}

fetch_kokoro() {
    # 该仓库有 348 个 espeak-ng-data 小文件：逐文件下载会触发镜像 429 限流，
    # 改用 git 克隆一次性拿全部小文件，仅对少量 LFS 大文件单独 curl（可断点续传）
    local REPO=csukuangfj/kokoro-multi-lang-v1_1
    local DEST=$BASE/models/kokoro-multi-lang-v1_1
    echo "[$(date +%H:%M:%S)] Kokoro v1.1-zh：git 克隆小文件 + curl 拉 LFS 大文件"
    if [ ! -d "$DEST/.git" ]; then
        rm -rf "$DEST"
        git clone --depth 1 "https://hf-mirror.com/$REPO" "$DEST" || return 1
    fi
    cd "$DEST" || return 1
    for f in $(grep -rl "^version https://git-lfs" . --exclude-dir=.git | sed 's|^\./||'); do
        echo "  LFS -> $f"
        # ⚠️ 必须先删指针文件：否则 curl -C - 会从指针尾部续传，
        #    指针文本残留在文件头导致模型文件损坏（Protobuf parsing failed）
        rm -f "$f"
        curl -fL --retry 6 --retry-delay 5 -C - -o "$f" \
            "https://hf-mirror.com/$REPO/resolve/main/$f" || return 1
    done
    cd - >/dev/null
    echo "KOKORO_DONE"
}

case "$STEP" in
    all)    fetch_matcha && fetch_kokoro ;;
    matcha) fetch_matcha ;;
    kokoro) fetch_kokoro ;;
    *)      echo "用法: $0 [all|matcha|kokoro]"; exit 2 ;;
esac

echo "[$(date +%H:%M:%S)] 汇总"
du -sh "$BASE/models"/* 2>/dev/null
echo "ALL_DONE"
