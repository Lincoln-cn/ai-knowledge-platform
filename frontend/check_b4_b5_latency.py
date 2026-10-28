"""B4 / B5 的真栈读数 —— **首字延迟 ≤10s** 与 **完整响应 ≤60s**（`ROADMAP §2.3`）。

━━━ 为什么这两条在同一个脚本里 ━━━
它们是**同一次请求上的两个时间戳**（首个正文帧 / done 帧）。拆成两条脚本要跑两遍同样的
建模型 + 建应用夹具，而且分开跑时「B4 好而 B5 差」这种形态没人解释得了。

━━━ 三条口径（它们决定这个数字有没有意义）━━━
① **t0 取在 `urlopen()` 之前**：连接 + 鉴权 + 检索 + 上游首帧全算进去。用户感知的首字延迟
   就是这一段；把 t0 挪到"收到第一个字节之后"会把连接与检索的时间藏起来，读数会虚好。
② **首字只认正文帧**（`{"text": ...}` 非空）。S3 的读数里 `{"refs": [...]}` 本来就先于正文
   （`refs@1 < text@2`），把引用帧算成首字，B4 立刻虚好 —— 那一帧跟"用户看到字"无关。
③ **只认终态成功的请求**：done 帧 `status` 必须是 `GENERATED`。否则"2 秒就失败"会变成一份
   漂亮的首字延迟读数 —— 快，但是错的。

━━━ 判据（取**最差一轮**，不取中位数）━━━
`§二` 写的是"首字延迟 ≤10s"，是一个**上限**，不是"平均下来差不多"。所以断言用 `max(...)`；
中位数只打印出来做对照（它解释"抖"还是"慢"）。
第 1 轮是**冷启动**（模型加载 / 连接池 / JIT），单独标出来但不豁免 —— 用户第一次提问也算数。

用法：
    python check_b4_b5_latency.py                      # 默认 http://localhost:5280
    python check_b4_b5_latency.py http://127.0.0.1:5280
    AIP_B4_LIMIT=10 AIP_B5_LIMIT=60 AIP_B4B5_ROUNDS=3 python check_b4_b5_latency.py
退出码：0 = 两条都达标；1 = 有红（或环境起不来，见下）。

★ 环境起不来（登录失败 / 没有对话模型）时**退出码也是 1**，消息里写明是哪一步 ——
  "没跑起来"和"跑起来不达标"在读数上必须能分开，但它们都不该被记成绿。
"""

from __future__ import annotations

import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"
# ★ 上游地址不再写死"桩"：夹具现在按"这条模型的上游 `/v1/models` 里是否真有 baseModel"选模型，
#   所以 `11434` 上现在是桩还是 Ollama 都不影响判据（`#225`）。

USER = "admin"
PASSWORD = "admin"

B4_LIMIT = float(os.environ.get("AIP_B4_LIMIT", "10"))
B5_LIMIT = float(os.environ.get("AIP_B5_LIMIT", "60"))
ROUNDS = int(os.environ.get("AIP_B4B5_ROUNDS", "3"))

#: 三个不同的问题，避免命中任何缓存 —— 同一句话重复问，第 2 轮起测的可能是缓存而不是链路。
QUESTIONS = (
    "报销标准是多少？",
    "巡检频次是怎么规定的？",
    "交接记录要保留多久？",
)

problems: list[str] = []


def check(ok: bool, message: str) -> None:
    if ok:
        print(f"  ✓ {message}")
    else:
        print(f"  ✗ {message}")
        problems.append(message)


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
        raise SystemExit(f"登录失败（{status}）：{str(body)[:200]} —— 真栈没起或账号不对，本跑不算读数")
    return str(token)


def _served_models() -> set[str]:
    """默认上游（`AIP_CHAT_API_BASE`）的 `/v1/models` 里实际有哪些模型名（探不到就空集）。

    ★ 为什么不用行里的 `apiBase`：模型的**列表接口不返回 apiBase**（实测一条为
      `{id,name,kind,supplier,baseModel,status,contextWindow,dimension,thinking,tools,builtin,remark,legacyDirect}`），
      所以只能问"默认上游到底服务哪些模型"。`AIP_CHAT_API_BASE` 与后端
      `spring.ai.openai.base-url` 是同一个上游（默认 `http://127.0.0.1:11434/v1`）。
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


def ensure_chat_model(token: str) -> tuple[str, bool]:
    """取一个**上游真的能服务**且已加载的对话模型；没有就建一个并等它 LOADED。

    ★ 为什么不能"没有就跳过"：没加载的模型发不出流式响应，于是第一轮 500 会被误读成
      "流式坏了"。这条兜底与 `check_s3_conversation_chain_e2e.py:277` 同口径。

    ★★ 2026-10-28 实测（`#225`）：也**不能"随便取第一个 LOADED"** ——
      ① 先量到的是 `qwen2.5-1.5b-本地`（Ollama），而当时 11434 是桩 ⇒ `404 模型不存在`；
      ② 换成"只认桩"之后又反过来 —— 桩一撤、Ollama 上 11434，判据全部落空。
      ⇒ **既不能认"桩"也不能认"Ollama"，只能认"这条记录的上游 `/v1/models` 里真的有它的
      baseModel"**（`_served_models` + `_row_serves`）—— 这是唯一与"谁在服务 11434"无关的判据。
    """
    _, body = call("GET", "/models?kind=CHAT&size=50", token)
    rows = data_of(body).get("list") or []
    for row in rows:
        if row.get("status") == "LOADED" and _row_serves(row):
            return str(row["id"]), False

    # 没有可服务的已加载模型 ⇒ 按环境变量建一个（默认 Ollama 的 OpenAI 兼容口）
    api_base = os.environ.get("AIP_CHAT_API_BASE", "http://127.0.0.1:11434/v1")
    base_model = os.environ.get("AIP_CHAT_BASE_MODEL", "qwen2.5:1.5b")
    status, body = call(
        "POST",
        "/models",
        token,
        {
            "name": f"B4B5-对话-{int(time.time())}",
            "kind": "CHAT",
            "supplier": "LOCAL_INFERENCE",
            "baseModel": base_model,
            "apiBase": api_base,
        },
    )
    model_id = str(data_of(body).get("id") or "")
    if status not in (200, 201) or not model_id:
        raise SystemExit(f"建对话模型失败（{status}）：{str(body)[:300]}")
    created = True

    status, body = call("POST", f"/models/{model_id}/load", token, {})
    if status not in (200, 202):
        raise SystemExit(f"加载对话模型失败（{status}）：{str(body)[:300]}")
    deadline = time.time() + 120
    while time.time() < deadline:
        _, body = call("GET", "/models?kind=CHAT&size=50", token)
        for row in data_of(body).get("list") or []:
            if str(row.get("id")) == model_id and row.get("status") == "LOADED":
                return model_id, created
        time.sleep(0.5)
    raise SystemExit(f"对话模型 {model_id} 在 120s 内没变成 LOADED —— 本跑不算读数")


def create_app(token: str, model_id: str) -> str:
    status, body = call(
        "POST",
        "/assistants",
        token,
        {
            "name": f"B4B5 延迟闸门-{int(time.time())}",
            "icon": "⏱",
            "description": "B4/B5 延迟闸门用的临时应用（闸门自己删）",
            "type": "SIMPLE",
            "modelId": model_id,
            "kbIds": [],
            "rolePrompt": "你是知识助理，请简洁回答。",
            "promptIds": [],
            "retrieval": {
                "mode": "HYBRID",
                "topK": 5,
                "threshold": 0,
                # ★★ 2026-10-28 实测（`#225`）：夹具不绑知识库时，默认策略是 **REFUSE**，
                #   于是整条链**根本不调上游模型**，只回一句 65 字的拒答话术
                #   （done 帧 `tokens` 全是 0、耗时 17ms）⇒ 那样量出来的"首字 0.04s"
                #   是拒答路径的速度，不是模型首字延迟。改成 FREE_ANSWER 才真的调模型。
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


def measure_once(token: str, app_id: str, question: str) -> dict:
    """发一次提问，**边收边记**两个时间戳。返回一份读数（不抛异常，错也记在读数里）。"""
    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        "Authorization": f"Bearer {token}",
    }
    req = urllib.request.Request(
        API + "/conversations/chat",
        data=json.dumps({"appId": app_id, "question": question}).encode("utf-8"),
        method="POST",
        headers=headers,
    )
    reading: dict = {
        "http": -1,
        "first_text": None,
        "done": None,
        "done_status": "",
        "chars": 0,
        "completion_tokens": 0,
        "error": "",
    }
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=int(B5_LIMIT) + 30) as resp:
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
                if frame.get("error"):
                    reading["error"] = str(frame["error"])[:200]
                text = frame.get("text")
                if isinstance(text, str):
                    if text.strip():
                        reading["chars"] += len(text)
                        if reading["first_text"] is None:
                            reading["first_text"] = time.perf_counter() - started
                # done 帧的识别与 S3 闸门同口径（status + messageId 同时出现）
                if "status" in frame and "messageId" in frame:
                    reading["done"] = time.perf_counter() - started
                    reading["done_status"] = str(frame.get("status"))
                    tokens = frame.get("tokens") or {}
                    reading["completion_tokens"] = int(tokens.get("completion") or 0)
    except urllib.error.HTTPError as e:
        reading["http"] = e.code
        reading["error"] = e.read().decode("utf-8", errors="replace")[:200]
    except Exception as e:  # noqa: BLE001
        reading["error"] = f"{type(e).__name__}: {e}"
    return reading


def fmt(value: float | None) -> str:
    return "—" if value is None else f"{value:.2f}s"


def main() -> int:
    print(f"== B4/B5 真栈闸门：首字 ≤{B4_LIMIT:g}s · 完整响应 ≤{B5_LIMIT:g}s（{BASE}）==")

    token = login()
    model_id, created_model = ensure_chat_model(token)
    app_id = ""
    try:
        app_id = create_app(token, model_id)
        print(f"  夹具：应用 {app_id} · 模型 {model_id}")

        rounds: list[dict] = []
        for index in range(ROUNDS + 1):
            question = QUESTIONS[index % len(QUESTIONS)]
            reading = measure_once(token, app_id, question)
            reading["label"] = "冷启动" if index == 0 else f"第 {index} 轮"
            rounds.append(reading)
            print(
                f"  · {reading['label']}：http={reading['http']} "
                f"首字={fmt(reading['first_text'])} 完整={fmt(reading['done'])} "
                f"终态={reading['done_status'] or '—'} 正文={reading['chars']} 字"
                + (f" error={reading['error']}" if reading["error"] else "")
            )

        # ── 判据 ⓪：这一跑到底测到了东西没有（先立这条，否则"没数据"会被后面的 max() 吃掉）──
        check(
            all(r["http"] == 200 for r in rounds),
            f"⓪ 每一轮都是 200（实得 {[r['http'] for r in rounds]}）",
        )
        succeeded = [r for r in rounds if r["done_status"] == "GENERATED"]
        check(
            len(succeeded) == len(rounds),
            f"⓪b 每一轮终态 GENERATED（实得 {[r['done_status'] or '—' for r in rounds]}）"
            f" —— 失败得快不是延迟好",
        )
        # ── 判据 ⓪d：这一跑真的调到了上游模型吗（2026-10-28 新增，`#225`）──
        #   拒答路径也满足 ⓪/⓪b/⓪c（200 + GENERATED + 65 字正文），但 `tokens.completion` 恒为 0
        #   ⇒ 那三个绿加起来仍然量的是一个**没调模型**的链路。这一条是"读数有效"的唯一凭据。
        check(
            all(r["completion_tokens"] > 0 for r in rounds),
            f"⓪d 每一轮都真的生成了正文（completion tokens > 0，实得 "
            f"{[r['completion_tokens'] for r in rounds]}）—— 全为 0 说明只走到拒答路径",
        )
        check(
            all(r["chars"] > 0 for r in rounds),
            f"⓪c 每一轮都有正文（实得 {[r['chars'] for r in rounds]} 字）",
        )

        # ── B4 ──
        firsts = [r["first_text"] for r in rounds if r["first_text"] is not None]
        if firsts:
            worst = max(firsts)
            median = statistics.median(firsts)
            check(
                worst <= B4_LIMIT,
                f"**B4 首字延迟 ≤{B4_LIMIT:g}s**（最差 {worst:.2f}s · 中位 {median:.2f}s · "
                f"全部 {[round(v, 2) for v in firsts]}）",
            )
        else:
            check(False, "**B4 首字延迟**：一帧正文都没收到 ⇒ 这条读数不存在（不是 0s）")

        # ── B5 ──
        dones = [r["done"] for r in rounds if r["done"] is not None]
        if dones:
            worst_done = max(dones)
            median_done = statistics.median(dones)
            check(
                worst_done <= B5_LIMIT,
                f"**B5 完整响应 ≤{B5_LIMIT:g}s**（最差 {worst_done:.2f}s · 中位 {median_done:.2f}s · "
                f"全部 {[round(v, 2) for v in dones]}）",
            )
        else:
            check(False, "**B5 完整响应**：一帧 done 都没收到 ⇒ 这条读数不存在（不是 0s）")
    finally:
        # ★ 顺序与创建相反；失败也要删，否则同一个库跑三遍就有三套夹具（本 job 顺序跑闸门）
        if app_id:
            call("DELETE", f"/assistants/{app_id}", token)
        if created_model:
            call("DELETE", f"/models/{model_id}", token)

    if problems:
        print(f"\n结果：红 —— {len(problems)} 条")
        for item in problems:
            print(f"  [FAIL] {item}")
        return 1
    print("\n结果：绿 —— B4/B5 两条都有读数且达标")
    return 0


if __name__ == "__main__":
    sys.exit(main())
