#!/usr/bin/env python
"""配置面字段闸门 —— 契约里的检索参数，两个配置面板都必须能配（`#79` 第三格的字段层）。

## 为什么要有这道闸门

`RetrievalParams` 的三项（`queryCount` / `decomposeEnabled` / `maxSubQuestions`）在后端、
在契约、在 `RetrievalParamsView` 里都存在了整整一周，而**两个配置面板一个都没做**。
用户只能吃平台默认值（3 / true / 3）—— 想省一次检索调用只能去改数据库。

那段时间所有闸门都是绿的：`check_pending_endpoints.py` 只看"端点在不在用"，
`check_mock_endpoints.py` 只看"mock 响应对不对"。**"字段发没发出去"没有人管。**

## 判据

1. 从 `frontend/src/api/retrieval.ts` 的 `interface RetrievalParams` 里**现读**字段名
   （不写死清单 —— 写死清单在契约加字段那天就会过期）。
2. 每个字段都必须在每个**配置面文件**里出现（`TARGETS`）。
3. 例外要显式登记在 `EXEMPT` 里，并写清理由；★ 例外集合里出现"契约里已经不存在"的字段也报红
   （与 `GRANDFATHERED` 清单必须随清理收缩同一条纪律）。
4. `TARGETS` 里任一文件不存在 ⇒ 红（文件改名了却没人管，闸门会静默变成永远绿）。

## 用法

    python frontend/check_config_surfaces.py            # 判（退出码 0/1）
    python frontend/check_config_surfaces.py --self-check  # 自检（离线，5 条）
"""

from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

CONTRACT = "frontend/src/api/retrieval.ts"
INTERFACE = "RetrievalParams"

#: 该能配检索参数的两个面板（少一个就是"这一处用户配不了"）
TARGETS = [
    "frontend/src/pages/assistants/components/AssistantConfigDrawer.vue",
    "frontend/src/pages/knowledge/components/SettingsPanel.vue",
]

#: 契约里**不属于**配置面、因此不必出现在上面两个文件里的字段，附理由。
EXEMPT: dict[str, str] = {
    # 今天是空的：`RetrievalParams` 的每一项两个面板都该能配。
    # 加例外请写清理由，并记住它是"需要人定期复查"的债 —— 契约里删掉字段时这里也要跟着删（否则闸门自己报红）。
}

FIELD_RE = re.compile(r"^\s{2}([a-zA-Z][A-Za-z0-9]*)\??\s*:", re.MULTILINE)


def strip_comments(text: str, vue: bool) -> str:
    """去掉注释 —— ★ **注释里写了字段名不等于用户能配它**（`#265` 的同一课）。

    替换成等长空白以保住行/列位置，报错行号仍可直接用。
    """
    out = re.sub(r"/\*.*?\*/", lambda m: " " * len(m.group(0)), text, flags=re.DOTALL)
    out = re.sub(r"//[^\n]*", lambda m: " " * len(m.group(0)), out)
    if vue:
        out = re.sub(r"<!--.*?-->", lambda m: " " * len(m.group(0)), out, flags=re.DOTALL)
    return out


def contract_fields(path: Path) -> list[str]:
    """从契约里现读 `interface RetrievalParams { … }` 的字段名。"""
    text = path.read_text(encoding="utf-8")
    start = text.find(f"export interface {INTERFACE} ")
    if start < 0:
        raise SystemExit(f"✗ 契约里找不到 interface {INTERFACE}（{path}）")
    body_start = text.index("{", start)
    depth = 0
    end = body_start
    for index in range(body_start, len(text)):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                end = index
                break
    body = text[body_start + 1 : end]
    # 去掉块注释与行注释，避免把注释里的 `xx:` 当字段
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.DOTALL)
    body = re.sub(r"//[^\n]*", "", body)
    fields = FIELD_RE.findall(body)
    # 去重但保序
    seen: list[str] = []
    for name in fields:
        if name not in seen:
            seen.append(name)
    return seen


def check(root: Path, contract: Path, targets: list[str], exempt: dict[str, str]) -> list[str]:
    problems: list[str] = []
    fields = contract_fields(contract)
    if not fields:
        problems.append(f"契约 {contract} 的 interface {INTERFACE} 里一个字段都没解析出来")
        return problems

    for name in exempt:
        if name not in fields:
            problems.append(
                f"例外清单里的 {name!r} 已经不在契约里了 —— 请从 EXEMPT 删掉"
                "（清单必须随清理收缩，否则它会长成想象中的豁免）"
            )

    required = [name for name in fields if name not in exempt]

    for relative in targets:
        path = root / relative
        if not path.exists():
            problems.append(f"配置面文件不存在：{relative}（改名了请同步 TARGETS）")
            continue
        text = strip_comments(path.read_text(encoding="utf-8"), vue=path.suffix == ".vue")
        missing = [name for name in required if not re.search(rf"\b{re.escape(name)}\b", text)]
        if missing:
            problems.append(
                "{}：缺 {}".format(relative, "、".join(missing))
                + " —— 契约里有、后端收，但这张面板发不出去 ⇒ 用户只能吃平台默认值"
            )
    return problems


def self_check() -> int:
    """离线自检：闸门能红，且红的是**被测对象**而不是脚本自己。"""
    failures: list[str] = []

    def case(name: str, contract_body: str, page_body: str, expect_ok: bool) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            api = root / "api"
            api.mkdir()
            (api / "retrieval.ts").write_text(
                "export interface RetrievalParams {\n" + contract_body + "}\n", encoding="utf-8"
            )
            page = root / "page.vue"
            page.write_text(page_body, encoding="utf-8")
            problems = check(root, api / "retrieval.ts", ["page.vue"], {})
            ok = not problems
            if ok != expect_ok:
                failures.append(
                    f"自检用例 {name!r} 期望 {'通过' if expect_ok else '报红'}，实际 "
                    + ("通过" if ok else "报红：" + problems[0])
                )

    contract_two = "  mode: string\n  topN: number\n"
    case("两字段都在 ⇒ 通过", contract_two, "form.mode = 'x'\nform.topN = 5\n", True)
    case("少一个字段 ⇒ 报红", contract_two, "form.mode = 'x'\n", False)
    case(
        "注释里的字段名不算（不许拿注释冒充入口）",
        contract_two,
        "// form.topN 还没做\nform.mode = 'x'\n",
        False,
    )
    case("契约新增字段立刻要求页面跟上 ⇒ 报红", contract_two + "  qc: number\n", "form.mode='x'\nform.topN=5\n", False)

    # 例外清单过期的用例单独跑（它不经过页面）
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        api = root / "api"
        api.mkdir()
        (api / "retrieval.ts").write_text(
            "export interface RetrievalParams {\n  mode: string\n}\n", encoding="utf-8"
        )
        (root / "page.vue").write_text("form.mode = 'x'\n", encoding="utf-8")
        problems = check(root, api / "retrieval.ts", ["page.vue"], {"ghost": "已经不存在了"})
        if not problems:
            failures.append("自检用例 '例外清单里有过期字段 ⇒ 报红' 期望报红，实际通过")

    if failures:
        for line in failures:
            print(f"✗ {line}")
        return 1
    print("✓ 自检通过（5 条：全在 / 少一个 / 注释不算 / 契约新增 / 例外过期）")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="配置面字段闸门")
    parser.add_argument("--self-check", action="store_true", help="离线自检（不读真实仓库）")
    args = parser.parse_args()
    if args.self_check:
        return self_check()

    problems = check(ROOT, ROOT / CONTRACT, TARGETS, EXEMPT)
    if problems:
        print("✗ 配置面缺字段：")
        for line in problems:
            print(f"  {line}")
        return 1
    fields = contract_fields(ROOT / CONTRACT)
    required = [name for name in fields if name not in EXEMPT]
    print(
        f"✓ 配置面完整：契约 {len(fields)} 个字段（例外 {len(EXEMPT)} 个），"
        f"要求 {len(required)} 个字段各出现在 {len(TARGETS)} 张面板里"
    )
    for name in required:
        print(f"  · {name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
