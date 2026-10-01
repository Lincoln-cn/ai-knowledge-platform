"""知识库文档「解析结果」视图闸门（浏览器实测）。

三件事，对应 0.2.1 批 3 的演示判据：
  1. 解析结果视图能从文档行打开（不白屏、有关闭出口）
  2. Markdown 按结构渲染 —— 标题 / 列表 / 表格至少各一处
     （★ 不能是整段纯文本；结构要落在 DOM 的 h*/ul|ol/table 上）
  3. 失败原因可见 —— failReason + failHint 原文展示，不代写、不吞掉

夹具口径与 check_knowledge_form.py 一致：
  · mock 模式直接吃 fixtures/knowledge.ts 里带 parsedContent / failReason 的种子；
  · 真栈模式走同一套 UI 操作，断言「视图能开、结构随数据可见」——
    真栈后端若尚未在 DocumentView 出网 parsedContent，结构断言会给出可定位的差异信息
    （而不是静默跳过）。

用法：python check_knowledge_parse.py [base]
"""
from __future__ import annotations

import json
import re
import sys
import time
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280/"
# 归一化：去掉结尾斜杠，拼路径时用 BASE + "/#/..."，避免 5280//#/ 双斜杠
BASE = BASE.rstrip("/")

# 结构关键词：用于在解析结果面板里定位「标题 / 列表 / 表格」
HEADING_RE = re.compile(r"<h[1-6][\s>]", re.I)
LIST_RE = re.compile(r"<(ul|ol)[\s>]", re.I)
TABLE_RE = re.compile(r"<table[\s>]", re.I)

# 真栈夹具：与 check_knowledge_form.py 同口径 —— 用登录态走接口造数，跑完清掉
# 真栈夹具：与后端测试共用同一批样张（structured.md 有标题/列表/表格；empty.docx 必然解析失败）
FIXTURES = Path(__file__).resolve().parent.parent / "backend/src/test/resources/fixtures"
API = BASE + "/api/v1"


def api_json(method: str, path: str, token: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(
        API + path,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        method=method,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except Exception as e:
        return {"code": -1, "message": str(e)}


def api_multipart(path: str, token: str, fields: dict[str, str], file) -> dict:
    """真上传（multipart）。

    ★ 闸门夹具必须走真上传：0.2.1 起「建档即解析」只在**带了文件字节**时才发生，
      只传 names 的轻量建档不会解析 ⇒ parsedContent 必然空，闸门会红在一个与产品无关的地方
      （这条曾长期挂在「已知红项」里）。
    """
    boundary = "----gateparse0boundary"
    buf = b""
    for k, v in fields.items():
        buf += (
            f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'
        ).encode("utf-8")
    buf += (
        f'--{boundary}\r\nContent-Disposition: form-data; name="files"; '
        f'filename="{file.name}"\r\nContent-Type: application/octet-stream\r\n\r\n'
    ).encode("utf-8")
    buf += file.read_bytes() + b"\r\n"
    buf += f"--{boundary}--\r\n".encode("utf-8")
    req = urllib.request.Request(
        API + path,
        data=buf,
        method="POST",
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": f"Bearer {token}",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.loads(resp.read().decode("utf-8") or "{}")
    except Exception as e:  # noqa: BLE001
        return {"code": -1, "message": str(e)}


def main() -> int:
    ok: list[tuple[bool, str]] = []

    def check(cond: bool, msg: str) -> None:
        ok.append((cond, msg))
        print(("  ✓ " if cond else "  ✗ ") + msg)

    token = ""
    fixture_kb_id: str | None = None
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
            page.wait_for_timeout(800)
            page.fill("input[autocomplete='username']", "admin")
            page.fill("input[autocomplete='current-password']", "admin")
            page.click("button:has-text('登录')")
            page.wait_for_timeout(2500)
            token = page.evaluate("() => localStorage.getItem('access_token') || ''")
            is_real = bool(token) and not token.startswith("mock_")

            # ────────────── 真栈夹具：空库时造一个带文档的库 ──────────────
            ts = int(time.time())
            if is_real:
                mlist = api_json("GET", "/models?kind=EMBEDDING&size=50", token)
                mrows = (mlist.get("data") or {}).get("list") or []
                if not mrows:
                    created = api_json(
                        "POST",
                        "/models",
                        token,
                        {
                            "name": f"解析闸门向量-{ts}",
                            "kind": "EMBEDDING",
                            "supplier": "LOCAL_INFERENCE",
                            "baseModel": "bge-m3",
                            "dimension": 1024,
                        },
                    )
                    fixture_model_id = (created.get("data") or {}).get("id")
                else:
                    fixture_model_id = mrows[0].get("id")

                kb = api_json(
                    "POST",
                    "/knowledge-bases",
                    token,
                    {
                        "name": f"解析闸门库-{ts}",
                        "category": "制度规范",
                        "description": "解析结果视图闸门夹具",
                        "importType": "LOCAL",
                        "embeddingModelId": fixture_model_id,
                        "maxClassification": "SECRET",
                    },
                )
                fixture_kb_id = (kb.get("data") or {}).get("id")
                print(f"   夹具：真栈建库 id={fixture_kb_id}")
                if fixture_kb_id:
                    meta = {"docType": "制度", "department": "采购部", "classification": "INTERNAL"}
                    # ① 结构化文档（标题/列表/表格都有）⇒ 验「解析结果按结构渲染」
                    ok_doc = api_multipart(
                        f"/knowledge-bases/{fixture_kb_id}/documents", token, meta, FIXTURES / "structured.docx"
                    )
                    fixture_doc_id = ((ok_doc.get("data") or [{}])[0]).get("id")
                    print(f"   夹具：上传 structured.docx → doc={fixture_doc_id}")
                    # ② 空文档 ⇒ 验「0 字符 ≠ 成功」的失败原因可见（0.2.1 的 DOC-I2）
                    bad_doc = api_multipart(
                        f"/knowledge-bases/{fixture_kb_id}/documents", token, meta, FIXTURES / "empty.docx"
                    )
                    fixture_bad_id = ((bad_doc.get("data") or [{}])[0]).get("id")
                    print(f"   夹具：上传 empty.docx（预期解析失败）→ doc={fixture_bad_id}")

            # ────────────── 进入知识库 → 文档面板 ──────────────
            page.goto(f"{BASE}/#/knowledge", wait_until="networkidle")
            page.wait_for_timeout(2500)
            # 点第一个可见库卡进详情（mock 与真栈同一入口）
            card = page.locator(".kb-card").first
            if card.count():
                card.click()
                page.wait_for_timeout(1800)
            else:
                check(False, "知识库列表有可点的库卡")
                print("=" * 60)
                print(f"通过 0/{len(ok)}")
                return 1

            # ────────────── 1. 解析结果视图能打开 ──────────────
            print("== 1. 解析结果视图能打开 ==")
            def open_parse_panel(name_hint: str = "") -> bool:
                """点开指定行（按名字定位）的「解析结果」。

                ★ 行序随上传时间变：不按名字定位，闸门在真栈上会开到"刚上传的失败样张"，
                  然后拿「结构渲染」去断言一篇本来就解析失败的文档 —— 红在一个与产品无关的地方。
                """
                clicked = page.evaluate(
                    """(name) => {
                        const rows = [...document.querySelectorAll('.el-table__row')];
                        const row = name ? rows.find(r => (r.innerText || '').includes(name)) : rows[0];
                        if (!row) return false;
                        const btn = [...row.querySelectorAll('button')]
                            .find(b => (b.innerText || '').includes('解析结果'));
                        if (btn) { btn.click(); return true; }
                        return false;
                    }""",
                    name_hint,
                )
                if clicked:
                    page.wait_for_timeout(900)
                return bool(clicked)

            parse_btn = page.locator("button:has-text('解析结果')").first
            has_btn = parse_btn.count() > 0
            check(has_btn, "文档行有「解析结果」入口")
            if not has_btn:
                print("   （后续检查依赖解析结果视图，提前结束）")
                print(f"\n控制台错误：{errs if errs else 0}")
                b.close()
                bad = [m for c, m in ok if not c]
                print("=" * 60)
                print(f"通过 {len(ok) - len(bad)}/{len(ok)}")
                for m in bad:
                    print("  - " + m)
                return 1

            # 真栈打开结构化样张（structured.md）；mock 的种子名不同 ⇒ 回退到第一行
            if not open_parse_panel("structured.docx"):
                parse_btn.click()
                page.wait_for_timeout(900)

            def parse_panel_text() -> str:
                return page.evaluate(
                    """() => {
                        // 解析结果是 dialog / drawer / 独立面板皆可 —— 取可见且带「解析」标题的那一块
                        const cands = [...document.querySelectorAll('.el-dialog, .el-drawer, .parse-result-panel')]
                            .filter(x => x.offsetParent !== null && x.getBoundingClientRect().width > 0);
                        const panel = cands.find(x => /解析/.test(x.innerText || ''));
                        return panel ? (panel.innerText || '') : '';
                    }"""
                )

            def parse_panel_html() -> str:
                return page.evaluate(
                    """() => {
                        const cands = [...document.querySelectorAll('.el-dialog, .el-drawer, .parse-result-panel')]
                            .filter(x => x.offsetParent !== null && x.getBoundingClientRect().width > 0);
                        const panel = cands.find(x => /解析/.test(x.innerText || ''));
                        return panel ? (panel.innerHTML || '') : '';
                    }"""
                )

            def result_body_html() -> str:
                """只取解析正文区（.result-body / .md-preview），排除弹窗标题等干扰。"""
                return page.evaluate(
                    """() => {
                        const el = document.querySelector('.el-dialog .result-body, .el-drawer .result-body')
                                || document.querySelector('.el-dialog .md-preview, .el-drawer .md-preview');
                        return el ? (el.innerHTML || '') : '';
                    }"""
                )

            ptext = parse_panel_text()
            check(len(ptext.strip()) > 10, f"解析结果视图已打开且非空白（文本长度 {len(ptext.strip())}）")

            # ────────────── 2. Markdown 结构可见 ──────────────
            print("\n== 2. Markdown 结构可见（标题/列表/表格至少各一处） ==")
            bhtml = result_body_html() or parse_panel_html()
            # 真栈若 DocumentView 尚未出网 parsedContent，正文区只会是空态 ——
            # 把「后端缺口」和「前端没渲染」区分开，报错才可定位
            empty_hint = page.evaluate(
                """() => {
                    const el = document.querySelector('.el-dialog .empty-tip, .el-drawer .empty-tip');
                    return el ? (el.innerText || '') : '';
                }"""
            )
            body_filled = bool(bhtml.strip()) and not empty_hint
            shown = empty_hint.strip() or bhtml[:60]
            check(
                body_filled,
                f"解析正文非空态（有内容）—— 当前：{shown!r}"
                "。若真栈为空：多半是 DocumentView 未出网 parsedContent 或该文档尚未解析",
            )
            has_h = bool(HEADING_RE.search(bhtml))
            has_list = bool(LIST_RE.search(bhtml))
            has_table = bool(TABLE_RE.search(bhtml))
            print(f"   h={has_h} ul/ol={has_list} table={has_table}")
            check(has_h, f"解析正文里有标题结构 <h*>（片段：{bhtml[:120]!r}）")
            check(has_list, "解析正文里有列表结构 <ul>/<ol>")
            check(has_table, "解析正文里有表格结构 <table>")

            # ────────────── 3. 失败原因可见 ──────────────
            print("\n== 3. 失败原因可见（failReason + failHint 原文） ==")
            # 关掉当前解析面板，找失败文档
            page.keyboard.press("Escape")
            page.wait_for_timeout(500)

            # mock 种子（fixtures/knowledge.ts doc3）的 failReason / failHint 原文 —— 不许代写
            RAW_REASON = "该 PDF 为扫描件，未检测到文本层，无法提取内容。"
            RAW_HINT = "建议：转为可检索 PDF 或改用 Markdown 格式后重新上传。"
            # ★ 真栈：文案由**后端**给，闸门不代写 —— 直接问后端要这篇（empty.docx）的
            #   failReason/failHint，再断言界面上原文可见。
            #   （mock 模式下 Python 侧打到的是代理后的真后端，拿不到 mock 种子 ⇒ 仍用上面固定文案）
            if is_real and fixture_bad_id:
                docs = api_json("GET", f"/knowledge-bases/{fixture_kb_id}/documents?page=1&size=20", token)
                mine = next(
                    (
                        d
                        for d in ((docs.get("data") or {}).get("list") or [])
                        if d.get("id") == fixture_bad_id
                    ),
                    {},
                )
                RAW_REASON = (mine.get("failReason") or "").strip()
                RAW_HINT = (mine.get("failHint") or "").strip()
                print(f"   真栈空文档 failReason：{RAW_REASON!r}")
                print(f"   真栈空文档 failHint  ：{RAW_HINT!r}")
                check(
                    bool(RAW_REASON) and bool(RAW_HINT),
                    "空文档解析失败带可读原因 + 下一步建议（DOC-I2，不是堆栈）",
                )

            # 优先走「解析结果」—— 失败态原文应一并展示；退回既有「查看原因」展开
            parsed_fail_shown = False
            rows = page.locator(".el-table__row")
            for i in range(rows.count()):
                row = rows.nth(i)
                if not row.is_visible():
                    continue
                name_cell = row.inner_text()
                # 只开失败/扫描行，保证断言的就是 failReason 所在的文档
                # （★ 真栈的失败样张叫 empty.docx —— 它既不含「扫描」也不含「失败」）
                if is_real:
                    if "empty.docx" not in name_cell:
                        continue
                elif not ("扫描" in name_cell or "失败" in name_cell):
                    continue
                btn = row.locator("button:has-text('解析结果')")
                if not btn.count():
                    continue
                btn.first.click()
                page.wait_for_timeout(800)
                body = page.evaluate("() => document.body.innerText")
                # ★ 空串必须挡住：`"" in body` 永远为真 —— 那会变成「拿不到原因也算通过」
                if RAW_REASON and RAW_HINT and RAW_REASON in body and RAW_HINT in body:
                    parsed_fail_shown = True
                    check(True, "解析结果视图内 failReason 原文完整可见")
                    check(True, "解析结果视图内 failHint 原文完整可见")
                    break
                page.keyboard.press("Escape")
                page.wait_for_timeout(400)

            if not parsed_fail_shown:
                fail_btn = page.locator("button:has-text('查看原因')").first
                if fail_btn.count():
                    fail_btn.click()
                    page.wait_for_timeout(600)
                    body = page.evaluate("() => document.body.innerText")
                    check(
                        bool(RAW_REASON) and RAW_REASON in body,
                        f"failReason 原文可见（期望含：{RAW_REASON!r}）",
                    )
                    check(bool(RAW_HINT) and RAW_HINT in body, f"failHint 原文可见（期望含：{RAW_HINT!r}）")
                else:
                    check(False, "能找到失败原因的展示入口（解析结果或查看原因）")
                    check(False, "failHint 原文可见")

            print(f"\n控制台错误：{errs if errs else 0}")
            b.close()
    except Exception as e:
        print(f"  ✗ 脚本异常：{e}")
        ok.append((False, f"脚本异常：{e}"))
    finally:
        # 真栈清夹具；mock 随浏览器关掉即消失
        if token and not token.startswith("mock_"):
            for label, fn in (
                ("库", lambda: api_json("DELETE", f"/knowledge-bases/{fixture_kb_id}", token) if fixture_kb_id else None),
                ("模型", lambda: api_json("DELETE", f"/models/{fixture_model_id}", token) if fixture_model_id else None),
            ):
                try:
                    fn()
                    print(f"   已清理临时{label}")
                except Exception as e:
                    print(f"   (清理{label}跳过: {e})")

    bad = [m for c, m in ok if not c]
    print("=" * 60)
    print(f"通过 {len(ok) - len(bad)}/{len(ok)}")
    for m in bad:
        print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
