"""全页截图 + 关键几何断言 —— 视觉验收的第三条腿。

技能纪律：「视觉验收是三条腿」= 截图给人看 + 几何给程序 + **自己读图**。
本脚本负责前两条；第三条由人（或编排者）真的打开图片看。

用法：
    python shot_sweep.py [base_url]

产出：`screenshots/sweep-<name>.png`，每张另附一行几何读数。
"""

from __future__ import annotations

import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280"
OUT = Path(__file__).resolve().parent / "screenshots"
OUT.mkdir(exist_ok=True)

# (文件名, 路由, 说明)
PAGES = [
    ("login", "/login", "登录页（深色石墨拍子）"),
    ("overview", "/", "迁移总览"),
    ("conversation", "/conversation", "问答三栅"),
    ("knowledge", "/knowledge", "知识库列表"),
    ("assistants", "/assistants", "应用管理（两档卡片）"),
    ("prompts", "/assistants/prompts", "提示词"),
    ("models", "/models", "模型管理"),
    ("analytics", "/analytics", "运营看板"),
    ("feedback", "/feedback", "反馈处理"),
    ("admin", "/admin", "系统管理"),
    ("workflow", "/workflow/as5", "编排画布"),
    ("not-found", "/no-such-page", "404"),
]


def main() -> int:
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel="chrome")
        page = browser.new_page(viewport={"width": 1600, "height": 900})

        console: list[str] = []
        page.on("pageerror", lambda e: console.append(f"PAGEERROR {e}"))

        def on_console(m) -> None:
            if m.type != "error":
                return
            url = (m.location or {}).get("url", "") if isinstance(m.location, dict) else ""
            console.append(f"{m.text} @ {url}")

        page.on("console", on_console)

        # 登录（hash 路由，判据用「登录后才存在的元素」，不用 URL 串）
        page.goto(f"{BASE}/login", wait_until="networkidle")
        page.fill('input[autocomplete="username"]', "admin")
        page.fill('input[autocomplete="current-password"]', "admin")
        page.locator("button:has-text('登录')").first.click()
        page.wait_for_selector(".rail", timeout=15000)
        page.wait_for_timeout(800)

        base_url = page.url.split("#")[0]

        print(f"{'页面':<12} {'横向溢出':>8} {'纵向滚动':>8} {'折叠':>6}  说明")
        print("-" * 78)

        for name, route, desc in PAGES:
            before = len(console)
            page.goto(f"{base_url}#{route}", wait_until="networkidle")
            page.wait_for_timeout(1400)

            geo = page.evaluate(
                """() => {
                    const de = document.documentElement;
                    const main = document.querySelector('main.main') || document.body;
                    return {
                        hOver: de.scrollWidth - de.clientWidth,
                        vScroll: main.scrollHeight - main.clientHeight,
                        wrapped: main.scrollHeight > main.clientHeight + 4,
                        mainH: main.clientHeight,
                        mainSH: main.scrollHeight,
                    };
                }"""
            )

            page.screenshot(path=str(OUT / f"sweep-{name}.png"))
            new = [c for c in console[before:]]
            flag = ""
            if new:
                flag = f"  ← {len(new)} 条新 console error"
                print(f"{name:<12} {geo['hOver']:>8} {geo['vScroll']:>8} "
                      f"{'是' if geo['wrapped'] else '否':>6}  {desc}{flag}")
                for c in new[:2]:
                    print(f"{'':<12} {c[:100]}")
                continue

            print(f"{name:<12} {geo['hOver']:>8} {geo['vScroll']:>8} "
                  f"{'是' if geo['wrapped'] else '否':>6}  {desc}")

        browser.close()

    print(f"\n截图落在 {OUT}")
    print("★ 第三条腿：**自己打开图看一遍** —— 断言只回答你已经想到要问的问题，")
    print("  图里那个你没问的维度，只有眼睛能发现。")
    real = [c for c in console if "favicon.ico" not in c]
    print(f"\n非 favicon 的 console error：{len(real)} 条")
    for c in real[:8]:
        print("  - " + c[:130])
    return 1 if real else 0


if __name__ == "__main__":
    sys.exit(main())
