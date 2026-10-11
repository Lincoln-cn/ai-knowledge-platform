"""分段入库链路真栈闸门（0.2.2 批 3）。

覆盖 `ROADMAP` 第 7 周的三条判据 + 演示物，一条链跑到底：
  ① 上传真实 DOCX（multipart）⇒ **建档即解析 + 入库即分块**
  ② 库里 `search_text` = 前缀 + 正文（判据 ③）—— 看**数据库的列**，不看界面
  ③ ★ 预览与入库用同一个分块器（判据 ④）：同文档同参数 ⇒ 逐项相同
  ④ 分段管理页能看到分段与字符数（演示物）；编辑分段重跑 SEG-I1 并重算 hash
  ⑤ 口径拒绝：strategy=CUSTOM / uploadIds / segmentLength > 上限 都必须**明确 400**

★ 为什么必须跑真栈（不是 mock）：mock 是浏览器内拦截，它的绿不能证明后端接上了。
  第一条断言就是「token 不是 mock_ 前缀」—— 连错环境要立刻失败，而不是静默跑出一片绿。
★ 为什么还要直接查库：界面显示的是视图，**库里的列**才是入库链路的真相
  （search_text 有没有前缀、content 有没有被前缀污染、进度列有没有回填）。
★ 上传走真实 multipart 端点（与「上传文档」弹窗同一个接口）：
  把确定性放在链路上，而不是放在元素选择器上；界面部分只覆盖「分段管理页」这一段。

前置：真栈起好（后端 8080 + 前端 5280 指向它）、Docker 里 `aip-postgres` 在跑。
用法：python check_segmentation_e2e.py [base]
"""
from __future__ import annotations

import json
import os
import re
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"
DOCX = Path(__file__).resolve().parent.parent / "backend/src/test/resources/fixtures/structured.docx"

# ────────────────────────── 直连库的连接 ──────────────────────────
# ★ 为什么必须可配置：CI 里的 PG 是 job 的 **service 容器**，本机没有叫 aip-postgres 的容器可 exec。
#
# ★ 而写死连接还有一个更阴的后果：它可能连到**另一个库**上去 —— 闸门照样跑、照样报数，
#   只是那些数字与"后端写的那个库"无关。实测踩过：本机同时开着 aip-postgres（15432，存量数据）
#   与演练用的 aip-ci-pg（15433），而 app 连的是后者 ⇒ 闸门读前者，报 0 段、然后崩在一个空 seg_id 上。
#   ⇒ 所以下面既支持三种连接方式，也把"读的是哪个库"显式打出来（红的时候要能一眼看出来）。
CONTAINER = os.environ.get("AIP_PG_CONTAINER", "aip-postgres")
DB = os.environ.get("PGDATABASE", "aiplatform")
USER = os.environ.get("PGUSER", "aiplatform")

# 与后端 app.segmentation.* 默认值一致（入库即分块用的就是这两个数）
SEG_LEN, OVERLAP = 500, 60
MODEL_LIMIT = 1200


def db_how() -> str:
    """这条闸门读的是哪个库的可读描述（进日志，也进失败信息）。"""
    override = os.environ.get("AIP_PSQL_CMD", "").strip()
    if override:
        return f"AIP_PSQL_CMD={override}"
    if os.environ.get("PGHOST"):
        return "psql 直连 {}:{}/{}（user={}）".format(
            os.environ["PGHOST"], os.environ.get("PGPORT", "5432"), DB, USER
        )
    return f"docker exec {CONTAINER} psql -U {USER} -d {DB}"


def psql(sql: str) -> str:
    """读库取真相（界面显示的是视图，库里的列才是入库链路的真相）。

    连接优先：`AIP_PSQL_CMD` 全量覆盖 → `PGHOST` 直连 → `docker exec`（本机默认）。
    """
    override = os.environ.get("AIP_PSQL_CMD", "").strip()
    if override:
        cmd = shlex.split(override) + ["-tAc", sql]
    elif os.environ.get("PGHOST"):
        cmd = ["psql", "-tAc", sql]
    else:
        cmd = ["docker", "exec", CONTAINER, "psql", "-U", USER, "-d", DB, "-tAc", sql]
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        raise RuntimeError(f"psql 失败（{db_how()}）：{out.stderr.strip()}\nSQL: {sql}")
    return out.stdout.strip()


def psql_int(sql: str) -> int:
    value = psql(sql)
    return int(value) if value.strip() else 0


def wait_parsed(doc_id: str, seconds: int) -> str:
    """等**解析落地**：PENDING_EMBEDDING（成功）或 SEGMENT_FAILED（失败）。

    ★ 第 9 周起解析是异步的（T249.3）：上传接口立刻返回、文档落在「分段中」，
    OCR/分块在后台跑。闸门要与界面一样**等**，否则读到的是在途态
    （CI 上就是这么红的：`状态=待向量化（实际 SEGMENTING）`、`segment_count=0`）。
    """
    deadline = time.time() + seconds
    while time.time() < deadline:
        current = psql(f"select status from kb_document where id='{doc_id}'")
        if current in ("PENDING_EMBEDDING", "SEGMENT_FAILED"):
            return current
        time.sleep(0.5)
    return psql(f"select status from kb_document where id='{doc_id}'")


def wait_status(doc_id: str, expected: str, seconds: int) -> str:
    """轮询**库里**的文档状态直到 expected（或超时返回当前值）。

    ★ 前端也是这样轮询的 —— 进度与状态的事实源是库，不是内存。

    ★★ 但**行不存在**要立刻失败，不能等到超时：那是「读的库不是后端写的那个」的唯一可靠信号，
    而它的表象只是"状态一直是空" —— 超时之后报出来的会是「入库没生效」，指错方向。
    """
    status = ""
    for _ in range(seconds * 2):
        status = psql(f"select status from kb_document where id='{doc_id}'")
        if status == expected or status.endswith("FAILED"):
            return status
        if status == "" and psql_int(f"select count(*) from kb_document where id='{doc_id}'") == 0:
            raise RuntimeError(
                f"库里查不到刚上传的文档 {doc_id} —— 这条闸门读的库不是后端在用的那个。\n"
                f"  当前连接：{db_how()}\n"
                f"  （真栈必须让两者指向同一个库；CI 用 PG* 环境变量，本机可用 AIP_PG_CONTAINER 指定容器）"
            )
        time.sleep(0.5)
    return status


def api(method: str, path: str, token: str, body: dict | None = None) -> tuple[int, dict]:
    req = urllib.request.Request(
        API + path,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        method=method,
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")
        except Exception:  # noqa: BLE001
            return e.code, {}
    except Exception as e:  # noqa: BLE001
        return -1, {"message": str(e)}


def api_multipart(path: str, token: str, fields: dict[str, str], file: Path) -> tuple[int, dict]:
    """真实 multipart 上传（与上传弹窗调的同一个端点：POST /knowledge-bases/{id}/documents）。"""
    boundary = "----e2e0boundary7f3a"
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
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")
        except Exception:  # noqa: BLE001
            return e.code, {}
    except Exception as e:  # noqa: BLE001
        return -1, {"message": str(e)}


def main() -> int:  # noqa: C901
    ok: list[tuple[bool, str]] = []

    def check(cond: bool, msg: str, detail: str = "") -> bool:
        ok.append((bool(cond), msg))
        print(("  ✓ " if cond else "  ✗ ") + msg + (f"\n      {detail}" if detail and not cond else ""))
        return bool(cond)

    ts = int(time.time())
    kb_id = doc_id = None
    token = ""
    db_rows: list[tuple] = []
    with sync_playwright() as pw:
        b = pw.chromium.launch(channel="chrome")
        page = b.new_page(viewport={"width": 1600, "height": 950})
        errs: list[str] = []
        page.on("pageerror", lambda e: errs.append(str(e)))

        def on_console(m) -> None:
            if m.type == "error":
                url = (m.location or {}).get("url", "") if isinstance(m.location, dict) else ""
                if "favicon" not in url:
                    errs.append(f"{m.text} @ {url}")

        page.on("console", on_console)

        try:
            # ────────────── 0 · 登录（必须连真后端） ──────────────
            page.goto(f"{BASE}/#/login", wait_until="networkidle")
            page.wait_for_timeout(800)
            page.fill("input[autocomplete='username']", "admin")
            page.fill("input[autocomplete='current-password']", "admin")
            page.click("button:has-text('登录')")
            page.wait_for_timeout(2500)
            token = page.evaluate("() => localStorage.getItem('access_token') || ''")
            print("== 0. 环境 ==")
            # ★ 读库那一半连的是哪个库要显式打出来：查错库的表象是"入库没生效"，指错方向
            print(f"   读库连接：{db_how()}")
            if not check(
                bool(token) and not token.startswith("mock_"),
                f"连的是真后端（token 非 mock_）：{token[:18]}…",
            ):
                print("   ⇒ 这不是真栈，后面的断言没有意义，直接结束")
                b.close()
                return 1

            # ────────────── 1 · 夹具：向量模型 + 库（走接口） ──────────────
            print("== 1. 夹具 ==")
            st, mlist = api("GET", "/models?kind=EMBEDDING&size=50", token)
            rows = ((mlist.get("data") or {}).get("list")) or []
            model_id = rows[0].get("id") if rows else None
            if not model_id:
                st, created = api(
                    "POST",
                    "/models",
                    token,
                    {
                        "name": f"E2E向量-{ts}",
                        "kind": "EMBEDDING",
                        "supplier": "LOCAL_INFERENCE",
                        "baseModel": "bge-m3",
                        "dimension": 1024,
                    },
                )
                model_id = (created.get("data") or {}).get("id")
            st, kb = api(
                "POST",
                "/knowledge-bases",
                token,
                {
                    "name": f"E2E分段库-{ts}",
                    "category": "制度规范",
                    "description": "分段链路真栈闸门夹具",
                    "importType": "LOCAL",
                    "embeddingModelId": model_id,
                    "maxClassification": "SECRET",
                },
            )
            kb_id = (kb.get("data") or {}).get("id")
            check(st == 200 and bool(kb_id), f"建库成功（={kb_id}）", f"status={st} body={kb}")

            # ────────────── 2 · 上传真实 DOCX（建档即解析 + 入库即分块） ──────────────
            print("== 2. 上传真实 DOCX（multipart） ==")
            check(DOCX.exists(), f"夹具文件存在：{DOCX.name} ({DOCX.stat().st_size if DOCX.exists() else 0} 字节)")
            st_up, up = api_multipart(
                f"/knowledge-bases/{kb_id}/documents",
                token,
                {
                    "docType": "制度",
                    # ★ 责任部门改成 org_id（UUID）：种子里只有 ROOT 是确定性 id
                    "orgId": "00000000-0000-4000-8000-000000000001",
                    "classification": "INTERNAL",
                },
                DOCX,
            )
            docs = up.get("data") or []
            check(
                st_up == 200 and bool(docs),
                f"上传建档成功（status={st_up}，返回 {len(docs)} 篇）",
                str(up)[:220],
            )
            doc_id = docs[0].get("id") if docs else None

            # ★ 第 9 周起解析是**异步**的（扫描件的 OCR 实测每页约 3.6 秒 —— 判据 ⑤ 要求不阻塞其它文档）
            #   ⇒ "上传完就能读分段/状态"不再成立。闸门要和界面一样**等**解析落地。
            #   （CI 抓到的就是这个：不等就会读到 SEGMENTING / segment_count=0。）
            wait_parsed(doc_id, 60)
            print(f"   解析落地：{wait_status(doc_id, 'PENDING_EMBEDDING', 1)}")

            row = psql(
                f"select id, status, segment_count, char_count from kb_document where id='{doc_id}'"
            ).split("|")
            if len(row) == 4:
                status, seg_cnt, char_cnt = row[1], int(row[2]), int(row[3])
            else:
                status, seg_cnt, char_cnt = "?", 0, 0
            check(
                status == "PENDING_EMBEDDING",
                f"状态=待向量化（实际 {status}）",
                "入库即分块成功后应是 PENDING_EMBEDDING（不是 SUCCEEDED —— 向量化还没做）",
            )
            check(seg_cnt > 0, f"分段数已回填（segment_count={seg_cnt}）")
            check(char_cnt > 0, f"字符数已回填（char_count={char_cnt}）")

            # ────────────── 3 · 库里的列：search_text 带前缀、content 不被污染 ──────────────
            print("== 3. 库里 search_text（判据 ③） ==")
            # ★ 用 json_agg 取整篇：content 里带换行，按行/按 | 切必然散架
            raw = psql(
                f"select json_agg(t) from (select ordinal, char_count, is_table, content_hash, "
                f"search_text, content from kb_segment where document_id='{doc_id}' order by ordinal) t"
            )
            for r in json.loads(raw) if raw else []:
                db_rows.append(
                    (
                        r["ordinal"],
                        r["char_count"],
                        r["is_table"],
                        r["content_hash"],
                        r["search_text"],
                        r["content"],
                    )
                )
            check(len(db_rows) == seg_cnt, f"库里分段行数与 segment_count 一致（{len(db_rows)} / {seg_cnt}）")
            check([r[0] for r in db_rows] == list(range(len(db_rows))), "ordinal 从 0 起且连续")

            prefix_head = "制度·采购部·"
            first = db_rows[0] if db_rows else ("", 0, False, "", "", "")
            check(
                first[4].startswith(prefix_head) and "｜" in first[4],
                "search_text 以「文种·部门·文档名｜」开头（判据 ③）",
                f"实际前 40 字：{first[4][:40]}",
            )
            check(
                prefix_head not in first[5],
                "content 里**不含**前缀（12 §4.2 纪律 1：引用展示要看到干净原文）",
            )
            check(all(re.fullmatch(r"[0-9a-f]{64}", r[3]) for r in db_rows), "content_hash 都是 64 位十六进制")
            check(any(r[2] for r in db_rows), "表格块被识别（is_table=true 至少一处）")

            # ────────────── 4 · 判据 ④：预览与入库同源 ──────────────
            print("== 4. 判据 ④ 预览与入库同源 ==")
            st, pv = api(
                "POST",
                "/segmentation/preview",
                token,
                {"documentId": doc_id, "segmentLength": SEG_LEN, "overlap": OVERLAP, "strategy": "AUTO"},
            )
            data = pv.get("data") or {}
            psegs = data.get("segments") or []
            check(st == 200, f"预览返回 200（实际 {st}）", str(pv)[:200])
            check(data.get("modelLimit") == MODEL_LIMIT, f"modelLimit={data.get('modelLimit')}（默认配置）")
            check(len(psegs) == len(db_rows), f"预览段数与库里一致（{len(psegs)} / {len(db_rows)}）")
            same = all(
                p["ordinal"] == r[0] and p["charCount"] == r[1] and p["content"] == r[5]
                for p, r in zip(psegs, db_rows)
            )
            check(same, "同一文档 + 同一参数 ⇒ 预览与入库逐项相同（ordinal/content/charCount）")

            # ────────────── 5 · 界面：分段管理页看得到分段 ──────────────
            print("== 5. 分段管理页（演示物） ==")
            page.goto(f"{BASE}/#/knowledge", wait_until="networkidle")
            # ★ 等库卡**出现**（不等固定 3 秒）：CI 是全新库 + 冷后端，首屏更慢
            card = page.locator(f".kb-card:has-text('E2E分段库-{ts}')").first
            try:
                card.wait_for(state="visible", timeout=30000)
            except Exception:  # noqa: BLE001
                pass
            page.wait_for_timeout(400)
            if card.count() == 0:
                card = page.locator(".kb-card").first
            card.click()
            page.wait_for_timeout(2500)
            tab = page.locator("button.side-item", has_text="分段")
            if tab.count() == 0:
                tabs = page.evaluate(
                    "() => [...document.querySelectorAll('button.side-item')].map(b => (b.innerText||'').trim())"
                )
                print(f"     侧栏页签：{tabs} / URL={page.url}")
            tab.first.click()
            page.wait_for_timeout(3500)
            body = page.inner_text("body")
            cards = page.locator(".seg-card").count()
            check(cards == len(db_rows), f"分段页卡数与库里一致（界面 {cards} / 库里 {len(db_rows)}）")
            # 界面里必须出现库里第一条分段的正文字样 —— 证明走的是真 GET /segments（不是空面板/占位）
            probe = db_rows[0][5].strip().splitlines()[-1][:12] if db_rows else ""
            check(bool(probe) and probe in body, f"分段正文出现在页面上（探测串 {probe!r}）")
            check("字符" in body or "字" in body, "页面上能看到字符数/字数（演示物）")

            # ────────────── 6 · 编辑分段：SEG-I1 拒绝超长 / 改内容重算 hash ──────────────
            print("== 6. 编辑分段（SEG-I1 + 重算） ==")
            seg_id = psql(f"select id from kb_segment where document_id='{doc_id}' order by ordinal limit 1")
            st_bad, bad = api("PUT", f"/segments/{seg_id}", token, {"content": "超" * 5000})
            check(st_bad == 400, f"粘进超长内容被拒（400，实际 {st_bad}）", str(bad)[:200])
            check("超出" in json.dumps(bad, ensure_ascii=False), "拒绝时给出可读原因")

            hash_before = psql(f"select content_hash from kb_segment where id='{seg_id}'")
            st_ok, upd = api("PUT", f"/segments/{seg_id}", token, {"content": "改写后的正文：仅此一句。"})
            hash_after = psql(f"select content_hash from kb_segment where id='{seg_id}'")
            emb = psql(f"select embedding_status from kb_segment where id='{seg_id}'")
            check(st_ok == 200, f"合法改写成功（200，实际 {st_ok}）", str(upd)[:200])
            check(hash_before != hash_after, "contentHash 随内容变了（SEG-I7：向量重算的依据）")
            check(emb == "PENDING_EMBEDDING", f"编辑后回到待向量化（实际 {emb}）")

            # ────────────── 7 · 其它口径：CUSTOM 拒 / uploadIds 拒 / 单条取数 ──────────────
            print("== 7. 口径拒绝（禁止静默忽略） ==")
            st_c, cust = api(
                "POST",
                "/segmentation/preview",
                token,
                {"documentId": doc_id, "segmentLength": SEG_LEN, "overlap": OVERLAP, "strategy": "CUSTOM"},
            )
            check(
                st_c == 400 and "CUSTOM" in json.dumps(cust, ensure_ascii=False),
                f"strategy=CUSTOM 明确拒绝（{st_c}）",
            )
            st_u, ups = api(
                "POST",
                "/segmentation/preview",
                token,
                {
                    "documentId": None,
                    "segmentLength": SEG_LEN,
                    "overlap": OVERLAP,
                    "strategy": "AUTO",
                    "uploadIds": ["up_x"],
                },
            )
            check(st_u == 400 and "暂存" in json.dumps(ups, ensure_ascii=False), f"uploadIds 明确拒绝（{st_u}）")
            st_o, over = api(
                "POST",
                "/segmentation/preview",
                token,
                {"documentId": doc_id, "segmentLength": 99999, "overlap": OVERLAP, "strategy": "AUTO"},
            )
            check(st_o == 400 and "上限" in json.dumps(over, ensure_ascii=False), f"分段长度超上限拒绝（{st_o}）")
            st_g, got = api("GET", f"/segments/{seg_id}", token)
            check(st_g == 200 and (got.get("data") or {}).get("id") == seg_id, f"单条取分段（{st_g}）")
            st_404, _ = api("GET", f"/segments/{'0' * 8}-0000-0000-0000-000000000000", token)
            check(st_404 == 404, f"不存在的分段 ⇒ 404（实际 {st_404}）")

            # ────────────── 8 · 真实进度与「刷新不重置」（第 8 周演示判据） ──────────────
            print("== 8. 开始向量化 → 进度 → ★ 刷新不重置 ==")
            page.goto(f"{BASE}/#/knowledge", wait_until="networkidle")
            # ★ 等库卡**出现**（不等固定 3 秒）：CI 是全新库 + 冷后端，首屏更慢
            card = page.locator(f".kb-card:has-text('E2E分段库-{ts}')").first
            try:
                card.wait_for(state="visible", timeout=30000)
            except Exception:  # noqa: BLE001
                pass
            page.wait_for_timeout(400)
            if card.count() == 0:
                card = page.locator(".kb-card").first
            card.click()
            page.wait_for_timeout(3000)  # 文档 Tab 默认激活

            # 点该文档行的「开始向量化」（用户显式动作，后端不自动推进）
            clicked = page.evaluate(
                """(docName) => {
                    const rows = [...document.querySelectorAll('.el-table__row, .doc-row')];
                    const row = rows.find(r => (r.innerText || '').includes(docName));
                    if (!row) return false;
                    const btn = [...row.querySelectorAll('button')]
                        .find(b => (b.innerText || '').includes('开始向量化'));
                    if (btn) { btn.click(); return true; }
                    return false;
                }""",
                "structured.docx",
            )
            check(clicked, f"文档行有「开始向量化」入口且能点（库里 {len(db_rows)} 段）")
            page.wait_for_timeout(1500)

            # 等**库里**跑完（服务端事实；这也顺带证明进度真的落库）
            status = wait_status(doc_id, "SUCCEEDED", 60)
            check(status == "SUCCEEDED", f"六态走到终点（实际 {status}）")
            db_processed = psql_int(f"select processed_segments from kb_document where id='{doc_id}'")
            db_segments = psql_int(f"select segment_count from kb_document where id='{doc_id}'")
            check(
                db_processed == db_segments and db_segments > 0,
                f"库里进度走满：{db_processed}/{db_segments}",
            )
            vectors = psql_int(
                f"select count(*) from vec_embedding_768 where metadata::jsonb ->> 'document_id' = '{doc_id}'"
            )
            check(vectors == db_segments, f"向量条数 == 分段数（{vectors} / {db_segments}）")

            # ★ 刷新页面：进度与状态必须**仍在**（读的是库里的数字，不是前端状态）
            page.reload(wait_until="networkidle")
            page.wait_for_timeout(3500)
            body = page.inner_text("body")
            check(
                f"{db_segments}/{db_segments}" in body or "成功" in body,
                "★ 刷新后进度/状态仍在（「刷新页面不重置」—— 读数来自库）",
            )
            check("向量化中" not in body, "刷新后不再是「向量化中」（没有停滞的假进度）")
        finally:
            # ────────────── 收尾：删掉夹具库（连带文档与分段） ──────────────
            try:
                if token and kb_id:
                    st, _ = api("DELETE", f"/knowledge-bases/{kb_id}", token)
                    print(f"\n清理：删除夹具库 {kb_id}（status={st}）")
            except Exception as e:  # noqa: BLE001
                print(f"\n清理失败（不影响结论）：{e}")
            if errs:
                print(f"\n控制台/页面错误 {len(errs)} 条（前 5 条）：")
                for e in errs[:5]:
                    print("   - " + e[:160])
            b.close()

    bad = [m for c, m in ok if not c]
    print("=" * 68)
    print(f"通过 {len(ok) - len(bad)}/{len(ok)}")
    for m in bad:
        print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
