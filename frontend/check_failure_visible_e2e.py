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

━━━ ★★ 三处「机器状态」陷阱（2026-10-28 各红过一次，账在 #223） ━━━
① **桩地址不能用 `localhost`**：本机 `localhost` 先解析 `::1`，而 `::1:11434` 是 **Docker 的 wslrelay**，
   `POST /__stub/config` 落在那儿是 **404** ⇒ 注入与复原**双双静默失败**，闸门于是量了"一个正常的成功页面"。
   ⇒ 一律 `AIP_STUB_URL`（默认 `http://127.0.0.1:11434`），与其余 `check_*.py` 同口径。
② **不能"随便挑第一个 LOADED 的对话模型"**：本机还挂着 `qwen2.5-1.5b-本地`（指向 Ollama），
   它打到桩上只会得到 `404 模型不存在` ⇒ 闸门量到的是"模型名不对"，不是"上游失败如何呈现"。
   ⇒ 只挑 **apiBase 指向桩**的模型；没有就**建一个**（`baseModel=stub-plain`）并加载，跑完回收。
③ **"起了个 vite" ≠ "前端接的是真后端"**：`USE_MOCK` 的默认值是 **true**（`src/config/env.ts`），
   mock 模式下这一页**一条 `/api/v1` 请求都不发** ⇒ ①a/④ 假绿（没有真请求就没有错），
   ①b/② 假红（mock 会话 handler 里没有这个 appId，页面只弹"应用不存在"，一个气泡都不发）。
   ⇒ 先证明"页面真的在跟真后端说话"，否则 `SystemExit` 明说"本跑不算读数"。

用法：
    python check_failure_visible_e2e.py                      # 默认 http://localhost:5280
    python check_failure_visible_e2e.py http://127.0.0.1:5280
    AIP_STUB_URL=http://127.0.0.1:11434 python check_failure_visible_e2e.py   # 桩不在默认地址时
退出码：0 = 全绿；1 = 有红。
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from playwright.sync_api import sync_playwright

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"
STUB = os.environ.get("AIP_STUB_URL", "http://127.0.0.1:11434").rstrip("/")

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


def _same_endpoint(left: str | None, right: str) -> bool:
    """两个 apiBase 是不是同一个 host:port（路径不比：有的存 `/v1`，有的存到根）。"""
    if not left:
        return False
    try:
        a = urllib.parse.urlsplit(left)
        b = urllib.parse.urlsplit(right)
    except ValueError:
        return False
    return (a.hostname, a.port or 80) == (b.hostname, b.port or 80)


def _pick_chat_model(token: str) -> tuple[str, str | None]:
    """挑一个**打到桩上**的已加载对话模型；没有就用桩的模型名建一个。

    ★ 为什么不"挑第一个 LOADED 的"（2026-10-28 实测）：本机还挂着一个指向 Ollama 的
      `qwen2.5-1.5b-本地`，它打到桩上只会得到 `404 模型不存在` ⇒ 闸门量到的是"模型名不对"，
      而它以为自己量的是"上游失败怎么呈现"。这类夹具漂移必须由夹具自己消掉。
    返回 `(modelId, 新建的 id 或 None)` —— 新建的那个要在跑完回收。
    """
    status, payload = call("GET", "/models?size=50", token)
    rows = (json.loads(payload).get("data") or {}).get("list") or [] if status == 200 else []
    for row in rows:
        if row.get("kind") != "CHAT" or row.get("status") != "LOADED":
            continue
        if _same_endpoint(row.get("apiBase"), STUB):
            print(f"  已加载且指向桩的对话模型：{row.get('name')}（{row.get('baseModel')}）")
            return row["id"], None

    print(f"  [夹具] 没有指向 {STUB} 的已加载对话模型 ⇒ 建一个（baseModel=stub-plain）")
    status, payload = call(
        "POST",
        "/models",
        token,
        {
            "name": f"桩对话模型-{int(time.time())}",
            "kind": "CHAT",
            "supplier": "LOCAL_INFERENCE",
            "baseModel": "stub-plain",
            "apiBase": STUB + "/v1",
        },
    )
    if status not in (200, 201):
        raise SystemExit(f"建对话模型失败（{status}）：{payload[:300]}")
    model_id = json.loads(payload)["data"]["id"]
    status, payload = call("POST", f"/models/{model_id}/load", token, {})
    if status not in (200, 202):
        raise SystemExit(f"加载对话模型失败（{status}）：{payload[:300]}")
    deadline = time.time() + 120
    while time.time() < deadline:
        status, payload = call("GET", "/models?size=50", token)
        for row in (json.loads(payload).get("data") or {}).get("list") or []:
            if row.get("id") == model_id and row.get("status") == "LOADED":
                return model_id, model_id
        time.sleep(1)
    raise SystemExit("新建的对话模型 120s 内没有进入 LOADED（本跑不算读数）")


def _discard_model(token: str, model_id: str | None) -> None:
    if not model_id:
        return
    status, payload = call("DELETE", f"/models/{model_id}", token)
    print(f"  [回收] 临时对话模型 {model_id} 删除结果 {status}（{payload[:80]}）")


def seed_app() -> tuple[str, str | None]:
    status, payload = call("POST", "/auth/login", body={"username": USER, "password": PASSWORD})
    if status != 200:
        raise SystemExit(f"登录失败（{status}）：{payload[:200]}")
    token = json.loads(payload)["data"]["accessToken"]

    model_id, created_model = _pick_chat_model(token)

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
            "retrieval": {"mode": "HYBRID", "topK": 5, "threshold": 0},
            "multiTurn": True,
            "thinkingEnabled": False,
            "prologue": "",
            "quickQuestions": [],
        },
    )
    if status not in (200, 201):
        raise SystemExit(f"建应用失败（{status}）：{payload[:300]}")
    return json.loads(payload)["data"]["id"], created_model


def main() -> int:
    print("== A3 真栈闸门（浏览器侧）：失败可见 + 可重试 ==")

    app_id, created_model = seed_app()
    print(f"  已建应用 {app_id}")

    # ★★ 顺序是判据的一部分（第一版写反了，闸门因此跑了**两遍**）：
    #   ① 先**注入失败**并断言注入成功 → ② 再在"必失败"前提下验界面 → ③ 最后复原并验对照。
    #   ★ 第一版写成「try: 跑 → finally: 复原」，而"注入"排在 try **之后** ⇒
    #     第一次跑时桩还是正常的（`①b` 看到的是成功页面、自然没有"失败"字样 ⇒ 红），
    #     而 `problems` 保留了那一次的失败项 ⇒ 出现"同一条判据既 ✓ 又 [FAIL]"的怪相。
    #   ★ 教训：**闸门自己的前置顺序**也是判据的一部分；顺序错了，红会红在莫名其妙的地方。
    #   ★★ 2026-10-28 追加：注入**失败**时直接退出（`#223`）。原版的处置是"记一条红然后照跑"，
    #     于是后面三条红全是"在成功页面上找失败文案"的假红 —— 一条环境问题被放大成四条判据红。
    if not stub_config({"fail_rate": 1.0}):
        print(f"  ✗ 桩不可达或不接受注入（{STUB}/__stub/config）")
        print("  ⇒ 注入没生效，后面的判据会量到「一个正常的成功页面」，本跑不算读数。")
        return 1
    check(True, "⓪ 桩已注入 100% 失败（`POST /__stub/config {fail_rate:1.0}`）")

    try:
        _run_under_failure(app_id)
    finally:
        # ★ 必须在 finally 里复原：真栈 job 的闸门是**顺序跑**的，泄漏的 fail_rate
        #   会让后面每一条都看到"上游全 500" —— 那会把"一次闸门崩溃"放大成"一整片红"。
        restored = stub_config({"fail_rate": 0.0})
        print(f"  [复原] 桩 fail_rate 已归零（{restored}）")
        _discard_model(_login_token(), created_model)

    # ── ③ 对照：把注入关掉之后，**同一条链必须真的能成功** ──
    #   ★ 这不是"配置改回去了"就算 —— 要拿一次真实的问答读数。
    #     没有它，一个"永远失败"的实现也能让 ①② 绿。
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
        # ★★ 第三处「机器状态」陷阱（2026-10-28 实测，账在 #223）：
        #   前端 `USE_MOCK` 的默认值是 **true**（`src/config/env.ts`：`import.meta.env.VITE_USE_MOCK ?? 'true'`），
        #   所以"起了个 vite"不等于"前端接的是真后端"。mock 模式下这一页**一条 `/api/v1` 请求都不发**，
        #   于是：①a/④ 假绿（没有真请求就没有错），①b/② 假红（mock 的会话 handler 里没有这个 appId，
        #   页面只弹一句"应用不存在"，一个气泡都不发）。⇒ 本闸门必须先证明"页面真的在跟真后端说话"。
        api_calls: list[str] = []
        page.on(
            "request",
            lambda r: api_calls.append(f"{r.method} {r.url}") if "/api/v1/" in r.url else None,
        )

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

        # ★ 没有这一条，mock 模式下的假红看起来与"产品真的有缺陷"一模一样。
        if not api_calls:
            browser.close()
            raise SystemExit(
                "前端在 mock 模式（本次登录 + 打开会话页 0 条 /api/v1 请求）⇒ 界面侧判据量的是 mock，"
                "本跑不算读数。要用真栈请以 VITE_USE_MOCK=false 重启前端。"
            )
        print(f"  [口径] 页面确实在跟真后端说话（本次流程 {len(api_calls)} 条 /api/v1 请求）")

        page.goto(BASE + f"/#/conversation?appId={app_id}", wait_until="networkidle")
        page.wait_for_timeout(2500)

        box = page.locator("textarea").first
        box.fill(QUESTION)
        page.keyboard.press("Enter")
        page.wait_for_timeout(6000)

        body = page.inner_text("body")
        print(f"  [诊断] 失败后页面片段 = {body[:260]!r}")

        # ★★ ① 的口径（本版加强过，理由写在文件头）：
        #   不能只断"含某个词" —— 实测页面上那句是 `生成失败：refs.map is not a function`，
        #   **它含"失败"**，而用户读到的是一句 JS 类型错误。⇒ 「含某个词」不是「可读」。
        JS_ERROR_MARKERS = (
            "is not a function",
            "TypeError",
            "Cannot read",
            "undefined is not",
            "[object Object]",
        )
        leaked = [marker for marker in JS_ERROR_MARKERS if marker in body]
        check(
            not leaked,
            f"①a ★★ 界面上**不许出现内部 JS 错误**（实得泄漏的标记 {leaked}）"
            f" —— 那是实现细节，不是给用户的原因（账在 #164）",
        )
        readable = any(word in body for word in ("失败", "错误", "异常", "重试"))
        check(readable, "①b ★ 失败之后界面**给出了可读原因**（正文含 失败/错误/异常/重试 之一）")

        retry = page.locator("button:has-text('重试'), button:has-text('重新生成')")
        check(
            retry.count() > 0,
            f"② ★★ 界面上有**能点的重试/重新生成入口**（实得 {retry.count()} 个）"
            f" —— 只给原因不给入口，用户只能刷新",
        )

        # ★ ④ 的口径：只豁免**已登记为未实现**的端点（`/analytics`，账在 `#160`），
        #   且逐条打印被豁免的 —— 不许"过滤掉所有 pageerror"（那等于把 ④ 删掉）。
        KNOWN_UNIMPLEMENTED = "接口不存在：api/v1/analytics"
        surfaced = [e for e in errors if KNOWN_UNIMPLEMENTED not in e]
        waived = [e for e in errors if KNOWN_UNIMPLEMENTED in e]
        if waived:
            print(f"  [豁免] 已登记未实现端点的页面错误 {len(waived)} 条（账在 #160）：{waived[:2]}")
        check(not surfaced, f"④ ★ 除已登记的未实现端点外，无页面异常（实得 {surfaced[:2]}）")

        browser.close()


if __name__ == "__main__":
    sys.exit(main())
