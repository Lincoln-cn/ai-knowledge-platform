#!/usr/bin/env python3
"""欠账册的"漂移"闸门（`#40` 判据 1 的窄版本）。

## 这条闸门在防什么

`#40`：同一个状态在四处各写一份（任务台账 / `ROADMAP` / 欠账册 / Issue），已实测 15 处漂移 ——
真做完的还挂在账上。它的危害不是"报错"，是**规划失真**：账上写着 open，于是剩余工作被高估，
下一轮计划建在错的账上；而它**不会让任何闸门变红**。

窄版本（判据 1 原话）：一条账提到**某个文件路径**，而该文件**已经在仓库里** ⇒ 报出来。
误报可控：只认反引号里、能 `os.path.exists` 命中的路径，不猜语义。

★ 为什么不做"更聪明"的版本（按 Issue 状态判断）：那要联网读 GitHub，闸门就不再能自己站住；
  而且**"文件在不在"已经能抓住绝大多数"做完了还挂着"** —— 一个功能的落地几乎总会留下文件。

## 判据

1. 账上每条**未标完成**（无 ✅、未被 `~~` 划掉）的 `T` 行，都不许提到仓库里已存在的路径；
2. `docs/已知问题与欠账.md` 读不到 ⇒ 红（删掉文档等于删掉判据 —— 与"基线缺失"同族）；
3. 扫到 0 条 `T` 行 ⇒ 红（说明解析写错了，不是说明账目干净）。

用法：
    python tools/check_ledger_drift.py            # 实判
    python tools/check_ledger_drift.py --self-check
"""

from __future__ import annotations

import os
import re
import sys
import tempfile

LEDGER = "docs/已知问题与欠账.md"

# `| **T251** | **多维…** | … |` / `| T252 | … |` / `| ~~T263~~ | … |`
ROW_RE = re.compile(r"^\|\s*(~{0,2})\s*\*{0,2}(T\d{2,3})\*{0,2}\s*\*{0,2}(~{0,2})\s*\|")
# 反引号里的东西：只留"像路径"的那些
TICK_RE = re.compile(r"`([^`\n]{2,120})`")
PATHISH_EXT = (".py", ".sql", ".java", ".ts", ".vue", ".md", ".yml", ".yaml", ".json", ".sh", ".cmd")

problems: list[str] = []


def _looks_like_path(token: str) -> bool:
    token = token.strip()
    if not token or any(ch in token for ch in " *<>|"):
        return False
    if not token.lower().endswith(PATHISH_EXT):
        return False
    # `foo/` 这种目录简写不判（目录存在不代表这条账做完了）
    return not token.endswith("/")


def _exists(token: str) -> bool:
    token = token.strip().lstrip("./")
    for base in (".", ".."):
        candidate = os.path.normpath(os.path.join(base, token))
        if os.path.isfile(candidate):
            return True
    return False


def scan(text: str) -> list[str]:
    """返回漂移行（不带行号），供实判与自检共用。"""
    hits: list[str] = []
    for line in text.splitlines():
        match = ROW_RE.match(line)
        if not match:
            continue
        tid = match.group(2)
        struck = bool(match.group(1) or match.group(3)) or line.lstrip("| ").startswith("~~")
        if struck or "✅" in line:
            continue
        for token in TICK_RE.findall(line):
            if not _looks_like_path(token):
                continue
            if _exists(token):
                hits.append(
                    f"{tid}：账上还挂着，但它提到的 `{token}` 已经在仓库里 "
                    f"—— 要么这条做完了（改成 ✅ 并写读数），要么这条账写错了（把产物改成真正缺的那一件）"
                )
                break
    return hits


def count_rows(text: str) -> int:
    return sum(1 for line in text.splitlines() if ROW_RE.match(line))


def check(path: str = LEDGER) -> int:
    try:
        with open(path, "r", encoding="utf-8") as fh:
            text = fh.read()
    except OSError as exc:
        print(f"✗ 读不到欠账册 {path}（{type(exc).__name__}: {exc}）—— 删掉文档等于删掉这条判据")
        return 1

    rows = count_rows(text)
    if rows == 0:
        print(f"✗ 在 {path} 里一条 `T` 行都没扫到 —— 是解析写错了，不是账目干净")
        return 1

    hits = scan(text)
    for hit in hits:
        print(f"  ✗ {hit}")
    if hits:
        print(f"\n结果：红 —— {len(hits)} 处漂移（`T` 行共 {rows} 条）")
        return 1
    print(f"✓ 欠账册里没有「提到了已存在产物」的未完成账（`T` 行共 {rows} 条）")
    return 0


SELF_CASES = [
    (
        "未完成 + 提到的文件存在 ⇒ 红",
        "| T900 | 某个功能 | 产物：`tools/check_ledger_drift.py` |\n",
        1,
    ),
    (
        "已完成（✅）⇒ 不报",
        "| T900 | 某个功能 | 产物：`tools/check_ledger_drift.py` ✅ 已做 |\n",
        0,
    ),
    (
        "划掉的 T ⇒ 不报",
        "| ~~T900~~ | 某个功能 | 产物：`tools/check_ledger_drift.py` |\n",
        0,
    ),
    (
        "未完成但提到的文件不存在 ⇒ 不报",
        "| T900 | 某个功能 | 产物：`tools/zz-not-here.py` |\n",
        0,
    ),
    (
        "只提到表名 / 目录简写 ⇒ 不报（那不是文件）",
        "| T900 | 多维度向量表路由 | 现在只有 `vec_embedding_768`，见 `docs/` |\n",
        0,
    ),
    (
        "没有 T 行 ⇒ 数出来 0（调用方据此判红）",
        "| 周 | 版本 |\n",
        0,
    ),
]


def self_check() -> int:
    failures = 0
    for name, text, expected in SELF_CASES:
        got = len(scan(text))
        ok = got == expected
        print(f"  {'✓' if ok else '✗'} {name}（期望 {expected} 处，实得 {got} 处）")
        if not ok:
            failures += 1

    # 一条真文件缺失 / 一条真行数为 0，用临时文件走完整 check()
    with tempfile.TemporaryDirectory() as tmp:
        empty = os.path.join(tmp, "empty.md")
        with open(empty, "w", encoding="utf-8") as fh:
            fh.write("| 周 | 版本 |\n")
        if check(empty) == 1:
            print("  ✓ 扫不到 T 行 ⇒ 退 1")
        else:
            print("  ✗ 扫不到 T 行却退 0")
            failures += 1

        if check(os.path.join(tmp, "missing.md")) == 1:
            print("  ✓ 文档缺失 ⇒ 退 1")
        else:
            print("  ✗ 文档缺失却退 0")
            failures += 1

    print(f"\n自检：{'通过' if failures == 0 else f'{failures} 条不过'}")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    if "--self-check" in sys.argv[1:]:
        sys.exit(self_check())
    sys.exit(check())
