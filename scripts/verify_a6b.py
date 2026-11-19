# -*- coding: utf-8 -*-
"""A6b：多轮指代 —— 10 轮之后，"它" 还指得回第 1 轮那个实体吗？

A6a（历史真的逐轮进请求）已由 prompt_tokens 递增证明过，A6b 验的是另一半：
**模型有没有用上那份历史**。这件事只能真模型验 —— 桩模型的回答是固定模板，
再怎么问都答不出"那个编号"。

用法：
    python scripts/verify_a6b.py            # 用当前默认对话模型
    python scripts/verify_a6b.py --model <模型id>

★ 检测到默认模型是桩（baseModel 以 stub 开头）时直接报 BLOCK 退出，不发请求 ——
  拿桩跑这条只会得到一个假的 FAIL。

判据：第 10 轮回答里出现第 1 轮那个实体（本脚本用世界上不存在的 `ZT-9931`，
      避免模型凭常识蒙对）。
"""
import json
import sys
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:18088/api/v1"
ENTITY = "ZT-9931"
FILLERS = [
    "再确认一下：差旅住宿标准是多少？",
    "用一句话说明什么是向量检索。",
    "把上一句改得更短。",
    "列两条公文写作的注意事项。",
    "什么是 RRF 融合？",
    "举一个不适合用全文检索的场景。",
    "简述切块过长会带来什么问题。",
    "用一句话解释准确度阈值的作用。",
    "还有别的要注意的吗？",
]


def call(path, token=None, method="GET", body=None, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if raw:
        req.add_header("Accept", "text/event-stream")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            txt = r.read().decode()
            return r.status, (txt if raw else json.loads(txt))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return None, repr(e)


def frame(line):
    line = line.strip()
    if line.startswith("data:"):
        line = line[5:].strip()
    if not line.startswith("{"):
        return {}
    try:
        return json.loads(line)
    except Exception:
        return {}


def ask(token, question, attachment_ids=None):
    body = {"appId": None, "question": question}
    if attachment_ids:
        body["attachmentIds"] = attachment_ids
    st, txt = call("/conversations/chat", token, "POST", body, raw=True)
    if st != 200:
        return st, "", None
    text, usage = [], None
    for line in txt.split("\n"):
        f = frame(line)
        if "text" in f:
            text.append(f["text"])
        if "tokens" in f:
            usage = f["tokens"]
        if "conversationId" in f:
            usage = usage or {}
            usage["conversationId"] = f["conversationId"]
    return st, "".join(text), usage


st, j = call("/auth/login", method="POST", body={"username": "admin", "password": "admin"})
tok = j["data"]["accessToken"]

# 模型体检：桩模型直接 BLOCK
st, j = call("/models?page=1&size=200", tok)
d = j.get("data") or {}
lst = d.get("list") if isinstance(d, dict) else d
by_id = {m["id"]: m for m in (lst or [])}
st, j = call("/chat-settings", tok)
settings = j["data"]
model_id = sys.argv[sys.argv.index("--model") + 1] if "--model" in sys.argv else settings.get("defaultModelId")
model = by_id.get(model_id) or {}
base = (model.get("baseModel") or "").lower()
if base.startswith("stub"):
    print(f"BLOCK  当前默认对话模型是桩（{model.get('name')} / {base}）—— 拿桩跑 A6b 只会得到假的 FAIL。")
    print("       先用 --model <真模型id> 或把「通用问答 → 默认对话模型」换成真模型再跑。")
    sys.exit(2)
print(f"用模型：{model.get('name')}（{base or '未知'}）")

# 附件里放一个世界上不存在的编号，避免模型凭常识蒙对
boundary = "----a6b" + uuid.uuid4().hex
payload = (f"项目编号是 {ENTITY}，校验位是 7。\n").encode()
body_b = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a6b.md\"\r\n"
          f"Content-Type: text/markdown\r\n\r\n").encode() + payload + f"\r\n--{boundary}--\r\n".encode()
req = urllib.request.Request(BASE + "/files", data=body_b, method="POST")
req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
req.add_header("Authorization", "Bearer " + tok)
with urllib.request.urlopen(req, timeout=60) as r:
    file_id = json.loads(r.read().decode())["data"]["id"]

st, answer, usage = ask(tok, "这份资料里的项目编号是多少？", [file_id])
print(f"第 1 轮  {st}  prompt_tokens={(usage or {}).get('prompt')}  答：{answer[:60]}")
conv = (usage or {}).get("conversationId")

for i, q in enumerate(FILLERS, start=2):
    st, answer, usage = ask(tok, q)
    print(f"第 {i} 轮  {st}  prompt_tokens={(usage or {}).get('prompt')}")
    if i == 10:
        break

last_q = "它的校验位是多少？"
st, answer, usage = ask(tok, last_q)
print(f'\n第 10 轮问「{last_q}」')
print(f"  答：{answer[:200]}")
print(f"  prompt_tokens={(usage or {}).get('prompt')}")

# 判定：第 10 轮回的是那个编号的校验位（7），且没有答成"不知道"
hit = ("7" in answer) and ("不知道" not in answer and "没有" not in answer)
print(f"\nA6b {'PASS' if hit else 'FAIL'}  —— 10 轮之后「它」{'仍指得回' if hit else '指不回'}第 1 轮那个编号")
print(f"会话 id（供清理）：{conv}")
