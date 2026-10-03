"""全站页面走查（真栈）—— 回答「哪些页面在浏览器里走不通」。

为什么需要它：既有的闸门各管一段（`check_knowledge_*` 管知识库、`check_models_form` 管模型、
`verify_permissions` 管路由门禁、`smoke_test` 只跑 mock 构建产物），
**没有一个回答"逐页走一遍、哪些页是坏的"**。而用户报问题的方式恰恰是「这个功能用不了」——
所以需要一个能一次跑出全貌的工具。

每一页记录三件事（不看代码、只看运行事实）：
  1. 页面是否渲染出关键内容（特征元素/关键词）
  2. 这一页发出的接口里**失败**的那些（状态 >= 400，含 URL 与状态码）
  3. 控制台/页面错误

★ 断言分两档，避免"把欠账装成红"或"把坏页装成绿"：
  · **必须能用**（`MUST_WORK`）：今天的演示路径，坏了就 FAIL
  · **已知未落地**（`KNOWN_PENDING`）：后端还没实现（各自有周次计划），只记录不判失败 ——
    但**必须真的失败**：如果哪天它不再报失败，说明该更新清单了（否则清单会变成永久黑洞）。

★ 这一版修掉的三个问题（上一版跑不起来）：
  1. `self_tier` 未定义 ⇒ 第一页就 NameError，输出只剩"环境"两行（假绿：退出码非 0 但看着像跑过）
  2. MUST_WORK 的断言实际在看**控制台错误**，报的却是**失败接口**的数字 ⇒ 断言与消息不是一件事
  3. 路由清单不全（漏了 `/knowledge/hittest`、`/change-password`）⇒ 漏页永远不被看见

用法：
    python check_flow_walkthrough.py                  # 默认 http://localhost:5280
    python check_flow_walkthrough.py http://127.0.0.1:5280
"""
from __future__ import annotations

import sys

from playwright.sync_api import sync_playwright

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")

# ── 必须能用（今天的演示路径：后端已实现的那几块）──
MUST_WORK: list[tuple[str, str, str]] = [
    ("/knowledge", "知识库列表", "知识库"),
    ("/models", "模型管理", "模型"),
    ("/admin", "系统管理", "用户"),
    # 改密页在加载时不发请求（表单要用户填），但它的接口 PUT /users/me/password 已实现 ⇒ 归入"必须能用"
    ("/change-password", "修改密码", "密码"),
]

# ── 已知后端未落地：**加载时就会报失败**（各自有周次计划，只记录不判失败）──
KNOWN_PENDING: list[tuple[str, str, str]] = [
    ("/", "工作台", "工作台"),
    ("/conversation", "对话", "对话"),
    ("/assistants", "应用管理", "应用"),
    ("/assistants/prompts", "提示词", "提示词"),
    ("/market", "应用市场", "应用"),
    ("/analytics", "运营看板", "看板"),
    ("/feedback", "反馈处理", "反馈"),
]

ALL_PAGES = MUST_WORK + KNOWN_PENDING
MUST_ROUTES = {r for r, _, _ in MUST_WORK}

# ── 「页面在、功能未落地」：加载时一声不响，**点主按钮才会报** ──
# ★ 这一档单列的理由：它最容易被误判成"这页是好的"。命中测试页加载零失败，
#   看起来比什么都干净 —— 但检索域要到第 10–11 周才有，点「测试」必然 404。
PAGE_ONLY: list[tuple[str, str, str, str]] = [
    # (路由, 名称, 要点的按钮, 期望失败的接口片段)
    ("/knowledge/hittest", "命中测试", "测试", "retrieval"),
]


def main() -> int:
    ok: list[tuple[bool, str]] = []
    rows: list[tuple[str, str, str, list[str], list[str]]] = []

    def check(cond: bool, msg: str) -> bool:
        # ★ 必须 return：曾经这里返回 None，而调用处写的是 `if not check(...): return 1`
        #   ⇒ 恒真 ⇒ 闸门在"环境"那步就退出了，输出只有两行却看着像"跑过"。
        #   闸门自己的返回值是它最容易骗过人的地方。
        ok.append((bool(cond), msg))
        print(("  [OK] " if cond else "  [NG] ") + msg)
        return bool(cond)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel="chrome")
        page = browser.new_page(viewport={"width": 1600, "height": 950})
        console_errors: list[str] = []
        failed_calls: list[str] = []
        # ★ 走查必须区分「演示账号本来就无权访问」与「后端真的坏了」：
        #   403 是权限门禁按设计工作，不是缺陷。但也不能一律放过 ——
        #   演示路径上的页面若是 403，那说明演示账号的权限配置坏了，仍然要红。
        forbidden_calls: list[str] = []

        def on_console(m) -> None:
            if m.type == "error":
                loc = m.location
                url = loc.get("url", "") if isinstance(loc, dict) else ""
                if "favicon" not in url:
                    console_errors.append(f"{m.text} @ {url}")

        def on_response(r) -> None:
            if "/api/v1" not in r.url:
                return
            path = r.url.split("/api/v1")[-1]
            if r.status == 403:
                forbidden_calls.append(f"{r.status} {path}")
            elif r.status >= 400:
                failed_calls.append(f"{r.status} {path}")

        page.on("console", on_console)
        page.on("pageerror", lambda e: console_errors.append(f"pageerror: {e}"))
        page.on("response", on_response)

        try:
            # ── 登录（真栈：token 不是 mock_ 前缀）──
            page.goto(f"{BASE}/#/login", wait_until="networkidle")
            page.wait_for_timeout(800)
            page.fill("input[autocomplete='username']", "admin")
            page.fill("input[autocomplete='current-password']", "admin")
            page.click("button:has-text('登录')")
            page.wait_for_timeout(2500)
            token = page.evaluate("() => localStorage.getItem('access_token') || ''")
            print("== 环境 ==")
            if not check(bool(token) and not token.startswith("mock_"), "连的是真后端（token 非 mock_）"):
                browser.close()
                return 1
            print(f"  · base = {BASE}")

            # ── 逐页走查（★ 每页独立容错：一页坏了不挡其余，否则"走查"本身会变成单点）──
            print("\n== 逐页走查 ==")
            for route, name, marker in ALL_PAGES:
                tier = "必须能用" if route in MUST_ROUTES else "已知未落地"
                print(f"  ... {route} {name}", flush=True)
                console_errors.clear()
                failed_calls.clear()
                forbidden_calls.clear()
                try:
                    page.goto(f"{BASE}/#{route}", wait_until="domcontentloaded")
                    # ★ 不用 networkidle：分析看板这类页面会持续轮询/请求未实现接口，
                    #   networkidle 可能永远等不到 ⇒ 整页走查卡死在一个页面上
                    page.wait_for_timeout(3000)
                    body = page.inner_text("body")
                    rendered = len(body.strip()) > 60
                    hit = marker in body
                except Exception as exc:  # noqa: BLE001
                    rows.append(
                        (route, name, tier, [f"页面异常：{exc}"], [], [])
                    )
                    print(f"  [NG] {route:<24} {name:<10} 页面异常：{str(exc)[:80]}")
                    continue
                errs = [e[:120] for e in console_errors[:3]]
                rows.append(
                    (route, name, tier, sorted(set(failed_calls)), errs, sorted(set(forbidden_calls)))
                )
                flag = "[OK]" if (hit and rendered) else "[NG]"
                print(
                    f"  {flag} {route:<24} {name:<10} 关键内容={'有' if hit else '无'} "
                    f"失败接口={len(set(failed_calls))} 403={len(set(forbidden_calls))} "
                    f"控制台错误={len(console_errors)}"
                )

            # ── 必须能用的页面：接口与控制台都要干净 ──
            print("\n== 必须能用的页面 ==")
            for route, name, marker in MUST_WORK:
                row = next(r for r in rows if r[0] == route)
                _, _, _, fails, errs, forbids = row
                check(not fails, f"{name}（{route}）没有失败接口 —— 实际：{fails}")
                check(not forbids, f"{name}（{route}）没有 403（演示账号应有全权）—— 实际：{forbids}")
                check(not errs, f"{name}（{route}）控制台无错误 —— 实际：{errs}")

            # ── 已知未落地：必须真的失败，否则清单该更新了 ──
            print("\n== 已知未落地（防止清单变黑洞）==")
            for route, name, _ in KNOWN_PENDING:
                row = next(r for r in rows if r[0] == route)
                if not row[3]:
                    check(False, f"{name}（{route}）已不再报失败 => 请把它从 KNOWN_PENDING 移走")
                else:
                    print(f"  · {name:<10} 仍未落地：{row[3][:3]}")

            # ── 页面在、功能未落地：点主按钮才报（这一档最容易冒充"好页面"）──
            print("\n== 页面在、功能未落地（点一下才现形）==")
            for route, name, action, api_fragment in PAGE_ONLY:
                failed_calls.clear()
                console_errors.clear()
                page.goto(f"{BASE}/#{route}", wait_until="domcontentloaded")
                page.wait_for_timeout(2500)
                try:
                    page.fill("input[placeholder*='测试问题'], input[placeholder*='问题']", "报销标准是什么")
                    page.click(f"button:has-text('{action}')")
                    page.wait_for_timeout(3000)
                except Exception as exc:  # noqa: BLE001
                    check(False, f"{name}（{route}）点「{action}」这一步做不下去：{str(exc)[:60]}")
                    continue
                hit = [f for f in dict.fromkeys(failed_calls) if api_fragment in f]
                if not hit:
                    check(False, f"{name}（{route}）点「{action}」已不再报失败 => 该域可能已落地，请更新本档")
                else:
                    print(f"  · {name:<10} 点「{action}」仍未落地：{hit[:2]}")
        finally:
            print("\n== 明细（失败接口 / 403 / 控制台）==")
            for route, name, tier, fails, errs, forbids in rows:
                print(f"  {route:<24} {name:<10} [{tier}]")
                for f in fails[:6]:
                    print(f"      接口 [NG] {f}")
                for f in forbids[:4]:
                    print(f"      接口 [403] {f}")
                for e in errs:
                    print(f"      控制台 [NG] {e}")
            browser.close()

    bad = [m for c, m in ok if not c]
    print("=" * 68)
    print(f"通过 {len(ok) - len(bad)}/{len(ok)}")
    for m in bad:
        print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
