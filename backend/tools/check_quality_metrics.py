#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""`#87` 的两个读数（忠实度 / 噪声敏感度）的口径闸门 —— **只读扫查**，不改任何文件。

为什么需要它：这两个读数**本版以人工抽查起步**（`13 §12.13` ③），也就是说
"忠实度有没有达标"今天是**人判**的，代码里**不该有**自动评测它的实现。
但"人工的"这件事本身没有读数 ⇒ 最容易出现的坏是：

    有人偷偷加了自动评测忠实度的实现，口径里那句"本版人工抽查起步 / 不引评测框架"
    却一个字没动 —— 于是验收报告上的"忠实度 92%"其实是机器判的，
    而口径说它是人判的。**没有任何东西会红。**

本脚本就是那条会红的东西。它钉住两件事：

  A. **口径在册**（`docs/系统设计方案/13-密级与数据隔离设计.md` 里确实写下了这两个指标）：
     1. 忠实度的定义（锚：`忠实度（Faithfulness）`）
     2. 噪声敏感度的定义（锚：`噪声敏感度（Noise Sensitivity）`）
     3. "本版以人工抽查起步"的声明（锚：`本版以人工抽查起步`）
     4. 冲突时谁赢（锚：`宁可少答不可编`）

  B. **实现未自动化**（代码里当前不存在"自动评测忠实度"的实现）：
     5. 扫 `backend` 的生产/测试代码、配置与依赖声明，**零命中**"自动评测忠实度"的特征标识符；
     6. ★ **口径与实现必须同进退**：若代码里真出现了自动评测特征（有人开始做了），
        那么 `13 §12.13` 里**必须**同时出现显式声明（锚：`自动评测已启用`），
        否则就是"偷偷加了实现却没更新口径" ⇒ **报红**。
        ⇒ 真要做自动化，正确动作是：先改 `13 §12.13` ③ 的口径、再落代码、再回来改这条判据。
        **跳过口径那一步，这里就会红** —— 这正是本闸门存在的理由。

跑法（从仓库根或 `backend/` 跑都行，路径按脚本自身位置推导）：
    python backend/tools/check_quality_metrics.py              # 判据，退出码 0/1
    python backend/tools/check_quality_metrics.py --self-check # 离线自检（CI 用），退出码 0/1
"""

import re
import sys
from pathlib import Path

# 仓库根：backend/tools/本文件 → parents[0]=tools, [1]=backend, [2]=仓库根
REPO_ROOT = Path(__file__).resolve().parents[2]
SELF_PATH = Path(__file__).resolve()
DOC_PATH = REPO_ROOT / "docs" / "系统设计方案" / "13-密级与数据隔离设计.md"

# 只扫这些（`frontend/` 与 `docs/` 不扫：前端是另一路在做，docs 里出现这些词是正常的口径文字）
SCAN_ROOTS = [
    REPO_ROOT / "backend" / "src",
    REPO_ROOT / "backend" / "tools",
]
SCAN_EXT = {".java", ".py", ".kt", ".yml", ".yaml", ".properties", ".xml"}
DEP_FILES = [REPO_ROOT / "backend" / "pom.xml"]

# 口径锚点：缺任一个 = 口径没写全
DOC_ANCHORS = [
    ("忠实度的定义", "忠实度（Faithfulness）"),
    ("噪声敏感度的定义", "噪声敏感度（Noise Sensitivity）"),
    ("「本版以人工抽查起步」的声明", "本版以人工抽查起步"),
    ("「冲突时谁赢」（宁可少答不可编）", "宁可少答不可编"),
]
# 口径里"已自动化"的显式声明锚（只有它出现，才允许代码里有自动评测实现）
AUTOMATION_DECL = "自动评测已启用"

# 自动评测忠实度 / 噪声敏感度的实现特征（标识符一律 ASCII，符合本仓库命名纪律）
# ★ **用子串匹配，不用 `\b` 词边界**：真实实现是 `compute_faithfulness` / `FaithfulnessEvaluator`
#   这类长标识符，`\b` 会把它们**全部放过**（反证实测：探针 `compute_faithfulness` 让闸门绿着过了，
#   闸门等于虚设）⇒ 子串匹配，宁可误报一次让人看一眼，不可漏报成"永远绿"。
AUTO_EVAL_RE = re.compile(
    r"faithfulness"
    r"|noise[_]?sensitivity"
    r"|ragas"
    r"|llm[_]?judge"
    r"|groundedness"
    r"|auto[_]?eval",
    re.IGNORECASE,
)
# 纯注释行不算实现（避免"以后要做 Faithfulness 自动评测"这类注释误报）
COMMENT_RE = re.compile(r"^\s*(?://|#|\*|<!--|--)")


def is_code_line(line):
    """注释行不算实现特征。"""
    return not COMMENT_RE.match(line)


def scan_auto_eval(roots=SCAN_ROOTS, dep_files=DEP_FILES, ext=SCAN_EXT):
    """扫代码与依赖声明，找"自动评测忠实度"的实现特征 → [(文件, 行号, 命中词)]。"""
    found = []
    targets = []
    for root in roots:
        if root.exists():
            targets.extend(p for p in root.rglob("*") if p.is_file() and p.suffix in ext)
    targets.extend(p for p in dep_files if p.exists())
    for path in sorted(set(targets)):
        if path.resolve() == SELF_PATH:
            continue  # 不扫自己：本文件为了解释判据必然含这些词
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for lineno, line in enumerate(text.splitlines(), 1):
            if not is_code_line(line):
                continue
            for m in AUTO_EVAL_RE.finditer(line):
                found.append((str(path.relative_to(REPO_ROOT)), lineno, m.group(0)))
    return found


def problems(doc_text, code_hits, has_automation_decl):
    """全部判据 → 问题列表。纯函数，便于 `--self-check` 用假数据打它。

    doc_text          : `13` 册全文
    code_hits         : scan_auto_eval() 的结果
    has_automation_decl: `13` 册里是否有 `自动评测已启用` 这句显式声明
    """
    out = []
    # A. 口径在册
    for name, anchor in DOC_ANCHORS:
        if anchor not in doc_text:
            out.append(
                f"13 册里找不到「{name}」的锚点 `{anchor}` —— 口径没写进去，"
                f"这个读数就没有可核对的定义（判据 A）"
            )
    # B. 实现未自动化
    if code_hits:
        where = "；".join(f"{f}:{n} 出现 `{w}`" for f, n, w in code_hits[:5])
        if not has_automation_decl:
            out.append(
                f"★ 代码里出现了「自动评测忠实度」的实现特征，但 13 §12.13 没有把口径改成「已自动化」"
                f"（缺锚点 `{AUTOMATION_DECL}`）—— 这就是「偷偷加了自动评测却没更新口径」。"
                f"命中 {len(code_hits)} 处：{where}"
            )
        else:
            out.append(
                f"代码里有自动评测特征（{where}），口径也写了「{AUTOMATION_DECL}」，"
                f"但本闸门的判据还没跟着改 —— 请更新 `problems()` 后再放行（判据 B6）"
            )
    return out


def main(argv):
    if "--self-check" in argv:
        good_doc = (
            f"…{DOC_ANCHORS[0][1]}…{DOC_ANCHORS[1][1]}…"
            f"{DOC_ANCHORS[2][1]}…{DOC_ANCHORS[3][1]}…"
        )
        no_hit = []
        hit = [("A.java", 10, "faithfulness")]
        checks = [
            ("口径四个锚点齐全 + 代码零命中 ⇒ 绿",
             problems(good_doc, no_hit, False) == []),
            ("四个锚点各自缺失都要报红（能红）",
             all(problems(good_doc.replace(anchor, ""), no_hit, False) != []
                 for _, anchor in DOC_ANCHORS)),
            ("★ 偷偷加自动评测、口径没更新 ⇒ 红",
             problems(good_doc, hit, False) != []),
            ("加了自动评测 + 口径已声明自动化 ⇒ 仍报红（等判据更新）",
             problems(good_doc, hit, True) != []),
            ("口径写了「已自动化」但代码零命中 ⇒ 绿（口径超前不算错）",
             problems(good_doc + AUTOMATION_DECL, no_hit, True) == []),
            ("★ 长标识符内嵌也要抓到（防回归到 `\\b` 边界）",
             bool(AUTO_EVAL_RE.search("compute_faithfulness"))
             and bool(AUTO_EVAL_RE.search("FaithfulnessEvaluator"))
             and bool(AUTO_EVAL_RE.search("RagasMetric"))
             and not AUTO_EVAL_RE.search("忠实度人工抽查")),
        ]
        failed = [name for name, ok in checks if not ok]
        for name, ok in checks:
            print(f"{'✓' if ok else '✗'} {name}")
        if failed:
            print(f"自检失败：{failed}")
            return 1
        print(f"质量指标口径自检通过（{len(checks)} 项）")
        return 0

    if not DOC_PATH.exists():
        print(f"✗ 找不到口径文档：{DOC_PATH}")
        return 1
    doc_text = DOC_PATH.read_text(encoding="utf-8")
    code_hits = scan_auto_eval()
    found = problems(doc_text, code_hits, AUTOMATION_DECL in doc_text)
    if found:
        print("✗ 忠实度 / 噪声敏感度的口径闸门没过：")
        for item in found:
            print(f"  - {item}")
        return 1

    print(
        f"✓ 口径闸门通过：13 §12.13 两个指标的定义都在册（{len(DOC_ANCHORS)} 个锚点齐全），"
        f"代码里没有自动评测忠实度的实现（扫描 {len(SCAN_ROOTS)} 处 + 依赖声明，0 命中）"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
