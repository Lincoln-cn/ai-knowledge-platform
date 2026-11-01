#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""★ 文档里写的 npm 脚本必须真的存在（闸门 · 本批新增）。

为什么要有这条：`docs/需求规格说明书/00-总览与复现指南.md` 的"环境与构建"表里写着
`npm run build:prod` —— `frontend/package.json` 里**没有**这个脚本。照着指南复现的人
第一条命令就红，而作者、闸门、CI 都不会响：文档不是代码，没有任何东西在跑它。

判据（窄，可金丝雀）：
  ① 被 git 跟踪的 `.md` 里出现的 `npm run <x>`，`x` 必须在 `frontend/package.json` 的 scripts 里；
  ② 读不到 `frontend/package.json` 即红（删掉文件等于删掉判据）；
  ③ 一份脚本都没扫到即红（解析写错 ≠ 文档干净）；
  ④ `--self-check` 用假数据跑 4 条用例。

范围声明：只认 `npm run`；`python xxx.py` 不在此列（大量历史文档引用临时脚本，
那会逼人写一堆假豁免 —— 一次只收一种形状）。
"""

from __future__ import annotations

import io
import json
import os
import re
import subprocess
import sys

USAGE = re.compile(r"npm run ([A-Za-z0-9:_-]+)")
PKG = os.path.join("frontend", "package.json")


def scripts_of(text):
    """从 package.json 文本里取 scripts 的键。"""
    data = json.loads(text)
    return sorted(data.get("scripts", {}).keys())


def used_in(text):
    return sorted(set(USAGE.findall(text)))


def check(script_names, docs):
    """docs: [(路径, 文本)] → 问题列表。"""
    problems = []
    known = set(script_names)
    total = 0
    for path, text in docs:
        for name in used_in(text):
            total += 1
            if name not in known:
                problems.append(
                    "✗ {}：写了 `npm run {}`，但 frontend/package.json 里没有这个脚本"
                    "（现有：{}）".format(path, name, ", ".join(script_names))
                )
    if total == 0:
        problems.append("✗ 一份文档里都没扫到 `npm run <x>` —— 要么文档改了写法，要么这个解析器坏了")
    return problems


def tracked_docs():
    out = [
        path
        for path in subprocess.run(
            ["git", "-c", "core.quotepath=false", "ls-files"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout.splitlines()
        if path.endswith(".md")
    ]
    docs = []
    for path in out:
        try:
            docs.append((path, io.open(path, encoding="utf-8").read()))
        except OSError:
            continue
    return docs


def self_check():
    cases = [
        ("全在", ["build", "dev"], [("a.md", "跑 `npm run build` 和 npm run dev")], 0),
        ("缺一个", ["build"], [("a.md", "跑 `npm run build:prod`")], 1),
        ("一份都没有", ["build"], [("a.md", "没有命令的文档")], 1),
        ("多份文档各自报", ["build"], [("a.md", "npm run x"), ("b.md", "npm run y")], 2),
    ]
    bad = 0
    for label, scripts, docs, expected in cases:
        got = len(check(scripts, docs))
        if got != expected:
            bad += 1
            print("✗ 自检『{}』：期望 {} 条问题，实际 {} 条".format(label, expected, got))
    print("✓ 自检通过（4 条用例）" if bad == 0 else "✗ 自检失败 {} 条".format(bad))
    return 0 if bad == 0 else 1


def main():
    if "--self-check" in sys.argv:
        return self_check()
    if not os.path.exists(PKG):
        print("✗ 找不到 {} —— 没有它这条判据就没有对照对象".format(PKG))
        return 1
    script_names = scripts_of(io.open(PKG, encoding="utf-8").read())
    docs = tracked_docs()
    problems = check(script_names, docs)
    if problems:
        print("\n".join(problems))
        print("\n处置：要么在 frontend/package.json 里加这个脚本，要么把文档改成已有的命令 —— "
              "别让复现指南第一条命令就红。")
        return 1
    print("✓ 文档里引用的 npm 脚本都存在（扫描 {} 份 md，脚本 {} 个）".format(len(docs), len(script_names)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
