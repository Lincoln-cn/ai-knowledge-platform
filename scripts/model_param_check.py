"""可调参数「真的进上游请求体」的一条命令验收（ROADMAP §11.65.3 ③c / §11.65.7 未验项）。

跑法：
    python scripts/model_param_check.py
    python scripts/model_param_check.py --model <chatModelId> --app <appId>
    python scripts/model_param_check.py --stub http://localhost:11434

## 它验的是哪件事

`ROADMAP §11.65.7` 里唯一剩下的「未验项」是：**模型参数真的进上游请求体**。
此前只有单测证据（`ModelParamUpstreamTest` 起一个 HttpServer 桩抓请求体）。
单测证明的是"这一层的代码拼对了"，不证明"真栈上一条真实的编排执行会把它带出去" ——
中间还隔着节点配置、图执行、适配器选择（Spring AI 分支 / 兜底分支）三处。

这个脚本用**真实的编排执行**去验，并且用**桩服务收到的原始请求体**作为判据：

1. 给一个 CHAT 模型声明两条参数（`temperature` 是官方键，`top_k` 是非官方键）。
2. 跑一张最小图 `START → AI 对话 → 回复`，节点的 `modelParams` 填 `temperature=0.37 / top_k=41`。
3. 从 `GET /__stub/requests` 里取那次 `chat/completions` 的**请求体原文**，断言：
   - `temperature == 0.37`（官方键覆盖了硬编码的默认 0.2）
   - `top_k == 41`（非官方键原样透传 —— 不是白名单放行）
4. 再跑一次**不带** `modelParams` 的图，断言 `temperature == 0.2` 且**没有** `top_k`。
   —— 这一条是反证：如果两次读数一样，"参数生效"就是错觉（比如参数根本没被读，而默认值恰好等于它）。

## 为什么要重置桩的记录

`/__stub/requests` 是**累积**的，里面还有检索、向量化、记忆抽取等其它调用。
每段开始前 `POST /__stub/reset`，然后只找这一段之后出现的 `chat/completions` ——
否则"找到一条带 temperature 的请求"可能来自上一轮或别的用例。

## 为什么用 `workflow/debug` 而不是正式会话

`modelParams` 只存在于**节点配置**里（AI 对话节点），正式对话链路从应用配置读模型，
不经过节点。所以要验参数透传，必须跑一次图。
`POST /assistants/{appId}/workflow/debug` 直接执行图、不需要先发布，是这条链最短的路。

## 前置

真栈：后端 18088（`CAPTCHA_ENABLED=false`）· 桩 11434（`backend/tools/openai_stub.py`）·
PG 15433。不需要前端。

## 退出码

0 = 全过；1 = 有失败；2 = 前置不满足（后端没起 / 桩没起 / 找不到模型或应用），
这时**不要**把它读成"参数没透传" —— 是链路没跑起来。
"""

import argparse
import json
import sys
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:18088/api/v1"
STUB = "http://localhost:11434"
TIMEOUT = 60

# 默认用一个桩模型（`stub-plain`）。★ 必须是**桩**：这个脚本要从桩侧读请求体，
# 真模型的上游不会告诉你它收到了什么。
DEFAULT_MODEL = "10ba5063-c0aa-4423-8d50-391f8b7ac4c5"
# 默认用 e2e 已经在用的那个编排应用；`--app` 可以换。
DEFAULT_APP = "30fc12bd-5dd8-460f-a8c7-5bf4dacd2721"

TEMP_DECLARED = 0.37
TOPK_DECLARED = 41
DEFAULT_TEMPERATURE = 0.2  # NativeSpringAiChatModel.payload() 里硬编码的那个值

PARAM_SCHEMA = json.dumps(
    [
        {
            "key": "temperature",
            "label": "温度",
            "type": "number",
            "default": DEFAULT_TEMPERATURE,
            "min": 0,
            "max": 2,
            "help": "越高越发散",
        },
        {
            "key": "top_k",
            "label": "top_k",
            "type": "integer",
            "default": 40,
            "min": 1,
            "max": 100,
            "help": "非官方键，原样透传给上游",
        },
    ],
    ensure_ascii=False,
)

results = []


def record(name, ok, detail=""):
    results.append({"name": name, "ok": ok, "detail": detail})
    print(("PASS  " if ok else "FAIL  ") + name + (("  — " + detail) if detail else ""))


def skip(name, detail=""):
    results.append({"name": name, "ok": None, "detail": detail})
    print("SKIP  " + name + (("  — " + detail) if detail else ""))


def call(path, token=None, method="GET", body=None, base=BASE, timeout=TIMEOUT):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except urllib.error.URLError as e:
        return 0, "连不上 %s：%s" % (base + path, e.reason)


def login():
    status, text = call(
        "/auth/login", method="POST", body={"username": "admin", "password": "admin"}
    )
    if status != 200:
        return None, "登录失败：HTTP %s · %s" % (status, text[:200])
    try:
        return json.loads(text)["data"]["accessToken"], None
    except (KeyError, TypeError, ValueError):
        return None, "登录响应形状不对：%s" % text[:200]


def stub_reset():
    status, text = call("/__stub/reset", method="POST", base=STUB, body={})
    return status in (200, 204), "HTTP %s · %s" % (status, text[:120])


def stub_chat_bodies():
    """自上次 reset 之后，桩收到的 chat/completions 请求体（保持到达顺序）。"""
    status, text = call("/__stub/requests", base=STUB)
    if status != 200:
        return None, "读桩记录失败：HTTP %s · %s" % (status, text[:160])
    try:
        payload = json.loads(text)
    except ValueError:
        return None, "桩记录不是 JSON：%s" % text[:160]
    items = payload.get("requests") if isinstance(payload, dict) else payload
    bodies = []
    for item in items or []:
        path = (item or {}).get("path") or ""
        if "chat/completions" not in path:
            continue
        body = item.get("body")
        if isinstance(body, dict):
            bodies.append(body)
    return bodies, None


def graph(model_id, model_params):
    """START → AI 对话 → 回复。`model_params` 为 None 时不带这个键（对照组的形状）。"""
    ai_config = {
        "modelId": model_id,
        "rolePrompt": "只回一句话。",
        "promptIds": [],
        "kbIds": [],
        "thinkingEnabled": False,
        "multiTurn": False,
        "asResult": True,
    }
    if model_params is not None:
        ai_config["modelParams"] = model_params
    return {
        "nodes": [
            {"id": "n-start", "type": "START_NODE", "title": "开始", "position": {"x": 60, "y": 200}, "config": {}},
            {"id": "n-ai", "type": "AI_CHAT_NODE", "title": "AI 对话", "position": {"x": 260, "y": 200}, "config": ai_config},
            {
                "id": "n-reply",
                "type": "REPLY_NODE",
                "title": "回复",
                "position": {"x": 480, "y": 200},
                # 回复节点的 content 是字面量（`ReplyNodeExecutor` 不解析 `{{…}}`），
                # 所以这里不写「回显 AI 输出」那种看起来像插值的东西 —— 那会让人以为
                # 模板语法存在。判据在上游请求体里，不依赖最终输出长什么样。
                "config": {"content": "（AI 节点的回答见节点轨迹）", "replyType": "content", "asResult": True},
            },
        ],
        "edges": [
            {"id": "e1", "source": "n-start", "target": "n-ai"},
            {"id": "e2", "source": "n-ai", "target": "n-reply"},
        ],
    }


def run_graph(token, app_id, model_id, model_params):
    status, text = call(
        "/assistants/%s/workflow/debug" % app_id,
        token=token,
        method="POST",
        body={"graph": graph(model_id, model_params), "inputs": {"entry": "说明一下变更流程"}},
    )
    if status != 200:
        return None, "调试接口 HTTP %s · %s" % (status, text[:240])
    try:
        data = json.loads(text).get("data") or {}
    except ValueError:
        return None, "调试接口返回不是 JSON：%s" % text[:240]
    if data.get("ok") is not True:
        return None, "图没跑通：errors=%s" % (data.get("errors") or "(无)")
    return data, None


def read_model(token, model_id):
    status, text = call("/models/%s" % model_id, token=token)
    if status != 200:
        return None, "读模型 HTTP %s · %s" % (status, text[:200])
    try:
        return json.loads(text)["data"], None
    except (KeyError, TypeError, ValueError):
        return None, "模型响应形状不对：%s" % text[:200]


def patch_param_schema(token, model, schema):
    """只改 paramSchema，其余字段原样回传 —— PUT 是 upsert，缺字段会被当成"要清空"。"""
    body = {
        "name": model.get("name"),
        "kind": model.get("kind"),
        "supplier": model.get("supplier"),
        "baseModel": model.get("baseModel"),
        "apiBase": model.get("apiBase"),
        "apiKey": None,
        "dimension": model.get("dimension"),
        "contextWindow": model.get("contextWindow"),
        "maxOutput": model.get("maxOutput"),
        "remark": model.get("remark"),
        "paramSchema": schema,
    }
    return call("/models/%s" % model.get("id"), token=token, method="PUT", body=body)


def main():
    global STUB
    parser = argparse.ArgumentParser(description="验可调参数是否真的进上游请求体")
    parser.add_argument("--model", default=DEFAULT_MODEL, help="一个指向桩的 CHAT 模型 id")
    parser.add_argument("--app", default=DEFAULT_APP, help="一个已存在的编排应用 id（只用来跑 debug）")
    parser.add_argument("--stub", default=STUB, help="桩服务地址")
    parser.add_argument("--keep-schema", action="store_true", help="跑完不改回 []（排查用）")
    args = parser.parse_args()

    STUB = args.stub.rstrip("/")

    token, err = login()
    if not token:
        print("[2] 前置不满足：%s" % err)
        print("    后端没起？用 scripts/aip-be-auto.cmd（CAPTCHA_ENABLED=false）。")
        return 2
    print("已登录 admin（%s）" % BASE)

    ok, detail = stub_reset()
    if not ok:
        print("[2] 前置不满足：桩服务不可用 —— %s" % detail)
        print("    桩没起？用 scripts/aip-stub.cmd（或 backend/tools/openai_stub.py --port 11434）。")
        return 2
    print("桩记录已重置（%s）" % args.stub)

    model, err = read_model(token, args.model)
    if model is None:
        print("[2] 前置不满足：%s" % err)
        print("    用 --model 指定一个 CHAT 模型；它必须指向桩，否则读不到请求体。")
        return 2
    if model.get("kind") != "CHAT":
        print("[2] 前置不满足：模型 %s 不是 CHAT（kind=%s）" % (args.model, model.get("kind")))
        return 2
    print("模型：%s（%s · apiBase=%s）" % (model.get("name"), model.get("baseModel"), model.get("apiBase")))

    original_schema = model.get("paramSchema") or "[]"
    status, text = patch_param_schema(token, model, PARAM_SCHEMA)
    if status != 200:
        print("[1] 声明参数失败：HTTP %s · %s" % (status, text[:240]))
        return 1
    record("模型上能声明参数（官方键 + 非官方键）", True, "paramSchema 已写入")

    # ── 第一段：带 modelParams ──────────────────────────────────────────
    ok, detail = stub_reset()
    if not ok:
        print("[1] 重置桩记录失败：%s" % detail)
        return 1
    data, err = run_graph(token, args.app, args.model, {"temperature": TEMP_DECLARED, "top_k": TOPK_DECLARED})
    if data is None:
        print("[1] 带参数的图没跑通：%s" % err)
        print("    应用 id 对吗？用 --app 指定；后端日志里这会有一条错因。")
        return 1
    record("带 modelParams 的图跑通", True, "finalOutput=%r" % (data.get("finalOutput") or "")[:40])

    bodies, err = stub_chat_bodies()
    if bodies is None:
        print("[1] %s" % err)
        return 1
    if not bodies:
        print("[1] 桩没收到任何 chat/completions 请求 —— 图跑通了但没调模型，判据不成立")
        return 1
    body = bodies[-1]
    record(
        "官方键 temperature 进了请求体（覆盖硬编码默认值）",
        body.get("temperature") == TEMP_DECLARED,
        "请求体 temperature=%r（期望 %r）" % (body.get("temperature"), TEMP_DECLARED),
    )
    record(
        "非官方键 top_k 原样透传（不是白名单放行）",
        body.get("top_k") == TOPK_DECLARED,
        "请求体 top_k=%r（期望 %r）" % (body.get("top_k"), TOPK_DECLARED),
    )

    # ── 第二段（反证）：不带 modelParams ─────────────────────────────────
    ok, detail = stub_reset()
    if not ok:
        print("[1] 重置桩记录失败：%s" % detail)
        return 1
    data2, err = run_graph(token, args.app, args.model, None)
    if data2 is None:
        print("[1] 对照组的图没跑通：%s" % err)
        return 1
    bodies2, err = stub_chat_bodies()
    if not bodies2:
        print("[1] 对照组：桩没收到 chat/completions 请求")
        return 1
    body2 = bodies2[-1]
    record(
        "不带参数时回到默认 temperature（反证：两次读数必须不同）",
        body2.get("temperature") == DEFAULT_TEMPERATURE,
        "请求体 temperature=%r（期望默认 %r）" % (body2.get("temperature"), DEFAULT_TEMPERATURE),
    )
    record(
        "不带参数时请求体里没有 top_k（反证）",
        "top_k" not in body2,
        "请求体 keys=%s" % sorted(body2.keys()),
    )

    # ── 清理 ───────────────────────────────────────────────────────────
    if args.keep_schema:
        skip("清理：paramSchema 保留原样（--keep-schema）", "当前仍是声明后的值")
    else:
        status, text = patch_param_schema(token, model, original_schema)
        record(
            "清理：paramSchema 还原",
            status == 200,
            "还原成 %s" % (original_schema[:60] if status == 200 else "HTTP %s · %s" % (status, text[:120])),
        )
    stub_reset()

    passed = sum(1 for r in results if r["ok"] is True)
    failed = [r for r in results if r["ok"] is False]
    print()
    print("汇总：%d 条通过 · %d 条失败" % (passed, len(failed)))
    for r in failed:
        print("  FAIL  %s%s" % (r["name"], ("  — " + r["detail"]) if r["detail"] else ""))
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
