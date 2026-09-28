"""模型创建表单业务完整性闸门（浏览器实测）。

四件事 + 两处顺带修复，每条都是「用户能不能真的配出外部 API 模型」的必要条件：
  1. 表单顺序：顶部先定「供应商 + 服务地址 + 密钥」，再谈模型类型/基础模型
  2. 目录跟着上游走：改 apiBase / apiKey / 供应商 ⇒ 目录重新拉，且内容随上游变化
  3. 外部 API 必填校验：选「外部API」不填 apiBase 提交 ⇒ 表单报错拦下
  4. 空目录给可操作提示：不是干巴巴的「没有匹配的基础模型」
  5. mock 的 /models/base-models 必须是 POST（契约已改；GET 会让 mock 模式整个目录拉不动）
  6. 编辑弹窗：apiKey 不回填、服务地址可改

外加「目录里没有也能注册」三件（基础模型可手填 / 向量维度可手填 / 来源标注可区分）：
  7. 目录里没有目标模型时，能手填基础模型名并提交成功
  8. 向量维度能手填（探不出/无目录值）；非法值（0/负数/非数字）被拦
  9. 手填的来源标注可见，与探测/目录值可区分

夹具自备（mock / 真栈同一套逻辑，不为 mock 分叉）：
  · 双上游目录：闸门自己 subprocess 起第二套 openai_stub.py（换端口 + --dim 不同，
    参考 backend ModelApiTest），断言两个 apiBase 的目录都非空且内容不同，跑完杀掉。
    目录指纹取「名称 + 维度/窗口」——两套桩对话模型同名，--dim 只体现在向量模型上，
    所以切到「向量模型」再比，差异才落在用户可见的目录项里。
  · 编辑入口：真栈空库时列表没有「编辑」按钮，先用页面同一套登录态走接口注册一条临时模型，
    断言后删掉。mock 种子数据 / 库里已有模型 = 等价物，直接复用，不再造数。

用法：python check_models_form.py [base]
"""
from __future__ import annotations

import json
import re
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path
from urllib.parse import quote

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280/"
ROOT = Path(__file__).resolve().parent
STUB_SCRIPT = ROOT.parent / "backend" / "tools" / "openai_stub.py"


# ────────────────────────── 夹具：第二套桩 ──────────────────────────


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def launch_stub(port: int, dim: int) -> subprocess.Popen:
    """起一套 openai_stub（换端口、--dim 不同，与 ModelApiTest 同思路）。"""
    return subprocess.Popen(
        [
            sys.executable,
            str(STUB_SCRIPT),
            "--host",
            "127.0.0.1",
            "--port",
            str(port),
            "--dim",
            str(dim),
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def await_stub(port: int, timeout: float = 5.0) -> None:
    deadline = time.time() + timeout
    url = f"http://127.0.0.1:{port}/v1/models"
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(url, timeout=1) as resp:
                if resp.status == 200:
                    return
        except Exception:
            time.sleep(0.15)
    raise RuntimeError(f"桩服务启动失败（端口 {port}）")


def stop_stub(proc: subprocess.Popen | None) -> None:
    if not proc:
        return
    proc.kill()
    try:
        proc.wait(timeout=3)
    except Exception:
        pass


# ────────────────────────── 夹具：接口造数/清理 ──────────────────────────


def api_json(
    base: str,
    method: str,
    path: str,
    token: str,
    body: dict | None = None,
) -> dict:
    """走页面同一套登录态调 /api/v1。返回统一包络 {code, message, data, hint}。"""
    url = base.rstrip("/") + "/api/v1" + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {token}",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        raw = resp.read().decode("utf-8")
    return json.loads(raw) if raw else {}


def api_create_temp_model(base: str, token: str) -> str | None:
    """注册一条临时对话模型，返回 id。失败返回 None（调用方再看列表现状）。"""
    catalog = api_json(
        base,
        "POST",
        "/models/base-models",
        token,
        {"kind": "CHAT", "supplier": "LOCAL_INFERENCE"},
    )
    items = catalog.get("data") or []
    base_model = items[0]["baseModel"] if items else "stub-plain"
    name = f"闸门临时-{int(time.time())}"
    created = api_json(
        base,
        "POST",
        "/models",
        token,
        {
            "name": name,
            "kind": "CHAT",
            "supplier": "LOCAL_INFERENCE",
            "baseModel": base_model,
        },
    )
    data = created.get("data") or {}
    return data.get("id") or None


def api_delete_model(base: str, token: str, model_id: str) -> None:
    api_json(base, "DELETE", f"/models/{model_id}", token)


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

    stub2: subprocess.Popen | None = None
    stub2_port: int | None = None
    created_id: str | None = None
    ui_created_names: list[str] = []
    token = ""
    api_origin = BASE

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
                """目录项：name 只取基础模型名；text 是整块可见文案（含维度/窗口）。"""
                return page.evaluate(
                    """() => {
                        const dlg = [...document.querySelectorAll('.el-dialog')]
                            .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                        if (!dlg) return { items: [], empty: '', loading: true };
                        const items = [...dlg.querySelectorAll('.catalog-item')].map(b => {
                            const name = ((b.querySelector('.ci-name') || b).innerText || '').trim();
                            const text = (b.innerText || '').trim().replace(/\\s+/g, ' ');
                            return { name, text };
                        });
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

            def click_radio_button(text: str) -> bool:
                """点弹窗里的 radio-button（供应商 / 模型类型）。"""
                return page.evaluate(
                    """(text) => {
                        const dlg = [...document.querySelectorAll('.el-dialog')]
                            .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                        if (!dlg) return false;
                        for (const el of dlg.querySelectorAll('.el-radio-button, .el-radio')) {
                            const t = (el.innerText || '').trim();
                            if (t === text || t.includes(text)) {
                                el.click();
                                const input = el.querySelector('input');
                                if (input) {
                                    input.click();
                                    input.dispatchEvent(new Event('change', { bubbles: true }));
                                }
                                return true;
                            }
                        }
                        return false;
                    }""",
                    text,
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
            # 切到「向量模型」：两套桩的对话模型同名，--dim 差异只体现在向量维度上，
            # 目录指纹（名称 + 维度）才能把两套上游区分开（与 ModelApiTest 断言 dimension 同思路）。
            clicked_kind = click_radio_button("向量模型") or click_radio_button("向量")
            check(bool(clicked_kind), "能切换到「向量模型」类型（看维度差异）")
            page.wait_for_timeout(1200)

            default_cat = catalog_state()
            print(
                "   默认目录:",
                [it["name"] for it in default_cat["items"][:6]],
                "empty:",
                default_cat["empty"][:40],
            )
            check(len(default_cat["items"]) > 0, f"默认上游能拉到基础模型（{len(default_cat['items'])} 个）")
            default_fp = {it["text"] for it in default_cat["items"]}

            # 闸门自备第二套桩：换端口 + 不同 --dim ⇒ 目录内容可区分
            stub2_port = free_port()
            stub2 = launch_stub(stub2_port, 512)
            await_stub(stub2_port)
            # ★ 用 localhost 而不是 127.0.0.1：mock 的「死上游」正则含 127.0.0.1:1，
            #   会把 127.0.0.1:1xxxx 这类端口误判成不可达。
            second_base = f"http://localhost:{stub2_port}/v1"
            filled = fill_form_item("服务地址", second_base) or fill_form_item("apiBase", second_base)
            check(filled, "能填入服务地址")
            page.wait_for_timeout(1600)  # 等重新拉目录（去抖 + 延迟）
            other_cat = catalog_state()
            print(
                "   第二套上游目录:",
                [it["name"] for it in other_cat["items"][:6]],
                "empty:",
                other_cat["empty"][:40],
            )
            other_fp = {it["text"] for it in other_cat["items"]}
            check(
                len(other_cat["items"]) > 0 and bool(other_fp) and other_fp != default_fp,
                f"改 apiBase 后目录内容跟着变（default={default_cat['items'][:2]} vs stub2={other_cat['items'][:2]}）",
            )

            # 再换成死地址 ⇒ 空目录 + 可操作 hint
            filled2 = fill_form_item("服务地址", "http://dead.mock/v1") or fill_form_item(
                "apiBase", "http://dead.mock/v1"
            )
            check(filled2, "能再次改服务地址")
            page.wait_for_timeout(1600)
            dead_cat = catalog_state()
            print("   死上游目录:", [it["name"] for it in dead_cat["items"]], "empty:", dead_cat["empty"][:80])
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
            # ★ 等添加弹窗完全收起再点「编辑」：共用同一个 ModelFormDialog，
            #   关闭动画未结束时 open() 会和 v-model 抢可见性，编辑弹窗打不开（偶发）。
            try:
                page.wait_for_function(
                    """() => ![...document.querySelectorAll('.el-dialog')].some(
                        d => d.offsetParent !== null && d.getBoundingClientRect().width > 0)""",
                    timeout=4000,
                )
            except Exception:
                pass
            page.wait_for_timeout(400)

            def edit_btn_count() -> int:
                return page.locator(".model-card button:has-text('编辑')").count()

            # 真栈空库时先造一条（接口 + 页面同一套登录态）；mock 种子 / 库里已有 ⇒ 等价物直接用
            if edit_btn_count() == 0:
                try:
                    created_id = api_create_temp_model(api_origin, token)
                    print(f"   已注册临时模型 id={created_id}")
                except Exception as e:
                    print(f"   接口注册临时模型失败：{e}")
                if created_id:
                    page.click("button:has-text('刷新')")
                    page.wait_for_timeout(1200)

            # 打开某个已有模型的「编辑」（列表卡片里那个）
            edit_btn = page.locator(".model-card button:has-text('编辑')").first
            has_edit = edit_btn.count() > 0
            check(has_edit, "模型列表里有「编辑」入口")
            if has_edit:
                try:
                    edit_btn.click(timeout=8000, force=True)
                    page.wait_for_timeout(1200)
                    edit_items = dialog_form_items()
                    if not edit_items:
                        # 编辑弹窗内容异步挂载 —— 再等一拍
                        page.wait_for_timeout(800)
                        edit_items = dialog_form_items()
                    if not edit_items:
                        try:
                            page.wait_for_function(
                                """() => {
                                    const dlg = [...document.querySelectorAll('.el-dialog')]
                                        .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                                    return !!dlg && dlg.querySelectorAll('.el-form-item').length > 0;
                                }""",
                                timeout=3000,
                            )
                            edit_items = dialog_form_items()
                        except Exception:
                            pass
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

            # ── 6. 手填基础模型（目录里没有时的逃生口）──────────────
            print("\n== 6. 目录里没有目标模型时，能手填基础模型名并提交成功 ==")
            page.click("button:has-text('添加模型')")
            page.wait_for_timeout(1200)

            # 让目录为空 ⇒「目录里没有」是字面事实
            fill_form_item("服务地址", "http://dead.mock/v1") or fill_form_item(
                "apiBase", "http://dead.mock/v1"
            )
            page.wait_for_timeout(1600)
            empty_cat = catalog_state()
            check(len(empty_cat["items"]) == 0, f"前置：死上游下目录为空（{len(empty_cat['items'])} 项）")

            def click_text_in_dialog(text: str) -> bool:
                # 优先点 button：空态文案里也可能含「手动填写」，点到 div 上不会切换模式
                return page.evaluate(
                    """(text) => {
                        const dlg = [...document.querySelectorAll('.el-dialog')]
                            .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                        if (!dlg) return false;
                        const candidates = [...dlg.querySelectorAll('button, a.el-button, [role=button]')];
                        for (const el of candidates) {
                            const t = (el.innerText || '').trim();
                            if (t === text || t.includes(text)) { el.click(); return true; }
                        }
                        // 兜底：叶子节点（且不是表单展示块）
                        for (const el of dlg.querySelectorAll('span, a')) {
                            const t = (el.innerText || '').trim();
                            if ((t === text || t.includes(text)) && el.children.length === 0
                                && !el.closest('.picked')) {
                                el.click(); return true;
                            }
                        }
                        return false;
                    }""",
                    text,
                )

            clicked_manual = click_text_in_dialog("手动填写") or click_text_in_dialog("目录里没有")
            check(bool(clicked_manual), "目录区有「手动填写」逃生口（可点击）")
            page.wait_for_timeout(400)

            # 手填基础模型名。后端 create 不校验 baseModel（字符串，load 时才验）；
            # mock 已与后端对齐 —— 任意名都可注册，陌生名应直接提交成功。
            def fill_base_input(val: str) -> bool:
                """填「基础模型」文本框：优先表单项 label，再按 placeholder/类名兜底。"""
                if fill_form_item("基础模型", val):
                    return True
                return page.evaluate(
                    """(val) => {
                        const dlg = [...document.querySelectorAll('.el-dialog')]
                            .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                        if (!dlg) return false;
                        for (const input of dlg.querySelectorAll('input')) {
                            const ph = (input.placeholder || '') + ' ' + (input.className || '');
                            if (/基础模型|baseModel|手动/i.test(ph) && !input.disabled) {
                                const setter = Object.getOwnPropertyDescriptor(
                                    window.HTMLInputElement.prototype, 'value')?.set;
                                setter.call(input, val);
                                input.dispatchEvent(new Event('input', { bubbles: true }));
                                input.dispatchEvent(new Event('change', { bubbles: true }));
                                return true;
                            }
                        }
                        return false;
                    }""",
                    val,
                )

            manual_try = f"manual-escape-{int(time.time())}"
            filled_manual = fill_base_input(manual_try)
            check(bool(filled_manual), f"能手填基础模型名（{manual_try!r}）")

            fill_form_item("模型名称", f"手填基础探针-{int(time.time())}")
            probe_name = page.evaluate(
                """() => {
                    const dlg = [...document.querySelectorAll('.el-dialog')]
                        .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                    if (!dlg) return '';
                    for (const it of dlg.querySelectorAll('.el-form-item')) {
                        const lab = it.querySelector('.el-form-item__label');
                        if (lab && lab.innerText.trim() === '模型名称') {
                            return (it.querySelector('input') || {}).value || '';
                        }
                    }
                    return '';
                }"""
            )
            page.wait_for_timeout(300)

            def submit_dialog() -> bool:
                return page.evaluate(
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

            def dialog_open() -> bool:
                return page.evaluate(
                    """() => {
                        return [...document.querySelectorAll('.el-dialog')].some(
                            d => d.offsetParent !== null && d.getBoundingClientRect().width > 0
                                && (d.innerText || '').includes('基础模型'));
                    }"""
                )

            def form_errors() -> list[str]:
                return page.evaluate(
                    """() => {
                        const dlg = [...document.querySelectorAll('.el-dialog')]
                            .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                        if (!dlg) return [];
                        return [...dlg.querySelectorAll('.el-form-item__error')].map(e => e.innerText.trim());
                    }"""
                )

            submit_dialog()
            page.wait_for_timeout(1200)

            # mock 已与后端对齐（baseModel 不校验）—— 陌生名直接提交成功，不必退回种子名
            check(not dialog_open(), "手填基础模型后提交成功（弹窗关闭）")
            if probe_name:
                ui_created_names.append(probe_name)
            # 若仍开着（红阶段/校验失败）——关掉再开下一节，避免遮罩挡住「添加模型」
            if dialog_open():
                page.keyboard.press("Escape")
                page.wait_for_timeout(400)
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
                page.wait_for_timeout(600)

            # ── 7. 向量维度可手填 + 非法值被拦 ─────────────────────
            print("\n== 7. 向量维度可手填（探不出/无目录值）；非法值被拦 ==")
            page.click("button:has-text('添加模型')")
            page.wait_for_timeout(1200)
            click_radio_button("向量模型") or click_radio_button("向量")
            page.wait_for_timeout(800)

            # 目录仍为空 + 手填一个向量基础模型 ⇒ 维度无从带出，必须能手填
            fill_form_item("服务地址", "http://dead.mock/v1") or fill_form_item(
                "apiBase", "http://dead.mock/v1"
            )
            page.wait_for_timeout(1200)
            clicked_manual2 = click_text_in_dialog("手动填写") or click_text_in_dialog("目录里没有")
            check(bool(clicked_manual2), "向量模型下也有「手动填写」逃生口")
            page.wait_for_timeout(300)

            def fill_dimension(val: str) -> bool:
                return page.evaluate(
                    """(val) => {
                        const dlg = [...document.querySelectorAll('.el-dialog')]
                            .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                        if (!dlg) return false;
                        for (const it of dlg.querySelectorAll('.el-form-item')) {
                            const lab = it.querySelector('.el-form-item__label');
                            if (!lab || !lab.innerText.trim().includes('向量维度')) continue;
                            const input = it.querySelector('input, textarea');
                            if (!input || input.disabled) return false;
                            const setter = Object.getOwnPropertyDescriptor(
                                window.HTMLInputElement.prototype, 'value')?.set
                                || Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value')?.set;
                            setter.call(input, val);
                            input.dispatchEvent(new Event('input', { bubbles: true }));
                            input.dispatchEvent(new Event('change', { bubbles: true }));
                            input.dispatchEvent(new Event('blur', { bubbles: true }));
                            return true;
                        }
                        return false;
                    }""",
                    val,
                )

            dim_is_input = page.evaluate(
                """() => {
                    const dlg = [...document.querySelectorAll('.el-dialog')]
                        .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                    if (!dlg) return false;
                    for (const it of dlg.querySelectorAll('.el-form-item')) {
                        const lab = it.querySelector('.el-form-item__label');
                        if (lab && lab.innerText.trim().includes('向量维度')) {
                            return !!it.querySelector('input, textarea');
                        }
                    }
                    return false;
                }"""
            )
            check(bool(dim_is_input), "向量维度是可编辑输入框（不是纯展示）")

            emb_name = f"手填维度探针-{int(time.time())}"
            fill_base_input("BGE-M3")
            fill_form_item("模型名称", emb_name)
            page.wait_for_timeout(200)

            for bad in ("0", "-1", "abc"):
                fill_dimension(bad)
                page.wait_for_timeout(250)
                submit_dialog()
                page.wait_for_timeout(700)
                errs_bad = form_errors()
                check(
                    dialog_open() and any("维度" in e or "正整数" in e or "数字" in e for e in errs_bad),
                    f"向量维度非法值 {bad!r} 被拦（errors={errs_bad}）",
                )

            # 合法正整数 ⇒ 必须真的提交成功（弹窗关闭），不只是「没有维度错误」
            fill_dimension("768")
            page.wait_for_timeout(250)
            fill_form_item("模型名称", emb_name)  # 防止上一轮校验态干扰
            submit_dialog()
            page.wait_for_timeout(1200)
            dim_ok_errors = form_errors()
            check(
                not dialog_open(),
                f"向量维度填合法正整数 768 提交成功（open={dialog_open()}, errors={dim_ok_errors}）",
            )
            if not dialog_open():
                ui_created_names.append(emb_name)

            # ── 8. 来源标注：手填 vs 目录/探测 可区分 ───────────────
            print("\n== 8. 来源标注：手填的来源可见，与探测/目录可区分 ==")
            if not dialog_open():
                page.click("button:has-text('添加模型')")
                page.wait_for_timeout(1000)
                click_text_in_dialog("手动填写") or click_text_in_dialog("目录里没有")
                page.wait_for_timeout(300)
                fill_base_input("Qwen3-8B")

            def source_tags_text() -> list[str]:
                return page.evaluate(
                    """() => {
                        const dlg = [...document.querySelectorAll('.el-dialog')]
                            .find(d => d.offsetParent !== null && d.getBoundingClientRect().width > 0);
                        if (!dlg) return [];
                        return [...dlg.querySelectorAll('.el-tag, .source-tag, [class*=source]')]
                            .map(e => (e.innerText || '').trim())
                            .filter(t => t.includes('来源') || t.includes('手填') || t.includes('探测') || t.includes('目录') || t.includes('MANUAL') || t.includes('PROBED') || t.includes('CATALOG'));
                    }"""
                )

            tags_manual = source_tags_text()
            print("   手填后的来源标注:", tags_manual)
            check(
                any(("手填" in t or "MANUAL" in t) for t in tags_manual),
                f"手填后来源标注可见且为「手填/MANUAL」（实际：{tags_manual}）",
            )

            # 切到向量 + 手填维度 ⇒ 维度来源也应是「手填」
            click_radio_button("向量模型") or click_radio_button("向量")
            page.wait_for_timeout(600)
            click_text_in_dialog("手动填写") or click_text_in_dialog("目录里没有")
            page.wait_for_timeout(300)
            fill_base_input("BGE-M3")
            fill_dimension("768")
            page.wait_for_timeout(300)
            tags_dim = source_tags_text()
            print("   手填维度后的来源标注:", tags_dim)
            check(
                any(("手填" in t or "MANUAL" in t) for t in tags_dim),
                f"手填维度后来源标注为「手填/MANUAL」（实际：{tags_dim}）",
            )

            # 与「探测」可区分：对话模型点自动探测，能力来源应变「探测/PROBED」，
            # 且与手填标注文案不同 —— 不要混成一个数字/一个标签。
            click_radio_button("对话模型") or click_radio_button("对话")
            page.wait_for_timeout(600)
            click_text_in_dialog("手动填写") or click_text_in_dialog("目录里没有")
            page.wait_for_timeout(300)
            fill_base_input("Qwen3-8B")
            page.wait_for_timeout(200)
            click_text_in_dialog("自动探测")
            page.wait_for_timeout(1000)
            tags_after_probe = source_tags_text()
            print("   探测后的来源标注:", tags_after_probe)
            has_probed = any("PROBED" in t or ("探测" in t and "手填" not in t) for t in tags_after_probe)
            has_manual = any("手填" in t or "MANUAL" in t for t in tags_after_probe)
            # 手填标注必须还在（能力是探测的，基础模型/维度仍可标手填），
            # 且「探测」与「手填」是不同文案 —— 分得清测出来的 / 填的。
            check(
                has_manual,
                f"探测后手填标注仍可见（探测={has_probed}, tags={tags_after_probe}）",
            )
            if has_probed:
                probed_txt = [t for t in tags_after_probe if "PROBED" in t or ("探测" in t and "手填" not in t)]
                manual_txt = [t for t in tags_after_probe if "手填" in t or "MANUAL" in t]
                check(
                    bool(probed_txt) and bool(manual_txt) and all(a != b for a in probed_txt for b in manual_txt),
                    f"手填标注与探测标注不同文案、不混成一个（探测={probed_txt}, 手填={manual_txt}）",
                )
            else:
                check(
                    has_manual,
                    f"探测未命中时手填标注仍可见（tags={tags_after_probe}）",
                )

            print(f"\n控制台错误：{errs if errs else 0}")
            b.close()
    finally:
        # 清理临时模型 + 第二套桩 —— 无论断言红绿都要收干净
        if created_id and token:
            try:
                api_delete_model(api_origin, token, created_id)
                print(f"   已清理临时模型 id={created_id}")
            except Exception as e:
                print(f"   ! 清理临时模型失败：{e}")
        # mock 的 /api 是浏览器内拦截，Python 侧 urllib 打到的是 vite 代理后的真后端 ——
        # 只有真栈（JWT）才需要清库；mock 的临时模型随浏览器关掉就没了。
        if token and not token.startswith("mock_"):
            for nm in ui_created_names:
                if not nm:
                    continue
                try:
                    listed = api_json(api_origin, "GET", f"/models?keyword={quote(nm)}", token)
                    data = listed.get("data") or {}
                    items = data.get("list") if isinstance(data, dict) else data
                    for it in items or []:
                        if it.get("name") == nm and it.get("id"):
                            api_delete_model(api_origin, token, it["id"])
                            print(f"   已清理 UI 临时模型 {nm} id={it['id']}")
                except Exception as e:
                    print(f"   (清理跳过 {nm}: {e})")
        stop_stub(stub2)

    bad = [m for c, m in ok if not c]
    print("=" * 60)
    print(f"通过 {len(ok) - len(bad)}/{len(ok)}")
    for m in bad:
        print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
