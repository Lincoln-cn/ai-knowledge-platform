"""把一份"问题登记"批量开成 GitHub Issue。

★ **这不是日常路径**。日常是发现一条就开一条：

    gh issue create --title "…" --label bug,priority:p2 --body-file <文件>

本脚本只用于**批量补录** —— 首次接入 Issue 管理，或把某处积压的问题一次性搬上去。
用完可以删；留着是因为下一次"批量补录"一定会再来一次（本项目的欠账是攒着做的）。

用法：

    python tools/create_issues.py .git/issues.md

输入格式（★ 用文件而不是命令行 `-m`：正文里有中文引号、代码块与反引号，
走 shell 一定会被引号/转义弄坏 —— 本项目已栽过一次）：

    === LABELS: bug,ocr,priority:p1
    === TITLE: 标题
    正文…

★ 每条正文都应当照这个形状写（`docs/已知问题与欠账.md` 与项目记忆里的 R60 规则）：
**现象 → 实测证据（不是推断）→ 根因 → 危害 → 处置 → 判据 → 出处**。
判据尤其重要：「改完之后看什么读数算好了」写不出来，说明这条问题还没被理解清。
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def parse(text: str) -> list[tuple[list[str], str, str]]:
    blocks: list[tuple[list[str], str, str]] = []
    current: list[str] = []
    for line in text.splitlines():
        if line.startswith("=== LABELS:"):
            if current:
                blocks.append(finish(current))
            current = [line]
        elif current:
            current.append(line)
    if current:
        blocks.append(finish(current))
    return blocks


def finish(lines: list[str]) -> tuple[list[str], str, str]:
    labels = [s.strip() for s in lines[0].split("=== LABELS:")[1].split(",") if s.strip()]
    title = ""
    body: list[str] = []
    for line in lines[1:]:
        if line.startswith("=== TITLE:"):
            title = line.split("=== TITLE:", 1)[1].strip()
        else:
            body.append(line)
    return labels, title, "\n".join(body).strip() + "\n"


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    source = Path(sys.argv[1])
    if not source.is_absolute():
        source = ROOT / source
    if not source.exists():
        print(f"输入不存在：{source}")
        return 2

    tmp = ROOT / ".git/_issue_body.md"
    blocks = parse(source.read_text(encoding="utf-8"))
    print(f"解析到 {len(blocks)} 条问题\n")
    created: list[tuple[int, str]] = []
    for labels, title, body in blocks:
        tmp.write_text(body, encoding="utf-8")
        result = subprocess.run(
            [
                "gh", "issue", "create",
                "--title", title,
                "--body-file", str(tmp),
                "--label", ",".join(labels),
            ],
            cwd=ROOT, capture_output=True, text=True, encoding="utf-8",
        )
        if result.returncode != 0:
            print(f"  [NG] {title}\n       {result.stderr.strip()[:200]}")
            continue
        url = result.stdout.strip().splitlines()[-1]
        number = int(re.search(r"/(\d+)$", url).group(1))
        created.append((number, title))
        print(f"  [OK] #{number}  {title[:60]}")

    mapping = ROOT / ".git/issue_map.md"
    mapping.write_text(
        "# 问题 → Issue 编号映射\n\n" + "\n".join(f"- #{n} {t}" for n, t in created) + "\n",
        encoding="utf-8",
    )
    print(f"\n共创建 {len(created)}/{len(blocks)} 条；映射写入 {mapping}")
    return 0 if len(created) == len(blocks) else 1


if __name__ == "__main__":
    sys.exit(main())
