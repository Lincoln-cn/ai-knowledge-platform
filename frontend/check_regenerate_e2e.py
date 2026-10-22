"""S3 的真栈端到端闸门 —— 「重新生成」必须**接住** `targetMessageId`，不许静默当新的一轮。

━━━ 为什么必须有一条**真栈**闸门 ━━━
`ROADMAP §2.1` 的 S3 一行里有「**重新生成**」。同一条链上有三格，其中两格早就齐了：
契约（`ChatMode` + `targetMessageId`）· 前端（`MessageList.vue` 的按钮 + `useChatSession` 透传）。
**后端那一格是本轮才补的**（PR #156）。

★★ 而在后端补上之前，这条链是**静默坏着的**，且它的形状特别值得记：
  前端一直在传 `mode: 'REGENERATE'` + `targetMessageId`，而后端请求体里**没有这两个字段**；
  本仓没有覆写 Jackson 的 `fail-on-unknown-properties`，Spring Boot 的默认值是 `false`
  （**忽略未知字段**）⇒ 那两个字段被静默丢弃 ⇒ 后端按 `NEW` 处理 ⇒
  **点「重新生成」会新增一问一答**，而界面上它看起来完全正常。
  ★ 这正是本仓头号病 `silent-failure`，而它**没有任何读数**能发现 ——
    除非有一条闸门去问"你接住那个 id 了吗"。

━━━ 判据四条（缺一条都能骗过）━━━
① 重新生成的响应里 `meta.messageId` **等于**传进去的 `targetMessageId`
  （⇒ 后端真的接住了它，而不是新起一条）；
② ★★ **给一个不存在的 `targetMessageId` ⇒ 必须 4xx** —— 这条是**针对上面那个 silent-failure
   的精确判据**：忽略 `targetMessageId` 的实现会**照样 200** 并新增一条消息；
③ 答案**带引用**（重新生成要重新检索；0 条意味着检索那一步没跑）；
④ ★ **对照**：普通提问（不带 mode）必须拿到一个**不同于** target 的消息 id
  —— 没有它，一个"永远返回同一个 id"的坏实现也能让 ① 绿。

★ **本闸门不数消息条数**（`GET /conversations` 尚未实现 ⇒ 拿不到会话 id 去读历史）。
  "消息总数不变"那条由 JUnit 覆盖：`RegenerateTurnTest#regenerateOverwritesInsteadOfAddingAMessagePair`
  （它在真 PG 上直接数 `cv_message`）。两处分工写在这里，免得下一个人以为本闸门漏了。

用法：
    python check_regenerate_e2e.py                      # 默认 http://localhost:5280
    python check_regenerate_e2e.py http://127.0.0.1:5280
退出码：0 = 全绿；1 = 有红。
"""

from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"

USER = "admin"
PASSWORD = "admin"
# ★ 问句必须与库里的实际内容对得上：问一个库里没有的词，"0 条引用"会被误读成"检索坏了"
QUESTION = "报销标准"

problems: list[str] = []


def check(ok: bool, message: str) -> None:
    if ok:
        print(f"  ✓ {message}")
    else:
        print(f"  ✗ {message}")
        problems.append(message)


def call(method: str, path: str, token: str | None = None, body: dict | None = None) -> tuple[int, str]:
    """调后端 REST（走 dev server 代理 ⇒ 与页面同一条路）。"""
    headers: dict[str, str] = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        API + path,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        method=method,
        headers=headers,
    )
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except Exception as e:  # noqa: BLE001
        return -1, str(e)


def sse_frames(payload: str) -> list[dict]:
    """SSE 逐行解析。

    ★ 帧的形状是**按内容命名的键**（实测）：`{"messageId","traceId","model"}` ·
      `{"refs":[…]}` · `{"text":…}` · `{"status","messageId","tokens","durationMs"}` ——
      **没有** `event`/`type` 字段。★ 按 `event` 找永远不命中，而症状与"产品不带引用"一模一样
      （S2 那条闸门踩过这个坑）。
    """
    frames: list[dict] = []
    for line in payload.splitlines():
        line = line.strip()
        if not line.startswith("data:"):
            continue
        try:
            frames.append(json.loads(line[len("data:") :].strip()))
        except json.JSONDecodeError:
            continue
    return frames


def meta_of(frames: list[dict]) -> dict:
    """第一帧是 meta（`messageId` + `traceId` + `model`，没有 `refs`/`text`）。"""
    for frame in frames:
        if "messageId" in frame and "refs" not in frame and "text" not in frame:
            return frame
    return {}


def refs_of(frames: list[dict]) -> list:
    for frame in frames:
        if "refs" in frame and isinstance(frame["refs"], list):
            return frame["refs"]
    return []


def main() -> int:
    print("== S3 真栈闸门：重新生成必须接住 targetMessageId ==")

    status, payload = call("POST", "/auth/login", body={"username": USER, "password": PASSWORD})
    if status != 200:
        raise SystemExit(f"登录失败（{status}）：{payload[:200]}")
    token = json.loads(payload)["data"]["accessToken"]
    print("  已登录（admin）")

    # ── 夹具：一个绑了「有文档的知识库」的应用（复用 S2 闸门的选法）──
    status, payload = call("GET", "/models?size=50", token)
    model_id = None
    for row in (json.loads(payload).get("data") or {}).get("list") or []:
        if row.get("kind") == "CHAT" and row.get("status") == "LOADED":
            model_id = row["id"]
            break
    if model_id is None:
        raise SystemExit("没有已加载的对话模型：真栈夹具要求先加载一个 CHAT 模型")

    status, payload = call("GET", "/knowledge-bases?size=50", token)
    kb_id = None
    for row in (json.loads(payload).get("data") or {}).get("list") or []:
        if (row.get("documentCount") or 0) > 0:
            kb_id = row["id"]
            break
    if kb_id is None:
        raise SystemExit("没有含文档的知识库：S3 要求有可引用的内容")

    status, payload = call(
        "POST",
        "/assistants",
        token,
        {
            "name": f"S3 重新生成闸门-{int(time.time())}",
            "icon": "📄",
            "description": "S3 重新生成闸门用的临时应用",
            "type": "SIMPLE",
            "modelId": model_id,
            "kbIds": [kb_id],
            "rolePrompt": "你是知识助理，请基于参考资料回答。",
            "promptIds": [],
            "retrieval": {"mode": "HYBRID", "topK": 5, "threshold": 0},
            "multiTurn": True,
            "thinkingEnabled": False,
            "prologue": "",
            "quickQuestions": [],
        },
    )
    if status not in (200, 201):
        raise SystemExit(f"建应用失败（{status}）：{payload[:300]}")
    app_id = json.loads(payload)["data"]["id"]
    print(f"  已建应用 {app_id}")

    # ── 先普通问一轮，拿到一条真实的助手消息 id ──
    status, payload = call("POST", "/conversations/chat", token, {"appId": app_id, "question": QUESTION})
    check(status == 200, f"前置：普通提问 ⇒ 200 + SSE（实得 {status}）")
    if status != 200:
        print(f"  [诊断] {payload[:300]}")
        return 1
    first_meta = meta_of(sse_frames(payload))
    answer_id = first_meta.get("messageId")
    check(bool(answer_id), f"前置：拿到第一轮的助手消息 id（实得 {answer_id}）")
    if not answer_id:
        print(f"  [诊断] meta 帧：{first_meta}")
        return 1

    # ── ① 重新生成：必须覆盖同一条 ──
    status, payload = call(
        "POST",
        "/conversations/chat",
        token,
        {"appId": app_id, "question": "", "mode": "REGENERATE", "targetMessageId": answer_id},
    )
    check(status == 200, f"① 重新生成 ⇒ 200 + SSE（实得 {status}）")
    if status != 200:
        print(f"  [诊断] {payload[:300]}")
        return 1

    again = sse_frames(payload)
    again_meta = meta_of(again)
    check(
        again_meta.get("messageId") == answer_id,
        f"① 重新生成必须**覆盖同一条**消息（期望 {answer_id}，实得 {again_meta.get('messageId')}）"
        f" —— 不同就说明它新起了一条（即『点重新生成却新增一问一答』）",
    )
    check(len(refs_of(again)) > 0, f"③ 重新生成要重新检索 ⇒ 必须带引用（实得 {len(refs_of(again))} 条）")

    # ── ② ★★ 精确判据：不存在的 targetMessageId ⇒ 必须 4xx（不许静默新增一轮）──
    status, payload = call(
        "POST",
        "/conversations/chat",
        token,
        {
            "appId": app_id,
            "question": QUESTION,
            "mode": "REGENERATE",
            "targetMessageId": "00000000-0000-4000-8000-0000000000ff",
        },
    )
    check(
        status != 200,
        f"② ★★ 不存在的 targetMessageId ⇒ 必须 4xx（实得 {status}）"
        f" —— 若这里 200，说明后端**忽略了** targetMessageId，那正是修好之前那个 silent-failure 的形状",
    )

    # ── ④ 对照：不带 mode 必须拿到一个**不同**的消息 id ──
    status, payload = call("POST", "/conversations/chat", token, {"appId": app_id, "question": QUESTION})
    check(status == 200, f"④ 对照：普通提问 ⇒ 200（实得 {status}）")
    if status == 200:
        fresh_id = meta_of(sse_frames(payload)).get("messageId")
        check(
            bool(fresh_id) and fresh_id != answer_id,
            f"④ ★ 对照：NEW 必须拿到**新的**消息 id（期望 ≠ {answer_id}，实得 {fresh_id}）"
            f" —— 没有这一条，一个『永远返回同一个 id』的坏实现也能让 ① 绿",
        )

    if problems:
        print(f"\n结果：红 —— {len(problems)} 条")
        for item in problems:
            print(f"  [FAIL] {item}")
        return 1
    print("\n结果：绿 —— S3「重新生成」四条判据全部成立（覆盖同一条 · 坏 target 4xx · 带引用 · NEW 仍新起）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
