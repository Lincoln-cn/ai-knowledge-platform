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

用法：
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


def ensure_chat_model(token: str) -> str:
    """找一个**已加载**的对话模型；没有就建一个本地推理模型并等它加载完。

    ★ 这一步不能省：没有已加载模型时第一轮会 500，那个 500 会被 B7 记成"上游异常"，
      而它其实只是夹具没准备好。
    """
    _, body = call("GET", "/models?kind=CHAT&size=50", token)
    rows = data_of(body).get("list") or []
    for row in rows:
        if row.get("status") == "LOADED":
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
            "baseModel": "stub-plain",
            "apiBase": os.environ.get("AIP_STUB_URL", "http://127.0.0.1:11434") + "/v1",
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
            "retrieval": {"mode": "HYBRID", "topK": 5, "threshold": 0},
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

        failures: dict[str, int] = {}
        failing_rounds: list[str] = []
        durations: list[float] = []
        started = time.perf_counter()

        for index in range(1, ROUNDS + 1):
            question = f"第 {index} 问：请用一句话回答「{index} 加一等于几」。"
            reading = ask_once(token, app_id, conversation_id, question)
            durations.append(reading["seconds"])
            kind = failure_kind(reading)
            if kind:
                failures[kind] = failures.get(kind, 0) + 1
                if len(failing_rounds) < 10:
                    failing_rounds.append(
                        f"#{index} {kind} · {reading['seconds']:.2f}s · "
                        f"error={reading['error'][:150]!r} · tail={reading['tail'][-200:]!r}"
                    )
            if index % 20 == 0 or index == ROUNDS:
                elapsed = time.perf_counter() - started
                ok_count = index - sum(failures.values())
                print(
                    f"  … {index}/{ROUNDS} 轮 · 成功 {ok_count} · 失败 {index - ok_count} · "
                    f"已用 {elapsed:.0f}s（均 {elapsed / index:.2f}s/轮）",
                    flush=True,
                )
            if PAUSE:
                time.sleep(PAUSE)

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
    sys.exit(main())
