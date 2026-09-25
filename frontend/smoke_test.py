"""frontend 冒烟测试。

验的是「冻结的接口契约 + mock 传输层」这条链路真的通：
  路由守卫 → 登录 → 令牌识别 → mock 接口 → 页面渲染 → 退出

每一步独立容错，失败即记录并继续，便于一次跑出全貌。
"""
import functools
import http.server
import socketserver
import threading
from pathlib import Path

from playwright.sync_api import sync_playwright

DIST = Path(__file__).resolve().parent / "dist"
PORT = 4205
BASE = f"http://127.0.0.1:{PORT}/"

Handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(DIST))
Handler.log_message = lambda *a, **k: None


class Quiet(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


srv = Quiet(("127.0.0.1", PORT), Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()

results = []
errors = []


def check(ok, label, detail=""):
    results.append((bool(ok), label, str(detail)))


with sync_playwright() as p:
    browser = p.chromium.launch(channel="chrome")
    page = browser.new_page()

    def on_msg(msg):
        if msg.type == "error" and "Failed to load resource" not in msg.text:
            errors.append(f"[console] {page.url} :: {msg.text}")

    page.on("console", on_msg)
    page.on("pageerror", lambda e: errors.append(f"[pageerror] {page.url} :: {e}"))

    def step(label, fn):
        """跑一步，异常记为 FAIL 而不是中断整个测试"""
        try:
            fn()
        except Exception as exc:  # noqa: BLE001
            check(False, label, f"{type(exc).__name__}: {str(exc).splitlines()[0][:150]}")

    # ── 1. 未登录访问受保护路由 → 跳登录 ──
    def s1():
        page.goto(BASE + "#/", wait_until="networkidle")
        page.wait_for_timeout(700)
        check("#/login" in page.url, "未登录访问受保护路由 → 跳登录", page.url)
        check("账号密码登录" in page.inner_text("body"), "登录页渲染", "")

    # ── 2. 密码错误 → 后端原因直达界面 ──
    def s2():
        page.fill("input[autocomplete='username']", "admin")
        page.fill("input[autocomplete='current-password']", "wrong-password")
        page.click("button:has-text('登录')")
        page.wait_for_timeout(1000)
        alert = page.inner_text(".el-alert") if page.locator(".el-alert").count() else ""
        check("密码不正确" in alert, "登录失败给出可读原因", alert)

    # ── 3. 账号不存在 → 也是可读原因 ──
    def s3():
        page.fill("input[autocomplete='username']", "nobody")
        page.fill("input[autocomplete='current-password']", "whatever")
        page.click("button:has-text('登录')")
        page.wait_for_timeout(1000)
        alert = page.inner_text(".el-alert") if page.locator(".el-alert").count() else ""
        check("不存在" in alert, "账号不存在给出可读原因", alert)

    # ── 4. 正确登录 → 迁移总览 ──
    def s4():
        page.fill("input[autocomplete='username']", "admin")
        page.fill("input[autocomplete='current-password']", "admin")
        page.click("button:has-text('登录')")
        page.wait_for_timeout(1400)
        body = page.inner_text("body")
        check("迁移总览" in body, "登录成功进入迁移总览", page.url)
        check("管理员" in body, "登录态来自 /users/me（mock 按令牌识别）", "")
        rows = page.locator("tbody tr").count()
        check(rows == 9, "迁移总览列出 9 个目标模块", f"实际 {rows} 行")
        rails = page.locator(".rail-item").count()
        check(rails > 0, "侧栏渲染", f"{rails} 项")

    # ── 5. 迁移总览里每个模块的「打开」都必须到得了真实页面 ──
    #     9 个模块全部迁移完成后，占位页机制已无测试对象；
    #     改为校验"总览里没有死链、也没有残留占位页"，这是等价且仍然有效的一条。
    def s5():
        page.goto(BASE + "#/", wait_until="networkidle")
        page.wait_for_timeout(1000)
        hrefs = page.eval_on_selector_all(
            "tbody tr a", "els => els.map(e => e.getAttribute('href'))"
        )
        check(len(hrefs) == 9, "总览列出 9 个可打开模块", f"实际 {len(hrefs)} 个链接")
        bad = []
        for href in hrefs:
            if not href:
                continue
            page.goto(BASE + href, wait_until="networkidle")
            page.wait_for_timeout(850)
            body = page.inner_text("body")
            if "尚未迁移" in body or "页面不存在" in body:
                bad.append(href)
        check(not bad, "总览每个模块都到得了真实页面（无死链/无残留占位）", f"问题链接: {bad}")

    # ── 6. 刷新页面 → 登录态仍在，且已迁移页面正常渲染 ──
    def s6():
        page.goto(BASE + "#/knowledge", wait_until="networkidle")
        page.wait_for_timeout(1000)
        page.reload(wait_until="networkidle")
        page.wait_for_timeout(1300)
        body = page.inner_text("body")
        check("尚未迁移" not in body and "/login" not in page.url,
              "刷新后登录态保持且已迁移页面仍渲染", page.url)

    # ── 7. 退出登录 → 回登录页 ──
    def s7():
        page.locator(".user").click()
        page.wait_for_timeout(500)
        page.locator(".el-dropdown-menu__item", has_text="退出登录").click()
        page.wait_for_timeout(1000)
        check("#/login" in page.url, "退出登录回到登录页", page.url)

    # ── 8. 退出后令牌已清 ──
    def s8():
        page.goto(BASE + "#/", wait_until="networkidle")
        page.wait_for_timeout(700)
        check("#/login" in page.url, "退出后令牌已清，无法再进受保护路由", page.url)

    # ── 9. 未知路由 → 404 而非白屏 ──
    def s9():
        page.goto(BASE + "#/no-such-page", wait_until="networkidle")
        page.wait_for_timeout(700)
        check("404" in page.inner_text("body"), "未知路由显示 404 页", "")

    step("① 路由守卫", s1)
    step("② 密码错误", s2)
    step("③ 账号不存在", s3)
    step("④ 登录成功", s4)
    step("⑤ 未迁移模块", s5)
    step("⑥ 刷新保持登录", s6)
    step("⑦ 退出登录", s7)
    step("⑧ 退出后隔离", s8)
    step("⑨ 未知路由", s9)

    # ── 10~15. 模型管理模块（第一个完成迁移的模块）──
    def relogin():
        page.goto(BASE + "#/login", wait_until="networkidle")
        page.wait_for_timeout(600)
        page.fill("input[autocomplete='username']", "admin")
        page.fill("input[autocomplete='current-password']", "admin")
        page.click("button:has-text('登录')")
        page.wait_for_timeout(1400)

    def s10():
        page.goto(BASE + "#/models", wait_until="networkidle")
        page.wait_for_timeout(1400)
        body = page.inner_text("body")
        check("模型管理" in body, "模型页渲染", page.url)
        cards = page.locator(".model-card").count()
        check(cards == 6, "列出 6 个模型", f"实际 {cards}")
        statuses = page.locator(".model-card .status").all_inner_texts()
        joined = " ".join(statuses)
        check(all(s in joined for s in ["未加载", "加载中", "已加载", "加载失败"]),
              "四种加载状态都有实例", joined.replace("\n", " "))
        check("内置模型不可删除" in body, "内置模型不渲染删除按钮", "")
        check("思考：无" in body and "思考：可开关" in body and "思考：强制开启" in body,
              "思考三态在列表可见", "")

    def s10b():
        # 迁移总览是活表：模型管理应已显示「已完成」
        page.goto(BASE + "#/", wait_until="networkidle")
        page.wait_for_timeout(1000)
        row = page.locator("tbody tr", has_text="模型管理").first
        check("已完成" in row.inner_text(), "迁移总览把模型管理标为已完成", row.inner_text().replace("\n", " "))
        # 回到模型页，后续步骤都基于它
        page.goto(BASE + "#/models", wait_until="networkidle")
        page.wait_for_timeout(1300)

    def s11():
        page.locator(".filters .el-select").first.click()
        page.wait_for_timeout(400)
        page.locator("li.el-select-dropdown__item", has_text="向量模型").click()
        page.wait_for_timeout(1200)
        cards = page.locator(".model-card").count()
        check(cards == 2, "按类型筛选：向量模型 2 个", f"实际 {cards}")
        page.locator(".filters .el-select").first.click()
        page.wait_for_timeout(400)
        page.locator("li.el-select-dropdown__item", has_text="全部类型").click()
        page.wait_for_timeout(1200)

    def s12():
        page.fill(".filters input[placeholder*='搜索模型名称']", "qwen3:8b")
        page.keyboard.press("Enter")
        page.wait_for_timeout(1200)
        cards = page.locator(".model-card").count()
        check(cards == 1, "关键字搜索：qwen3:8b", f"实际 {cards}")
        page.fill(".filters input[placeholder*='搜索模型名称']", "")
        page.keyboard.press("Enter")
        page.wait_for_timeout(1200)

    def s13():
        card = page.locator(".model-card", has_text="bge-m3").first
        check("未加载" in card.inner_text(), "bge-m3 初始为未加载", "")
        card.locator("button", has_text="加载").first.click()
        page.wait_for_timeout(1600)
        check("加载中" in page.locator(".model-card", has_text="bge-m3").first.inner_text(),
              "点加载后进入加载中", "")
        # 页面在有「加载中」时自动轮询，无需手动刷新
        page.wait_for_timeout(7000)
        check("已加载" in page.locator(".model-card", has_text="bge-m3").first.inner_text(),
              "加载中→已加载（自动轮询，无需手动刷新）", "")

    def s14():
        page.locator("button", has_text="添加模型").first.click()
        page.wait_for_timeout(900)
        dlg = page.locator(".el-dialog:visible").first
        dlg.locator(".catalog-item", has_text="deepseek-r1:7b").first.click()
        page.wait_for_timeout(400)
        dlg.locator("button", has_text="自动探测").first.click()
        page.wait_for_timeout(1300)
        dlg_text = dlg.inner_text()
        check("来源：探测" in dlg_text, "新建成时也能探测能力（靠基础模型，不靠 id）", "")
        check("强制开启" in dlg_text, "探测结果回填到思考模式", "")
        page.fill(".el-dialog:visible input[placeholder*='给这个模型起个名字']", "冒烟测试模型")
        page.locator(".el-dialog:visible .el-dialog__footer button:has-text('确定')").click()
        page.wait_for_timeout(1500)
        check("冒烟测试模型" in page.inner_text(".grid"), "新建模型落到列表", "")

    def s15():
        page.locator("button", has_text="添加模型").first.click()
        page.wait_for_timeout(900)
        dlg = page.locator(".el-dialog:visible").first
        dlg.locator(".catalog-item", has_text="glm4:9b").first.click()
        page.wait_for_timeout(400)
        dlg.locator("button", has_text="自动探测").first.click()
        page.wait_for_timeout(1300)
        msg = page.locator(".el-message").first.inner_text() if page.locator(".el-message").count() else ""
        check("无法自动探测" in msg and "手工声明" in msg,
              "探测失败不阻断，且给出手工声明这一步", msg[:70])
        page.locator(".el-dialog:visible .el-dialog__footer button:has-text('取消')").click()
        page.wait_for_timeout(500)

    def s16():
        # 新建的模型是「未加载」，应能删除；内置的不能
        card = page.locator(".model-card", has_text="冒烟测试模型").first
        check(card.locator("button", has_text="删除").count() == 1, "非内置模型可删除", "")
        card.locator("button", has_text="删除").first.click()
        page.wait_for_timeout(500)
        page.locator(".el-message-box__btns button:has-text('删除')").click()
        page.wait_for_timeout(1300)
        check("冒烟测试模型" not in page.inner_text(".grid"), "删除后从列表消失", "")

    step("— 重新登录", relogin)
    step("⑩ 模型管理页面", s10)
    step("⑩b 迁移总览状态", s10b)
    step("⑪ 类型筛选", s11)
    step("⑫ 关键字搜索", s12)
    step("⑬ 加载与自动轮询", s13)
    step("⑭ 新建模型（含探测）", s14)
    step("⑮ 探测失败路径", s15)
    step("⑯ 删除模型", s16)

    browser.close()

srv.shutdown()

print("=" * 78)
fails = 0
for ok, label, detail in results:
    if not ok:
        fails += 1
    print(f"{'PASS' if ok else 'FAIL'}  {label:<42} {detail}")

print("=" * 78)
if errors:
    print(f"浏览器控制台错误 {len(errors)} 条：")
    for e in dict.fromkeys(errors):
        print("   ", e)
else:
    print("浏览器控制台：无 error / pageerror")
print(f"断言 {len(results) - fails}/{len(results)} 通过，控制台错误 {len(set(errors))} 条")
raise SystemExit(1 if (fails or errors) else 0)
