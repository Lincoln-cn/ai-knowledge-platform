"""给编排画布截图（人可看，我也自己看）。

用法：
    python shot_workflow.py                       # 存到 screenshots/
    python shot_workflow.py http://localhost:5280/ 名字
"""
from __future__ import annotations

import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = "http://localhost:5280/"
NAME = "workflow"

args = [a for a in sys.argv[1:]]
if args and args[0].startswith("http"):
    BASE = args.pop(0)
if args:
    NAME = args[0]

OUT = Path(__file__).resolve().parent / "screenshots"
OUT.mkdir(exist_ok=True)

with sync_playwright() as p:
    b = p.chromium.launch(channel="chrome")
    page = b.new_page(viewport={"width": 1600, "height": 900})
    errors: list[str] = []
    page.on("pageerror", lambda e: errors.append(f"PAGEERROR: {e}"))
    page.on("console", lambda m: errors.append(f"CONSOLE {m.text[:160]}") if m.type == "error" else None)
    failed: list[str] = []
    page.on("response", lambda r: failed.append(f"HTTP {r.status} {r.url}") if r.status >= 400 else None)

    page.goto(BASE + "#/login", wait_until="networkidle")
    page.wait_for_timeout(600)
    page.fill("input[autocomplete='username']", "admin")
    page.fill("input[autocomplete='current-password']", "admin")
    page.click("button:has-text('登录')")
    page.wait_for_timeout(1800)

    page.goto(BASE + "#/assistants/as5/workflow", wait_until="networkidle")
    # 等自动布局（若已实现）
    page.wait_for_timeout(4500)

    whole = OUT / f"{NAME}.png"
    page.screenshot(path=str(whole))
    print(f"整页截图：{whole}")

    canvas = page.locator(".workflow-main").first
    if canvas.count():
        box = OUT / f"{NAME}-canvas.png"
        canvas.screenshot(path=str(box))
        print(f"画布截图：{box}")

    # 只看画布区域：判断节点是否重叠、控件位置
    print()
    print("=== 画布几何信息 ===")
    nodes = page.locator(".workflow-node-container")
    print(f"节点数：{nodes.count()}")
    boxes = []
    for i in range(min(nodes.count(), 20)):
        bb = nodes.nth(i).bounding_box()
        if bb:
            boxes.append(bb)
    overlaps = 0
    for i in range(len(boxes)):
        for j in range(i + 1, len(boxes)):
            a, c = boxes[i], boxes[j]
            if a["x"] < c["x"] + c["width"] and c["x"] < a["x"] + a["width"] and a["y"] < c["y"] + c["height"] and c["y"] < a["y"] + a["height"]:
                overlaps += 1
    print(f"重叠的对数：{overlaps}（应为 0）")
    if boxes:
        print(f"节点包围盒范围：x {min(b['x'] for b in boxes):.0f}~{max(b['x']+b['width'] for b in boxes):.0f}"
              f"  y {min(b['y'] for b in boxes):.0f}~{max(b['y']+b['height'] for b in boxes):.0f}")

    ctrl = page.locator(".workflow-control").first
    if ctrl.count():
        cb = ctrl.bounding_box()
        vw = page.viewport_size or {"width": 0, "height": 0}
        if cb:
            right_gap = (cb["x"] + cb["width"])
            print(f"控件位置：x={cb['x']:.0f}~{right_gap:.0f} (视口宽 {vw['width']})  y={cb['y']:.0f}~{cb['y']+cb['height']:.0f}")
            print(f"  → {'右下 ✓' if right_gap > vw['width'] * 0.6 else '偏左 ✗'}")
    else:
        print("控件：未找到 .workflow-control")

    # ── 所有节点是否都在**可视区**内（「一页装下」的硬指标）──
    #   注意：不能只用画布元素的框 —— 画布可能延伸到视口之外（页面溢出时），
    #   那时节点"在画布里"但用户看不到。所以取 视口 ∩ 画布。
    print()
    print("=== 是否一页装下 ===")
    vh = (page.viewport_size or {}).get("height", 0)
    canvas_box = canvas.bounding_box() if canvas.count() else None
    if canvas_box and boxes:
        left, top = canvas_box["x"], canvas_box["y"]
        right = left + canvas_box["width"]
        bottom = min(top + canvas_box["height"], vh)
        outside = []
        for i, b in enumerate(boxes):
            if b["x"] < left - 1 or b["y"] < top - 1 or b["x"] + b["width"] > right + 1 or b["y"] + b["height"] > bottom + 1:
                outside.append(
                    f"#{i} x {b['x']:.0f}~{b['x']+b['width']:.0f} y {b['y']:.0f}~{b['y']+b['height']:.0f}"
                )
        print(f"  可视区（视口 ∩ 画布）：x {left:.0f}~{right:.0f}  y {top:.0f}~{bottom:.0f}（视口高 {vh}）")
        print(f"  被切出可视区的节点：{len(outside)}（应为 0）")
        for o in outside[:6]:
            print(f"     ✗ {o}")
    else:
        print("  (无法取到画布或节点包围盒)")

    # ── 滚动条：用户要求「没有滚动条，在一页」 ──
    metrics = page.evaluate(
        """() => {
            const main = document.querySelector('.main');
            return {
                winOverflow: document.documentElement.scrollHeight - window.innerHeight,
                bodyOverflow: document.body.scrollHeight - window.innerHeight,
                mainClient: main ? main.clientHeight : null,
                mainScroll: main ? main.scrollHeight : null,
            };
        }"""
    )
    print()
    print("=== 滚动条 ===")
    print(f"  window: scrollHeight - innerHeight = {metrics['winOverflow']}px（应为 0）")
    print(f"  .main : scrollHeight {metrics['mainScroll']} vs clientHeight {metrics['mainClient']}"
          f" → 溢出 {metrics['mainScroll'] - metrics['mainClient']}px（应为 0）")

    print()
    print(f"控制台错误/异常（{len(errors)}）：")
    for e in errors[:8]:
        print("   ", e[:180])
    if not errors:
        print("    (无)")
    print(f"HTTP >=400 的请求（{len(failed)}）：")
    for f in failed[:8]:
        print("   ", f[:200])
    if not failed:
        print("    (无)")

    try:
        b.close()
    except Exception:
        pass
