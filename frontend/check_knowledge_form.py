"""知识库建库 / 文档建档表单业务完整性闸门（浏览器实测）。

四件事，每条都是「用户能不能真的把带密级的档案建进库」的必要条件：
  1. 建库必须选「密级上限」（ceiling）—— 缺了不让提交；填了能提交成功
  2. 文档建档三项必填：文种 / 责任部门 / 密级 —— 缺任一被拦；三项齐全能提交
  3. 文档密级 > 库 ceiling ⇒ 界面显示后端可读原因（含两个中文密级名，原文展示）
  4. 列表 `total` 与可见条数一致；越权/不存在的库深链给可读提示（不白屏）

夹具自备（mock / 真栈同一套逻辑，不依赖 mock 种子）：
  · 测试库走页面建库向导本身（同时覆盖第 1 条）；名称带时间戳。
  · 文档建档走「上传文档」弹窗本身（同时覆盖第 2/3 条）—— 文档建档表单的主入口。
  · 真栈（JWT）用同一套登录态走接口清理；mock 的临时数据随浏览器关掉即消失。
  · ★ 不用 Python urllib 造夹具：mock 的 /api 是浏览器内拦截，Python 侧打到的是代理后的真后端。

用法：python check_knowledge_form.py [base]
"""
from __future__ import annotations

import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280/"
ROOT = Path(__file__).resolve().parent

CEILING_RE = re.compile(
    r"文档密级（(公开|内部|秘密|机密)）高于知识库的密级上限（(公开|内部|秘密|机密)）"
)


def api_json(base: str, method: str, path: str, token: str, body: dict | None = None) -> dict:
    url = base.rstrip("/") + "/api/v1" + path
    try:
        url.encode("ascii")
    except UnicodeEncodeError:
        if "?" in url:
            head, qs = url.split("?", 1)
            url = head + "?" + urllib.parse.quote(qs, safe="=&%:/?+")
        else:
            url = urllib.parse.quote(url, safe="/:?=&%+")
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return json.loads(raw) if raw else {"code": e.code, "message": str(e)}
        except Exception:
            return {"code": e.code, "message": raw[:200] or str(e)}
    return json.loads(raw) if raw else {}


def main() -> int:
    ok: list[tuple[bool, str]] = []

    def check(cond: bool, msg: str) -> None:
        ok.append((cond, msg))
        print(("  ✓ " if cond else "  ✗ ") + msg)

    ts = int(time.time())
    kb_name = f"闸门密级库-{ts}"
    model_name = f"闸门向量-{ts}"
    token = ""
    api_origin = BASE
    fixture_model_id: str | None = None

    try:
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
            token = page.evaluate("() => localStorage.getItem('access_token') || ''")

            # ────────────── 夹具：真栈可能一个向量模型都没有 ──────────────
            # mock 下 urllib 打不到浏览器内 mock（打到的是代理后的真后端），故只在真栈造。
            if token and not token.startswith("mock_"):
                mlist = api_json(api_origin, "GET", "/models?kind=EMBEDDING&size=50", token)
                mdata = mlist.get("data") or {}
                mrows = mdata.get("list") if isinstance(mdata, dict) else mdata
                if not mrows:
                    created = api_json(
                        api_origin,
                        "POST",
                        "/models",
                        token,
                        {
                            "name": f"闸门向量-{ts}",
                            "kind": "EMBEDDING",
                            "supplier": "LOCAL_INFERENCE",
                            "baseModel": "bge-m3",
                            "dimension": 1024,
                        },
                    )
                    fixture_model_id = (created.get("data") or {}).get("id")
                    print(f"   夹具：真栈注册临时向量模型 id={fixture_model_id}")
                else:
                    print(f"   夹具：真栈复用向量模型 {mrows[0].get('name')}")

            page.goto(f"{BASE}/#/knowledge", wait_until="networkidle")
            page.wait_for_timeout(1500)

            # ────────────── 通用工具 ──────────────

            def dlg_visible() -> bool:
                return page.evaluate(
                    """() => [...document.querySelectorAll('.el-dialog')].some(
                        d => d.offsetParent !== null && d.getBoundingClientRect().width > 0)"""
                )

            def dlg_text() -> str:
                return page.evaluate(
                    """() => {
                        const d = [...document.querySelectorAll('.el-dialog')]
                            .find(x => x.offsetParent !== null && x.getBoundingClientRect().width > 0);
                        return d ? (d.innerText || '') : '';
                    }"""
                )

            def visible_form_labels() -> list[str]:
                return page.evaluate(
                    """() => {
                        const d = [...document.querySelectorAll('.el-dialog')]
                            .find(x => x.offsetParent !== null && x.getBoundingClientRect().width > 0);
                        if (!d) return [];
                        return [...d.querySelectorAll('.el-form-item')]
                            .filter(it => it.offsetParent !== null && it.getBoundingClientRect().height > 0)
                            .map(it => {
                                const lab = it.querySelector('.el-form-item__label');
                                return (lab ? lab.innerText : '').trim();
                            });
                    }"""
                )

            def form_errors() -> list[str]:
                return page.evaluate(
                    """() => {
                        const d = [...document.querySelectorAll('.el-dialog')]
                            .find(x => x.offsetParent !== null && x.getBoundingClientRect().width > 0);
                        if (!d) return [];
                        return [...d.querySelectorAll('.el-form-item__error')]
                            .filter(e => e.offsetParent !== null)
                            .map(e => e.innerText.trim());
                    }"""
                )

            def fill_input(label: str, value: str) -> bool:
                return page.evaluate(
                    """([label, value]) => {
                        const d = [...document.querySelectorAll('.el-dialog')]
                            .find(x => x.offsetParent !== null && x.getBoundingClientRect().width > 0);
                        if (!d) return false;
                        for (const it of d.querySelectorAll('.el-form-item')) {
                            if (it.offsetParent === null) continue;
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
                            input.dispatchEvent(new Event('blur', { bubbles: true }));
                            return true;
                        }
                        return false;
                    }""",
                    [label, value],
                )

            def pick_option(text: str) -> bool:
                try:
                    for cand in (
                        page.locator(f".el-select-dropdown__item:text-is('{text}')"),
                        page.locator(".el-select-dropdown__item", has_text=text),
                    ):
                        try:
                            loc = cand.filter(visible=True).first
                            loc.wait_for(state="visible", timeout=2000)
                            loc.click(timeout=3000)
                            return True
                        except Exception:
                            continue
                except Exception:
                    pass
                return False

            def select_by_label(label: str, option: str) -> bool:
                try:
                    item = (
                        page.locator(".el-dialog")
                        .filter(visible=True)
                        .locator(".el-form-item")
                        .filter(has=page.locator(f".el-form-item__label:text-is('{label}')"))
                        .first
                    )
                    item.wait_for(state="visible", timeout=3000)
                    item.locator(".el-select").first.click(timeout=3000)
                    page.wait_for_timeout(400)
                except Exception:
                    return False
                return pick_option(option)

            def fill_select_first(label: str) -> bool:
                try:
                    item = (
                        page.locator(".el-dialog")
                        .filter(visible=True)
                        .locator(".el-form-item")
                        .filter(has=page.locator(f".el-form-item__label:text-is('{label}')"))
                        .first
                    )
                    item.wait_for(state="visible", timeout=3000)
                    item.locator(".el-select").first.click(timeout=3000)
                    page.wait_for_timeout(500)
                    opt = page.locator(".el-select-dropdown__item").filter(visible=True).first
                    opt.wait_for(state="visible", timeout=3000)
                    opt.click(timeout=3000)
                    page.wait_for_timeout(300)
                    return True
                except Exception:
                    return False

            def click_btn(*texts: str) -> bool:
                return page.evaluate(
                    """(texts) => {
                        const d = [...document.querySelectorAll('.el-dialog')]
                            .find(x => x.offsetParent !== null && x.getBoundingClientRect().width > 0);
                        if (!d) return false;
                        for (const btn of d.querySelectorAll('button')) {
                            if (btn.offsetParent === null) continue;
                            const t = (btn.innerText || '').trim();
                            if (texts.includes(t) && !btn.disabled) { btn.click(); return true; }
                        }
                        return false;
                    }""",
                    list(texts),
                )

            def click_upload_btn() -> bool:
                return page.evaluate(
                    """() => {
                        const d = [...document.querySelectorAll('.el-dialog')]
                            .find(x => x.offsetParent !== null && x.getBoundingClientRect().width > 0);
                        if (!d) return false;
                        for (const btn of d.querySelectorAll('button')) {
                            const t = (btn.innerText || '').trim();
                            if (/^上传/.test(t) && !btn.disabled) { btn.click(); return true; }
                        }
                        return false;
                    }"""
                )

            def close_dlg() -> None:
                page.keyboard.press("Escape")
                page.wait_for_timeout(500)
                click_btn("取消")
                page.wait_for_timeout(500)

            def active_step_body() -> str:
                return page.evaluate(
                    """() => {
                        const d = [...document.querySelectorAll('.el-dialog')]
                            .find(x => x.offsetParent !== null && x.getBoundingClientRect().width > 0);
                        if (!d) return '';
                        const bodies = [...d.querySelectorAll('.step-body')].filter(
                            x => x.offsetParent !== null && x.getBoundingClientRect().height > 0);
                        return bodies.map(x => x.innerText || '').join('\\n');
                    }"""
                )

            def set_files(selector: str, path: str) -> bool:
                try:
                    loc = page.locator(selector).first
                    if loc.count():
                        loc.set_input_files(path)
                        return True
                except Exception:
                    pass
                return False

            tmp = ROOT / f"_gate_doc_{ts}.md"
            tmp.write_text("闸门临时文档：用于验证建档三项必填。\n" * 20, encoding="utf-8")

            # ────────────── 1. 建库表单：密级上限必填 ──────────────
            print("== 1. 建库表单：「密级上限」必填 ==")
            page.click("button:has-text('创建知识库')")
            page.wait_for_timeout(1200)
            labels = visible_form_labels()
            print("   建库表单可见标签:", labels)
            check(any("密级上限" in x for x in labels), f"建库表单有「密级上限」（实际：{labels}）")

            fill_input("名称", kb_name)
            check(fill_select_first("分类"), "能选中「分类」")
            check(fill_select_first("向量模型"), "能选中「向量模型」")

            click_btn("下一步")
            page.wait_for_timeout(900)
            errs1 = form_errors()
            print("   校验错误:", errs1)
            check(any("密级上限" in e for e in errs1), f"缺「密级上限」被表单拦下（errors={errs1}）")
            check(dlg_visible(), "缺密级上限时弹窗未跳走")
            body0 = active_step_body()
            check("拖到此处" not in body0, "缺密级上限时仍停在第一步")

            # 填密级上限=秘密（ceiling）⇒ 能提交成功（进入上传步）
            check(select_by_label("密级上限", "秘密"), "能选中「密级上限」选项（秘密）")
            click_btn("下一步")
            page.wait_for_timeout(1500)
            body1 = active_step_body()
            on_upload = "拖到此处" in body1 or "点击选择" in body1
            check(on_upload, f"填了密级上限后能提交成功（进入上传步；摘要：{body1[:60]!r}）")

            # ────────────── 2. 走完向导建库（夹具，ceiling=秘密）──────────────
            print("\n== 2. 走完向导建库（ceiling=秘密）作为建档夹具 ==")
            set_files(".el-dialog input[type=file]", str(tmp)) or set_files("input[type=file]", str(tmp))
            page.wait_for_timeout(800)
            click_btn("下一步")  # 上传步 → 分段步（ensureKb）
            page.wait_for_timeout(2500)
            labels2 = visible_form_labels()
            print("   分段步可见标签:", labels2)
            check(any("文种" in x for x in labels2), f"建档表单有「文种」（实际：{labels2}）")
            check(
                any("责任部门" in x or "部门" in x for x in labels2),
                f"建档表单有「责任部门」（实际：{labels2}）",
            )
            check(
                any("密级" in x and "上限" not in x for x in labels2),
                f"建档表单有「密级」（实际：{labels2}）",
            )

            # ── 2a. 缺任一一项被拦 ──
            click_btn("开始导入")
            page.wait_for_timeout(900)
            errs2 = form_errors()
            print("   缺三项错误:", errs2)
            check(
                any("文种" in e for e in errs2)
                or any("部门" in e for e in errs2)
                or any("密级" in e for e in errs2),
                f"缺建档三项被拦（errors={errs2}）",
            )
            # ★ 文种/部门现在是**下拉选择**（允许现场新增）：往 input 里塞 value 不会改 v-model，
            #   必须真的点开下拉选一项 —— 与用户的操作一致（这条曾是闸门的假绿灯来源）
            check(select_by_label("文种", "制度"), "能选中「文种」选项（制度）")
            check(select_by_label("责任部门", "采购部"), "能选中「责任部门」选项（采购部）")
            click_btn("开始导入")
            page.wait_for_timeout(900)
            errs2b = form_errors()
            print("   缺密级错误:", errs2b)
            check(any("密级" in e for e in errs2b), f"缺密级仍被拦（errors={errs2b}）")

            # ── 2b. 三项齐全（密级=内部 ≤ ceiling=秘密）⇒ ★ 生成预览 → 提交成功 ──
            check(select_by_label("密级", "内部"), "能选中「密级」选项（内部）")

            # ★ 「生成预览」是这条链上最容易整段失效的一步：它要先把文件建档（解析 + 分块），
            #   再按 documentId 拿预览。曾因向导走「暂存上传」（真栈未实现）而必然 400。
            click_btn("生成预览", "智能分段", "生成")
            # ★ 等**信号**，不等固定时长（今天这条教训已经吃过三次）：
            #   「生成预览」现在是"建档 → **等解析落地**（异步，T249.3）→ 取预览"三步，
            #   固定 4 秒偶尔不够 ⇒ 闸门随机变红，而红的原因与产品无关。
            #   等到「分段卡出现」或「报错文案出现」为止（最多 60 秒）。
            for _ in range(120):
                page.wait_for_timeout(500)
                _items = page.locator(".preview-item").count()
                _body = active_step_body() or dlg_text()
                if _items > 0 or "生成预览失败" in _body:
                    break
            page.wait_for_timeout(500)
            body_prev = active_step_body() or dlg_text()
            notif_prev = page.evaluate(
                "() => [...document.querySelectorAll('.notice, .el-message')].map(e => e.innerText).join('\\n')"
            )
            # ★ 「生成预览失败」是模板里的**静态标签**（<b>生成预览失败：</b>{{ previewError }}），
            #   出现即代表预览报了错；真正的原因在标签后面 —— 所以要把详情打出来。
            idx = body_prev.find("生成预览失败")
            detail = body_prev[idx : idx + 200] if idx >= 0 else ""
            check(idx < 0, f"点「生成预览」不报错（错误详情：{detail!r}）")
            # ★ 精确信号：预览区**真的渲染了分段卡**（不是"页面里有『字』就算过"）
            prev_items, prev_head = page.evaluate(
                """() => {
                    const items = [...document.querySelectorAll('.preview-item')]
                        .filter(x => x.offsetParent !== null);
                    const head = [...document.querySelectorAll('span')]
                        .map(x => (x.innerText || '').trim())
                        .find(t => t.includes('切分预览')) || '';
                    return [items.length, head];
                }"""
            )
            check(
                prev_items > 0,
                f"★ 预览区真的渲染了分段卡（{prev_items} 张；标题「{prev_head}」）",
            )
            check(
                "共" in prev_head and "段" in prev_head,
                f"预览标题给出分段数（实际「{prev_head}」）",
            )

            click_btn("开始导入")
            page.wait_for_timeout(3000)
            body3 = active_step_body() or dlg_text()
            notif = page.evaluate(
                "() => [...document.querySelectorAll('.notice, .el-message')].map(e => e.innerText).join('\\n')"
            )
            submit_ok = (
                "已创建" in body3
                or "已创建" in notif
                or "成功" in notif
                or ("导入结果" in dlg_text() and "失败" not in notif)
            )
            check(submit_ok, f"三项齐全能提交（body={body3[:70]!r} notice={notif[:80]!r}）")
            click_btn("完成")
            page.wait_for_timeout(1200)
            if dlg_visible():
                close_dlg()
                page.wait_for_timeout(800)

            # ────────────── 3. 文档密级 > 库 ceiling ⇒ 后端原因原文 ──────────────
            print("\n== 3. 文档密级 > 库 ceiling ⇒ 界面显示后端原因（含两个中文密级名） ==")
            page.goto(f"{BASE}/#/knowledge", wait_until="networkidle")
            page.wait_for_timeout(1500)
            card = page.locator(f".kb-card:has-text('{kb_name}')").first
            if card.count():
                card.click()
            else:
                # 兜底：点第一个可见卡
                page.locator(".kb-card").first.click()
            page.wait_for_timeout(1800)

            page.locator("button:has-text('上传文档')").first.click()
            page.wait_for_timeout(1200)
            set_files(".el-dialog input[type=file]", str(tmp)) or set_files("input[type=file]", str(tmp))
            page.wait_for_timeout(800)

            check(select_by_label("文种", "合同"), "能选中「文种」选项（合同）")
            check(select_by_label("责任部门", "法务部"), "能选中「责任部门」选项（法务部）")
            check(select_by_label("密级", "机密"), "能选中「密级」选项（机密）")
            page.wait_for_timeout(300)
            check(click_upload_btn(), "点了上传提交按钮")
            page.wait_for_timeout(2500)
            body_text = page.evaluate("() => document.body.innerText")
            m = CEILING_RE.search(body_text)
            print("   页面命中 ceiling 文案:", m.group(0) if m else "（未命中）")
            idx = body_text.find("文档密级")
            snippet = body_text[idx : idx + 80] if idx >= 0 else body_text[-200:]
            check(bool(m), f"界面显示后端 ceiling 原因（含两个中文密级名）；片段：{snippet!r}")
            if m:
                check(
                    m.group(1) == "机密" and m.group(2) == "秘密",
                    f"两个密级名是「机密」/「秘密」（实际 {m.group(1)}/{m.group(2)}）",
                )
            close_dlg()

            # ────────────── 4. 列表 total / 深链 404 ──────────────
            print("\n== 4. 列表 total 与可见条数一致 / 深链 404 可读 ==")
            page.goto(f"{BASE}/#/knowledge", wait_until="networkidle")
            page.wait_for_timeout(1500)
            total_text = page.evaluate(
                """() => {
                    const el = [...document.querySelectorAll('.filters .micro, .filters span')]
                        .map(e => (e.innerText || '').trim())
                        .find(t => t.startsWith('共'));
                    return el || '';
                }"""
            )
            m_total = re.search(r"共\s*(\d+)\s*个", total_text)
            total = int(m_total.group(1)) if m_total else -1
            card_count = page.locator(".kb-card").count()
            print(f"   total 文案：{total_text!r} → {total}；卡片数：{card_count}")
            check(total >= 0, f"列表有「共 N 个」文案（实际：{total_text!r}）")
            check(total == card_count, f"total（{total}）与可见条数（{card_count}）一致")

            page.goto(f"{BASE}/#/knowledge?kb=no-such-kb-{ts}", wait_until="networkidle")
            page.wait_for_timeout(3000)
            deep_text = page.evaluate("() => document.body.innerText")
            has_readable = any(
                k in deep_text
                for k in ("找不到", "没有访问权限", "已被删除", "不存在", "加载失败", "回到知识库列表", "回到列表")
            )
            check(len(deep_text.strip()) > 40, "深链 404 不是白屏")
            check(has_readable, f"深链 404 有可读提示（片段：{deep_text[:120]!r}）")
            check("Uncaught" not in deep_text and "TypeError" not in deep_text, "深链 404 不是 JS 报错弹窗")

            print(f"\n控制台错误：{errs if errs else 0}")
            b.close()
    finally:
        # 真栈清库/清临时模型；mock 随浏览器关掉即消失
        if token and not token.startswith("mock_"):
            if fixture_model_id:
                try:
                    api_json(api_origin, "DELETE", f"/models/{fixture_model_id}", token)
                    print(f"   已清理临时向量模型 id={fixture_model_id}")
                except Exception as e:
                    print(f"   (清理向量模型跳过: {e})")
            try:
                listed = api_json(api_origin, "GET", f"/knowledge-bases?keyword={kb_name}", token)
                data = listed.get("data") or {}
                rows = data.get("list") if isinstance(data, dict) else data
                for it in rows or []:
                    if it.get("name") == kb_name and it.get("id"):
                        api_json(api_origin, "DELETE", f"/knowledge-bases/{it['id']}", token)
                        print(f"   已清理临时库 {kb_name} id={it['id']}")
            except Exception as e:
                print(f"   (清理跳过 {kb_name}: {e})")
        try:
            if tmp.exists():
                tmp.unlink()
        except Exception:
            pass

    bad = [m for c, m in ok if not c]
    print("=" * 60)
    print(f"通过 {len(ok) - len(bad)}/{len(ok)}")
    for m in bad:
        print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
