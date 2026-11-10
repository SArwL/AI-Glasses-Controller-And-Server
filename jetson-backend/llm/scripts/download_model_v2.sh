#!/bin/bash
# v2: resume-aware chunked download. Each retry continues from local bytes.
set -u
cd /mnt/ssd/jetson/Qwen_LLM/models || exit 1
API="https://modelscope.cn/api/v1/models/unsloth/Qwen3.6-35B-A3B-GGUF/repo?Revision=master&FilePath="
GGUF="Qwen3.6-35B-A3B-UD-Q4_K_M.gguf"
MMPROJ="mmproj-F16.gguf"

SIZE=$(curl -sL -D - -r 0-0 -o /dev/null "${API}${GGUF}" | grep -i '^content-range' | tr -d '\r' | awk -F/ '{print $2}' | tr -dc '0-9')
[ -z "$SIZE" ] && SIZE=22134528992
MPSIZE=$(curl -sL -D - -r 0-0 -o /dev/null "${API}${MMPROJ}" | grep -i '^content-range' | tr -d '\r' | awk -F/ '{print $2}' | tr -dc '0-9')
echo "gguf size: $SIZE, mmproj size: $MPSIZE"
N=12
CHUNK=$(( (SIZE + N - 1) / N ))
mkdir -p .parts

dl_range() {  # $1=url $2=start $3=end $4=outfile
  curl -fsSL -r $2-$3 -o "$4" --retry 4 --retry-delay 6 "$1" 2>/dev/null
}

dl_chunk() {  # $1=index $2=start $3=end
  local i=$1 S=$2 E=$3 TARGET=$(( E - S + 1 ))
  local f=".parts/part.$i"
  for attempt in $(seq 1 200); do
    local CUR=$(stat -c%s "$f" 2>/dev/null || echo 0)
    [ "$CUR" -ge "$TARGET" ] && { echo "part $i done $(date +%H:%M:%S)"; return 0; }
    local RS=$(( S + CUR ))
    if dl_range "${API}${GGUF}" $RS $E "$f.tmp"; then
      cat "$f.tmp" >> "$f" && rm -f "$f.tmp"
    else
      rm -f "$f.tmp"; sleep 10
    fi
  done
  echo "part $i GAVE UP"; return 1
}
export -f dl_range dl_chunk
export API GGUF SIZE CHUNK

seq 0 11 | xargs -P 4 -I{} bash -c '
  i={}; S=$(( i * CHUNK )); E=$(( (i+1) * CHUNK - 1 ))
  [ $E -ge $SIZE ] && E=$((SIZE-1))
  dl_chunk $i $S $E
' &
XARGS_PID=$!

# mmproj: single stream with resume
( for attempt in $(seq 1 200); do
    CUR=$(stat -c%s .parts/mmproj.part 2>/dev/null || echo 0)
    [ -n "$MPSIZE" ] && [ "$CUR" -ge "$MPSIZE" ] && { echo "mmproj done $(date +%H:%M:%S)"; exit 0; }
    if dl_range "${API}${MMPROJ}" $CUR 999999999 .parts/mmproj.part.tmp; then
      cat .parts/mmproj.part.tmp >> .parts/mmproj.part && rm -f .parts/mmproj.part.tmp
    else
      rm -f .parts/mmproj.part.tmp; sleep 10
    fi
  done ) &

wait $XARGS_PID || echo "some chunks failed"
wait

echo "=== assembling ==="
cat $(for i in $(seq 0 11); do echo .parts/part.$i; done) > "${GGUF}.new"
GOT=$(stat -c%s "${GGUF}.new")
echo "GOT: $GOT / expected $SIZE"
if [ "$GOT" = "$SIZE" ]; then
  mv "${GGUF}.new" "${GGUF}"
  [ -f .parts/mmproj.part ] && mv .parts/mmproj.part "${MMPROJ}"
  rm -rf .parts
  echo "DOWNLOAD COMPLETE, SIZE OK"
else
  echo "SIZE MISMATCH"; exit 1
fi
