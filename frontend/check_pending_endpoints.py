#!/usr/bin/env python3
"""第 10 道静态闸门：后端「还没实现的端点」有没有被前端调用（`#160` 判据 3）。

<h3>为什么需要这一道</h3>
<p>`ContractDriftTest.KNOWN_MISSING` 是**后端欠账**的口径（"哪个端点还没写"），
它不表达"这会让页面报错"。而前端一旦调用这样的端点就是一次 404：
未处理的 rejection 会变成页面级 `PAGEERROR`，用户看到的只是"这块空着"——
与"这个功能还没做"看起来一模一样，处置却完全不同（一个等排期，一个要隔离依赖）。

<h3>判据（每条都要能红）</h3>
<ol>
  <li>台账里的端点被前端<strong>声明</strong>了，却没登记在本脚本的 {@code PENDING} 账里 ⇒ 红；</li>
  <li>账里那条<strong>已有调用方</strong>，但没写 {@code guard}（降级/提示落在哪）⇒ 红；</li>
  <li>账里那条<strong>已经不在台账</strong>（后端补上了）⇒ 红 —— 清单必须随修复收缩；</li>
  <li>账里那条已<strong>没有前端声明</strong>（契约删了）⇒ 红。</li>
</ol>

<h3>为什么不判"页面渲染出来了"</h3>
<p>那是浏览器读数，需要起服务；本工程的前端闸门在本机绑不了端口（`ROADMAP.md:2006`），
所以那一条永远在真栈那一侧。这里只回答一个静态问题：**谁会调到还没写的端点**。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

FRONTEND = Path(__file__).resolve().parent
ROOT = FRONTEND.parent
DRIFT = ROOT / "backend/src/test/java/cn/lincoln/aiplatform/ContractDriftTest.java"
API_DIR = FRONTEND / "src/api"
PAGES = FRONTEND / "src/pages"

# ──────────────────────────────────────────────────────────────────────────────
# 账：台账端点落到前端之后的处置。
#
# `issue` 是这批欠账的 Issue 号（与后端台账必须一致 —— 两处写不同号就没有真源了）。
# `guard` 写给读的人看：**这个端点在真栈上 404 时，用户看到什么**。
#         空字符串 = 目前没有调用方，等排期；一旦有人调用，判据 ② 会要求补上。
#         不要写"已处理"这类没有位置的话 —— 要能被 grep 到。
# ──────────────────────────────────────────────────────────────────────────────
PENDING = {
    "POST /assistants/{*}/workflow/debug": {
        "issue": "#198",
        "guard": "",
    },
    # ★ 已落：POST /assistants/{*}/workflow/publish 随 #198 第四块后端交付（2026-10-31），
    #   故从本账里摘掉 —— 后端台账（ContractDriftTest.KNOWN_MISSING）同一时刻摘的同一条。
    #   ⚠️ 摘掉的是"端点缺失"这笔账，**不是**"页面已接发布"：前端仍无页面调用
    #   `publishWorkflow`（只有 `frontend/src/api/workflow.ts` 的契约声明），那一格另有欠账。
    "GET /assistants/{*}/workflow/records": {
        "issue": "#198",
        "guard": "",
    },
    "GET /assistants/{*}/workflow/versions": {
        "issue": "#198",
        "guard": "workflow/components/common/PublishHistory.vue getList 的 catch ⇒ 「查看发布历史」还没接后端（列表归空 + loading 收掉）",
    },
    "GET /assistants/{*}/workflow/versions/{*}": {
        "issue": "#198",
        "guard": "workflow/Index.vue previewVersion 的 catch ⇒ 「预览历史版本」还没接后端",
    },
    "POST /assistants/{*}/workflow/versions/{*}/rollback": {
        "issue": "#198",
        "guard": "",
    },
    "POST /files": {
        "issue": "#187",
        "guard": "conversation/components/Composer.vue onPickFiles 的 catch ⇒ 附件行内标 failed 并显示原因（不冒充上传成功）",
    },
}


def read_backend_ledger() -> list[tuple[str, str]]:
    """从 ContractDriftTest 的 KNOWN_MISSING 字段块里读逐条台账。

    ★ 只读字段块，不读整个文件 —— 里面的金丝雀测试也用 `missing("GET", "/users", …)` 造数据，
    整文件扫会把那些假端点当成真欠账。
    """
    text = DRIFT.read_text(encoding="utf-8")
    start = text.index("KNOWN_MISSING =")
    block = text[start : text.index("/**", start)]
    return re.findall(r'missing\("([A-Z]+)",\s*"([^"]+)"', block)


def segs_backend(path: str) -> list[str]:
    return ["*" if s == "{*}" else s for s in path.strip("/").split("/")]


def segs_frontend(url: str) -> list[str]:
    """前端 url 模板归一成段列表：`${x}` 与 `:x` 都算通配段。"""
    url = url.split("?")[0]
    url = re.sub(r"\$\{[^}]*\}", "*", url)
    url = re.sub(r":([A-Za-z_][A-Za-z0-9_]*)", "*", url)
    return url.strip("/").split("/")


def same_path(a: list[str], b: list[str]) -> bool:
    return len(a) == len(b) and all(x == "*" or y == "*" or x == y for x, y in zip(a, b))


def collect_declarations() -> list[tuple[str, int, str, str]]:
    """扫前端 api 层的每一条 `url:` 声明 → (文件, 行号, url 模板, 所属导出函数名)。"""
    decls: list[tuple[str, int, str, str]] = []
    for path in sorted(API_DIR.rglob("*.ts")):
        if path.name.endswith(".test.ts"):
            continue
        src = path.read_text(encoding="utf-8")
        for m in re.finditer(r"url:\s*[`'\"]([^`'\"]+)[`'\"]", src):
            line = src[: m.start()].count("\n") + 1
            names = list(re.finditer(r"export const (\w+)", src[: m.start()]))
            fn = names[-1].group(1) if names else "?"
            decls.append((path.relative_to(FRONTEND).as_posix(), line, m.group(1), fn))
    return decls


def count_callers(fn: str) -> int:
    """这个导出函数在 `src/pages` 下被多少个文件调用（跳过 import 行与定义行）。"""
    if fn == "?":
        return 0
    pattern = re.compile(r"\b" + re.escape(fn) + r"\s*\(")
    hits: set[str] = set()
    for path in PAGES.rglob("*"):
        if path.suffix not in {".vue", ".ts"}:
            continue
        src = path.read_text(encoding="utf-8")
        for m in pattern.finditer(src):
            line_start = src.rfind("\n", 0, m.start()) + 1
            line_end = src.find("\n", m.start())
            line = src[line_start : line_end if line_end != -1 else len(src)]
            if "import" in line or "export const" in line:
                continue
            hits.add(path.relative_to(FRONTEND).as_posix())
    return len(hits)


def check() -> int:
    ledger = read_backend_ledger()
    decls = collect_declarations()

    if not ledger:
        print("✗ 台账读不出来（正则与 ContractDriftTest 的形状对不上了）⇒ 这道闸门已失效")
        return 1

    problems: list[str] = []
    declared: dict[str, list[str]] = {}

    for method, path in ledger:
        target = segs_backend(path)
        matched = [d for d in decls if same_path(target, segs_frontend(d[2]))]
        if not matched:
            continue
        key = f"{method} {path}"
        called = max(count_callers(d[3]) for d in matched)
        whereabouts = ", ".join(f"{d[0]}:{d[1]}" for d in matched)
        declared[key] = [str(called), whereabouts]

        entry = PENDING.get(key)
        if entry is None:
            problems.append(
                f"台账端点被前端声明，但没登记处置：{key}\n    声明处：{whereabouts}\n"
                f"    ⇒ 在 check_pending_endpoints.py 的 PENDING 里补一条（issue + guard）"
            )
            continue
        if not re.fullmatch(r"#\d+", entry["issue"]):
            problems.append(f"{key} 的 issue 号不是形如 #123：{entry['issue']!r}（账目必须指回一条真 Issue）")
        if called > 0 and not entry["guard"].strip():
            problems.append(
                f"{key} 已经有 {called} 个调用方，但账里没写 guard\n"
                f"    调用在：{whereabouts} 所声明的函数\n"
                f"    ⇒ 404 会变成未处理的 rejection ⇒ 页面「点了没反应」。补一条可读提示再登记"
            )

    # 判据 ③④：账里那些现在对不上的
    ledger_keys = {f"{m} {p}" for m, p in ledger}
    for key, entry in PENDING.items():
        if key not in ledger_keys:
            problems.append(
                f"账里还留着 {key}（{entry['issue']}），但它已不在后端台账里\n"
                f"    ⇒ 后端补上了（或这条台账被删了）：把账里这条删掉，并复核页面侧的降级还能不能撤"
            )
        elif key not in declared:
            problems.append(
                f"账里还留着 {key}，但前端已经没有这个声明了\n"
                f"    ⇒ 契约删了就删账；否则这是一条永远查不到的死账"
            )

    print(f"后端台账 {len(ledger)} 条 · 前端声明 {len(decls)} 条 · 账 {len(PENDING)} 条")
    for key in sorted(declared):
        called, whereabouts = declared[key]
        mark = "有调用" if called != "0" else "未调用"
        print(f"  {key:52s} {mark:6s} {whereabouts}")

    if problems:
        print()
        for p in problems:
            print(f"✗ {p}")
        return 1
    return 0


def self_check() -> int:
    """内置样本：三段匹配逻辑各自给出预期结果（这道闸门自己的金丝雀）。"""
    cases = [
        (segs_backend("/files"), segs_frontend("/files"), True),
        (segs_backend("/assistants/{*}/workflow/draft"), segs_frontend("/assistants/:appId/workflow/draft"), True),
        (segs_backend("/assistants/{*}/workflow/draft"), segs_frontend("/assistants/${id}/workflow/draft"), True),
        # 段数不同必须不匹配 —— 否则 `/workflow/versions` 会吃掉 `/workflow/versions/3`
        (segs_backend("/assistants/{*}/workflow/versions"), segs_frontend("/assistants/1/workflow/versions/3"), False),
        # 字面段不同必须不匹配
        (segs_backend("/files"), segs_frontend("/files/x"), False),
        (segs_backend("/workflow/node-types"), segs_frontend("/workflow/nodes"), False),
    ]
    bad = 0
    for a, b, expected in cases:
        got = same_path(a, b)
        if got != expected:
            bad += 1
            print(f"✗ 自测失败：{a} vs {b} 期望 {expected} 实得 {got}")
    print(f"自测：{len(cases) - bad}/{len(cases)} 通过")
    return 1 if bad else 0


def main() -> int:
    if "--self-check" in sys.argv:
        return self_check()
    return check()


if __name__ == "__main__":
    sys.exit(main())
