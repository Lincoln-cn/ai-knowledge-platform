#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_mock_endpoints.py -- mock 不许实现契约里没有的端点

## 为什么要有这一条（`#211` 换来的）

`POST /knowledge-bases/{kbId}/uploads` 那次事故的形状是：**mock 真的实现了它**。
于是 mock 模式下这条路能跑通、开发时完全看不出"真后端没有这个端点"，
而契约、后端台账、脚本账各自还登记着它 —— 四处互相打气，直到有人去真栈上试。

单看任何一处都像"已经做了"；只有把 **mock 路由** 与 **契约声明** 两处放一起比，
"mock 在替一个不存在的能力作保"这件事才现形。

## 判据

- 扫 `src/mock/handlers/*.ts` 里的路由元组 `['METHOD', '/path', handler]`；
- 扫 `src/api/**/*.ts` 里的请求声明 `url: '/path'`（同一个 `:param` 风格）；
- **每条 mock 路由的路径**都必须在契约里出现过，否则退 1。

## 刻意不做的两件事（第一维）

1. **不比对 method**：契约里 `request({ url })` 常常省略 method（默认 GET），
   强行比对会把"契约用了默认 GET"误判成"mock 多实现了一个方法"。
   路径对不上已经能抓住 `#211` 那种整条端点不存在的情形。
2. **不要求"契约有 ⇒ mock 有"**：mock 本来只覆盖一部分，缺是正常的。

## 第二维：响应形状（`#262`）

第一维只回答"这条端点存在吗"。`#262` 的形状是：**端点存在、mock 也实现了，
但返回的东西的形状不对** —— 契约 `{ graph, updatedAt, version }`，mock 返回裸图
`{ nodes, edges }`。路径对得上，于是所有闸门都绿，而页面里
`const { graph } = await rollbackWorkflow()` 拿到 `undefined`：回滚"成功"了、画布没变化。

判据（`src/mock/handlers/*.ts` 的每条路由 vs `src/api/**/*.ts` 里同路径的 `request<T>`）：

- 取 handler **return 出去那个值**的顶层形状：对象 / 数组 / 裸标量；
  `return helper(...)` 就追到 helper 的返回表达式（同文件优先，其次 `fixtures`）；
- 种类不同（对象 vs 裸标量、数组 vs 对象）⇒ 不一致；
- 两侧都是对象时，契约声明的**必填键**必须都出现在 mock 的对象字面量里 ⇒ 缺键即不一致；
- 两侧都是数组时，元素形状只在两侧都解析得出时才比（解析不出不算错）。

刻意"宁可少报"：解析不出形状的一律**跳过并计数**，不猜。读数里会打出跳过多少条 ——
跳过太多的闸门等于没管，这条读数就是给它的护栏。

用法：
    python check_mock_endpoints.py               # 判据（两维），退 0/1
    python check_mock_endpoints.py --self-check  # 离线自检 15 条
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MOCK = ROOT / "src" / "mock" / "handlers"
API = ROOT / "src" / "api"

# `['GET', '/knowledge-bases/:kbId/documents', …]`
ROUTE_RE = re.compile(r"\[\s*'(GET|POST|PUT|PATCH|DELETE)',\s*'([^']+)'")
# `url: '/documents/:id'` 与 `url: `/documents/${id}``
URL_RE = re.compile(r"url:\s*[`']([^`']+)[`']")

PARAM_RE = re.compile(r":([A-Za-z_][\w]*)")

# 刻意不走 `src/api` 契约层的 mock 路由：路径 -> 为什么。
# ★ 每一条都必须写清理由；某条不再出现在 mock 里时，判据会红（清单必须随撤销收缩）。
# ★ 这里不是"放行名单"：它要求的是"说不出理由就不许留"。
KNOWN_MOCK_ONLY = {
    "/conversations/chat": (
        "流式对话走 `src/lib/sse.ts`（`fetch` + `ReadableStream`，不是 `request()`）—— "
        "契约层只有非流式接口，SSE 端口本来就在 api 层之外"
    ),
}


def normalize(path: str) -> str:
    """把 `:id` / `${id}` / `{*}` 一律归一成 `:x`，并去掉查询串与尾部斜杠。"""
    p = path.split("?")[0]
    p = re.sub(r"\$\{[^}]*\}", ":x", p)
    p = p.replace("{*}", ":x")
    p = PARAM_RE.sub(":x", p)
    p = re.sub(r"/+", "/", p)
    return p.rstrip("/") or "/"


def stale_allow(routes, allow=None) -> list[str]:
    """登记了例外、但 mock 里已经没有这条路由 ⇒ 必须删登记（清单随撤销收缩）。"""
    allow = KNOWN_MOCK_ONLY if allow is None else allow
    seen = {norm for _, _, norm in routes}
    return [
        f"{path}：在 KNOWN_MOCK_ONLY 里登记了理由，但 mock 已没有这条路由 —— "
        "把这条登记删掉（清单必须随撤销收缩）"
        for path in allow
        if path not in seen
    ]


def mock_routes(root: Path = MOCK) -> list[tuple[str, str, str]]:
    """返回 [(method, 原始路径, 归一路径)]。"""
    out: list[tuple[str, str, str]] = []
    for file in sorted(root.glob("*.ts")):
        text = file.read_text(encoding="utf-8")
        for method, path in ROUTE_RE.findall(text):
            out.append((method, path, normalize(path)))
    return out


def contract_paths(root: Path = API) -> set[str]:
    out: set[str] = set()
    for file in sorted(root.glob("**/*.ts")):
        if file.name.endswith(".test.ts"):
            continue
        text = file.read_text(encoding="utf-8")
        for url in URL_RE.findall(text):
            out.add(normalize(url))
    return out


def problems(routes, declared, allow=KNOWN_MOCK_ONLY) -> list[str]:
    out: list[str] = []
    for method, raw, norm in routes:
        if norm in allow:
            continue
        if norm not in declared:
            out.append(
                f"{method} {raw}：mock 实现了这条路由，但 `src/api` 里没有任何声明 —— "
                "mock 在替一个前端契约里不存在的能力作保（`#211` 的形状）"
            )
    return out


# ══════════════════════════════════════════════════════════════════════════
# 第二维：响应形状（`#262`）
# ══════════════════════════════════════════════════════════════════════════

_QUOTES = ("'", '"', "`")


def _skip_string(text: str, i: int) -> int:
    """i 指向引号，返回该字面量结束后的下标；模板串整体跳过（不解释 `${}`）。"""
    quote = text[i]
    i += 1
    n = len(text)
    while i < n:
        c = text[i]
        if c == "\\":
            i += 2
            continue
        if c == quote:
            return i + 1
        i += 1
    return n


def _skip_comment(text: str, i: int) -> int:
    """i 指向注释开头；不是注释则原样返回。"""
    n = len(text)
    if text.startswith("//", i):
        j = text.find("\n", i)
        return n if j < 0 else j + 1
    if text.startswith("/*", i):
        j = text.find("*/", i + 2)
        return n if j < 0 else j + 2
    return i


def _match(text: str, i: int, opener: str, closer: str) -> int:
    """i 指向开括号，返回配对闭括号下标；字符串/注释里的括号不计数。找不到返回 -1。"""
    depth = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c in _QUOTES:
            i = _skip_string(text, i)
            continue
        if c == "/" and text.startswith(("//", "/*"), i):
            i = _skip_comment(text, i)
            continue
        if c == opener:
            depth += 1
        elif c == closer:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def _match_angle(text: str, i: int) -> int:
    """i 指向 `<`，返回配对的 `>`（`=>` 里的 `>` 不算）。找不到返回 -1。"""
    depth = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c in _QUOTES:
            i = _skip_string(text, i)
            continue
        if c == "/" and text.startswith(("//", "/*"), i):
            i = _skip_comment(text, i)
            continue
        if c == "<":
            depth += 1
        elif c == ">" and text[i - 1] != "=":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def _strip_comments(text: str) -> str:
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c in _QUOTES:
            j = _skip_string(text, i)
            out.append(text[i:j])
            i = j
            continue
        if c == "/" and text.startswith(("//", "/*"), i):
            i = _skip_comment(text, i)
            out.append(" ")
            continue
        out.append(c)
        i += 1
    return "".join(out)


def _split_top(text: str, seps: str = ",") -> list[str]:
    """按顶层分隔符切分（括号/方括号/花括号内的不算）。"""
    parts: list[str] = []
    start = 0
    depth = 0
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c in _QUOTES:
            i = _skip_string(text, i)
            continue
        if c == "/" and text.startswith(("//", "/*"), i):
            i = _skip_comment(text, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and c in seps:
            parts.append(text[start:i])
            start = i + 1
        i += 1
    parts.append(text[start:])
    return parts


def _split_union(text: str) -> list[str]:
    """按顶层 `|` 切联合类型（`<...>` 里的泛型参数先算进深度）。"""
    parts: list[str] = []
    start = 0
    depth = 0
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c in _QUOTES:
            i = _skip_string(text, i)
            continue
        if c == "/" and text.startswith(("//", "/*"), i):
            i = _skip_comment(text, i)
            continue
        if text.startswith("=>", i):
            i += 2
            continue
        if c in "([{<":
            depth += 1
        elif c in ")]}>":
            depth -= 1
        elif c == "|" and depth == 0:
            parts.append(text[start:i])
            start = i + 1
        i += 1
    parts.append(text[start:])
    return [p.strip() for p in parts if p.strip()]


def _strip_outer_parens(text: str) -> str:
    t = text.strip()
    while t.startswith("(") and _match(t, 0, "(", ")") == len(t) - 1:
        t = t[1:-1].strip()
    return t


def _short(text: str, limit: int = 60) -> str:
    one = " ".join(text.split())
    return one if len(one) <= limit else one[:limit] + "…"


@dataclass(frozen=True)
class Shape:
    """一个顶层形状。kind = object / array / string / number / boolean / nullish / any / multi。"""

    kind: str
    keys: tuple[str, ...] = ()
    optional: tuple[str, ...] = ()
    partial: bool = False
    elem: "Shape | None" = None
    alts: tuple["Shape", ...] = ()


def _shape_text(shape: Shape) -> str:
    if shape.kind == "multi":
        return " | ".join(_shape_text(a) for a in shape.alts)
    if shape.kind == "object":
        items = [k + ("?" if k in shape.optional else "") for k in shape.keys]
        if shape.partial:
            items.append("…")
        return "{" + ", ".join(items) + "}"
    if shape.kind == "array":
        elem = _shape_text(shape.elem) if shape.elem else ""
        if shape.elem is not None and shape.elem.kind == "multi":
            elem = "(" + elem + ")"
        return elem + "[]"
    return shape.kind


def _shapes_text(shapes: tuple[Shape, ...]) -> str:
    return " | ".join(_shape_text(s) for s in shapes)


def _multi(left: Shape, right: Shape) -> Shape:
    """两个可能的返回值合成一个多形状。"""
    alts: list[Shape] = []
    for shape in (left, right):
        for alt in shape.alts if shape.kind == "multi" else (shape,):
            if alt not in alts:
                alts.append(alt)
    return alts[0] if len(alts) == 1 else Shape("multi", alts=tuple(alts))


def _combine(shapes: list[Shape]) -> Shape:
    result = shapes[0]
    for shape in shapes[1:]:
        result = _multi(result, shape)
    return result


def _compatible(contract: Shape, mock: Shape) -> bool | None:
    """契约的某一个分支能不能接受 mock 的形状。None = 这一支判不了。"""
    if contract.kind == "any":
        return True
    if mock.kind == "multi":
        # 元素形状本身是多分支（数组里两种对象）：全对得上才算一致，一半对一半不对就判不了
        verdicts = [_compatible(contract, alt) for alt in mock.alts]
        if all(v is False for v in verdicts):
            return False
        if all(v is True for v in verdicts):
            return True
        return None
    if mock.kind == "object":
        if contract.kind != "object":
            return False
        missing = [k for k in contract.keys if k not in contract.optional and k not in mock.keys]
        if mock.partial or contract.partial:
            return None if missing else True
        return not missing
    if mock.kind == "array":
        if contract.kind != "array":
            return False
        if contract.elem is not None and mock.elem is not None:
            return _compatible(contract.elem, mock.elem)
        return True
    return contract.kind == mock.kind


def _judge(contracts: tuple[Shape, ...], mock: Shape) -> bool | None:
    """True 一致 / False 不一致 / None 判不了（跳过）。

    ★ mock 侧是多分支联合（三元 / `??` / 多个 return）时**只认两种确定结论**：
    所有分支都对得上才算一致，所有分支都对不上才算不一致；一半对一半不对
    往往是"守卫收窄"（`if (!x) throw` 之后返回非空）而不是真有问题 ——
    这种既不敢判红也不敢判绿，按"解析不出"跳过并计数（宁可少报）。
    """
    if mock.kind == "multi":
        verdicts = [_judge(contracts, alt) for alt in mock.alts]
        if all(v is False for v in verdicts):
            return False
        if all(v is True for v in verdicts):
            return True
        return None
    undecided = False
    for contract in contracts:
        verdict = _compatible(contract, mock)
        if verdict is True:
            return True
        if verdict is None:
            undecided = True
    return None if undecided else False


def _mismatch_detail(contracts: tuple[Shape, ...], mock: Shape) -> str:
    """给不一致的那条写一句人话：缺哪个键、还是种类都不一样。"""
    if mock.kind == "multi":
        for alt in mock.alts:
            if _judge(contracts, alt) is False:
                return _mismatch_detail(contracts, alt)
        return "多个返回形状里有对不上的"
    for contract in contracts:
        if contract.kind == "object" and mock.kind == "object":
            missing = [k for k in contract.keys if k not in contract.optional and k not in mock.keys]
            if missing:
                return "契约必填键缺失：" + "、".join(missing)
        if contract.kind != mock.kind:
            return f"种类不同：契约 {contract.kind} vs mock {mock.kind}"
    return "形状不一致"


# ── mock 侧：把 handler 的 return 值解析成形状 ────────────────────────────

_ROUTE_HEAD_RE = re.compile(r"\[\s*'(GET|POST|PUT|PATCH|DELETE)'\s*,\s*'([^']+)'\s*,")

_MEMBER_KEY_RE = re.compile(
    r"^\s*(?:readonly\s+)?(?P<key>[A-Za-z_$][\w$]*|'[^']*'|\"[^\"]*\"|\d+)\s*(?P<opt>\?)?\s*[:(]"
)


def _object_keys(literal: str) -> tuple[tuple[str, ...], tuple[str, ...], bool]:
    """从 `{...}` 文本取顶层键。返回 (键, 可选键, partial)。

    partial = 有展开（`...x`）、计算键（`[k]`）、索引签名或认不出的成员 ——
    键集不完整，这时候"缺键"不能当证据（宁可少报）。
    """
    body = literal.strip()
    if body.startswith("{"):
        body = body[1:]
    if body.rstrip().endswith("}"):
        body = body.rstrip()[:-1]
    keys: list[str] = []
    optional: list[str] = []
    partial = False
    for raw in _split_top(body, ",;"):
        member = _strip_comments(raw).strip()
        if not member:
            continue
        if member.startswith("...") or member.startswith("[") or member.startswith("new "):
            partial = True
            continue
        m = _MEMBER_KEY_RE.match(member)
        if not m:
            if re.fullmatch(r"[A-Za-z_$][\w$]*", member):  # 简写属性
                keys.append(member)
            else:
                partial = True
            continue
        key = m.group("key").strip("'\"")
        if key not in keys:
            keys.append(key)
        if m.group("opt") and key not in optional:
            optional.append(key)
    return tuple(keys), tuple(optional), partial


def _find_assign(text: str, start: int) -> int | None:
    """从 start 起找顶层赋值号；不是声明（遇 `;` 等）返回 None。"""
    depth = 0
    i = start
    n = len(text)
    while i < n:
        c = text[i]
        if c in _QUOTES:
            i = _skip_string(text, i)
            continue
        if c == "/" and text.startswith(("//", "/*"), i):
            i = _skip_comment(text, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                return None
            depth -= 1
        elif depth == 0:
            if c == ";":
                return None
            if (
                c == "="
                and text[i + 1:i + 2] not in ("=", ">")
                and text[i - 1:i] not in ("=", "!", "<", ">", "+", "-", "*", "/")
            ):
                return i
        i += 1
    return None


def _read_initializer(text: str, start: int) -> str:
    """从 start 起读一个初始化表达式，到同层 `;` 或行尾（非续行）为止。"""
    depth = 0
    i = start
    n = len(text)
    while i < n:
        c = text[i]
        if c in _QUOTES:
            i = _skip_string(text, i)
            continue
        if c == "/" and text.startswith(("//", "/*"), i):
            i = _skip_comment(text, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif depth == 0 and c == ";":
            break
        elif depth == 0 and c == "\n":
            rest = text[i + 1:].lstrip()
            prev = text[start:i].rstrip()[-1:]
            if prev and prev not in "=+-*/%&|?:,(<>" and rest[:1] not in (".", "?", ":", "|", "&", "+", "=", ")"):
                break
        i += 1
    return text[start:i].strip()


def _top_arrow(text: str) -> int | None:
    """找顶层的 `=>`（参数括号闭合之后的那个）。"""
    depth = 0
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c in _QUOTES:
            i = _skip_string(text, i)
            continue
        if c == "/" and text.startswith(("//", "/*"), i):
            i = _skip_comment(text, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and text.startswith("=>", i):
            return i
        i += 1
    return None


def _locals_of(text: str) -> dict[str, str]:
    """收集一段代码里的局部声明名 → 初始化表达式（同名的取第一个）。"""
    env: dict[str, str] = {}
    for m in re.finditer(r"(?:^|[^.\w$])(?:const|let|var)\s+([A-Za-z_$][\w$]*)", text):
        name = m.group(1)
        eq = _find_assign(text, m.end())
        if eq is None:
            continue
        init = _read_initializer(text, eq + 1)
        if init and name not in env:
            env[name] = init
    return env


def _opens_function_body(text: str, brace: int, open_paren: int) -> bool:
    """`{` 是不是一个函数体（而不是 if/for 的块、也不是对象字面量）。"""
    j = brace - 1
    while j >= 0 and text[j] in " \t\r\n":
        j -= 1
    if j < 0:
        return False
    if text[j] == ">":
        k = j - 1
        while k >= 0 and text[k] in " \t\r\n":
            k -= 1
        return k >= 0 and text[k] == "="
    if text[j] == ")" and open_paren >= 0:
        k = open_paren - 1
        while k >= 0 and text[k] in " \t\r\n":
            k -= 1
        end = k + 1
        while k >= 0 and (text[k].isalnum() or text[k] in "_$"):
            k -= 1
        return text[k + 1:end] not in ("if", "for", "while", "switch", "catch", "with", "return")
    return False


def _read_expression(text: str, start: int) -> tuple[str, int]:
    """从 start 读一个表达式，到同层 `;` 或闭合 `}` 为止。返回 (表达式, 停下处下标)。"""
    depth = 0
    i = start
    n = len(text)
    while i < n:
        c = text[i]
        if c in _QUOTES:
            i = _skip_string(text, i)
            continue
        if c == "/" and text.startswith(("//", "/*"), i):
            i = _skip_comment(text, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif depth == 0 and c == ";":
            break
        elif depth == 0 and c == "\n":
            # 行尾不是运算符/箭头就收尾：否则 `return []` 会把下一行整段吞进来
            rest = text[i + 1:].lstrip()
            prev = text[start:i].rstrip()[-1:]
            if prev and prev not in "=+-*/%&|?:,(<>" and rest[:1] not in (".", "?", ":", "|", "&", "+", "=", ")"):
                break
        i += 1
    return text[start:i].strip(), i


def _return_expressions_of(body: str) -> list[str]:
    """抽出一个函数体（块体或表达式体）**在它自己这一层** return 的表达式。

    箭头函数体里（`.map((x) => { ... return ... })`）的 return 不算，
    否则会把回调的返回值当成接口的返回值。
    """
    t = _strip_comments(body).strip()
    if not t.startswith("{"):
        expr = _strip_outer_parens(t.rstrip(";").strip())
        return [expr] if expr else []
    if _match(t, 0, "{", "}") != len(t) - 1:
        return []
    exprs: list[str] = []
    stack: list[bool] = []
    parens: list[int] = []
    last_closed = -1
    i = 1
    n = len(t)
    while i < n:
        c = t[i]
        if c in _QUOTES:
            i = _skip_string(t, i)
            continue
        if c == "/" and t.startswith(("//", "/*"), i):
            i = _skip_comment(t, i)
            continue
        if c == "(":
            parens.append(i)
            i += 1
            continue
        if c == ")":
            last_closed = parens.pop() if parens else -1
            i += 1
            continue
        if c == "{":
            stack.append(_opens_function_body(t, i, last_closed))
            i += 1
            continue
        if c == "}":
            if stack:
                stack.pop()
            i += 1
            continue
        if t.startswith("return", i) and not any(stack):
            prev = t[i - 1] if i > 0 else ""
            if prev and (prev.isalnum() or prev in "_$"):
                i += 1
                continue
            expr, j = _read_expression(t, i + len("return"))
            if expr:
                exprs.append(expr)
            i = j
            continue
        i += 1
    return exprs


def _arrow_body(handler: str) -> str | None:
    h = _strip_comments(handler).strip()
    if h.startswith("function"):
        open_paren = h.find("(")
        if open_paren < 0:
            return None
        close = _match(h, open_paren, "(", ")")
        if close < 0:
            return None
        brace = _function_body(h, close + 1)
        if brace < 0:
            return None
        end = _match(h, brace, "{", "}")
        if end < 0:
            return None
        return h[brace:end + 1]
    arrow = _top_arrow(h)
    if arrow is None:
        return None
    return h[arrow + 2:].strip()


def _function_body(text: str, after: int) -> int:
    """返回函数体那个 `{` 的下标；跳过一个返回类型里出现的对象字面量。"""
    i = after
    n = len(text)
    while i < n:
        c = text[i]
        if c in _QUOTES:
            i = _skip_string(text, i)
            continue
        if c == "/" and text.startswith(("//", "/*"), i):
            i = _skip_comment(text, i)
            continue
        if c == "{":
            end = _match(text, i, "{", "}")
            if end < 0:
                return -1
            j = i - 1
            while j >= 0 and text[j] in " \t\r\n":
                j -= 1
            # 返回类型写法的收尾字符也算"这里就是函数体"：`: TrendPoint[] {`、`): Foo<T> {`
            if j >= 0 and (text[j] in ")}]>" or text[j].isalnum() or text[j] in "_$"):
                return i
            i = end + 1
            continue
        if c in ";}":
            return -1
        i += 1
    return -1


def _return_expressions(handler: str) -> list[str]:
    body = _arrow_body(handler)
    if body is None:
        return []
    return _return_expressions_of(body)


def _split_ternary(expr: str) -> tuple[str, str, str] | None:
    depth = 0
    q = -1
    i = 0
    n = len(expr)
    while i < n:
        c = expr[i]
        if c in _QUOTES:
            i = _skip_string(expr, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "?" and depth == 0:
            if expr[i + 1:i + 2] not in (".", "?") and expr[i - 1:i] != "?":
                q = i
                break
        i += 1
    if q < 0:
        return None
    depth = 0
    nested = 0
    i = q + 1
    while i < n:
        c = expr[i]
        if c in _QUOTES:
            i = _skip_string(expr, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and c == "?" and expr[i + 1:i + 2] not in (".", "?") and expr[i - 1:i] != "?":
            nested += 1
        elif depth == 0 and c == ":":
            if nested:
                nested -= 1
            else:
                return expr[:q].strip(), expr[q + 1:i].strip(), expr[i + 1:].strip()
        i += 1
    return None


def _split_logical(expr: str) -> tuple[str, str, str] | None:
    depth = 0
    i = 0
    while i < len(expr):
        c = expr[i]
        if c in _QUOTES:
            i = _skip_string(expr, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0:
            for op in ("??", "||", "&&"):
                if expr.startswith(op, i):
                    return expr[:i].strip(), op, expr[i + len(op):].strip()
        i += 1
    return None


def _strip_as(expr: str) -> str:
    """`x as T` / `x satisfies T` → 取左侧（形状由值决定）。"""
    depth = 0
    i = 0
    while i < len(expr):
        c = expr[i]
        if c in _QUOTES:
            i = _skip_string(expr, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0 and (expr.startswith(" as ", i) or expr.startswith(" satisfies ", i)):
            return expr[:i].strip()
        i += 1
    return expr


def _split_call(expr: str) -> tuple[str, str] | None:
    """`callee(args)` → (callee, args)；末端不是一个调用就返回 None。

    ★ 认**最外层的那个调用**：`a(x).map(y)` 要给出 `("a(x).map", "y")`，
    而不是被第一个 `(` 卡住放弃 —— 方法链是本项目 mock 最主要的返回形态。
    """
    i = 0
    n = len(expr)
    while i < n:
        c = expr[i]
        if c in _QUOTES:
            i = _skip_string(expr, i)
            continue
        if c == "[":
            # 数组/下标也在接收者位置出现（`[...orgs].sort(...)`），跳过整段而不是一票否决
            close = _match(expr, i, "[", "]")
            if close < 0:
                return None
            i = close + 1
            continue
        if c == "(":
            close = _match(expr, i, "(", ")")
            if close < 0:
                return None
            if close == n - 1:
                return expr[:i].strip(), expr[i + 1:close]
            i = close + 1
            continue
        i += 1
    return None


# ── 契约侧：从 `request<T>({ url })` 取返回类型字面量 ─────────────────────

_PRIMITIVES = {
    "string": "string",
    "number": "number",
    "boolean": "boolean",
    "null": "nullish",
    "undefined": "nullish",
    "void": "nullish",
    "never": "nullish",
    "any": "any",
    "unknown": "any",
    "object": "any",
}

_INTERFACE_RE = re.compile(
    r"(?:export\s+)?interface\s+([A-Za-z_$][\w$]*)\s*(?:<[^{]*?>)?\s*(?:extends\s+([^{]+?))?\s*\{"
)
_TYPE_ALIAS_RE = re.compile(r"(?:export\s+)?type\s+([A-Za-z_$][\w$]*)\s*(?:<[^=]*?>)?\s*=")
_REQUEST_RE = re.compile(r"\brequest\s*<")
_URL_LITERAL_RE = re.compile(r"""url\s*:\s*(['"`])([^'"`]+)\1""")
_METHOD_LITERAL_RE = re.compile(r"method\s*:\s*'(GET|POST|PUT|PATCH|DELETE)'")


@dataclass
class _Types:
    named: dict[str, str] = field(default_factory=dict)
    partial: set[str] = field(default_factory=set)


def _read_type_alias(text: str, start: int) -> str:
    """读一个 type 别名的右侧，到不续行的行尾或 `;`。"""
    depth = 0
    i = start
    n = len(text)
    while i < n:
        c = text[i]
        if c in _QUOTES:
            i = _skip_string(text, i)
            continue
        if c == "/" and text.startswith(("//", "/*"), i):
            i = _skip_comment(text, i)
            continue
        if text.startswith("=>", i):
            i += 2
            continue
        if c in "([{<":
            depth += 1
        elif c in ")]}>":
            depth -= 1
        elif depth == 0 and c == ";":
            break
        elif depth == 0 and c == "\n":
            prev = text[:i].rstrip()
            nxt = text[i + 1:].lstrip()
            if prev.endswith(("|", "&", "=", ",", "<")) or nxt[:1] in ("|", "&"):
                i += 1
                continue
            break
        i += 1
    return text[start:i].strip()


def _collect_named_types(text: str, types: _Types) -> None:
    for m in _INTERFACE_RE.finditer(text):
        name = m.group(1)
        brace = m.end() - 1
        end = _match(text, brace, "{", "}")
        if end < 0:
            continue
        types.named.setdefault(name, text[brace:end + 1])
        if m.group(2):
            # 继承来的键不在本文件里 —— 只记 partial，不让"缺键"当证据
            types.partial.add(name)
    for m in _TYPE_ALIAS_RE.finditer(text):
        name = m.group(1)
        expr = _read_type_alias(text, m.end())
        if expr:
            types.named.setdefault(name, expr)


def _type_shapes(text: str, types: _Types, depth: int = 0, seen: frozenset[str] = frozenset()) -> tuple[Shape, ...] | None:
    """把一个类型字面量解析成若干可选形状；解析不出返回 None（跳过，不猜）。"""
    if depth > 8:
        return None
    t = _strip_comments(text).strip().rstrip(";,").strip()
    if not t:
        return None
    t = _strip_outer_parens(t)
    parts = _split_union(t)
    if len(parts) > 1:
        out: list[Shape] = []
        for part in parts:
            sub = _type_shapes(part, types, depth + 1, seen)
            if sub is None:
                return None
            for shape in sub:
                if shape not in out:
                    out.append(shape)
        return tuple(out)
    if t.endswith("[]"):
        base = _type_shapes(t[:-2], types, depth + 1, seen)
        if base is None or len(base) != 1:
            return None
        return (Shape("array", elem=base[0]),)
    m = re.fullmatch(r"Array\s*<(.+)>", t, re.S)
    if m:
        base = _type_shapes(m.group(1), types, depth + 1, seen)
        if base is None or len(base) != 1:
            return None
        return (Shape("array", elem=base[0]),)
    if t.startswith("{"):
        keys, optional, partial = _object_keys(t)
        return (Shape("object", keys=keys, optional=optional, partial=partial),)
    if t.startswith("[") and t.endswith("]"):
        return (Shape("array"),)
    if re.fullmatch(r"[A-Za-z_$][\w$.]*\s*(?:<.*>)?", t, re.S):
        name = t.split("<")[0].strip()
        if name in _PRIMITIVES:
            return (Shape(_PRIMITIVES[name]),)
        if name in types.named and name not in seen:
            shapes = _type_shapes(types.named[name], types, depth + 1, seen | {name})
            if shapes and name in types.partial:
                shapes = tuple(
                    Shape("object", keys=s.keys, optional=s.optional, partial=True) if s.kind == "object" else s
                    for s in shapes
                )
            return shapes
        return None
    return None


@dataclass(frozen=True)
class _ContractReturn:
    label: str
    method: str | None
    path: str
    shapes: tuple[Shape, ...] | None


def _contract_returns(texts: dict[str, str]) -> list[_ContractReturn]:
    types = _Types()
    for text in texts.values():
        _collect_named_types(text, types)
    out: list[_ContractReturn] = []
    for label, text in texts.items():
        for m in _REQUEST_RE.finditer(text):
            open_angle = text.find("<", m.start())
            if open_angle < 0:
                continue
            close = _match_angle(text, open_angle)
            if close < 0:
                continue
            j = close + 1
            while j < len(text) and text[j] in " \t\r\n":
                j += 1
            if j >= len(text) or text[j] != "(":
                continue
            paren_end = _match(text, j, "(", ")")
            if paren_end < 0:
                continue
            args = text[j + 1:paren_end]
            brace = args.find("{")
            if brace < 0 or args[:brace].strip():
                continue
            obj_end = _match(args, brace, "{", "}")
            if obj_end < 0:
                continue
            options = args[brace:obj_end + 1]
            url = _URL_LITERAL_RE.search(options)
            if not url:
                continue
            method_m = _METHOD_LITERAL_RE.search(options)
            line = text.count("\n", 0, m.start()) + 1
            out.append(
                _ContractReturn(
                    f"{label}:{line}",
                    method_m.group(1) if method_m else None,
                    url.group(2),
                    _type_shapes(text[open_angle + 1:close], types),
                )
            )
    return out


# ── 把两边接起来判 ──────────────────────────────────────────────────────

@dataclass
class _MockFile:
    label: str
    functions: dict[str, str] = field(default_factory=dict)
    values: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class _RouteShape:
    file: str
    line: int
    method: str
    path: str
    shapes: tuple[Shape, ...] | None
    reason: str = ""


@dataclass(frozen=True)
class _Mismatch:
    route: _RouteShape
    contract: _ContractReturn
    contract_text: str
    mock_text: str
    detail: str


@dataclass
class ShapeReport:
    routes: int = 0
    consistent: int = 0
    inconsistent: list[_Mismatch] = field(default_factory=list)
    skipped: list[tuple[str, str]] = field(default_factory=list)


_BUILTIN_CALLS = {
    "String": "string",
    "Number": "number",
    "Boolean": "boolean",
    "parseInt": "number",
    "parseFloat": "number",
    "Date.now": "number",
    "Math.max": "number",
    "Math.min": "number",
    "Math.round": "number",
    "Math.abs": "number",
    "Math.floor": "number",
    "Math.ceil": "number",
    "JSON.stringify": "string",
    "Object.keys": "array",
    "Object.values": "array",
    "Object.entries": "array",
    "Array.isArray": "boolean",
    "isNaN": "boolean",
    "Number.isFinite": "boolean",
    "crypto.randomUUID": "string",
    "encodeURIComponent": "string",
}

_STRING_METHODS = {
    "join",
    "toISOString",
    "toFixed",
    "trim",
    "trimStart",
    "trimEnd",
    "toLowerCase",
    "toUpperCase",
    "padStart",
    "padEnd",
    "replace",
    "replaceAll",
    "toString",
    "format",
    "substring",
    "substr",
    "charAt",
    "normalize",
}

_BOOLEAN_METHODS = {
    "some",
    "every",
    "includes",
    "indexOf",
    "lastIndexOf",
    "startsWith",
    "endsWith",
    "isArray",
    "test",
    "findIndex",
    "has",
}


class _MockShaper:
    def __init__(self, texts: dict[str, str]):
        self.files = {label: _analyze_mock_file(label, text) for label, text in texts.items()}
        self.index: dict[str, list[tuple[str, str]]] = {}
        for label, mf in self.files.items():
            for name, body in mf.functions.items():
                self.index.setdefault(name, []).append((label, "fn|" + body))
            for name, init in mf.values.items():
                self.index.setdefault(name, []).append((label, "val|" + init))

    def route_shapes(self, texts: dict[str, str]) -> list[_RouteShape]:
        out: list[_RouteShape] = []
        for label, text in texts.items():
            for m in _ROUTE_HEAD_RE.finditer(text):
                method, path = m.group(1), m.group(2)
                line = text.count("\n", 0, m.start()) + 1
                close = _match(text, m.start(), "[", "]")
                if close < 0:
                    out.append(_RouteShape(label, line, method, path, None, "路由元组没有配对的 `]`"))
                    continue
                handler = text[m.end():close]
                exprs = _return_expressions(handler)
                if not exprs:
                    out.append(_RouteShape(label, line, method, path, None, "handler 里认不出 return 值"))
                    continue
                env = _locals_of(handler)
                shapes: list[Shape] = []
                reason = ""
                for expr in exprs:
                    shape = self._infer(expr, label, env)
                    if shape is None:
                        reason = f"返回表达式解析不出：{_short(expr)}"
                        break
                    shapes.append(shape)
                if reason:
                    out.append(_RouteShape(label, line, method, path, None, reason))
                    continue
                out.append(_RouteShape(label, line, method, path, tuple(shapes)))
        return out

    # ── 形状推断 ──

    def _infer(
        self, expr: str, label: str, env: dict[str, str], depth: int = 0, seen: frozenset[tuple[str, ...]] = frozenset()
    ) -> Shape | None:
        if depth > 12:
            return None
        e = _strip_comments(expr).strip().rstrip(";,").strip()
        e = _strip_outer_parens(e)
        while e.startswith("await "):
            e = _strip_outer_parens(e[len("await "):].strip())
        if not e:
            return None
        e = _strip_as(e)
        while e.endswith("!") and not e.endswith("!="):
            # `kbs.find(...)!` 的非空断言只影响类型，不影响形状
            e = e[:-1].rstrip()
        ternary = _split_ternary(e)
        if ternary is not None:
            left = self._infer(ternary[1], label, env, depth + 1, seen)
            right = self._infer(ternary[2], label, env, depth + 1, seen)
            if left is None or right is None:
                return None
            return _multi(left, right)
        logical = _split_logical(e)
        if logical is not None:
            left = self._infer(logical[0], label, env, depth + 1, seen)
            right = self._infer(logical[2], label, env, depth + 1, seen)
            if left is None or right is None:
                return None
            return _multi(left, right)
        if e.startswith("{"):
            if _match(e, 0, "{", "}") == len(e) - 1:
                keys, _, partial = _object_keys(e)
                return Shape("object", keys=keys, partial=partial)
            return None
        if e.startswith("["):
            if _match(e, 0, "[", "]") == len(e) - 1:
                inner = e[1:-1].strip()
                if not inner:
                    return Shape("array")
                elems = [self._infer(x, label, env, depth + 1, seen) for x in _split_top(inner)]
                if elems and all(x is not None for x in elems):
                    return Shape("array", elem=_combine([x for x in elems if x]))
                return Shape("array")
            # 不是裸数组字面量：`[...set].sort(...)` 这类继续走方法链，别在这里放弃
        if e[0] in _QUOTES:
            if _skip_string(e, 0) >= len(e):
                return Shape("string")
            return None
        if re.fullmatch(r"-?\d[\d_]*(?:\.\d+)?(?:[eE][+-]?\d+)?n?", e):
            return Shape("number")
        if e in ("true", "false"):
            return Shape("boolean")
        if e in ("null", "undefined", "void 0"):
            return Shape("nullish")
        if e.startswith("!"):
            return Shape("boolean")
        if re.fullmatch(r"[A-Za-z_$][\w$]*", e):
            return self._infer_name(e, label, env, depth, seen)
        call = _split_call(e)
        if call is not None:
            return self._infer_call(call[0], label, env, depth, seen)
        return None

    def _infer_name(
        self, name: str, label: str, env: dict[str, str], depth: int, seen: frozenset[tuple[str, ...]]
    ) -> Shape | None:
        if name in env:
            # 局部变量的键要带初值：不同函数里的同名局部（handlers 的 `doc` 与 helper 内的 `doc`）不是同一个东西，
            # 只按 (文件, 名字) 去重会把「helper 内部读同名局部」误判成环而放弃解析。
            scoped = (label, name, env[name])
            if scoped in seen or depth > 12:
                return None
            return self._infer(env[name], label, env, depth + 1, seen | {scoped})
        key = (label, name)
        if key in seen or depth > 12:
            return None
        mf = self.files.get(label)
        if mf is not None:
            if name in mf.values:
                return self._infer(mf.values[name], label, env, depth + 1, seen | {key})
            if name in mf.functions:
                return self._shape_of_body(mf.functions[name], label, depth + 1, seen | {key})
        sources: list[tuple[str, str]] = []
        for src_label, payload in self.index.get(name, []):
            if payload not in [p for _, p in sources]:
                sources.append((src_label, payload))
        if len(sources) != 1:
            return None
        src_label, payload = sources[0]
        if payload.startswith("fn|"):
            return self._shape_of_body(payload[3:], src_label, depth + 1, seen | {(src_label, name)})
        return self._infer(payload[4:], src_label, {}, depth + 1, seen | {(src_label, name)})

    def _shape_of_body(self, body: str, label: str, depth: int, seen: frozenset[tuple[str, ...]]) -> Shape | None:
        exprs = _return_expressions_of(body)
        if not exprs:
            return None
        env = _locals_of(body) if body.lstrip().startswith("{") else {}
        shapes: list[Shape] = []
        for expr in exprs:
            shape = self._infer(expr, label, env, depth + 1, seen)
            if shape is None:
                return None
            shapes.append(shape)
        return _combine(shapes)

    def _infer_call(
        self, callee: str, label: str, env: dict[str, str], depth: int, seen: frozenset[tuple[str, ...]]
    ) -> Shape | None:
        callee = _strip_comments(callee).strip()
        if callee in _BUILTIN_CALLS:
            kind = _BUILTIN_CALLS[callee]
            return Shape("array") if kind == "array" else Shape(kind)
        if "." not in callee:
            return self._infer_name(callee, label, env, depth + 1, seen)
        receiver, method = callee.rsplit(".", 1)
        method = method.strip()
        if method in _STRING_METHODS:
            return Shape("string")
        if method == "split":
            return Shape("array", elem=Shape("string"))
        if method in _BOOLEAN_METHODS:
            return Shape("boolean")
        if method in ("keys", "values", "entries"):
            return Shape("array")
        recv = self._infer(receiver, label, env, depth + 1, seen)
        if method in ("filter", "slice", "sort", "reverse", "concat", "splice", "fill"):
            if recv is not None and recv.kind == "string":
                return Shape("string")
            if recv is not None and recv.kind == "array":
                return Shape("array", elem=recv.elem if method != "splice" else None)
            return None
        if method == "map":
            return Shape("array") if recv is None or recv.kind == "array" else None
        if method in ("find", "at", "pop", "shift"):
            return recv.elem if recv is not None and recv.kind == "array" else None
        if method in ("flat", "flatMap"):
            return Shape("array")
        return None


def _analyze_mock_file(label: str, text: str) -> _MockFile:
    mf = _MockFile(label)
    for m in re.finditer(r"(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*(?:<[^<>]*>)?\s*\(", text):
        name = m.group(1)
        open_paren = text.find("(", m.end() - 1)
        if open_paren < 0:
            continue
        close = _match(text, open_paren, "(", ")")
        if close < 0:
            continue
        brace = _function_body(text, close + 1)
        if brace < 0:
            continue
        end = _match(text, brace, "{", "}")
        if end < 0:
            continue
        mf.functions.setdefault(name, text[brace:end + 1])
    for m in re.finditer(r"(?:export\s+)?(?:const|let|var)\s+([A-Za-z_$][\w$]*)", text):
        name = m.group(1)
        eq = _find_assign(text, m.end())
        if eq is None:
            continue
        init = _read_initializer(text, eq + 1)
        if not init:
            continue
        arrow = _top_arrow(init)
        if arrow is not None:
            mf.functions.setdefault(name, init[arrow + 2:].strip())
        else:
            mf.values.setdefault(name, init)
    return mf


def _pick_contract(returns: list[_ContractReturn], method: str, path: str) -> tuple[_ContractReturn | None, str]:
    cands = [
        r
        for r in returns
        if normalize(r.path) == path and (r.method == method or (r.method is None and method == "GET"))
    ]
    if not cands:
        return None, "契约里没有这条端点（第一维登记过的例外不比形状）"
    shapes = [r.shapes for r in cands]
    if any(s is None for s in shapes):
        return None, "契约的返回类型解析不出"
    if any(s != shapes[0] for s in shapes[1:]):
        return None, "契约里同一路径有多个返回类型"
    return cands[0], ""


def _run_shape_check(
    mock_texts: dict[str, str], route_texts: dict[str, str], api_texts: dict[str, str]
) -> ShapeReport:
    shaper = _MockShaper(mock_texts)
    routes = shaper.route_shapes(route_texts)
    contracts = _contract_returns(api_texts)
    report = ShapeReport(routes=len(routes))
    for route in routes:
        ident = f"{route.file}:{route.line} {route.method} {route.path}"
        if route.shapes is None:
            report.skipped.append((ident, route.reason or "解析不出返回形状"))
            continue
        contract, why = _pick_contract(contracts, route.method, normalize(route.path))
        if contract is None or contract.shapes is None:
            report.skipped.append((ident, why))
            continue
        mock_shape = route.shapes[0] if len(route.shapes) == 1 else Shape("multi", alts=route.shapes)
        verdict = _judge(contract.shapes, mock_shape)
        if verdict is True:
            report.consistent += 1
        elif verdict is False:
            report.inconsistent.append(
                _Mismatch(
                    route,
                    contract,
                    _shapes_text(contract.shapes),
                    _shape_text(mock_shape),
                    _mismatch_detail(contract.shapes, mock_shape),
                )
            )
        else:
            reason = (
                "判不了（mock 返回多分支联合，只部分分支与契约相符 —— 可能是守卫收窄）"
                if mock_shape.kind == "multi"
                else "判不了（mock 对象带展开/动态键，缺键不能作证）"
            )
            report.skipped.append((ident, reason))
    return report


def check_shapes() -> ShapeReport:
    mock_texts: dict[str, str] = {}
    for path in sorted(MOCK.parent.glob("**/*.ts")):
        mock_texts[path.relative_to(ROOT).as_posix()] = path.read_text(encoding="utf-8")
    route_texts = {
        label: text for label, text in mock_texts.items() if label.startswith("src/mock/handlers/")
    }
    api_texts: dict[str, str] = {}
    for path in sorted(API.glob("**/*.ts")):
        api_texts[path.relative_to(ROOT).as_posix()] = path.read_text(encoding="utf-8")
    return _run_shape_check(mock_texts, route_texts, api_texts)


def report_shapes(report: ShapeReport) -> bool:
    """打读数；返回"形状这一维过没过"。"""
    total_skip = len(report.skipped)
    print(
        f"mock 返回形状：一致 {report.consistent} 处 · 不一致 {len(report.inconsistent)} 处 · "
        f"跳过 {total_skip} 处（解析不出 / 无从比对）"
    )
    if report.inconsistent:
        print(f"\n✗ {len(report.inconsistent)} 处 mock 返回形状与契约不一致：")
        for item in report.inconsistent:
            print(f"  - {item.route.file}:{item.route.line}  {item.route.method} {item.route.path}")
            print(f"      契约 {item.contract_text}（{item.contract.label}）")
            print(f"      mock {item.mock_text}")
            print(f"      {item.detail}")
    if report.skipped:
        counts: dict[str, int] = {}
        for _, why in report.skipped:
            counts[why] = counts.get(why, 0) + 1
        detail = " · ".join(f"{why} {n} 处" for why, n in counts.items())
        print(f"  跳过明细：{detail}")
        for ident, why in report.skipped[:8]:
            print(f"    · {ident} —— {why}")
        if total_skip > 8:
            print(f"    · …另有 {total_skip - 8} 处（同上分类，不逐条列）")
    return not report.inconsistent


# ── 自检 ────────────────────────────────────────────────────────────────


def self_check() -> int:
    failures: list[str] = []

    def expect(name: str, ok: bool) -> None:
        if not ok:
            failures.append(name)

    expect("归一：`:id` 与 `${id}` 等价", normalize("/documents/:id") == normalize("/documents/${id}"))
    expect("归一：查询串去掉", normalize("/documents?page=1") == "/documents")
    expect("归一：尾部斜杠去掉", normalize("/documents/") == "/documents")
    expect("归一：`{*}` 当通配", normalize("/assistants/{*}/workflow") == "/assistants/:x/workflow")

    routes = [("POST", "/ghost", normalize("/ghost")), ("GET", "/documents/:id", normalize("/documents/:id"))]
    declared = {normalize("/documents/:id")}
    found = problems(routes, declared)
    expect("多出来的 mock 路由要报", len(found) == 1 and "/ghost" in found[0])
    expect("契约里有的 mock 路由不报", all("/documents/" not in f for f in found))

    allowed = problems([("POST", "/ghost", normalize("/ghost"))], set(), allow={"/ghost": "理由"})
    expect("登记了理由的例外不报", allowed == [])

    expect("登记了但路由已消失要报", stale_allow([], {"/gone": "理由"}) != [])
    expect(
        "路由还在就不报陈旧",
        stale_allow([("GET", "/x", "/x")], {"/x": "理由"}) == [],
    )

    # ── 第二维：形状（合成用例，走的是实判同一条代码路径，不是另写一套判断）──
    def shape_case(mock_body: str, api_type: str) -> ShapeReport:
        mock = f"export const routes = [['GET', '/thing', {mock_body}]]"
        api = f"export const getThing = () => request<{api_type}>({{ method: 'GET', url: '/thing' }})"
        return _run_shape_check({"handlers/m.ts": mock}, {"handlers/m.ts": mock}, {"api/c.ts": api})

    report = shape_case("() => ({ a: 'x' })", "{ a: string }")
    expect("形状：契约对象 vs mock 对象要判一致", (report.consistent, len(report.inconsistent), len(report.skipped)) == (1, 0, 0))

    report = shape_case("() => 'x'", "{ a: string }")
    expect("形状：契约对象 vs mock 裸标量要报红", report.consistent == 0 and len(report.inconsistent) == 1)
    expect(
        "形状：报红要指出文件与行号",
        bool(report.inconsistent) and report.inconsistent[0].route.file == "handlers/m.ts" and report.inconsistent[0].route.line == 1,
    )

    report = shape_case("() => ({ a: true })", "{ a: string; b: number }")
    expect("形状：契约必填键缺了要报红", len(report.inconsistent) == 1 and "b" in report.inconsistent[0].detail)

    mock = (
        "const cloneThing = (g) => ({ nodes: g.nodes, edges: g.edges })\n"
        "export const routes = [['GET', '/thing', () => cloneThing(x)]]"
    )
    api = "export const getThing = () => request<{ graph: string; version: number }>({ method: 'GET', url: '/thing' })"
    report = _run_shape_check({"handlers/m.ts": mock}, {"handlers/m.ts": mock}, {"api/c.ts": api})
    expect(
        "形状：`return helper(...)` 要追到 helper 的返回表达式",
        len(report.inconsistent) == 1 and "graph" in report.inconsistent[0].detail,
    )

    report = shape_case("() => mystery()", "{ a: string }")
    expect("形状：解析不出要进跳过计数、不许瞎报", len(report.skipped) == 1 and report.consistent == 0 and report.inconsistent == [])

    if failures:
        print("✗ 自检失败：")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("✓ 自检通过（归一 4 条 · 判据 5 条 · 形状 6 条）")
    return 0


def main(argv: list[str]) -> int:
    if "--self-check" in argv:
        return self_check()

    routes = mock_routes()
    declared = contract_paths()
    if not routes:
        print(f"✗ 在 {MOCK} 下一条路由都没解析到 —— 正则或目录结构变了，别让它安静通过")
        return 1

    failed = False
    found = problems(routes, declared)
    found.extend(stale_allow(routes))
    print(f"mock 路由 {len(routes)} 条 · 契约声明路径 {len(declared)} 个 · 刻意的例外 {len(KNOWN_MOCK_ONLY)} 条")
    if found:
        print(f"\n✗ {len(found)} 条 mock 路由在契约里没有对应声明：")
        for f in found:
            print(f"  - {f}")
        failed = True
    else:
        print("✓ 每条 mock 路由都能在契约里找到声明")

    # 第二维与第一维各跑各的：一维红不影响另一维出读数（否则修完路径才看得到形状）。
    print()
    if not report_shapes(check_shapes()):
        failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
