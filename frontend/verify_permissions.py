"""路由级权限门禁校验。

**它回答的问题**：没权限的人，**直接敲 URL** 进得去吗？

这一条和「按钮藏起来了」是两件事。按钮层（`session.can`）只能挡住走界面的人，
拦不住手输地址、收藏夹、或别人发来的链接。所以本脚本一律 **直接 `goto` 目标 URL**，
从不点菜单 —— 点菜单测得是菜单，不是门禁。

用法：
    python verify_permissions.py                    # 起本地服务跑 dist/
    python verify_permissions.py --base http://127.0.0.1:5280/   # 跑已开的 dev server

⚠️ 有一类断言**只能**在 `--base`（dev server）下跑：mock 的接口层门禁要走
`import('/src/api/prompts.ts')` —— 那是 Vite 现场编译 TS 的路径，dist 里没有 `/src/*`。
这类断言在 dist 模式下显式打印 SKIP（不伪装成通过），见 `INTERFACE_LAYER_OK`。
"""
from __future__ import annotations

import functools
import http.server
import os
import socketserver
import sys
import threading
from pathlib import Path

from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent
# ★ 3788-4586 在部分开发机上被 Windows 动态端口排除占掉（Hyper-V/WSL 那一族）
#   => bind() 直接 WSAEACCES/10013，换哪个用户跑都一样。
#   默认值保持 4210（CI 依赖它），但允许用 AIP_GATE_PORT 覆盖，好让本机也能跑：
#     set AIP_GATE_PORT=14205  &&  python smoke_test.py
PORT = int(os.environ.get("AIP_GATE_PORT", "4210"))

# ── 接口层断言是否可跑 ──
# dev server 才有 /src/*.ts（Vite 现场编译）；dist 是打包产物，按路径 import 必 404。
INTERFACE_LAYER_OK = "--base" in sys.argv

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
    ("/market", ["admin", "zhangyun", "liwei", "chenjun"]),
    ("/assistants", ["admin", "zhangyun", "liwei", "chenjun"]),
    ("/assistants/prompts", ["admin", "liwei"]),
    ("/models", ["admin"]),
    ("/analytics", ["admin", "zhangyun", "liwei"]),
    ("/feedback", ["admin", "zhangyun"]),
    ("/admin", ["admin"]),
    ("/conversation", ["admin", "zhangyun", "liwei", "chenjun"]),
]

# ── 顶栏期望（同一矩阵的另一面：菜单不该出现进不去的项）──
# ⚠️ 首项是产品首页的侧栏名。R45 把 `overview` 从「迁移总览」改成「工作台」后，
#    这里不同步就会变成「断言一个不存在的菜单项」⇒ 恒假失败（或改成恒真）。
# ⚠️ 07 §五 之后，顶栏多了一维「数据范围」：`USER`（范围 shared）看不到「知识库」
#    （知识库只在 own / all 下有页面形态），也看不到「应用管理」（同理）。
#    应用市场（/market）登录即可进，所有人可见；应用管理（/assistants）只在 own / all 有形态。
NAV = {
    "admin": [
        "工作台", "对话", "知识库", "应用市场", "应用管理", "提示词",
        "模型管理", "运营看板", "反馈处理",
        # ★ 2026-10-19 服务商配置 / 基础数据 / 组织机构从「系统管理」拆成独立菜单，
        #   这条期望矩阵必须同步；否则门禁会把"菜单按规则搬对了"报成红。
        "基础数据", "服务商配置", "组织机构", "系统管理",
    ],
    "zhangyun": ["工作台", "对话", "知识库", "应用市场", "应用管理", "运营看板", "反馈处理"],
    "liwei": ["工作台", "对话", "知识库", "应用市场", "应用管理", "提示词", "运营看板"],
    "chenjun": ["工作台", "对话", "应用市场"],
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
                page.wait_for_selector("input[autocomplete='username']", timeout=15000)
                page.fill("input[autocomplete='username']", username)
                page.fill("input[autocomplete='current-password']", password)
                page.click("button:has-text('登录')")
                # ★ #14 / #54：等**登录后的壳挂上**，不是固定时长。路由跳转、首屏 chunk、
                #   工作台 h1 是三个不同的信号，缺一个都会让后面的路由检查读到半成品页面。
                try:
                    page.wait_for_url(lambda u: "#/login" not in u, timeout=15000)
                    page.wait_for_selector(".shell", timeout=15000)
                    page.locator("h1", has_text="工作台").first.wait_for(timeout=15000)
                except Exception:  # noqa: BLE001
                    # 失败信息带上现场：只报"壳没挂上"会让人以为是路由问题，
                    # 而真相可能是"值没进模型 / 验证码空 / 接口被拒"——三者处置完全不同
                    evidence = {
                        "url": page.url.split("#")[-1],
                        "user": page.input_value("input[autocomplete='username']"),
                        "pwd_len": len(page.input_value("input[autocomplete='current-password']")),
                        "captcha_inputs": page.locator("input[autocomplete='off']").count(),
                        "toasts": [t.strip() for t in page.locator(".el-message").all_inner_texts()][:3],
                        "form_errors": [
                            t.strip() for t in page.locator(".el-form-item__error").all_inner_texts()
                        ][:3],
                    }
                    check(False, f"{role_name}：登录后壳未挂上", f"现场 {evidence}")
                    # ★ 一个账号登录失败后必须跳出：否则后面 11 条路由都会在未登录页上
                    #   连锁失败，把一个登录问题放大成"整片红"，读数反而更难定位。
                    ctx.close()
                    continue

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

                # ── 顶栏：不该出现进不去的项 ──
                # 外壳已从左侧栏改成**分组式带框顶栏**（Cobalt 签名 5）：
                # 顶层只显示「工作台」+ 三个分组名，各组项在展开后才进 DOM（`v-if`）。
                # 所以要先逐个展开分组再收标签 —— 直接读顶层会把断言变成在量分组名。
                try:
                    page.goto(base + "#/", wait_until="networkidle")
                    page.wait_for_timeout(1400)
                    got: list[str] = []
                    if page.locator(".nav-item", has_text="工作台").count():
                        got.append("工作台")
                    for idx in range(page.locator(".nav-trigger").count()):
                        page.locator(".nav-trigger").nth(idx).click()
                        page.wait_for_timeout(240)
                        for t in page.locator(".menu-label").all_inner_texts():
                            t = t.strip()
                            if t and t not in got:
                                got.append(t)
                        page.keyboard.press("Escape")
                        page.wait_for_timeout(140)
                    want = NAV[username]
                    check(
                        sorted(got) == sorted(want),
                        f"{role_name} ▸ 顶栏只列可访问项",
                        f"实得 {got}",
                    )
                except Exception as exc:  # noqa: BLE001
                    check(False, f"{role_name} ▸ 顶栏", f"{type(exc).__name__}: {str(exc).splitlines()[0][:90]}")

                # ── A7 按钮侧：没有 assistant:edit 的账号看不到「新建应用」──
                # 接口侧已由 AssistantPermissionHttpTest 钉住（USER 建/发布 403）；
                # 这一条钉界面侧：同一页面对四种角色分别断言按钮在/不在 ——
                # 只藏一种角色的按钮，等于另外三种角色还在走"点下去才报错"的老路。
                try:
                    page.goto(base + "#/assistants", wait_until="networkidle")
                    page.wait_for_timeout(1500)
                    new_app = page.locator("button", has_text="新建应用").count()
                    want_new = username in ("admin", "liwei")  # 只有 assistant:edit
                    check(
                        (new_app > 0) == want_new,
                        f"{role_name} ▸ 应用管理「新建应用」按钮{'可见' if want_new else '不可见'}",
                        f"实得 {new_app} 个",
                    )
                except Exception as exc:  # noqa: BLE001
                    check(False, f"{role_name} ▸ 应用管理按钮层",
                          f"{type(exc).__name__}: {str(exc).splitlines()[0][:90]}")

                # ── A7 按钮侧（第二页）：没有 knowledge:create 的账号看不到「创建知识库」──
                # 与上一条同形，但权限点不同（knowledge:create 只给 ADMIN / KB_OPERATOR）。
                # 两页一起看，才能排除"恰好这一页写对了"的巧合。
                try:
                    page.goto(base + "#/knowledge", wait_until="networkidle")
                    page.wait_for_timeout(1500)
                    new_kb = page.locator("button", has_text="创建知识库").count()
                    want_kb = username in ("admin", "zhangyun")
                    check(
                        (new_kb > 0) == want_kb,
                        f"{role_name} ▸ 知识库「创建知识库」按钮{'可见' if want_kb else '不可见'}",
                        f"实得 {new_kb} 个",
                    )
                except Exception as exc:  # noqa: BLE001
                    check(False, f"{role_name} ▸ 知识库按钮层",
                          f"{type(exc).__name__}: {str(exc).splitlines()[0][:90]}")

                # ── A7 按钮侧（第三处）：知识库**详情页**的「+ 上传文档」要 documentUpload ──
                # 与前一处不是同一个权限点（`knowledge:create` vs `knowledge:document:upload`）：
                # 分开断才能排除「所有写入口都被同一颗权限点整体放开/挡住」这种整体性错误 ——
                # 那种错误在只断一处时看起来完全正常。
                # kb4 是 PUBLIC + owner=u1，三种数据范围都看得见；
                # ★ chenjun（USER / SHARED）**不切详情**（`Index.vue:368` 的 `openedKb`
                #   对 consumer 恒为 null）⇒ 对他「没有上传按钮」是恒真句，不能当判据，
                #   显式打印 SKIP 而不是 check(True)。
                try:
                    page.goto(base + "#/knowledge?kb=kb4", wait_until="networkidle")
                    page.wait_for_timeout(1800)
                    detail_open = page.locator(".doc-panel").count() > 0
                    upload = page.locator("button", has_text="上传文档").count()
                    want_upload = username in ("admin", "zhangyun")  # 只有 knowledge:document:upload
                    if username == "chenjun" and not detail_open:
                        print("  SKIP  知识库详情「+ 上传文档」（普通用户是共享档，不切详情，见 openedKb）")
                    else:
                        check(
                            detail_open,
                            f"{role_name} ▸ 知识库详情能打开（kb4）",
                            f"文档面板 {detail_open}",
                        )
                        check(
                            (upload > 0) == want_upload,
                            f"{role_name} ▸ 知识库详情「+ 上传文档」按钮{'可见' if want_upload else '不可见'}",
                            f"实得 {upload} 个",
                        )
                except Exception as exc:  # noqa: BLE001
                    check(False, f"{role_name} ▸ 知识库详情按钮层",
                          f"{type(exc).__name__}: {str(exc).splitlines()[0][:90]}")


                # ── T86 提示词类型收紧：应用配置进得了提示词页，但一个「类型」写入口都没有 ──
                # 本页的门禁没变（仍是 assistant:edit）—— 变的是**类型的归属**：
                # 类型是分类法（契约，归系统管理），提示词是内容（归应用配置）。
                # 判据用元素计数而不是文本：desc 里仍会出现「提示词类型」四个字（指着它不在本页）。
                if username == "liwei":
                    try:
                        page.goto(base + "#/assistants/prompts", wait_until="networkidle")
                        page.wait_for_timeout(1700)
                        new_type = page.locator("button", has_text="新建类型").count()
                        type_panel = page.locator(".h4", has_text="提示词类型").count()
                        type_rows = page.locator("th", has_text="提示词数").count()
                        check(
                            new_type == 0 and type_panel == 0 and type_rows == 0,
                            "应用配置 ▸ 提示词页零个类型写入口",
                            f"新建类型 {new_type} / 类型面板 {type_panel} / 类型表头 {type_rows}",
                        )
                        # 只读引用仍要能用：类别下拉必须列得出 4 个类型，收紧不能把这条断了
                        page.locator(".el-select").first.click()
                        page.wait_for_timeout(700)
                        opts = [t.strip() for t in page.locator(".el-select-dropdown__item").all_inner_texts()]
                        known = ("通用", "合同法务", "制度咨询", "写作辅助")
                        hit = len({t for t in opts if t in known})
                        check(
                            hit == 4,
                            "应用配置 ▸ 类别下拉仍列出 4 个类型（只读引用没断）",
                            f"下拉项 {opts}",
                        )
                    except Exception as exc:  # noqa: BLE001
                        check(False, "应用配置 ▸ 提示词类型收紧", f"{type(exc).__name__}: {str(exc).splitlines()[0][:90]}")

                # ── 接口层：/prompt-configs 的写门禁（与页面门禁同一个权限点 assistant:edit）──
                # 这条**页面级验不了**：没有 assistant:edit 的账号在 /assistants/prompts 上直接落
                # 403，连一个能点的写入口都看不到 —— 只能绕开界面直调接口去验。
                # 手法只在本机 dev（--base）成立：它 import 的是 Vite 现场编译的 TS 模块；
                # dist 里没有 /src/*，所以那里打印 SKIP 而不是 check(True) —— 假的通过比没有更糟。
                if username in ("liwei", "zhangyun"):
                    if not INTERFACE_LAYER_OK:
                        print("  SKIP  接口层 /prompt-configs 写门禁（需 --base dev，dist 里没有 /src/*）")
                    else:
                        try:
                            page.goto(base + "#/assistants", wait_until="networkidle")
                            page.wait_for_timeout(1400)
                            res = page.evaluate(
                                """async () => {
                                  const api = await import('/src/api/prompts.ts');
                                  try {
                                    const c = await api.createPromptConfig({
                                      roleName: '门禁探针_' + Date.now(),
                                      callWord: '探针：仅用于写门禁校验',
                                      typeId: 'pt1',
                                      description: '探针',
                                      icon: '📝'
                                    });
                                    await api.deletePromptConfig(c.id);
                                    return { ok: true };
                                  } catch (e) {
                                    return { ok: false, code: e.code, message: e.message };
                                  }
                                }"""
                            )
                            if username == "liwei":
                                # 正向对照：有 assistant:edit 就必须写得动。
                                # 没有它，下面那条 403 断言可能只是「什么都 403」的恒真句。
                                check(
                                    res.get("ok") is True,
                                    "应用配置 ▸ 接口层可写提示词配置（正向对照）",
                                    str(res),
                                )
                            else:
                                ok = (
                                    res.get("ok") is False
                                    and res.get("code") == 403
                                    and "维护提示词配置" in str(res.get("message", ""))
                                )
                                check(
                                    ok,
                                    "知识运营 ▸ 接口层拒绝写提示词配置（403 + 明确原因）",
                                    str(res),
                                )
                        except Exception as exc:  # noqa: BLE001
                            check(False, f"{role_name} ▸ 接口层 /prompt-configs",
                                  f"{type(exc).__name__}: {str(exc).splitlines()[0][:90]}")

                # ── 接口层：/feedbacks/batch 的写门禁（与页面门禁同一个权限点 feedback:handle）──
                # 页面级验不了：无 feedback:handle 的账号连 /feedback 都进不去，
                # 看不到批量入口 —— 只能绕开界面直调接口去验。
                # 手法只在本机 dev（--base）成立（同上：import 的是 Vite 现场编译 TS）。
                if username in ("liwei", "zhangyun"):
                    if not INTERFACE_LAYER_OK:
                        print("  SKIP  接口层 /feedbacks/batch 写门禁（需 --base dev，dist 里没有 /src/*）")
                    else:
                        try:
                            # 任意已登录页均可直调；用 liwei 进得去的页面作为宿主
                            page.goto(base + "#/assistants", wait_until="networkidle")
                            page.wait_for_timeout(1200)
                            res = page.evaluate(
                                """async () => {
                                  const api = await import('/src/api/feedback.ts');
                                  try {
                                    const r = await api.batchHandleFeedbacks({
                                      ids: ['fb1'],
                                      status: '处理中',
                                      assigneeId: null
                                    });
                                    return { ok: true, accepted: (r.accepted||[]).length, rejected: (r.rejected||[]).length };
                                  } catch (e) {
                                    return { ok: false, code: e.code, message: e.message };
                                  }
                                }"""
                            )
                            if username == "zhangyun":
                                # 正向对照：有 feedback:handle 就必须办得动。
                                # 没有它，下面那条 403 断言可能只是「什么都 403」的恒真句。
                                check(
                                    res.get("ok") is True,
                                    "知识运营 ▸ 接口层可批量办理反馈（正向对照）",
                                    str(res),
                                )
                            else:
                                ok = (
                                    res.get("ok") is False
                                    and res.get("code") == 403
                                    and "feedback:handle" in str(res.get("message", ""))
                                )
                                check(
                                    ok,
                                    "应用配置 ▸ 接口层拒绝批量办理反馈（403 + 明确原因）",
                                    str(res),
                                )
                        except Exception as exc:  # noqa: BLE001
                            check(False, f"{role_name} ▸ 接口层 /feedbacks/batch",
                                  f"{type(exc).__name__}: {str(exc).splitlines()[0][:90]}")

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
