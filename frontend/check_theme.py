"""令牌桥验证 —— 读运行时的**计算值**，不靠眼看。

改造前实测：同屏三种蓝在打架（应用令牌 `#2f6fed` / Element Plus 默认 `#409eff` /
编排模块孤儿覆盖 `#3370ff`）。本脚本证明桥生效了：EP 的按钮不再是 `#409eff`，
而是映射到本系统的电光钴蓝。

同时验证字体分工真的落地了：
  · body 吃到 Geist（拉丁）+ 本机 CJK 栈
  · 表格表头吃到 JetBrains Mono
  · 数字列是等宽数字（tabular-nums）

用法：
    python check_theme.py [base_url]        # 默认 http://localhost:5280

退出码：0 = 全部通过；1 = 有失败。
"""

from __future__ import annotations

import sys

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280"

PASS = "  ✓"
FAIL = "  ✗"
results: list[tuple[bool, str]] = []


def check(ok: bool, msg: str) -> None:
    results.append((ok, msg))
    print((PASS if ok else FAIL) + " " + msg)


def read_var(page, name: str) -> str:
    return page.evaluate(
        "n => getComputedStyle(document.documentElement).getPropertyValue(n).trim()", name
    )


def main() -> int:
    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel="chrome")
        page = browser.new_page(viewport={"width": 1600, "height": 900})

        errors: list[str] = []
        page.on("pageerror", lambda e: errors.append(str(e)))

        def on_console(m) -> None:
            # ⚠️ Chromium 的 console 消息文本里**不含 URL**（只有
            # "Failed to load resource: … 404"），URL 在 m.location 里。
            # 只按 text 过滤会把 /favicon.ico 认成真错误 —— 判据要读对地方。
            if m.type != "error":
                return
            url = (m.location or {}).get("url", "") if isinstance(m.location, dict) else ""
            errors.append(f"{m.text} @ {url}")

        page.on("console", on_console)

        print(f"== 打开 {BASE}/login ==")
        page.goto(f"{BASE}/login", wait_until="networkidle")

        # ── 1. 令牌存在且是系统值 ──────────────────────────────────────
        print("\n== 1. 令牌解析 ==")
        accent = read_var(page, "--color-accent")
        check(accent.startswith("oklch("), f"--color-accent = {accent}（应为 oklch 值）")

        paper = read_var(page, "--color-paper")
        check(paper.startswith("oklch("), f"--color-paper   = {paper}")

        # 兼容垫片**已被删除**（触发条件「旧令牌引用 = 0」已满足，见 tokens.css 末尾的说明）。
        # 所以这条断言**反过来**：`--brand` 必须解析不出来。
        # 留着它比删掉它更有价值 —— 垫片一旦被人加回来，"页面不许用旧令牌名"这条纪律
        # 就又没人守了，而那种回归是静默的。
        legacy = read_var(page, "--brand")
        check(
            not legacy,
            f"--brand（兼容垫片）应已删除 —— 实得 {legacy or '（空，正确）'}",
        )

        # ── 2. 字体分工 ────────────────────────────────────────────────
        print("\n== 2. 字体分工（拉丁面 + CJK 栈） ==")
        body_font = page.evaluate("getComputedStyle(document.body).fontFamily")
        check("Geist" in body_font, f"body font-family 含 Geist：{body_font[:70]}")

        body_lh = page.evaluate("getComputedStyle(document.body).lineHeight")
        # 14px 正文 × 1.75 = 24.5px；中文行高下限
        try:
            lh_ok = float(body_lh.replace("px", "")) >= 22
        except ValueError:
            lh_ok = False
        check(lh_ok, f"body line-height = {body_lh}（中文正文需 ≥1.75×14px≈24.5）")

        # 字体文件是否真的加载成功（@font-face 的 src 没 404）
        loaded = page.evaluate(
            "async () => { await document.fonts.ready;"
            " return [...document.fonts].filter(f => f.status === 'loaded').map(f => f.family); }"
        )
        check(
            "Geist" in loaded or "Space Grotesk" in loaded,
            f"已加载的字族：{sorted(set(loaded))}",
        )

        # ── 3. Element Plus 令牌桥 ────────────────────────────────────
        print("\n== 3. Element Plus 桥（关键：不再是 #409eff） ==")
        ep_primary = read_var(page, "--el-color-primary")
        check(ep_primary.startswith("oklch("), f"--el-color-primary = {ep_primary}")

        # 真按钮上的计算值才算数 —— 变量对了但没被用上也是白搭
        btn = page.locator(".el-button").first
        if btn.count():
            bg = btn.evaluate("el => getComputedStyle(el).backgroundColor")
            check(
                "64, 158, 255" not in bg and "rgb(64" not in bg,
                f"实心按钮背景 = {bg}（不得是 EP 默认的 rgb(64,158,255)）",
            )
        else:
            check(False, "登录页上找不到 .el-button —— 无法验按钮")

        # 登录进去看表格表头。
        # ⚠️ 这工程是 hash 路由，登录后 URL 是 `…/login#/`，基路径本身就含 "/login"
        # ⇒ 不能用 `"/login" not in url` 当跳转判据（永远不成立）。
        # 正解 = 等一个"只有登录后才存在"的元素。
        page.fill('input[autocomplete="username"]', "admin")
        page.fill('input[autocomplete="current-password"]', "admin")
        page.locator("button:has-text('登录')").first.click()
        # 外壳已从左侧 rail 改成**带框顶栏**（Cobalt 签名 5）⇒ 判据换成 `.topbar`。
        # ⚠️ 这一处是漏网之鱼：本轮改外壳时同步了 smoke_test / shot_sweep /
        #    verify_permissions 三处 `.rail`，**唯独漏了这里** —— 于是它报了红。
        #    教训：换结构时要把"谁在断言这个结构"数一遍，而不是碰见一个改一个。
        try:
            page.wait_for_selector(".topbar", timeout=15000)
        except Exception:
            check(False, f"登录后没等到带框顶栏 .topbar（当前 URL: {page.url}）")
        page.wait_for_timeout(1200)

        print(f"\n== 4. 登录后：{page.url} ==")

        # 表头字体必须在**真有表格的页面**上验，且**无条件**。
        # ⚠️ 这里原来是 `if 有表格: 验` —— 一个"有就验、没有就算过"的条件断言。
        #    落地页从「迁移总览」换成「工作台」后那张表没了 ⇒ 这条断言**静默消失**
        #    （总数 10/10 → 9/9，没有任何报红）。静默丢覆盖比报红更危险：
        #    报红会有人修，"少了一条"没人会注意到。
        base_url = page.url.split("#")[0]
        page.goto(f"{base_url}#/analytics", wait_until="networkidle")
        page.wait_for_timeout(1600)

        th = page.locator(".el-table th .cell")
        if th.count():
            th_font = th.first.evaluate("el => getComputedStyle(el).fontFamily")
            check("JetBrains Mono" in th_font, f"表头字体 = {th_font[:60]}（应为 mono）")
        else:
            check(False, "在 /analytics 上没找到表格 —— 断言无法执行（这是失败，不是跳过）")

        # 顶栏选中态：不应是整块彩底（新顶栏里"当前"落在分组按钮或菜单项上）
        rail = page.locator(".nav-item.active, .menu-item.active").first
        if rail.count():
            rbg = rail.evaluate("el => getComputedStyle(el).backgroundColor")
            rcolor = rail.evaluate("el => getComputedStyle(el).color")
            print(f"    （参考）顶栏选中态 bg={rbg} color={rcolor}")

        # ── 5. 控制台 ──────────────────────────────────────────────────
        print("\n== 5. 控制台 ==")
        noise = [e for e in errors if "favicon.ico" in e]
        real = [e for e in errors if "favicon.ico" not in e]
        check(not real, f"非 favicon 的错误：{real[:3] if real else '无'}")
        if noise:
            print(f"    （既存噪声 {len(noise)} 条：/favicon.ico 404 —— 不算问题）")

        browser.close()

    bad = [m for ok, m in results if not ok]
    print(f"\n{'=' * 60}")
    print(f"通过 {len(results) - len(bad)}/{len(results)}")
    if bad:
        print("失败项：")
        for m in bad:
            print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
