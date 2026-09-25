"""设计系统合规闸门 —— 客观量度，不靠眼看。

对 `src/**` 逐文件扫描，按 design.md 的硬约束打分。任一维度非零即列出。

用法：
    python check_design.py                # 全量扫描
    python check_design.py --top 25       # 只列违规最多的 25 个文件
    python check_design.py --file <路径>   # 只看一个文件（子代理自查用）

退出码：0 = 全部为零；1 = 有违规。

━━━ 为什么要有这个脚本 ━━━
"回归全绿"只说明"原来断言过的东西没坏"，不说明"用户看的东西是对的"。
本工程已经踩过：删掉冲突样式后三套回归全绿，而用户报的截断问题一行断言都没覆盖。
所以每一条设计约束都要变成可复查的数字，而不是"我看过了"。
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"

# 设计系统自身的真源文件 —— 它们**定义**令牌，本来就要写颜色与字号。
# 它们是这套规则的立法者，不参与评分；否则会得到"立法者违规 16 处"这种噪声。
FROZEN = {
    "src/styles/index.css",
    "tokens.css",
    "src/styles/element-plus.css",
    "src/styles/fonts.css",
}
FROZEN_ABS = {(ROOT / p).resolve() for p in FROZEN}

# 死代码：零处 import 的文件。它不在任何渲染路径上，计数但要单列 ——
# 否则"孤儿文件里的旧色板"会和"活代码里的违规"混在一个数字里。
ORPHAN = {"src/pages/workflow/styles/element-plus.scss"}


def strip_comments(text: str, scss: bool) -> str:
    """去掉注释再扫描。

    否则会出现这类假阳性：注释里写到 `font-family` 或某个色值，
    被当成真实声明计入违规 —— 一个会报假的检查等于没有检查。
    """
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    if scss:
        text = re.sub(r"(?m)//[^\n]*$", " ", text)
    return text


def strip_html_comments(text: str) -> str:
    """模板里也要去注释。

    否则「在注释里解释为什么不写某个写法」会被判成**用了**那个写法 ——
    实测踩过：NodeContainer 的注释里写着『不能写 :teleported="false"』，
    结果这一行被计成 1 处违规。**解释某个反模式，不该被算成犯它。**
    """
    return re.sub(r"<!--.*?-->", " ", text, flags=re.S)


def style_blocks(text: str) -> list[str]:
    """取出 <style> 块；.vue 里可能有多块（scoped / module）。"""
    return re.findall(r"<style[^>]*>(.*?)</style>", text, re.S)


def template_blocks(text: str) -> list[str]:
    m = re.search(r"<template>(.*)</template>", text, re.S)
    return [m.group(1)] if m else []


# ── 各维度 ────────────────────────────────────────────────────────────────

HEX = re.compile(r"#[0-9a-fA-F]{3,8}\b")
RGB = re.compile(r"\brgba?\s*\(")
OKLCH = re.compile(r"\boklch\s*\(", re.I)

# 这些颜色名在 CSS 里可以接受（无彩色语义），但也要计数以便审阅
CSS_KEYWORD_COLORS = re.compile(
    r"\b(?:color|background|background-color|border-color|fill|stroke)\s*:\s*"
    r"(?:white|black|red|green|blue|gray|grey)\b",
    re.I,
)

# ⚠️ 这两条**不要**写成负向先行断言。第一版写的是
#     `font-family\s*:\s*(?!var\(|inherit\b)`
#   实测会把 `font-family: var(--x)` 与 `font-family: inherit` 都算成违规 ——
#   因为 `\s*` 可以回溯到"零个空格"，断言检查的位置就落在那个空格上（`" var("`），
#   而不是 `var(` 上 ⇒ 断言成立 ⇒ 整体匹配成功。
#   **可回溯的量词 + 负向断言 = 判据形同虚设。** 正解是把值抓出来再判。
FONT_FAMILY_DECL = re.compile(r"font-family\s*:\s*([^;}]+)", re.I)
FAMILY_OK = re.compile(r"^\s*(?:var\(|inherit\b|unset\b|initial\b|revert\b)", re.I)


def count_inline_font_family(css: str) -> int:
    """写死了具体字族的声明数（合规的 `var(--token)` 与全局关键字不算）。"""
    return sum(1 for v in FONT_FAMILY_DECL.findall(css) if not FAMILY_OK.match(v))


FONT_SIZE = re.compile(r"font-size\s*:\s*(\d+(?:\.\d+)?)px", re.I)
FONT_ITALIC = re.compile(r"font-style\s*:\s*italic", re.I)

SIDE_STRIPE = re.compile(r"border-(?:left|right)\s*:\s*([3-9]|\d{2,})px\s+solid", re.I)
VIEWPORT_H = re.compile(r"(?:min-)?height\s*:\s*100(?:d)?vh", re.I)
ZINDEX_BIG = re.compile(r"z-index\s*:\s*\d{4,}", re.I)
GRADIENT_TEXT = re.compile(r"background-clip\s*:\s*text", re.I)
TRANSITION_ALL = re.compile(r"transition\s*:\s*all\b", re.I)
BROWSER_EASE = re.compile(r"\bease\b(?!-out|-in|-in-out|-linear)", re.I)

# 旧令牌名（迁移期）。tokens.css 末端有一层临时兼容垫片顶着它们，
# 但页面应当迁到新名字 —— 垫片的删除触发条件就是这个数为 0。
# 注意交替顺序：长名字必须排在短名字前面，否则 `--line-2` 会被 `--line` 先吃掉。
LEGACY_RE = re.compile(
    r"var\(\s*--(?:brand-soft|brand-line|brand-2|danger-soft|warn-soft|ok-soft|"
    r"brand|canvas|surface|sunken|line-2|line|ink-2|ink|muted|ok|warn|danger|font|data)\s*\)"
)

TELEPORTED_FALSE = re.compile(r':teleported\s*=\s*"false"')

# 模板里把 emoji 塞进 img 的 src
EMOJI_IN_IMG = re.compile(r"<img[^>]*:?src\s*=\s*[\"'][^\"']*[\U0001F300-\U0001FAFF\u2600-\u27BF]")
EMOJI_ANY = re.compile(r"[\U0001F300-\U0001FAFF\u2600-\u27BF]")

HAS_FOCUS_VISIBLE = re.compile(r":focus-visible")
HAS_REDUCED_MOTION = re.compile(r"prefers-reduced-motion")


class Report:
    def __init__(self) -> None:
        self.per_file: dict[str, dict[str, int]] = {}
        self.global_hits: dict[str, list[str]] = {}

    def add(self, path: str, key: str, n: int = 1) -> None:
        if not n:
            return
        self.per_file.setdefault(path, {})
        self.per_file[path][key] = self.per_file[path].get(key, 0) + n

    def add_global(self, key: str, where: str) -> None:
        self.global_hits.setdefault(key, []).append(where)


def scan_file(path: Path, rel: str, rep: Report) -> None:
    # 真源文件不参与评分 —— 它是立法者
    if path.resolve() in FROZEN_ABS:
        return

    text = path.read_text(encoding="utf-8", errors="replace")
    is_css = path.suffix == ".css"
    is_scss = path.suffix == ".scss"
    orphan = rel in ORPHAN

    if is_css or is_scss:
        css = strip_comments(text, scss=True)
        tpl = ""
    else:
        blocks = style_blocks(text)
        # scoped 样式和全局样式一样评分 —— 两者都进了最终样式表
        css = strip_comments("\n".join(blocks), scss=True)
        tpl = strip_html_comments("\n".join(template_blocks(text)))

    # 孤儿文件单列命名空间，避免"死代码里的旧色板"污染活代码的读数
    ns = ("[孤儿] " if orphan else "") + rel

    if css:
        # 令牌纪律：内联色值。页面里应当全部走 var(--token)
        rep.add(ns, "内联色值", len(HEX.findall(css)) + len(RGB.findall(css)) + len(OKLCH.findall(css)))
        rep.add(ns, "关键字颜色", len(CSS_KEYWORD_COLORS.findall(css)))
        rep.add(ns, "内联 font-family", count_inline_font_family(css))

        # 中文下限：正文不许低于 12px
        small = [float(v) for v in FONT_SIZE.findall(css) if float(v) < 12]
        rep.add(ns, "字号<12px", len(small))

        # 中文没有真斜体
        rep.add(ns, "斜体", len(FONT_ITALIC.findall(css)))

        # 反模式
        rep.add(ns, "彩色左/右粗条", len(SIDE_STRIPE.findall(css)))
        rep.add(ns, "100vh", len(VIEWPORT_H.findall(css)))
        rep.add(ns, "z-index>=1000", len(ZINDEX_BIG.findall(css)))
        rep.add(ns, "渐变文字", len(GRADIENT_TEXT.findall(css)))
        rep.add(ns, "transition:all", len(TRANSITION_ALL.findall(css)))
        rep.add(ns, "浏览器默认ease", len(BROWSER_EASE.findall(css)))
        rep.add(ns, "旧令牌引用", len(LEGACY_RE.findall(css)))

    if tpl:
        # 节点内浮层写 :teleported="false" ⇒ popper 进 foreignObject ⇒ 用户选不了
        rep.add(ns, 'teleported="false"', len(TELEPORTED_FALSE.findall(tpl)))
        rep.add(ns, "emoji塞进img", len(EMOJI_IN_IMG.findall(tpl)))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--top", type=int, default=0, help="只列违规最多的 N 个文件")
    ap.add_argument("--file", default="", help="只看一个文件（相对工程根）")
    args = ap.parse_args()

    files = sorted(SRC.rglob("*")) if not args.file else [ROOT / args.file]
    rep = Report()
    total = 0

    for p in files:
        if not p.is_file():
            continue
        if p.suffix not in (".vue", ".css", ".scss", ".ts"):
            continue
        if "node_modules" in p.parts:
            continue
        rel = p.relative_to(ROOT).as_posix()
        scan_file(p, rel, rep)
        total += 1

    # ── 汇总 ──
    keys = [
        "内联色值",
        "关键字颜色",
        "内联 font-family",
        "字号<12px",
        "斜体",
        "彩色左/右粗条",
        "100vh",
        "z-index>=1000",
        "渐变文字",
        "transition:all",
        "浏览器默认ease",
        "旧令牌引用",
        'teleported="false"',
        "emoji塞进img",
    ]

    print(f"扫描 {total} 个文件（其中 {len(FROZEN)} 个真源文件不计分）\n")
    print("── 活代码合计（越低越好，目标全 0） ──")
    grand = 0
    for k in keys:
        n = sum(f.get(k, 0) for rel, f in rep.per_file.items() if not rel.startswith("[孤儿]"))
        grand += n
        flag = "✓" if n == 0 else "✗"
        print(f"  {flag} {k:<20} {n}")
    print(f"\n  活代码违规合计 = {grand}")

    orphan_total = sum(
        sum(d.values()) for rel, d in rep.per_file.items() if rel.startswith("[孤儿]")
    )
    if orphan_total:
        print(f"  （另有 {orphan_total} 处在孤儿文件里 —— 零处 import，不在渲染路径上）")

    # ── 逐文件 ──
    rows = []
    for rel, d in rep.per_file.items():
        s = sum(d.get(k, 0) for k in keys)
        if s:
            rows.append((s, rel, d))
    rows.sort(reverse=True)

    if rows:
        shown = rows[: args.top] if args.top else rows
        print(f"\n── 违规最多的文件（共 {len(rows)} 个有违规，列 {len(shown)} 个） ──")
        for s, rel, d in shown:
            detail = " ".join(f"{k}×{d[k]}" for k in keys if d.get(k))
            print(f"  {s:>4}  {rel}")
            print(f"        {detail}")

    return 1 if grand else 0


if __name__ == "__main__":
    sys.exit(main())
