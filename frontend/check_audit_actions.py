#!/usr/bin/env python
"""审计动作码：后端枚举 / 前端契约 / 审计页筛选项，三处必须逐字对齐。

## 为什么要有这条闸门

动作码是三处各写一份的同一种知识：

0. 后端每个 `audit.record(动作, "资源类型", ...)` 调用点里的**资源类型字面量**，与审计页的 `RESOURCES`；
1. 后端 `AuditAction` 枚举（真正写进 `pf_audit_log.action` 的值）；
2. 前端契约 `AuditActionCode`（TS 联合类型）；
3. 审计页的筛选项列表 `ACTIONS`（人看到的标签）。

任一处漏一个，症状都很轻、都不报错：

- 契约漏 ⇒ 类型上够用（页面用 `string` 兜着），但下一次有人写 switch 少一支；
- 筛选列表漏 ⇒ 那一类记录**筛不出来**，只能在全部记录里翻；页面还会直接显示原始 code（`audit.purge`）
  而不是中文标签 —— 「这一行看不懂」是最少被反馈的那类缺陷。

这正是 `#40` 的形状：同一状态多处各写一份，漂移时没有任何一处会响。

## 判据

1. 三处集合**逐个相等**（多一个没登记要红，少一个也要红）；
2. 每一处至少有一个元素（否则"都为空"会让相等成立 —— 空集假绿）；
3. 后端枚举里没有重复值。

跑法：`python frontend/check_audit_actions.py`（退 0 = 一致）；`--self-check` 跑离线用例。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ENUM = ROOT.parent / "backend/src/main/java/cn/lincoln/aiplatform/platform/domain/AuditAction.java"
CONTRACT = ROOT / "src/api/audit.ts"
PAGE = ROOT / "src/pages/audit/Index.vue"

CODE_RE = re.compile(r'"([a-z][a-z0-9]*\.[a-z][a-z0-9-]*)"')

# ★ 资源类型：`audit.record(AuditAction.X, "DOCUMENT", …)` 的第二个参数（可能跨行写）。
#   它是魔法字符串，散在十来个调用点上 —— 最容易漂、又最不会响的那类知识。
RESOURCE_USE_RE = re.compile(r'record\(\s*[A-Za-z_.]+\s*,\s*"([A-Z_]+)"', re.S)


def enum_codes(text: str) -> list[str]:
    """后端枚举里所有 `"资源.动作"` 字面量。"""
    body = text.split("public enum AuditAction", 1)[-1]
    return CODE_RE.findall(body)


def contract_codes(text: str) -> list[str]:
    """前端 `AuditActionCode` 联合类型的成员。"""
    match = re.search(r"export type AuditActionCode\s*=(.*?)\n\n", text, re.S)
    if not match:
        return []
    return re.findall(r"'([a-z][a-z0-9]*\.[a-z][a-z0-9-]*)'", match.group(1))


def page_codes(text: str) -> list[str]:
    """审计页 `const ACTIONS = [...]` 里的 value。"""
    match = re.search(r"const ACTIONS = \[(.*?)\n\]", text, re.S)
    if not match:
        return []
    return re.findall(r"value:\s*'([a-z][a-z0-9]*\.[a-z][a-z0-9-]*)'", match.group(1))


def used_resources(source_root):
    """后端所有 `audit.record(...)` 调用点用到的资源类型（去重排序）。"""
    seen = set()
    for path in sorted(source_root.rglob("*.java")):
        seen.update(RESOURCE_USE_RE.findall(path.read_text(encoding="utf-8")))
    return sorted(seen)


def page_resources(text):
    """审计页 `const RESOURCES = [...]` 里的 value（逐行解析，避开正则里的方括号转义）。"""
    out = []
    inside = False
    for line in text.splitlines():
        if 'const RESOURCES = [' in line:
            inside = True
            continue
        if inside and line.strip() == ']':
            break
        if inside:
            match = re.search("value: '([A-Z_]+)'", line)
            if match:
                out.append(match.group(1))
    return out


def resource_problems(used, page):
    issues = []
    if not used:
        issues.append("后端一条 audit.record 都没解析到 —— 资源类型这条判据此刻是瞎的")
    if not page:
        issues.append("审计页 RESOURCES 一条都没解析到 —— 路径变了或格式改了")
    if issues:
        return issues
    for code in sorted(set(used) - set(page)):
        issues.append(f"审计页 RESOURCES 缺 '{code}'（后端会写这个资源类型 ⇒ 按它筛不出来，且显示原始英文）")
    for code in sorted(set(page) - set(used)):
        issues.append(f"审计页 RESOURCES 多 '{code}'（后端不会写这个资源类型 ⇒ 选它永远是空列表）")
    return issues


def problems(backend: list[str], contract: list[str], page: list[str]) -> list[str]:
    """三处对比。空集合单独报 —— 否则"都没解析到"会以"全部一致"收场。"""
    issues: list[str] = []
    for name, codes in (("后端 AuditAction", backend), ("前端 AuditActionCode", contract), ("审计页 ACTIONS", page)):
        if not codes:
            issues.append(f"{name} 一条都没解析到 —— 路径变了或格式改了，这条闸门此刻是瞎的")
    if len(backend) != len(set(backend)):
        issues.append("后端 AuditAction 里有重复的动作码（写进库之后就是两种历史）")
    if issues:
        return issues

    b, c, p = set(backend), set(contract), set(page)
    for code in sorted(b - c):
        issues.append(f"契约 AuditActionCode 缺 '{code}'（后端已会写这个值 ⇒ 前端类型不认识它）")
    for code in sorted(c - b):
        issues.append(f"契约 AuditActionCode 多 '{code}'（后端不会写这个值 ⇒ 前端在等一个永远不来的记录）")
    for code in sorted(b - p):
        issues.append(f"审计页筛选项缺 '{code}'（这类记录筛不出来，且页面会直接显示原始 code）")
    for code in sorted(p - b):
        issues.append(f"审计页筛选项多 '{code}'（后端不会写这个值 ⇒ 选它永远是空列表）")
    return issues


def self_check() -> int:
    """离线用例：不读文件，只喂字符串。"""
    enum = 'public enum AuditAction {\n  A("document.delete"),\n  B("audit.purge");\n}'
    contract = "export type AuditActionCode =\n  | 'document.delete'\n  | 'audit.purge'\n\nexport interface X {}"
    page = "const ACTIONS = [\n  { value: 'document.delete', label: '删文档' },\n  { value: 'audit.purge', label: '清理' },\n]"
    cases = []

    b, c, p = enum_codes(enum), contract_codes(contract), page_codes(page)
    cases.append(("三处一致 ⇒ 无问题", problems(b, c, p) == [], problems(b, c, p)))
    cases.append(("解析到了元素", (len(b), len(c), len(p)) == (2, 2, 2), (b, c, p)))

    miss = problems(enum_codes(enum), contract_codes("export type AuditActionCode =\n  | 'document.delete'\n\nx"), page_codes(page))
    cases.append(("契约少一个 ⇒ 报", any("缺 'audit.purge'" in s for s in miss), miss))

    extra = problems(enum_codes(enum), contract_codes(contract), page_codes(page + "\n"))
    cases.append(("页面多/少一个也会报（这里页面同款 ⇒ 不报）", extra == [], extra))

    bad_page = "const ACTIONS = [\n  { value: 'document.delete', label: '删文档' },\n]"
    miss_page = problems(enum_codes(enum), contract_codes(contract), page_codes(bad_page))
    cases.append(("页面筛选项少一个 ⇒ 报", any("筛选项缺 'audit.purge'" in s for s in miss_page), miss_page))

    res_used = sorted(
        RESOURCE_USE_RE.findall('audit.record(AuditAction.X, "DOCUMENT", id, d)')
        + RESOURCE_USE_RE.findall('audit.record(AuditAction.Y, "ROLE", id, null, d)'))
    cases.append(("资源类型能解析", res_used == ["DOCUMENT", "ROLE"], res_used))
    res_page = page_resources(
        "const RESOURCES = [" + chr(10)
        + "  { value: 'DOCUMENT', label: '文档' }," + chr(10)
        + "  { value: 'ROLE', label: '角色' }," + chr(10)
        + "]")
    cases.append(
        ("资源类型两端一致 ⇒ 无问题", resource_problems(res_used, res_page) == [], resource_problems(res_used, res_page)))
    miss_res = resource_problems(
        res_used, page_resources("const RESOURCES = [" + chr(10) + "  { value: 'DOCUMENT', label: '文档' }," + chr(10) + "]"))
    cases.append(("页面缺一个资源类型 ⇒ 报", any("缺 'ROLE'" in i for i in miss_res), miss_res))

    blind = problems([], [], [])
    cases.append(("一条都解析不到 ⇒ 报（不许空集假绿）", len(blind) == 3, blind))

    failed = 0
    for title, ok, detail in cases:
        print(("✓ " if ok else "✗ ") + title)
        if not ok:
            print(f"    实际：{detail}")
            failed += 1
    print(f"\n自检：{len(cases) - failed}/{len(cases)} 通过")
    return 1 if failed else 0


def main() -> int:
    if "--self-check" in sys.argv:
        return self_check()

    backend = enum_codes(ENUM.read_text(encoding="utf-8"))
    contract = contract_codes(CONTRACT.read_text(encoding="utf-8"))
    page = page_codes(PAGE.read_text(encoding="utf-8"))
    issues = problems(backend, contract, page)
    issues += resource_problems(used_resources(ENUM.parents[3]), page_resources(PAGE.read_text(encoding="utf-8")))

    if issues:
        print("✗ 审计动作码三处对不上：")
        for issue in issues:
            print(f"  - {issue}")
        print("\n三处 = 后端 AuditAction 枚举 / src/api/audit.ts 的 AuditActionCode / pages/audit 的 ACTIONS。")
        return 1
    print(
        f"✓ 审计动作码三处一致：{len(backend)} 个；"
        f"资源类型两端一致：{len(used_resources(ENUM.parents[3]))} 种")
    return 0


if __name__ == "__main__":
    sys.exit(main())
