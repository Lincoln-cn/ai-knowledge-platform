#!/usr/bin/env python3
"""真栈验「直答型问答对命中即原样返回、不调模型」—— 0.9.9 发版前必须过的一条。

这条判据为什么必须真的跑一遍：
  `directAnswer` 是"跳过融合与模型调用"的强动作。它有三种可能的样子，而前两种
  在**结果上完全一样**：
    ① 真的走了直答（回答 = 问答对原文，没调模型）
    ② 没走直答，但是模型照着出处分段复述了那句话（看起来也对，且写了提示语）
    ③ 开关根本没生效（回答是模型正常生成的）
  ⇒ 判据只能是"回答与问答对答案**一字不差**"（模型不会逐字复述带书名号与时间的那一串），
    再加一条反证：把开关关掉，同一个问句必须**不再**一字不差。

用法（服务由用户点启动器起，脚本只发请求）：
    python scripts/qa_direct_check.py
    python scripts/qa_direct_check.py --base http://localhost:18088/api/v1 --kb <uuid>
"""

import json
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:18088/api/v1"
KB = "2925bfb1-823e-46a5-a6c4-b5511d41cd6e"  # 实体关系验证-1795

QUESTION = "机房的日常维护由谁负责"
ANSWER = "机房与网络的日常维护由运维科负责（依据《运维值班表》：工作日 08:30–18:00）。"


def call(path: str, token: str | None = None, method: str = "GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace") or "{}")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")[:400]


def login() -> str:
    st, r = call("/auth/login", method="POST", body={"username": "admin", "password": "admin"})
    if st != 200:
        raise SystemExit(f"登录失败：{st} {r}")
    data = r.get("data") or {}
    token = data.get("accessToken") or data.get("token")
    if not token:
        raise SystemExit(f"登录响应里没有令牌：{json.dumps(r, ensure_ascii=False)[:300]}")
    return token


def ask(token: str, conversation_id: str, question: str) -> tuple[str, dict | None]:
    """发一次聊天流，返回（答案正文, done 帧）。"""
    body = {"conversationId": conversation_id, "question": question}
    req = urllib.request.Request(BASE + "/conversations/chat", data=json.dumps(body).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "text/event-stream")
    req.add_header("Authorization", "Bearer " + token)
    try:
        response = urllib.request.urlopen(req, timeout=180)
    except urllib.error.HTTPError as e:
        raise SystemExit(f"聊天流被拒：HTTP {e.code} · {e.read().decode('utf-8', 'replace')[:400]}") from None
    text, done, event = "", None, None
    with response as r:
        for raw in r:
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if not line:
                event = None
                continue
            if line.startswith(":"):
                continue
            if line.startswith("event: "):
                event = line[len("event: "):]
                continue
            if line.startswith("data: "):
                try:
                    payload = json.loads(line[len("data: "):])
                except Exception:
                    continue
                if event == "delta":
                    text += payload.get("text", "")
                elif event == "done":
                    done = payload
                elif event == "error":
                    raise SystemExit(f"流里出现 error 帧：{json.dumps(payload, ensure_ascii=False)[:300]}")
    return text, done


def main() -> int:
    global BASE, KB
    args = sys.argv[1:]
    if "--base" in args:
        BASE = args[args.index("--base") + 1]
    if "--kb" in args:
        KB = args[args.index("--kb") + 1]

    token = login()
    print(f"已登录（{BASE}）· 知识库 {KB}")

    # ---------- 1. 建一条直答型问答对（同问句已存在就复用/改开关） ----------
    st, listing = call(f"/knowledge-bases/{KB}/qa-pairs?page=1&size=200", token)
    if st != 200:
        print(f"[FAIL] 读问答对列表失败：{st} {listing}")
        return 1
    existing = [p for p in ((listing.get("data") or {}).get("items") or []) if p.get("question") == QUESTION]

    pair_id = None
    if existing:
        pair_id = existing[0]["id"]
        print(f"复用已有问答对 {pair_id}（当前 directAnswer={existing[0].get('directAnswer')}）")
        st, _ = call(f"/qa-pairs/{pair_id}", token, "DELETE")
    st, created = call(
        f"/knowledge-bases/{KB}/qa-pairs",
        token,
        "POST",
        {"question": QUESTION, "answer": ANSWER, "classification": "INTERNAL", "directAnswer": True},
    )
    if st not in (200, 201):
        print(f"[FAIL] 建直答型问答对失败：{st} {created}")
        return 1
    pair = (created.get("data") or {})
    pair_id = pair.get("id")
    if not pair.get("directAnswer"):
        print(f"[FAIL] 建出来的问答对 directAnswer 不是 true：{json.dumps(pair, ensure_ascii=False)[:300]}")
        return 1
    print(f"已建直答型问答对 {pair_id}")

    st, conv = call("/conversations", token, "POST", {})
    if st not in (200, 201):
        print(f"[FAIL] 建会话失败：{st} {conv}")
        return 1
    conversation_id = (conv.get("data") or {}).get("id")
    print(f"会话 {conversation_id}")

    failed = []

    def check(ok: bool, name: str, detail: str = "") -> None:
        print(("  ✓ " if ok else "  ✗ ") + name + (f" —— {detail}" if detail else ""))
        if not ok:
            failed.append(name)

    # ---------- 2. 判据：回答与问答对答案一字不差 ----------
    text, done = ask(token, conversation_id, QUESTION)
    print(f"  回答 {len(text)} 字 · status={(done or {}).get('status')} · refs={len((done or {}).get('refs') or [])}")
    check(text.strip() == ANSWER.strip(), "★★ 直答型问答对命中时，回答就是问答对原文（一字不差）", f"首 60 字：{text[:60]!r}")
    check(len(text.strip()) > 0, "回答不是空的")

    # ---------- 3. 反证：关掉开关，同一问句必须不再一字不差 ----------
    st, patched = call(f"/qa-pairs/{pair_id}/direct-answer", token, "PATCH", {"directAnswer": False})
    if st != 200 or not (patched.get("data") or {}).get("directAnswer") is False:
        print(f"[FAIL] 关开关失败：{st} {patched}")
        return 1
    print("  已关掉 directAnswer（反证准备）")

    text2, done2 = ask(token, conversation_id, QUESTION)
    print(f"  反证回答 {len(text2)} 字 · status={(done2 or {}).get('status')}")
    check(text2.strip() != ANSWER.strip(), "★ 反证：关掉开关后回答不再一字不差（若仍相等，说明'相等'另有来源，判据无效）", f"首 60 字：{text2[:60]!r}")

    # ---------- 4. 清理 ----------
    call(f"/conversations/{conversation_id}", token, "DELETE")
    call(f"/qa-pairs/{pair_id}", token, "DELETE")
    print("已清理会话与问答对")

    if failed:
        print(f"\n[FAIL] {len(failed)} 条判据没过：{'；'.join(failed)}")
        return 1
    print("\n[PASS] 直答型问答对在真栈上：命中即原样返回，且关掉开关后行为真的变了")
    return 0


if __name__ == "__main__":
    sys.exit(main())
