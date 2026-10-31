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

## 刻意不做的两件事

1. **不比对 method**：契约里 `request({ url })` 常常省略 method（默认 GET），
   强行比对会把"契约用了默认 GET"误判成"mock 多实现了一个方法"。
   路径对不上已经能抓住 `#211` 那种整条端点不存在的情形。
2. **不要求"契约有 ⇒ mock 有"**：mock 本来只覆盖一部分，缺是正常的。

用法：
    python check_mock_endpoints.py            # 判据，退 0/1
    python check_mock_endpoints.py --self-check   # 离线自检 6 条
"""

from __future__ import annotations

import re
import sys
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

    if failures:
        print("✗ 自检失败：")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("✓ 自检通过（归一 4 条 · 判据 5 条）")
    return 0


def main(argv: list[str]) -> int:
    if "--self-check" in argv:
        return self_check()

    routes = mock_routes()
    declared = contract_paths()
    if not routes:
        print(f"✗ 在 {MOCK} 下一条路由都没解析到 —— 正则或目录结构变了，别让它安静通过")
        return 1

    found = problems(routes, declared)
    found.extend(stale_allow(routes))
    print(f"mock 路由 {len(routes)} 条 · 契约声明路径 {len(declared)} 个 · 刻意的例外 {len(KNOWN_MOCK_ONLY)} 条")
    if found:
        print(f"\n✗ {len(found)} 条 mock 路由在契约里没有对应声明：")
        for f in found:
            print(f"  - {f}")
        return 1
    print("✓ 每条 mock 路由都能在契约里找到声明")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
