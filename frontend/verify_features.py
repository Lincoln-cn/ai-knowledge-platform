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
                check("类型" in body and ("配置" in body or "内容" in body),
                      "提示词：类型与配置合一（7 合 1）", "")

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

            step("知识库列表", kb_list)
            step("知识库四步向导", kb_wizard)
            step("命中测试", kb_hittest)
            step("应用管理", assistants)
            step("提示词 7 合 1", prompts)
            step("会话渲染", conversation_render)
            step("会话流式", conversation_stream)
            step("会话空应用无引用区块", conversation_no_ref_block)
            step("反馈处理", feedback)
            step("运营看板", analytics)
            step("账号与权限", admin)
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
