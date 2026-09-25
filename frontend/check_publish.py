"""模拟「合同风险研判流程」从打开到发布成功的完整步骤，逐步打印实际结果。"""
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
    page.on("console", lambda m: errs.append(f"CONSOLE {m.text[:140]}") if m.type == "error" else None)

    def msgs() -> list[str]:
        """消息只显示 1.5s，所以读之前先短暂轮询抓一下"""
        for _ in range(12):
            got = [t.strip() for t in page.locator(".el-message").all_inner_texts() if t.strip()]
            if got:
                return got
            page.wait_for_timeout(120)
        return []

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
    print(f"步骤 1：打开画布。节点 {len(titles)} 个：{titles}")

    def pick_one(picker, label: str) -> bool:
        """把一个级联选成「某上游节点 → 某字段」。"""
        picker.click()
        page.wait_for_timeout(1000)
        if page.locator(".el-cascader__dropdown:visible").count() == 0:
            return False
        for _ in range(3):
            col = ".el-cascader__dropdown:visible .el-cascader-menu:last-child .el-cascader-node"
            loc = page.locator(col)
            if loc.count() == 0:
                break
            loc.first.click()
            page.wait_for_timeout(800)
            if page.locator(".el-cascader__dropdown:visible").count() == 0:
                return True
        page.keyboard.press("Escape")
        page.wait_for_timeout(250)
        return False

    def fill_all_cascaders(scope, label: str):
        """节点里**每一个**级联都要填（条件节点每个分支各一个，只填第一个不够）。"""
        n = scope.locator(".el-cascader").count()
        print(f"    {label}：级联数 {n}")
        for i in range(n):
            ok = pick_one(scope.locator(".el-cascader").nth(i), f"{label}[{i}]")
            print(f"      [{i}] {'已选 ✓' if ok else '★ 没选上'}")

    print()
    print("步骤 2：找「检索合同制度」节点的「检索问题」选择器")
    idx = next((i for i, t in enumerate(titles) if "检索合同" in t), None)
    print(f"  该节点下标：{idx}")
    node = page.locator(NODE).nth(idx)
    picker = node.locator(".el-select, .el-cascader").first
    print(f"  选择器存在：{picker.count() > 0}")

    if picker.count():
        print()
        print("步骤 3：点开它，看下拉是否真的显示出来（不再被卡片裁）")
        picker.click()
        page.wait_for_timeout(1200)
        vis = page.evaluate(
            """() => [...document.querySelectorAll('.el-popper, .el-select-dropdown, .el-cascader__dropdown')]
                .filter(p => { const r = p.getBoundingClientRect(); return r.width > 0 && r.height > 0; })
                .map(p => ({ cls: String(p.className).slice(0, 40),
                             rect: (r => [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)])(p.getBoundingClientRect()),
                             text: (p.innerText || '').trim().slice(0, 60).replace(/\\n/g, '|') }))"""
        )
        for v in vis:
            print(f"    {v}")
        print(f"  → {'下拉可见 ✓' if vis else '★ 仍不可见 ✗'}")
        page.keyboard.press("Escape")
        page.wait_for_timeout(300)

        print()
        print("步骤 4：填必填的「引用变量」")
        fill_all_cascaders(node, "检索合同制度 · 检索问题")

        ci = next((i for i, t in enumerate(titles) if "风险分流" in t), None)
        if ci is not None:
            fill_all_cascaders(page.locator(NODE).nth(ci), "风险分流 · 分支变量")
        page.keyboard.press("Escape")
        page.wait_for_timeout(400)

    print()
    print("步骤 5：点「发布」，轮询 15 秒抓任何反馈")
    page.locator(".top button:has-text('发布')").first.click()
    seen = None
    for _ in range(30):
        page.wait_for_timeout(500)
        ms = [t.strip() for t in page.locator(".el-message").all_inner_texts() if t.strip()]
        box = page.locator(".el-message-box")
        if ms or box.count():
            seen = (ms, box.inner_text()[:200] if box.count() else "")
            break
    print(f"  反馈：{seen}")
    if not seen:
        print("  ★ 15 秒内没有任何反馈（静默失败）")
    print(f"  消息：{msgs()}")

    if page.locator(".el-message-box").count():
        print()
        print("步骤 6：填发布说明并确认")
        box = page.locator(".el-message-box")
        if box.locator("input").count():
            box.locator("input").first.fill("自动化验证：合同风险研判流程")
        # 确认按钮就是弹窗里文本为「发布」的那个（不要用 last，footer 里还有取消）
        ok_btn = box.locator("button").filter(has_text="发布").last
        print(f"  确认按钮数：{box.locator('button').filter(has_text='发布').count()}")
        ok_btn.click()
        # 轮询等成功提示或弹窗消失
        got = []
        for _ in range(30):
            page.wait_for_timeout(400)
            got = [t.strip() for t in page.locator(".el-message").all_inner_texts() if t.strip()]
            if got or page.locator(".el-message-box").count() == 0:
                break
        print(f"  消息：{got}")
        print(f"  弹窗还在：{page.locator('.el-message-box').count() > 0}")
        print(f"  → {'发布成功 ✓' if any('发布成功' in x for x in got) else '★ 没看到成功提示'}")

        print()
        print("步骤 7：开发布历史，确认新版本进了列表")
        page.locator(".top .el-dropdown button").first.click()
        page.wait_for_timeout(700)
        page.locator("text=发布历史").last.click()
        page.wait_for_timeout(2000)
        panel = page.locator(".workflow-publish-history")
        if panel.count():
            txt = panel.inner_text().replace("\n", " | ")
            print(f"  历史：{txt[:240]}")
            print(f"  → {'新版本已在列表 ✓' if '自动化验证' in txt else '★ 没看到新版本'}")

    page.screenshot(path=str(__import__("pathlib").Path(__file__).parent / "screenshots" / "publish.png"))

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
