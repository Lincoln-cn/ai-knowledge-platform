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
import re
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

# ── 顶栏期望表的**源码对照物** ────────────────────────────────────────────────
# 期望表是手写的，菜单是 `routes.ts` 里的 `navGroups`。两者不一致时，
# 过去的做法是"人眼对齐"，结果漏了两次（`#346`）。现在读源码来比对。
ROUTES_TS = ROOT / "src" / "router" / "routes.ts"
# 顶栏第一项是首页（`overview`），它不在 `navGroups` 里 —— 比对时单独剔除。
HOME_LABEL = "工作台"


def nav_group_labels() -> list[str]:
    """从 `routes.ts` 的 `navGroups` 读出全部菜单 label（保持声明顺序）。

    ★ 只认 `path: '...', label: '...'` 这种同一行的写法 —— `navGroups` 里每一项都是
    这么写的（见 `routes.ts` 的 `NavItem`）。找不到 `navGroups` 段时返回空表，
    由调用方把"读不到"**报成红**（空表绝不能等价于"没问题"）。
    """
    try:
        src = ROUTES_TS.read_text(encoding="utf-8")
    except OSError:
        return []
    # 数组字面量从 `export const navGroups` 开始，到行首的 `]` 结束（子数组的 `]` 有缩进）
    m = re.search(r"export const navGroups[^\[]*\[(.*?)^\]", src, re.S | re.M)
    if not m:
        return []
    # ★ 只匹配**行首是 `{`** 的真正条目：注释行（`//`）里写的样例字面量不算菜单项。
    #   这条是踩出来的 —— 第一次实现时把 `routes.ts` 注释里的一句
    #   "安全围栏 … { path: '/settings/guard', label: '安全围栏' }" 当成了真菜单项，
    #   于是自检报"navGroups 有、NAV 没有"（`#265` 的同一课：注释不是代码）。
    return [
        label
        for _, label in re.findall(
            r"^\s*\{\s*path:\s*'([^']+)',\s*label:\s*'([^']+)'", m.group(1), re.M
        )
    ]

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
    # ★ 2026-11-16 由 `assistant:view` 收紧到 `assistant:edit`（ROADMAP §11.52.9）：
    #   这页唯一的动作是"从模板建一个新应用"，那需要写权限；只读用户进来什么都做不了。
    ("/templates", ["admin", "liwei"]),
    ("/assistants/prompts", ["admin", "liwei"]),
    ("/models", ["admin"]),
    ("/analytics", ["admin", "zhangyun", "liwei"]),
    ("/feedback", ["admin", "zhangyun"]),
    ("/admin", ["admin"]),
    ("/org", ["admin"]),
    ("/settings/chat", ["admin"]),
    ("/conversation", ["admin", "zhangyun", "liwei", "chenjun"]),
    # ★ 2026-11-01 补三条：A7 的"按钮不可见"在这三页由**路由门禁**承担（页面内没有第二个门），
    #   所以它们必须出现在这张路由矩阵里 —— 否则"进不去"这件事从来没有被量过。
    #   `/basedata` 的入口权限点就是 `platform:dict:manage`（`pages/basedata/route.ts` 的注释写明理由：
    #   读接口登录即可，但那不构成"能进维护页"的理由）。
    ("/basedata", ["admin"]),
    ("/audit", ["admin"]),
    ("/settings/providers", ["admin"]),
    # ★ 2026-11-16 补四行（`#346`）：这四条路由此前进了菜单、却**不在路由矩阵里**，
    #   也就是说"普通人能不能直接敲 URL 进去"从来没有被量过。
    #   `/complaints` 没有权限点 ⇒ 所有登录用户都能进（法规要求"便捷入口"）。
    ("/complaints", ["admin", "zhangyun", "liwei", "chenjun"]),
    ("/compliance", ["admin"]),
    # 这两条今天都是 ADMIN 独有：检索引擎 `platform:retrieval:manage`、
    # 长期记忆借 `conversation:settings`（拆出"我的记忆"页之前，普通用户不该进来）。
    ("/settings/retrieval", ["admin"]),
    ("/settings/memory", ["admin"]),
    # 安全围栏（ROADMAP §11.53）：读也守 `platform:guard:manage`
    ("/settings/guard", ["admin"]),
    # 拦截记录：与安全围栏同一颗权限点 —— 能改规则的人才能读"拦到了什么"
    # （规则表本身就是要收紧的清单，拦截记录反过来会暴露用户问了什么）
    ("/guard-hits", ["admin"]),
    # 记忆管理：记忆的**数据**侧。与「长期记忆」（规则侧）同一颗 `conversation:settings`
    # 权限点 —— 能改记忆规则的人才该能逐条改/删记忆（记忆是喂给模型的上下文）。
    ("/memories", ["admin"]),
]

# ── A7 写入口矩阵（按钮侧）──
#
# 每一行 = 一个**界面写入口**：路由、按钮文案、守它的权限点、哪些账号该看见、检查哪些账号、是否精确匹配。
# 与 `MATRIX`（路由层）配对读：路由层回答"进不进的去"，这张表回答"进去了能不能动手"。
# 一个门禁借用语义不相关的权限点，只有把入口逐条摊开才会在读数上露出来。
WRITE_ENTRIES = [
    # 路由, 名称, 按钮文案, 权限点, 应可见账号, 只检查这些账号（None = 四个角色全跑）, 精确匹配
    ("/assistants", "应用管理 · 新建应用", "新建应用", "assistant:edit", {"admin", "liwei"}, None, True),
    ("/knowledge", "知识库 · 创建知识库", "创建知识库", "knowledge:create", {"admin", "zhangyun"}, None, True),
    ("/knowledge?kb=kb4", "知识库详情 · 上传文档", "+ 上传文档",
     "knowledge:document:upload", {"admin", "zhangyun"}, None, True),
    ("/knowledge?kb=kb4&tab=segments", "知识库详情 · 全部重新向量化", "全部重新向量化",
     "knowledge:segment:edit", {"admin", "zhangyun"}, None, True),
    ("/knowledge?kb=kb4&tab=questions", "知识库详情 · 新建问答对", "新建问答对",
     "knowledge:qa:edit", {"admin", "zhangyun"}, None, True),
    ("/knowledge?kb=kb4&tab=settings", "知识库详情 · 保存设置", "保存",
     "knowledge:manage", {"admin", "zhangyun"}, None, True),
    # 列表卡片上的「删除」是**破坏性动作**：它连文档与分段一起删，且不可恢复。
    # 这一条此前没有入口被钉住（只在 SettingsPanel 里有一份同权限的删除按钮），
    # 所以"谁不该看到删除"一直是靠 `!consumer` 这个**角色判断**兜的 —— 角色判断不是权限点。
    ("/knowledge", "知识库卡片 · 删除", "删除", "knowledge:manage", {"admin", "zhangyun"}, None, True),
    # `/org` 与 `/models` 的路由门禁本身就是这两个点 ⇒ 其余角色在路由层被拦（MATRIX 已断）
    # ★ 组织机构用「新增根部门」而不是「新增下级」：后者要先**选中一个节点**才渲染
    #   （`pages/org/Index.vue:75` 在 `v-if="selected"` 的模板里），刚进页面必然 0 个 ——
    #   那样量的是"有没有选中"，不是"有没有权限"。
    ("/org", "组织机构 · 新增根部门", "新增根部门", "identity:org:manage", {"admin"}, {"admin"}, True),
    ("/models", "模型管理 · 添加模型", "添加模型", "model:manage", {"admin"}, {"admin"}, True),
    # ★ 2026-11-01：A7 台账里一直缺的三处，前两处补在这里、第三处由上面的路由矩阵承担。
    #
    # 一、知识库「停用」不是按钮而是 `el-switch`（`pages/knowledge/Index.vue:96` 的
    #     `v-if="!consumer && canManage"`）—— 文案里那个"停用"是确认框标题，不是可点元素。
    #     所以这一行用**选择器形态**（`sel:` 前缀），选择器是 `data-testid`：
    #     ★ 不按组件库类名选（`.el-switch`），R58 的教训是"选择器与组件库真实类名不一致 ⇒ 恒 0 命中"，
    #     那种红/绿都读不出权限问题。`data-testid` 是我们自己写下的契约，组件库升级不会动它。
    #
    # 二、反馈「批量改状态」/「批量指派」由 `canHandle = session.can(PERM.feedbackHandle)` 守着
    #     （`pages/feedback/Index.vue:60` 的 `<template v-if="canHandle">`）。这一页能进的只有
    #     管理员与知识运营（路由矩阵已断），所以按钮侧量的是"这两个账号里它确实渲染"。
    ("/knowledge", "知识库卡片 · 停用开关", "sel:[data-testid=kb-toggle-enabled]",
     "knowledge:manage", {"admin", "zhangyun"}, None, True),
    ("/feedback", "反馈处理 · 批量改状态", "批量改状态",
     "feedback:handle", {"admin", "zhangyun"}, {"admin", "zhangyun"}, True),
]

# ── 顶栏期望（同一矩阵的另一面：菜单不该出现进不去的项）──
# ⚠️ 首项是产品首页的菜单名（`工作台`，路由 `overview`）。
# ⚠️ 07 §五 之后，顶栏多了一维「数据范围」：`USER`（范围 SHARED）看不到「知识库」
#    （知识库只在 OWN / ALL 下有页面形态），也看不到「应用管理」（同理）。
#    应用市场（/market）登录即可进，所有人可见。
# ⚠️⚠️ 这张表是**手写的**，而顶栏断言是 `sorted(got) == sorted(want)` **严格相等**
#    ⇒ 菜单加一项而这里没加，闸门就该红。但 2026-11-16 之前它红了没人管：
#    「检索引擎」与「长期记忆」两项在 `routes.ts` 里登记了、`/settings/retrieval`
#    的注释还写着"只注册了路由、没有菜单入口"，可**这里始终只有 17 项**（`#346`）。
#    ⇒ 现在 `shape_problems()` 多了一条断言 ⑧：NAV[admin] 与 `routes.ts` 的
#    `navGroups` 必须**双向一一对应**，不再靠人眼对齐（`#346` 判据 3）。
#
# ★ 2026-11-16 菜单重组（ROADMAP §11.52）：旧名 → 新名
#   模板中心→应用模板 · 服务商配置→模型服务商→服务商 · 模型管理→模型 · 对话设置→通用问答 ·
#   系统管理→用户角色 · 合规设置→合规备案
NAV = {
    "admin": [
        "工作台",
        # ── 对话与知识（7）──
        "对话", "知识库", "应用市场", "应用管理", "应用模板", "提示词",
        # 「记忆管理」是记忆的**数据**侧（逐条记忆、画像、分层计数），
        # 规则侧在「模型与检索 → 长期记忆」。两页同源权限 `conversation:settings`。
        "记忆管理",
        # ── 模型与检索（6）──
        "服务商", "模型", "通用问答", "检索引擎", "长期记忆", "安全围栏",
        # ── 组织与权限（3）──
        "用户角色", "组织机构", "基础数据",
        # ── 运营与合规（6）──
        "运营看板", "反馈处理", "投诉举报", "拦截记录", "审计记录", "合规备案",
    ],
    # ★ 「投诉举报」对**所有登录用户**可见（法规要求"便捷入口"；`complaint:handle`
    #   只管"能不能办理"，答不了"有没有入口"）⇒ 四个账号的期望矩阵都含它。
    #
    # ★ 2026-11-16 三处权限收紧（ROADMAP §11.52.9）后的复算结果：
    #   · 「应用模板」由 `assistant:view` 收紧到 `assistant:edit` ⇒ 知识运营看不到它了
    #     （原来看得见、进去什么都做不了）
    #   · 「检索引擎」由 `knowledge:view` 收紧到 `platform:retrieval:manage`
    #   · 「长期记忆」由 `conversation:view` 收紧到 `conversation:settings`
    #   这两条是 `#346` 的第二半：普通消费者不该在顶栏看到管理旋钮。
    "zhangyun": [
        "工作台", "对话", "知识库", "应用市场", "应用管理",
        "运营看板", "反馈处理", "投诉举报",
    ],
    "liwei": [
        "工作台", "对话", "知识库", "应用市场", "应用管理", "应用模板", "提示词",
        "运营看板", "投诉举报",
    ],
    # ★ 普通用户只剩四项：他是"从应用中心选一个应用来用"的人，
    #   顶栏对他应该只回答两件事 —— 去哪聊、去哪看（ROADMAP §11.52.10 判据 6）。
    "chenjun": ["工作台", "对话", "应用市场", "投诉举报"],
}

results: list[tuple[bool, str, str]] = []


def check(ok: bool, label: str, detail: str = "") -> None:
    results.append((bool(ok), label, str(detail)))


class Silent(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def shape_problems() -> list[str]:
    """不启浏览器，只看期望表本身是否还自洽（`#332` 判据 2）。

    ★ 这道闸门读的是**手写的期望表**（`MATRIX` / `NAV`），不是源码 ——
    所以它的「形状失效」不是正则对不上，而是：表内部自相矛盾，
    或表与实际菜单的对应关系已经没人维护。
    下面三条都是真出过的：`NAV` 里写了菜名但 `MATRIX` 里没有那条路由（反之亦然）、
    同一角色同一菜名写两遍（复制粘贴留下的）、某条路由没有任何角色能进（写了等于没写）。
    """
    problems: list[str] = []

    seen_routes: set[str] = set()
    for route, who in MATRIX:
        if route in seen_routes:
            problems.append(f"MATRIX 里 {route} 写了不止一次")
        seen_routes.add(route)
        if not who:
            problems.append(f"MATRIX 里 {route} 没有任何角色能进 —— 这条断言永远不会失败")

    for role, items in NAV.items():
        if not items:
            problems.append(f"NAV[{role}] 是空表")
        dup = [x for x in set(items) if items.count(x) > 1]
        if dup:
            problems.append(f"NAV[{role}] 有重复项：{dup}")

    unknown = set(NAV) - {u for _, who in MATRIX for u in who}
    if unknown:
        problems.append(f"NAV 里有 MATRIX 中不存在的角色：{sorted(unknown)}")

    # ── ⑧ NAV ↔ navGroups 双向一一对应（`#346`）──────────────────────────────
    # ★ 为什么必须双向：
    #   「navGroups 有、NAV 没有」= 加了菜单没改期望表 —— 今天这三处老账的形状；
    #   「NAV 有、navGroups 没有」= 改名或删菜单没改期望表 —— 下一个人会被误导。
    #   单向检查只能抓到一半，而**抓到一半的断言会让下一次改错看起来是好的**。
    labels = nav_group_labels()
    if not labels:
        # 读不到就必须报红：否则这条断言变成恒真，比没有更糟。
        problems.append("读不到 src/router/routes.ts 的 navGroups —— 断言 ⑧ 变成恒真，等于没写")
    else:
        admin_items = [x for x in NAV.get("admin", []) if x != HOME_LABEL]
        missing = [x for x in labels if x not in admin_items]
        extra = [x for x in admin_items if x not in labels]
        if missing:
            problems.append(f"navGroups 里有、NAV[admin] 里没有：{missing}（加菜单忘了改期望表）")
        if extra:
            problems.append(f"NAV[admin] 里有、navGroups 里没有：{extra}（改名或删菜单忘了改期望表）")
        dup_label = sorted({x for x in labels if labels.count(x) > 1})
        if dup_label:
            problems.append(f"navGroups 里有重复的 label：{dup_label}")

        # ── ⑨ 每个菜名至少有一个角色能看见 ──
        # 写了但谁都看不见 = 这条期望永远不会被验证（与 `MATRIX` 的"没有角色能进"同族）。
        for label in labels:
            if not any(label in items for items in NAV.values()):
                problems.append(f"菜名「{label}」不在任何角色的期望矩阵里 —— 谁登录都看不见")

    return problems


def self_check() -> int:
    """`--self-check`：不启浏览器，只验期望表自洽 + 一条「形状坏了会被抓到」的反证。"""
    problems = shape_problems()
    if problems:
        for p in problems:
            print(f"✗ {p}")
        print(f"自检失败：期望表自身不自洽（{len(problems)} 处）")
        return 1
    print(f"✓ 期望表自洽：MATRIX {len(MATRIX)} 条路由 · NAV {len(NAV)} 个角色")

    # ★ 反证：把一个角色的菜名复制一份，shape_problems 必须抓到。
    #   没有这一步，「自洽」只证明"现在没坏"，不证明"坏了会报"。
    sample_role = next(iter(NAV))
    NAV[sample_role] = [*NAV[sample_role], NAV[sample_role][0]]
    try:
        caught = shape_problems()
    finally:
        NAV[sample_role] = NAV[sample_role][:-1]
    if not caught:
        print("✗ 反证失败：故意塞了重复菜名，shape_problems 却没报")
        return 1
    print(f"✓ 反证：塞重复菜名后如期报出「{caught[0]}」")
    return 0


def main() -> int:
    if "--self-check" in sys.argv:
        return self_check()

    base = f"http://127.0.0.1:{PORT}/"
    srv = None

    if "--base" in sys.argv:
        base = sys.argv[sys.argv.index("--base") + 1]
        if not base.endswith("/"):
            base += "/"
    else:
        # ★ `#332`：dist 不存在是「读不懂被检查的对象」，不是「权限配错了」。
        #   以前这种情形会起一个空目录服务、然后在登录页上一路红到底，
        #   维护者会去查权限点，而真因是没跑 `npm run build`。
        if not (ROOT / "dist" / "index.html").exists():
            print(f"✗ 闸门自身失效：{ROOT / 'dist' / 'index.html'} 不存在 —— 还没有可检查的构建产物。")
            print("  这不是「检查不通过」，是「我读不懂被检查的对象了」。先跑 npm run build，")
            print("  或者用 --base 指向已起的 dev server。")
            return 3

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

                # ── A7 写入口矩阵（按钮侧）──
                #
                # 与 MATRIX 同一条纪律：**期望列独立写下**（真源 = 后端 `identity/domain/Permissions.of(...)`
                # 与 `docs/前端页面与功能清单.md` 的权限点归属），**不从界面里那句 `session.can(...)` 推导** ——
                # 从界面推导的话，"借错了一颗别的点"和"借了一颗谁都不持有的点"都会跟着一起绿
                # （后者 2026-10-28 真发生过：`knowledge:qa:edit` / `conversation:settings` 没人持有，
                #  两个写入口对包括管理员在内的所有人隐形，见 `frontend/check_permission_catalog.py`）。
                #
                # 两种形态：
                #   shown —— 有权限就渲染、没权限就不渲染（数按钮）
                #   route —— 只在"有权限的人"身上断（其余角色在**路由层**就被拦，MATRIX 已经断过；
                #            在这里再断一句"看不见"是恒真的废话，会白白撑大分母）
                for route, label, button_text, permission, allowed, only_roles, exact in WRITE_ENTRIES:
                    if only_roles is not None and username not in only_roles:
                        continue
                    # 普通用户对知识库**详情**根本不展开（`pages/knowledge/Index.vue` 的 `openedKb` 对
                    # consumer 恒 null），此时"没按钮"是一句恒真的废话 ⇒ 显式 SKIP 而不是 check(True)。
                    if "kb=kb4" in route and username == "chenjun":
                        print(f"  SKIP  {label}（普通用户是共享档，不展开知识库详情）")
                        continue
                    try:
                        page.goto(base + "#" + route, wait_until="networkidle")
                        page.wait_for_timeout(1800 if "kb=kb4" in route else 1400)
                        if button_text.startswith("sel:"):
                            # ★ 选择器形态：给"不是按钮、没有文案"的写入口用（如 el-switch）。
                            #   选择器一律是 `data-testid`，理由见 WRITE_ENTRIES 里那条注释。
                            actual = page.locator(button_text[4:]).count()
                        elif exact:
                            # ★ 精确匹配可及名称：`tab=settings` 那张表单里还有一颗「保存检索参数」，
                            #   而它是**恒在**的（没有 v-if）—— 用包含匹配会把"没权限"读成"有权限"。
                            actual = page.get_by_role("button", name=button_text, exact=True).count()
                        else:
                            actual = page.locator("button", has_text=button_text).count()
                        expected_visible = username in allowed
                        check(
                            (actual > 0) == expected_visible,
                            f"{role_name} ▸ {label}（{permission}）应{'可见' if expected_visible else '不可见'}",
                            f"实得 {actual} 个",
                        )
                    except Exception as exc:  # noqa: BLE001
                        # 页面打不开也要留一行读数：否则"没跑"和"没权限"在日志里长得一样
                        check(False, f"{role_name} ▸ {label}",
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
