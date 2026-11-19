"""A7 接口侧的四个写入口补充读数（知识库改/启停/删 + 字典写；有反馈数据时连反馈办理一起）。

跑法：python scripts/a7_writes.py（需要真栈已在 18088 上起来、且关着验证码）

目的：台账 A7 的 🟡 理由是「未逐一：知识库删除/停用 · 反馈办理 · 基础数据四类字典的写入口」。
这里用**普通用户 chenjun** 逐个打一遍，看是不是 403。

三条纪律：
- 用**真实存在的 id**（不存在的 id 会先 404/400，证明不了权限）。
- 只在 @PreAuthorize 层被拦时才安全 —— 所以先确认 chenjun 没有对应权限点，再打 DELETE。
- 不改数据：全部期望 403；任何非 403 都原样报出来，不算通过。
"""

import json
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:18088/api/v1"


def call(path, method="GET", body=None, token=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def login(username, password):
    st, txt = call("/auth/login", "POST", {"username": username, "password": password})
    if st != 200:
        raise SystemExit(f"{username} 登录失败 {st}: {txt[:200]}")
    return json.loads(txt)["data"]["accessToken"]


admin = login("admin", "admin")
chenjun = login("chenjun", "123456")

# ① 合法 id：知识库取第一个
st, txt = call("/knowledge-bases?page=1&size=1", token=admin)
kb_id = json.loads(txt)["data"]["list"][0]["id"]
print(f"知识库 id = {kb_id}")

# ② 反馈：取第一个（可能没有数据）
feedback_id = None
st, txt = call("/feedbacks?page=1&size=1", token=admin)
if st == 200:
    rows = json.loads(txt)["data"].get("list") or []
    if rows:
        feedback_id = rows[0]["id"]
print(f"反馈 id = {feedback_id}")

cases = [
    ("知识库改（PUT /knowledge-bases/{id}）", "PUT", f"/knowledge-bases/{kb_id}", {
        "name": "权限探针-勿存", "category": "制度规范", "maxClassification": "INTERNAL",
        "embeddingModelId": None, "importType": "LOCAL", "description": "",
    }),
    ("知识库启停（PATCH /knowledge-bases/{id}/enabled）", "PATCH", f"/knowledge-bases/{kb_id}/enabled",
     {"enabled": True}),
    ("知识库删（DELETE /knowledge-bases/{id}）", "DELETE", f"/knowledge-bases/{kb_id}", None),
    ("字典写（POST /dictionaries）", "POST", "/dictionaries",
     {"kind": "DOC_TYPE", "code": "权限探针", "label": "权限探针", "sortNo": 99}),
]
if feedback_id:
    cases.insert(
        3,
        ("反馈办理（PATCH /feedbacks/{id}）", "PATCH", f"/feedbacks/{feedback_id}", {
            "status": "RESOLVED", "remark": "权限探针",
        }),
    )

print("\n角色 chenjun（普通用户）逐个打：")
ok = 0
for name, method, path, body in cases:
    st, txt = call(path, method, body, chenjun)
    hit = st in (401, 403)
    ok += 1 if hit else 0
    print(f"  [{'PASS' if hit else 'FAIL'}] {name} ⇒ {st}")
    if not hit:
        print(f"        响应：{txt[:200]}")

print(f"\n汇总：{ok} / {len(cases)} 被拒（期望全部 401/403）")
