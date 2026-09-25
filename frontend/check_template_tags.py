"""定位 Vue SFC 模板里未闭合 / 多余闭合的标签。

编译器只报最外层那个（“Element is missing end tag”），
真正的漏点往往在内部某一行。这里做一次标签配平，直接指出位置。

用法：python check_template_tags.py <file.vue> [更多文件...]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "param", "source", "track", "wbr"}

TAG = re.compile(r"<(/?)([a-zA-Z][\w.-]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*?)(/?)>", re.S)


def template_span(text: str) -> tuple[int, str]:
    """取出 <template> ... </template> 顶层块，返回 (起始行号, 内容)"""
    start = text.find("<template")
    if start < 0:
        return 1, ""
    open_end = text.find(">", start) + 1
    # 从后往前找最后一个 </template>
    end = text.rfind("</template>")
    return text[:open_end].count("\n") + 1, text[open_end:end]


def check(path: Path) -> int:
    text = path.read_text(encoding="utf-8", errors="replace")
    base_line, body = template_span(text)
    body = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), body, flags=re.S)

    stack: list[tuple[str, int]] = []
    problems = []

    for match in TAG.finditer(body):
        closing, name, _attrs, self_close = match.groups()
        line = base_line + body[: match.start()].count("\n")
        lower = name.lower()

        if closing:
            if not stack:
                problems.append(f"第 {line} 行：多余的 </{name}>（栈已空）")
                continue
            top_name, top_line = stack[-1]
            if top_name == lower:
                stack.pop()
            else:
                # 找栈里有没有它，若有则中间的都没闭合
                idx = next((i for i in range(len(stack) - 1, -1, -1) if stack[i][0] == lower), None)
                if idx is None:
                    problems.append(f"第 {line} 行：</{name}> 与栈顶 <{top_name}>（第 {top_line} 行）不匹配")
                else:
                    for open_name, open_line in stack[idx + 1:]:
                        problems.append(f"第 {open_line} 行：<{open_name}> 未闭合（被第 {line} 行的 </{name}> 截断）")
                    del stack[idx + 1:]
                    stack.pop()
        elif self_close or lower in VOID:
            continue
        else:
            stack.append((lower, line))

    for open_name, open_line in stack:
        problems.append(f"第 {open_line} 行：<{open_name}> 未闭合（到模板结束仍未关闭）")

    print(f"\n=== {path.name} ===")
    if problems:
        for problem in problems:
            print("  ✗ " + problem)
    else:
        print("  ✓ 标签配平")
    return 1 if problems else 0


if __name__ == "__main__":
    targets = [Path(a) for a in sys.argv[1:]]
    if not targets:
        print(__doc__)
        raise SystemExit(2)
    bad = sum(check(p) for p in targets)
    print(f"\n{len(targets)} 个文件，{bad} 个有问题")
    raise SystemExit(1 if bad else 0)
