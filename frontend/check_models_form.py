"""模型创建表单业务完整性闸门（浏览器实测）。

四件事 + 两处顺带修复，每条都是「用户能不能真的配出外部 API 模型」的必要条件：
  1. 表单顺序：顶部先定「供应商 + 服务地址 + 密钥」，再谈模型类型/基础模型
  2. 目录跟着上游走：改 apiBase / apiKey / 供应商 ⇒ 目录重新拉，且内容随上游变化
  3. 外部 API 必填校验：选「外部API」不填 apiBase 提交 ⇒ 表单报错拦下
  4. 空目录给可操作提示：不是干巴巴的「没有匹配的基础模型」
  5. mock 的 /models/base-models 必须是 POST（契约已改；GET 会让 mock 模式整个目录拉不动）
  6. 编辑弹窗：apiKey 不回填、服务地址可改

用法：python check_models_form.py [base]
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280/"
ROOT = Path(__file__).resolve().parent


def main() -> int:
    ok: list[tuple[bool, str]] = []

    def check(cond: bool, msg: str) -> None:
        ok.append((cond, msg))
        print(("  ✓ " if cond else "  ✗ ") + msg)

    # ── 0. 静态：mock 路由必须是 POST ──────────────────────────────
    print("== 0. mock 契约：/models/base-models 方法 ==")
    handler_src = (ROOT / "src" / "mock" / "handlers" / "models.ts").read_text(encoding="utf-8")
    # 找到 base-models 那条路由的方法（[ 与方法串之间可能有注释/换行）
    route_m = re.search(
        r"\[\s*(?://[^\n]*\n\s*)*'(GET|POST|PUT|DELETE)'\s*,\s*'/models/base-models'", handler_src
    )
    method = route_m.group(1) if route_m else None
    check(method == "POST", f"mock 的 /models/base-models 是 POST（当前：{method}）")

    with sync_playwright() as pw:
        b = pw.chromium.launch(channel="chrome")
        page = b.new_page(viewport={"width": 1600, "height": 950})
        errs: list[str] = []
        page.on("pageerror", lambda e: errs.append(str(e)))

        def on_console(m) -> None:
            if m.type != "error":
                return
            url = (m.location or {}).get("url", "") if isinstance(m.location, dict) else ""
            if "favicon" not in url:
                errs.append(f"{m.text} @ {url}")

        page.on("console", on_console)

        page.goto(f"{BASE}/#/login", wait_until="networkidle")
        page.wait_for_timeout(600)
        page.fill("input[autocomplete='username']", "admin")
        page.fill("input[autocomplete='current-password']", "admin")
        page.click("button:has-text('登录')")
        page.wait_for_timeout(1800)
        page.goto(f"{BASE}/#/models", wait_until="networkidle")
        page.wait_for_timeout(1500)

        # 打开「添加模型」弹窗
        page.click("button:has-text('添加模型')")
        page.wait_for_timeout(1200)

        def dialog_form_items() -> list[dict]:
            """可见弹窗里按 DOM 顺序取表单项 {label, y}。"""
            return page.evaluate(
                """() => {
                    const dlg = [...document.querySelectorAll('.el-dialog')]
                        .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                    if (!dlg) return [];
                    return [...dlg.querySelectorAll('.el-form-item')].map((it, i) => {
                        const lab = it.querySelector('.el-form-item__label');
                        return {
                            i,
                            label: (lab ? lab.innerText : '').trim(),
                            y: Math.round(it.getBoundingClientRect().top),
                            disabledInput: !!it.querySelector('input:disabled, textarea:disabled'),
                            placeholder: (it.querySelector('input, textarea') || {}).placeholder || '',
                            value: (it.querySelector('input, textarea') || {}).value || '',
                            hintText: (it.querySelector('.hint') || {}).innerText || '',
                        };
                    });
                }"""
            )

        def catalog_state() -> dict:
            return page.evaluate(
                """() => {
                    const dlg = [...document.querySelectorAll('.el-dialog')]
                        .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                    if (!dlg) return { items: [], empty: '', loading: true };
                    const items = [...dlg.querySelectorAll('.catalog-item')].map(b =>
                        (b.querySelector('.ci-name') || b).innerText.trim());
                    const emptyEl = dlg.querySelector('.catalog-empty');
                    return {
                        items,
                        empty: emptyEl ? emptyEl.innerText.trim() : '',
                        loading: !!dlg.querySelector('.el-loading-mask'),
                    };
                }"""
            )

        def fill_form_item(label: str, value: str) -> bool:
            """按 label 找到表单项里的 input 填值。"""
            return page.evaluate(
                """([label, value]) => {
                    const dlg = [...document.querySelectorAll('.el-dialog')]
                        .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                    if (!dlg) return false;
                    for (const it of dlg.querySelectorAll('.el-form-item')) {
                        const lab = it.querySelector('.el-form-item__label');
                        if (!lab || lab.innerText.trim() !== label) continue;
                        const input = it.querySelector('input, textarea');
                        if (!input) return false;
                        const setter = Object.getOwnPropertyDescriptor(
                            window.HTMLInputElement.prototype, 'value')?.set
                            || Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value')?.set;
                        setter.call(input, value);
                        input.dispatchEvent(new Event('input', { bubbles: true }));
                        input.dispatchEvent(new Event('change', { bubbles: true }));
                        return true;
                    }
                    return false;
                }""",
                [label, value],
            )

        def item_by_label(items: list[dict], label: str) -> dict | None:
            for it in items:
                if it["label"] == label:
                    return it
            return None

        # ── 1. 表单顺序 ──────────────────────────────────────────
        print("\n== 1. 表单顺序：供应商 / 服务地址 / 密钥 在最上面 ==")
        items = dialog_form_items()
        labels = [it["label"] for it in items]
        print("   表单标签顺序:", labels)

        def idx_of(*names: str) -> int:
            for n in names:
                for i, lab in enumerate(labels):
                    if lab == n:
                        return i
            return -1

        i_sup = idx_of("供应商")
        i_base = idx_of("服务地址", "apiBase")
        i_key = idx_of("密钥", "apiKey")
        i_kind = idx_of("模型类型")
        i_bm = idx_of("基础模型")

        check(i_sup >= 0, f"有「供应商」项（index={i_sup}）")
        check(i_base >= 0, f"有「服务地址」项（index={i_base}）")
        check(i_key >= 0, f"有「密钥」项（index={i_key}）")
        # 三项都要在模型类型/基础模型之前（缺任一项直接不通过，避免空集恒真）
        after = [x for x in (i_kind, i_bm) if x >= 0]
        all_present = i_sup >= 0 and i_base >= 0 and i_key >= 0
        check(
            all_present and (not after or max(i_sup, i_base, i_key) < min(after)),
            f"三项都排在模型类型/基础模型之前（sup={i_sup} base={i_base} key={i_key} kind={i_kind} bm={i_bm}）",
        )
        check(all_present and (i_kind < 0 or i_sup < i_kind), "供应商在表单最前面（先定连哪个服务）")

        # ── 2. 供应商取值 / 本地推理 hint / 密钥 placeholder ───────
        print("\n== 2. 供应商选项与文案 ==")
        sup_text = page.evaluate(
            """() => {
                const dlg = [...document.querySelectorAll('.el-dialog')]
                    .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                if (!dlg) return '';
                const all = dlg.innerText;
                return all;
            }"""
        )
        check("本地推理" in sup_text, "展示「本地推理」")
        check("外部API" in sup_text or "外部 API" in sup_text, "展示「外部API」")

        api_item = item_by_label(items, "服务地址") or item_by_label(items, "apiBase")
        if api_item:
            hint = api_item["hintText"] or ""
            # 本地推理时 hint 应写明「留空 = 使用默认服务」
            page_text_near = page.evaluate(
                """() => {
                    const dlg = [...document.querySelectorAll('.el-dialog')]
                        .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                    if (!dlg) return '';
                    for (const it of dlg.querySelectorAll('.el-form-item')) {
                        const lab = it.querySelector('.el-form-item__label');
                        if (lab && (lab.innerText.trim() === '服务地址' || lab.innerText.trim() === 'apiBase')) {
                            return it.innerText;
                        }
                    }
                    return '';
                }"""
            )
            check(
                "留空" in page_text_near and ("默认" in page_text_near),
                f"本地推理下服务地址 hint 写明「留空 = 使用默认服务」（实际：{page_text_near.strip()[:60]!r}）",
            )
        else:
            check(False, "找不到服务地址表单项")

        key_item = item_by_label(items, "密钥") or item_by_label(items, "apiKey")
        if key_item:
            ph = key_item["placeholder"]
            check("留空" in ph and "修改" in ph, f"密钥 placeholder 说明「留空则不修改」（实际：{ph!r}）")
        else:
            check(False, "找不到密钥表单项")

        # ── 3. 目录跟着上游走 ───────────────────────────────────
        print("\n== 3. 目录跟着 apiBase 走（重新拉 + 内容变化） ==")
        # 先等默认目录出来
        page.wait_for_timeout(800)
        default_cat = catalog_state()
        print("   默认目录:", default_cat["items"][:6], "empty:", default_cat["empty"][:40])
        check(len(default_cat["items"]) > 0, f"默认上游能拉到基础模型（{len(default_cat['items'])} 个）")

        # 换一个 mock 分支用的 apiBase（openai 系）
        filled = fill_form_item("服务地址", "http://openai.mock/v1") or fill_form_item("apiBase", "http://openai.mock/v1")
        check(filled, "能填入服务地址")
        page.wait_for_timeout(1600)  # 等重新拉目录（去抖 + mock 延迟）
        openai_cat = catalog_state()
        print("   openai 上游目录:", openai_cat["items"][:6], "empty:", openai_cat["empty"][:40])
        check(
            len(openai_cat["items"]) > 0 and set(openai_cat["items"]) != set(default_cat["items"]),
            f"改 apiBase 后目录内容跟着变（default={default_cat['items'][:3]} vs openai={openai_cat['items'][:3]}）",
        )

        # 再换成死地址 ⇒ 空目录 + 可操作 hint
        filled2 = fill_form_item("服务地址", "http://dead.mock/v1") or fill_form_item("apiBase", "http://dead.mock/v1")
        check(filled2, "能再次改服务地址")
        page.wait_for_timeout(1600)
        dead_cat = catalog_state()
        print("   死上游目录:", dead_cat["items"], "empty:", dead_cat["empty"][:80])
        empty_text = dead_cat["empty"]
        check(len(dead_cat["items"]) == 0, "死上游目录为空")
        check(
            any(k in empty_text for k in ("服务地址", "已启动", "未从上游", "检查")),
            f"空目录给可操作 hint（实际：{empty_text[:60]!r}）",
        )
        check(
            empty_text.strip() != "没有匹配的基础模型",
            "不是干巴巴的「没有匹配的基础模型」",
        )

        # ── 4. 外部 API 必填校验 ─────────────────────────────────
        print("\n== 4. 外部API 未填 apiBase 提交 ⇒ 表单校验拦下 ==")
        # 清空服务地址，选「外部API」
        fill_form_item("服务地址", "") or fill_form_item("apiBase", "")
        fill_form_item("密钥", "") or fill_form_item("apiKey", "")
        # 点供应商里的「外部API」（radio-button 或 select）
        clicked_supplier = page.evaluate(
            """() => {
                const dlg = [...document.querySelectorAll('.el-dialog')]
                    .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                if (!dlg) return false;
                for (const el of dlg.querySelectorAll('.el-radio-button, .el-radio, .el-select-dropdown__item, button, label')) {
                    const t = (el.innerText || '').trim();
                    if (t === '外部API' || t === '外部 API') { el.click(); return true; }
                }
                // 可能在 select 弹层里：先点开「供应商」的 select
                for (const it of dlg.querySelectorAll('.el-form-item')) {
                    const lab = it.querySelector('.el-form-item__label');
                    if (lab && lab.innerText.trim() === '供应商') {
                        const sel = it.querySelector('.el-select');
                        if (sel) { sel.click(); return 'select'; }
                    }
                }
                return false;
            }"""
        )
        page.wait_for_timeout(500)
        if clicked_supplier == "select":
            page.evaluate(
                """() => {
                    for (const el of document.querySelectorAll('.el-select-dropdown__item')) {
                        const t = (el.innerText || '').trim();
                        if (t === '外部API' || t === '外部 API') { el.click(); return true; }
                    }
                    return false;
                }"""
            )
            page.wait_for_timeout(400)
        check(bool(clicked_supplier), "能选中「外部API」供应商")

        # 填名称 + 选个基础模型（若目录空则先用有目录的 apiBase）
        # 为了只测校验，不填 apiBase 直接提交
        fill_form_item("模型名称", "外部API校验探针")
        page.wait_for_timeout(300)

        # 点弹窗底部「确定」/「添加」
        page.evaluate(
            """() => {
                const dlg = [...document.querySelectorAll('.el-dialog')]
                    .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                if (!dlg) return false;
                for (const btn of dlg.querySelectorAll('.el-dialog__footer button, footer button')) {
                    const t = (btn.innerText || '').trim();
                    if (t === '确定' || t === '添加' || t === '保存') { btn.click(); return true; }
                }
                return false;
            }"""
        )
        page.wait_for_timeout(900)
        err_texts = page.evaluate(
            """() => {
                const dlg = [...document.querySelectorAll('.el-dialog')]
                    .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                if (!dlg) return [];
                return [...dlg.querySelectorAll('.el-form-item__error')].map(e => e.innerText.trim());
            }"""
        )
        print("   校验错误:", err_texts)
        check(
            any(("apiBase" in t or "服务地址" in t) for t in err_texts),
            f"外部API 未填 apiBase 被表单校验拦下（错误：{err_texts}）",
        )

        # ── 5. 编辑态：apiKey 不回填、服务地址可改 ─────────────────
        print("\n== 5. 编辑弹窗：apiKey 不回填 / apiBase 可改 ==")
        # 关掉当前（添加）弹窗 —— Esc + 取消双保险
        page.keyboard.press("Escape")
        page.wait_for_timeout(500)
        page.evaluate(
            """() => {
                const dlg = [...document.querySelectorAll('.el-dialog')]
                    .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                if (!dlg) return false;
                for (const btn of dlg.querySelectorAll('button')) {
                    if ((btn.innerText || '').trim() === '取消') { btn.click(); return true; }
                }
                return false;
            }"""
        )
        page.wait_for_timeout(900)
        # 打开某个已有模型的「编辑」（列表卡片里那个）
        edit_btn = page.locator(".model-card button:has-text('编辑')").first
        has_edit = edit_btn.count() > 0
        check(has_edit, "模型列表里有「编辑」入口")
        if has_edit:
            try:
                edit_btn.click(timeout=8000, force=True)
                page.wait_for_timeout(1200)
                edit_items = dialog_form_items()
                print("   编辑表单:", [(it['label'], it['value'], it['disabledInput']) for it in edit_items])
                key_edit = item_by_label(edit_items, "密钥") or item_by_label(edit_items, "apiKey")
                base_edit = item_by_label(edit_items, "服务地址") or item_by_label(edit_items, "apiBase")
                if key_edit:
                    check(
                        key_edit["value"] == "",
                        f"编辑态密钥不回填（实际值：{key_edit['value']!r}）",
                    )
                    ph = key_edit["placeholder"]
                    check("留空" in ph, f"编辑态密钥 placeholder 仍说明留空语义（{ph!r}）")
                else:
                    check(False, "编辑弹窗里有密钥项")
                if base_edit:
                    check(not base_edit["disabledInput"], "编辑态服务地址可改（未禁用）")
                else:
                    check(False, "编辑弹窗里有服务地址项")
            except Exception as e:
                check(False, f"打开编辑弹窗失败：{e}")
            page.keyboard.press("Escape")
            page.wait_for_timeout(600)

        print(f"\n控制台错误：{errs if errs else 0}")
        b.close()

    bad = [m for c, m in ok if not c]
    print("=" * 60)
    print(f"通过 {len(ok) - len(bad)}/{len(ok)}")
    for m in bad:
        print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
