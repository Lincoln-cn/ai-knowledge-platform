"""验「全部收起 / 全部展开」是否真的生效。

判据用**节点总高度**：收起后内容隐藏，所有节点的高度应显著变小；
展开后应恢复。这比看 DOM 文本可靠。

用法：python check_collapse.py [base]
"""
from __future__ import annotations

import sys

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280/"
# 用 .lf-node（SVG <g>，恒存在）而不是节点内部 HTML 的类名 ——
# 后者依赖 foreignObject 里的子应用渲染时机，会忽有忽无。
sel = ".lf-node"


def total_height(page) -> float:
    loc = page.locator(sel)
    n = loc.count()
    h = 0.0
    boxes = []
    for i in range(n):
        bb = loc.nth(i).bounding_box()
        boxes.append(None if bb is None else round(bb["height"]))
        if bb:
            h += bb["height"]
    if n:
        print(f"    [debug] 找到 {n} 个节点，各自高度 {boxes}")
    return h


with sync_playwright() as p:
    b = p.chromium.launch(channel="chrome")
    page = b.new_page(viewport={"width": 1600, "height": 900})
    page.goto(BASE + "#/login", wait_until="networkidle")
    page.wait_for_timeout(600)
    page.fill("input[autocomplete='username']", "admin")
    page.fill("input[autocomplete='current-password']", "admin")
    page.click("button:has-text('登录')")
    page.wait_for_timeout(1600)
    page.goto(BASE + "#/assistants/as5/workflow", wait_until="networkidle")
    page.wait_for_timeout(4000)

    btns = page.locator(".workflow-control button")
    print(f"控制条按钮数：{btns.count()}")
    for i in range(btns.count()):
        t = btns.nth(i).inner_text().strip()
        title = btns.nth(i).get_attribute("title") or ""
        print(f"  [{i}] 文本={t!r} title={title!r}")

    # 直接用 DOM 查一遍，排除选择器/时序问题
    dom = page.evaluate(
        """() => ({
            byClass: document.querySelectorAll('.workflow-node-container').length,
            lfNodes: document.querySelectorAll('.lf-node').length,
            htmlOverlay: document.querySelectorAll('.lf-html-overlay').length,
            canvas: !!document.querySelector('.lf-graph'),
            url: location.hash,
        })"""
    )
    print()
    print(f"DOM 探测：{dom}")

    h0 = total_height(page)
    print()
    print(f"初始节点总高：{h0:.0f}px")

    # 收起 / 展开分别是最后两个按钮
    idx_fold = btns.count() - 2
    idx_expand = btns.count() - 1

    # ⚠️ 判据**不假定初始是展开态**。
    #    原来的写法是「展开后高度应回到初始 h0」，那是节点默认展开时的假设。
    #    现在卡片的展开态只列「参数输出」、默认收起，初始就是收起态 ⇒ 那条判据方向反了，
    #    会把正确的行为报成"展开无效 / 不稳定"。
    #    正解 = 判「两个状态差异显著」+「来回切换读数是同一对值」，与谁先谁后无关。
    btns.nth(idx_fold).click()
    page.wait_for_timeout(1200)
    h1 = total_height(page)
    print(f"点「收起全部」后总高：{h1:.0f}px   变化 {h1 - h0:+.0f}px")

    btns.nth(idx_expand).click()
    page.wait_for_timeout(1200)
    h2 = total_height(page)
    print(f"点「展开全部」后总高：{h2:.0f}px   变化 {h2 - h0:+.0f}px")

    lo, hi = min(h0, h1, h2), max(h0, h1, h2)
    spread_ok = hi > lo * 1.25
    print(f"  → 收起态 {lo:.0f} / 展开态 {hi:.0f}（差 {hi - lo:.0f}px）"
          f"  {'两态差异显著 ✓' if spread_ok else '★ 收起展开几乎没差别 ✗'}")

    btns.nth(idx_fold).click()
    page.wait_for_timeout(1000)
    h3 = total_height(page)
    btns.nth(idx_expand).click()
    page.wait_for_timeout(1000)
    h4 = total_height(page)
    stable = (
        abs(min(h3, h4) - lo) < lo * 0.15 and abs(max(h3, h4) - hi) < hi * 0.15
    )
    print(f"再来回一次：收起 {h3:.0f} 展开 {h4:.0f}"
          f"  → {'稳定 ✓（读数与上一轮一致）' if stable else '★ 不稳定 ✗'}")

    page.screenshot(path=str(__import__("pathlib").Path(__file__).parent / "screenshots" / "collapse-expand.png"))
    print()
    print("截图：screenshots/collapse-expand.png")

    try:
        b.close()
    except Exception:
        pass
