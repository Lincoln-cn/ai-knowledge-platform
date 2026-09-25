"""验「添加组件」面板：每个条目是否真的渲染出了图标。"""
from __future__ import annotations

import sys

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280/"

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

    page.locator("button:has-text('添加组件')").first.click()
    page.wait_for_timeout(1500)

    items = page.evaluate(
        """() => [...document.querySelectorAll('.workflow-dropdown-menu .workflow-dropdown-item')].map(el => {
            const label = (el.innerText || '').split('\\n')[0].trim();
            const svg = el.querySelectorAll('svg').length;
            const img = el.querySelectorAll('img').length;
            const imgOk = [...el.querySelectorAll('img')].every(i => i.complete && i.naturalWidth > 0);
            const firstChildHtml = el.firstElementChild ? el.firstElementChild.tagName : '(none)';
            return { label, svg, img, imgOk, firstChildHtml, empty: svg === 0 && img === 0 };
        })"""
    )
    print(f"面板条目数：{len(items)}")
    bad = []
    for it in items:
        mark = "★ 无图标" if it["empty"] else ("裂图" if it["img"] and not it["imgOk"] else "✓")
        if mark != "✓":
            bad.append((it["label"], mark))
        print(f"  {it['label']:14} svg={it['svg']} img={it['img']} {mark}")
    print()
    print(f"有问题的条目：{bad if bad else '无 ✓'}")

    page.screenshot(path=str(__import__("pathlib").Path(__file__).parent / "screenshots" / "palette.png"))

    print("\n控制台错误：")
    for e in errs[:6]:
        print("   ", e[:150])
    if not errs:
        print("    (无)")

    try:
        b.close()
    except Exception:
        pass
