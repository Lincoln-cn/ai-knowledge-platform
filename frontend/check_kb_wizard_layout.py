# -*- coding: utf-8 -*-
"""建库向导布局闸门（issue #42 四条判据）。

用法：
    python check_kb_wizard_layout.py [BASE]        # 正证，默认 http://localhost:5280/
    python check_kb_wizard_layout.py --self-check  # 闸门自证：注入 calc(100% - 320px) 必须红

判据（#42 原文逐条落地）：
  ① 12 列栅格/令牌齐全：没有硬编码的 Npx 宽度 —— calc(100% - Npx) 一律清除。
     本条扫**源码**（读 pages/knowledge/ 下 .vue 做正则），不只靠截图。
  ② 窄屏（≤1280 / ≤1024）下无横向滚动、无元素重叠。
     横向滚动 = document.documentElement.scrollWidth <= clientWidth；
     重叠 = 左列 .split-left 与右列 .split-right 两个关键矩形不交叠。
  ③ 预览区高度随视口变化（不是定值）：
     .split-right 的 max-height 声明值是 vh（不是 px 定值），
     且两个视口高度下的计算值实测不同。
  ④ 真栈闸门：向导四步都能走完，每步无 console error、无布局溢出。

退出码：0 = 全绿；1 = 有红（或 --self-check 下闸门没能红，即闸门自身失效）。
"""
import re
import sys
import time
from pathlib import Path

FRONTEND = Path(__file__).resolve().parent          # frontend/
SRC = FRONTEND / "src" / "pages" / "knowledge"     # 判据① 的扫描根
WIZARD = SRC / "components" / "CreateWizard.vue"
# ★ 用 `channel="chrome"`（与仓库里其余 20 个脚本同一个写法），**不要**写死 Windows 路径：
#   `#42` 交付时这里写的是 `r"C:\Program Files\Google\Chrome\Application\chrome.exe"`
#   —— 那条在本机跑得通、在 ubuntu runner 上必炸
#   （`BrowserType.launch: Failed to launch chromium because executable doesn't exist at …`），
#   而 `channel=` 在两边都能找到浏览器（runner 自带 Chrome）。
DEFAULT_BASE = "http://localhost:5280/"

# 判据① 的病灶正则：calc(100% - Npx) 一律算「猜兄弟宽度」
CALC_RE = re.compile(r"calc\(\s*100%\s*-\s*\d+(?:\.\d+)?px\s*\)")
# self-check 注入锚点：.split-step 的左列宽度（命中即说明扫描能抓到布局宽度里的 calc）
POISON_ANCHOR = "minmax(260px, 5fr)"
POISON_TEXT = "calc(100% - 320px)"


def scan_source():
    """判据①：扫 pages/knowledge/ 下全部 .vue，返回 calc(100% - Npx) 命中清单。"""
    hits = []
    for vue in sorted(SRC.rglob("*.vue")):
        for lineno, line in enumerate(
            vue.read_text(encoding="utf-8").splitlines(), 1
        ):
            if CALC_RE.search(line):
                hits.append((vue.relative_to(FRONTEND), lineno, line.strip()))
    return hits


def self_check():
    """--self-check：把一处宽度故意改回 calc(100% - 320px)，闸门必须红；还原后必须绿。"""
    print("== 闸门自证（--self-check）：注入 calc(100% - 320px) 必须被扫描抓到 ==")
    original = WIZARD.read_text(encoding="utf-8")
    if POISON_ANCHOR not in original:
        print(f"✗ 自证前提失败：锚点 {POISON_ANCHOR!r} 不在 {WIZARD.name} 里（锚点过期）")
        return 1

    baseline = scan_source()
    print(f"读数 1（还原态基线）：calc(100% - Npx) 命中 {len(baseline)} 处")

    poisoned = original.replace(POISON_ANCHOR, POISON_TEXT, 1)
    try:
        WIZARD.write_text(poisoned, encoding="utf-8")
        injected = scan_source()
    finally:
        WIZARD.write_text(original, encoding="utf-8")   # 无论结果如何都还原
    print(f"读数 2（注入 {POISON_TEXT} 后）：命中 {len(injected)} 处")
    for name, lineno, text in injected:
        print(f"    ✗ {name}:{lineno}  {text}")

    restored = scan_source()
    print(f"读数 3（还原后）：命中 {len(restored)} 处")

    poisoned_red = len(injected) > len(baseline)
    restored_green = restored == baseline
    print(f"注入后变红：{'✓' if poisoned_red else '✗'}    还原后回绿：{'✓' if restored_green else '✗'}")
    if not poisoned_red:
        print("✗ 闸门失效：注入 calc(100% - 320px) 后扫描没有红 —— 这条判据等于没有")
    return 0 if (poisoned_red and restored_green) else 1


def main():
    args = [a for a in sys.argv[1:]]
    if "--self-check" in args:
        return self_check()
    base = args[0] if args else DEFAULT_BASE
    if not base.endswith("/"):
        base += "/"

    from playwright.sync_api import sync_playwright

    ok = []

    def check(cond, msg):
        ok.append((bool(cond), msg))
        print(("  ✓ " if cond else "  ✗ ") + msg)

    def step_check(step_no, errs_snapshot):
        """判据④ 的每步两问：无 console error、无布局溢出。"""
        new_errs = errs[errs_snapshot:]
        check(not new_errs, f"判据④ 第 {step_no} 步无 console error" +
              ("" if not new_errs else f"（新增 {len(new_errs)} 条：{new_errs[0]}）"))
        overflow = page.evaluate(
            "() => document.documentElement.scrollWidth > document.documentElement.clientWidth"
        )
        check(not overflow, f"判据④ 第 {step_no} 步无布局溢出")

    def rects_separated():
        """判据② 的关键两矩形：左列与右列不交叠。返回 (是否分离, 说明)。"""
        return page.evaluate(
            """() => {
                const ra = document.querySelector('.split-left')?.getBoundingClientRect();
                const rb = document.querySelector('.split-right')?.getBoundingClientRect();
                if (!ra || !rb || (ra.width === 0 && rb.width === 0)) return [null, '矩形未取到'];
                const overlap = !(ra.right <= rb.left + 1 || rb.right <= ra.left + 1 ||
                                  ra.bottom <= rb.top + 1 || rb.bottom <= ra.top + 1);
                return [!overlap,
                        `left(${ra.left.toFixed(0)},${ra.top.toFixed(0)},${ra.width.toFixed(0)}x${ra.height.toFixed(0)})` +
                        ` right(${rb.left.toFixed(0)},${rb.top.toFixed(0)},${rb.width.toFixed(0)}x${rb.height.toFixed(0)})`];
            }"""
        )

    print(f"== 建库向导布局闸门（#42）BASE = {base} ==")

    # ── 判据① 源码扫描（先扫：红了就不必起浏览器，快速失败）──
    print("【判据①】源码无 calc(100% - Npx)")
    hits = scan_source()
    check(not hits, f"pages/knowledge/ 下 .vue 无 calc(100% - Npx)（扫描 {len(list(SRC.rglob('*.vue')))} 个文件，命中 {len(hits)} 处）")
    for name, lineno, text in hits:
        print(f"    ✗ {name}:{lineno}  {text}")
    if hits:
        print("源码扫描已红 —— 跳过浏览器断言（判据① 是其余判据的前提）")
        bad = [m for c, m in ok if not c]
        print(f"\n通过 {len(ok) - len(bad)}/{len(ok)}" + ("；失败：" + "; ".join(bad) if bad else ""))
        return 1

    with sync_playwright() as pw:
        b = pw.chromium.launch(headless=True, channel="chrome")
        page = b.new_page(viewport={"width": 1600, "height": 950})
        errs = []
        page.on("pageerror", lambda e: errs.append(f"pageerror: {e}"))
        page.on(
            "console",
            lambda m: errs.append(f"console.error: {m.text}")
            if m.type == "error" and "favicon" not in m.text
            else None,
        )

        try:
            # ── 登录（任务书给定选择器）──
            page.goto(f"{base}#/login")
            page.fill('input[placeholder*="用户名"], input[type="text"]', "admin")
            page.fill('input[type="password"]', "admin")
            page.click('button:has-text("登录")')
            page.wait_for_timeout(1500)
            token = page.evaluate("() => localStorage.getItem('access_token')")
            check(bool(token), "登录成功（access_token 已落 localStorage）")

            # ── 打开建库向导 ──
            page.goto(f"{base}#/knowledge")
            page.wait_for_timeout(1200)
            page.click('button:has-text("创建知识库")')
            page.wait_for_selector('.el-dialog__title:has-text("创建知识库")', timeout=8000)

            def item(label: str):
                """按 label 精确定位表单项（"密级"不会误中"密级上限"）。"""
                return page.locator(
                    f'.el-dialog .el-form-item:has(.el-form-item__label:text-is("{label}"))'
                ).first

            def pick_option():
                """点下拉里第一个**可见**选项（EP 每个 select 都有隐藏 popper，必须滤可见）。

                ★ `force=True`：CI 上这条曾以 `element is not stable` 超时 30s —— 下拉展开的
                  过渡动画期间元素位置一直在变，Playwright 的"稳定性"检查会一直重试到超时。
                  `filter(visible=True)` 已经保证了"可见"，这里再等动画结束 + force 跳过稳定性，
                  断言语义不变（仍点的是同一个可见选项）。
                """
                opt = page.locator(".el-select-dropdown__item").filter(visible=True).first
                opt.wait_for(state="visible", timeout=5000)
                page.wait_for_timeout(250)
                opt.click(force=True, timeout=10000)

            # ── 第一步：基本信息 ──
            mark = len(errs)
            item("名称").locator("input").fill("布局闸门临时库")
            # 分类 / 密级上限 / 向量模型：各选下拉第一项（rules 必填）
            for label in ("分类", "密级上限", "向量模型"):
                item(label).locator(".el-select").click()
                pick_option()
                page.wait_for_timeout(150)
            step_check(1, mark)
            page.click('.el-dialog button:has-text("下一步")')
            page.wait_for_timeout(500)

            # ── 第二步：上传文档 ──
            mark = len(errs)
            doc = FRONTEND / "_gate_wizard_doc.md"
            doc.write_text(
                "# 闸门临时文档\n"
                + "\n".join(f"第 {i} 行：布局闸门用的填充文字，验证分段与预览。" for i in range(1, 41)),
                encoding="utf-8",
            )
            page.set_input_files('.el-dialog input[type="file"]', str(doc))
            page.wait_for_timeout(400)
            step_check(2, mark)
            page.click('.el-dialog button:has-text("下一步")')
            page.wait_for_timeout(500)

            # ── 第三步：分段设置 —— 判据② ③ 的测量都发生在这一步 ──
            mark = len(errs)

            # 判据② 之横向滚动（1600 常规视口）
            check(
                page.evaluate("() => document.documentElement.scrollWidth <= document.documentElement.clientWidth"),
                "判据② 1600px 视口无横向滚动",
            )
            # 判据② 之左右列不交叠（1600 双列布局）
            sep, info = rects_separated()
            check(sep, f"判据② 1600px 视口左列与右列不交叠（{info}）")

            # 判据③ 之声明值是 vh（CSSOM 取声明值，不受内容量影响）
            decl = page.evaluate(
                """() => {
                    for (const sheet of document.styleSheets) {
                        let rules; try { rules = sheet.cssRules } catch (e) { continue }
                        for (const r of rules) {
                            if (r.selectorText && /split-right/.test(r.selectorText) && r.style && r.style.maxHeight)
                                return r.style.maxHeight;
                        }
                    }
                    return '';
                }"""
            )
            check(decl.endswith("vh"), f"判据③ 预览区 max-height 声明是视口值（{decl or '未取到'}）")

            # 判据③ 之双视口实测：950 高 → 700 高，计算值必须跟着变
            page.set_viewport_size({"width": 1600, "height": 950})
            mh_hi = page.evaluate(
                "() => parseFloat(getComputedStyle(document.querySelector('.split-right')).maxHeight)"
            )
            page.set_viewport_size({"width": 1600, "height": 700})
            mh_lo = page.evaluate(
                "() => parseFloat(getComputedStyle(document.querySelector('.split-right')).maxHeight)"
            )
            check(
                abs(mh_hi - mh_lo) > 40,
                f"判据③ 预览区高度随视口变化（950 高→{mh_hi:.0f}px，700 高→{mh_lo:.0f}px）",
            )

            # 判据② 之窄屏两档：≤1280 / ≤1024 无横向滚动、无重叠
            for width in (1280, 1024):
                page.set_viewport_size({"width": width, "height": 800})
                page.wait_for_timeout(300)
                check(
                    page.evaluate(
                        "() => document.documentElement.scrollWidth <= document.documentElement.clientWidth"
                    ),
                    f"判据② {width}px 视口无横向滚动",
                )
                sep, info = rects_separated()
                check(sep, f"判据② {width}px 视口左列与右列不交叠（{info}）")
            page.set_viewport_size({"width": 1600, "height": 950})
            page.wait_for_timeout(300)

            # 建档三项（文种 / 责任部门 / 密级）后生成预览
            for label in ("文种", "密级"):
                item(label).locator(".el-select").click()
                pick_option()
                page.wait_for_timeout(150)
            # el-tree-select 渲染根元素就是 .el-select（无 .el-tree-select 类），弹层里是树
            item("责任部门").locator(".el-select").click()
            page.wait_for_timeout(300)
            page.wait_for_timeout(250)
            page.locator(".el-tree-node__content").filter(visible=True).first.click(
                force=True, timeout=10000
            )
            page.wait_for_timeout(150)
            page.click('.el-dialog button:has-text("生成预览")')
            try:
                page.wait_for_selector(".preview-list, .preview-error", timeout=60000)
            except Exception:
                pass
            check(page.locator(".preview-list").count() > 0, "判据④ 生成预览出结果（.preview-list 有分段）")
            step_check(3, mark)

            # ── 第四步：导入结果 ──
            mark = len(errs)
            page.click('.el-dialog button:has-text("开始导入")')
            page.wait_for_timeout(1500)
            step_check(4, mark)
            check(page.locator('.el-dialog .notice.info').count() > 0, "判据④ 第四步显示导入结果")
            page.click('.el-dialog button:has-text("完成")')
            page.wait_for_timeout(500)
            mark = len(errs)
            new_errs = errs[mark:]
            check(not new_errs, "判据④ 收尾（完成）无 console error")
        finally:
            try:
                doc.unlink(missing_ok=True)
            except Exception:
                pass
            b.close()

    bad = [m for c, m in ok if not c]
    print(f"\n通过 {len(ok) - len(bad)}/{len(ok)}" + ("；失败：" + "; ".join(bad) if bad else "；四条判据全绿"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
