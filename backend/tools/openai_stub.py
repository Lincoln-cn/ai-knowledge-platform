#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
OpenAI 兼容的测试桩服务 —— 开发期替代真模型服务（后端 Tools）。

════════════════════════════════════════════════════════════════════════════
  ★ 它是什么，以及它不是什么（先说清楚，否则迟早会有人拿它的输出当结论）
════════════════════════════════════════════════════════════════════════════

它**是**：
  ① 一个能被 Spring AI 的 openai starter 直接连上的假模型服务 —— 于是 M1（模型注册）、
     M2（向量化）、M4b（流式问答）的代码路径**不必等真模型**就能开发与测试。
  ② ★ 一个**能看见请求体**的观察点。这一点比"能返回假数据"重要得多：
     "Spring AI 到底有没有把 reasoning_effort 发出来"这类问题，答案在**请求体**里，
     而真 Ollama 不会告诉你它收到了什么。`GET /__stub/requests` 就是为此存在的。
  ③ 一个**故障注入器**（延迟 / 错误率），用来跑"失败路径先于成功路径"（ROADMAP §4.7.2 纪律 5）。
  ④ 一个**结构化输出的脚本开关**（`entity_override`）：让"要读几个固定字段"的调用按指定的值回 ——
     多跳判定的难例演示（第 2 轮才拿到答案）靠它，注入类闸门也靠它。
     ★ 它只覆盖**值**，键名仍由 schema 决定 —— 否则就成了"桩在骗过解析器"而不是"模型给了这个值"。

它**不是**：
  ✗ **不是三份技术结论的证据来源**。本桩的"思考三态""结构化输出稳定性"都是**我写的设定**，
    拿它当"模型能力"的实测结果是自欺。真模型的能力仍需在真模型上测。
  ✗ 不是性能基准。它的"响应时间"只反映我 sleep 了多久。

  ⇒ 因此 `docs/技术结论/M0-技术结论.md` 里三份结论**仍然是待实测**，
    本桩能让探针**跑通**（证明探针本身没写错），但填进去的只能是"桩"而不是"模型"。

════════════════════════════════════════════════════════════════════════════
  用法
════════════════════════════════════════════════════════════════════════════

    python tools/openai_stub.py                      # 起在 11434（与 Ollama 同端口）
    python tools/openai_stub.py --port 11434 --delay-ms 50
    python tools/openai_stub.py --fail-rate 0.3      # 注入 30% 的 500，用来测降级/重试

    # 看它收到了什么（★ 这是它最有价值的接口）
    curl http://localhost:11434/__stub/requests

    # 清空记录 / 改行为
    curl -X POST http://localhost:11434/__stub/reset
    curl -X POST -d '{"delay_ms":200}' http://localhost:11434/__stub/config

    # ★ 让**流式**回答按帧慢放（S3 的「生成中停止」需要"吐到一半"的窗口）：
    #   delay_ms 只推迟**整个回答的开始**，正文仍是一次性全到 —— 停在"一半"是不可能的。
    curl -X POST -d '{"chunk_delay_ms":40}' http://localhost:11434/__stub/config
    curl -X POST -d '{"chunk_delay_ms":0}' http://localhost:11434/__stub/config   # 复位

    # ★ 让结构化输出按指定的值回（多跳判定的难例演示、注入类闸门靠它）
    curl -X POST -d '{"entity_override":{"sufficient":false,"nextQuery":"违约金 上限"}}' \
         http://localhost:11434/__stub/config
    curl -X POST -d '{"entity_override":null}' http://localhost:11434/__stub/config   # 取消覆盖

  ★ 端口默认取 11434，是为了让 `application.yml` 的
    `spring.ai.openai.base-url: http://localhost:11434/v1` **一行都不用改** ——
    换真 Ollama 时只是把进程换掉。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import re
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ────────────────────────────── 模型清单与行为设定 ──────────────────────────────

#: 对话模型 → 行为设定 + 能力元数据。
#: ★ thinking 的三个值是我**刻意设定**的，用来让"探测三态"的代码有东西可测；
#:   它们不是任何真实模型的能力。
#: ★ context_window / tools 是能力探测（POST /models/probe）的元数据来源 ——
#:   探测策略是「元数据优先 + 行为探针兜底」，这两项从 /v1/models 元数据读，
#:   思考三态走行为探针（带/不带 reasoning_effort 看是否产出 reasoning）。
CHAT_MODELS = {
    "stub-think-toggleable": {
        "thinking": "TOGGLEABLE",   # 认 reasoning_effort：传 none 就没思考
        "context_window": 32768,
        "tools": "PARALLEL",
    },
    "stub-think-always": {
        "thinking": "ALWAYS_ON",    # 忽略 reasoning_effort，永远有思考
        "context_window": 65536,
        "tools": "SUPPORTED",
    },
    "stub-plain": {
        "thinking": "NONE",         # 从不思考
        "context_window": 8192,
        "tools": "UNSUPPORTED",
    },
}

EMBED_MODEL = "stub-embed"

# ────────────────────────────── 运行状态 ──────────────────────────────

_LOCK = threading.Lock()
_REQUESTS: list[dict] = []
#: `entity_override` 是"把结构化输出的某些**值**改成我指定的"（键名仍由 schema 决定）。
#: 默认 None = 完全按 schema 造占位值（既有测试依赖那些值，别改默认）。
_CONFIG = {"delay_ms": 0, "chunk_delay_ms": 0, "fail_rate": 0.0, "entity_override": None}
_EMBED_DIM = 768

#: 假的 token 计数用：让 usage 字段看起来合理（M1 的用量统计要读它）
_CHARS_PER_TOKEN = 4


# ────────────────────────────── 小工具 ──────────────────────────────


def deterministic_vector(text: str, dim: int) -> list[float]:
    """
    由文本**确定性地**推出一个单位向量。

    ★ 为什么要确定性：向量化的集成测试需要可复现 —— 同一段文本必须每次得到同一个向量，
    否则"入库后检索得到它"这类断言会随机失败。用 sha256 做种子就同时满足了
    "看起来随机（不同文本向量几乎正交）"和"同文本必定相同"。
    """
    seed = int.from_bytes(hashlib.sha256(text.encode("utf-8")).digest()[:8], "big")
    rng = random.Random(seed)
    raw = [rng.gauss(0.0, 1.0) for _ in range(dim)]
    norm = math.sqrt(sum(v * v for v in raw)) or 1.0
    return [round(v / norm, 6) for v in raw]


def _extract_json_object(text: str, start: int) -> str | None:
    """从 `start`（一个 `{` 的下标）起，按括号配平取出一整个 JSON 对象原文。

    ★ 为什么不继续用正则：第 12 周的查询理解要的 schema 是**两层嵌套数组** ——

        {"subQuestions":[{"question":"…","queries":["…"]}]}

    而"无嵌套对象"的正则只能命中到最内层，于是 `subQuestions` 会被认成别的形状。
    括号配平（含字符串内的转义）是唯一能对付任意深度又不误伤 `{` 在字符串里的写法。
    """
    depth = 0
    in_string = False
    escaped = False
    for i in range(start, len(text)):
        char = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
            continue
        if char == '"':
            in_string = True
        elif char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start : i + 1]
    return None


def _properties_schema(prompt: str) -> dict:
    """把提示词里 `"properties":{…}` 那一块真的**解析**成 schema（而不是扫键名）。"""
    match = re.search(r'"properties"\s*:\s*\{', prompt)
    if not match:
        return {}
    raw = _extract_json_object(prompt, match.end() - 1)
    if raw is None:
        return {}
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _value_for(schema: dict, path: str) -> object:
    """按 schema 造一个占位值。`path` 用来让**并列的元素彼此不同**（见下）。"""
    kind = schema.get("type")
    if kind == "array":
        items = schema.get("items") or {}
        if isinstance(items, dict) and (items.get("type") == "object" or "properties" in items):
            # ★ 数组里的对象：造 **2 个互不相同**的元素（值里带下标）。
            #   为什么必须不同：上层的"子问题去重"是按字符串包含关系粗判的 ——
            #   两个一模一样的元素会被合并成一个，于是"多意图"这件事在夹具里消失，
            #   测试会变成"永远只看到 1 个子问题"而**没有任何地方报错**。
            return [
                _object_payload(items.get("properties") or {}, f"{path}-{index}")
                for index in (1, 2)
            ]
        return ["stub-a", "stub-b"]
    if kind == "integer":
        return 1
    if kind == "number":
        return 1.0
    if kind == "boolean":
        return True
    return path or "stub"


def _object_payload(properties: dict, path: str) -> dict:
    payload: dict = {}
    for name, schema in properties.items():
        if not isinstance(schema, dict):
            continue
        payload[name] = _value_for(schema, f"{path}.{name}" if path else name)
    return payload


def json_payload_from_prompt(prompt: str) -> dict:
    """
    从提示词里的 JSON Schema 反推一个**键名正确**的对象（含嵌套）。

    结构化输出的请求里，Spring AI 的 BeanOutputConverter 会把 schema 摆进提示词。
    这里照抄键名、按 type 给占位值 —— 于是任何 `record Foo(...)` 都能被"成功解析"，
    从而验证**解析链路**（而不是验证模型会不会好好输出）。

    ★ 顶层标量的取值沿用旧约定（`"stub"` / `["stub-a","stub-b"]`）—— 既有测试依赖它们，
      改掉等于把别人的断言顺手改红。嵌套对象的取值带下标（见 `_value_for`）。
    """
    properties = _properties_schema(prompt)
    if not properties:
        return {"result": "stub"}
    payload: dict = {}
    for name, schema in properties.items():
        if not isinstance(schema, dict):
            continue
        kind = schema.get("type")
        if kind == "array" and (schema.get("items") or {}).get("type") != "object":
            payload[name] = ["stub-a", "stub-b"]
        elif kind == "integer":
            payload[name] = 1
        elif kind == "number":
            payload[name] = 1.0
        elif kind == "boolean":
            payload[name] = True
        elif kind == "array":
            payload[name] = _value_for(schema, name)
        else:
            payload[name] = "stub"
    return payload or {"result": "stub"}


def wants_json(prompt: str) -> bool:
    """提示词里是否在要一个 JSON 对象（`.entity()` 的两条路径都覆盖）。"""
    lowered = prompt.lower()
    return "json" in lowered and (
        '"properties"' in prompt or "valid json" in lowered or "json 对象" in prompt
    )


def reasoning_for(model: str, reasoning_effort: str | None) -> str | None:
    """
    按模型设定决定本次是否产出"思考内容"。

    ★ 这段逻辑就是"三态"的定义：能关的是 TOGGLEABLE，关不掉的是 ALWAYS_ON，从不产出的是 NONE。
    """
    entry = CHAT_MODELS.get(model)
    behavior = entry["thinking"] if entry else "NONE"
    if behavior == "NONE":
        return None
    if behavior == "ALWAYS_ON":
        return "（桩）先判断题型，再列已知条件，最后代入计算。"
    off = reasoning_effort is not None and reasoning_effort.lower() in ("none", "minimal")
    return None if off else "（桩）先判断题型，再列已知条件，最后代入计算。"


def last_user_message(messages: list[dict]) -> str:
    for msg in reversed(messages or []):
        if msg.get("role") == "user":
            return str(msg.get("content", ""))
    return ""


def completion_body(model: str, content: str, reasoning: str | None) -> str:
    """非流式的响应体。字段形状照 OpenAI 的 chat.completion。"""
    message: dict = {"role": "assistant", "content": content}
    if reasoning:
        # ★ 思考内容走独立字段（OpenAI 兼容协议里就是 reasoning_content 这一类），
        #   与正文分开 —— 这也是探针判断"有没有思考"的依据
        message["reasoning_content"] = reasoning

    prompt_chars = 0
    return json.dumps(
        {
            "id": f"chatcmpl-stub-{int(time.time() * 1000)}",
            "object": "chat.completion",
            "created": int(time.time()),
            "model": model,
            "choices": [
                {"index": 0, "message": message, "finish_reason": "stop", "logprobs": None}
            ],
            "usage": {
                "prompt_tokens": max(1, prompt_chars // _CHARS_PER_TOKEN),
                "completion_tokens": max(1, len(content) // _CHARS_PER_TOKEN),
                "total_tokens": max(2, (len(content) + 8) // _CHARS_PER_TOKEN),
            },
        },
        ensure_ascii=False,
    )


def sse_chunks(
    model: str,
    content: str,
    reasoning: str | None,
    usage: tuple[int, int] | None = None,
) -> list[str]:
    """
    把一次回答切成 SSE 帧。

    顺序刻意与真实服务一致：先 role 帧 → 思考帧 → 正文分片 → 带 finish_reason 的收尾帧 → usage 帧 → [DONE]。
    ★ 之后要照这个顺序，是因为 M4b 的 `refs 必须先于第一个 delta` 那条时序约束，
    需要有一个"会分片吐 delta"的上游才能被真正测出来。
    ★ usage 帧只在请求了 `stream_options.include_usage` 时追加（OpenAI 的形状：choices 为空数组）。
    """
    base = {
        "id": f"chatcmpl-stub-{int(time.time() * 1000)}",
        "object": "chat.completion.chunk",
        "created": int(time.time()),
        "model": model,
    }

    def frame(delta: dict, finish=None) -> str:
        payload = dict(base)
        payload["choices"] = [{"index": 0, "delta": delta, "finish_reason": finish}]
        return "data: " + json.dumps(payload, ensure_ascii=False) + "\n\n"

    frames = [frame({"role": "assistant", "content": ""})]
    if reasoning:
        frames.append(frame({"reasoning_content": reasoning}))
    # 按 4 个字符一片地吐，模拟逐字流
    for i in range(0, len(content), 4):
        frames.append(frame({"content": content[i : i + 4]}))
    frames.append(frame({}, finish="stop"))
    if usage:
        prompt_tokens, completion_tokens = usage
        payload = dict(base)
        payload["choices"] = []
        payload["usage"] = {
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": prompt_tokens + completion_tokens,
        }
        frames.append("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n")
    frames.append("data: [DONE]\n\n")
    return frames


# ────────────────────────────── HTTP 处理 ──────────────────────────────


class StubHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "OpenAIStub/0.1"

    # 默认实现往 stderr 刷两行、还带时间戳，太吵；这里压成一行
    def log_message(self, fmt, *args):  # noqa: A003
        sys.stderr.write("[stub] " + (fmt % args) + "\n")

    # ── 出口：普通响应 ──
    def _send_json(self, status: int, payload) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _send_error_json(self, status: int, message: str, err_type: str = "stub_error") -> None:
        self._send_json(status, {"error": {"message": message, "type": err_type, "code": status}})

    # ── 出口：SSE（分块传输）──
    def _send_sse(self, frames: list[str]) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        # 没有 Content-Length ⇒ 用分块传输，客户端（Reactor 的 SSE 解析器）按帧读取
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        for frame in frames:
            data = frame.encode("utf-8")
            self.wfile.write(f"{len(data):X}\r\n".encode("ascii"))
            self.wfile.write(data)
            self.wfile.write(b"\r\n")
            self.wfile.flush()
            # ★ 帧间延迟：让上游"吐到一半"这件事在真栈上可观测。
            #   没有它，整段正文会在一次 flush 里全到 ⇒ 「生成中停止」只能停在"零字"或"全字"，
            #   S3 那条「停止保留已生成内容」就永远断言不到中间态（`delay_ms` 做不到这件事：它只推迟开头）。
            chunk_delay = _CONFIG.get("chunk_delay_ms") or 0
            if chunk_delay:
                time.sleep(chunk_delay / 1000.0)
        self.wfile.write(b"0\r\n\r\n")
        self.wfile.flush()

    # ── 读入 ──
    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return {"__unparsable__": raw.decode("utf-8", errors="replace")}

    def _record(self, body) -> None:
        """★ 把每个请求原样记下来 —— `GET /__stub/requests` 是它最有价值的用途。"""
        with _LOCK:
            _REQUESTS.append(
                {
                    "at": time.time(),
                    "method": self.command,
                    # ★ 记完整路径：base-url 写错（比如多写一层 /v1）时，这里会直接显示出来
                    "path": self.path,
                    "headers": {k: v for k, v in self.headers.items()},
                    "body": body,
                }
            )

    def _maybe_inject_fault(self) -> bool:
        """延迟 + 错误率注入。返回 True 表示已经回了错误、调用方应立刻返回。"""
        if _CONFIG["delay_ms"]:
            time.sleep(_CONFIG["delay_ms"] / 1000.0)
        rate = _CONFIG["fail_rate"]
        if rate > 0 and random.random() < rate:
            self._send_error_json(500, "桩服务按 fail_rate 注入的故障", "stub_injected_failure")
            return True
        return False

    # ── 路由 ──
    def do_GET(self):  # noqa: N802
        path = self.path.split("?")[0]
        if path == "/__stub/requests":
            with _LOCK:
                self._send_json(200, {"count": len(_REQUESTS), "requests": list(_REQUESTS)})
            return
        if path.endswith("/models"):
            self._record(None)
            self._send_json(200, self._models_payload())
            return
        self._send_error_json(404, f"桩服务没有实现 {path}")

    def do_POST(self):  # noqa: N802
        path = self.path.split("?")[0]
        body = self._read_json()

        # ★ 请求体解析不了就必须 400 —— 不能让"非法请求"看起来像一次成功的调用。
        #   真实事故：客户端忘了转义换行 ⇒ 上游收到非法 JSON；桩这里原本默默按「一条空文本」处理，
        #   返回 200 + 1 个向量 ⇒ 客户端以为自己发对了，排查时只能对着"怎么只回了 1 条"猜。
        #   桩的职责是把上游的拒绝如实演出来（真 OpenAI 兼容服务会回 400 invalid JSON）。
        if isinstance(body, dict) and "__unparsable__" in body:
            self._send_json(
                400,
                {
                    "error": {
                        "type": "invalid_request_error",
                        "message": "请求体不是合法 JSON（多半是字符串里的控制字符没转义，如换行）",
                        "raw_prefix": str(body["__unparsable__"])[:200],
                    }
                },
            )
            return

        if path == "/__stub/reset":
            with _LOCK:
                _REQUESTS.clear()
                # ★ 连 entity_override 一起清：它改的是"模型给出了什么值"，
                #   残留会让**后面的**闸门断言到假的模型行为（delay/fail_rate 只影响时序与成败，性质不同）
                _CONFIG["entity_override"] = None
            self._send_json(200, {"ok": True, "cleared": True})
            return
        if path == "/__stub/config":
            with _LOCK:
                for key in ("delay_ms", "chunk_delay_ms", "fail_rate"):
                    if key in body:
                        _CONFIG[key] = body[key]
                if "entity_override" in body:
                    override = body["entity_override"]
                    # ★ 只收对象或 null：写成字符串时"看起来配好了、实际什么都没覆盖"，
                    #   而那正是本项目最怕的静默失败 ⇒ 明确 400，别让它悄悄不生效
                    if override is not None and not isinstance(override, dict):
                        self._send_error_json(
                            400,
                            'entity_override 必须是对象（如 {"sufficient": false}）或 null',
                            "invalid_request_error",
                        )
                        return
                    _CONFIG["entity_override"] = override
            self._send_json(200, {"ok": True, "config": dict(_CONFIG)})
            return

        # ★ 宽松匹配：不管上游把 base-url 写成 host、host/v1 还是 host/v1/v1，
        #   都能接住 —— 同时把真实路径记下来供人核对
        if path.endswith("/chat/completions"):
            self._record(body)
            if self._maybe_inject_fault():
                return
            self._handle_chat(body)
            return
        if path.endswith("/embeddings"):
            self._record(body)
            if self._maybe_inject_fault():
                return
            self._handle_embeddings(body)
            return

        self._send_error_json(404, f"桩服务没有实现 {path}")

    # ── 具体端点 ──
    def _models_payload(self):
        """
        模型清单。★ 除 OpenAI 标准字段外，附带能力元数据：

        - context_window / tools：能力探测的「元数据优先」来源
        - dimension：向量模型的维度提示（探测仍会调 embeddings 实测长度）

        这些扩展字段是我们自己约定的 —— 不依赖网关专有字段（service_type/adapter），
        换真 Ollama / JAiRouter 时探测会自动降级到行为探针。
        """
        data = []
        for name, meta in CHAT_MODELS.items():
            data.append(
                {
                    "id": name,
                    "object": "model",
                    "created": 0,
                    "owned_by": "stub",
                    "context_window": meta["context_window"],
                    "tools": meta["tools"],
                }
            )
        data.append(
            {
                "id": EMBED_MODEL,
                "object": "model",
                "created": 0,
                "owned_by": "stub",
                "dimension": _EMBED_DIM,
            }
        )
        return {"object": "list", "data": data}

    def _answer_text(self, body) -> tuple[str, str | None]:
        model = body.get("model") or "stub-plain"
        prompt = last_user_message(body.get("messages"))
        effort = body.get("reasoning_effort")

        if wants_json(prompt) or (body.get("response_format") or {}).get("type"):
            payload = json_payload_from_prompt(prompt)
            # ★ 脚本化覆盖（只改值）：难例演示需要"判定说不够、并给出下一轮查询"，
            #   而按 schema 造的占位值恒为 sufficient=true + nextQuery="stub" ⇒ 多跳永远停在第 1 轮
            with _LOCK:
                override = _CONFIG["entity_override"]
            if override:
                payload.update(override)
            content = json.dumps(payload, ensure_ascii=False)
        else:
            content = f"（桩）关于「{prompt[:30]}」，这是占位回答。"
            # ★ 有参考资料就带上 `[N]` 角标：真实模型被要求在正文里标出处（`promptOf` 把
            #   refs 按 `[1] 标题 / 正文` 摆好），而桩不标的话 ——
            #   ① 前端「回到原句」滚过去**没有角标可落**，「引用能点开」这条交互在真栈上
            #      永远测不到（`check_design.py` 只能断"按钮存在"，存在 ≠ 有反应）；
            #   ② 而"界面没有角标"到底是模型没标、还是渲染丢了，读数上分不开。
            #   ⇒ 桩在这里模仿"标了出处"的形状，让下游那一格可测。
            marks = re.findall(r"^\[(\d+)\] ", prompt, re.M)
            if marks:
                content += "据资料" + "".join(f"[{m}]" for m in marks) + "所述。"
        return content, reasoning_for(model, effort)

    def _handle_chat(self, body) -> None:
        model = body.get("model") or "stub-plain"
        if model not in CHAT_MODELS:
            self._send_error_json(404, f"模型 {model} 不存在（桩服务只提供 {list(CHAT_MODELS)}）")
            return

        content, reasoning = self._answer_text(body)
        if body.get("stream"):
            # 只有显式要求才回 usage（与 OpenAI 的 stream_options.include_usage 同形）；
            # 这样应用侧 #142 的 onUsage 解析才能被真栈读数验证。
            usage = None
            if (body.get("stream_options") or {}).get("include_usage"):
                messages = body.get("messages") or []
                prompt_chars = sum(len(str(m.get("content", ""))) for m in messages)
                usage = (
                    max(1, prompt_chars // _CHARS_PER_TOKEN),
                    max(1, len(content) // _CHARS_PER_TOKEN),
                )
            self._send_sse(sse_chunks(model, content, reasoning, usage))
        else:
            self._send_json(200, json.loads(completion_body(model, content, reasoning)))

    def _handle_embeddings(self, body) -> None:
        model = body.get("model") or EMBED_MODEL
        raw_input = body.get("input")
        texts = raw_input if isinstance(raw_input, list) else [raw_input or ""]

        data = [
            {
                "object": "embedding",
                "index": index,
                "embedding": deterministic_vector(str(text), _EMBED_DIM),
            }
            for index, text in enumerate(texts)
        ]
        total_chars = sum(len(str(t)) for t in texts)
        self._send_json(
            200,
            {
                "object": "list",
                "data": data,
                "model": model,
                "usage": {
                    "prompt_tokens": max(1, total_chars // _CHARS_PER_TOKEN),
                    "total_tokens": max(1, total_chars // _CHARS_PER_TOKEN),
                },
            },
        )


# ────────────────────────────── 入口 ──────────────────────────────


def main() -> int:
    global _EMBED_DIM

    parser = argparse.ArgumentParser(
        description="OpenAI 兼容的测试桩服务（开发期替代真模型服务）"
    )
    parser.add_argument("--port", type=int, default=11434, help="默认 11434，与 Ollama 同端口")
    parser.add_argument("--host", default="0.0.0.0", help="默认 0.0.0.0（容器内外都能连）")
    parser.add_argument("--dim", type=int, default=768, help="embedding 维度")
    parser.add_argument("--delay-ms", type=int, default=0, help="每次请求的固定延迟")
    parser.add_argument("--fail-rate", type=float, default=0.0, help="0~1，注入 500 的概率")
    args = parser.parse_args()

    _EMBED_DIM = args.dim
    _CONFIG["delay_ms"] = args.delay_ms
    _CONFIG["fail_rate"] = args.fail_rate

    server = ThreadingHTTPServer((args.host, args.port), StubHandler)
    print(f"★ OpenAI 兼容桩服务已启动：http://localhost:{args.port}/v1")
    print(f"  模型：{', '.join(list(CHAT_MODELS) + [EMBED_MODEL])}（embedding 维度 {_EMBED_DIM}）")
    print(f"  行为：延迟 {args.delay_ms}ms · 故障率 {args.fail_rate}")
    print(f"  观测：GET http://localhost:{args.port}/__stub/requests")
    print("  ★ 提醒：本服务的结果是设定值，不能当作真实模型能力的技术结论。")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[stub] 收到中断，正在关闭…")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
