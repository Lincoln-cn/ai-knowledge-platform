#!/usr/bin/env python
"""部署文档必须覆盖 `application.yml` 里的每一个 `app.*` 配置键。

判据（`#272` 判据 ②）：一个键如果没有任何一处部署文档提到它，运维就不知道它存在 ——
而这类键的错误形状恰恰是"配错了不报错，只在某个场景里表现怪"（JWT issuer 写成裸串、
限流的 capacity 与 window 只改一个、本地向量两个文件只拷一个）。

判定方式：键可以以**点分名**（`app.ocr.dpi`）或**环境变量名**（`OCR_DPI`）出现在文档里 ——
两种写法都算覆盖。环境变量名按 Spring 的宽松绑定规则从点分名推：去掉 `app.` 前缀、
`.` 与 `-` 都换成 `_`、转大写。

★ 读的是 `git show HEAD:backend/src/main/resources/application.yml`（不是工作区文件）：
本仓库的工作区里经常躺着别人未提交的 `application.yml`，拿它当判据会报一串假的"缺失"
（`#75`/`#227` 那一批踩过一次）。

用法：
    python tools/check_config_docs.py            # 实判，缺一个就退 1
    python tools/check_config_docs.py --report   # 只列出覆盖情况
    python tools/check_config_docs.py --self-check
"""

import re
import subprocess
import sys

YML_PATH = "backend/src/main/resources/application.yml"
DOC_PATH = "deploy/README.md"


def head_text(path):
    """取 HEAD 里的那一份文件内容（工作区可能被人改过）。"""
    done = subprocess.run(
        ["git", "show", "HEAD:" + path],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if done.returncode != 0:
        raise SystemExit("✗ 读不到 HEAD 里的 %s：%s" % (path, done.stderr.strip()))
    return done.stdout


def app_entries(text):
    """从 yml 文本里取出 `app.*` 的叶子键与它的值文本。

    ★ 不用 PyYAML：快闸那个 job 里没有它（装了也是给闸门加一条外部依赖，
    而这台闸门只关心 `app:` 段里的块式写法 —— 数组与多行串在这里不出现）。
    做法与 `check_config_keys.py` 的摊平器同源：按两空格缩进维护祖先链。
    """
    entries = {}
    stack = []
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        expanded = raw.replace("	", "  ")
        indent = len(expanded) - len(expanded.lstrip(" "))
        line = expanded.strip()
        if ":" not in line or line.startswith("-"):
            continue
        name, _, rest = line.partition(":")
        name = name.strip().strip("'\"")
        if not name:
            continue
        depth = indent // 2
        stack = [item for item in stack if item[0] < depth]
        full = ".".join([n for _, n in stack] + [name])
        value = rest.strip()
        if full.startswith("app.") and value:
            entries[full] = value
        stack.append((depth, name))
    return entries


def env_name(key):
    """按 Spring 宽松绑定规则推的环境变量名：`app.ocr.tesseract-path` → `OCR_TESSERACT_PATH`。"""
    body = key[4:] if key.startswith("app.") else key
    return body.replace(".", "_").replace("-", "_").upper()


PLACEHOLDER = re.compile(r"^\$\{([A-Za-z_][A-Za-z0-9_]*)[:}]")


def env_candidates(key, value):
    """这个键可能被写成的环境变量名。

    ★ 两个来源都要看：yml 里显式写的 `${NAME:默认}` 才是真假 —— `app.security.public-rate-limit.enabled`
    的真实环境变量叫 `PUBLIC_RATE_LIMIT_ENABLED`，而不是按规则推出来的那个长名字。
    推导名只作为兜底（键没有占位符时用它）。
    """
    names = set()
    if isinstance(value, str):
        found = PLACEHOLDER.match(value.strip())
        if found:
            names.add(found.group(1))
    names.add(env_name(key))
    return names


def missing_keys(keys, doc):
    """哪些键在文档里一次都没出现（点分名与任一环境变量名都算）。"""
    missing = []
    for key in sorted(keys):
        if key in doc:
            continue
        if any(name in doc for name in env_candidates(key, keys[key])):
            continue
        missing.append(key)
    return missing


def self_check():
    """离线用例：每条都造一个最小输入，不去读仓库文件。"""
    keys = {
        "app.ocr.dpi": "${OCR_DPI:300}",
        "app.security.jwt.issuer": "http://x",
        "app.a.b-c": 1,
    }
    cases = []

    cases.append(
        ("全用点分名 -> 齐", missing_keys(keys, "app.ocr.dpi app.security.jwt.issuer app.a.b-c"), [])
    )
    cases.append(
        (
            "全用环境变量名 -> 齐",
            missing_keys(keys, "OCR_DPI SECURITY_JWT_ISSUER A_B_C"),
            [],
        )
    )
    cases.append(
        (
            "漏一个 -> 点出来",
            missing_keys(keys, "app.ocr.dpi OCR_DPI SECURITY_JWT_ISSUER"),
            ["app.a.b-c"],
        )
    )
    cases.append(("空文档 -> 全缺", missing_keys(keys, ""), sorted(keys)))
    cases.append(("派生：连字符也换下划线", env_name("app.a.b-c"), "A_B_C"))
    cases.append(("派生：只去 app. 前缀", env_name("app.ocr.dpi"), "OCR_DPI"))
    cases.append(
        (
            "占位符名优先于推导名",
            sorted(env_candidates("app.security.public-rate-limit.enabled", "${PUBLIC_RATE_LIMIT_ENABLED:true}")),
            ["PUBLIC_RATE_LIMIT_ENABLED", "SECURITY_PUBLIC_RATE_LIMIT_ENABLED"],
        )
    )

    failed = 0
    for name, got, want in cases:
        if got == want:
            print("  ✓ " + name)
        else:
            print("  ✗ %s：期望 %r，实际 %r" % (name, want, got))
            failed += 1

    if failed:
        print("✗ 自检未通过（%d 条）" % failed)
        return 1
    print("✓ 自检通过（%d 条）" % len(cases))
    return 0


def main(argv):
    if "--self-check" in argv:
        return self_check()

    keys = app_entries(head_text(YML_PATH))
    if not keys:
        print("✗ %s 里一个 app.* 键都没读到 —— 解析器坏了，不是「没有配置」" % YML_PATH)
        return 1

    doc = head_text(DOC_PATH)
    missing = missing_keys(keys, doc)

    if "--report" in argv:
        print("app.* 键 %d 个；文档未提及 %d 个" % (len(keys), len(missing)))
        for key in missing:
            print("  · %s（或写 %s）" % (key, env_name(key)))

    if missing:
        print("✗ %d 个 app.* 键在 %s 里一次都没出现：" % (len(missing), DOC_PATH))
        for key in missing:
            print("    %s（或写 %s）" % (key, env_name(key)))
        return 1

    print("✓ 部署文档覆盖全部 %d 个 app.* 键" % len(keys))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
