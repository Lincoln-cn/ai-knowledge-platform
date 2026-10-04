"""编排画布「节点类型」一致性闸门（issue #20 / #21）。

issue #20 是 `e53b99b`（枚举统一为 SCREAMING_SNAKE_CASE）之后画布**整页不可用**：
契约里的节点类型改成了 `START_NODE`，而画布侧的注册名还是 `start-node` ⇒
LogicFlow 找不到节点模型，一个节点都渲染不出来（控制台一条
「找不到START_NODE对应的节点，请确认是否已注册此类型节点」）。
它在 main 上活了 9 天，因为**没有一条闸门会去看这件事** ——
真栈那五条闸门全在知识库域，画布只有 mock 在守，而 mock 的图是前端夹具自带的。

★ 为什么这条做成**静态**闸门，而不是真栈浏览器闸门：
  `assistant` 与 `workflow` 两个后端模块目前只有 `package-info.java`（没有任何端点，
  `GET /api/v1/assistants` 就是 404）⇒ 真栈上**没有编排应用可挂图**，
  那条路线今天根本跑不起来（见 #21）。而 #20 这一类回归是纯静态可判的：
  它坏在"注册的类型名与契约取值不是同一个字符串"，与后端在不在无关。

核三件事：

  ① 契约 `ImplementedNodeType`（8 种）里的每一种，都**恰好有一个**画布节点模块
     用**同一个取值**注册自己
  ② 画布上不许再出现契约取值之外的注册名（即 `start-node` 那类 kebab 残留）
  ③ 每个注册类型都能解析到一个图标文件
     —— `nodeIcon()` 是按机械规则推文件名的，取不到就是**静默没有图标**（又一例"不报错的降级"）

★ 判据来源：设计册 14 号 §2.3（`start-node` → `START_NODE` 是**取值**要改）
  与 §三（**文件名 / 目录名 / 组件名不动**）。两套词汇只在 `icons/utils.ts` 相遇。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONTRACT = ROOT / "frontend/src/api/workflow.ts"
NODES_DIR = ROOT / "frontend/src/pages/workflow/components/nodes"
ICONS_DIR = ROOT / "frontend/src/pages/workflow/components/icons"

ok: list[tuple[bool, str]] = []


def check(cond: bool, msg: str) -> None:
    ok.append((cond, msg))
    print(("  ✓ " if cond else "  ✗ ") + msg)


def union_members(text: str, type_name: str) -> list[str]:
    """取 `export type X = 'A' | 'B' ...` 里的成员。"""
    m = re.search(rf"export type {type_name} =(.*?)(?:\n\n|\nexport )", text, re.S)
    if not m:
        raise SystemExit(f"★ 解析不出契约里的 {type_name} —— 闸门本身失效了，先修它")
    return re.findall(r"'([^']+)'", m.group(1))


def registered_types() -> dict[str, str]:
    """画布节点模块注册的类型：{目录名: 注册的 type}"""
    found: dict[str, str] = {}
    for path in sorted(NODES_DIR.glob("*/index.ts")):
        text = path.read_text(encoding="utf-8")
        m = re.search(r"export default\s*\{(.*?)\}", text, re.S)
        if not m:
            continue
        t = re.search(r"type:\s*'([^']+)'", m.group(1))
        if t:
            found[path.parent.name] = t.group(1)
    return found


def main() -> int:
    contract_text = CONTRACT.read_text(encoding="utf-8")
    implemented = union_members(contract_text, "ImplementedNodeType")
    planned = union_members(contract_text, "PlannedNodeType")
    all_types = set(implemented) | set(planned)
    registered = registered_types()

    print(
        f"契约：已实现 {len(implemented)} 种 · 规划中 {len(planned)} 种"
        f" · 画布注册了 {len(registered)} 个模块\n"
    )

    print("== 1. 每个「已实现」的契约类型都有画布模块，且注册名 == 契约取值 ==")
    missing = [t for t in implemented if t not in registered.values()]
    check(not missing, f"契约的 {len(implemented)} 种已实现类型全部有模块（缺：{missing or '无'}）")

    extra = {d: t for d, t in registered.items() if t not in all_types}
    for directory, registered_type in sorted(extra.items()):
        print(f"     nodes/{directory}/ 注册的是 {registered_type!r}，不在契约取值里")
    check(
        not extra,
        "画布上没有契约取值之外的注册名"
        + ("（★ 这正是 issue #20 的形状：注册名与小写连字符的目录名混为一谈）" if extra else ""),
    )

    print("\n== 2. 注册名必须是取值范围（SCREAMING_SNAKE），不是目录名形态 ==")
    kebab = {d: t for d, t in registered.items() if re.fullmatch(r"[a-z0-9-]+", t)}
    check(not kebab, f"没有 kebab 形态的注册名（实测 {len(kebab)} 个：{kebab or '无'}）")

    print("\n== 3. 每个注册类型都能解析到图标文件 ==")
    # nodeIcon() 的规则：type.toLowerCase().replace(/_/g, '-') + '-icon.vue'
    icon_files = {p.stem for p in ICONS_DIR.glob("*.vue")}
    unresolved = [
        f"{d}→{t}（找 {t.lower().replace('_', '-')}-icon.vue）"
        for d, t in sorted(registered.items())
        if f"{t.lower().replace('_', '-')}-icon" not in icon_files
    ]
    check(
        not unresolved,
        f"{len(registered)} 个节点类型都能解析到图标（取不到就是静默没有图标）"
        + (f"（{unresolved}）" if unresolved else ""),
    )

    print("\n== 4. 已实现类型的集合与模块集合互为一一对应 ==")
    check(
        sorted(registered.values()) == sorted(implemented),
        "注册名集合 == 契约已实现类型集合"
        + (f"\n     仅契约有：{sorted(set(implemented) - set(registered.values()))}"
           f"\n     仅画布有：{sorted(set(registered.values()) - set(implemented))}"
           if sorted(registered.values()) != sorted(implemented) else ""),
    )

    bad = [m for c, m in ok if not c]
    print("=" * 60)
    print(f"通过 {len(ok) - len(bad)}/{len(ok)}")
    for m in bad:
        print("  - " + m.splitlines()[0])
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
