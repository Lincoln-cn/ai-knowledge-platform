"""验「单例节点」约束：面板能否再加第二个开始节点 + 删除规则是否按 count 判。

用法：python check_singleton.py [base]
"""
from __future__ import annotations

import sys

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280/"
NODE = ".workflow-node-container"

with sync_playwright() as p:
    b = p.chromium.launch(channel="chrome")
    page = b.new_page(viewport={"width": 1600, "height": 900})
    errs: list[str] = []
    page.on("pageerror", lambda e: errs.append(f"PAGEERROR: {e}"))
    page.on("console", lambda m: errs.append(f"CONSOLE {m.text[:130]}") if m.type == "error" else None)

    page.goto(BASE + "#/login", wait_until="networkidle")
    page.wait_for_timeout(600)
    page.fill("input[autocomplete='username']", "admin")
    page.fill("input[autocomplete='current-password']", "admin")
    page.click("button:has-text('登录')")
    page.wait_for_timeout(1600)
    page.goto(BASE + "#/assistants/as5/workflow", wait_until="networkidle")
    page.wait_for_timeout(4500)

    def counts():
        return page.evaluate(
            """() => {
                const nodes = [...document.querySelectorAll('.workflow-node-container')];
                const titles = nodes.map(n => (n.innerText || '').split('\\n')[0].trim());
                return { total: nodes.length, starts: titles.filter(t => t.startsWith('开始')).length, titles };
            }"""
        )

    c0 = counts()
    print(f"初始：共 {c0['total']} 个节点，其中「开始」{c0['starts']} 个")
    print(f"  标题：{c0['titles']}")
    print()

    print("=== 打开「添加组件」面板，看「开始节点」那一项 ===")
    page.locator("button:has-text('添加组件')").first.click()
    page.wait_for_timeout(1200)
    item = page.locator(".workflow-dropdown-menu .workflow-dropdown-item").filter(has_text="开始节点").first
    print(f"  找到该项：{item.count() > 0}")
    if item.count():
        txt = item.inner_text().replace("\n", " / ")
        cls = item.get_attribute("class") or ""
        print(f"  该项文本：{txt!r}")
        print(f"  该项 class：{cls}")
        print(f"  → 「已存在」标记：{'有 ✓' if '已存在' in txt else '无 ✗'}   is-planned 样式：{'有 ✓' if 'is-planned' in cls else '无'}")

    print()
    print("=== 点它，看会不会被拒 ===")
    before = counts()["starts"]
    if item.count():
        item.click()
        page.wait_for_timeout(1400)
        msg = page.locator(".el-message").all_inner_texts()
        after = counts()["starts"]
        print(f"  提示消息：{[m.strip() for m in msg]}")
        print(f"  开始节点数 {before} → {after}  {'被拒绝 ✓' if after == before else '★ 还是加上了 ✗'}")

    page.keyboard.press("Escape")
    page.wait_for_timeout(400)
    page.screenshot(path=str(__import__("pathlib").Path(__file__).parent / "screenshots" / "singleton.png"))

    print()
    print("=== 删除规则（按 count 判，不是写死 start-node 不能删） ===")
    print(f"  当前开始节点数 = {counts()['starts']}")
    print("  （若 = 2，应有至少一个的菜单里「删除」可点；若 = 1，应置灰并给原因）")

    print()
    print("控制台错误：")
    for e in errs[:6]:
        print("   ", e[:150])
    if not errs:
        print("    (无)")

    try:
        b.close()
    except Exception:
        pass
