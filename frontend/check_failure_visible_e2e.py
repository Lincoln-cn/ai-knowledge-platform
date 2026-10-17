"""A3 的**界面侧**读数 —— 「失败可见（可读原因 + 可重试）」在真实界面上的那一半。

━━━ 为什么必须有一条浏览器闸门 ━━━
`ROADMAP §2.2` 的 A3 是「**失败可见**（人为构造失败，界面给出**可读原因**与**重试**）」。

★ 服务层那一半已经有读数（`ChatStreamEventRecordingTest#anUpstreamFailureKeepsItsOwnDetailInThePersistedReason`
断言上游 502 ⇒ `FAILED` + 原话；`#aFailedWriteEndsTheTurnWithErrorThenDoneAndPersistsFailed`
断言 `failHint` = 「网络或服务异常，可重试」）。
★ 但"**界面**给出可读原因与**重试**"只有浏览器能验：落库里有 `failReason`、而界面什么都不显示，
  读数上完全一样 —— 用户看到的是"转圈转到死"。

━━━ ★★ 怎么"人为构造失败"：用桩自己的注入口，而不是改代码 ━━━
`backend/tools/openai_stub.py` 原生支持（`POST /__stub/config {"fail_rate": 1.0}` ⇒ 100% 500，
它的 docstring 逐字写着「`--fail-rate 0.3` 注入 30% 的 500，用来测降级/重试」）。
⇒ 本闸门**只调它的控制接口**，不碰产品代码、不 mock 后端。

━━━ 判据四条 ━━━
① ★ 失败之后，界面**给出了可读原因**（正文里出现 `failReason` 的那句话，或明确含"失败/错误/异常"之一）；
② ★★ **可重试**：界面上有一个**能点的重试/重新生成**入口 —— 只给原因不给入口，用户只能刷新；
③ ★ **对照**：把 `fail_rate` 关回去之后，**同一条链能成功** ——
   没有这一条，一个"永远失败"的实现也能让 ①② 绿；
④ 全程无未处理页面异常。

用法：
    python check_failure_visible_e2e.py                      # 默认 http://localhost:5280
    python check_failure_visible_e2e.py http://127.0.0.1:5280
退出码：0 = 全绿；1 = 有红。
"""

from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"
STUB = "http://localhost:11434"

USER = "admin"
PASSWORD = "admin"
QUESTION = "报销标准"

problems: list[str] = []


def check(ok: bool, message: str) -> None:
    if ok:
        print(f"  ✓ {message}")
    else:
        print(f"  ✗ {message}")
        problems.append(message)


def call(method: str, path: str, token: str | None = None, body: dict | None = None) -> tuple[int, str]:
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


def stub_config(payload: dict) -> bool:
    """改桩的行为（★ 这是它**设计好的**注入口，见 `openai_stub.py` 的 docstring）。"""
    req = urllib.request.Request(
        STUB + "/__stub/config",
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status == 200
    except Exception:  # noqa: BLE001
        return False


def seed_app() -> str:
    status, payload = call("POST", "/auth/login", body={"username": USER, "password": PASSWORD})
    if status != 200:
        raise SystemExit(f"登录失败（{status}）：{payload[:200]}")
    token = json.loads(payload)["data"]["accessToken"]

    status, payload = call("GET", "/models?size=50", token)
    model_id = None
    for row in (json.loads(payload).get("data") or {}).get("list") or []:
        if row.get("kind") == "CHAT" and row.get("status") == "LOADED":
            model_id = row["id"]
            break
    if model_id is None:
        raise SystemExit("没有已加载的对话模型")

    status, payload = call("GET", "/knowledge-bases?size=50", token)
    kb_id = None
    for row in (json.loads(payload).get("data") or {}).get("list") or []:
        if (row.get("documentCount") or 0) > 0:
            kb_id = row["id"]
            break

    status, payload = call(
        "POST",
        "/assistants",
        token,
        {
            "name": f"A3 失败闸门-{int(time.time())}",
            "icon": "📄",
            "description": "A3 失败闸门用的临时应用",
            "type": "SIMPLE",
            "modelId": model_id,
            "kbIds": [kb_id] if kb_id else [],
            "rolePrompt": "你是知识助理，请基于参考资料回答。",
            "promptIds": [],
            "retrieval": {"mode": "HYBRID", "topK": 5, "scoreThreshold": 0},
            "multiTurn": True,
            "thinkingEnabled": False,
            "prologue": "",
            "quickQuestions": [],
        },
    )
    if status not in (200, 201):
        raise SystemExit(f"建应用失败（{status}）：{payload[:300]}")
    return json.loads(payload)["data"]["id"]


def main() -> int:
    print("== A3 真栈闸门（浏览器侧）：失败可见 + 可重试 ==")

    app_id = seed_app()
    print(f"  已建应用 {app_id}")

    try:
        _run_under_failure(app_id)
    finally:
        # ★★ 必须在 finally 里复原：本闸门把桩改成"100% 失败"，而真栈 job 的 12 条闸门是
        #   **顺序跑**的 ⇒ 只要这里异常退出而没复原，后面每一条都会看到"上游全 500"。
        #   那会把"一次闸门崩溃"放大成"一整片红"，排查方向会被完全带偏。
        restored = stub_config({"fail_rate": 0.0})
        print(f"  [复原] 桩 fail_rate 已归零（{restored}）")

    # ★ 先确认桩真的被置成"必失败"——否则下面的红会指向产品
    check(
        stub_config({"fail_rate": 1.0}),
        "⓪ 桩已注入 100% 失败（`POST /__stub/config {fail_rate:1.0}`）",
    )

    _run_under_failure(app_id)

    # ── ③ 对照：把注入关掉之后，**同一条链必须真的能成功** ──
    #   ★ 这不是"配置改回去了"就算 —— 要拿一次真实的问答读数。
    #     没有它，一个"永远失败"的实现也能让 ①② 绿。
    check(stub_config({"fail_rate": 0.0}), "③a 已把桩恢复成正常（`fail_rate=0`）")
    status, payload = call(
        "POST",
        "/conversations/chat",
        _login_token(),
        {"appId": app_id, "question": QUESTION},
    )
    check(
        status == 200 and "\"status\":\"GENERATED\"" in payload,
        f"③b ★ 对照：注入关掉后**同一条链能成功**（实得 {status}，"
        f"done 帧里应为 GENERATED）",
    )

    if problems:
        print(f"\n结果：红 —— {len(problems)} 条")
        for item in problems:
            print(f"  [FAIL] {item}")
        return 1
    print("\n结果：绿 —— A3 界面侧成立（可读原因 · 有重试入口 · 无未处理异常 · 关掉注入后能成功）")
    return 0


def _login_token() -> str:
    status, payload = call("POST", "/auth/login", body={"username": USER, "password": PASSWORD})
    if status != 200:
        raise SystemExit(f"登录失败（{status}）：{payload[:200]}")
    return json.loads(payload)["data"]["accessToken"]


def _run_under_failure(app_id: str) -> None:
    """在"桩必失败"的前提下，验界面侧的 ①②④。"""
    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome")
        page = browser.new_page(viewport={"width": 1500, "height": 900})
        errors: list[str] = []
        page.on("pageerror", lambda e: errors.append(f"PAGEERROR: {e}"))

        page.goto(BASE + "/#/login", wait_until="networkidle")
        page.wait_for_timeout(600)
        page.fill("input[autocomplete='username']", USER)
        page.fill("input[autocomplete='current-password']", PASSWORD)
        page.click("button:has-text('登录')")
        page.wait_for_timeout(2500)
        check(
            bool(page.evaluate("() => localStorage.getItem('access_token') || ''")),
            "⓪ 登录真的成功了（localStorage 里有 access_token）",
        )

        page.goto(BASE + f"/#/conversation?appId={app_id}", wait_until="networkidle")
        page.wait_for_timeout(2500)

        box = page.locator("textarea").first
        box.fill(QUESTION)
        page.keyboard.press("Enter")
        page.wait_for_timeout(6000)

        body = page.inner_text("body")
        print(f"  [诊断] 失败后页面片段 = {body[:260]!r}")

        readable = any(word in body for word in ("失败", "错误", "异常", "重试"))
        check(readable, "① ★ 失败之后界面**给出了可读原因**（正文含 失败/错误/异常/重试 之一）")

        retry = page.locator("button:has-text('重试'), button:has-text('重新生成')")
        check(
            retry.count() > 0,
            f"② ★★ 界面上有**能点的重试/重新生成入口**（实得 {retry.count()} 个）"
            f" —— 只给原因不给入口，用户只能刷新",
        )

        check(not errors, f"④ 全程无未处理页面异常（实得 {errors[:2]}）")

        browser.close()


if __name__ == "__main__":
    sys.exit(main())
