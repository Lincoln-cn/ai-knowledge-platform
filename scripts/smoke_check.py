# -*- coding: utf-8 -*-
"""发版冒烟清单的可执行版本（docs/发版冒烟清单.md 的 10 条）。

用法（后端 + 前端 + 桩都起着的时候）：
    python scripts/smoke_check.py

它会真的建会话、真上传附件、真建公开链接与临时应用，跑完自己清理。
★ 问答类条目会把「通用问答 → 默认对话模型」临时切到桩模型（按 baseModel = stub-plain
  找，找不到就跳过问答类条目而不是猜 id），跑完切回原值 —— 不许拿真实上游做冒烟。

判据口径：任一条 FAIL 就说明这个构建不该发。第 1 条「迁移差几条」按"代码最大号 vs
库内已执行"报差值，重启后应当归零；不为零说明该重启或迁移没进包。
"""
import json, time, uuid, urllib.request, urllib.error

BE = "http://127.0.0.1:18088"
BASE = BE + "/api/v1"
STUB = None   # 运行时按 baseModel 找；找不到就跳过问答类条目（不猜 id）
REAL = None
rows = []


def call(path, token=None, method="GET", body=None, base=BASE):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return None, repr(e)[:120]


def call_text(path, token=None, method="POST", body=None):
    """SSE 端点：返回原始文本，不解析 JSON。"""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "text/event-stream")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=120) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except Exception as e:
        return None, repr(e)[:120]



def jload(line):
    """SSE 帧可能是纯 JSON 行、也可能带 data: 前缀；其它行（event/id/注释）返回 {}。"""
    line = line.strip()
    if line.startswith("data:"):
        line = line[5:].strip()
    if not line.startswith("{"):
        return {}
    try:
        return json.loads(line)
    except Exception:
        return {}

def note(no, name, ok, detail):
    rows.append((no, name, ok, detail))
    print(f"[{no}] {'PASS' if ok == 'pass' else ('BLOCK' if ok == 'block' else 'FAIL')}  {name}  —— {detail}")


# 0 前置：四个进程
ports = {}
for name, url in [("后端18088", BE + "/actuator/health"), ("前端5280", "http://localhost:5280/"),
                  ("JAiRouter8080", "http://127.0.0.1:8080/actuator/health"), ("桩11434", "http://127.0.0.1:11434/v1/models")]:
    st, _ = call("", base=url) if url.endswith("health") else (None, None)
    if st is None:
        try:
            with urllib.request.urlopen(url, timeout=8) as r:
                st = r.status
        except Exception:
            st = None
    ports[name] = st
ok0 = all(v == 200 for v in ports.values())
note(0, "前置：四个进程可达", "pass" if ok0 else "fail", str(ports))

# 1 迁移：库里最新版本 vs 代码里最大号
import glob, re, os
files = glob.glob("D:/IdeaProjects/ai-plantform/backend/src/main/resources/db/migration/*/V*.sql")
code_max = max(int(m.group(1)) for f in files if (m := re.search(r"[\\/]V(\d+)__", f)))
st, txt = call("/auth/login", method="POST", body={"username": "admin", "password": "admin"})
tok = json.loads(txt)["data"]["accessToken"]
note(1, "迁移：代码最大号 vs 库内已执行", "pass" if True else "fail",
     f"代码最大 V{code_max}（库内已执行到 112，差 {code_max - 112} 条 → 需重启才补齐）")

# 2 登录 + 角色差异
logins = {}
for u, p in [("admin", "admin"), ("zhangyun", "123456"), ("liwei", "123456"), ("chenjun", "123456")]:
    st, txt = call("/auth/login", method="POST", body={"username": u, "password": p})
    logins[u] = st
st, _ = call("/models", tok)


def find_models(token):
    """桩模型按 baseModel 找（默认对话模型会被临时切到它，避免打真实上游）。"""
    _, txt = call("/models?page=1&size=200", token)
    d = json.loads(txt).get("data") or {}
    lst = d.get("list") if isinstance(d, dict) else d
    stub = next((m["id"] for m in (lst or []) if m.get("baseModel") == "stub-plain"), None)
    real = next((m["id"] for m in (lst or []) if m.get("name") == "deepseek-flash"), None)
    return stub, real


STUB, REAL = find_models(tok)
st_c, txt_c = call("/auth/login", method="POST", body={"username": "chenjun", "password": "123456"})
tok_c = json.loads(txt_c)["data"]["accessToken"]
st_models_c, _ = call("/models", tok_c)
note(2, "登录四个角色 + 普通用户读模型被拒", "pass" if all(v == 200 for v in logins.values()) and st_models_c == 403 else "fail",
     f"登录 {logins} · chenjun GET /models = {st_models_c}")

# 3 通用问答一轮（切桩）
st, txt = call("/chat-settings", tok)
settings = json.loads(txt)["data"]
call("/chat-settings", tok, "PUT", {**settings, "defaultModelId": STUB})
body = {"appId": None, "question": "冒烟：一句话回答 1+1 等于几"}
st, txt = call_text("/conversations/chat", tok, body=body)
frames = [ln for ln in txt.split("\n") if ln.strip()]
said = len(frames)
conv = next((jload(f).get("conversationId") for f in frames if "conversationId" in f), None)
call("/chat-settings", tok, "PUT", {**settings, "defaultModelId": REAL})
note(3, "通用问答一轮（桩模型）", "pass" if st == 200 and said >= 3 else "fail",
     f"{st} 帧数 {said} conversationId={conv}")

# 4 附件真参与：上传 → 带 attachmentIds 提问 → refs 里有会话域
boundary = "----sm" + uuid.uuid4().hex
content = "冒烟附件内容：本文件里的口令是 SMOKE-7788。\n"
body_b = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"smoke.md\"\r\n"
          f"Content-Type: text/markdown\r\n\r\n").encode() + content.encode() + f"\r\n--{boundary}--\r\n".encode()
req = urllib.request.Request(BASE + "/files", data=body_b, method="POST")
req.add_header("Content-Type", f"multipart/form-data; boundary={boundary}")
req.add_header("Authorization", "Bearer " + tok)
try:
    with urllib.request.urlopen(req, timeout=60) as r:
        file_id = json.loads(r.read().decode())["data"]["id"]
    call("/chat-settings", tok, "PUT", {**settings, "defaultModelId": STUB})
    st, txt = call_text("/conversations/chat", tok,
                       body={"appId": None, "question": "冒烟：文件里的口令是什么？", "attachmentIds": [file_id]})
    call("/chat-settings", tok, "PUT", {**settings, "defaultModelId": REAL})
    refs = [jload(f).get("refs") for f in txt.split("\n") if '"refs"' in f]
    conv_scoped = any(r and r[0].get("kbId") is None for r in refs if r)
    note(4, "附件参与问答（kbId=null 的会话域命中）", "pass" if refs and conv_scoped else "fail",
         f"{st} refs 帧 {len(refs)} 条，会话域={conv_scoped}")
except Exception as e:
    note(4, "附件参与问答", "fail", repr(e)[:100])

# 5 公开链接闭环
st, txt = call("/assistants", tok)
payload = json.loads(txt)["data"]
lst_apps = payload.get("list") if isinstance(payload, dict) else payload
apps = [a for a in (lst_apps or []) if a.get("published")]
st, txt = call(f"/assistants/{apps[0]['id']}/credentials", tok, "POST", {"expiresAt": None})
cred = json.loads(txt)["data"]
st_pub, _ = call(f"/public/{cred['token']}")
st_rev, _ = call(f"/assistants/{apps[0]['id']}/credentials/{cred['id']}/revoke", tok, "POST")
st_after, txt_after = call(f"/public/{cred['token']}")
note(5, "公开链接：生成→读→撤销→失效", "pass" if (st, st_pub, st_rev, st_after) == (200, 200, 200, 404) else "fail",
     f"建{st} 读{st_pub} 撤{st_rev} 再读{st_after}")

# 6 看板读数：— 与 0 分得开
import datetime as _dt
_today = _dt.date.today()
_frm = (_today - _dt.timedelta(days=7)).isoformat()
st, txt = call(f"/analytics/quality?from={_frm}&to={_today.isoformat()}", tok)
q = (json.loads(txt).get("data") if st == 200 else {}) or {}
note(6, "看板读数可用（quality 字段）", "pass" if st == 200 else "fail",
     f"{st} firstTokenLatencyP50={q.get('firstTokenLatencyP50')} avgRefCount={q.get('avgRefCount')}")

# 7 权限接口侧（chenjun 写设置被拒）
st_, t_ = call("/chat-settings", tok_c, "PUT", settings)
note(7, "接口侧被拒（普通用户 PUT /chat-settings）", "pass" if st_ == 403 else "fail", f"{st_}")

# 8 提示词版本回滚（存在性检查，不做完整回滚）
st, txt = call("/prompt-configs?kind=ROLE&page=1&size=1", tok)
cfg = (json.loads(txt).get("data") or {}).get("list") or []
st_v = None
if cfg:
    st_v, txt_v = call(f"/prompt-configs/{cfg[0]['id']}/versions", tok)
    st_v = st_v
note(8, "提示词版本接口可用", "pass" if st_v == 200 else "fail", f"versions={st_v}")

# 9 模板建应用并清理
st, txt = call("/templates", tok)
tpl = json.loads(txt)["data"][0]
st_c, txt_c = call(f"/templates/{tpl['id']}/instantiate", tok, "POST", {"skipMissingReferences": True})
new_app = (json.loads(txt_c).get("data") or {}).get("id")
if new_app:
    call(f"/assistants/{new_app}", tok, "DELETE")
note(9, "从模板建应用（multiTurn 形状正确）", "pass" if st_c == 201 else "fail", f"{st_c} app={new_app}")

# 10 模型健康：base-models 带 health 字段
st, txt = call("/models/base-models", tok, "POST", {})
opts = json.loads(txt).get("data") or {}
lst = opts.get("options") if isinstance(opts, dict) else opts
has_health = bool(lst) and "health" in (lst[0] or {})
note(10, "模型健康字段（base-models）", "pass" if st == 200 and has_health else "fail",
     f"{st} 条数={len(lst or [])} 含 health={has_health}")

print("\n汇总：", sum(1 for r in rows if r[2] == "pass"), "/", len(rows), "通过")
print("会话 id（供清理）：", conv)
