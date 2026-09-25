"""验「发布历史」抽屉：是否越界、是否有自己的关闭按钮、点外部能否关闭。

用法：python check_history.py [base]
"""
from __future__ import annotations

import sys

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280/"
PANEL = ".workflow-publish-history"

with sync_playwright() as p:
    b = p.chromium.launch(channel="chrome")
    page = b.new_page(viewport={"width": 1600, "height": 900})
    errs: list[str] = []
    page.on("pageerror", lambda e: errs.append(f"PAGEERROR: {e}"))
    page.on("console", lambda m: errs.append(f"CONSOLE {m.text[:140]}") if m.type == "error" else None)

    page.goto(BASE + "#/login", wait_until="networkidle")
    page.wait_for_timeout(600)
    page.fill("input[autocomplete='username']", "admin")
    page.fill("input[autocomplete='current-password']", "admin")
    page.click("button:has-text('登录')")
    page.wait_for_timeout(1600)
    page.goto(BASE + "#/assistants/as5/workflow", wait_until="networkidle")
    page.wait_for_timeout(4000)

    def open_history():
        # 工具条右侧的 ⋮ → 发布历史
        page.locator(".top .el-dropdown button").first.click()
        page.wait_for_timeout(600)
        page.locator("text=发布历史").last.click()
        page.wait_for_timeout(1800)

    print(f"1) 打开发布历史")
    open_history()
    panel = page.locator(PANEL)
    print(f"   面板存在：{panel.count() > 0}")
    if panel.count():
        pb = panel.bounding_box()
        vw = (page.viewport_size or {}).get("width", 0)
        vh = (page.viewport_size or {}).get("height", 0)
        print(f"   面板几何：x {pb['x']:.0f}~{pb['x']+pb['width']:.0f}  y {pb['y']:.0f}~{pb['y']+pb['height']:.0f}  (视口 {vw}×{vh})")
        inside = pb["y"] >= -1 and pb["y"] + pb["height"] <= vh + 1 and pb["x"] + pb["width"] <= vw + 1 and pb["x"] >= -1
        print(f"   → {'完整落在视口内 ✓' if inside else '★ 越界 ✗'}")
        # 行数（历史条目）
        rows = page.locator(f"{PANEL} .flex-between")
        print(f"   历史条目数：{rows.count()}")
        print(f"   文本前 80 字：{panel.inner_text()[:80]!r}")
        # ★ 在面板打开的状态下截图（否则拍到的全是关闭后的画面）
        page.screenshot(
            path=str(__import__("pathlib").Path(__file__).parent / "screenshots" / "publish-history.png")
        )
        print("   截图：screenshots/publish-history.png（面板打开态）")

    print()
    print("2) 面板自带的关闭按钮")
    close_btn = page.locator(f"{PANEL} button[title='关闭']")
    print(f"   找到关闭按钮：{close_btn.count() > 0}")
    if close_btn.count():
        close_btn.first.click()
        page.wait_for_timeout(900)
        print(f"   → 点击后面板：{'已关闭 ✓' if page.locator(PANEL).count() == 0 else '★ 仍在 ✗'}")

    print()
    print("3) 再打开 → 分别点几个「外部」位置，看哪个能关")
    # ⚠️ 探针点位要选**真的空白**处。
    #    原来「左侧边栏」取 (100,400)，而侧栏改版后那个纵坐标正好压在
    #    「反馈处理」这个导航链接上 ⇒ 点完既关了面板、又跳走了页面，
    #    下一次 open_history() 在别的路由上找不到 .top，报成"超时"。
    #    侧栏条目只排到 y≈470，所以取 y=700（条目之下的空白区）。
    for label, (cx, cy) in [
        ("左侧边栏空白", (100, 700)),
        ("顶部工具栏空白", (800, 30)),
        ("画布空白", (500, 600)),
    ]:
        open_history()
        if page.locator(PANEL).count() == 0:
            print(f"   [{label}] 打开失败，跳过")
            continue
        page.mouse.click(cx, cy)
        page.wait_for_timeout(900)
        gone = page.locator(PANEL).count() == 0
        print(f"   点 {label:10} ({cx:4},{cy:3}) → {'已关闭 ✓' if gone else '★ 仍在 ✗'}")
        if not gone:
            # 关掉再试下一个
            btn = page.locator(f"{PANEL} button[title='关闭']")
            if btn.count():
                btn.first.click()
                page.wait_for_timeout(500)

    print()
    print("4) 预览某版本后再点外部（旧代码在这一步关不掉）")
    open_history()
    rows = page.locator(f"{PANEL} .flex-between")
    if rows.count():
        rows.first.click()
        page.wait_for_timeout(2000)
        print(f"   点第一条版本后，面板存在：{page.locator(PANEL).count() > 0}")
        page.mouse.click(500, 600)
        page.wait_for_timeout(1000)
        print(f"   → 点外部后面板：{'已关闭 ✓' if page.locator(PANEL).count() == 0 else '★ 仍关不掉 ✗'}")

    print()
    print("控制台错误：")
    for e in errs[:6]:
        print("   ", e[:160])
    if not errs:
        print("    (无)")

    try:
        b.close()
    except Exception:
        pass
