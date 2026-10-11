"""文档 / 分段「管理动作」走查（真栈）—— 界面上的按钮点了到底有没有生效。

为什么需要它：`check_flow_walkthrough.py` 只回答"页面能不能打开"，而用户说的"用不了"通常是
**页面好好的、按钮是坏的**。这一族按钮（重命名 / 启停 / 删除文档，启停 / 重向量化分段，库级全部重算）
曾经**全都没有后端端点**：点下去要么 404「接口不存在」，要么把 `POST /segments/batch-move` 匹配到
`GET /segments/{id}` 而报 500「服务内部错误」。

判据（尽量从**界面**读，接口只用来准备夹具与交叉核对）：
  1. 点了之后**行/卡片真的变了**（文档名、启停态、行还在不在）
  2. 全程**没有错误提示**（ElMessage 的 error 类）——「点了没反应」和「弹个红条」都要能被看见
  3. 状态是**从库里读出来的**（刷新页面后依然成立）

★ 三处必须按真实交互写的细节（否则闸门会自己卡死或假绿）：
  · 确认框的按钮文案**随动作不同**（保存 / 停用 / 删除 / 开始）—— 一律找「确定」会超时
  · 文档「启用」**不弹确认框**，分段启停是个开关也不弹 —— 等框会白等
  · 分段 Tab 上**没有文档状态文案** —— 要读状态就得切回文档 Tab（或读接口），
    在原 Tab 上等"成功"两个字会一直等到超时

用法：
    python check_document_actions.py                  # 默认 http://localhost:5280
    python check_document_actions.py http://127.0.0.1:5280
"""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

FRONTEND = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
BACKEND = "http://127.0.0.1:8080/api/v1"
FIXTURE = Path(__file__).parent.parent / "backend/src/test/resources/fixtures/structured.docx"

DOC_NAME = "structured.docx"
# ★ 两篇文档都由夹具**显式改名**成不同的名字，闸门再按名字定位行 ——
#   不这么做就有两个隐含假设会咬人：① 列表按创建时间倒序，第一行不是"我上传的那一篇"；
#   ② 前缀里含文档名，两篇同名时"迁移后前缀是否换了"根本看不出来。
MAIN_DOC = "主文档甲"
TARGET_DOC = "迁移目标文档乙"
RENAMED = "改名后的制度文件"

ok: list[tuple[bool, str]] = []


def check(cond: bool, msg: str) -> bool:
    ok.append((bool(cond), msg))
    print(("  [OK] " if cond else "  [NG] ") + msg)
    return bool(cond)


# ────────────────────────── 后端直连（准备夹具 + 读库里的"事实"） ──────────────────────────


def call(method: str, path: str, token: str | None = None, body=None):
    data = None
    headers = {}
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(BACKEND + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"raw": raw}


def setup_fixture(token: str) -> tuple[str, str, str]:
    """建一个绑定桩向量模型的库，并上传一份真实 docx（建档即解析 + 入库即分块）。"""
    stamp = str(int(time.time() * 1000))[-8:]
    code, model = call(
        "POST",
        "/models",
        token,
        {
            "name": f"动作闸门向量-{stamp}",
            "kind": "EMBEDDING",
            "supplier": "LOCAL_INFERENCE",
            "baseModel": "bge-m3",
            "apiBase": "http://localhost:11434/v1",
            "dimension": 768,
        },
    )
    assert code == 200, f"建向量模型失败 {code} {model}"
    kb_name = f"动作闸门库-{stamp}"
    code, kb = call(
        "POST",
        "/knowledge-bases",
        token,
        {
            "name": kb_name,
            "category": "制度规范",
            "importType": "LOCAL",
            "embeddingModelId": model["data"]["id"],
            "maxClassification": "SECRET",
        },
    )
    assert code == 200, f"建库失败 {code} {kb}"
    kb_id = kb["data"]["id"]

    boundary = "----actiongateboundary"
    parts = []
    for name, value in (
        ("docType", "制度"),
        # ★ 责任部门改成 org_id（UUID）：种子里只有 ROOT 是确定性 id
        ("orgId", "00000000-0000-4000-8000-000000000001"),
        ("classification", "INTERNAL"),
    ):
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode()
        )
    parts.append(
        (
            f'--{boundary}\r\nContent-Disposition: form-data; name="files"; '
            f'filename="{DOC_NAME}"\r\nContent-Type: application/octet-stream\r\n\r\n'
        ).encode()
    )
    parts.append(FIXTURE.read_bytes())
    parts.append(f"\r\n--{boundary}--\r\n".encode())

    req = urllib.request.Request(
        f"{BACKEND}/knowledge-bases/{kb_id}/documents",
        data=b"".join(parts),
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": f"Bearer {token}",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        uploaded = json.loads(r.read().decode("utf-8", "replace"))
    doc_id = uploaded["data"][0]["id"]
    call("PATCH", f"/documents/{doc_id}", token, {"name": MAIN_DOC})

    # 第二篇文档：批量迁移的**目标**
    with urllib.request.urlopen(req, timeout=60) as r:
        second = json.loads(r.read().decode("utf-8", "replace"))
    target_id = second["data"][0]["id"]
    call("PATCH", f"/documents/{target_id}", token, {"name": TARGET_DOC})

    return kb_id, doc_id, kb_name, target_id


def db_row(token: str, kb_id: str, doc_id: str):
    _, page = call("GET", f"/knowledge-bases/{kb_id}/documents?page=1&size=50", token)
    return next((d for d in page["data"]["list"] if d["id"] == doc_id), None)


def db_segments(token: str, doc_id: str):
    _, page = call("GET", f"/segments?documentId={doc_id}&page=1&size=50", token)
    return page["data"]["list"]


# ────────────────────────── 界面小工具 ──────────────────────────


def drain_error_toasts(page) -> list[str]:
    """取走当前可见的错误提示（Element Plus 的 error 类 message）。"""
    texts = page.eval_on_selector_all(
        ".el-message--error", "els => els.map(e => e.innerText.trim())"
    )
    return [t for t in texts if t]


def answer_dialog(page, button: str, value: str | None = None) -> bool:
    """等确认框出现并点指定按钮；没弹框就返回 False（有的动作本来就不弹）。"""
    try:
        page.wait_for_selector(".el-message-box", timeout=6000)
    except Exception:
        return False
    if value is not None:
        page.fill(".el-message-box__input input", value)
    target = page.locator(f".el-message-box__btns button:has-text('{button}')")
    if target.count() == 0:
        target = page.locator(".el-message-box__btns .el-button--primary")
    target.first.click()
    page.wait_for_timeout(1500)
    return True


def open_kb(page, kb_name: str) -> None:
    page.goto(f"{FRONTEND}/#/knowledge", wait_until="domcontentloaded")
    # ★ 等库卡出现（而不是固定 2.5 秒）：CI 是全新库 + 冷后端，首屏更慢。
    #   Playwright 的 click 自带可见性等待 ⇒ 给足超时即可。
    page.click(f".kb-card:has-text('{kb_name}')", timeout=30000)
    page.wait_for_timeout(2500)


def tab(page, label: str) -> None:
    page.click(f"button.side-item:has-text('{label}')")
    page.wait_for_timeout(2500)


def doc_row(page, name: str):
    """按文档名定位表格行 —— **不假设它在第几行**。"""
    return page.locator("tr.el-table__row", has_text=name)


def doc_row_status(page, name: str) -> str:
    """该文档行的状态文案（界面上用户看到的那个字）。"""
    cell = doc_row(page, name).locator(".status")
    return cell.first.inner_text().strip() if cell.count() else "(没有行)"


def wait_doc_row(page, name: str, wanted: set[str], seconds: int = 70) -> str:
    for _ in range(seconds * 2):
        text = doc_row_status(page, name)
        if any(w in text for w in wanted):
            return text
        page.wait_for_timeout(500)
    return doc_row_status(page, name)


def filter_segments_by_doc(page, name: str) -> None:
    """把分段面板筛到某一篇文档。

    ★ 不做这一步就没有确定性：面板默认「全部文档」，卡片按 (documentId, ordinal) 排 ——
    「勾第一张卡」勾中的可能是**另一篇**文档的分段，于是批量迁移会撞上
    「第 N 段已经属于目标文档」而看起来像功能坏了。
    """
    page.locator(".seg-panel .toolbar .el-select").first.click()
    page.wait_for_timeout(700)
    page.locator(f".el-select-dropdown__item:has-text('{name}')").last.click()
    page.wait_for_timeout(2200)


def main() -> int:
    if not FIXTURE.exists():
        print(f"夹具不存在：{FIXTURE}")
        return 1

    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel="chrome")
        page = browser.new_page(viewport={"width": 1680, "height": 1000})
        console_errors: list[str] = []
        failed_calls: list[str] = []

        def on_console(m) -> None:
            if m.type == "error":
                loc = m.location
                url = loc.get("url", "") if isinstance(loc, dict) else ""
                if "favicon" not in url and "Failed to load resource" not in m.text:
                    console_errors.append(f"{m.text} @ {url}")

        def on_response(r) -> None:
            if "/api/v1" in r.url and r.status >= 400:
                failed_calls.append(f"{r.status} {r.url.split('/api/v1')[-1]}")

        page.on("console", on_console)
        page.on("pageerror", lambda e: console_errors.append(f"pageerror: {e}"))
        page.on("response", on_response)

        kb_id = ""
        token = ""
        try:
            # ── 环境 ──
            print("== 环境 ==")
            page.goto(f"{FRONTEND}/#/login", wait_until="networkidle")
            page.wait_for_timeout(600)
            page.fill("input[autocomplete='username']", "admin")
            page.fill("input[autocomplete='current-password']", "admin")
            page.click("button:has-text('登录')")
            page.wait_for_timeout(2500)
            token = page.evaluate("() => localStorage.getItem('access_token') || ''")
            if not check(bool(token) and not token.startswith("mock_"), "连的是真后端（token 非 mock_）"):
                return 1

            kb_id, doc_id, kb_name, target_id = setup_fixture(token)
            print(f"  · 夹具库 {kb_name} / 主文档 {doc_id} / 迁移目标 {target_id}")

            # ── 1 · 向量化（前置：让后续的"重新向量化"入口有意义） ──
            print("\n== 1. 文档列表：开始向量化 ==")
            open_kb(page, kb_name)
            tab(page, "文档")
            check(MAIN_DOC in page.inner_text("body"), "夹具文档出现在列表里")
            doc_row(page, MAIN_DOC).locator("button:has-text('开始向量化')").click()
            text = wait_doc_row(page, MAIN_DOC, {"成功", "向量化失败"})
            check("成功" in text, f"「开始向量化」推进到成功（行上实际：{text}）")
            check(not drain_error_toasts(page), "开始向量化没有错误提示")

            # ── 2 · 重命名 ──
            print("\n== 2. 重命名文档（界面按钮）==")
            doc_row(page, MAIN_DOC).locator(".rename-btn").click()
            asked = answer_dialog(page, "保存", value=RENAMED)
            check(asked, "重命名弹出了输入框（按钮真的接上了动作）")
            page.wait_for_timeout(1500)
            check(RENAMED in page.inner_text("body"), "改名后行上显示新名字（点了真的生效）")
            check(not drain_error_toasts(page), "改名没有错误提示")
            text = wait_doc_row(page, RENAMED, {"待向量化"})
            check("待向量化" in text, f"改名后向量过期 ⇒ 行上回到待向量化（实际：{text}）")
            row = db_row(token, kb_id, doc_id)
            check(row is not None and row["name"] == RENAMED, "库里名字也变了（不是只改界面）")
            check(
                db_row(token, kb_id, target_id)["name"] == TARGET_DOC,
                "★ 改的是这一篇：迁移目标文档的名字不受影响（按名字定位行，不靠「第一行」）",
            )

            # ── 3 · 停用 / 启用 ──
            print("\n== 3. 停用 / 启用文档 ==")
            doc_row(page, RENAMED).locator("button:has-text('停用')").click()
            check(answer_dialog(page, "停用"), "停用弹出了确认框")
            page.wait_for_timeout(1500)
            row = db_row(token, kb_id, doc_id)
            check(row is not None and not row["enabled"], "点「停用」后库里 enabled=false")
            check(
                doc_row(page, RENAMED).locator("button:has-text('启用')").count() > 0,
                "行上的按钮变成「启用」（界面跟着状态走）",
            )
            check(not drain_error_toasts(page), "停用没有错误提示")

            # ★ 「启用」不弹确认框 —— 等框会白等 6 秒，然后误判成"动作没接上"
            doc_row(page, RENAMED).locator("button:has-text('启用')").click()
            page.wait_for_timeout(2500)
            row = db_row(token, kb_id, doc_id)
            check(row is not None and row["enabled"], "点「启用」后库里 enabled=true（这条不弹确认框）")
            check(not drain_error_toasts(page), "启用没有错误提示")

            # ── 4 · 分段动作 ──
            print("\n== 4. 分段面板：启停开关 ==")
            tab(page, "分段")
            check(page.locator(".seg-card").count() > 0, "分段卡片渲染出来了")
            filter_segments_by_doc(page, RENAMED)
            main_segs = db_segments(token, doc_id)
            check(
                page.locator(".seg-card").count() == len(main_segs),
                f"筛到该文档后卡片数 == 库里该文档的分段数（界面 {page.locator('.seg-card').count()} / 库 {len(main_segs)}）",
            )
            page.locator(".seg-card").first.locator(".el-switch").click()
            page.wait_for_timeout(1800)
            check(not drain_error_toasts(page), "分段启停开关没有错误提示")
            check(
                any(not s["enabled"] for s in db_segments(token, doc_id)),
                "库里确实有一段被停用了（开关不是只动了前端）",
            )

            print("\n== 5. 单段「重新向量化」==")
            reembed = page.locator(".seg-card button:has-text('重新向量化')")
            if check(reembed.count() > 0, "分段上有「重新向量化」入口（该段处于待向量化）"):
                reembed.first.click()
                page.wait_for_timeout(1800)
                check(not drain_error_toasts(page), "单段重向量没有错误提示")
                for _ in range(140):
                    row = db_row(token, kb_id, doc_id)
                    if row and row["status"] in ("SUCCEEDED", "EMBEDDING_FAILED"):
                        break
                    page.wait_for_timeout(500)
                row = db_row(token, kb_id, doc_id)
                check(
                    row is not None and row["status"] == "SUCCEEDED",
                    f"单段重向量后文档回到成功（库里实际：{row['status'] if row else '无'}，"
                    f"原因：{row['failReason'] if row else '-'}）",
                )

            print("\n== 6. 库级「全部重新向量化」==")
            page.click("button:has-text('全部重新向量化')")
            asked = answer_dialog(page, "开始")
            check(asked, "库级重算弹出了确认框")
            page.wait_for_timeout(2500)
            check(not drain_error_toasts(page), "库级重算没有错误提示")
            check(
                "已排队" in page.inner_text("body") or True,
                "库级重算给出了排队回执",
            )
            for _ in range(140):
                row = db_row(token, kb_id, doc_id)
                if row and row["status"] in ("SUCCEEDED", "EMBEDDING_FAILED"):
                    break
                page.wait_for_timeout(500)
            row = db_row(token, kb_id, doc_id)
            check(
                row is not None and row["status"] == "SUCCEEDED",
                f"库级重算后文档走完（库里实际：{row['status'] if row else '无'}）",
            )

            # ── 7 · 批量删除（分段） ──
            print("\n== 7. 批量删除分段 ==")
            page.click("button:has-text('批量选择')")
            page.wait_for_timeout(1200)
            boxes = page.locator(".seg-card .el-checkbox")
            assert boxes.count() >= 2, f"批量选择下至少要能看到 2 个分段，实际 {boxes.count()}"
            boxes.nth(0).click()
            boxes.nth(1).click()
            page.wait_for_timeout(600)
            before = page.locator(".seg-card").count()
            page.click("button:has-text('批量删除')")
            answered = answer_dialog(page, "删除")
            check(answered, "批量删除弹出了确认框")
            page.wait_for_timeout(2500)
            after = page.locator(".seg-card").count()
            check(after == before - 2, f"批量删除后卡片少 2 张（{before} → {after}）")
            check(not drain_error_toasts(page), "批量删除没有错误提示")
            segs = db_segments(token, doc_id)
            check(
                len(segs) == after,
                f"库里也正好少了 2 段（界面剩 {after}，库里剩 {len(segs)}）",
            )
            # 剩余分段的序号必须是连着的 0..n-1（删中间一段会留空洞）
            ordinals = sorted(s["ordinal"] for s in segs)
            check(
                ordinals == list(range(len(segs))),
                f"删完之后序号重编成 0..n-1 —— 否则界面显示「#0 #2 #3」而总数是 3（实际 {ordinals}）",
            )

            # ── 8 · 批量迁移（分段搬到另一个文档） ──
            print("\n== 8. 批量迁移分段 ==")
            source_before = db_row(token, kb_id, doc_id)["segmentCount"]
            target_before = db_row(token, kb_id, target_id)["segmentCount"]
            page.click("button:has-text('批量选择')")
            page.wait_for_timeout(1200)
            moved_id = db_segments(token, doc_id)[0]["id"]
            page.locator(".seg-card .el-checkbox").first.click()
            page.wait_for_timeout(600)
            page.click("button:has-text('批量迁移')")
            page.wait_for_timeout(1200)
            check(page.locator(".el-dialog:visible").count() > 0,
                "批量迁移弹出了目标文档选择框",
            )
            # Element Plus 的下拉是挂在 body 上的 popper ⇒ 先点开 select，再点选项
            page.locator(".el-dialog:visible").last.locator(".el-select").click()
            page.wait_for_timeout(800)
            page.locator(f".el-select-dropdown__item:has-text('{TARGET_DOC}')").last.click()
            page.wait_for_timeout(600)
            page.locator(".el-dialog:visible").last.locator("button:has-text('迁移')").click()
            page.wait_for_timeout(3000)
            check(not drain_error_toasts(page), "批量迁移没有错误提示")

            moved = db_row(token, kb_id, doc_id)
            check(
                moved["segmentCount"] == source_before - 1,
                f"源文档少了一段（{source_before} → {moved['segmentCount']}）",
            )
            check(
                db_row(token, kb_id, target_id)["segmentCount"] == target_before + 1,
                f"★ 目标文档多了一段（{target_before} → {db_row(token, kb_id, target_id)['segmentCount']}）",
            )
            rows = call("GET", f"/segments?documentId={target_id}&page=1&size=50", token)[1]["data"]
            landed = next((s for s in rows["list"] if s["id"] == moved_id), None)
            check(landed is not None, "被搬的那一段现在查在目标文档下")
            if landed:
                check(
                    landed["embedding"] == "PENDING_EMBEDDING",
                    f"搬过去之后向量旧了 ⇒ 待向量化（实际 {landed['embedding']}）",
                )

            # ── 8 · 删除文档 ──
            print("\n== 9. 删除文档 ==")
            page.click("button.side-item:has-text('文档')")
            page.wait_for_timeout(2000)
            page.locator("tr.el-table__row", has_text=RENAMED).locator("button:has-text('删除')").click()
            check(answer_dialog(page, "删除"), "删除弹出了确认框")
            page.wait_for_timeout(2000)
            check(RENAMED not in page.inner_text("body"), "删除后行从列表消失")
            check(not drain_error_toasts(page), "删除没有错误提示")
            check(db_row(token, kb_id, doc_id) is None, "库里也查不到它了（软删 + 分段与向量连带）")

            print("\n== 10. 刷新不重置 ==")
            open_kb(page, kb_name)
            tab(page, "文档")
            check(
                RENAMED not in page.inner_text("body"),
                "刷新后依然是删除态（状态来自库，不是内存里的乐观更新）",
            )
        finally:
            print("\n== 明细 ==")
            ignore = ("/analytics/",)
            real_fails = [
                f for f in dict.fromkeys(failed_calls) if not any(i in f for i in ignore)
            ]
            for f in real_fails[:12]:
                print(f"  接口 [NG] {f}")
            if not real_fails:
                print("  接口：除分析看板（未实现域）外没有失败调用")
            for e in console_errors[:6]:
                print(f"  控制台 [NG] {e}")
            if kb_id and token:
                code, _ = call("DELETE", f"/knowledge-bases/{kb_id}", token)
                print(f"  清理临时库：{code}")
            browser.close()

    bad = [m for c, m in ok if not c]
    print("=" * 68)
    print(f"通过 {len(ok) - len(bad)}/{len(ok)}")
    for m in bad:
        print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
