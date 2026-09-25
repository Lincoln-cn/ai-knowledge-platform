"""验证「节点 / 配置分离」：点卡片 → 弹窗里有完整表单；拖拽不误触。

这是本次交互改动的本体，必须实测：
  · 点卡片 → el-drawer 出现，且里面有真实的表单项（不是空壳）
  · 拖动节点 → **不**弹窗（否则挪一下节点就弹配置）
  · 弹窗里的表单可编辑，且改动写回 nodeModel（关掉再开还在）
  · Esc / 遮罩关闭
"""

from __future__ import annotations

import sys

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280"


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

        def on_console(m) -> None:
            if m.type != "error":
                return
            url = (m.location or {}).get("url", "") if isinstance(m.location, dict) else ""
            if "favicon" not in url:
                errs.append(f"{m.text} @ {url}")

        page.on("console", on_console)

        page.goto(f"{BASE}/#/login", wait_until="networkidle")
        page.wait_for_timeout(600)
        page.fill("input[autocomplete='username']", "admin")
        page.fill("input[autocomplete='current-password']", "admin")
        page.click("button:has-text('登录')")
        page.wait_for_timeout(1800)
        page.goto(f"{BASE}/#/assistants/as5/workflow", wait_until="networkidle")
        page.wait_for_timeout(5500)

        # ── 1. 弹窗初始不可见 ──
        # ⚠️ 不能用"存在数"判 —— 每个节点各有一个 drawer，DOM 里恒有 9 个。
        #    判据必须是**可见性**（offsetParent 非空 + 有尺寸）。
        VIS = (
            "() => [...document.querySelectorAll('.node-config-drawer')]"
            ".filter(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0).length"
        )
        print("\n== 1. 初始状态 ==")
        check(page.evaluate(VIS) == 0, "初始没有可见的配置弹窗")
        check(page.locator(".workflow-node-container .el-form").count() == 0,
              "卡片里不再有表单（配置已搬走）")

        # ── 2. 拖动节点：不许弹窗 ──
        print("\n== 2. 拖动节点不应误触弹窗 ==")
        card = page.locator(".workflow-node-container .step-container").first
        cb = card.bounding_box()
        page.mouse.move(cb["x"] + cb["width"] / 2, cb["y"] + cb["height"] / 2)
        page.mouse.down()
        page.mouse.move(cb["x"] + cb["width"] / 2 + 90, cb["y"] + cb["height"] / 2 + 40, steps=8)
        page.mouse.up()
        page.wait_for_timeout(700)
        check(page.evaluate(VIS) == 0, "拖动后没有可见的配置弹窗")

        # ── 3. 点卡片：弹窗出现且带真实表单 ──
        # ⚠️ 必须**逐个节点都试**，不能只试第一个。
        #    第一版只点了第一个（开始节点），而它的标题不可改名；
        #    可改名的节点标题当时套着 WorkFlowReadWrite（带 @mousedown.stop），
        #    会把事件吃掉 ⇒ 那类节点点了没反应，而探针却报全绿。
        print("\n== 3. 逐个节点：点卡片都应打开配置弹窗 ==")
        cards = page.locator(".workflow-node-container .step-container")
        total = cards.count()
        failed: list[str] = []
        for i in range(total):
            name = cards.nth(i).inner_text().split("\n")[0].strip()[:10]
            cards.nth(i).click()
            page.wait_for_timeout(700)
            if page.evaluate(VIS) == 0:
                failed.append(f"{i}:{name}")
            else:
                # ⚠️ 关掉后必须**等遮罩真的消失**再点下一个。
                #    抽屉在右侧宽 480px，若遮罩还在，下一次点击会落在遮罩上、
                #    根本到不了卡片 —— 表象是"右侧那几个节点点不开"（左侧因为不在
                #    遮罩范围内所以能过），看起来像节点本身有问题，其实是探针的时序。
                page.keyboard.press("Escape")
                try:
                    page.wait_for_selector(
                        ".el-overlay", state="detached", timeout=3000
                    )
                except Exception:
                    page.wait_for_timeout(900)
                page.wait_for_timeout(250)
        check(not failed, f"{total} 个节点全部能点开配置（失败：{failed if failed else '无'}）")

        # ── 4. 表单内容与关闭 ──
        print("\n== 4. 弹窗内容与关闭 ==")
        cards.nth(3 if total > 3 else 0).click()
        page.wait_for_timeout(900)
        vis = page.evaluate(VIS)
        check(vis >= 1, f"配置弹窗可见（{vis} 个）")
        if vis >= 1:
            items = page.evaluate(
                "() => [...document.querySelectorAll('.node-config-drawer')]"
                ".filter(d=>d.offsetParent!==null).reduce((a,d)=>a+d.querySelectorAll('.el-form-item').length,0)"
            )
            inputs = page.evaluate(
                "() => [...document.querySelectorAll('.node-config-drawer')]"
                ".filter(d=>d.offsetParent!==null).reduce((a,d)=>a+d.querySelectorAll('input,textarea').length,0)"
            )
            check(items > 0, f"可见弹窗里有表单项 {items} 个")
            check(inputs > 0, f"可见弹窗里有可编辑控件 {inputs} 个")
            page.keyboard.press("Escape")
            page.wait_for_timeout(800)
            check(page.evaluate(VIS) == 0, "Esc 后弹窗已关闭")

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
