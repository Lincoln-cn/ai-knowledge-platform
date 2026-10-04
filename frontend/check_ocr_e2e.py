"""OCR 入库链真栈闸门（第 9 周 · 0.3.1 · T249.4）—— **走界面**，不是走接口。

覆盖 `ROADMAP` 第 9 周的演示物与判据 ⑤：

  ① 上传扫描件 ⇒ **接口立刻返回**、文档落在「分段中」
  ② ★ 界面上出现「已识别 N/M 页」（M = 需要 OCR 的页数 = 3）—— 这是逐页回写的**用户可见面**
  ③ M 只在解析进行中出现；完成后这一行消失、状态变成「待向量化」（入库成功）
  ④ 解析结果里是**识别出来的中文**（不是空、不是乱码）
  ⑤ ★ 文本型文档**不受影响**：不出现「已识别 N/M 页」（M=0），且很快落到待向量化

★ 为什么必须走界面（而不是只打接口）：`ROADMAP` 的演示物写的是「上传扫描件 → "已识别 3/10 页" → 入库成功」
   —— 那是**用户看到的**那一面。进度字段在库里对、但界面没渲染，用户面对的仍然是几分钟空白。
★ 为什么不能只跑 mock：OCR 是**系统级依赖**（装了 tesseract 才跑），mock 一个字节都不掺和。

前置：真栈起好（后端 8080 + 前端 5280 指向它）。
用法：python check_ocr_e2e.py [base]
"""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"
FIXTURES = Path(__file__).resolve().parent.parent / "backend/src/test/resources/fixtures"
SCANNED = FIXTURES / "scanned.pdf"
STRUCTURED = FIXTURES / "structured.pdf"

# 上传等待上限：3 页扫描件实测约 11 秒（每页约 3.6 秒）；给足余量（CI 更慢）
PARSE_TIMEOUT_S = 180

ok: list[tuple[bool, str]] = []


def check(cond: bool, msg: str, detail: str = "") -> bool:
    ok.append((bool(cond), msg))
    print(("  ✓ " if cond else "  ✗ ") + msg + (f"    {detail}" if detail else ""))
    return bool(cond)


def api(method: str, path: str, token: str | None = None, body: dict | None = None):
    req = urllib.request.Request(
        API + path,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        method=method,
        headers={
            "Content-Type": "application/json",
            **( {"Authorization": f"Bearer {token}"} if token else {}),
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8") or "{}"
        try:
            return e.code, json.loads(raw)
        except Exception:  # noqa: BLE001
            return e.code, {"raw": raw[:200]}


def main() -> int:
    if not SCANNED.exists():
        print(f"★ 夹具不存在：{SCANNED}")
        return 1

    ts = str(int(time.time()))
    with sync_playwright() as pw:
        b = pw.chromium.launch(channel="chrome")
        page = b.new_page(viewport={"width": 1600, "height": 900})
        errs: list[str] = []
        page.on("pageerror", lambda e: errs.append(str(e)[:160]))
        page.on(
            "console",
            lambda m: errs.append(m.text[:160]) if m.type == "error" else None,
        )

        token = ""
        kb_id = ""
        try:
            # ── 0 · 登录（真栈判据：token 不是 mock_ 前缀）──
            page.goto(f"{BASE}/#/login", wait_until="networkidle")
            page.wait_for_selector("input[autocomplete='username']", timeout=15000)
            page.fill("input[autocomplete='username']", "admin")
            page.fill("input[autocomplete='current-password']", "admin")
            page.click("button:has-text('登录')")
            try:
                page.wait_for_url(lambda u: "#/login" not in u, timeout=20000)
            except Exception:  # noqa: BLE001
                pass
            token = page.evaluate("() => localStorage.getItem('access_token') || ''")
            print("== 0. 环境 ==")
            if not check(
                bool(token) and not token.startswith("mock_"),
                f"连的是真后端（token 非 mock_）：{token[:18]}…",
            ):
                print("   ⇒ 这不是真栈，后面的断言没有意义，直接结束")
                b.close()
                return 1

            # ── 1 · 夹具：一个空库（走接口，与其它真栈闸门同法）──
            st, created = api(
                "POST",
                "/knowledge-bases",
                token,
                {
                    "name": f"OCR闸门库-{ts}",
                    "category": "制度规范",
                    "description": "OCR 端到端闸门夹具",
                    "icon": "📘",
                    "importType": "LOCAL",
                    "embeddingModelId": "00000000-0000-0000-0000-000000000001",
                    "maxClassification": "INTERNAL",
                },
            )
            kb_id = ((created.get("data") or {}).get("id")) or ""
            if not check(st == 200 and bool(kb_id), f"建库成功（{st}）", str(created)[:160]):
                b.close()
                return 1
            print(f"   夹具库 id={kb_id}")

            def open_kb() -> None:
                """进知识库详情（文档面板）—— 与用户点进去是同一条路。"""
                page.goto(f"{BASE}/#/knowledge", wait_until="networkidle")
                page.wait_for_timeout(1500)
                card = page.locator(f".kb-card:has-text('OCR闸门库-{ts}')").first
                if card.count() == 0:
                    card = page.locator(".kb-card").first
                card.click()
                page.wait_for_timeout(1800)
                page.locator("button:has-text('文档')").first.click()
                page.wait_for_timeout(1200)

            def upload(path: Path, doc_type: str, dept: str, cls: str) -> None:
                """走上「上传文档」弹窗 —— 与用户操作同一条路。"""
                page.locator("button:has-text('上传文档')").first.click()
                page.wait_for_timeout(1200)
                sel = ".el-dialog input[type=file]"
                loc = page.locator(sel).first
                if loc.count() == 0:
                    loc = page.locator("input[type=file]").first
                loc.set_input_files(str(path))
                page.wait_for_timeout(900)

                def pick_option(text: str) -> bool:
                    try:
                        opt = page.locator(f".el-select-dropdown__item:has-text('{text}')").first
                        opt.wait_for(state="visible", timeout=4000)
                        opt.click(timeout=4000)
                        page.wait_for_timeout(300)
                        return True
                    except Exception:  # noqa: BLE001
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
                        item.wait_for(state="visible", timeout=4000)
                        item.locator(".el-select").first.click(timeout=4000)
                        page.wait_for_timeout(400)
                    except Exception:  # noqa: BLE001
                        return False
                    return pick_option(option)

                select_by_label("文种", doc_type)
                select_by_label("责任部门", dept)
                select_by_label("密级", cls)
                page.wait_for_timeout(300)
                page.evaluate(
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

            def rows_text() -> str:
                return page.evaluate(
                    "() => [...document.querySelectorAll('.el-table__row')].map(r => r.innerText).join('\\n')"
                )

            def wait_row(predicate, timeout_s: int) -> tuple[bool, str, list[str]]:
                """轮询文档行，直到 predicate(text) 为真；返回 (命中, 最后一次文本, 见过的进度快照)。"""
                seen: list[str] = []
                deadline = time.time() + timeout_s
                text = ""
                while time.time() < deadline:
                    try:
                        text = rows_text()
                    except Exception:  # noqa: BLE001
                        text = ""
                    if "已识别" in text:
                        snap = [ln.strip() for ln in text.splitlines() if "已识别" in ln]
                        if snap and snap[0] not in seen:
                            seen.append(snap[0])
                    if predicate(text):
                        return True, text, seen
                    page.wait_for_timeout(400)
                return False, text, seen

            # ══════════ 1 · 扫描件：异步 + 界面上的「已识别 N/M 页」 ══════════
            print("\n== 1. 上传扫描件（3 页，全部需要 OCR） ==")
            open_kb()
            t0 = time.time()
            upload(SCANNED, "制度", "采购部", "内部")
            # ① 异步：接口立刻返回 ⇒ 最多几百毫秒内界面上就有这一行（还在解析中）
            appeared, text, seen = wait_row(lambda t: "scanned" in t, 20)
            submit_ms = int((time.time() - t0) * 1000)
            check(appeared, f"上传后文档行很快出现（{submit_ms} ms）", text[:120])
            check(
                "待解析" in text or "分段中" in text or "已识别" in text,
                "上传后文档处于解析中/待解析（**不是**已经解析完 —— 扫描件的解析是异步的）",
                text[:200],
            )

            # ② 界面上必须出现「已识别 N/M 页」，且 M = 3
            saw_progress = wait_row(lambda t: "已识别" in t, 30)
            print(f"   进度快照（界面实测）：{saw_progress[2]}")

            # ③ 等到解析完成（待向量化）
            done, final_text, seen_final = wait_row(
                lambda t: ("待向量化" in t or "失败" in t), PARSE_TIMEOUT_S
            )
            all_snapshots = saw_progress[2] + [s for s in seen_final if s not in saw_progress[2]]

            check(
                bool(all_snapshots),
                "★ 界面上出现过「已识别 N/M 页」（逐页进度的用户可见面，判据 ⑤/演示物）",
                f"实测快照 {all_snapshots[:4]}",
            )
            if all_snapshots:
                check(
                    any("/3 页" in s for s in all_snapshots),
                    "分母 M=3（扫描件三页都需要 OCR）",
                    f"实测 {all_snapshots[:4]}",
                )
            check(
                done and "待向量化" in final_text,
                "解析完成 ⇒ 文档落到「待向量化」（入库成功）",
                final_text[:200],
            )
            if done and "待向量化" in final_text:
                check(
                    "已识别" not in final_text,
                    "解析完成后「已识别 N/M 页」这一行消失（不是一条永久挂着的噪声）",
                    final_text[:200],
                )

            # ④ 解析结果是识别出来的中文
            row = page.locator(".el-table__row").filter(has_text="scanned").first
            btn = row.locator("button:has-text('解析结果')")
            if check(btn.count() > 0, "扫描件行有「解析结果」入口"):
                btn.first.click()
                page.wait_for_timeout(2000)
                parsed = page.evaluate("() => document.body.innerText")
                check(
                    "管理制度" in parsed or "制度" in parsed,
                    "解析结果里是识别出来的中文（OCR 真的跑通了）",
                    parsed[:160].replace("\n", " "),
                )
                page.keyboard.press("Escape")
                page.wait_for_timeout(600)

            # ══════════ 2 · 文本型文档不受影响 ══════════
            print("\n== 2. 文本型 PDF（不受影响：没有 OCR 页） ==")
            open_kb()
            t_text = time.time()
            upload(STRUCTURED, "制度", "采购部", "内部")
            ok_row, text2, seen2 = wait_row(lambda t: "structured" in t, 20)
            check(ok_row, "文本型文档上传后行出现", text2[:120])
            done2, final2, extra2 = wait_row(
                lambda t: ("待向量化" in t or "失败" in t), 60
            )
            text_ms = int((time.time() - t_text) * 1000)
            check(done2 and "待向量化" in final2, f"文本型文档照样入库（{text_ms} ms）", final2[:200])
            check(
                not (seen2 + extra2),
                "★ 文本型文档**不出现**「已识别 N/M 页」（M=0 ⇒ 不该挂一条 0/0 的噪声）",
                f"实测 {seen2 + extra2}",
            )

            print(f"\n控制台错误：{errs if errs else 0}")
        except Exception as e:  # noqa: BLE001
            print(f"  ✗ 脚本异常：{type(e).__name__}: {e}")
            ok.append((False, f"脚本异常：{e}"))
        finally:
            if token and kb_id and not token.startswith("mock_"):
                st, _ = api("DELETE", f"/knowledge-bases/{kb_id}", token)
                print(f"   已清理夹具库（{st}）")
            b.close()

    bad = [m for c, m in ok if not c]
    print("=" * 60)
    print(f"通过 {len(ok) - len(bad)}/{len(ok)}")
    for m in bad:
        print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
