"""验应用管理列表的文字是否被截断（应用名 / 应用介绍 / 模型名 / 类型标签）。

判据用 **scrollWidth vs clientWidth**（真截断的客观证据），不看截图靠眼睛。
用法：python check_assistants.py [base]
"""
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
    page.goto(BASE + "#/assistants", wait_until="networkidle")
    page.wait_for_timeout(2200)

    info = page.evaluate(
        """() => {
            const out = [];
            document.querySelectorAll('.app-card').forEach((card, i) => {
                const name = card.querySelector('.ac-name');
                const desc = card.querySelector('.ac-name + div, .micro.muted');
                const model = card.querySelector('.ac-facts dd');
                const tags = [...card.querySelectorAll('.ac-name .el-tag')].map(t => t.innerText.trim());
                const box = card.getBoundingClientRect();
                const pick = (el, vertical) => el ? {
                    text: el.innerText.trim().slice(0, 60),
                    cw: el.clientWidth, sw: el.scrollWidth,
                    ch: el.clientHeight, sh: el.scrollHeight,
                    w: Math.round(el.getBoundingClientRect().width),
                    // 单行省略看横向；两行截断看纵向（scrollWidth 在换行后已无意义）
                    clipped: vertical ? (el.scrollHeight > el.clientHeight + 1)
                                      : (el.scrollWidth > el.clientWidth + 1),
                } : null;
                out.push({
                    i, cardW: Math.round(box.width),
                    name: pick(name, false), desc: pick(desc, true), model: pick(model, false), tags,
                });
            });
            return out;
        }"""
    )

    print(f"卡片数：{len(info)}")
    print()
    clipped_total = 0
    for c in info[:12]:
        flag = "被截 ✗" if any(x and x["clipped"] for x in (c["name"], c["desc"], c["model"])) else "未截 ✓"
        if flag.startswith("被截"):
            clipped_total += 1
        print(f"[{c['i']}] 卡宽 {c['cardW']:3}  标签 {c['tags']}   {flag}")
        for key in ("name", "desc", "model"):
            v = c[key]
            if v:
                mark = " ★截" if v["clipped"] else ""
                if key == "desc":
                    print(f"      {key:6} 宽 {v['w']:3} 高 {v['ch']:3}/{v['sh']:3}{mark}  {v['text']!r}")
                else:
                    print(f"      {key:6} 宽 {v['w']:3} (client {v['cw']:3} / scroll {v['sw']:3}){mark}  {v['text']!r}")
    print()
    print(f"有截断的卡片：{clipped_total}（应为 0）")

    print()
    print("控制台错误：")
    for e in errs[:6]:
        print("   ", e[:150])
    if not errs:
        print("    (无)")

    page.screenshot(path=str(__import__("pathlib").Path(__file__).parent / "screenshots" / "assistants.png"))
    print("截图：screenshots/assistants.png")

    try:
        b.close()
    except Exception:
        pass
