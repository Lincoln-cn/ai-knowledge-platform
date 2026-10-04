"""模块功能点校验。

「路由/页面/接口/mock 是否就位、页面能否打开」是一层校验；
本脚本回答的是更硬的问题：**声称的那些设计要点，界面上真的做了吗？**

每一项都直接对着 DOM 断言文本或元素，不看代码、不看自述。
"""
from __future__ import annotations

import functools
import http.server
import socketserver
import threading
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent
PORT = 4209
BASE = f"http://127.0.0.1:{PORT}/"

results: list[tuple[bool, str, str]] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    results.append((bool(ok), label, str(detail)))


class Silent(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def main() -> int:
    handler = functools.partial(Silent, directory=str(ROOT / "dist"))

    class Quiet(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

    srv = Quiet(("127.0.0.1", PORT), handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(channel="chrome")
            page = browser.new_page()

            def step(label, fn):
                try:
                    fn()
                except Exception as exc:  # noqa: BLE001
                    check(False, label, f"{type(exc).__name__}: {str(exc).splitlines()[0][:120]}")

            # 登录
            page.goto(BASE + "#/login", wait_until="networkidle")
            page.wait_for_timeout(600)
            page.fill("input[autocomplete='username']", "admin")
            page.fill("input[autocomplete='current-password']", "admin")
            page.click("button:has-text('登录')")
            page.wait_for_timeout(1500)

            def goto(path, wait=1600):
                page.goto(BASE + "#" + path, wait_until="networkidle")
                page.wait_for_timeout(wait)
                return page.inner_text("body")
            # ── 知识库：四步向导 ──
            def kb_list():
                body = goto("/knowledge")
                check("知识库" in body, "知识库：页面渲染", "")
                check(page.locator(".el-card, tbody tr").count() > 0, "知识库：有列表内容",
                      f"{page.locator('.el-card, tbody tr').count()} 项")

            def kb_wizard():
                # 点「新建/创建」进入向导
                btn = page.locator("button", has_text="新建")
                if not btn.count():
                    btn = page.locator("button", has_text="创建")
                btn.first.click()
                page.wait_for_timeout(1500)
                steps = page.locator(".el-step").count()
                check(steps == 4, "知识库：创建向导为四步", f"实际 {steps} 个 el-step")

            def kb_hittest():
                body = goto("/knowledge/hittest")
                check("命中" in body, "命中测试：页面渲染", "")
                check("对比" in body, "命中测试：有三模式对比入口", "")

            # ── 应用管理 + 提示词 ──
            def assistants():
                body = goto("/assistants")
                check("配置" in body and "概览" in body, "应用管理：有配置/概览操作", "")

            def prompts():
                body = goto("/assistants/prompts")
                check("提示词" in body, "提示词：页面渲染", "")
                # 提示词页只剩「配置」：类型是分类法（契约），维护入口已搬到系统管理。
                # 用元素计数而不是文本 —— desc 里仍会出现「提示词类型」四个字（说明它不在本页）。
                new_type = page.locator("button", has_text="新建类型").count()
                type_panel = page.locator(".h4", has_text="提示词类型").count()
                check("提示词配置" in body and new_type == 0 and type_panel == 0,
                      "提示词：页面只管配置（类型已归系统管理）",
                      f"新建类型按钮 {new_type} 个 / 类型面板 {type_panel} 个")

            # ── 问答会话 ──
            def conversation_render():
                body = goto("/conversation")
                check("历史会话" in body or "会话" in body, "会话：渲染", "")
                check(page.locator("textarea").count() > 0, "会话：有输入框", "")

            def conversation_stream():
                box = page.locator("textarea").first
                box.fill("付款比例是多少")
                box.press("Enter")
                page.wait_for_timeout(8000)
                # 通用问答（未挂知识库）走 mock 的 withoutRefs 语料
                body = page.inner_text("body")
                check("不依赖知识库的通用问题" in body,
                      "会话：流式回答真的流出来了", "发送 8s 后仍未渲染出回答即为失败")
                check("正在检索并生成" not in body,
                      "会话：已脱离「生成中」状态", "")

            def conversation_no_ref_block():
                # 05 要求：空应用无引用时**不显示**「引用来源」区块。
                # 用元素计数，不用文本 —— 空态文案里也含「引用来源」四个字，文本断言会假阳。
                count = page.locator(".ref-block").count()
                check(count == 0, "会话：空应用无引用时不渲染引用区块",
                      f"实际 .ref-block = {count}")

            # ── 反馈处理 ──
            def feedback():
                body = goto("/feedback")
                check("反馈" in body, "反馈：页面渲染", "")
                check("V0.2" in body, "反馈：标注 V0.2 设计预演", "")
                check("待处理" in body, "反馈：有待处理状态", "")

            def feedback_batch():
                """批量办理入口的真实可用性。

                ⚠️ 不能写成 `"批量" in body` —— 列表页文案里本来就有「批量」字样，
                那是恒真断言。这里锁的是**状态变化**：未勾选时按钮禁用，勾选 2 行后
                变为可点，点进去能提交并给出回执。
                """
                goto("/feedback")
                btn_status = page.locator(".filters button", has_text="批量改状态")
                btn_assign = page.locator(".filters button", has_text="批量指派")
                check(btn_status.count() == 1 and btn_assign.count() == 1,
                      "反馈批量：有批量改状态/批量指派入口",
                      f"改状态 {btn_status.count()} 个 / 指派 {btn_assign.count()} 个")
                if btn_status.count() == 0:
                    return
                # 未勾选 → 必须禁用（证明按钮不是摆设）
                check(btn_status.first.is_disabled(),
                      "反馈批量：未勾选时批量按钮禁用",
                      f"disabled={btn_status.first.is_disabled()}")

                # 勾选 2 行（只点表体行，避开表头全选框）
                boxes = page.locator(".el-table__body tr .el-checkbox")
                n = boxes.count()
                check(n >= 2, "反馈批量：待处理列表至少 2 行可勾选", f"实际 {n} 行")
                if n < 2:
                    return
                boxes.nth(0).click()
                page.wait_for_timeout(180)
                boxes.nth(1).click()
                page.wait_for_timeout(180)
                check(btn_status.first.is_enabled(),
                      "反馈批量：勾选 2 行后批量按钮变为可用",
                      f"disabled={btn_status.first.is_disabled()}")

                # 真提交一条路径：打开对话框 → 办理 → 拿到回执
                btn_status.first.click()
                page.wait_for_timeout(500)
                dlg = page.locator(".el-dialog", has_text="批量改状态")
                check(dlg.count() > 0, "反馈批量：勾选后能打开批量办理对话框", "")
                if not dlg.count():
                    return
                dlg.locator("button", has_text="办理").first.click()
                page.wait_for_timeout(1000)
                receipt_dlg = page.locator(".el-dialog", has_text="批量办理回执")
                body = page.inner_text("body")
                check(receipt_dlg.count() > 0 and "已办成" in body,
                      "反馈批量：提交后给出批量办理回执（逐条结果）",
                      f"回执弹窗 {receipt_dlg.count()} 个")

            # ── 运营看板：质量观测 ──
            def analytics():
                body = goto("/analytics")
                check("首字延迟" in body, "看板：有首字延迟 P50", "")
                check("结构化输出失败率" in body, "看板：有结构化输出失败率", "")
                check("埋点" in body, "看板：失败率标注「只埋点」", "")

            # ── 账号与权限 ──
            def admin():
                body = goto("/admin")
                check("用户管理" in body, "账号权限：有用户管理", "")
                check("角色管理" in body, "账号权限：有角色管理", "")
                check("权限点" in body, "账号权限：有权限点清单", "")
                check("提示词类型" in body, "账号权限：有提示词类型 Tab", "")

            def prompt_types_tab():
                # ?tab=promptTypes 是深链，直接落到第 4 个 Tab。
                # 断言必须**限定在该 Tab 面板内**：其它 Tab 的用户表也在 DOM 里（el-tab-pane 用
                # v-show 惰性渲染），不限定就会把用户行数当类型行数（4 用户 + 4 类型 = 8）。
                body = goto("/admin?tab=promptTypes", wait=2000)
                # 断言「真的切过去了」，不是标签文本在 body 里 —— 四个 Tab 的标签永远都在
                # DOM 中，`"提示词类型" in body` 是恒真断言。要看激活态：is-active 的 Tab 文本，
                # 或 #pane-promptTypes 面板存在（lazy 下未激活则不渲染）。
                active_tab = page.locator(".el-tabs__item.is-active").inner_text().strip()
                check(active_tab == "提示词类型", "账号权限：深链进提示词类型 Tab",
                      f"实际激活 Tab =「{active_tab}」")
                check(page.locator("#pane-promptTypes").count() > 0,
                      "账号权限：提示词类型面板已渲染", "")
                scope = "#pane-promptTypes"
                names = [t.strip() for t in page.locator(f"{scope} tbody tr td:nth-child(2)").all_inner_texts()]
                want = ["通用", "合同法务", "制度咨询", "写作辅助"]
                check(sorted(names) == sorted(want),
                      "账号权限：提示词类型列出 4 个类型", f"实际 {names}")
                acts = [
                    page.locator(f"{scope} button", has_text="新建类型").count(),
                    page.locator(f"{scope} tbody tr button", has_text="编辑").count(),
                    page.locator(f"{scope} tbody tr button", has_text="删除").count(),
                ]
                check(acts[0] > 0 and acts[1] == 4 and acts[2] == 4,
                      "账号权限：提示词类型有增/改/删入口",
                      f"新建 {acts[0]} / 编辑 {acts[1]} / 删除 {acts[2]}")

            # ── 免登录公开页（沉浸布局，需顶层路由）──
            def public_ok():
                body = goto("/public/pub_ok_demo")
                check("外部访客" in body or "免登录" in body, "公开页：免登录沉浸页渲染", "")
                # ⚠️ 负向断言的「被测词」必须真实存在于壳内页面上，否则恒真。
                #    R45 起壳内首页是「工作台」（旧词「迁移总览」已全库消失）。
                check("侧栏" not in body and "工作台" not in body,
                      "公开页：无侧栏（确实挂在顶层而非 shell 下）", "")

            def public_expired():
                body = goto("/public/pub_expired_demo")
                check("过期" in body, "公开页：过期凭证给出可读原因", "")

            def public_revoked():
                body = goto("/public/pub_revoked_demo")
                check("撤销" in body, "公开页：已撤销凭证给出可读原因", "")

            def public_bogus():
                body = goto("/public/no-such-token-xyz")
                # 实际文案是「访问凭证无效」+ 原因 + 下一步（区分访问者与管理员）
                check("无效" in body, "公开页：无效凭证拒绝访问", "")
                check("重新索取" in body or "重新生成" in body,
                      "公开页：无效凭证给出下一步", "")

            # ── 改密页：口令强度口径（issue #7）──
            #
            # ★ 这条断言的判据必须是「**提交前**的表单校验」，不能是「提交后被后端拒」——
            #   后者在只判 `min: 8` 的老代码下也成立，那正是 issue #7 的病：
            #   「12345678」长度够、当天能过前端，提交后才被后端打回。
            # ⇒ 所以这里**不提交**，只填值 + blur，然后读表单自己的校验文案。
            def change_password_policy():
                goto("/change-password")
                inputs = page.locator("input[type='password']")
                check(inputs.count() >= 3, "改密：三个口令输入框都在", f"{inputs.count()} 个")

                def errors() -> str:
                    page.wait_for_timeout(900)
                    return " | ".join(page.locator(".el-form-item__error").all_inner_texts())

                # ① 长度够、但只有数字一类 ⇒ 必须被拦
                inputs.nth(1).fill("12345678")
                inputs.nth(2).fill("12345678")
                inputs.nth(2).blur()
                shown = errors()
                check("至少 3 类" in shown, "改密：弱口令（只有数字一类）在前端就被拦下",
                      f"校验文案 = {shown[:100]}")

                # ② 合格口令不得误报（字符类别够、不含账号名）
                inputs.nth(1).fill("Abc12345!@")
                inputs.nth(2).fill("Abc12345!@")
                inputs.nth(2).blur()
                shown = errors()
                check("至少 3 类" not in shown, "改密：合格口令不误报", f"校验文案 = {shown[:100]}")

                # ③ ★ 含账号名 ⇒ 必须被拦。这一条同时证明**账号名真的取到了**
                #    （`session.user.username` 若为 undefined，这条会静默失效）
                inputs.nth(1).fill("Admin1234!@")
                inputs.nth(2).fill("Admin1234!@")
                inputs.nth(2).blur()
                shown = errors()
                check("账号名" in shown, "改密：含账号名被拦（证明用户名确实取到了）",
                      f"校验文案 = {shown[:100]}")

            step("知识库列表", kb_list)
            step("知识库四步向导", kb_wizard)
            step("命中测试", kb_hittest)
            step("应用管理", assistants)
            step("提示词只管配置", prompts)
            step("会话渲染", conversation_render)
            step("会话流式", conversation_stream)
            step("会话空应用无引用区块", conversation_no_ref_block)
            step("反馈处理", feedback)
            step("反馈批量办理", feedback_batch)
            step("运营看板", analytics)
            step("账号与权限", admin)
            step("账号与权限·提示词类型", prompt_types_tab)
            step("改密页·口令强度口径", change_password_policy)
            step("公开页·有效", public_ok)
            step("公开页·过期", public_expired)
            step("公开页·撤销", public_revoked)
            step("公开页·无效", public_bogus)

            browser.close()
    finally:
        srv.shutdown()

    print("=" * 88)
    fails = 0
    for ok, label, detail in results:
        if not ok:
            fails += 1
        print(f"{'PASS' if ok else 'FAIL'}  {label:<34}{detail}")
    print("=" * 88)
    print(f"功能点断言 {len(results) - fails}/{len(results)} 通过")
    return 1 if fails else 0


if __name__ == "__main__":
    raise SystemExit(main())
