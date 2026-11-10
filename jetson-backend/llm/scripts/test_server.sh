#!/bin/bash
# Smoke test: health / models / text chat / vision / tool calling
BASE=${BASE:-http://127.0.0.1:8080}
MODEL=${MODEL:-qwen3.6-35b-a3b}

echo "=== 1. health ==="
curl -s $BASE/health; echo

echo "=== 2. models ==="
curl -s $BASE/v1/models | python3 -c "import json,sys; print([m['id'] for m in json.load(sys.stdin)['data']])"

echo "=== 3. text chat (thinking off) ==="
curl -s $BASE/v1/chat/completions -H "Content-Type: application/json" -d "{
  \"model\":\"$MODEL\",
  \"messages\":[{\"role\":\"user\",\"content\":\"用一句话介绍你自己\"}],
  \"max_tokens\":256,
  \"reasoning_effort\":\"none\"
}" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['choices'][0]['message']['content'][:400])"

echo; echo "=== 4. vision ==="
python3 - <<'EOF'
from PIL import Image, ImageDraw
img = Image.new('RGB', (320, 240), (200, 220, 255))
d = ImageDraw.Draw(img)
d.rectangle([60, 60, 260, 180], fill=(255, 80, 80), outline=(0,0,0), width=3)
d.ellipse([120, 90, 200, 150], fill=(255, 255, 0))
d.text((100, 30), "TEST CARD", fill=(0,0,0))
img.save('/tmp/test_image.png')
print("test image saved")
EOF
IMG_B64=$(base64 -w0 /tmp/test_image.png)
python3 - "$IMG_B64" "$BASE" "$MODEL" <<'EOF'
import json, sys, urllib.request
img, base, model = sys.argv[1], sys.argv[2], sys.argv[3]
payload = {
  "model": model,
  "messages":[{"role":"user","content":[
      {"type":"image_url","image_url":{"url":"data:image/png;base64,"+img}},
      {"type":"text","text":"这张图片里有什么？一句话回答"}
  ]}],
  "max_tokens":128
}
req = urllib.request.Request(base+"/v1/chat/completions",
    data=json.dumps(payload).encode(), headers={"Content-Type":"application/json"})
r = json.load(urllib.request.urlopen(req, timeout=300))
print(r['choices'][0]['message']['content'][:400])
EOF

echo; echo "=== 5. tool calling (thinking off) ==="
curl -s $BASE/v1/chat/completions -H "Content-Type: application/json" -d "{
  \"model\":\"$MODEL\",
  \"messages\":[{\"role\":\"user\",\"content\":\"现在室温是26度，帮我把风扇开到最高档\"}],
  \"tools\":[{
    \"type\":\"function\",
    \"function\":{
      \"name\":\"set_fan_speed\",
      \"description\":\"Set the fan speed\",
      \"parameters\":{\"type\":\"object\",\"properties\":{\"level\":{\"type\":\"string\",\"enum\":[\"low\",\"medium\",\"high\"]}},\"required\":[\"level\"]}
    }
  }],
  \"max_tokens\":512,
  \"reasoning_effort\":\"none\"
}" | python3 -c "
import json,sys
d=json.load(sys.stdin)
m=d['choices'][0]['message']
print('content:', m.get('content'))
print('tool_calls:', json.dumps(m.get('tool_calls'), ensure_ascii=False))
"

echo; echo "=== 6. tool calling (thinking on, complex task) ==="
curl -s $BASE/v1/chat/completions -H "Content-Type: application/json" -d "{
  \"model\":\"$MODEL\",
  \"messages\":[{\"role\":\"user\",\"content\":\"室温26度，湿度80%，我要睡觉了。请先关灯，再把风扇调到合适档位，并提醒我湿度偏高\"}],
  \"tools\":[
    {\"type\":\"function\",\"function\":{\"name\":\"set_fan_speed\",\"description\":\"Set the fan speed\",\"parameters\":{\"type\":\"object\",\"properties\":{\"level\":{\"type\":\"string\",\"enum\":[\"low\",\"medium\",\"high\"]}},\"required\":[\"level\"]}}},
    {\"type\":\"function\",\"function\":{\"name\":\"set_light\",\"description\":\"Turn the light on or off\",\"parameters\":{\"type\":\"object\",\"properties\":{\"state\":{\"type\":\"string\",\"enum\":[\"on\",\"off\"]}},\"required\":[\"state\"]}}}
  ],
  \"max_tokens\":2048
}" | python3 -c "
import json,sys
d=json.load(sys.stdin)
m=d['choices'][0]['message']
print('content:', m.get('content'))
print('tool_calls:', json.dumps(m.get('tool_calls'), ensure_ascii=False))
"

echo; echo "=== all tests done ==="
