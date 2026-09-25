"""路由级权限门禁校验。

**它回答的问题**：没权限的人，**直接敲 URL** 进得去吗？

这一条和「按钮藏起来了」是两件事。按钮层（`session.can`）只能挡住走界面的人，
拦不住手输地址、收藏夹、或别人发来的链接。所以本脚本一律 **直接 `goto` 目标 URL**，
从不点菜单 —— 点菜单测得是菜单，不是门禁。

用法：
    python verify_permissions.py                    # 起本地服务跑 dist/
    python verify_permissions.py --base http://127.0.0.1:5280/   # 跑已开的 dev server
"""
from __future__ import annotations

import functools
import http.server
import socketserver
import sys
import threading
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent
PORT = 4210

# ── 账号（取自 mock/fixtures/identity.ts，四个角色各一个）──
#   username, password, 角色名
ACCOUNTS = [
    ("admin", "admin", "管理员"),
    ("zhangyun", "123456", "知识运营"),
    ("liwei", "123456", "应用配置"),
    ("chenjun", "123456", "普通用户"),
]

# ── 期望矩阵：路由 → 允许访问的账号 ──
#   与 pages/*/route.ts 的 meta.permission 对照写成，是独立的一份"期望"，
#   故意不从代码里推导 —— 从代码推导的话，代码错了测试也跟着错。
MATRIX = [
    ("/knowledge", ["admin", "zhangyun", "liwei", "chenjun"]),
    ("/knowledge/hittest", ["admin", "zhangyun", "liwei"]),
    ("/assistants", ["admin", "zhangyun", "liwei", "chenjun"]),
    ("/assistants/prompts", ["admin", "liwei"]),
    ("/models", ["admin"]),
    ("/analytics", ["admin", "zhangyun", "liwei"]),
    ("/feedback", ["admin", "zhangyun"]),
    ("/admin", ["admin"]),
    ("/conversation", ["admin", "zhangyun", "liwei", "chenjun"]),
]

# ── 侧栏期望（同一矩阵的另一面：菜单不该出现进不去的项）──
NAV = {
    "admin": ["迁移总览", "问答", "知识库", "应用管理", "提示词", "模型管理", "运营看板", "反馈处理", "系统管理"],
    "zhangyun": ["迁移总览", "问答", "知识库", "应用管理", "运营看板", "反馈处理"],
    "liwei": ["迁移总览", "问答", "知识库", "应用管理", "提示词", "运营看板"],
    "chenjun": ["迁移总览", "问答", "知识库", "应用管理"],
}

results: list[tuple[bool, str, str]] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    results.append((bool(ok), label, str(detail)))


class Silent(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def main() -> int:
    base = f"http://127.0.0.1:{PORT}/"
    srv = None

    if "--base" in sys.argv:
        base = sys.argv[sys.argv.index("--base") + 1]
        if not base.endswith("/"):
            base += "/"
    else:
        handler = functools.partial(Silent, directory=str(ROOT / "dist"))

        class Quiet(socketserver.ThreadingTCPServer):
            allow_reuse_address = True
            daemon_threads = True

        srv = Quiet(("127.0.0.1", PORT), handler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()

    print(f"目标：{base}\n")

    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(channel="chrome")

            for username, password, role_name in ACCOUNTS:
                allowed = {r for r, who in MATRIX if username in who}
                print(f"── {role_name}（{username}） 应可访问 {len(allowed)}/{len(MATRIX)} 个路由 ──")

                ctx = browser.new_context()
                page = ctx.new_page()

                # 每个账号都从干净 context 开始：localStorage 里不能残留别人的 token
                page.goto(base + "#/login", wait_until="networkidle")
                page.wait_for_timeout(500)
                page.fill("input[autocomplete='username']", username)
                page.fill("input[autocomplete='current-password']", password)
                page.click("button:has-text('登录')")
                page.wait_for_timeout(1600)

                if "#/login" in page.url:
                    check(False, f"{role_name}：登录", f"仍停在 {page.url}")

                for route, _ in MATRIX:
                    try:
                        # ★ 直接敲 URL —— 这才是门禁要挡的入口
                        page.goto(base + "#" + route, wait_until="networkidle")
                        page.wait_for_timeout(1100)
                        url = page.url
                        body = page.inner_text("body")
                        blocked = "#/403" in url
                        says_denied = "没有访问该页面的权限" in body or "无权访问" in body

                        if route in allowed:
                            # 允许 ⇒ 不能落 403，也不能是空白
                            ok = not blocked and len(body.strip()) > 40
                            check(ok, f"{role_name} ▸ {route} 放行", "落 403" if blocked else "")
                        else:
                            # 禁止 ⇒ 必须落 403，且要说清原因
                            ok = blocked and says_denied
                            check(
                                ok,
                                f"{role_name} ▸ {route} 拦截",
                                f"url={url.split('#')[-1] or '/'} 未落 403" if not blocked else ("无原因文案" if not says_denied else ""),
                            )
                    except Exception as exc:  # noqa: BLE001
                        check(False, f"{role_name} ▸ {route}", f"{type(exc).__name__}: {str(exc).splitlines()[0][:90]}")

                # ── 硬刷新：门禁不能只在 SPA 跳转时生效 ──
                # 这条专门测「刷新后权限点还没取回来」的时序 —— 守卫必须等 profile 落地再判。
                forbidden = next((r for r, who in MATRIX if username not in who), None)
                if forbidden:
                    try:
                        page.goto(base + "#" + forbidden, wait_until="networkidle")
                        page.wait_for_timeout(900)
                        page.reload(wait_until="networkidle")
                        page.wait_for_timeout(1600)
                        ok = "#/403" in page.url
                        check(ok, f"{role_name} ▸ 硬刷新 {forbidden} 仍被拦", f"url={page.url.split('#')[-1]}")
                    except Exception as exc:  # noqa: BLE001
                        check(False, f"{role_name} ▸ 硬刷新", f"{type(exc).__name__}: {str(exc).splitlines()[0][:90]}")

                # ── 侧栏：不该出现进不去的项 ──
                try:
                    page.goto(base + "#/", wait_until="networkidle")
                    page.wait_for_timeout(1400)
                    got = [t.strip() for t in page.locator(".rail-item").all_inner_texts() if t.strip()]
                    want = NAV[username]
                    check(
                        sorted(got) == sorted(want),
                        f"{role_name} ▸ 侧栏只列可访问项",
                        f"实得 {got}",
                    )
                except Exception as exc:  # noqa: BLE001
                    check(False, f"{role_name} ▸ 侧栏", f"{type(exc).__name__}: {str(exc).splitlines()[0][:90]}")

                ctx.close()
                print()

            browser.close()
    finally:
        if srv:
            srv.shutdown()

    passed = sum(1 for ok, _, _ in results if ok)
    total = len(results)
    for ok, label, detail in results:
        if not ok:
            print(f"  FAIL  {label}" + (f"    {detail}" if detail else ""))
    print("=" * 88)
    print(f"权限门禁断言 {passed}/{total} 通过")
    return 0 if passed == total else 1


if __name__ == "__main__":
    raise SystemExit(main())
