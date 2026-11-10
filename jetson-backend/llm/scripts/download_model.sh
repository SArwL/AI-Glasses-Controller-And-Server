#!/bin/bash
# Chunked parallel download of Qwen3.6-35B-A3B Q4_K_M + mmproj from ModelScope
set -u
cd /mnt/ssd/jetson/Qwen_LLM/models || exit 1
API="https://modelscope.cn/api/v1/models/unsloth/Qwen3.6-35B-A3B-GGUF/repo?Revision=master&FilePath="
GGUF="Qwen3.6-35B-A3B-UD-Q4_K_M.gguf"
MMPROJ="mmproj-F16.gguf"

# get exact size from Content-Range of a 1-byte range request
SIZE=$(curl -sL -D - -r 0-0 -o /dev/null "${API}${GGUF}" | grep -i '^content-range' | tr -d '\r' | awk -F/ '{print $2}' | tr -dc '0-9')
echo "total size: $SIZE"
[ -z "$SIZE" ] && { echo "FATAL: cannot determine size"; exit 1; }

N=12
CHUNK=$(( (SIZE + N - 1) / N ))
mkdir -p .parts

# download one chunk with retry loop
dl_chunk() {
  local i=$1 S=$2 E=$3
  for attempt in $(seq 1 25); do
    if curl -fsSL -r ${S}-${E} -o .parts/part.$i --retry 5 --retry-delay 8 "${API}${GGUF}"; then
      echo "part $i OK $(date +%H:%M:%S)"
      return 0
    fi
    echo "part $i attempt $attempt failed $(date +%H:%M:%S)"
    sleep 15
  done
  echo "part $i GAVE UP"
  return 1
}
export -f dl_chunk
export API GGUF SIZE CHUNK

# launch 12 chunks, max 4 concurrent
seq 0 11 | xargs -P 4 -I{} bash -c '
  i={}
  S=$(( i * CHUNK ))
  E=$(( (i+1) * CHUNK - 1 ))
  [ $E -ge $SIZE ] && E=$((SIZE-1))
  dl_chunk $i $S $E
' &
XARGS_PID=$!

# mmproj in parallel (single connection)
for attempt in $(seq 1 15); do
  curl -fsSL -o .parts/mmproj.part --retry 6 --retry-delay 8 "${API}${MMPROJ}" && { echo "mmproj OK $(date +%H:%M:%S)"; break; }
  echo "mmproj attempt $attempt failed"; sleep 15
done &

wait $XARGS_PID
CHUNK_RC=$?
wait

# assemble in order
echo "=== assembling ==="
cat $(for i in $(seq 0 11); do echo .parts/part.$i; done) > "${GGUF}.new" || exit 1
GOT=$(stat -c%s "${GGUF}.new")
echo "GOT: $GOT / expected $SIZE"
if [ "$GOT" = "$SIZE" ]; then
  mv "${GGUF}.new" "${GGUF}"
  [ -f .parts/mmproj.part ] && mv .parts/mmproj.part "${MMPROJ}"
  rm -rf .parts
  echo "DOWNLOAD COMPLETE, SIZE OK"
  exit 0
else
  echo "SIZE MISMATCH, keeping partial"
  exit 1
fi
