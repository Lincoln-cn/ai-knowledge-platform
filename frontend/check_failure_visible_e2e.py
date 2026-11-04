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

━━━ ★ 守卫的形状（#223 本轮定） ━━━
每条陷阱 = 一个 `_apply_*_guard`（条件不成立 ⇒ `SystemExit` **中断**，消息以
「【环境问题，不是产品缺陷】」开头并声明「本跑不算读数」）。★ 为什么是"中断"而不是
"记一条红然后照跑"：照跑会把**一条**环境问题放大成一串像产品缺陷的红（#223 实测 4 红里 3 条是
环境）。★ `stub_config()` 的返回值（注入**与复原**）都必须被检查 —— 复原失败是把 `fail_rate=1.0`
泄漏给后面每一条真栈闸门。★ `--self-check` 给每条守卫喂**故意构造的坏条件**并断言它真的会中断：
一个永远为真的守卫等于没有（`#223` 判据 + R58 反证口径）。

用法：
    python check_failure_visible_e2e.py                      # 默认 http://localhost:5280
    python check_failure_visible_e2e.py http://127.0.0.1:5280
    AIP_STUB_URL=http://127.0.0.1:11434 python check_failure_visible_e2e.py   # 桩不在默认地址时
    python check_failure_visible_e2e.py --self-check          # 离线自检：三处机器状态守卫自己有没有错
退出码：0 = 全绿；1 = 有红。
"""

from __future__ import annotations

import inspect
import json
import os
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


# ★ 位置参数里滤掉 `--self-check` 这类开关 —— 否则 `python ... --self-check` 会把开关本身
#   当成被测站点的地址（第一版就是这么脏的，虽然自检不读 BASE，但别留这种暗坑）。
_ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
BASE = (_ARGS[0] if _ARGS else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"

# ★ 陷阱①（#223）：默认值必须是 `127.0.0.1`，**不许写 `localhost`** ——
#   本机 `localhost` 先解析到 `::1`，而 `::1:11434` 是 Docker 的 wslrelay（`/__stub/config` 落在
#   那儿是 404）⇒ 注入与复原**双双静默失败**。与其余 `check_*.py` 同口径：`AIP_STUB_URL` 可覆盖。
_DEFAULT_STUB = "http://127.0.0.1:11434"
STUB = os.environ.get("AIP_STUB_URL", _DEFAULT_STUB).rstrip("/")

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


def _env_problem(detail: str, how_to_fix: str) -> str:
    """把"环境/机器状态不就绪"说成一句**不可能被读成产品缺陷**的话（判据④，账在 #223）。

    ★ 三条守卫报错时措辞必须一眼看出"这不是产品的错" —— #223 的危害正是机器状态问题被
      当成产品缺陷去查（4 条红里 3 条是环境）。统一形状后 `--self-check` 逐条断言它。
    """
    return f"【环境问题，不是产品缺陷】{detail} ⇒ 本跑不算读数。{how_to_fix}"


def stub_config(payload: dict, base: str | None = None) -> bool:
    """改桩的行为（★ 这是它**设计好的**注入口，见 `openai_stub.py` 的 docstring）。

    ★ 返回值**必须被调用方检查**（#223）：它只返回 bool，第一版注入与复原**双双没人看它** ——
      注入失败 ⇒ 闸门在"桩一切正常"的假前提下找失败文案（①b/② 假红）；
      复原失败 ⇒ `fail_rate=1.0` 泄漏给后面每一条真栈闸门（一整片红）。
    """
    req = urllib.request.Request(
        (base or STUB) + "/__stub/config",
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status == 200
    except Exception:  # noqa: BLE001
        return False


def _restore_problem(restored: bool) -> str | None:
    """复原（`fail_rate` 归零）失败 ⇒ 那句环境定性的话；成功 ⇒ None。

    ★ #223 的原话是"注入与复原**双双**静默失败"：注入侧已经"失败即退出"，复原侧也一样不许静默 ——
      它泄漏的是**后面每一条**真栈闸门的读数。
    """
    if restored:
        return None
    return _env_problem(
        f"桩复原失败（POST {STUB}/__stub/config {{fail_rate:0.0}} 没有 200）——"
        "fail_rate 可能还留在 1.0，会污染后面所有真栈闸门",
        '请手动把桩的 fail_rate 归零（POST /__stub/config {"fail_rate":0.0}）。',
    )


def _stub_guard_message(injected: bool) -> str | None:
    """陷阱① 的判据：桩**接受注入**了吗？不接受 ⇒ 那句环境定性的话；接受 ⇒ None。

    ★ 这条守卫量的是"我打的是不是真的那个桩"（`::1:11434` = Docker 的 wslrelay，404），
      不是量产品 —— 所以消息必须点名 `localhost`/`::1` 这条机制，别让人去查界面代码。
    """
    if injected:
        return None
    return _env_problem(
        f"桩不可达或不接受注入（POST {STUB}/__stub/config 没有 200）——"
        "`localhost` 在本机会先解析到 `::1`，那是 Docker 的 wslrelay，落在那儿是 404，"
        "注入与复原会双双静默失败",
        "请确认 `python backend/tools/openai_stub.py` 在跑，且 AIP_STUB_URL 指向它"
        f"（默认 {_DEFAULT_STUB}，不带 localhost）。",
    )


def _apply_stub_guard(injected: bool) -> None:
    """陷阱① 的前置守卫：不成立就**中断**，不许带着坏前提继续量（#223）。"""
    message = _stub_guard_message(injected)
    if message:
        raise SystemExit(message)


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


def _model_fixture_message(api_base: str | None) -> str | None:
    """陷阱② 的判据：这个模型**真的会打到桩上**吗？不会 ⇒ 说清为什么不能用它；会 ⇒ None。

    ★ `localhost` 主机名一律判不可信（不只是"不等于 127.0.0.1 就跳过"）：它可能解析到
      `::1` 的 wslrelay —— 那正是陷阱① 的机制，两条陷阱是同一个坑的两个入口。
    """
    if not api_base:
        return _env_problem(
            "候选对话模型没有 apiBase ⇒ 证明不了它的上游是桩",
            "闸门只用 apiBase 指向桩的模型；请给模型配上桩地址，或让闸门自建 stub-plain。",
        )
    if _same_endpoint(api_base, STUB):
        return None
    try:
        host = urllib.parse.urlsplit(api_base).hostname
    except ValueError:
        host = None
    if host == "localhost":
        return _env_problem(
            f"候选对话模型的 apiBase 用的是 `localhost`（{api_base}）—— 它在本机先解析到 `::1`"
            "（Docker 的 wslrelay），打上去是 404",
            "请把模型的 apiBase 改成 127.0.0.1 的桩地址（或让闸门自建）。",
        )
    return _env_problem(
        f"候选对话模型指向 {api_base}，不是桩 {STUB} —— 问它只会得到 `404 模型不存在`（#223 实测），"
        "闸门量到的会是「模型名不对」而不是「上游失败怎么呈现」",
        f"请让模型的 apiBase 指向桩 {STUB}，或删掉这条模型让闸门自建 stub-plain。",
    )


def _apply_model_guard(api_base: str | None) -> None:
    """陷阱② 的前置守卫：选中/建出的模型必须真的指向桩，否则**中断**（#223）。

    ★ 选择逻辑只挑指向桩的还不够 —— "建了"不等于"建对了"（后端可能改写 apiBase），
      所以自建路径在 LOADED 之后也要过这道守卫自证一次。
    """
    message = _model_fixture_message(api_base)
    if message:
        raise SystemExit(message)


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
        if _model_fixture_message(row.get("apiBase")) is None:
            _apply_model_guard(row.get("apiBase"))
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
        raise SystemExit(_env_problem(
            f"建对话模型失败（{status}）：{payload[:300]}",
            "这是闸门的夹具准备（不是产品判据）；请看后端日志。",
        ))
    model_id = json.loads(payload)["data"]["id"]
    status, payload = call("POST", f"/models/{model_id}/load", token, {})
    if status not in (200, 202):
        raise SystemExit(_env_problem(
            f"加载对话模型失败（{status}）：{payload[:300]}",
            "这是闸门的夹具准备（不是产品判据）；请看后端日志。",
        ))
    deadline = time.time() + 120
    while time.time() < deadline:
        status, payload = call("GET", "/models?size=50", token)
        for row in (json.loads(payload).get("data") or {}).get("list") or []:
            if row.get("id") != model_id or row.get("status") != "LOADED":
                continue
            # ★★ 自证（#223）："建了"不等于"建对了" —— 后端可能改写 apiBase，
            #   所以 LOADED 之后再过一遍守卫，不指向桩就中断，不许带着坏夹具去量界面。
            _apply_model_guard(row.get("apiBase"))
            print(f"  [夹具] 临时桩模型已 LOADED 且指向桩（apiBase={row.get('apiBase')}）")
            return model_id, model_id
        time.sleep(1)
    raise SystemExit(_env_problem(
        "新建的对话模型 120s 内没有进入 LOADED",
        "请看后端日志确认 /models/{id}/load 为什么没起来；本闸门不带病测量。",
    ))


def _mock_mode_message(api_calls: list[str]) -> str | None:
    """本次流程一条 `/api/v1` 请求都没发 ⇒ 页面在 mock 模式，返回该说的那句话；否则 None。

    ★ 抽成纯函数的理由：这条判据自己必须能被断言（`--self-check`），否则它就是"看起来在守、
      其实从没红过"的那一类守卫 —— 本工程的 `#223` 正是因为闸门对机器状态有假设才量错东西。
    """
    if api_calls:
        return None
    return _env_problem(
        "前端在 mock 模式（本次登录 + 打开会话页 0 条 /api/v1 请求）——"
        "`USE_MOCK` 的默认值是 true（src/config/env.ts），mock 下这一页一条 /api/v1 都不发，"
        "①a/④ 会假绿、①b/② 会假红",
        "要用真栈请以 VITE_USE_MOCK=false 重启前端。",
    )


def _apply_mock_guard(api_calls: list[str]) -> None:
    """陷阱③ 的前置守卫：页面没在跟真后端说话 ⇒ **中断**，本跑不算读数（#223）。"""
    message = _mock_mode_message(api_calls)
    if message:
        raise SystemExit(message)


def _discard_model(token: str, model_id: str | None) -> None:
    if not model_id:
        return
    status, payload = call("DELETE", f"/models/{model_id}", token)
    print(f"  [回收] 临时对话模型 {model_id} 删除结果 {status}（{payload[:80]}）")


def seed_app() -> tuple[str, str | None]:
    status, payload = call("POST", "/auth/login", body={"username": USER, "password": PASSWORD})
    if status != 200:
        raise SystemExit(_env_problem(
            f"登录失败（{status}）：{payload[:200]}",
            "请确认后端在跑、账号口令对（这一步是夹具准备，不是产品判据）。",
        ))
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
        raise SystemExit(_env_problem(
            f"建应用失败（{status}）：{payload[:300]}",
            "这是闸门的夹具准备（不是产品判据）；请看后端日志。",
        ))
    return json.loads(payload)["data"]["id"], created_model


def main() -> int:
    print("== A3 真栈闸门（浏览器侧）：失败可见 + 可重试 ==")

    # ★★ 顺序是判据的一部分（第一版写反了，闸门因此跑了**两遍**）：
    #   ① 先**注入失败**并断言注入成功 → ② 再在"必失败"前提下验界面 → ③ 最后复原并验对照。
    #   ★ 第一版写成「try: 跑 → finally: 复原」，而"注入"排在 try **之后** ⇒
    #     第一次跑时桩还是正常的（`①b` 看到的是成功页面、自然没有"失败"字样 ⇒ 红），
    #     而 `problems` 保留了那一次的失败项 ⇒ 出现"同一条判据既 ✓ 又 [FAIL]"的怪相。
    #   ★ 教训：**闸门自己的前置顺序**也是判据的一部分；顺序错了，红会红在莫名其妙的地方。
    #   ★★ 陷阱① 的前置守卫（#223）排在**一切之前**（连 seed_app 都排它后面）：注入不成立就
    #     **中断**，一条产品判据都不产出。原版的处置是"记一条红然后照跑"，于是后面三条红全是
    #     "在成功页面上找失败文案"的假红 —— 一条环境问题被放大成四条判据红。
    _apply_stub_guard(stub_config({"fail_rate": 1.0}))
    check(True, "⓪ 桩已注入 100% 失败（`POST /__stub/config {fail_rate:1.0}`）")

    created_model: str | None = None
    try:
        # ★ seed_app 也进 try：夹具准备崩了同样不许把 `fail_rate=1.0` 留在桩上（复原在 finally）。
        app_id, created_model = seed_app()
        print(f"  已建应用 {app_id}")
        _run_under_failure(app_id)
    finally:
        # ★ 必须在 finally 里复原：真栈 job 的闸门是**顺序跑**的，泄漏的 fail_rate
        #   会让后面每一条都看到"上游全 500" —— 那会把"一次闸门崩溃"放大成"一整片红"。
        restored = stub_config({"fail_rate": 0.0})
        # ★ 复原的返回值也**必须被检查**（#223：注入与复原"双双静默失败"过）：
        #   复原失败 = `fail_rate=1.0` 泄漏给后面每一条真栈闸门（一次崩溃放大成一整片红）。
        restore_problem = _restore_problem(restored)
        if restore_problem:
            print(f"  ✗ {restore_problem}")
            problems.append(restore_problem)
        else:
            print("  [复原] 桩 fail_rate 已归零")
        if created_model:
            try:
                _discard_model(_login_token(), created_model)
            except SystemExit as cleanup_error:
                print(f"  [回收] 跳过临时模型回收（拿不到 token）：{cleanup_error}")

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
        raise SystemExit(_env_problem(
            f"登录失败（{status}）：{payload[:200]}",
            "请确认后端在跑、账号口令对（这一步是夹具准备，不是产品判据）。",
        ))
    return json.loads(payload)["data"]["accessToken"]


def _run_under_failure(app_id: str) -> None:
    """在"桩必失败"的前提下，验界面侧的 ①②④。"""
    # ★ 延迟导入：`--self-check` 要在没装 playwright 的机器上也能跑（CI 的静态 job 就不装）。
    from playwright.sync_api import sync_playwright

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
        #   守卫在这里（登录后、任何界面判据之前）中断 ⇒ ①a/①b/②/④ 一条读数都不产出。
        _apply_mock_guard(api_calls)
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


def self_check() -> int:
    """离线自检三处「机器状态」守卫本身（`#223`）。跑法：`python check_failure_visible_e2e.py --self-check`

    ★ 为什么要有它：这三条守卫的作用是"让闸门别量错东西"，而一条守卫自己错了的表现是**静默**
      —— 要么它永远为真（等于没有），要么它把正确的情形也判成错。只有把它们当被测对象才有读数。
    ★★ 每条守卫的"会红"都由**故意构造的坏条件**产生（喂坏状态 ⇒ 断言守卫真的**中断**，并把责任
      说成环境问题），而不是"断言它平时不报错" —— 后者正是"永远为真"的那种守卫（#223 判据）。
    不起浏览器、不要 `playwright`；不连栈（只连本机回环上一个"绑了没听"的端口，验 `stub_config`
    的失败路径）。
    """
    failures: list[str] = []

    def expect(ok: bool, what: str) -> None:
        print(f"  {'✓' if ok else '✗'} {what}")
        if not ok:
            failures.append(what)

    def expect_env_message(message: str | None, what: str) -> None:
        """断言守卫消息把责任说成**环境问题**（判据④）：不是产品缺陷，且声明本跑不算读数。"""
        ok = message is not None and "不是产品缺陷" in message and "本跑不算读数" in message
        expect(ok, f"{what}（实得 {message!r}）")

    def expect_guard_blocks(guard, bad_input, what: str) -> None:
        """喂**故意构造的坏条件** ⇒ 断言守卫真的中断（抛 `SystemExit`）—— 会红才算守卫。"""
        try:
            guard(bad_input)
        except SystemExit as e:
            expect_env_message(str(e), what)
        else:
            expect(False, f"{what} —— 守卫没中断（坏条件被放行 ⇒ 假红/假绿会照常产出）")

    # ── ① 桩地址 + 注入/复原的返回值（#223 陷阱①）──
    expect("localhost" not in _DEFAULT_STUB, f"① 默认桩地址不含 localhost（实得 {_DEFAULT_STUB}）")
    expect(
        os.environ.get("AIP_STUB_URL") is None or STUB == os.environ["AIP_STUB_URL"].rstrip("/"),
        "① 桩地址可被 AIP_STUB_URL 覆盖且不留尾斜杠",
    )
    expect(_stub_guard_message(True) is None, "① 好条件：注入成功 ⇒ 守卫放行（不误伤）")
    expect_guard_blocks(_apply_stub_guard, False, "① 坏条件：注入失败 ⇒ 守卫中断并定性为环境问题")
    message = _stub_guard_message(False)
    expect(
        message is not None and "localhost" in message and "::1" in message,
        "① 注入失败的解释点名 localhost ⇒ ::1（wslrelay）这条机制（别让人去查界面代码）",
    )
    expect(_restore_problem(True) is None, "① 好条件：复原成功 ⇒ 不报")
    expect_env_message(_restore_problem(False), "① 坏条件：复原失败（返回值被检查）⇒ 报环境问题，不静默")
    # ★ 真网络坏条件：端口"绑了但不 listen" ⇒ 连它必然被拒，且别的进程抢不走这个端口。
    #   要证的是"注入失败这条检测链路活着" —— `stub_config` 恒 True 会让所有守卫形同虚设。
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        dead = f"http://127.0.0.1:{probe.getsockname()[1]}"
        expect(
            stub_config({"fail_rate": 0.0}, base=dead) is False,
            f"① 坏条件：桩地址没人听（{dead}）⇒ stub_config 返回 False（不许恒 True、不许抛异常）",
        )

    # ── ② 模型夹具（#223 陷阱②）：只有 apiBase 真的指向桩的模型才许用 ──
    expect(_same_endpoint(STUB + "/v1", STUB), "② 同一 host:port、路径不同 ⇒ 判为指向桩（路径不比）")
    expect(not _same_endpoint("http://127.0.0.1:11435/v1", STUB), "② 端口不同 ⇒ 不算（否则会挑到别的 ollama）")
    expect(not _same_endpoint(None, STUB), "② apiBase 为空 ⇒ 不算")
    expect(not _same_endpoint("not a url", STUB), "② 坏 URL ⇒ 不算，不抛异常")
    expect(_model_fixture_message(STUB + "/v1") is None, "② 好条件：apiBase 指向桩 ⇒ 守卫放行（不误伤）")
    for bad, why in (
        (None, "apiBase 为空"),
        ("http://127.0.0.1:11435/v1", "端口不同（那是 Ollama）"),
        ("http://localhost:11434/v1", "主机名是 localhost（会解析到 ::1 的 wslrelay）"),
        ("not a url", "坏 URL"),
    ):
        expect_guard_blocks(_apply_model_guard, bad, f"② 坏条件：{why} ⇒ 守卫中断并定性为环境问题")

    # ── ③ mock 模式（#223 陷阱③）：页面没发 /api/v1 ⇒ 本跑不算读数 ──
    expect(
        _mock_mode_message(["GET http://127.0.0.1:5280/api/v1/models"]) is None,
        "③ 好条件：有 /api/v1 请求 ⇒ 不判 mock（不误伤）",
    )
    expect_guard_blocks(_apply_mock_guard, [], "③ 坏条件：零请求 ⇒ 守卫中断并定性为环境问题")
    message = _mock_mode_message([])
    expect(message is not None and "mock" in message, "③ 零请求的解释点名 mock 模式")
    expect(
        message is not None and "VITE_USE_MOCK=false" in message,
        "③ 解释里给出怎么修（VITE_USE_MOCK=false 重启前端）",
    )

    # ── 判据④（#223）：三处陷阱任一命中，都不许把责任推给产品 ──
    expect(
        all(
            m is not None and m.startswith("【环境问题，不是产品缺陷】")
            for m in (
                _stub_guard_message(False),
                _restore_problem(False),
                _model_fixture_message(None),
                _mock_mode_message([]),
            )
        ),
        "判据④：四条命中消息全部以「【环境问题，不是产品缺陷】」开头",
    )

    # ── 接线：守卫能红还不够 —— 它得真的被挂在测量路径上（否则"能红的守卫"照样没用）──
    expect("_apply_stub_guard(" in inspect.getsource(main), "接线：陷阱①守卫挂在 main 的任何判据之前")
    expect("_restore_problem(" in inspect.getsource(main), "接线：复原的返回值真的被 main 检查（不静默）")
    expect(
        "_apply_mock_guard(" in inspect.getsource(_run_under_failure),
        "接线：陷阱③守卫挂在浏览器判据（①a/①b/②/④）之前",
    )
    fixture_source = inspect.getsource(_pick_chat_model)
    expect(
        "_model_fixture_message(" in fixture_source and "_apply_model_guard(" in fixture_source,
        "接线：陷阱②守卫挂在夹具挑选与自建之后（自证 apiBase 指向桩）",
    )

    print()
    if failures:
        print(f"自检失败 {len(failures)} 条：")
        for item in failures:
            print(f"  - {item}")
        return 1
    print("自检通过：三处机器状态守卫都在按预期工作（含各自在坏条件下的红）。")
    return 0


if __name__ == "__main__":
    try:
        code = self_check() if "--self-check" in sys.argv else main()
    except SystemExit as err:
        # ★ 环境守卫的中断消息**就是这次的读数**（判据④：一眼看出是环境问题），
        #   stdout 也留一份（闸门读数习惯看 stdout）；退出码仍由 SystemExit 原样定。
        if isinstance(err.code, str):
            print(f"\n{err.code}")
        raise
    sys.exit(code)
