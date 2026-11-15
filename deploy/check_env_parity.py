#!/usr/bin/env python3
"""核对 `deploy/README.md` 的环境变量表与 `application.yml` 的 `${KEY:default}` 是否一致。

为什么需要这个脚本
==================
部署手册与代码分属两个文件，`application.yml` 是运行时真源、`deploy/README.md` 是运维看的
那份。两者漂了不会报错：运维照文档配了一个代码里根本不读的变量（空转），或照文档的默认值
配了一个代码里默认值不同的变量（行为与手册写的不一样）。这两种都不会有任何报错。

三种差异，严重程度递增
======================
1. `yml` 里有、文档表里没有 —— **漏写**。运维不知道有这个旋钮，遇到问题时无从下手。
2. 文档表里有、`yml` 里没有 —— **错文档**。照它配是个空转，而且是"看起来配了"的空转。
3. 两边都有、默认值不同 —— **最严重**。照文档配会得到与手册描述不同的行为。

退出码
======
- `0`：只有第 1 类差异，或第 2/3 类差异全部落在 `ALLOWED_...` 豁免表里。
- `1`：存在未被豁免的第 2 类或第 3 类差异。

用法
====
    python deploy/check_env_parity.py            # 人读
    python deploy/check_env_parity.py --verbose  # 连第 1 类漏写也逐条列出

维护规则
========
- 新增 `${ENV:default}` ⇒ 在 `deploy/README.md` 的环境变量表里加一行；否则本脚本报"漏写"。
- 改 `application.yml` 的默认值 ⇒ 同步改文档表那一格；否则本脚本报"默认值不同"。
- 确实不需要写进文档的（内部开关、测试专用），加进 `ALLOWED_UNDOCUMENTED` 并写一句理由。
  **豁免表只接受"不需要写"，不接受"来不及写"。**
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
YML = ROOT / "backend" / "src" / "main" / "resources" / "application.yml"
README = ROOT / "deploy" / "README.md"

# yml 里被引用、但确实不需要写进部署手册的键：值 + 理由。
ALLOWED_UNDOCUMENTED: dict[str, str] = {
    "AI_API_KEY": "与 `ai_model_provider` 表里的密钥重复；平台侧密钥走数据库，不走环境变量",
    "AIP_OLLAMA_MAX_LOADED_MODELS": "本地 Ollama 的可选调优项，默认不设即走 Ollama 自身策略",
    "LOG_FILE": "日志落文件是可选项，默认只打 stdout（容器里由 docker logs 收）",
    "SSE_ASYNC_TIMEOUT": "流式响应的内部超时，改动属于调优而非部署必填",
    "UPLOAD_MAX_FILE_SIZE": "★ 真正的上传上限由 `UPLOAD_MAX_REQUEST_SIZE` 决定（Tomcat 层），这一个只在 multipart 解析层生效，单独改没有效果",
    "UPLOAD_MAX_REQUEST_SIZE": "同上：容器部署时由 `deploy/docker-compose.yml` 的 nginx/Tomcat 配置承担，不写在环境变量表里",
}

# 文档表里出现、但代码并不读的键：要么是笔误，要么是已经改名的旧键。
ALLOWED_UNDOCUMENTED_EXTRAS: dict[str, str] = {
    "TESSDATA_PREFIX": "Tesseract 自己的变量，由 `OCR_TESSDATA_PATH` 间接设置，不是本应用的读点",
}

# 文档故意不写字面量默认值的键：把它们当"默认值不同"会逼着文档抄一份密钥进仓库。
ALLOWED_TEXTUAL_DEFAULTS: dict[str, str] = {
    "JWT_SECRET": "文档写的是「仓库里的示例值」而不是那串 base64 —— 抄进文档等于把开发默认值固化成交付默认值",
    "MODEL_API_KEY_SECRET": "同上",
}

# 默认值归一：文档里写的是人话，yml 里写的是字面量。
def normalize(raw: str) -> str:
    """把两侧的默认值压成可直接比较的形状。"""
    text = raw.strip()
    # markdown 强调标记：文档里 `**false**` 与 yml 的 `false` 是同一个值
    text = text.replace("**", "").replace("`", "").strip()
    # 文档里给人的补充说明，如 `空（走 PATH）`、`300`、`false`
    for marker in ("（", "("):
        if marker in text:
            text = text.split(marker)[0].strip()
    if text in {"空", "留空", "未设置", "-", ""}:
        return "<empty>"
    if text.lower() in {"null", "none"}:
        return "<empty>"
    return text


def yml_env_defaults() -> dict[str, str]:
    """抽出 `${KEY:default}` 与 `${KEY}` 两种写法。"""
    text = YML.read_text(encoding="utf-8")
    found: dict[str, str] = {}
    # 去掉 yml 的注释行，避免把注释里举例的 ${FOO:bar} 当成真引用
    body = "\n".join(
        line for line in text.splitlines() if not line.lstrip().startswith("#")
    )
    for match in re.finditer(r"\$\{([A-Z][A-Z0-9_]+)(?::([^}]*))?\}", body):
        key, default = match.group(1), match.group(2)
        found[key] = normalize(default if default is not None else "")
    return found


def readme_env_defaults() -> dict[str, str]:
    """抽出文档表里的环境变量。

    文档里有两种写法，必须都认（只认第一种会把 `## 八之补二` 整节判成"没写"）：
      A. `| `AUDIT_RETENTION_DAYS` | 365 | 说明 |`          —— 第一列直接是 ENV
      B. `| `app.retrieval.rerank.top-k`（`RETRIEVAL_RERANK_TOP_K`） | 10 |` —— 第一列是
         配置键 + 全角括号里的 ENV
    """
    text = README.read_text(encoding="utf-8")
    found: dict[str, str] = {}
    for line in text.splitlines():
        direct = re.match(r"^\|\s*`([A-Z][A-Z0-9_]{2,})`\s*\|\s*([^|]*)\|", line)
        nested = re.match(r"^\|\s*`[^`]+`\s*（\s*`([A-Z][A-Z0-9_]{2,})`\s*）\s*\|\s*([^|]*)\|", line)
        match = direct or nested
        if match:
            found[match.group(1)] = normalize(match.group(2))
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description="核对部署手册与 application.yml 的环境变量")
    parser.add_argument("--verbose", action="store_true", help="连漏写项也逐条列出")
    args = parser.parse_args()

    for path in (YML, README):
        if not path.exists():
            print(f"找不到 {path}（这个脚本要在仓库根运行）")
            return 1

    yml = yml_env_defaults()
    doc = readme_env_defaults()

    undocumented = sorted(set(yml) - set(doc))
    undocumented = [k for k in undocumented if k not in ALLOWED_UNDOCUMENTED]
    stale = sorted(set(doc) - set(yml))
    stale = [k for k in stale if k not in ALLOWED_UNDOCUMENTED_EXTRAS]

    mismatched: list[tuple[str, str, str]] = []
    for key in sorted(set(yml) & set(doc)):
        if key in ALLOWED_TEXTUAL_DEFAULTS:
            continue
        if yml[key] != doc[key]:
            mismatched.append((key, yml[key], doc[key]))

    print(f"application.yml 引用的环境变量：{len(yml)} 个")
    print(f"deploy/README.md 表格里的环境变量：{len(doc)} 个")
    print()

    if undocumented:
        print(f"① 代码里有、文档表里没有：{len(undocumented)} 个（漏写）")
        if args.verbose:
            for key in undocumented:
                print(f"   - {key}（yml 默认 {yml[key]}）")
        else:
            print("   " + ", ".join(undocumented))
            print("   （加 --verbose 看默认值；确实不用写的加进 ALLOWED_UNDOCUMENTED 并写理由）")
        print()

    if stale:
        print(f"② 文档表里有、代码里没有：{len(stale)} 个（错文档，照它配是空转）")
        for key in stale:
            print(f"   - {key}")
        print()

    if mismatched:
        print(f"③ 两边都有但默认值不同：{len(mismatched)} 个（最严重）")
        for key, yml_value, doc_value in mismatched:
            print(f"   - {key}: yml = {yml_value} / 文档 = {doc_value}")
        print()

    blocking = len(stale) + len(mismatched)
    if blocking:
        print(f"不一致 {blocking} 处（第 ②③ 类），退出码 1")
        return 1

    print("第 ②③ 类差异 0 处；第 ① 类差异待豁免表或补文档。")
    print("豁免表当前条目：" + (", ".join(sorted(ALLOWED_UNDOCUMENTED)) or "（空）"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
