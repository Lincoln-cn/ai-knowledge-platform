"""B7 的真栈读数 —— **连续 200 次提问，0 次未处理异常**（`ROADMAP §2.3`）。

━━━ "未处理异常"在本闸门里怎么读 ━━━
一条提问有**四个面**，任一坏了都算一次失败（而且是分开计数，红了知道坏在哪一面）：
  ① HTTP 面：非 200（含 `#211` 那种"500 且响应体为空"）；
  ② 帧面：出现 `{"error": …}`；
  ③ 终态面：done 帧的 `status` 不是 `GENERATED`（`FAILED` 也是"没崩但没答出来"）；
  ④ 正文面：一次 `{"text":…}` 都没收到（响应 200 + 终态 GENERATED 但一个字没有）。
★ 再加**日志面**（可选 `AIP_B7_LOG`）：跑前记文件大小偏移，跑完只读**新增**部分数 `ERROR` 行 ——
  这是 `#211` 那类"异常被 Tomcat 吃掉、响应体是空的"唯一能抓住的地方。
  没给日志路径时**明确打印"只断了响应面"**，不假装查过日志。

━━━ 为什么复用同一个会话 ━━━
每轮都新建会话的话，多轮历史这条链一次都不会被压到；复用同一个会话 ⇒ 第 200 轮带着 199 轮历史
在跑，`A6` 那个 10 轮窗口（以及窗口外的裁剪）才真的走了 200 次。跑完再核 `turnCount == 2×轮数`
（1 问 1 答 ⇒ 2），这是"历史确实写进去了"的旁证，不是主判据。

━━━ 耗时只在 p95 上断，不在 max 上断 ━━━
200 轮的 max 会被一次 GC / 一次慢查询污染，用它红会训练人"重跑一次就好"；所以判据是
**p95 ≤ B5 的上限（60s）**，max 只打印。★ 但"越跑越慢"本身是读数：首 10 轮与末 10 轮的中位数
也会打印，两者差一个量级就是一条独立发现，不该被"p95 绿"盖住。

━━━ 令牌过期不算产品红（`#225` 那条，2026-10-28 实测踩到）━━━
`access-ttl` 是 30m，而 200 轮真模型实测要 27–31 分钟 ⇒ **长跑一定跨过它**。第一次跑到 #124
起连续 77 条 `HTTP 401`，如果照实计成"未处理异常"，这条闸门量的就是"令牌有没有过期"。
⇒ 碰到 401 就**重新登录并重试该轮**，续签次数单独打印（续签之后仍 401 才计失败）。

★ `--self-check`：用假 `ask` 把三条续签语义跑一遍（不续签 / 续签一次 / 续签后仍 401），
  外加一条反向用法（`reauth=False` 时 401 必须计失败）。它不需要真栈、不需要真模型 ——
  否则"续签到底会不会发生"只能靠一次 30 分钟长跑证明，实际上就是从来没验过（`#226` 判据 ②）。

用法：
    python check_b7_stability.py --self-check     # 离线：只验续签语义（快闸里跑这条）
    python check_b7_stability.py
    AIP_B7_ROUNDS=200 AIP_B7_LOG=/tmp/backend.log python check_b7_stability.py
退出码：0 = 达标；1 = 有红 / 环境起不来。
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"

USER = "admin"
PASSWORD = "admin"
ROUNDS = int(os.environ.get("AIP_B7_ROUNDS", "200"))
PAUSE = float(os.environ.get("AIP_B7_PAUSE", "0"))
B5_LIMIT = 60.0
LOG_PATH = os.environ.get("AIP_B7_LOG", "")

problems: list[str] = []


def check(ok: bool, message: str) -> None:
    if ok:
        print(f"  ✓ {message}")
    else:
        print(f"  ✗ {message}")
        problems.append(message)


def note(message: str) -> None:
    print(f"  · {message}")


def call(method: str, path: str, token: str = "", body: dict | None = None) -> tuple[int, dict]:
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
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")
        except Exception:  # noqa: BLE001
            return e.code, {}
    except Exception as e:  # noqa: BLE001
        return -1, {"message": f"{type(e).__name__}: {e}"}


def data_of(body: dict) -> dict:
    value = body.get("data")
    return value if isinstance(value, dict) else {}


def login() -> str:
    status, body = call("POST", "/auth/login", "", {"username": USER, "password": PASSWORD})
    token = data_of(body).get("accessToken")
    if status != 200 or not token:
        raise SystemExit(f"登录失败（{status}）：{str(body)[:200]} —— 真栈没起，本跑不算读数")
    return str(token)


def _served_models() -> set[str]:
    """默认上游（`AIP_CHAT_API_BASE`）的 `/v1/models` 里实际有哪些模型名（探不到就空集）。

    ★ 为什么不用行里的 `apiBase`：模型的**列表接口不返回 apiBase**（实测一条为
      `{id,name,kind,supplier,baseModel,status,contextWindow,dimension,thinking,tools,builtin,remark,legacyDirect}`），
      所以只能问"默认上游到底服务哪些模型"。
    """
    url = os.environ.get("AIP_CHAT_API_BASE", "http://127.0.0.1:11434/v1").rstrip("/") + "/models"
    try:
        with urllib.request.urlopen(url, timeout=3) as resp:
            payload = json.loads(resp.read().decode("utf-8", "replace"))
    except Exception:  # noqa: BLE001
        return set()
    return {str(row.get("id")) for row in payload.get("data") or []}


def _row_serves(row: dict) -> bool:
    """这条模型记录声明的 baseModel，默认上游**真的**有吗。"""
    return str(row.get("baseModel") or "") in _served_models()



def ensure_chat_model(token: str) -> str:
    """找一个**上游真的能服务**且**已加载**的对话模型；没有就建一个并等它加载完。

    ★ 这一步不能省：没有已加载模型时第一轮会 500，那个 500 会被 B7 记成"上游异常"，
      而它其实只是夹具没准备好。
    ★★ 2026-10-28 实测（`#225`）：也**不能"随便取第一个 LOADED"** ——
      ① 先量到的是 `qwen2.5-1.5b-本地`（Ollama），当时 11434 是桩 ⇒ `404 模型不存在`；
      ② 换成"只认桩"之后又反过来 —— 桩一撤、Ollama 上 11434，判据全部落空。
      ⇒ **既不能认"桩"也不能认"Ollama"，只能认"这条记录的上游 `/v1/models` 里真的有它的
      baseModel"**（`_served_models` + `_row_serves`）。
    """
    _, body = call("GET", "/models?kind=CHAT&size=50", token)
    rows = data_of(body).get("list") or []
    for row in rows:
        if row.get("status") == "LOADED" and _row_serves(row):
            return str(row.get("id"))

    stamp = int(time.time())
    status, created = call(
        "POST",
        "/models",
        token,
        {
            "name": f"B7稳定性-{stamp}",
            "kind": "CHAT",
            "supplier": "LOCAL_INFERENCE",
            "baseModel": os.environ.get("AIP_CHAT_BASE_MODEL", "qwen2.5:1.5b"),
            "apiBase": os.environ.get("AIP_CHAT_API_BASE", "http://127.0.0.1:11434/v1"),
        },
    )
    model_id = str(data_of(created).get("id") or "")
    if status not in (200, 201) or not model_id:
        raise SystemExit(f"建对话模型失败（{status}）：{str(created)[:300]}")

    call("POST", f"/models/{model_id}/load", token, {})
    deadline = time.time() + 120
    while time.time() < deadline:
        time.sleep(1)
        _, body = call("GET", "/models?kind=CHAT&size=50", token)
        for row in data_of(body).get("list") or []:
            if str(row.get("id")) == model_id and row.get("status") == "LOADED":
                return model_id
    raise SystemExit("模型 120s 内没到 LOADED ⇒ 环境没准备好，本跑不算读数")


def create_app(token: str, model_id: str) -> str:
    stamp = int(time.time())
    status, body = call(
        "POST",
        "/assistants",
        token,
        {
            "name": f"B7稳定性-{stamp}",
            "icon": "🧪",
            "description": "B7 连续提问闸门的夹具",
            "type": "SIMPLE",
            "modelId": model_id,
            "kbIds": [],
            "rolePrompt": "你是稳定性压测的应答方，请简短回答。",
            "promptIds": [],
            "retrieval": {
                "mode": "HYBRID",
                "topK": 5,
                "threshold": 0,
                # ★★ 2026-10-28 实测（`#225`）：不绑知识库时默认策略是 REFUSE，
                #   整条链**根本不调上游模型**（done 帧 tokens 全 0、65 字拒答话术）
                #   ⇒ 那样 200 次"稳"是拒答路径的稳。FREE_ANSWER 才真的走生成。
                "noRefStrategy": "FREE_ANSWER",
            },
            "multiTurn": True,
            "thinkingEnabled": False,
            "prologue": "",
            "quickQuestions": [],
        },
    )
    app_id = str(data_of(body).get("id") or "")
    if status not in (200, 201) or not app_id:
        raise SystemExit(f"建应用失败（{status}）：{str(body)[:300]}")
    return app_id


def create_conversation(token: str, app_id: str) -> str:
    status, body = call("POST", "/conversations", token, {"appId": app_id})
    conversation_id = str(data_of(body).get("id") or "")
    if status not in (200, 201) or not conversation_id:
        raise SystemExit(f"建会话失败（{status}）：{str(body)[:300]}")
    return conversation_id


def ask_once(token: str, app_id: str, conversation_id: str, question: str) -> dict:
    """发一次提问，**边收边记**四个面的读数（不抛异常：错也记在读数里，由上层分类计数）。"""
    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        "Authorization": f"Bearer {token}",
    }
    req = urllib.request.Request(
        API + "/conversations/chat",
        data=json.dumps(
            {"appId": app_id, "conversationId": conversation_id, "question": question}
        ).encode("utf-8"),
        method="POST",
        headers=headers,
    )
    reading: dict = {
        "http": -1,
        "error": "",
        "done_status": "",
        "chars": 0,
        "completion_tokens": 0,
        "seconds": 0.0,
        "tail": "",
    }
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=int(B5_LIMIT) + 60) as resp:
            reading["http"] = resp.status
            for raw in resp:
                line = raw.decode("utf-8", errors="replace").strip()
                if not line.startswith("data:"):
                    continue
                payload = line[5:].strip()
                if not payload or payload == "[DONE]":
                    continue
                try:
                    frame = json.loads(payload)
                except json.JSONDecodeError:
                    continue
                reading["tail"] = (reading["tail"] + payload)[-400:]
                if frame.get("error"):
                    reading["error"] = str(frame["error"])[:300]
                text = frame.get("text")
                if isinstance(text, str):
                    reading["chars"] += len(text)
                # done 帧的识别与 S3/B4B5 闸门同口径（status + messageId 同时出现）
                if "status" in frame and "messageId" in frame:
                    reading["done_status"] = str(frame.get("status"))
                    tokens = frame.get("tokens") or {}
                    reading["completion_tokens"] = int(tokens.get("completion") or 0)
    except urllib.error.HTTPError as e:
        reading["http"] = e.code
        reading["error"] = e.read().decode("utf-8", errors="replace")[:300]
    except Exception as e:  # noqa: BLE001
        reading["error"] = f"{type(e).__name__}: {e}"
    reading["seconds"] = time.perf_counter() - started
    return reading


def failure_kind(reading: dict) -> str:
    """把一次读数归成"没坏"或**四种面之一**（分开计数，红了知道坏在哪一面）。"""
    if reading["http"] != 200:
        return f"HTTP {reading['http']}"
    if reading["error"]:
        return "error 帧"
    # done 帧没到也算坏：那是"流断了"，只是没抛异常
    if reading["done_status"] != "GENERATED":
        return f"终态 {reading['done_status'] or '缺失'}"
    if reading["chars"] == 0:
        return "正文为空"
    return ""


def run_rounds(
    rounds: int,
    ask,
    relogin,
    token: str,
    started: float,
    pause: float = 0.0,
    reauth: bool = True,
) -> tuple[str, int, dict, list, list, list]:
    """跑 N 轮，返回 (最后的 token, 续签次数, 失败分类, 失败样本, 耗时, 读数)。

    ★ 为什么把这段抽成函数（`#226` 判据 ②）：续签逻辑埋在 `main()` 里，就**只能靠一次真模型长跑**
      才能证明它工作 —— 而那条长跑要 30 分钟、要真模型，于是"会不会续签"实际上从没被验过。
      抽出来之后 `--self-check` 用假 `ask` 就能把三条语义都跑一遍（含"不续签时会红"的反向用法）。

    ★★ 401 的语义（`#226`）：`access-ttl` 30 分钟，200 轮真模型实测 27–31 分钟 ⇒ 长跑**一定**会跨过它。
      第一次跑到 #124 起连续 77 条 401 就是这个。所以：
      - 续签**前**的 401 ⇒ 续签并重试该轮，不计失败（那是量具的问题）；
      - 续签**后**仍 401 ⇒ 照实计失败（不许被续签掩盖，否则修法本身变成新的遮蔽）；
      - `reauth=False` 只给自检用：它必须是"401 计失败"的那条反向读数。
    """
    failures: dict[str, int] = {}
    failing_rounds: list[str] = []
    durations: list[float] = []
    readings: list[dict] = []
    reauths = 0

    for index in range(1, rounds + 1):
        question = f"第 {index} 问：请用一句话回答「{index} 加一等于几」。"
        reading = ask(question, token)
        if reading["http"] == 401 and reauth:
            token = relogin()
            reauths += 1
            reading = ask(question, token)
        durations.append(reading["seconds"])
        readings.append(reading)
        kind = failure_kind(reading)
        if kind:
            failures[kind] = failures.get(kind, 0) + 1
            if len(failing_rounds) < 10:
                failing_rounds.append(
                    f"#{index} {kind} · {reading['seconds']:.2f}s · "
                    f"error={reading['error'][:150]!r} · tail={reading['tail'][-200:]!r}"
                )
        if index % 20 == 0 or index == rounds:
            elapsed = time.perf_counter() - started
            ok_count = index - sum(failures.values())
            print(
                f"  … {index}/{rounds} 轮 · 成功 {ok_count} · 失败 {index - ok_count} · "
                f"已用 {elapsed:.0f}s（均 {elapsed / index:.2f}s/轮）",
                flush=True,
            )
        if pause:
            time.sleep(pause)

    return token, reauths, failures, failing_rounds, durations, readings


def _reading(http: int = 200, status: str = "GENERATED", chars: int = 20, tokens: int = 12) -> dict:
    return {
        "http": http,
        "error": "",
        "tail": "",
        "chars": chars,
        "done_status": status,
        "completion_tokens": tokens,
        "seconds": 0.01,
    }


def self_check() -> int:
    """`--self-check`：离线验续签语义（`#226` 判据 ②③）—— 不需要真模型、不需要真栈。"""
    failures = 0

    def case(name: str, ok: bool, detail: str = "") -> None:
        nonlocal failures
        print(f"  {'✓' if ok else '✗'} {name}{('' if ok else '：' + detail)}")
        if not ok:
            failures += 1

    # ① 没有 401 ⇒ 一次都不续签、零失败
    _, reauths, fails, _, _, _ = run_rounds(
        5, lambda q, t: _reading(), lambda: "tok", "tok", time.perf_counter())
    case("全程 200 ⇒ 续签 0 次、零失败", reauths == 0 and not fails, f"reauths={reauths} fails={fails}")

    # ② 第 3 轮 401 ⇒ 续签一次并重试该轮，不计失败
    calls = {"n": 0}

    def ask_with_expiry(q, t):
        calls["n"] += 1
        return _reading(http=401) if calls["n"] == 3 else _reading()

    _, reauths, fails, _, _, readings = run_rounds(
        5, ask_with_expiry, lambda: "tok2", "tok", time.perf_counter())
    case("401 一次 ⇒ 续签 1 次、零失败", reauths == 1 and not fails, f"reauths={reauths} fails={fails}")
    case("续签后那一轮算成功（读数 5 条且都是 200）",
         len(readings) == 5 and all(r["http"] == 200 for r in readings), f"readings={len(readings)}")

    # ③ 续签之后仍然 401 ⇒ 必须计失败（不许被续签掩盖）
    def ask_always_401(q, t):
        return _reading(http=401)

    _, reauths, fails, _, _, _ = run_rounds(
        3, ask_always_401, lambda: "tok2", "tok", time.perf_counter())
    case("续签后仍 401 ⇒ 每一轮都计失败", fails.get("HTTP 401") == 3, f"fails={fails}")

    # ④ 反向用法：不续签时 401 必须计失败 —— 证明上面那条绿的是"续签"，不是"脚本永远绿"
    _, reauths, fails, _, _, _ = run_rounds(
        2, ask_always_401, lambda: "tok2", "tok", time.perf_counter(), reauth=False)
    case("关掉续签 ⇒ 401 立刻计失败（这条绿说明闸门量的不是空）",
         reauths == 0 and fails.get("HTTP 401") == 2, f"reauths={reauths} fails={fails}")

    print("")
    print("自检：" + ("通过" if failures == 0 else f"{failures} 条不过"))
    return 0 if failures == 0 else 1


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, int(round(ratio * (len(ordered) - 1))))
    return ordered[index]


def median(values: list[float]) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / 2


def log_offset() -> int:
    if not LOG_PATH:
        return 0
    try:
        return os.path.getsize(LOG_PATH)
    except OSError:
        return 0


def scan_log(offset: int) -> tuple[int, list[str]]:
    """只读**本次跑出来的新增部分**，数 `ERROR` 行（`#211` 那类被吃掉的异常只有这里能看见）。"""
    if not LOG_PATH:
        return 0, []
    try:
        with open(LOG_PATH, "r", encoding="utf-8", errors="replace") as fh:
            fh.seek(offset)
            lines = [line.rstrip() for line in fh if line.strip()]
    except OSError as e:
        return -1, [f"读不到日志（{type(e).__name__}: {e}）"]
    bad = [line for line in lines if "ERROR" in line or "Exception" in line]
    return len(bad), bad


def main() -> int:
    print(f"== B7 真栈闸门：连续 {ROUNDS} 次提问，0 次未处理异常（{BASE}）==")
    if LOG_PATH:
        print(f"  · 日志面：{LOG_PATH}（只读本次新增部分）")
    else:
        print("  · 日志面：**未提供 AIP_B7_LOG ⇒ 本跑只断响应面，不声称查过日志**")

    token = login()
    model_id = ensure_chat_model(token)
    app_id = ""
    conversation_id = ""
    offset = log_offset()
    try:
        app_id = create_app(token, model_id)
        conversation_id = create_conversation(token, app_id)
        note(f"夹具：应用 {app_id} · 会话 {conversation_id} · 模型 {model_id}")

        started = time.perf_counter()

        token, reauths, failures, failing_rounds, durations, readings = run_rounds(
            ROUNDS,
            ask=lambda question, tok: ask_once(tok, app_id, conversation_id, question),
            relogin=login,
            token=token,
            started=started,
            pause=PAUSE,
        )

        total_seconds = time.perf_counter() - started
        failed_total = sum(failures.values())

        # ── 判据 1：一条都不能坏（四个面分开报）──
        detail = " · ".join(f"{kind} ×{count}" for kind, count in sorted(failures.items()))
        check(
            failed_total == 0,
            f"**{ROUNDS} 次提问 0 次未处理异常**（实得 {failed_total} 次{('：' + detail) if detail else ''}）",
        )
        for line in failing_rounds:
            note(f"[失败样本] {line}")

        # ── 判据 1b：这一跑真的调到了上游模型吗（2026-10-28 新增，`#225`）──
        #   不绑知识库 + 默认 REFUSE 策略时，整条链只回一句 65 字拒答话术，
        #   `tokens.completion` 恒为 0 —— 那样 200 次"稳"是拒答路径的稳，不是模型链路的稳。
        #   只看**没坏**的那些轮：坏轮本来就没有 tokens，混进来会把"1 次 500"重复计成两条红。
        generated = [
            r["completion_tokens"] for r in readings if not failure_kind(r)
        ]
        check(
            bool(generated) and all(value > 0 for value in generated),
            f"没坏的那些轮都真的生成了正文（completion tokens > 0，最小 "
            f"{min(generated) if generated else 0}，样本 {len(generated)} 轮）"
            f"—— 全为 0 说明只走到拒答路径",
        )

        # ── 判据 2：耗时 p95（max 只打印，理由见文件头）──
        p95 = percentile(durations, 0.95)
        observe_max = max(durations) if durations else 0.0
        early = median(durations[:10])
        late = median(durations[-10:])
        note(
            f"耗时：p50 {median(durations):.2f}s · p95 {p95:.2f}s · max {observe_max:.2f}s · "
            f"总 {total_seconds:.0f}s"
        )
        note(
            f"令牌续签 {reauths} 次（access-ttl 30m，200 轮会跨过它；续签那一轮重试后不计失败）"
        )
        note(
            f"退化读数：首 10 轮中位 {early:.2f}s → 末 10 轮中位 {late:.2f}s"
            f"（{late / early:.2f}×）—— 越跑越慢本身是一条发现，别被 p95 绿盖住"
        )
        check(p95 <= B5_LIMIT, f"p95 ≤ B5 上限 {B5_LIMIT:g}s（实得 {p95:.2f}s）")

        # ── 判据 3：日志面（可选）──
        if LOG_PATH:
            errors, lines = scan_log(offset)
            if errors < 0:
                check(False, lines[0])
            else:
                check(errors == 0, f"后端日志新增部分 0 条 ERROR/Exception（实得 {errors} 条）")
                for line in lines[:10]:
                    note(f"[日志] {line[:200]}")
        else:
            note("日志面未断（没有 AIP_B7_LOG）—— 这一格不算读过")

        # ── 旁证：历史真的写进去了 ──
        # ★ 只在零失败时断：有失败时少一条助手消息是**预期**，拿它去红会把主判据的两个原因混成一个。
        if failed_total == 0:
            status, body = call("GET", f"/conversations/{conversation_id}", token)
            turn_count = int(data_of(body).get("turnCount") or 0)
            check(
                turn_count == 2 * ROUNDS,
                f"会话 turnCount == 2×{ROUNDS} = {2 * ROUNDS}（实际 {turn_count}）—— 历史确实写进去了",
            )
        else:
            note("有失败 ⇒ 跳过 turnCount 断言（少了助手消息是预期，避免把两个原因混成一个）")
    finally:
        if conversation_id:
            call("DELETE", f"/conversations/{conversation_id}", token)
        if app_id:
            call("DELETE", f"/assistants/{app_id}", token)

    if problems:
        print(f"\n结果：红 —— {len(problems)} 条")
        for item in problems:
            print(f"  [FAIL] {item}")
        return 1
    print("\n结果：绿 —— B7 有读数且达标")
    return 0


if __name__ == "__main__":
    if "--self-check" in sys.argv[1:]:
        sys.exit(self_check())
    sys.exit(main())
