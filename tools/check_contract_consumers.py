#!/usr/bin/env python3
"""前后端配对第三格：前端消费点只读读数（issue #79）。

三个维度是「后端端点 / 契约登记 / 前端消费点」。前两个已有闸门，
第三个从来没有读数 —— 所以「后端已落 + 契约已登记 + 页面零引用」
可以全程绿。

本脚本只做只读扫查，**默认不失败**，先给一份可核对的基线：
  1. 静态扫 backend 的 @*Mapping，得到后端端点；
  2. 静态扫 frontend/src/api/*.ts，得到契约函数与 method+url；
  3. 只保留「后端已落且契约已登记」的端点；
  4. 在 frontend/src（api/ 之外）找契约函数的调用点；
  5. 输出「端点 → 契约函数 → 消费点文件:行」或「零命中」。

★ 为什么不用「路径字符串搜索」判红：页面可能通过 API 函数、动态拼接、
  或组件库间接调用，字符串命中/不命中都会误报。这里用**函数名**做第一版，
  仍然只报告不判红；等基线人工过一遍后再谈是否进 CI。

★ 门禁（`#79`）：`--check` 拿 `tools/consumer-baseline.txt` 当基线 ——
  **新增的零消费端点会红**；基线里那条现在有人用了 / 端点没了也会红（清单必须随修复收缩）。

用法：
  python tools/check_contract_consumers.py            # 只读读数
  python tools/check_contract_consumers.py --check     # 门禁：与基线比对，退 0/1
  python tools/check_contract_consumers.py --baseline  # 按当前读数重排基线（人工过一遍再用）
  python tools/check_contract_consumers.py --json
  python tools/check_contract_consumers.py --self-test
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BACKEND_DIR = ROOT / "backend/src/main/java"
FRONTEND_SRC = ROOT / "frontend/src"
FRONTEND_API = FRONTEND_SRC / "api"

ANNOTATION_RE = re.compile(r"@(Get|Post|Put|Patch|Delete)Mapping\s*(?:\(([^)]*)\))?", re.S)
CLASS_MAPPING_RE = re.compile(r"@RequestMapping\s*\(\s*(?:value\s*=\s*)?\"([^\"]*)\"", re.S)
STRING_RE = re.compile(r"\"([^\"]*)\"")
EXPORT_CONST_RE = re.compile(r"export\s+const\s+([A-Za-z_$][\w$]*)\s*=", re.S)
EXPORT_FN_RE = re.compile(r"export\s+function\s+([A-Za-z_$][\w$]*)\s*\(", re.S)
URL_RE = re.compile(r"url\s*:\s*['\"]([^'\"]+)['\"]")
METHOD_RE = re.compile(r"method\s*:\s*['\"]([A-Za-z]+)['\"]")
CONSUMER_RE_TMPL = r"\b{fn}\s*\("


# ---------------------------------------------------------------- 剥注释（#265）
#
# ★ 为什么必须剥：判「某契约函数有没有被页面调用」靠的是**函数名匹配**。页面里
#   只要有一句说明性注释写着 `someFn(`（"端点已实现，但 catch 仍必须留"这类说明
#   就是这么写的），这条端点就会被判成「已接线」—— 闸门替一句注释签了字，
#   `#79` 的读数偏高。
#
# ★ 为什么不能一条正则了事（下面每一条都是实测会踩的形状）：
#   ① 字符串里的 `//` 不是注释 —— `'http://localhost:8080/v1'`、mock 里拼 SVG 的
#      模板字符串（`frontend/src/mock/handlers/identity.ts:117`）都这么写；
#      粗暴 `re.sub(r"//[^\n]*")` 会把**同一行后面**的真调用一起抹掉 ⇒ 反向误伤。
#   ② `.vue` 里 `//` 只在 `<script>` 里是注释，`<template>` 里不是；`<!-- -->` 只在
#      `<template>` 与块外是注释，`<script>` 里不是 ⇒ 必须按块分区，不能整文件一种规则。
#   ③ 顶层 `<template>` 里可以再嵌 `<template v-if>`（本仓库 306 处）⇒ 找 `</template>`
#      必须**嵌套计数**，否则取到内层的结束标签、把后面的 `<script>` 当"块外"处理、
#      整块漏剥（漏剥 = 假阳性，正是本 Issue 要修的方向）。
#   ④ 模板字符串的 `${…}` 是**真代码**，不能连它一起当字符串跳过。
#
# ★ 三条实现约束：
#   · **等长替换**：注释字符换成空格、换行保留 ⇒ 行号不动，读数里的 `file:line` 仍可核对；
#   · 字符串与正则字面量整段跳过，其中的 `//` 与 `<!--` 不受影响；
#   · 只抹注释，**不抹字符串内容**（字符串里的函数名仍算"提到了"，但那是另一回事）。

VUE_BLOCK_RE = re.compile(r"<(script|template|style)\b[^>]*>", re.I)
VUE_TEMPLATE_TAG_RE = re.compile(r"</?template\b[^>]*>", re.I)
CONSUMER_SUFFIXES = {".ts", ".vue", ".tsx", ".js"}
REGEX_PREFIX_CHARS = "=([{,;:?&|!+-*%~^<>"
REGEX_PREFIX_WORDS = {
    "return", "typeof", "case", "in", "of", "new", "delete", "void", "throw",
    "instanceof", "do", "else", "yield", "await",
}


def _blank(chars: list[str], start: int, end: int) -> None:
    """把 [start, end) 抹成空格，换行符保留 —— 这样行号与原文一致。"""
    for index in range(start, min(end, len(chars))):
        if chars[index] != "\n":
            chars[index] = " "


def _is_regex_start(text: str, index: int) -> bool:
    """`/` 是正则字面量的开头，还是除号？看前一个有效字符/关键字。"""
    cursor = index - 1
    while cursor >= 0 and text[cursor] in " \t\r\n":
        cursor -= 1
    if cursor < 0:
        return True
    if text[cursor] in REGEX_PREFIX_CHARS:
        return True
    word_end = cursor
    while cursor >= 0 and (text[cursor].isalnum() or text[cursor] in "_$"):
        cursor -= 1
    return text[cursor + 1 : word_end + 1] in REGEX_PREFIX_WORDS


def _skip_quoted(text: str, start: int) -> int:
    quote = text[start]
    index = start + 1
    while index < len(text):
        if text[index] == "\\":
            index += 2
            continue
        if text[index] == quote:
            return index + 1
        index += 1
    return len(text)


def _skip_regex(text: str, start: int) -> int:
    index = start + 1
    in_class = False
    while index < len(text):
        char = text[index]
        if char == "\\":
            index += 2
            continue
        if char == "[":
            in_class = True
        elif char == "]":
            in_class = False
        elif char == "/" and not in_class:
            return index + 1
        elif char == "\n":
            return index + 1
        index += 1
    return len(text)


def _find_interpolation_end(text: str, brace: int) -> int:
    """从 `${` 的那个 `{` 起，返回配对 `}` 的下标。"""
    depth = 0
    index = brace
    while index < len(text):
        char = text[index]
        if char in {"'", '"'}:
            index = _skip_quoted(text, index)
            continue
        if char == "`":
            index = _skip_template_literal(text, index, None)
            continue
        if text[index : index + 2] == "//":
            newline = text.find("\n", index)
            index = len(text) if newline < 0 else newline
            continue
        if text[index : index + 2] == "/*":
            close = text.find("*/", index + 2)
            index = len(text) if close < 0 else close + 2
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
        index += 1
    return len(text)


def _skip_template_literal(text: str, start: int, chars: list[str] | None) -> int:
    """跳过模板字符串；`chars` 非空时顺手把 `${…}` 里的注释剥掉（插值是真代码）。"""
    index = start + 1
    while index < len(text):
        char = text[index]
        if char == "\\":
            index += 2
            continue
        if char == "`":
            return index + 1
        if char == "$" and text[index + 1 : index + 2] == "{":
            close = _find_interpolation_end(text, index + 1)
            if chars is not None:
                chars[index + 2 : close] = list(_strip_js(text[index + 2 : close]))
            index = close + 1
            continue
        index += 1
    return len(text)


def _strip_js(text: str) -> str:
    """剥 JS/TS 的行注释与块注释；字符串、模板字符串、正则字面量原样保留。"""
    chars = list(text)
    index = 0
    while index < len(text):
        char = text[index]
        pair = text[index : index + 2]
        if pair == "//":
            end = text.find("\n", index)
            end = len(text) if end < 0 else end
            _blank(chars, index, end)
            index = end
            continue
        if pair == "/*":
            close = text.find("*/", index + 2)
            end = len(text) if close < 0 else close + 2
            _blank(chars, index, end)
            index = end
            continue
        if char in {"'", '"'}:
            index = _skip_quoted(text, index)
            continue
        if char == "`":
            index = _skip_template_literal(text, index, chars)
            continue
        if char == "/" and _is_regex_start(text, index):
            index = _skip_regex(text, index)
            continue
        index += 1
    return "".join(chars)


def _strip_markup(segment: str, chars: list[str], offset: int) -> None:
    """HTML/模板区：只抹 `<!-- … -->` —— `//` 在这里**不是**注释。"""
    for match in re.finditer(r"<!--.*?-->", segment, re.S):
        _blank(chars, offset + match.start(), offset + match.end())


def _strip_css(segment: str, chars: list[str], offset: int) -> None:
    """样式区：只抹 `/* … */`（不抹 `//` —— CSS 的 `//` 可能是协议相对 URL）。"""
    for match in re.finditer(r"/\*.*?\*/", segment, re.S):
        _blank(chars, offset + match.start(), offset + match.end())


def strip_comments(text: str, suffix: str) -> str:
    """按文件类型剥注释，返回**与原文等长**的文本（换行保留 ⇒ 行号不变）。"""
    if suffix != ".vue":
        return _strip_js(text)
    chars = list(text)
    position = 0
    while True:
        match = VUE_BLOCK_RE.search(text, position)
        if not match:
            break
        tag = match.group(1).lower()
        body_start = match.end()
        _strip_markup(text[position : match.start()], chars, position)
        if tag == "template":
            # ★ 嵌套计数：`<template v-if>` 的内层 `</template>` 不是 SFC 的结束
            depth = 1
            cursor = body_start
            block_end = len(text)
            closed = False
            while depth > 0:
                inner = VUE_TEMPLATE_TAG_RE.search(text, cursor)
                if not inner:
                    break
                if inner.group(0).startswith("</"):
                    depth -= 1
                    if depth == 0:
                        block_end = inner.start()
                        position = inner.end()
                        closed = True
                        break
                else:
                    depth += 1
                cursor = inner.end()
            if not closed:
                position = len(text)
            _strip_markup(text[body_start:block_end], chars, body_start)
            continue
        close = text.find(f"</{tag}>", body_start)
        if close < 0:
            close = len(text)
            position = len(text)
        else:
            position = close
        body = text[body_start:close]
        if tag == "script":
            chars[body_start:close] = list(_strip_js(body))
        else:
            _strip_css(body, chars, body_start)
    _strip_markup(text[position:], chars, position)
    return "".join(chars)


@dataclass(frozen=True)
class Endpoint:
    method: str
    path: str
    controller: str
    line: int


@dataclass(frozen=True)
class ContractCall:
    function: str
    method: str
    path: str
    file: str
    line: int


@dataclass
class ConsumerRow:
    endpoint: Endpoint
    calls: list[ContractCall]
    consumers: list[str]


def normalize_path(raw: str) -> str:
    """把 `/api/v1/a/{id}/b` 与 `/a/:id/b` 归一成同一种形状。"""
    path = raw.strip()
    if path.startswith("/api/v1/"):
        path = path[len("/api/v1") :]
    path = re.sub(r"\{[^}]+\}", "{}", path)
    path = re.sub(r":[A-Za-z_$][\w$]*", "{}", path)
    path = re.sub(r"/+", "/", path)
    if not path.startswith("/"):
        path = "/" + path
    return path.rstrip("/") or "/"


def first_string(args: str | None) -> str:
    if not args:
        return ""
    match = STRING_RE.search(args)
    return match.group(1) if match else ""


def scan_backend(root: Path = BACKEND_DIR) -> list[Endpoint]:
    endpoints: list[Endpoint] = []
    for path in sorted(root.rglob("*Controller.java")):
        text = path.read_text(encoding="utf-8")
        class_match = CLASS_MAPPING_RE.search(text)
        base = class_match.group(1) if class_match else ""
        for match in ANNOTATION_RE.finditer(text):
            method = match.group(1).upper()
            method_path = first_string(match.group(2))
            full = "/".join(part.strip("/") for part in (base, method_path) if part.strip("/"))
            line = text.count("\n", 0, match.start()) + 1
            endpoints.append(
                Endpoint(
                    method=method,
                    path=normalize_path("/" + full),
                    controller=path.name,
                    line=line,
                )
            )
    return endpoints


def scan_frontend_api(api_dir: Path = FRONTEND_API) -> list[ContractCall]:
    calls: list[ContractCall] = []
    # ★ 也要扫 `api/legacy/*`：页面有两条调用路径 —— 直接调契约函数，或经 legacy 包装层
    #   （`legacy/setting.ts` 的 `addSubmit` 就是 `PUT /workflow/draft`）。只扫顶层时，
    #   走包装层的端点会被判成"零消费"，于是这条门禁会把"其实接上了"报成"没人用"。
    candidates = list(api_dir.glob("*.ts")) + list((api_dir / "legacy").glob("*.ts"))
    for path in sorted(candidates):
        if path.name in {"index.ts", "types.ts", "stream-types.ts"}:
            continue
        text = path.read_text(encoding="utf-8")
        starts: list[tuple[int, str]] = []
        starts.extend((m.start(), m.group(1)) for m in EXPORT_CONST_RE.finditer(text))
        starts.extend((m.start(), m.group(1)) for m in EXPORT_FN_RE.finditer(text))
        starts.sort()
        for index, (start, function) in enumerate(starts):
            end = starts[index + 1][0] if index + 1 < len(starts) else len(text)
            block = text[start:end]
            url_match = URL_RE.search(block)
            if not url_match:
                continue
            method_match = METHOD_RE.search(block)
            if not method_match:
                continue
            line = text.count("\n", 0, start) + 1
            calls.append(
                ContractCall(
                    function=function,
                    method=method_match.group(1).upper(),
                    path=normalize_path(url_match.group(1)),
                    file=path.name,
                    line=line,
                )
            )
    return calls


def build_report(root: Path = ROOT) -> list[ConsumerRow]:
    backend_dir = root / "backend/src/main/java"
    src_dir = root / "frontend/src"
    api_dir = src_dir / "api"
    # 允许 --self-test 用临时根目录；此时模块级常量要跟着换。
    endpoints = scan_backend(backend_dir)
    calls = scan_frontend_api(api_dir)
    by_key: dict[tuple[str, str], list[ContractCall]] = {}
    for call in calls:
        by_key.setdefault((call.method, call.path), []).append(call)

    rows: list[ConsumerRow] = []
    # ★ #265：先剥注释再匹配 —— 注释里的函数名不是消费点。每个文件只读一次、只剥一次
    #   （原来是「端点 × 契约函数」次重读）。等长替换 ⇒ 行号仍是原文行号。
    stripped_cache: dict[Path, str] = {}

    def scan_text(path: Path) -> str:
        if path not in stripped_cache:
            raw = path.read_text(encoding="utf-8", errors="ignore")
            stripped_cache[path] = strip_comments(raw, path.suffix)
        return stripped_cache[path]

    for endpoint in endpoints:
        matched = by_key.get((endpoint.method, endpoint.path), [])
        if not matched:
            continue
        consumers: list[str] = []
        for call in matched:
            pattern = re.compile(CONSUMER_RE_TMPL.format(fn=re.escape(call.function)))
            for path in sorted(src_dir.rglob("*")):
                if not path.is_file() or path.suffix not in CONSUMER_SUFFIXES:
                    continue
                if api_dir in path.parents:
                    continue
                text = scan_text(path)
                for hit in pattern.finditer(text):
                    line = text.count("\n", 0, hit.start()) + 1
                    consumers.append(f"{path.relative_to(root).as_posix()}:{line}")
        rows.append(ConsumerRow(endpoint=endpoint, calls=matched, consumers=sorted(set(consumers))))
    return rows


def self_test() -> int:
    """离线造数据做反证：每个用例都要能红，不能只打印 OK。

    ★ #265 加的两条：注释里的调用不算消费 / 字符串里的 `//` 不把真调用抹掉。
    """
    failures: list[str] = []
    checked = 0
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "backend/src/main/java/demo").mkdir(parents=True)
        (root / "frontend/src/api").mkdir(parents=True)
        (root / "frontend/src/pages").mkdir(parents=True)
        (root / "backend/src/main/java/demo/FakeController.java").write_text(
            '@RequestMapping("/api/v1/fake")\n'
            "class FakeController {\n"
            '  @GetMapping("/ghost")\n'
            "  void ghost() {}\n"
            '  @GetMapping("/commented")\n'
            "  void commented() {}\n"
            '  @GetMapping("/used")\n'
            "  void used() {}\n"
            "}\n",
            encoding="utf-8",
        )
        (root / "frontend/src/api/fake.ts").write_text(
            "export const ghost = () => request({ method: 'GET', url: '/fake/ghost' })\n"
            "export const commented = () => request({ method: 'GET', url: '/fake/commented' })\n"
            "export const used = () => request({ method: 'GET', url: '/fake/used' })\n",
            encoding="utf-8",
        )
        (root / "frontend/src/pages/Ghost.vue").write_text(
            "<template><div>ghost</div></template>\n<script setup>\n</script>\n",
            encoding="utf-8",
        )
        # ★ #265 反证一：只剩注释里的调用 ⇒ 必须判零消费（修复前这里会判「已接线」）。
        #   三种注释都写一遍：模板 `<!-- -->`、脚本行注释、脚本块注释。
        (root / "frontend/src/pages/Commented.vue").write_text(
            "<template><div><!-- commented(1) --></div></template>\n"
            "<script setup>\n"
            "// commented(2)\n"
            "/* commented(3) */\n"
            "</script>\n",
            encoding="utf-8",
        )
        # ★ #265 反证二：字符串里的 `//` 不能把同一行后面的真调用一起抹掉
        #   （`'http://…'` 若被当注释，`used()` 会消失，/fake/used 就会误判成零消费）
        (root / "frontend/src/pages/Used.vue").write_text(
            "<template><div>used</div></template>\n"
            "<script setup>\n"
            "const base = 'http://localhost:8080/v1'; used()\n"
            "</script>\n",
            encoding="utf-8",
        )
        rows = build_report(root)
        by_path = {row.endpoint.path: row for row in rows}

        checked += 1
        ghost = by_path.get("/fake/ghost")
        if ghost is None or ghost.consumers:
            failures.append("假端点 /fake/ghost 没有以零消费被列出")

        checked += 1
        commented = by_path.get("/fake/commented")
        if commented is None or commented.consumers:
            failures.append("注释里的调用被算成了消费（/fake/commented）—— 剥注释没生效")

        checked += 1
        used = by_path.get("/fake/used")
        if used is None or not used.consumers:
            failures.append("字符串里的 `//` 把真调用一起剥掉了（/fake/used 被判零消费）")

    gate_failures = self_test_gate()
    checked += 4
    failures.extend(gate_failures)
    if failures:
        for item in failures:
            print(f"SELF-TEST FAIL: {item}")
        return 1
    print(
        f"SELF-TEST OK：{checked} 条离线用例 —— 假端点零消费 / 注释里的调用不算消费 /"
        " 字符串里的 // 不影响扫描 / 基线门禁三向都能红"
    )
    return 0


BASELINE = ROOT / "tools/consumer-baseline.txt"
ISSUE_RE = re.compile(r"#\d+")


def read_baseline(path: Path = BASELINE) -> dict[tuple[str, str], str]:
    """`METHOD<TAB>PATH<TAB>#issue` —— 键是 (method, path)，值是 Issue 号。"""
    if not path.exists():
        return {}
    entries: dict[tuple[str, str], str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("	")
        if len(parts) != 3:
            raise SystemExit(f"基线格式错（应为 METHOD<TAB>PATH<TAB>#issue）：{line!r}")
        entries[(parts[0].strip(), parts[1].strip())] = parts[2].strip()
    return entries


def check_against_baseline(rows, baseline: dict[tuple[str, str], str]) -> list[str]:
    """两个方向都要红：**新增的零消费**、以及**基线里已经过期的**。"""
    problems: list[str] = []
    zero = {(row.endpoint.method, row.endpoint.path): row for row in rows if not row.consumers}

    for key, row in zero.items():
        if key not in baseline:
            functions = ", ".join(call.function for call in row.calls) or "(无契约函数)"
            problems.append(
                f"{key[0]} {key[1]}：零消费且不在基线里（契约函数 {functions}）—— "
                "要么给它接一个页面入口，要么登记进 tools/consumer-baseline.txt 并写清 Issue"
            )

    for key, issue in baseline.items():
        if not ISSUE_RE.fullmatch(issue):
            problems.append(f"{key[0]} {key[1]}：基线条目没有 Issue 号（{issue!r}）")
        if key not in zero:
            problems.append(
                f"{key[0]} {key[1]}：基线里登记了零消费，但这次读数里它不零消费（或端点已不存在）—— "
                "把基线这条删掉（清单必须随修复收缩）"
            )
    return problems


def render_baseline(rows) -> str:
    lines = [
        "# 前端零消费端点的基线（`tools/check_contract_consumers.py --check` 读它）",
        "# 格式：METHOD<TAB>PATH<TAB>#issue —— 每一条都要能说出「为什么现在可以零消费」",
        "# ★ 接上入口或删掉端点之后，把对应行删掉；否则 --check 会红（清单必须随修复收缩）",
        "",
    ]
    for row in rows:
        if row.consumers:
            continue
        lines.append(f"{row.endpoint.method}	{row.endpoint.path}	#?")
    return "\n".join(lines) + "\n"


def self_test_gate() -> list[str]:
    """门禁方向的自检：新增零消费要红、过期基线要红、有 Issue 号才收。"""
    failures: list[str] = []

    class _Row:
        def __init__(self, method, path, consumers):
            self.endpoint = type("E", (), {"method": method, "path": path})()
            self.consumers = consumers
            self.calls = []

    zero_row = _Row("GET", "/ghost", [])
    used_row = _Row("GET", "/used", ["pages/x.vue:1"])

    problems = check_against_baseline([zero_row], {})
    if not problems or "/ghost" not in problems[0]:
        failures.append("新增零消费没有报")

    problems = check_against_baseline([zero_row], {("GET", "/ghost"): "#1"})
    if problems:
        failures.append(f"登记了 Issue 的基线仍被报：{problems}")

    problems = check_against_baseline([used_row], {("GET", "/used"): "#1"})
    if not problems:
        failures.append("基线过期（现在有人用了）没有报")

    problems = check_against_baseline([zero_row], {("GET", "/ghost"): "issue-1"})
    if not any("Issue 号" in item for item in problems):
        failures.append("基线条目缺 Issue 号没有报")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description="前后端配对第三格：前端消费点只读读数")
    parser.add_argument("--json", action="store_true", help="输出 JSON")
    parser.add_argument("--self-test", action="store_true", help="内置反证")
    parser.add_argument("--check", action="store_true", help="门禁：与基线比对，退 0/1")
    parser.add_argument("--baseline", action="store_true", help="按当前读数重排基线（人工过一遍再用）")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    rows = build_report()
    if args.baseline:
        BASELINE.write_text(render_baseline(rows), encoding="utf-8", newline="\n")
        print(f"已写入 {BASELINE.relative_to(ROOT)}：{sum(1 for r in rows if not r.consumers)} 条（记得逐条补 Issue 号）")
        return 0
    if args.check:
        problems = check_against_baseline(rows, read_baseline())
        if problems:
            print(f"✗ 第三格门禁：{len(problems)} 条对不上\n")
            for item in problems:
                print(f"  - {item}")
            return 1
        print(f"✓ 第三格门禁通过：零消费 {sum(1 for r in rows if not r.consumers)} 条，全部在基线里且仍为零消费")
        return 0
    zero = [row for row in rows if not row.consumers]
    if args.json:
        payload = [
            {
                "endpoint": asdict(row.endpoint),
                "calls": [asdict(call) for call in row.calls],
                "consumers": row.consumers,
            }
            for row in rows
        ]
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0

    print("# 前端消费点读数（#79）")
    print(f"后端端点与契约匹配：{len(rows)} 条；前端零消费：{len(zero)} 条\n")

    domains: dict[str, list[int]] = {}
    for row in rows:
        domain = row.endpoint.path.split("/")[1] or "(root)"
        domains.setdefault(domain, [0, 0])[0] += 1
        if not row.consumers:
            domains[domain][1] += 1
    print("# 按域（端点数 / 零消费数）")
    for domain in sorted(domains):
        total, zero_count = domains[domain]
        print(f"{domain:24} {total:3} / {zero_count}")
    print()

    for row in zero:
        functions = ", ".join(call.function for call in row.calls)
        print(f"ZERO  {row.endpoint.method:6} {row.endpoint.path:55} {functions}")
        print(f"      backend: {row.endpoint.controller}:{row.endpoint.line}")
    print("\n# 有消费的端点（前 20 条）")
    for row in [item for item in rows if item.consumers][:20]:
        print(f"USED  {row.endpoint.method:6} {row.endpoint.path:55} -> {row.consumers[0]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
