"""权限点清单的双侧一致性闸门（静态，不需要浏览器）。

**它回答的问题**：前端那 20 个权限点常量、后端 `Permissions.ALL`、mock 里"角色 → 权限点"
三份清单说的是同一件事吗？

为什么需要它（`#220`）：权限点在本仓是**代码常量**（`08 §4.3.1`），而"各角色拿到哪些"
是**数据**（`iam_role_permission`，管理员在界面上勾）。两侧各写一份清单，于是有三种
不易察觉的坏法 —— 它们都**不会报错**，只会让写入口对谁都不可见：

1. 前端多/少一个常量 ⇒ 后端没有这颗点（或前端没有这颗点的勾选框）；
2. mock 的目录漏一颗 ⇒ 角色管理页少一个勾选框、`session.can(它)` 恒假 ⇒ 对应写入口**谁也不看见**；
3. 某个角色的 mock 集合与后端初始映射不一致 ⇒ mock 演示里的可做/不可做与真环境不同。

`verify_permissions.py` 只验"给定权限点，界面按它藏了按钮没有"；它**验不了**
"这颗权限点是否真的存在、是否有人持有"。本脚本补的就是那一格。

用法：
    python check_permission_catalog.py            # 从仓库根或 frontend/ 下跑都行
退出码：0 = 三份清单一致；1 = 有差异（逐条打印）。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
FRONTEND = HERE if (HERE / "src").is_dir() else HERE / "frontend"
REPO = FRONTEND.parent

PERM_TS = FRONTEND / "src" / "constants" / "permissions.ts"
MOCK_IDENTITY = FRONTEND / "src" / "mock" / "fixtures" / "identity.ts"
BACKEND_PERMISSIONS = (
    REPO / "backend" / "src" / "main" / "java" / "cn" / "lincoln" / "aiplatform"
    / "identity" / "domain" / "Permissions.java"
)

# mock 里的角色 code → 后端 `Permissions.of(...)` 的 key（同名，故意不在这里重命名）
ROLE_CODES = ("ADMIN", "KB_OPERATOR", "APP_CONFIG", "USER")

results: list[tuple[bool, str, str]] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    results.append((bool(ok), label, str(detail)))


def read(path: Path) -> str:
    if not path.is_file():
        print(f"找不到文件：{path}", file=sys.stderr)
        raise SystemExit(2)
    return path.read_text(encoding="utf-8")


# ── 后端：常量值 + ALL + 各角色初始映射 ────────────────────────────────


def parse_backend(text: str) -> tuple[dict[str, str], list[str], dict[str, list[str]]]:
    consts = dict(re.findall(r'public static final String (\w+) = "([^"]+)";', text))

    def list_of(name: str) -> list[str]:
        m = re.search(
            r"List<String>\s+" + re.escape(name) + r"\s*=\s*List\.of\((.*?)\);", text, re.S
        )
        if not m:
            return []
        body = m.group(1)
        # 去掉注释行，避免把注释里的单词当成常量名
        body = re.sub(r"//[^\n]*", "", body)
        body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
        return [tok for tok in re.findall(r"\b([A-Z][A-Z0-9_]+)\b", body)]

    all_names = list_of("ALL")
    roles: dict[str, list[str]] = {}
    for code in ROLE_CODES:
        # ★ ADMIN 在 Java 里没有独立的 List 常量，它映射到 `ALL`（`BY_ROLE_CODE` 的 "ADMIN" → ALL）。
        #   照着 DOWN 找 `List<String> ADMIN = …` 会得到空表，于是 ADMIN 那一条永远报"mock 多出全部"
        #   —— 这正是一个"检查器自己写错了"的典型：它看起来在报错，报的却是自己的错。
        names = all_names if code == "ADMIN" else list_of(code)
        roles[code] = [consts.get(n, f"<未定义:{n}>") for n in names]
    return consts, [consts.get(n, f"<未定义:{n}>") for n in all_names], roles


# ── 前端：PERM 常量 + mock 目录 + 角色集合 ────────────────────────────


def parse_frontend_perm(text: str) -> dict[str, str]:
    m = re.search(r"export const PERM = \{(.*?)\} as const", text, re.S)
    if not m:
        return {}
    body = re.sub(r"//[^\n]*", "", m.group(1))
    body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
    return dict(re.findall(r"(\w+)\s*:\s*'([^']+)'", body))


def parse_mock(text: str) -> tuple[list[str], dict[str, list[str] | str]]:
    """返回（目录里的权限点，角色 → 权限点列表 或 字符串 'all'）。"""
    perm_block = re.search(r"export const permissions[^=]*=\s*\[(.*?)\n\]", text, re.S)
    catalog = re.findall(r"code:\s*PERM\.(\w+)", perm_block.group(1)) if perm_block else []

    roles: dict[str, list[str] | str] = {}
    for code in ROLE_CODES:
        m = re.search(r"code: '" + code + r"',", text)
        if not m:
            continue
        tail = text[m.end():]
        pm = re.search(r"permissions:\s*(all|\[)(.*?)(?:,?\s*\n\s*\])", tail, re.S)
        if not pm:
            continue
        if pm.group(1) == "all":
            roles[code] = "all"
        else:
            roles[code] = re.findall(r"PERM\.(\w+)", pm.group(2))
    return catalog, roles


def main() -> int:
    backend_consts, backend_all, backend_roles = parse_backend(read(BACKEND_PERMISSIONS))
    perm_map = parse_frontend_perm(read(PERM_TS))
    catalog, mock_roles = parse_mock(read(MOCK_IDENTITY))

    # ① 两侧权限点的**值**必须完全一致（名称可以不同，值不许）
    front_values = set(perm_map.values())
    back_values = set(backend_all)
    missing_in_front = sorted(back_values - front_values)
    extra_in_front = sorted(front_values - back_values)
    check(
        not missing_in_front and not extra_in_front,
        "前后端权限点值集合一致",
        f"前端缺 {missing_in_front} · 前端多 {extra_in_front}",
    )

    # ② 前端常量里每一个都要在 mock 目录里出现（否则角色管理页没有它的勾选框，
    #    而 `session.can(它)` 对**所有**角色恒假 ⇒ 被它守着的写入口谁也不可见）
    catalog_values = {perm_map[n] for n in catalog if n in perm_map}
    orphan = sorted(front_values - catalog_values)
    check(
        not orphan,
        "mock 权限点目录覆盖全部常量",
        f"目录里没有：{orphan}（这些点没有任何角色能持有）",
    )

    unknown_const = sorted({n for n in catalog if n not in perm_map})
    check(not unknown_const, "mock 目录里没有笔误常量", f"未定义：{unknown_const}")

    # ③ 各角色的 mock 集合与后端**初始映射**逐点相等（ADMIN 允许写 `all`，等价于全量）
    for code in ROLE_CODES:
        if code not in backend_roles:
            check(False, f"后端缺少角色 {code} 的初始映射", "")
            continue
        want = set(backend_roles[code])
        got_raw = mock_roles.get(code)
        if got_raw is None:
            check(False, f"{code} 在 mock 里缺少角色定义", "")
            continue
        got = front_values if got_raw == "all" else {perm_map[n] for n in got_raw if n in perm_map}
        check(
            got == want,
            f"{code} 的角色集合与后端初始映射一致",
            f"mock 多 {sorted(got - want)} · mock 少 {sorted(want - got)}",
        )

    # ④ 前端任何地方引用的 `PERM.x` 都必须是已定义的常量（防笔误产出一颗"永远为假"的门禁）
    used: dict[str, set[str]] = {}
    for path in (FRONTEND / "src").rglob("*"):
        if path.suffix not in {".ts", ".vue"}:
            continue
        for name in re.findall(r"PERM\.(\w+)", path.read_text(encoding="utf-8", errors="ignore")):
            used.setdefault(name, set()).add(path.name)
    undefined = sorted(n for n in used if n not in perm_map)
    check(
        not undefined,
        "src 里引用的 PERM.* 都有定义",
        f"未定义：{[(n, sorted(used[n])) for n in undefined]}",
    )

    passed = sum(1 for ok, _, _ in results if ok)
    for ok, label, detail in results:
        mark = "OK" if ok else "NG"
        print(f"  [{mark}] {label}" + (f" —— {detail}" if detail and not ok else ""))
    print("=" * 88)
    print(f"权限点清单断言 {passed}/{len(results)} 通过")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
