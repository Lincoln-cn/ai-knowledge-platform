"""管理菜单的一致性闸门：菜单名 = 页面名、字数、图标唯一。

## 为什么要有这道闸门
`ROADMAP` 的 §11.52.4 定了命名六条（N1-N6）。这些要求靠人眼逐页核对会漏，
而漏掉的形态恰好是"看不出来"的：页面标题写四个字、菜单写三个字，
两个都通顺，只有并排看才发现不一样。N3「label = 页面 title 一字不差」更是
typecheck 与 build 都抓不到的 —— 它们是两个独立的字符串字面量。

## 判据（每条都能红）
1. `navGroups` 里每一项的 path 都能落到 `pages{path}/Index.vue`
2. 该页 `<PageHeader title="X">` 的 X 与菜单 label 一字不差（N3）
   * 全屏工作区没有 PageHeader，豁免写死在 `NO_HEADER` 里，不许"读不到就跳过" -
     那会让新增的无标题页静默通过
3. label 长度 2-4 字（N1），不以「配置」「设置」结尾（N2）
4. `<PageHeader desc="X">` 的 X 长度 24-36 字（N4），不含「——」、圆括号、指代词
5. 同一组内 icon 不重复（N5）

## 用法
python check_nav_titles.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ROUTES = ROOT / "src" / "router" / "routes.ts"

# 全屏工作区没有 PageHeader（对话是整屏聊天 + 自己的 TopBar）。
# 豁免要写死在这里：新加的无标题页必须来登记一次。
NO_HEADER = {"/conversation"}

ITEM_RE = re.compile(
    r"^\s*\{\s*path:\s*'([^']+)',\s*label:\s*'([^']+)',\s*icon:\s*'([^']+)'\s*\},?\s*$"
)
GROUP_RE = re.compile(r"^\s*title:\s*'([^']+)',\s*$")
HEADER_RE = re.compile(r"<PageHeader\b[^>]*>", re.S)
TITLE_ATTR = re.compile(r'(?<![:\w])title="([^"]+)"')
DESC_ATTR = re.compile(r'(?<![:\w])desc="([^"]+)"')
DESC_BOUND = re.compile(r':desc="([^"]*)"')
QUOTED = re.compile(r"'([^']+)'")

BANNED_DESC = ["——", "（", "(", "这里", "本页", "此处"]
BANNED_SUFFIX = ["配置", "设置"]


def nav_groups():
    """从 routes.ts 抽出 [(组名, [(path, label, icon), ...]), ...]。"""
    src = ROUTES.read_text(encoding="utf-8")
    start = src.find("export const navGroups")
    if start < 0:
        return None
    body = src[start:]
    nxt = body.find("\nexport ", 1)
    if nxt > 0:
        body = body[:nxt]

    groups: list[tuple[str, list[tuple[str, str, str]]]] = []
    cur_title: str | None = None
    cur_items: list[tuple[str, str, str]] = []
    for line in body.splitlines():
        item = ITEM_RE.match(line)
        if item:
            if cur_title is None:
                return None
            cur_items.append(item.groups())
            continue
        group = GROUP_RE.match(line)
        if group:
            if cur_title is not None:
                groups.append((cur_title, cur_items))
            cur_title = group.group(1)
            cur_items = []
    if cur_title is not None:
        groups.append((cur_title, cur_items))
    return groups


def main() -> int:
    groups = nav_groups()
    if not groups:
        print("X 从 routes.ts 读不出 navGroups - 闸门自身失效（解析器与文件结构对不上了）")
        return 3

    problems: list[str] = []
    total = 0
    for title, items in groups:
        icons: dict[str, str] = {}
        if len(items) > 10:
            print(f"! 组「{title}」有 {len(items)} 项，超过一屏（§11.52.6 的加层阈值）")
        for path, label, icon in items:
            total += 1

            if not (2 <= len(label) <= 4):
                problems.append(f"{path}: label「{label}」是 {len(label)} 字（N1 要求 2-4）")
            for suffix in BANNED_SUFFIX:
                if label.endswith(suffix):
                    problems.append(f"{path}: label「{label}」以「{suffix}」结尾（N2）")

            if icon in icons:
                problems.append(f"组「{title}」: icon「{icon}」被「{icons[icon]}」与「{label}」共用（N5）")
            else:
                icons[icon] = label

            page = ROOT / "src" / "pages" / path.strip("/") / "Index.vue"
            if not page.exists():
                problems.append(f"{path}: 找不到页面 src/pages{path}/Index.vue（判据 1）")
                continue
            if path in NO_HEADER:
                continue

            src = page.read_text(encoding="utf-8")
            header = HEADER_RE.search(src)
            if not header:
                problems.append(f"{path}: 页面里没有 <PageHeader>；若是新的全屏页，请加进 NO_HEADER（判据 2）")
                continue
            head = header.group(0)

            page_title = TITLE_ATTR.search(head)
            if not page_title:
                problems.append(f"{path}: PageHeader 没有 title 静态属性（判据 2）")
            elif page_title.group(1) != label:
                problems.append(
                    f"{path}: 菜单 label「{label}」≠ 页面 title「{page_title.group(1)}」（N3）"
                )

            # desc 有两种写法：静态字符串，或者一个消费者一个建设者口径的
            # 三元表达式（见 pages/knowledge/Index.vue）。两种都要逐句校验 -
            # 只校验其中一个分支，等于让另一个分支永远没人管。
            texts: list[str] = []
            desc = DESC_ATTR.search(head)
            if desc:
                texts = [desc.group(1)]
            else:
                bound = DESC_BOUND.search(head)
                if bound:
                    texts = QUOTED.findall(bound.group(1))
            if not texts:
                problems.append(f"{path}: PageHeader 既没有 desc 也没有 :desc（判据 4）")
            for text in texts:
                if not (24 <= len(text) <= 36):
                    problems.append(f"{path}: desc 长度 {len(text)}（N4 要求 24-36）：「{text}」")
                for banned in BANNED_DESC:
                    if banned in text:
                        problems.append(f"{path}: desc 含「{banned}」（N4）：「{text}」")

    if problems:
        print(f"X {len(problems)} 处不一致：")
        for one in problems:
            print(f"  - {one}")
        return 1

    print(f"OK 菜单 {total} 项 / {len(groups)} 组：title 与 label 一致、字数与图标都合规")
    return 0


if __name__ == "__main__":
    sys.exit(main())