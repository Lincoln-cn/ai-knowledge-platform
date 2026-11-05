#!/usr/bin/env python3
"""结构化输出失败率的真模型读数（issue `#63`，填 `docs/技术结论/M0-技术结论.md` 结论三）。

**问题**：`09 §5.2` 的选型结论「不自研四层防御，用官方 `BeanOutputConverter`」建立在
**未测的假设**上：模型对 `HopDecision.Payload` 形状的结构化输出，到底多大概率解析不出来。
解析失败 ⇒ `BusinessException` ⇒ `HopStop.JUDGE_FAILED` ⇒ 记 D1 —— 失败率直接抬高 degradeRate。

**测试路径**（与生产的同构点，逐条对照源码）：
1. 提示词 = `MultiHopService.prompt()` 逐字（判断标准 1–4 + 用户问题 + 「已有结果（标题：正文前 100 字）」）；
   追加 `ChatModelAdapter.FORMAT_SEPARATOR = "\\n\\n"` + `BeanOutputConverter.getFormat()` 逐字文案
   （含对 `record Payload(Boolean sufficient, String nextQuery)` 生成的 JSON Schema）。
2. HTTP 请求体 = 仅 `model` + `messages` + `stream:false` —— 同 `UpstreamGateway.chatCompletionWithUsage`
   的 payload 形状（**不带 temperature / max_tokens / response_format**，生产也不带）。
3. 响应正文 = `choices[0].message.content` —— 同 `UpstreamGateway` 取正文那一行（只看 content）。
4. 解析 = 复刻 `BeanOutputConverter` 默认 cleaner 链（Whitespace → ThinkingTag → MarkdownCodeBlock →
   Whitespace）+ Jackson 严格 readValue 语义（未知字段忽略、Boolean 宽松 coercion、缺字段→null、
   类型不匹配→异常）。清洗规则与解析行为已用真实 `BeanOutputConverter`（spring-ai-model 2.0.1）
   的 20 样例行为矩阵逐条校准。
5. 应用层判定 = `HopDecision.from` 口径：解析成功但 `sufficient == null` ⇒ 生产仍判失败（D1）。

**与生产的差异**（★ 引用本表时必须一并看）：
- HTTP 层直连本机 ollama `/v1/chat/completions`，未过真后端 `ChatPort.completeAs`（本机没有由用户
  启动的后端服务，agent 不自起服务）；解析层是 Python 复刻而非 JVM 的 `BeanOutputConverter` 本身
  （语义按上述 20 样例矩阵校准，但不等于 JVM 逐字节一致）。
- HTTP 超时放宽到 180s（本机 CPU 跑小模型慢，5s 会把「模型慢」误记成「结构化失败」）；每条记录
  耗时，并另按生产预算 `QueryUnderstandingService.DEFAULT_BUDGET_MS = 5000` 统计「超预算」占比
  —— 生产上超预算同样落 `JUDGE_FAILED`，但形态不同，不混进结构化失败率。
- 默认参数档为「模型出厂默认」（不发 /no_think、不设 temperature），与生产发请求的方式一致。

用法：
  python tools/check_structured_output.py --models qwen2.5:1.5b qwen3:4b deepseek-r1:1.5b \\
      --out .tmp-sai/readings.jsonl
  # 汇总表打到 stdout；每条调用的原始响应全文进 --out 指定的 JSONL（取证存档，不入 git）。
"""
import argparse
import http.client
import io
import json
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

# ────────────────────────── 生产同构的提示词 ──────────────────────────

# MultiHopService.prompt() 的 text block（剥离 4 空格公共缩进后的运行时字符串），逐字。
PROMPT_TEMPLATE = """\
你在判断"已有的检索结果够不够回答用户的问题"。

判断标准：
1. 只有当材料里**已经能直接回答**问题时，sufficient 才为 true；
2. 如果材料只给出了**线索**（例如一个编号、一个名称），而答案要用它再查一次才拿得到，sufficient 为 false，
   并在 nextQuery 里写出**那一次要查的查询**（不要写成"再查一下"这种空话，要写出具体要查什么）；
3. nextQuery 不超过 60 字，与用户问题同一语言，专有名词与编号原样保留；
4. 如果材料确实不够、而你也想不出更好的查询，就把 sufficient 置 false、nextQuery 留空 —— 不要硬编。

用户问题：%s

已有结果（标题：正文前 100 字）：
%s"""

# ChatModelAdapter.FORMAT_SEPARATOR = "\n\n"
FORMAT_SEPARATOR = "\n\n"

# BeanOutputConverter.getFormat() 的逐字文案（String.format，%s = schema），schema 为
# record Payload(Boolean sufficient, String nextQuery) 经 victools 生成的逐字输出。
FORMAT_TEXT = (
    "Your response should be in JSON format.\n"
    "Do not include any explanations, only provide a RFC8259 compliant JSON response following this format without deviation.\n"
    "Do not include markdown code blocks in your response.\n"
    "Remove the ```json markdown from the output.\n"
    "Here is the JSON Schema instance your output must adhere to:\n"
    "```{schema}```\n"
)

PAYLOAD_SCHEMA = (
    '{\n'
    '  "$schema" : "https://json-schema.org/draft/2020-12/schema",\n'
    '  "type" : "object",\n'
    '  "properties" : {\n'
    '    "nextQuery" : {\n'
    '      "type" : "string"\n'
    '    },\n'
    '    "sufficient" : {\n'
    '      "type" : "boolean"\n'
    '    }\n'
    '  },\n'
    '  "required" : [ "nextQuery", "sufficient" ],\n'
    '  "additionalProperties" : false\n'
    '}'
)

SNIPPET_LENGTH = 100  # MultiHopService.SNIPPET_LENGTH
PROD_BUDGET_MS = 5000  # QueryUnderstandingService.DEFAULT_BUDGET_MS


def snippet(content: str) -> str:
    """MultiHopService.snippet()：空白压成单空格后截 100 字。"""
    flat = re.sub(r"\s+", " ", content).strip()
    return flat if len(flat) <= SNIPPET_LENGTH else flat[:SNIPPET_LENGTH]


def build_prompt(question: str, hits: list) -> str:
    """MultiHopService.prompt()：「N. 标题：正文前 100 字」逐条 + 模板 + 格式尾巴。"""
    lines = []
    for i, (title, content) in enumerate(hits, start=1):
        lines.append(f"{i}. {title if title else '（无标题）'}：{snippet(content)}")
    body = PROMPT_TEMPLATE % (question, "\n".join(lines))
    return body + FORMAT_SEPARATOR + FORMAT_TEXT.format(schema=PAYLOAD_SCHEMA)


# ────────────────────────── BeanOutputConverter 语义复刻 ──────────────────────────

# ThinkingTagCleaner.DEFAULT_PATTERNS（Pattern.DOTALL），逐字。
THINKING_PATTERNS = [
    re.compile(r"(?s)<thinking>.*?</thinking>\s*"),
    re.compile(r"(?s)<think>.*?</think>\s*"),
    re.compile(r"(?s)<reasoning>.*?</reasoning>\s*"),
    re.compile(r"(?s)```thinking.*?```\s*"),
    re.compile(r"(?s)<!--\s*thinking:.*?-->\s*"),
]


class ParseError(Exception):
    """转换器 convert() 抛 RuntimeException 的对应物（生产里转成 BusinessException）。"""


def whitespace_clean(text: str) -> str:
    return text.strip()


def thinking_tag_clean(text: str) -> str:
    if not text or ("<" not in text and "`" not in text):
        return text
    for pattern in THINKING_PATTERNS:
        text = pattern.sub("", text)
    return text


def markdown_code_block_clean(text: str) -> str:
    text = text.strip()
    if text.startswith("```") and text.endswith("```"):
        first_newline = text.find("\n")
        if first_newline != -1:
            text = text[first_newline + 1: len(text) - 3].strip()
    return text


def clean_response(text: str) -> str:
    """默认 cleaner 链：Whitespace → ThinkingTag → MarkdownCodeBlock → Whitespace。"""
    return whitespace_clean(markdown_code_block_clean(thinking_tag_clean(whitespace_clean(text))))


def jackson_bool(value):
    """Jackson 对 Boolean 的宽松 coercion（DEFAULT）：0/1 与 "true"/"false" 可接受。"""
    if isinstance(value, bool):
        return value
    if isinstance(value, int) and value in (0, 1):
        return bool(value)
    if isinstance(value, str) and value.lower() in ("true", "false"):
        return value.lower() == "true"
    raise ParseError(f"Cannot coerce {value!r} to boolean")


def convert_payload(raw: str):
    """BeanOutputConverter<Payload>.convert(raw) 的复刻。

    返回 {"sufficient": bool|None, "nextQuery": str|None}；
    抛 ParseError 对应转换失败（StreamReadException / MismatchedInputException 等）。
    """
    if raw is None or not raw.strip():
        raise ParseError("No content to map due to end-of-input")
    cleaned = clean_response(raw)
    try:
        data = json.loads(cleaned)
    except json.JSONDecodeError as e:
        raise ParseError(f"{type(e).__name__}: {e}") from e
    if not isinstance(data, dict):
        raise ParseError(f"Cannot deserialize from {type(data).__name__} value")
    result = {"sufficient": None, "nextQuery": None}
    if "sufficient" in data and data["sufficient"] is not None:
        result["sufficient"] = jackson_bool(data["sufficient"])
    if "nextQuery" in data and data["nextQuery"] is not None:
        if not isinstance(data["nextQuery"], str):
            raise ParseError(f"Cannot deserialize nextQuery from {type(data['nextQuery']).__name__}")
        result["nextQuery"] = data["nextQuery"]
    return result


# ────────────────────────── 失败形态归类 ──────────────────────────

# 归类口径（与结论三表格的列一致）：
#   fence          = markdown 围栏残渣（不完整的 ```、多段围栏）卡住解析
#   prose          = 前后有废话（JSON 前后夹着说明文字）
#   field          = 字段缺失或改名（解析成功但 sufficient 缺/非布尔 ⇒ HopDecision.from 判失败）
#   truncated      = 截断（JSON 未闭合 / finish_reason=length）
#   non-json       = 非 JSON（纯文本、伪 JSON、顶层数组、空响应）
def classify_failure(raw: str, cleaned: str, parse_error: ParseError | None,
                     payload: dict | None, finish_reason: str | None) -> str | None:
    """返回失败形态（成功则 None）。判定顺序固定，保证同输入同归类。"""
    if parse_error is None:
        if payload is not None and payload["sufficient"] is None:
            return "field"
        return None
    msg = str(parse_error)
    has_brace = "{" in cleaned
    ends_json = cleaned.rstrip().endswith("}")
    # 1) 截断优先：JSON 起了头但收不了尾（或服务端已截断）
    if finish_reason == "length" or (has_brace and not ends_json and "end-of-input" in msg) or \
       ("Unterminated" in msg):
        return "truncated"
    # 1b) 合法 JSON 但顶层形状不对（顶层数组等）⇒ 按口径注释归 non-json（只能降级）
    if "Cannot deserialize" in msg:
        return "non-json"
    # 2) 前后有废话：把围栏标记全部抹掉后，JSON 主体（第一个 { 到最后一个 }）之外仍有内容
    no_fence = raw.replace("```", "")
    first, last = no_fence.find("{"), no_fence.rfind("}")
    if first != -1 and last > first and (no_fence[:first].strip() or no_fence[last + 1:].strip()):
        return "prose"
    # 3) markdown 围栏形态异常（粘连 ```{、半围栏残渣、多段围栏）——原始响应带围栏但救不回来
    if "```" in raw:
        return "fence"
    return "non-json"


# ────────────────────────── ollama /v1/chat/completions ──────────────────────────

def call_ollama(root: str, model: str, prompt: str, timeout_s: float, via_exec: bool = False):
    """生产同构的请求体：只有 model + messages（+ stream:false）。返回响应 JSON。

    via_exec=True 时请求经 `docker exec` 在容器网络命名空间内用 bash /dev/tcp 直发
    ollama 本体（Host 127.0.0.1:11434）——因为宿主 11434 被桩服务与 docker-proxy
    双绑，直连会被路由到桩。HTTP 端点与请求体与生产完全一致，仅发起点不同。
    """
    payload = {
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "stream": False,
    }
    body = json.dumps(payload).encode("utf-8")
    if via_exec:
        request_bytes = (
            b"POST /v1/chat/completions HTTP/1.1\r\n"
            b"Host: 127.0.0.1:11434\r\n"
            b"Content-Type: application/json\r\n"
            + f"Content-Length: {len(body)}\r\n".encode("ascii")
            + b"Connection: close\r\n\r\n"
            + body
        )
        proc = subprocess.run(
            ["docker", "exec", "-i", "aip-ollama", "bash", "-c",
             "exec 3<>/dev/tcp/127.0.0.1/11434; cat >&3; cat <&3"],
            input=request_bytes, capture_output=True, timeout=timeout_s,
        )
        if proc.returncode != 0:
            raise OSError(f"docker exec transport failed: {proc.stderr.decode('utf-8', 'replace')[:200]}")
        return _parse_http_response(proc.stdout)
    req = urllib.request.Request(
        root.rstrip("/") + "/v1/chat/completions",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout_s) as resp:
        return json.loads(resp.read().decode("utf-8"))


class _FakeSock:
    """给 http.client.HTTPResponse 用的只读假 socket（解析 chunked / Content-Length）。"""

    def __init__(self, data: bytes):
        self._f = io.BytesIO(data)

    def makefile(self, *args, **kwargs):
        return self._f


def _parse_http_response(raw: bytes):
    resp = http.client.HTTPResponse(_FakeSock(raw))
    resp.begin()
    return json.loads(resp.read().decode("utf-8"))


# ────────────────────────── 测量输入（多跳判定的真实场景形状） ──────────────────────────

CASES = [
    ("c1-够答-报销上限", "员工出差住宿报销上限是多少？", [
        ("差旅报销管理规定", "第六条 住宿费实行限额报销，一线城市每晚不超过 400 元，其他城市每晚不超过 300 元，凭票据实报销。"),
    ]),
    ("c2-线索-要查工号", "张伟的工号是多少？", [
        ("2024 年度优秀员工表彰名单", "研发部：张伟、李娜；市场部：王强。获奖人员名单经办公会审议通过。"),
    ]),
    ("c3-不够-想不出查询", "竞品 AuroraDB 的企业版定价是多少？", [
        ("本公司产品手册", "知识助理平台标准版支持 50 个知识库，企业版不限知识库数量，提供私有化部署与专属支持。"),
    ]),
    ("c4-线索-合同索引", "合同 HT-2024-001 的签署日期是哪天？", [
        ("合同台账索引（2024）", "HT-2024-001 采购合同，正本存于法务部卷宗 2024-A-03；HT-2024-002 服务合同，正本存于卷宗 2024-A-04。"),
    ]),
    ("c5-够答-密码重置", "怎么重置登录密码？", [
        ("账号常见问题", "重置密码：在登录页点击「忘记密码」，输入绑定手机号接收验证码，验证通过后设置新密码，新密码需 8 位以上含字母与数字。"),
    ]),
    ("c6-线索-年报", "2023 年的营业收入是多少？", [
        ("财务档案借阅须知", "历年年度报告存放于财务共享盘「年报」目录，借阅需经财务总监审批，借期不超过 3 个工作日。"),
    ]),
    ("c7-混合-只答一半", "A 产品和 B 产品的企业版价格分别是多少？", [
        ("产品报价单（2024 春季）", "A 产品企业版：19.8 万元/年，含 100 个账号。B 产品企业版价格面议，需联系区域销售经理获取报价。"),
    ]),
    ("c8-线索-制度条款", "《信息安全管理办法》第几条规定了数据分类分级？", [
        ("制度体系索引", "《信息安全管理办法》（2023 修订）全文见制度库-信息安全类，共 12 章 68 条；《数据分类分级指南》为其附件一。"),
    ]),
]


def run_model(root: str, model: str, repeats: int, timeout_s: float, out_lines: list,
              via_exec: bool = False):
    results = []
    for case_id, question, hits in CASES:
        prompt = build_prompt(question, hits)
        for rep in range(1, repeats + 1):
            started = time.monotonic()
            record = {"model": model, "case": case_id, "rep": rep, "prompt": prompt}
            try:
                resp = call_ollama(root, model, prompt, timeout_s, via_exec)
                elapsed_ms = int((time.monotonic() - started) * 1000)
                choice = (resp.get("choices") or [{}])[0]
                message = choice.get("message") or {}
                content = message.get("content") or ""
                reasoning = message.get("reasoning_content")
                finish_reason = choice.get("finish_reason")
                record.update({
                    "elapsed_ms": elapsed_ms,
                    "content": content,
                    "has_reasoning_content": reasoning is not None and reasoning != "",
                    "finish_reason": finish_reason,
                })
                cleaned = clean_response(content)
                try:
                    payload = convert_payload(content)
                    parse_error = None
                except ParseError as e:
                    payload, parse_error = None, e
                failure = classify_failure(content, cleaned, parse_error, payload, finish_reason)
                record.update({
                    "cleaned": cleaned,
                    "payload": payload,
                    "parse_error": None if parse_error is None else str(parse_error),
                    "failure": failure,
                    # 生产可用 = 解析成功且 sufficient 非 null（HopDecision.from 口径）
                    "prod_usable": failure is None,
                    "over_prod_budget": elapsed_ms > PROD_BUDGET_MS,
                })
            except (urllib.error.URLError, OSError, json.JSONDecodeError, KeyError) as e:
                elapsed_ms = int((time.monotonic() - started) * 1000)
                record.update({
                    "elapsed_ms": elapsed_ms,
                    "failure": "non-json",
                    "content": f"<transport error: {type(e).__name__}: {e}>",
                    "prod_usable": False,
                    "over_prod_budget": elapsed_ms > PROD_BUDGET_MS,
                })
            out_lines.append(json.dumps(record, ensure_ascii=False))
            results.append(record)
            flag = "OK " if record["prod_usable"] else f"FAIL[{record['failure']}]"
            print(f"  {model} {case_id} rep{rep}: {flag} {record['elapsed_ms']}ms", flush=True)
    return results


def summarize(model: str, results: list):
    n = len(results)
    ok = sum(1 for r in results if r["prod_usable"])
    fail = n - ok
    dist = {}
    for r in results:
        if r["failure"]:
            dist[r["failure"]] = dist.get(r["failure"], 0) + 1
    over = sum(1 for r in results if r.get("over_prod_budget"))
    lines = [
        f"\n== {model} ==",
        f"成功 {ok}/{n} · 失败 {fail}/{n} · 失败率 {fail / n * 100:.1f}%",
        f"超生产 5s 预算 {over}/{n}（{over / n * 100:.0f}%）· "
        f"reasoning_content 出现 {sum(1 for r in results if r.get('has_reasoning_content'))}/{n}",
        "失败形态分布: " + ("、".join(f"{k}×{v}" for k, v in sorted(dist.items())) or "无"),
    ]
    seen = set()
    for r in results:
        if r["failure"] and r["failure"] not in seen:
            seen.add(r["failure"])
            frag = (r.get("content") or "")[:240].replace("\n", "\\n")
            lines.append(f"  [{r['failure']}] 首例 {r['case']} rep{r['rep']} 逐字片段: {frag!r}")
            if r.get("parse_error"):
                lines.append(f"    异常: {r['parse_error'][:160]}")
    print("\n".join(lines))


# ────────────────────────── 自检（--self-check） ──────────────────────────


def self_check() -> int:
    """解析管线自检：喂故意坏掉的响应，断言它必须报错、且归类与官方 BeanOutputConverter 校准矩阵一致。

    这条必须能红：把 convert_payload / classify_failure 改成"永远成功"时，
    本自检必须以非零退出码失败（合并评审要看的反证）。
    """
    cases = [
        # (说明, 原始响应, 期望)；期望 = ("ok", sufficient, nextQuery) | ("field",) | ("err", 形态)
        ("裸 JSON 成功", '{"sufficient": true, "nextQuery": null}', ("ok", True, None)),
        ("标准 ```json 围栏救活", '```json\n{"sufficient": false, "nextQuery": "查工号"}\n```',
         ("ok", False, "查工号")),
        ("think 标签混排救活（推理模型契约场景）",
         '<thinking>先想想</thinking>\n```json\n{"sufficient": true, "nextQuery": null}\n```',
         ("ok", True, None)),
        ("粘连围栏 ```{ 切掉大括号 —— 必须报错", '```{\n  "nextQuery" : "重新查询",\n  "sufficient" : false\n}```',
         ("err", "fence")),
        ("截断 JSON —— 必须报错", '{"sufficient": true, "nextQuery": "查', ("err", "truncated")),
        ("纯文本 —— 必须报错", '我不知道怎么回答这个问题', ("err", "non-json")),
        ("前后有废话 —— 必须报错（官方 BeanOutputConverter 不认）",
         '好的，我判断如下：{"sufficient": true, "nextQuery": null} 仅供参考', ("err", "prose")),
        ("顶层数组 —— 必须报错且归非 JSON", '[{"sufficient": true}]', ("err", "non-json")),
        ("空响应 —— 必须报错", '', ("err", "non-json")),
        ("缺 sufficient：解析成功但生产判失败（HopDecision.from）", '{"nextQuery": "x"}', ("field",)),
        ("字段改名 next_query：宽松匹配静默 null（官方行为实证钉住）",
         '{"next_query": "x", "sufficient": true}', ("ok", True, None)),
        ("布尔宽松 coercion：1 可接受（Jackson DEFAULT 实证钉住）",
         '{"sufficient": 1, "nextQuery": null}', ("ok", True, None)),
    ]
    failures = []
    for name, raw, expect in cases:
        parse_error, payload = None, None
        try:
            payload = convert_payload(raw)
        except ParseError as e:
            parse_error = e
        kind = classify_failure(raw, clean_response(raw), parse_error, payload, None)
        if expect[0] == "err":
            good = parse_error is not None and kind == expect[1]
            detail = f"期望 err/{expect[1]}，实际 {kind}，异常 {parse_error}"
        elif expect[0] == "field":
            good = parse_error is None and kind == "field"
            detail = f"期望 field，实际 {kind}，payload {payload}"
        else:
            good = (parse_error is None and kind is None
                    and payload["sufficient"] == expect[1] and payload["nextQuery"] == expect[2])
            detail = f"期望 ok {expect[1]!r}/{expect[2]!r}，实际 {kind}，payload {payload}，异常 {parse_error}"
        print(f"  [{'PASS' if good else 'FAIL'}] {name}" + ("" if good else f" —— {detail}"))
        if not good:
            failures.append((name, detail))
    print(f"\nself-check：{len(cases) - len(failures)}/{len(cases)} 通过")
    for name, detail in failures:
        print(f"  - {name}: {detail}")
    return 1 if failures else 0


def main():
    ap = argparse.ArgumentParser(description="结构化输出失败率真模型读数（issue #63）")
    ap.add_argument("--root", default="http://127.0.0.1:11434", help="OpenAI 兼容根地址（ollama）")
    ap.add_argument("--models", nargs="+", default=None, help="要测的模型名")
    ap.add_argument("--self-check", action="store_true",
                    help="只跑解析管线自检（不打真模型）：喂故意坏掉的响应并断言它必须报错")
    ap.add_argument("--repeats", type=int, default=3, help="每个输入重复次数（8 输入 × N ≥ 20）")
    ap.add_argument("--timeout", type=float, default=180.0, help="HTTP 超时秒（不等于生产预算）")
    ap.add_argument("--out", default="structured_output_readings.jsonl", help="逐条读数存档（JSONL）")
    ap.add_argument("--via", choices=["http", "exec"], default="http",
                    help="exec = 经 docker exec 在容器网络命名空间内直发 ollama（宿主 11434 被桩双绑时用）")
    args = ap.parse_args()

    if args.self_check:
        return self_check()
    if not args.models:
        ap.error("需要 --models，或用 --self-check 只跑自检")

    out_lines = []
    for model in args.models:
        print(f"\n--- {model}：{len(CASES)} 输入 × {args.repeats} 次 ---", flush=True)
        results = run_model(args.root, model, args.repeats, args.timeout, out_lines,
                            via_exec=(args.via == "exec"))
        summarize(model, results)

    with open(args.out, "w", encoding="utf-8") as f:
        f.write("\n".join(out_lines) + "\n")
    print(f"\n逐条读数已存 {args.out}（{len(out_lines)} 条）")


if __name__ == "__main__":
    sys.exit(main())
