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
import time
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

            def settle(minimum_ms: int = 350) -> None:
                """等页面**稳定**，而不是等一个固定毫秒数（issue #14）。

                ★ 为什么改：本文件原先 **0 处 `wait_for_selector`、全是固定 `wait_for_timeout`** ⇒
                  慢一拍就开始断言。而 CI runner 比开发机慢，于是同一份代码时而 43/43、时而 42/43 ——
                  实测 19 轮里红过 1 轮。**随机红比不红更坏**：报红会有人去改本来没错的东西。

                做法：网络静默 → 没有可见的 loading 遮罩 → 再给一拍让 Vue 渲染落定。
                ⚠️ 不做成"所有等待都换成它"：有几处是**故意**等一个瞬时常态（例如等流式回复
                8 秒那条），换成等稳定反而抓不到那个状态 —— 那些留在原处并注明。
                """
                try:
                    page.wait_for_load_state("networkidle", timeout=8000)
                except Exception:  # noqa: BLE001
                    pass
                for _ in range(40):
                    if page.locator(".el-loading-mask:visible").count() == 0:
                        break
                    page.wait_for_timeout(100)
                page.wait_for_timeout(minimum_ms)

            # 登录
            page.goto(BASE + "#/login", wait_until="networkidle")
            page.wait_for_selector("input[autocomplete='username']", timeout=15000)
            page.fill("input[autocomplete='username']", "admin")
            page.fill("input[autocomplete='current-password']", "admin")
            page.click("button:has-text('登录')")
            settle(800)

            def goto(path, wait=1600):
                page.goto(BASE + "#" + path, wait_until="networkidle")
                settle(wait)
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
                # 等向导真的开出来（原先固定 1500ms：慢一拍就数到 0 个 .el-step）
                try:
                    page.locator(".el-step").first.wait_for(timeout=15000)
                except Exception:  # noqa: BLE001
                    pass
                steps = page.locator(".el-step").count()
                check(steps == 4, "知识库：创建向导为四步", f"实际 {steps} 个 el-step")
                # ★ 关窗再往下走：本文件走 hash 路由（`goto` 是 `#/path`），同行导航**不重载页面**
                #   ⇒ 不关的话这层遮罩会盖住后面每一步的点击（本轮实测：下一步 Locator.click 超时 30s）。
                if page.locator(".el-dialog").count():
                    page.keyboard.press("Escape")
                    try:
                        page.locator(".el-dialog").first.wait_for(state="hidden", timeout=5000)
                    except Exception:  # noqa: BLE001
                        pass

            def kb_category_prefill():
                # ★ Issue #46：点分类筛选 → 再点「创建知识库」/空态「创建第一个知识库」，
                #   向导第 1 步必须回填**当前筛选的分类**。走用户的真实路径（点 tab，而不是往 URL 里塞中文）。
                #   价值全在"它曾经为假过"：旧实现把参数存进一个待用变量、等 `reset()`（只挂 `@closed`）
                #   落值 ⇒ 第一次打开必为空、第二次打开回填的是**上一次**的值（晚一拍）。
                #   ⚠️ 它当初能溜过去，是因为只用 `vue-tsc` 判过"改好了"，而**没有这条断言**。
                def close_wizard():
                    # ★ 必须关窗：本文件走 hash 路由（`goto` 是 `#/path`），同行导航**不重载页面** ⇒
                    #   向导的遮罩会一直盖着，后面每一步的点击都被它吃掉（实测：Locator.click 超时 30s）。
                    if page.locator(".el-dialog").count():
                        page.keyboard.press("Escape")
                        try:
                            page.locator(".el-dialog").first.wait_for(state="hidden", timeout=5000)
                        except Exception:  # noqa: BLE001
                            pass

                def wizard_category():
                    empty = page.locator("button", has_text="创建第一个知识库")
                    target = empty if empty.count() else page.locator("button", has_text="创建知识库")
                    target.first.click()
                    try:
                        page.locator(".el-dialog .el-step").first.wait_for(timeout=15000)
                    except Exception:  # noqa: BLE001
                        pass
                    return page.locator(
                        ".el-dialog .el-form-item:has-text('分类') .el-select"
                    ).first.inner_text()

                close_wizard()
                goto("/knowledge", wait=1800)
                page.locator("button.cat", has_text="制度规范").first.click()
                settle(700)
                shown = wizard_category()
                check("制度规范" in shown,
                      "知识库：打开向导回填当前筛选的分类（Issue #46）",
                      f"分类控件显示 = {shown!r}")
                close_wizard()

                # ★ 反向：换一个分类再打开 ⇒ 必须是**新的**那个（专守"晚一拍"那个方向）
                page.locator("button.cat", has_text="产品资料").first.click()
                settle(700)
                shown2 = wizard_category()
                check("产品资料" in shown2 and "制度规范" not in shown2,
                      "知识库：换筛选后再打开，回填的是新分类（不是上一次的）",
                      f"分类控件显示 = {shown2!r}")
                close_wizard()

            # ── 上传弹窗：责任部门的组织树（**第一次打开**就要有数据）──
            def kb_upload_dialog_org_tree():
                """★ 守的是「数据读取只挂在 `@closed` 的 `reset()` 上」这个坑。

                `UploadDialog` 原先只在 `reset()` 里读组织树，而 `reset()` 挂 `@closed`
                （关窗之后才跑）⇒ **第一次打开必然是一棵空树**，而「责任部门」是必填
                ⇒ 从知识库详情页上传文档，第一次根本选不出部门、提交不上去（真栈闸门实测红）。
                ⚠️ 它当初能溜过去：mock 侧没有任何断言会走到这个弹窗，而真栈那边只报一句
                「能选中…失败」—— 与 `#46` 是同一个坑（那次也是 `reset()` 挂在 `@closed` 上）。
                ★ 反证（把 `loadOrgTree()` 挪回 `reset()` 再构建一次）：
                  `FAIL 上传弹窗：第一次打开时「责任部门」就有部门可选  下拉内容 = '无数据'`，
                  54/55 —— **只有这一条红**；改回 ⇒ 55/55。
                ★ 判据落在**具体部门名**上、不落在结构上：空树照样渲染出 `.el-tree`
                  （读数 `无数据`）⇒ 只断言"有树"的判据会**恒绿**。
                """
                goto("/knowledge", wait=1800)
                page.locator(".kb-card").first.click()
                settle(1800)
                page.locator("button", has_text="上传文档").first.click()
                settle(1200)
                dlg = page.locator(".el-dialog").filter(visible=True).first
                item = (
                    dlg.locator(".el-form-item")
                    .filter(has=page.locator(".el-form-item__label:text-is('责任部门')"))
                    .first
                )
                item.locator(".el-select__wrapper").first.click()
                settle(700)
                dropdown = page.locator(".el-select-dropdown").filter(visible=True).first
                text = dropdown.inner_text()
                # ★ 断言的是 V25 种子里的**具体部门**（不是"有若干项"）：夹具与后端种子同值，
                #   谁把种子的读取挪回 `@closed`，这里立刻红。
                check("采购部" in text and "法务部" in text,
                      "上传弹窗：第一次打开时「责任部门」就有部门可选",
                      f"下拉内容 = {text[:60]!r}")
                check(dropdown.locator(".el-tree").count() > 0,
                      "上传弹窗：「责任部门」是树选择（不是扁平列表）", "")
                page.keyboard.press("Escape")
                settle(500)
                # ★ 必须关窗：本文件走 hash 路由（`goto` 是 `#/path`），同行导航**不重载页面**
                #   ⇒ 这层遮罩会盖住后面每一步的点击（实测：Locator.click 超时 30s）。
                if page.locator(".el-dialog").count():
                    page.keyboard.press("Escape")
                    try:
                        page.locator(".el-dialog").first.wait_for(state="hidden", timeout=5000)
                    except Exception:  # noqa: BLE001
                        pass
                goto("/knowledge", wait=1500)

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
                # 提示词页只管「配置」：类型是分类法（契约），维护入口在「基础数据」。
                # 用元素计数而不是文本 —— desc 里仍会出现「提示词类型」四个字（说明它不在本页）。
                new_type = page.locator("button", has_text="新建类型").count()
                type_panel = page.locator(".h4", has_text="提示词类型").count()
                check("提示词配置" in body and new_type == 0 and type_panel == 0,
                      "提示词：页面只管配置（类型已归基础数据）",
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
                # ★ 第 19 周：字典类基础数据搬去「基础数据」菜单（用户口径：别再往系统管理堆 tab）。
                #   判据落在**tab 标签**上而不是 body 文本 —— 本页通知里仍会写「密级/文种/提示词类型/库分类
                #   已搬去基础数据」，`"提示词类型" not in body` 是恒假的（这种"被测词恰好出现在别处"
                #   与本文件里 public_ok 那条负向断言同源，都是踩过的坑）。
                tabs = [
                    t.strip() for t in page.locator(".el-tabs__item").all_inner_texts()
                ]
                check(
                    tabs == ["用户管理", "角色管理", "权限点清单"],
                    "账号权限：只剩三个 tab（字典类已搬走，不再堆 tab）",
                    f"实际 tab = {tabs}",
                )

            def prompt_types_tab():
                # ★ 第 19 周：提示词类型的维护入口从「系统管理」的一个 tab 搬到了「基础数据」的
                #   第二个 tab。这一页**没有 ?tab= 深链**（它是字典维护页，tab 只是分类；
                #   深链留给"多业务 tab 的工作台"那种页），所以这里走用户的真实路径：打开页面 → 点 tab。
                goto("/basedata", wait=1800)
                page.click(".el-tabs__item:has-text('提示词类型')")
                page.wait_for_timeout(1500)
                scope = "#pane-PROMPT_TYPE"
                check(page.locator(scope).count() > 0, "基础数据：提示词类型面板已渲染", "")
                # 第 2 列是 code（第 1 列是展示名）—— 与后端种子同口径
                names = [
                    t.strip()
                    for t in page.locator(f"{scope} tbody tr td:nth-child(2)").all_inner_texts()
                ]
                want = ["通用", "合同法务", "制度咨询", "写作辅助"]
                check(sorted(names) == sorted(want),
                      "基础数据：提示词类型列出 4 个类型", f"实际 {names}")
                created = page.locator(f"{scope} button", has_text="新增").count()
                edited = page.locator(f"{scope} tbody tr button", has_text="编辑").count()
                disabled = page.locator(f"{scope} tbody tr button", has_text="停用").count()
                # ★★ 关键是**删除入口必须为 0**：字典项只能停用不能删除（V24 的纪律 ——
                #   code 已经写进历史数据，删掉会让历史数据的展示回落成机器码而无人报错）。
                #   这条负向断言就是那条纪律的哨兵：谁哪天"顺手"加个删除按钮，这里立刻红。
                removed = page.locator(f"{scope} tbody tr button", has_text="删除").count()
                check(created > 0 and edited == 4 and disabled == 4 and removed == 0,
                      "基础数据：提示词类型有增/改/停用入口，且**没有删除**（字典项只停用）",
                      f"新增 {created} / 编辑 {edited} / 停用 {disabled} / 删除 {removed}")

            # ── 组织机构（第 19 周 T302；用户口径「组织机构管理单独放出来」）──
            def org_tree():
                goto("/org", wait=1800)
                check(page.locator(".org-page").count() > 0, "组织机构：页面已渲染", "")
                # ★ 这个菜单是**分组折叠**的（`_shell` 里 `v-if="openGroup === group.title"`：点了分组标题
                #   才渲染出 `a.menu-item`）⇒ 断言必须走真实路径：先展开「管理」，再看那一项在不在。
                #   ⚠️ 第一版直接 `a:has-text('组织机构')` 拿到 **0 个**，那不是"菜单没这项"，
                #      而是它还没被展开 —— 探针的错，不是产品的错（本项目反复踩的"读数先怀疑探针"）。
                page.get_by_role("button", name="管理", exact=True).first.click()
                page.wait_for_timeout(500)
                items = [t.strip() for t in page.locator("a.menu-item").all_inner_texts()]
                check(any("组织机构" in t for t in items),
                      "组织机构：左侧「管理」分组里有独立入口（不塞进系统管理）",
                      f"分组内条目 = {items}")
                nodes = [t.strip() for t in page.locator(".org-page .el-tree-node").all_inner_texts()]
                flat = " ".join(nodes)
                # ★ 断言的是 V25 种子里的**具体部门**（不是"有若干节点"）：夹具与后端种子同值，
                #   谁把种子改瘦了、或树渲染断了，这里立刻红。
                check("总部" in flat and "技术部" in flat,
                      "组织机构：树里出现种子部门（总部/技术部）", f"节点数 {len(nodes)}")
                check(page.locator("button", has_text="新增根部门").count() > 0,
                      "组织机构：有新增入口", "")

                # 选中一个部门 ⇒ 右侧出现可编辑的详情，且写明"code 是身份、不可改"。
                # ⚠️ 必须点 `.el-tree-node__content`（那一行）而不是 `.el-tree-node`：
                #    后者的外层节点**包含自己的整棵子树**，`:has-text('技术部')` 会命中父节点「总部」，
                #    点它的中心等于点在整棵树的中部 ⇒ 实际选中的是别的部门（本轮实测落到了「法务部」），
                #    于是下面"候选里不该有技术部"那条会**假红**。
                page.locator(".org-page .el-tree-node__content", has_text="技术部").first.click()
                page.wait_for_timeout(600)
                detail = page.locator(".org-page .detail-panel").inner_text()
                check("TECH" in detail and "不可改" in detail,
                      "组织机构：选中技术部，详情写明 code 是身份且不可改",
                      f"详情片段 = {detail[:60]!r}")
                # ★ 负向断言：**code 必须没有输入框** —— 它是共享主体与审计里的标识，
                #   可改就等于"把既有共享悄悄指到别处"（后端与 mock 都是刻意不给改的）。
                code_inputs = page.locator(".org-page .detail-panel input[value='TECH']").count()
                check(code_inputs == 0, "组织机构：code 不可编辑（没有它的输入框）",
                      f"值为 TECH 的输入框 {code_inputs} 个")

                # ★ 移动部门的目标选择必须是**树**（用户 2026-10-08：扁平列表在层级多时不好操作），
                #   且候选里**不能有它自己**（自己与自己的子树注定被拒 —— 提前摘掉而不是让用户点了吃错误）。
                #   判据取弹层里的结构：有 `.el-tree` 才是树；扁平下拉只会有 `.el-select-dropdown__item`。
                #   （⚠️ 不要去匹配 `.el-tree-select` 这个类名 —— Element Plus 并不把它渲染在 DOM 上，
                #     第一版就是这么假红的。判据要落在**结构差异**上，不落在组件库的类名上。）
                page.locator(".org-page button", has_text="移动").first.click()
                page.wait_for_timeout(700)
                page.locator(".el-dialog .el-select__wrapper").first.click()
                page.wait_for_timeout(900)
                dropdown = page.locator(".el-select-dropdown:visible").first
                tree_cnt = dropdown.locator(".el-tree").count()
                # ★ 判据必须是"**层级被渲染出来了**"：Element Plus 的 TreeSelect 内部同样用
                #   `.el-select-dropdown__item` 包树节点 ⇒ `扁平项 == 0` 是错的判据（第一版就这么假红，
                #   读数 `树 1 / 扁平项 8`）。真正的结构差异是**嵌套**：树里有带子级的节点
                #   （`.el-tree-node__children`），扁平列表永远为 0。
                nested_cnt = dropdown.locator(".el-tree-node__children").count()
                check(tree_cnt > 0 and nested_cnt > 0,
                      "组织机构：移动目标是树选择（渲染出层级，不是扁平列表）",
                      f"树 {tree_cnt} / 带子级的节点 {nested_cnt}")
                cand = dropdown.locator(".el-tree").inner_text()
                check("总部" in cand and "行政部" in cand,
                      "组织机构：移动候选里能看到层级", f"候选片段 = {cand[:50]!r}")
                check("技术部" not in cand,
                      "组织机构：移动候选排除了「自己和自己的子树」", f"候选片段 = {cand[:50]!r}")
                page.keyboard.press("Escape")
                page.wait_for_timeout(300)

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
                    """读表单校验文案，**等它静默 400ms** 再读。

                    ★ 这一处量过（别凭感觉改）。实测（`/change-password`，采样间隔 100ms）：
                      · 填 `12345678`（弱口令）后：+100ms 出现「…至少 3 类」
                      · 改填 `Abc12345!@`（合格）后：旧文案**停留到 +300ms 才消失**
                    两种错法都试过了，记在这里免得后人再踩：
                      · 固定 `wait_for_timeout(900)` —— 本机够（900 > 300），CI 慢的时候不够
                      · "连续两次读数一致就算落定" —— 240ms 就判定，而旧文案要到 300ms 才没 ⇒
                        **每轮都红**（把"还没变"当成了"已落定"）
                    正确口径：**值连续 400ms 不变**（> 实测的 300ms 转换窗口），最多等 6s。
                    """
                    deadline = time.monotonic() + 6.0
                    last_text: str | None = None
                    stable_since = 0.0
                    while time.monotonic() < deadline:
                        now = " | ".join(page.locator(".el-form-item__error").all_inner_texts())
                        if now != last_text:
                            last_text, stable_since = now, time.monotonic()
                        elif time.monotonic() - stable_since >= 0.4:
                            return now
                        page.wait_for_timeout(100)
                    return last_text or ""

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
            step("知识库分类回填", kb_category_prefill)
            step("上传弹窗·责任部门组织树", kb_upload_dialog_org_tree)
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
            step("组织机构", org_tree)
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
