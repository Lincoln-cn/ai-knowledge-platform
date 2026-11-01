#!/usr/bin/env python3
"""`app.*` 配置键的引用与声明必须对得上（静默失效族的闸门）。

为什么需要它：`@Value("${app.foo.bar:4}")` 里的键只要拼错一个字母，Spring **不会报错** ——
它用默认值把服务起起来，于是"我明明配了 8，怎么还是 4"变成一次没人能解释的排查。
本项目把这条叫头号病 `silent-failure` 的一种，所以用一条静态判据挡住：

  1. 代码里引用的 `app.*` 键，必须在 `application.yml` 里**有声明**（值可以是占位符 `${ENV:default}`）；
  2. `application.yml` 里声明的 `app.*` 键，如果**没有任何代码引用**，不算错（可能是留给运维的开关），
     但要在 `--report` 里列出来 —— 让"声明了却没人读"这件事可见。

跑法：
    python tools/check_config_keys.py            # 判据，退出码 0/1
    python tools/check_config_keys.py --report   # 额外列出无人引用的键
    python tools/check_config_keys.py --self-check   # 离线自检（CI 用）
"""

import io
import re
import sys
from pathlib import Path

JAVA_ROOT = Path("src/main/java")
YML = Path("src/main/resources/application.yml")

VALUE_RE = re.compile(r'@Value\(\s*"\$\{([^}:"]+)(?::[^}]*)?\}"\s*\)')


def yml_keys(text):
    """把 YAML 的层级结构摊平成 `a.b.c` 键名（只处理块式写法，数组与多行串跳过）。"""
    keys = set()
    stack = []  # [(depth, name), ...] 当前祖先链
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        expanded = raw.replace("	", "  ")
        indent = len(expanded) - len(expanded.lstrip(" "))
        line = expanded.strip()
        if ":" not in line or line.startswith("-"):
            continue
        name, _, _rest = line.partition(":")
        name = name.strip().strip("'\"")
        if not name:
            continue
        depth = indent // 2
        stack = [item for item in stack if item[0] < depth]
        keys.add(".".join([n for _, n in stack] + [name]))
        stack.append((depth, name))
    return keys


def yml_text():
    """优先读**仓库里那一份**（`git show HEAD:`）：本地工作树可能被别人未提交的旧副本覆盖，
    拿它做判据会报出一串"其实已经声明了"的假警报 —— 那是闸门自己在骗人。

    CI 里工作树就等于 HEAD，两种读法没有区别。
    """
    import subprocess

    try:
        out = subprocess.run(
            ["git", "show", f"HEAD:backend/{YML.as_posix()}"],
            capture_output=True,
            text=True,
            check=True,
        )
        return out.stdout
    except Exception:
        return YML.read_text(encoding="utf-8") if YML.exists() else None


def code_keys(root=JAVA_ROOT):
    """代码里引用的 `app.*` 键 → 出现位置列表。"""
    found = {}
    for path in sorted(root.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        for match in VALUE_RE.finditer(text):
            key = match.group(1).strip()
            if key.startswith("app."):
                found.setdefault(key, []).append(str(path))
    return found


def problems(declared, referenced):
    missing = sorted(k for k in referenced if k not in declared)
    return [f"代码引用了 {k}，但 application.yml 里没有这个键（Spring 会用默认值静默起起来）"
            for k in missing]


def main(argv):
    if "--self-check" in argv:
        declared = yml_keys("app:\n  demo:\n    on: true\n    size: 4\n")
        referenced = {"app.demo.on": ["A.java"], "app.demo.size": ["A.java"]}
        bad = {"app.demo.missing": ["A.java"]}
        checks = [
            ("声明与引用一致", problems(declared, referenced) == []),
            ("引用了一个没声明的键", problems(declared, bad) != []),
            ("占位符写法也能解析", "app.demo.size" in declared),
        ]
        failed = [name for name, ok in checks if not ok]
        for name, ok in checks:
            print(f"{'✓' if ok else '✗'} {name}")
        if failed:
            print(f"自检失败：{failed}")
            return 1
        print(f"配置键自检通过（{len(checks)} 项）")
        return 0

    text = yml_text()
    if text is None:
        print(f"✗ 找不到 {YML}")
        return 1
    declared = yml_keys(text)
    referenced = code_keys()
    found = problems(declared, referenced)
    if found:
        print("✗ 配置键对不上：")
        for item in found:
            print(f"  - {item}")
        return 1

    if "--report" in argv:
        unused = sorted(k for k in declared if k.startswith("app.") and k not in referenced)
        print(f"（参考）声明但无人引用的 app.* 键 {len(unused)} 个：")
        for key in unused:
            print(f"  · {key}")

    print(f"✓ 配置键一致：代码引用 {len(referenced)} 个 app.* 键，全部在 application.yml 里有声明")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
