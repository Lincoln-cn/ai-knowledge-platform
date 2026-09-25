"""验节点操作菜单：每个节点都有菜单 + 删除真能删 + 单例删除禁用但给原因。"""
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

    def node_count() -> int:
        return page.locator(NODE).count()

    titles = page.evaluate(
        """() => [...document.querySelectorAll('.workflow-node-container')].map(el => (el.innerText || '').split('\\n')[0].trim())"""
    )
    print(f"节点数：{len(titles)}")

    def open_last_menu(idx: int) -> list[str]:
        """打开第 idx 个节点的「最后一个下拉」（就是 ⋮ 节点菜单），返回菜单项文本。"""
        el = page.locator(NODE).nth(idx)
        el.locator(".el-dropdown").last.locator("button").first.click()
        page.wait_for_timeout(800)
        txt = page.evaluate(
            """() => {
                const menus = [...document.querySelectorAll('.el-dropdown-menu')]
                  .filter(m => m.getBoundingClientRect().width > 0);
                return menus.length ? menus[menus.length - 1].innerText.trim() : '';
            }"""
        )
        return [s.strip() for s in txt.split("\n") if s.strip()]

    no_menu = []
    for i, t in enumerate(titles):
        items = open_last_menu(i)
        has = any("删除节点" in x for x in items)
        print(f"  [{i}] {t!r:16} {'✓ 有节点菜单' if has else '★ 无'}")
        if not has:
            no_menu.append(t)
        page.keyboard.press("Escape")
        page.wait_for_timeout(250)

    print(f"\n没有节点菜单的节点：{no_menu if no_menu else '无 ✓'}")

    def read_menu(idx: int) -> list[str]:
        el = page.locator(NODE).nth(idx)
        el.locator(".el-dropdown").last.locator("button").first.click()
        page.wait_for_timeout(800)
        txt = page.evaluate(
            """() => {
                const menus = [...document.querySelectorAll('.el-dropdown-menu')]
                  .filter(m => m.offsetParent !== null || (m.getBoundingClientRect().width > 0));
                return menus.map(m => m.innerText.trim().replace(/\\n+/g, ' | '));
            }"""
        )
        page.keyboard.press("Escape")
        page.wait_for_timeout(300)
        return txt

    print()
    print("=== 菜单 DOM 状态全量 ===")
    el = page.locator(NODE).nth(8)
    el.locator(".el-dropdown").last.locator("button").first.click()
    page.wait_for_timeout(1200)
    dump = page.evaluate(
        """() => [...document.querySelectorAll('.el-dropdown-menu')].map((m, i) => {
            const b = m.getBoundingClientRect();
            const cs = getComputedStyle(m);
            return {
                i,
                text: (m.innerText || '').trim().slice(0, 30).replace(/\\n/g, '|'),
                rect: [Math.round(b.x), Math.round(b.y), Math.round(b.width), Math.round(b.height)],
                display: cs.display, visibility: cs.visibility, opacity: cs.opacity,
                offsetParent: m.offsetParent ? m.offsetParent.className.slice(0, 40) : null,
                parentChain: (() => {
                    const out = []; let p = m.parentElement;
                    while (p && out.length < 5) { out.push(p.tagName + '.' + String(p.className).slice(0, 30)); p = p.parentElement; }
                    return out;
                })(),
            };
        })"""
    )
    for d in dump:
        print(f"  [{d['i']}] 文本={d['text']!r}")
        print(f"      rect={d['rect']} display={d['display']} vis={d['visibility']} opacity={d['opacity']}")
        print(f"      offsetParent={d['offsetParent']}  父链={d['parentChain']}")

    print()
    print("=== 开始节点（单例）的菜单 ===")
    start_idx = next((i for i, t in enumerate(titles) if t.startswith("开始")), None)
    if start_idx is not None:
        print(f"  菜单：{open_last_menu(start_idx)}")
        page.keyboard.press("Escape")
        page.wait_for_timeout(300)

    print()
    print("=== 删一个非单例节点（兜底回复） ===")
    target = next((i for i, t in enumerate(titles) if "兜底" in t), None)
    print(f"  目标下标：{target}")
    if target is not None:
        n0 = node_count()
        # 打开 ⋮ 菜单，点「删除节点」
        page.locator(NODE).nth(target).locator(".el-dropdown").last.locator("button").first.click()
        page.wait_for_timeout(800)
        page.locator("[class*='el-dropdown-menu'] [class*='el-dropdown-item']:has-text('删除节点')").last.click(
            force=True
        )
        page.wait_for_timeout(1000)
        box = page.locator(".el-message-box")
        print(f"  确认框出现：{box.count() > 0}")
        if box.count():
            page.locator(".el-message-box button", has_text="删除").last.click()
            page.wait_for_timeout(1800)
        n1 = node_count()
        print(f"  节点数 {n0} → {n1}  {'删除生效 ✓' if n1 < n0 else '★ 未生效 ✗'}")

    print()
    print("=== 单例删除被拦（点「删除节点」应弹原因、节点数不变） ===")
    if start_idx is not None:
        n0 = node_count()
        page.locator(NODE).nth(start_idx).locator(".el-dropdown").last.locator("button").first.click()
        page.wait_for_timeout(800)
        page.locator("[class*='el-dropdown-menu'] [class*='el-dropdown-item']:has-text('删除节点')").last.click(
            force=True
        )
        page.wait_for_timeout(1200)
        msgs = [t.strip() for t in page.locator(".el-message").all_inner_texts()]
        n1 = node_count()
        print(f"  提示：{msgs}")
        print(f"  节点数 {n0} → {n1}  {'被拦 ✓' if n1 == n0 else '★ 被删了 ✗'}")

    page.screenshot(path=str(__import__("pathlib").Path(__file__).parent / "screenshots" / "node-menu.png"))

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
