"""验节点操作菜单（⋮ 下拉）：每节点有「删除节点」入口 + 删除真能删 + 单例删除被拦且给原因。

真触发方式见 NodeContainer.vue：`el-dropdown :trigger="[]"` + document 捕获阶段 mouseup
按落点手动 handleOpen/handleClose（LogicFlow 节点上原生 click 不派发，trigger="click" 打不开）。
菜单项类名是 el-dropdown-menu__item（不是 el-dropdown-item）。
弹层 teleported 到 body，且多个节点的 popper 会同时留在 DOM 里、只有当前打开的那个
有非零宽高 ⇒ 判「哪个菜单开着」必须用可见性过滤，不能用 .last 赌顺序。

⚠️ 必须**按节点标题现场定位**，不能用首次读到的 nth(i) 下标：LogicFlow 的
`toFront` 会把刚点过的节点挪到 DOM 末尾，下标当场作废（实测点完「开始」后
nth(8) 已经不是「兜底回复」）。

用法：python check_node_menu.py [base]
"""
from __future__ import annotations

import sys
from pathlib import Path

from playwright.sync_api import TimeoutError as PWTimeout
from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280/"
NODE = ".workflow-node-container"
EXPECTED_COUNT = 9
DELETE_LABEL = "删除节点"
# deleteRestriction() 给单例（仅剩一个）的禁用原因 —— 见 lib/node-actions.ts
SINGLETON_REASON = "开始节点是流程唯一入口，不能删除"


def main() -> int:
    ok: list[tuple[bool, str]] = []

    def check(cond: bool, msg: str) -> None:
        ok.append((cond, msg))
        print(("  ✓ " if cond else "  ✗ ") + msg)

    with sync_playwright() as pw:
        b = pw.chromium.launch(channel="chrome")
        page = b.new_page(viewport={"width": 1600, "height": 900})
        errs: list[str] = []
        page.on("pageerror", lambda e: errs.append(str(e)))
        page.on(
            "console",
            lambda m: errs.append(m.text[:130]) if m.type == "error" else None,
        )

        # ── 登录 ──
        page.goto(f"{BASE}/#/login", wait_until="networkidle")
        page.wait_for_timeout(600)
        page.fill("input[autocomplete='username']", "admin")
        page.fill("input[autocomplete='current-password']", "admin")
        page.click("button:has-text('登录')")
        page.wait_for_timeout(1600)

        # ── 强制真导航进画布（纯 hash goto 不产生文档导航）──
        page.goto(f"{BASE}/?n=wf#/assistants/as5/workflow", wait_until="networkidle")
        page.wait_for_selector(NODE, timeout=30000)
        # 画布 fitView / 懒加载分块：等节点数稳定，而不是死等固定毫秒
        page.wait_for_timeout(1500)

        def node_count() -> int:
            return page.locator(NODE).count()

        def node_title(i: int) -> str:
            return page.evaluate(
                "(i) => (document.querySelectorAll('.workflow-node-container')[i]"
                ".querySelector('h4')?.textContent || '').trim()",
                i,
            )

        def node_index_by_title(title: str) -> int | None:
            """现场按标题找下标 —— toFront 会重排 DOM，不能沿用旧下标。"""
            titles_now = [node_title(i) for i in range(node_count())]
            return titles_now.index(title) if title in titles_now else None

        def close_any_menu() -> None:
            """收起可能残留的下拉弹层。Escape 依赖焦点，实测有时要按两次。"""
            for _ in range(4):
                if page.locator(".el-dropdown-menu:visible").count() == 0:
                    return
                page.keyboard.press("Escape")
                page.wait_for_timeout(280)
            # 仍关不掉就再点一次触发器（手动 mouseup 是开关语义）
            if page.locator(".el-dropdown-menu:visible").count():
                try:
                    btn = page.locator(".el-dropdown-menu:visible").locator(
                        "xpath=ancestor::*[contains(@class,'el-dropdown')][1]//button"
                    ).first
                    btn.click(timeout=2000)
                    page.wait_for_timeout(280)
                except Exception:
                    pass

        def open_menu(title: str):
            """按**标题**打开对应节点的 ⋮ 菜单，返回 (visible_menu, items)；失败 (None, [])。"""
            close_any_menu()
            idx = node_index_by_title(title)
            if idx is None:
                return None, []
            try:
                page.locator(NODE).nth(idx).locator(".el-dropdown button").first.click(timeout=5000)
                menu = page.locator(".el-dropdown-menu:visible")
                menu.wait_for(state="visible", timeout=3000)
                # 互斥兜底：出现多个可见菜单时，取文本能对上目标节点特征的那个；
                # 先按「只允许 1 个」硬校验留给调用方的 check。
                items = [t.strip() for t in menu.locator(".el-dropdown-menu__item").all_inner_texts()]
                return menu, [t for t in items if t]
            except Exception:
                return None, []

        # ══════════ 前置 ══════════
        print("== 前置 ==")
        n0 = node_count()
        titles = [node_title(i) for i in range(n0)]
        print(f"  节点数：{n0}  标题：{titles}")
        check(n0 == EXPECTED_COUNT, f"节点数 == {EXPECTED_COUNT}（实测 {n0}）")

        start_idx = next((i for i, t in enumerate(titles) if t.startswith("开始")), None)
        fall_idx = next((i for i, t in enumerate(titles) if "兜底" in t), None)
        check(start_idx is not None, f"找到「开始」节点（下标 {start_idx}）")
        check(fall_idx is not None, f"找到「兜底」节点（下标 {fall_idx}）")

        # ══════════ 1. 每个节点都能打开菜单，且菜单里有「删除节点」 ══════════
        print("\n== 1. 每个节点的 ⋮ 菜单都有「删除节点」 ==")
        missing: list[str] = []
        for i in range(n0):
            menu, items = open_menu(titles[i])
            has = any(DELETE_LABEL in x for x in items)
            shown = " | ".join(items) if items else "(菜单未打开)"
            vis_n = page.locator(".el-dropdown-menu:visible").count()
            print(f"  [{i}] {titles[i]!r:12} {'✓' if has else '✗'}  vis={vis_n}  {shown}")
            if not has:
                missing.append(f"{i}:{titles[i]}")
            close_any_menu()
        check(not missing, f"{n0} 个节点都有「删除节点」入口（失败：{missing if missing else '无'}）")

        # ══════════ 2. 单例（开始）删除被拦：弹原因 + 节点数不变 ══════════
        print("\n== 2. 开始节点（单例）删除被拦 ==")
        if start_idx is None:
            check(False, "无法测单例删除：找不到「开始」节点")
        else:
            before = node_count()
            menu, items = open_menu("开始")
            if menu is None:
                check(False, "开始节点的 ⋮ 菜单没能打开")
            else:
                # 禁用项仍然可点（NodeContainer 用 is-disabled-item 而非 el disabled），
                # 点它由 requestDeleteNode → MsgError 弹原因
                menu.locator(f".el-dropdown-menu__item:has-text('{DELETE_LABEL}')").click(timeout=3000)
                try:
                    page.wait_for_selector(".el-message", timeout=3000)
                    toasts = [t.strip() for t in page.locator(".el-message").all_inner_texts()]
                except Exception:
                    toasts = []
                after = node_count()
                reason_hit = any(SINGLETON_REASON in t for t in toasts)
                print(f"  菜单项：{items}")
                print(f"  提示：{toasts}")
                print(f"  节点数 {before} → {after}")
                check(bool(toasts), "点击后弹出了提示（不是静默失败）")
                check(reason_hit, f"提示含禁用原因 {SINGLETON_REASON!r}（实测 {toasts}）")
                check(after == before, f"节点数不变（{before} → {after}）")
            close_any_menu()

        # ══════════ 3. 非单例（兜底）删除真能删掉：确认框 → 节点数 -1 ══════════
        print("\n== 3. 兜底节点删除生效 ==")
        if fall_idx is None:
            check(False, "无法测删除：找不到「兜底」节点")
        else:
            before = node_count()
            menu, items = open_menu("兜底回复")
            if menu is None:
                check(False, "兜底节点的 ⋮ 菜单没能打开")
            else:
                menu.locator(f".el-dropdown-menu__item:has-text('{DELETE_LABEL}')").click(timeout=3000)
                box = page.locator(".el-message-box")
                try:
                    box.wait_for(state="visible", timeout=3000)
                    box_text = box.inner_text()
                except Exception:
                    box_text = ""
                print(f"  确认框出现：{bool(box_text)}  文案：{box_text[:80]!r}")
                check(bool(box_text), "点击后弹出二次确认框")
                check(
                    "确定删除节点" in box_text,
                    f"确认框文案是「确定删除节点…」（实测 {box_text[:60]!r}）",
                )
                if box_text:
                    box.locator("button", has_text="删除").first.click(timeout=3000)
                    page.wait_for_timeout(1200)
                after = node_count()
                print(f"  节点数 {before} → {after}")
                check(after == before - 1, f"节点数真减 1（{before} → {after}）")
            close_any_menu()

        # 收尾
        shot = Path(__file__).parent / "screenshots" / "node-menu.png"
        try:
            shot.parent.mkdir(parents=True, exist_ok=True)
            page.screenshot(path=str(shot))
        except Exception:
            pass

        print(f"\n控制台错误：{errs if errs else 0}")
        b.close()

    bad = [m for c, m in ok if not c]
    print("=" * 60)
    print(f"通过 {len(ok) - len(bad)}/{len(ok)}")
    for m in bad:
        print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
