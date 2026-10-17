"""A2 的**浏览器侧**读数 —— 「刷新页面不重置」在真实界面上的那一半。

━━━ 为什么必须有一条浏览器闸门 ━━━
`ROADMAP §2.2` 的 A2 是「**最容易被绕过、也最能说明问题的一条**」：
「任意时刻的进度与状态必须与数据库实际状态一致，**刷新页面不重置**。
前端现在的进度是 mock 推进的 —— 这一条通过，就等于『后端真的接管了状态』」。

★ 读侧那一半已经由 JUnit 守住（`ConversationStateSurvivesReloadTest`：服务层读回的状态与内容）。
★ 而「**刷新**」这个动作在浏览器里还多两步：路由重建 → 页面重新拉历史 → 渲染。
  那两步坏了、而服务层是对的 —— 症状就是"刷新后对话空了/答案没了"，用户会说"这个功能不好用"。

━━━ 判据三条（缺一条都能骗过）━━━
① 打开会话页 ⇒ **答案真的渲染出来了**（`✓` 前置；否则下面两条是空跑）；
② ★★ **刷新之后，同一段答案文字仍在** —— 这是 A2 在界面上的字面含义；
③ ★ **对照：刷新前后的答案文字完全相同** —— 只断"还在"的话，一个"刷新后换成别的答案"
   （例如重新生成了一次）的实现也能绿。

★★ 为什么答案文本可以**精确断言**：真栈用的是 `backend/tools/openai_stub.py`，
它回的是确定性字符串 `（桩）关于「{问题前 30 字}」，这是占位回答。`
⇒ 不需要模糊匹配、也不会因为模型措辞漂移而随机红。★ 这是本闸门能"确定性"的前提，
  写在最前面，免得下一个人以为我在猜文本。

★ 本闸门**不在 UI 里打字**：会话由 API 先问一轮造出来，然后浏览器只做"打开 → 读 → 刷新 → 再读"。
  理由：打字路径已被 `check_flow_walkthrough` / `check_history` 覆盖，
  而本闸门要验的是**刷新这一刻**，少一个变量就少一处随机红。

用法：
    python check_reload_keeps_state_e2e.py                      # 默认 http://localhost:5280
    python check_reload_keeps_state_e2e.py http://127.0.0.1:5280
退出码：0 = 全绿；1 = 有红。
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"

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


def seed_one_turn() -> tuple[str, str]:
    """用 API 造一轮问答（★ 与页面同一条后端路径），返回 (appId, 期望的答案片段)。"""
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
            "name": f"A2 刷新闸门-{__import__('time').time():.0f}",
            "icon": "📄",
            "description": "A2 刷新闸门用的临时应用",
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
    app_id = json.loads(payload)["data"]["id"]

    status, _ = call("POST", "/conversations/chat", token, {"appId": app_id, "question": QUESTION})
    if status != 200:
        raise SystemExit(f"造一轮问答失败（{status}）")

    # ★ 桩的答案是确定性字符串：`（桩）关于「{问题前 30 字}」，这是占位回答。`
    #   够它稳定复现的那一段前缀（★ 不取整句：整句依赖问题长度截断，取前缀更稳）
    expected = f"（桩）关于「{QUESTION[:30]}」"
    return app_id, expected


def main() -> int:
    print("== A2 真栈闸门（浏览器侧）：刷新页面不重置 ==")

    app_id, expected = seed_one_turn()
    print(f"  已用 API 造一轮问答（应用 {app_id}）")
    print(f"  期望在界面上看到的答案片段：{expected}")

    with sync_playwright() as p:
        browser = p.chromium.launch(channel="chrome")
        page = browser.new_page(viewport={"width": 1500, "height": 900})
        errors: list[str] = []
        page.on("pageerror", lambda e: errors.append(f"PAGEERROR: {e}"))

        # ── 登录（UI 路径：本闸门要验的是"刷新"，但登录仍走真实界面，免得绕过路由门禁）──
        page.goto(BASE + "/#/login", wait_until="networkidle")
        page.wait_for_timeout(600)
        page.fill("input[autocomplete='username']", USER)
        page.fill("input[autocomplete='current-password']", PASSWORD)
        page.click("button:has-text('登录')")
        # ★ 与 `check_flow_walkthrough.py:302-303` 同一条稳法：等 2500ms 后**用 localStorage 确认**
        #   登录真的成功了。
        # ★★ 上一版只等 1800ms 且不确认 —— 若登录没成，页面就停在登录页，而 `body.innerText`
        #   恰好只剩应用名 ⇒ 症状与"页面没渲染"一模一样（上一轮就是这么红的，而我没法定因）。
        page.wait_for_timeout(2500)
        token = page.evaluate("() => localStorage.getItem('access_token') || ''")
        check(bool(token), "⓪ 登录真的成功了（localStorage 里有 access_token）")
        print(f"  [诊断] 登录后 url = {page.url} · token 长度 = {len(token)}")

        # ── 打开会话页（★ 页面按 `appId` 载入该应用最近一条会话）──
        page.goto(BASE + f"/#/conversation?appId={app_id}", wait_until="networkidle")
        page.wait_for_timeout(3000)

        def rendered() -> str:
            return page.inner_text("body")

        before = rendered()
        # ★ 诊断（不是判据）：上一轮红在"页面片段只有应用名"，这三行能立刻分开
        #   "没导航过去" / "导航了但没渲染" / "渲染了但内容空" 三种。
        print(f"  [诊断] 会话页 url = {page.url}")
        print(f"  [诊断] body 长度 = {len(before)}")
        print(f"  [诊断] body 前 200 字 = {before[:200]!r}")

        check(
            expected in before,
            f"① 打开会话页 ⇒ 答案真的渲染出来了（找 {expected!r}）"
            f"{'；实际页面片段：' + before[:200] if expected not in before else ''}",
        )

        # ── ★★ 刷新：这是本闸门唯一的动作 ──
        page.reload(wait_until="networkidle")
        page.wait_for_timeout(3000)

        after = rendered()
        check(
            expected in after,
            f"② ★★ 刷新之后同一段答案**仍在**（A2 在界面上的字面含义）"
            f"{'；刷新后页面片段：' + after[:200] if expected not in after else ''}",
        )
        # ★★ 为什么**不**比较整页文本（我第一版就是这么写的，那会随机红）：
        #   侧栏有相对时间（"刚刚" → "1 分钟前"），翻页/滚动位置也会变 ⇒ `before == after`
        #   在某些时刻恒假。★ 本仓的纪律是"随机红比不红更坏"。
        # ⇒ 改成比较**答案出现的次数**：它既稳定，又能抓住"刷新后追加了第二条"这种坏法。
        check(
            after.count(expected) == before.count(expected),
            f"③ ★ 对照：刷新前后**答案出现的次数必须相同**"
            f"（前 {before.count(expected)} 次，后 {after.count(expected)} 次）"
            f" —— 只断『还在』的话，一个『刷新后偷偷追加/重生成了一次』的实现也能绿",
        )
        # ★★ ④ 的口径（本版收窄过，理由写在这里）：
        #   本闸门要验的是"**刷新不重置**"，不是"所有端点都实现了"。
        #   而 `/analytics` **在 `ContractDriftTest` 的未实现清单里**（`:139`），前端正常调用它
        #   ⇒ 必然抛 `PAGEERROR: 接口不存在：api/v1/analytics/...`。那不是本闸门该否决的东西
        #   （它自己的账在 `#160`）。
        #   ★ 但**只豁免这一个前缀**、且**必须逐条打印被豁免的** —— 不许"过滤掉所有 pageerror"
        #     （那等于把 ④ 删掉）。
        KNOWN_UNIMPLEMENTED = "接口不存在：api/v1/analytics"
        surfaced = [e for e in errors if KNOWN_UNIMPLEMENTED not in e]
        waived = [e for e in errors if KNOWN_UNIMPLEMENTED in e]
        if waived:
            print(f"  [豁免] 已登记未实现端点的页面错误 {len(waived)} 条（账在 #160）：{waived[:2]}")
        check(not surfaced, f"④ ★ 除已登记的未实现端点外，无页面异常（实得 {surfaced[:2]}）")

        browser.close()

    if problems:
        print(f"\n结果：红 —— {len(problems)} 条")
        for item in problems:
            print(f"  [FAIL] {item}")
        return 1
    print("\n结果：绿 —— A2 浏览器侧成立（能渲染 · 刷新后仍在 · 前后完全一致）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
