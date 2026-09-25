"""全页截图 + 关键几何断言 —— 视觉验收的第三条腿。

技能纪律：「视觉验收是三条腿」= 截图给人看 + 几何给程序 + **自己读图**。
本脚本负责前两条；第三条由人（或编排者）真的打开图片看。

用法：
    python shot_sweep.py [base_url]

产出：`screenshots/sweep-<name>.png`，每张另附一行几何读数。

────────────────────────────────────────────────────────────────────────────
为什么每页都要带一个「特征元素」并等它（而不是 `wait_for_timeout`）：

R45 实测，**12 张截图里有 2 张拍的是别的页**，而扫描表把 12 页全报成通过：
  · `sweep-login.png`  与 `sweep-overview.png` **哈希相同** —— 脚本先登录、
    再拍 `#/login`，而登录后访问 `/login` 会被重定向到 `/`，于是"登录页"
    拍到的是工作台。
  · `sweep-workflow.png` 与 `sweep-admin.png` **哈希相同** —— 路由改对了，
    但 hash-only 导航**不改文档** ⇒ `networkidle` 无事可等 ⇒ 固定 1400ms
    在工作流那个 ~1MB 懒加载分块未就绪时拍到**上一页**。

两个真因同源：**脚本从未断言"拍到的页就是我要的页"**。几何读数（溢出/滚动/
折叠）在全 0 的情况下，一张拍错的图和一张正确的图**长得完全一样**。
⇒ 修法两条：**(a)** 变 query 串强制真文档导航（这样 `networkidle` 才有意义）；
**(b)** 每页给一个特征选择器 + 期望标题文字，**等它出现**、再核对文字，
    对不上就计入失败。这与 `check_theme.py` 那条静默失效的断言同一条纪律：
    **断言必须自带目标，找不到被测物即失败。**
"""

from __future__ import annotations

import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280"
OUT = Path(__file__).resolve().parent / "screenshots"
OUT.mkdir(exist_ok=True)

# (文件名, 路由, 说明, 该页特征选择器, 期望标题文字)
# ⚠️ 编排画布的路由是 `/assistants/:appId/workflow`，**不是** `/workflow/:appId`。
#    写成后者会拿到一张 404 页（R45 实测）。
PAGES = [
    ("overview", "/", "工作台（产品首页）", ".page-head", "工作台"),
    ("conversation", "/conversation", "问答三栅", ".chat-page", None),
    ("knowledge", "/knowledge", "知识库列表", ".page-header", "知识库"),
    ("assistants", "/assistants", "应用管理（两档卡片）", ".page-header", "应用管理"),
    ("prompts", "/assistants/prompts", "提示词", ".page-header", "提示词"),
    ("models", "/models", "模型管理", ".page-header", "模型管理"),
    ("analytics", "/analytics", "运营看板", ".page-header", "运营看板"),
    ("feedback", "/feedback", "反馈处理", ".page-header", "反馈处理"),
    ("admin", "/admin", "系统管理", ".page-header", "账号与权限"),
    ("workflow", "/assistants/as5/workflow", "编排画布", ".workflow-page", None),
    ("not-found", "/no-such-page", "404", ".empty-title", "页面不存在"),
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

        bad: list[str] = []

        print(f"{'页面':<12} {'横向溢出':>8} {'纵向滚动':>8} {'折叠':>6}  说明")
        print("-" * 78)

        def shoot(name: str, desc: str, selector: str, expect_text: str | None) -> None:
            """等待特征元素 → 核对标题 → 量几何 → 截图。"""
            before = len(console)

            # (a) 特征元素必须出现；等不到 = 拍到别的页，这是失败而非跳过
            try:
                page.wait_for_selector(selector, timeout=20000)
                landed = True
            except Exception:
                landed = False
            page.wait_for_timeout(1100)

            body = page.inner_text("body")
            landed_error = "页面不存在" in body or "没有访问该页面的权限" in body
            title_ok = expect_text is None or expect_text in body

            geo = page.evaluate(
                """() => {
                    const de = document.documentElement;
                    const main = document.querySelector('main.main') || document.body;
                    return {
                        hOver: de.scrollWidth - de.clientWidth,
                        vScroll: main.scrollHeight - main.clientHeight,
                        wrapped: main.scrollHeight > main.clientHeight + 4,
                    };
                }"""
            )

            page.screenshot(path=str(OUT / f"sweep-{name}.png"))

            problems: list[str] = []
            if not landed:
                problems.append(f"等不到特征元素 {selector}")
                bad.append(f"{name} ({page.url.split('#')[-1]}) 等不到 {selector}")
            if name != "not-found" and landed_error:
                problems.append("★ 落到错误页")
                bad.append(f"{name} 落到错误页")
            if not title_ok:
                problems.append(f"标题里没有「{expect_text}」")
                bad.append(f"{name} 标题不含「{expect_text}」")

            new = [c for c in console[before:]]
            if new:
                problems.append(f"{len(new)} 条新 console error")

            print(
                f"{name:<12} {geo['hOver']:>8} {geo['vScroll']:>8} "
                f"{'是' if geo['wrapped'] else '否':>6}  {desc}"
                + (f"  ← {'；'.join(problems)}" if problems else "")
            )
            for c in new[:2]:
                print(f"{'':<12} {c[:100]}")

        # ── 登录页必须在登录**之前**拍：登录后访问 `/login` 会被重定向到 `/` ──
        page.goto(f"{BASE}/?n=login#/login", wait_until="networkidle")
        shoot("login", "登录页（深色石墨拍子）", "input[autocomplete='username']", "登录")

        # ── 登录（判据用「登录后才存在的元素」，不用 URL 串）──
        page.fill('input[autocomplete="username"]', "admin")
        page.fill('input[autocomplete="current-password"]', "admin")
        page.locator("button:has-text('登录')").first.click()
        page.wait_for_selector(".topbar", timeout=15000)
        page.wait_for_timeout(900)

        base_url = page.url.split("#")[0]

        for i, (name, route, desc, selector, expect_text) in enumerate(PAGES):
            # (b) 变 query 串 ⇒ 强制真文档导航，`networkidle` 才真的等到懒加载分块
            page.goto(f"{base_url}/?n={i}#{route}", wait_until="networkidle")
            shoot(name, desc, selector, expect_text)

        browser.close()

    print(f"\n截图落在 {OUT}")
    print("★ 第三条腿：**自己打开图看一遍** —— 断言只回答你已经想到要问的问题，")
    print("  图里那个你没问的维度，只有眼睛能发现。")
    real = [c for c in console if "favicon.ico" not in c]
    print(f"\n非 favicon 的 console error：{len(real)} 条")
    for c in real[:8]:
        print("  - " + c[:130])
    if bad:
        print(f"\n★ 有 {len(bad)} 页没拍成目标页面（几何读数为 0 也一律算失败）：")
        for b in bad:
            print("  - " + b)
    return 1 if (real or bad) else 0


if __name__ == "__main__":
    sys.exit(main())
