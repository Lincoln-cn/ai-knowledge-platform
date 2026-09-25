"""验节点里的下拉：AI 模型列表 / 知识库列表 / 检索问题级联。"""
from __future__ import annotations

import sys

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280/"
NODE = ".workflow-node-container"

VISIBLE = """() => [...document.querySelectorAll('.el-select-dropdown, .el-cascader__dropdown, .el-popper')]
    .filter(p => { const r = p.getBoundingClientRect(); return r.width > 0 && r.height > 0; })
    .map(p => (p.innerText || '').trim().replace(/\\n+/g, ' | ').slice(0, 220))"""

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
    page.wait_for_timeout(4500)

    titles = page.evaluate(
        """() => [...document.querySelectorAll('.workflow-node-container')].map(el => (el.innerText||'').split('\\n')[0].trim())"""
    )
    print(f"节点：{titles}")
    print()

    def open_picker(label: str, idx: int, desc: str):
        print(f"── {label}（节点 [{idx}] {titles[idx]!r}）")
        node = page.locator(NODE).nth(idx)
        sel = node.locator(".el-select").first
        print(f"   .el-select 数量：{node.locator('.el-select').count()}")
        if not sel.count():
            print("   没有 el-select")
            return
        sel.click()
        page.wait_for_timeout(1300)
        vis = page.evaluate(VISIBLE)
        print(f"   可见下拉：{vis if vis else '★ 无（未展开）'}")
        page.keyboard.press("Escape")
        page.wait_for_timeout(400)

    ai = next((i for i, t in enumerate(titles) if "风险研判" in t), None)
    if ai is not None:
        open_picker("AI 模型下拉", ai, "ai-chat-node")

    q = next((i for i, t in enumerate(titles) if "改写检索问" in t), None)
    if q is not None:
        open_picker("问题改写节点的模型下拉", q, "question-node")

    print()
    print("── 知识库选择（检索合同制度节点）")
    si = next((i for i, t in enumerate(titles) if "检索合同" in t), None)
    if si is not None:
        node = page.locator(NODE).nth(si)
        # 节点里的「添加」按钮打开知识库弹窗
        添加 = node.locator("button", has_text="添加")
        print(f"   节点内「添加」按钮数：{添加.count()}")
        if 添加.count():
            添加.last.click()
            page.wait_for_timeout(1500)
            dlg = page.locator(".el-dialog:visible")
            print(f"   弹窗数：{dlg.count()}")
            if dlg.count():
                txt = dlg.last.inner_text().replace("\n", " | ")
                print(f"   弹窗内容：{txt[:260]}")
                print(f"   → {'知识库已列出 ✓' if ('合同' in txt or '制度' in txt or '库' in txt) else '★ 列表为空'}")
                page.keyboard.press("Escape")
                page.wait_for_timeout(400)

    page.screenshot(path=str(__import__("pathlib").Path(__file__).parent / "screenshots" / "pickers.png"))

    print()
    print("控制台错误：")
    for e in errs[:8]:
        print("   ", e[:160])
    if not errs:
        print("    (无)")

    try:
        b.close()
    except Exception:
        pass
