#!/usr/bin/env python3
"""欠账册的"漂移"闸门（`#40` 判据 1）。

## 这条闸门在防什么

`#40`：同一个状态在四处各写一份（任务台账 / `ROADMAP` / 欠账册 / Issue），已实测 15 处漂移 ——
真做完的还挂在账上。它的危害不是"报错"，是**规划失真**：账上写着 open，于是剩余工作被高估，
下一轮计划建在错的账上；而它**不会让任何闸门变红**。

## 两条判据

**规则 1（路径）**：一条未标完成的账提到**某个文件路径**，而该文件**已经在仓库里** ⇒ 报出来。
只认反引号里、能 `os.path.exists` 命中的路径，不猜语义。

**规则 2（否定 + 代码里已有）**：一条未标完成的账里出现否定词（`没有` / `缺` / `未` / `只有` / `仍是` /
`现在只` / `停在` / `不在`），且同一行反引号里的**标识符**在代码里出现过 ⇒ 报出来。
例：账上写「现在只有 `min: 8`」，而代码里早已换成与后端同一套策略 —— 这句「现在」过期了。

★ 为什么规则 2 不是"更聪明的版本"：它仍然只做**字面存在性**判断，不读 GitHub 状态、不联网
  （联网的闸门不能自己站住）。它抓的是"账上写着缺某样东西，而那样东西已经在仓库里" ——
  这正是 2026-11-01 那两次清账里全部 8 条的形态。

## 判据

1. 未标完成的 `T` 行不许命中规则 1 / 规则 2（豁免写进 `tools/ledger-drift-allow.txt`，每条要写理由）；
2. 豁免过期（账上已不再命中）⇒ 红（豁免必须随清理收缩，与其它闸门的基线同规矩）；
3. `docs/已知问题与欠账.md` 读不到 ⇒ 红（删掉文档等于删掉判据 —— 与"基线缺失"同族）；
4. 扫到 0 条 `T` 行 ⇒ 红（说明解析写错了，不是说明账目干净）。

用法：
    python tools/check_ledger_drift.py            # 实判
    python tools/check_ledger_drift.py --self-check
"""

from __future__ import annotations

import io
import os
import re
import sys
import tempfile

LEDGER = "docs/已知问题与欠账.md"
ALLOW = "tools/ledger-drift-allow.txt"

# `| **T251** | **多维…** | … |` / `| T252 | … |` / `| ~~T263~~ | … |`
ROW_RE = re.compile(r"^\|\s*(~{0,2})\s*\*{0,2}(T\d{2,3})\*{0,2}\s*\*{0,2}(~{0,2})\s*\|")
TICK_RE = re.compile(r"`([^`\n]{2,120})`")
ALLOW_RE = re.compile(r"^\s*(T\d{2,3})\s+(\S.*?)\s+(\S.*)$")

PATHISH_EXT = (".py", ".sql", ".java", ".ts", ".vue", ".md", ".yml", ".yaml", ".json", ".sh", ".cmd")
NEGATIONS = ("没有", "缺", "未", "只有", "仍是", "现在只", "停在", "不在")

CORPUS_ROOTS = ("backend/src/main/java", "frontend/src", ".github/workflows", "tools", "backend/tools")
CORPUS_EXTS = (".java", ".ts", ".vue", ".py", ".yml", ".json")
SKIP_DIRS = ("node_modules", "target", ".git", "dist")

_problems: list[str] = []


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


def build_corpus() -> str:
    """把代码正文读成一段文本，给规则 2 做"标识符在不在"的判断（不联网、不调 grep）。"""
    parts: list[str] = []
    for root in CORPUS_ROOTS:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
            for name in filenames:
                if not name.endswith(CORPUS_EXTS):
                    continue
                path = os.path.join(dirpath, name)
                try:
                    with io.open(path, encoding="utf-8", errors="ignore") as fh:
                        parts.append(fh.read())
                except OSError:
                    continue
    return "\n".join(parts)


def load_allow(path: str = ALLOW) -> dict[tuple[str, str], str]:
    """豁免：一行 `T### <字面量> <理由>`。文件不存在 = 没有豁免（不是错误）。"""
    allowed: dict[tuple[str, str], str] = {}
    if not os.path.isfile(path):
        return allowed
    with io.open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            match = ALLOW_RE.match(line)
            if match:
                allowed[(match.group(1), match.group(2))] = match.group(3)
    return allowed


def _open_rows(text: str):
    for line in text.splitlines():
        match = ROW_RE.match(line)
        if not match:
            continue
        struck = bool(match.group(1) or match.group(3)) or line.lstrip("| ").startswith("~~")
        if struck or "✅" in line:
            continue
        yield match.group(2), line


def scan(
    text: str,
    corpus: str = "",
    allow: dict[tuple[str, str], str] | None = None,
    used: set[tuple[str, str]] | None = None,
) -> list[str]:
    """返回漂移行（不带行号），供实判与自检共用。`corpus` 为空时只跑规则 1。

    `used` 用来记下"这次真正用到的豁免条目" —— 一条豁免如果不再被用到，就是过期的，要删掉。
    """
    allow = allow or {}
    used = used if used is not None else set()
    hits: list[str] = []
    for tid, line in _open_rows(text):
        tokens = [tok.strip() for tok in TICK_RE.findall(line)]

        # ── 规则 1：提到已存在的路径 ──
        hit1 = next((tok for tok in tokens if _looks_like_path(tok) and _exists(tok)), None)
        if hit1:
            if (tid, hit1) in allow:
                used.add((tid, hit1))
            else:
                hits.append(
                    f"{tid}：账上还挂着，但它提到的 `{hit1}` 已经在仓库里 "
                    f"—— 要么这条做完了（改成 ✅ 并写读数），要么这条账写错了（把产物改成真正缺的那一件）"
                )
            continue

        # ── 规则 2：否定词 + 反引号里的标识符在代码里出现过 ──
        if not corpus or not any(word in line for word in NEGATIONS):
            continue
        for token in tokens:
            if len(token) < 4 or _looks_like_path(token):
                continue
            if corpus.count(token) == 0:
                continue
            if (tid, token) in allow:
                used.add((tid, token))
                break
            hits.append(
                f"{tid}：账上说「{token}」还缺 / 只有（现状），但代码里已经有它了 "
                f"—— 复核这条账：做完了就写 ✅ 与读数；只是那个位置还没有，就把现状写具体，别写整句否定"
            )
            break
    return hits


def count_rows(text: str) -> int:
    return sum(1 for line in text.splitlines() if ROW_RE.match(line))


def check(path: str = LEDGER) -> int:
    try:
        with io.open(path, encoding="utf-8") as fh:
            text = fh.read()
    except OSError as exc:
        print(f"✗ 读不到欠账册 {path}（{type(exc).__name__}: {exc}）—— 删掉文档等于删掉这条判据")
        return 1

    rows = count_rows(text)
    if rows == 0:
        print(f"✗ 在 {path} 里一条 `T` 行都没扫到 —— 是解析写错了，不是账目干净")
        return 1

    allow = load_allow()
    used: set[tuple[str, str]] = set()
    hits = scan(text, build_corpus(), allow, used)
    for hit in hits:
        print(f"  ✗ {hit}")

    # 豁免必须随清理收缩：账上已不再命中的豁免条目要删掉
    unused = [key for key in allow if key not in used]
    for tid, token in unused:
        print(f"  ✗ 豁免过期：`{tid}` 里的 `{token}` 已不再命中 —— 删掉 tools/ledger-drift-allow.txt 里这一行")

    if hits or unused:
        print(f"\n结果：红 —— {len(hits)} 处漂移、{len(unused)} 条过期豁免（`T` 行共 {rows} 条）")
        return 1
    print(f"✓ 欠账册没有可检出的漂移（`T` 行共 {rows} 条，豁免 {len(allow)} 条）")
    return 0


SELF_CASES = [
    (
        "规则 1：未完成 + 提到的文件存在 ⇒ 红",
        "| T900 | 某个功能 | 产物：`tools/check_ledger_drift.py` |\n",
        "",
        1,
    ),
    (
        "规则 1：已完成（✅）⇒ 不报",
        "| T900 | 某个功能 | 产物：`tools/check_ledger_drift.py` ✅ 已做 |\n",
        "",
        0,
    ),
    (
        "规则 1：划掉的 T ⇒ 不报",
        "| ~~T900~~ | 某个功能 | 产物：`tools/check_ledger_drift.py` |\n",
        "",
        0,
    ),
    (
        "规则 1：提到的文件不存在 ⇒ 不报",
        "| T900 | 某个功能 | 产物：`tools/zz-not-here.py` |\n",
        "",
        0,
    ),
    (
        "规则 1：只提到表名 / 目录简写 ⇒ 不报（那不是文件）",
        "| T900 | 多维度向量表路由 | 现在只有 `vec_embedding_768`，见 `docs/` |\n",
        "",
        0,
    ),
    (
        "规则 2：否定词 + 标识符在代码里出现 ⇒ 红",
        "| T900 | 校验比后端松 | 现在只有 `PasswordPolicy` 那一条 |\n",
        "class PasswordPolicy {}",
        1,
    ),
    (
        "规则 2：没有否定词 ⇒ 不报（陈述句不是在说缺口）",
        "| T900 | 校验与后端一致 | 用的是 `PasswordPolicy` |\n",
        "class PasswordPolicy {}",
        0,
    ),
    (
        "规则 2：标识符在代码里没有 ⇒ 不报（那是真缺口）",
        "| T900 | 缺个东西 | 现在只有 `NoSuchThingAnywhere` |\n",
        "class PasswordPolicy {}",
        0,
    ),
    (
        "没有 T 行 ⇒ 数出来 0（调用方据此判红）",
        "| 周 | 版本 |\n",
        "",
        0,
    ),
]


def self_check() -> int:
    failures = 0
    for name, text, corpus, expected in SELF_CASES:
        got = len(scan(text, corpus))
        ok = got == expected
        print(f"  {'✓' if ok else '✗'} {name}（期望 {expected} 处，实得 {got} 处）")
        if not ok:
            failures += 1

    # 豁免：命中但已登记 ⇒ 不报
    allowed_text = "| T900 | 某功能 | 产物：`tools/check_ledger_drift.py` |\n"
    allow = {("T900", "tools/check_ledger_drift.py"): "历史原因，下批清"}
    ok = len(scan(allowed_text, "", allow)) == 0
    print(f"  {'✓' if ok else '✗'} 规则 1 命中但已豁免 ⇒ 不报")
    failures += 0 if ok else 1

    # 一条真文件缺失 / 一条真行数为 0，用临时文件走完整 check()
    with tempfile.TemporaryDirectory() as tmp:
        empty = os.path.join(tmp, "empty.md")
        with io.open(empty, "w", encoding="utf-8") as fh:
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
