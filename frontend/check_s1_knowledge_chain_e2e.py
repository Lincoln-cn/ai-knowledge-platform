"""S1「知识运营」整链真栈闸门（0.5.0 验收 · `docs/ROADMAP.md` §2.1 S1）。

判据原文（`docs/ROADMAP.md:41`）：

    建库 → 上传真实文档 → 切分 + 调参 → 向量化（真实进度）→ 命中测试 → 停用分段后不再命中

**为什么要有这一条**：`docs/验收台账.md` 里 S1 一直是 🟡 —— 零件（建库表单、切分预览、向量化、
命中测试页）各自都有闸门，但**没有一条链**把上一环的输出当下一环的输入。零件齐 ≠ 场景通：
`check_flow_walkthrough.py` 只证明"页能开"，`check_knowledge_parse.py` 只证明"能解析"，
`check_segmentation_e2e.py` 只证明"预览端点能调" —— 没有任何一条脚本证明"同一份文档从空库一路走到
命中测试，并且停用后就真的搜不到"。

**这条链的每一环都拿上一环的产物**（不是各自造夹具）：

1. 空库（`documentCount == 0`）→ 上传**真实字节**（运行时生成的 Markdown，约 50 段）；
2. 等解析落地（`PENDING_EMBEDDING`）—— 用轮询，不用固定 sleep（`#170`）；
3. **切分预览与入库同源**：`POST /segmentation/preview{documentId}` 的段数必须等于文档的
   `segmentCount`（`Segmenter#plan` 是预览与入库的唯一入口，`12 §3.5`）；
4. **调参真的改变计划**：`segmentLength 300` 的段数 > `800` 的段数，且每段 `charCount` ≤ 上限
   ——"收下参数但不用"是本项目反复记过的静默失败形态，所以这里断言的是**结果差**，不是"返回 200"；
5. **向量化进度是库里的真数字**：向桩注入 `delay_ms` 把三批（`EmbeddingRunner.BATCH_SIZE = 20`）
   拉开，然后轮询文档列表，**必须读到过一次 `0 < processedSegments < segmentCount` 的中间读数**。
   ★ 这一条能红：如果进度是"0 → N 一步跳"（界面上的进度条就成了假动作），中间读数根本不存在。
   ★ 跑完必须把 `delay_ms` 复位成 0 —— 同一 job 里后面的闸门共用这个桩。
6. 终态一致：`SUCCEEDED` ∧ `processedSegments == segmentCount == GET /segments 的总数`；
7. 命中测试（HYBRID）有命中，且命中的是**这份文档**、内容含我们埋进去的那句话；
8. **停用命中的分段后不再命中**，再启用后命中回来 —— 这是 S1 最后一步，也是一对照对：
   只断"停用后 0 条"的话，"检索整个坏了"也会让它变绿。

**这条闸门不能证明什么**（写下来防止被当成质量读数）：

- **不能证明"命中得好"**：`B1 命中率 ≥70%` 要 P1 语料与标注，这里只证明"同一份自己写的文档能被搜到"。
- **不能证明向量语义**：桩的 `deterministic_vector` 是 sha256 确定性向量，**语义无关**
  （`backend/tools/openai_stub.py:110`），所以 VECTOR 模式只记读数不断言，命中由全文路负责。
- **不能证明界面**：这是 HTTP 链。界面上的按钮与轮询由 `check_flow_walkthrough.py` 的
  `/knowledge` 与 `/knowledge/hittest` 两页覆盖，两边合起来才算"能点的路径"。

用法：

    python check_s1_knowledge_chain_e2e.py                    # 默认 http://localhost:5280
    python check_s1_knowledge_chain_e2e.py http://127.0.0.1:5280

    # 桩不在默认地址时（只能在 11434 上注入延迟，不影响断言，只是读不到中间进度）
    AIP_STUB_URL=http://127.0.0.1:11434 python check_s1_knowledge_chain_e2e.py

退出码：0 = 全部通过；1 = 有断言失败（逐条打印 `[NG]`）。
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"

#: 桩的根地址（注入延迟用）。CI 与本地启动器都是 11434。
STUB = os.environ.get("AIP_STUB_URL", "http://127.0.0.1:11434").rstrip("/")

#: 文档里埋的那句话 —— 命中测试问的就是它。★ 只出现一次（否则"停用分段"要多停几段才 0 条）。
NEEDLE = "偏远地区运维补贴标准为每人每天八十元"
QUESTION = "偏远地区运维补贴标准是多少"

#: 建库参数：分类用种子里存在的，密级上限盖住文档密级（INTERNAL ≤ SECRET）。
ORG_ROOT = "00000000-0000-4000-8000-000000000001"
DOC_TYPE = "制度"
DOC_CLASSIFICATION = "INTERNAL"

#: 桩延迟（毫秒）：三批 × 300ms ⇒ 约 0.9s 的窗口，够 0.1s 轮询读到两次中间读数。
STUB_DELAY_MS = 300


# ──────────────────────────────── HTTP ────────────────────────────────


def api(method: str, path: str, token: str, body: dict | None = None) -> tuple[int, dict]:
    """调后端 REST（走 dev server 的代理 ⇒ 与页面同一条路）。

    ★ 未登录时**不能**发空的 `Authorization` 头：那会让 Bearer 过滤器判认证失败，
      `/auth/login` 自己也拿回 401「未认证」—— 症状像"密码错了"。
    """
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        API + path,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        method=method,
        headers=headers,
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


def upload(token: str, kb_id: str, file_path: str, filename: str) -> tuple[int, dict]:
    """multipart 上传（手写 boundary：只依赖标准库，不为一个闸门引 requests）。"""
    boundary = "----s1chain0boundary"
    buf = b""
    for key, value in (
        ("docType", DOC_TYPE),
        ("orgId", ORG_ROOT),
        ("classification", DOC_CLASSIFICATION),
    ):
        buf += (
            f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'
        ).encode("utf-8")
    buf += (
        f'--{boundary}\r\nContent-Disposition: form-data; name="files"; '
        f'filename="{filename}"\r\nContent-Type: text/markdown\r\n\r\n'
    ).encode("utf-8")
    with open(file_path, "rb") as fh:
        buf += fh.read()
    buf += b"\r\n" + f"--{boundary}--\r\n".encode("utf-8")
    req = urllib.request.Request(
        API + f"/knowledge-bases/{kb_id}/documents",
        data=buf,
        method="POST",
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": f"Bearer {token}",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")
        except Exception:  # noqa: BLE001
            return e.code, {}
    except Exception as e:  # noqa: BLE001
        return -1, {"message": str(e)}


def stub_config(payload: dict) -> bool:
    """改桩的行为（延迟注入）。★ 桩不可达/不认这个 key ⇒ False，调用方必须如实记「没读到」。"""
    req = urllib.request.Request(
        STUB + "/__stub/config",
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return resp.status == 200
    except Exception:  # noqa: BLE001
        return False


# ──────────────────────────────── 夹具 ────────────────────────────────


def build_markdown(path: str, sections: int = 72) -> int:
    """生成一份**真实字节**的 Markdown：72 节 ⇒ 正文约 2.5 万字 ⇒ 约 50 个分段。

    ★ 为什么要这么长：`EmbeddingRunner.BATCH_SIZE = 20`，**只有 ≥3 批**（>40 段）才可能在
      0.9s 的延迟窗口里读到中间进度。段落刻意写成 ~400 字（< 默认 500 的装箱目标），
      这样埋进去的那句话不会跨段被切两半。
    """
    filler = (
        "本节说明值班交接与巡检记录的填写要求：交接班必须在系统内逐项勾选，"
        "巡检记录需附现场照片与处置结论，异常项要写明发现时间、责任人、"
        "临时处置措施与复查结果。未按期复查的条目由属地运维负责人跟进，"
        "跨区域事项报省级平台协调，所有记录保留不少于三年备查。"
        "交接清单由上一班次填写、下一班次确认，双方签署后不可再改；"
        "如需更正，必须新增一条更正记录并写明原因，不得覆盖原始内容。"
        "巡检项按区域、设备类型与风险等级分组，重点设备的巡检频次为每班一次，"
        "一般设备为每日一次；发现隐患时先做临时隔离，再按流程上报。"
        "处置结论要写清处置人、处置方式与备件消耗情况，涉及停机的必须附恢复确认。"
        "本节的记录口径与月度考核挂钩，抽查发现缺项、代签或事后补录的，"
        "按考核细则扣分并纳入班组复盘。"
    )
    lines = [
        "# 运维管理手册（S1 整链闸门夹具）",
        "",
        "本手册用于端到端链路验证，内容为合成文本，不含任何真实单位信息。",
        "",
    ]
    for i in range(1, sections + 1):
        lines.append(f"## 第 {i} 条 巡检与交接规范")
        lines.append("")
        if i == 23:
            # ★ 关键句：只出现这一次 ⇒ 停用命中分段后应当 0 条
            lines.append(f"{NEEDLE}，按月由属地分公司申报，省级平台复核后发放。")
        else:
            lines.append(f"{filler}")
        lines.append("")
    text = "\n".join(lines)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    return len(text)


def login(username: str, password: str) -> str:
    status, body = api("POST", "/auth/login", "", {"username": username, "password": password})
    token = (body.get("data") or {}).get("accessToken")
    if status != 200 or not token:
        raise SystemExit(f"登录失败（{username}）：status={status} body={str(body)[:200]}")
    return token


def doc_row(token: str, kb_id: str, doc_id: str) -> dict:
    """从列表端点读这一篇的最新状态（文档端点都挂在知识库下）。"""
    _, body = api("GET", f"/knowledge-bases/{kb_id}/documents?page=1&size=100", token)
    for row in ((body.get("data") or {}).get("list")) or []:
        if row.get("id") == doc_id:
            return row
    return {}


def segments_of(token: str, doc_id: str) -> list[dict]:
    """分段列表（第 1 页 100 条：本夹具约 55 段，一页装得下）。"""
    status, body = api("GET", f"/segments?documentId={doc_id}&page=1&size=100", token)
    if status != 200:
        raise SystemExit(f"读分段列表失败：status={status} body={str(body)[:200]}")
    return ((body.get("data") or {}).get("list")) or []


def hit_test(token: str, kb_id: str, mode: str, threshold: float) -> list[dict]:
    status, body = api(
        "POST",
        "/retrieval/test",
        token,
        {
            "question": QUESTION,
            "mode": mode,
            "threshold": threshold,
            "topN": 10,
            "noRefStrategy": "REFUSE",
            "kbIds": [kb_id],
        },
    )
    if status != 200:
        raise SystemExit(f"命中测试失败（{mode}）：status={status} body={str(body)[:300]}")
    return (body.get("data") or {}).get("items") or []


# ──────────────────────────────── 主链 ────────────────────────────────


def main() -> int:
    ok: list[str] = []
    bad: list[str] = []
    notes: list[str] = []

    def check(cond: bool, msg: str) -> bool:
        (ok if cond else bad).append(msg)
        print(("  [OK] " if cond else "  [NG] ") + msg)
        return cond

    def note(msg: str) -> None:
        notes.append(msg)
        print("  [··] " + msg)

    admin = login("admin", "admin")
    stamp = str(int(time.time()))

    kb_id = ""
    doc_id = ""
    model_id = ""
    delay_injected = False
    workdir = tempfile.mkdtemp(prefix="s1chain-")
    md_path = os.path.join(workdir, f"s1-chain-{stamp}.md")
    md_name = f"S1整链-{stamp}.md"

    try:
        # ── 1. 空库起点 ──
        print("== 1. 建库（从空库开始）==")
        chars = build_markdown(md_path)
        status, created = api(
            "POST",
            "/models",
            admin,
            {
                "name": f"S1整链向量-{stamp}",
                "kind": "EMBEDDING",
                "supplier": "LOCAL_INFERENCE",
                "baseModel": "bge-m3",
                "dimension": 768,
            },
        )
        model_id = (created.get("data") or {}).get("id") or ""
        if not check(status == 200 and bool(model_id), f"建向量模型（{status}）"):
            return 1
        note(f"向量模型 {model_id}：桩返回 {768} 维（与 `_EMBED_DIM` 一致）")

        status, kb = api(
            "POST",
            "/knowledge-bases",
            admin,
            {
                "name": f"S1整链库-{stamp}",
                "category": "制度规范",
                "importType": "LOCAL",
                "embeddingModelId": model_id,
                "maxClassification": "SECRET",
            },
        )
        kb_id = (kb.get("data") or {}).get("id") or ""
        if not check(status == 200 and bool(kb_id), f"建知识库（{status}）"):
            return 1
        _, listing = api("GET", f"/knowledge-bases?size=50&keyword={stamp}", admin)
        row = next(
            (
                r
                for r in ((listing.get("data") or {}).get("list")) or []
                if r.get("id") == kb_id
            ),
            {},
        )
        check(row.get("documentCount") == 0, f"新库是空的（documentCount={row.get('documentCount')}）")

        # ── 2. 上传真实文档 ──
        print("== 2. 上传真实文档 ==")
        status, uploaded = upload(admin, kb_id, md_path, md_name)
        data = uploaded.get("data")
        doc_id = (
            (data[0].get("id") if isinstance(data, list) and data else None)
            or (data or {}).get("id")
            or ""
        )
        if not check(status == 200 and bool(doc_id), f"上传 {md_name}（{chars} 字，{status}）"):
            return 1

        # ── 3. 解析落地（含切分）──
        print("== 3. 等解析落地（轮询，不用固定 sleep）==")
        deadline = time.time() + 180
        row: dict = {}
        while time.time() < deadline:
            row = doc_row(admin, kb_id, doc_id)
            if row.get("status") in {"PENDING_EMBEDDING", "SEGMENT_FAILED"}:
                break
            time.sleep(1.0)
        landed = row.get("status")
        if not check(landed == "PENDING_EMBEDDING", f"解析落地到 PENDING_EMBEDDING（实际 {landed}）"):
            note(f"failReason={row.get('failReason')} failHint={row.get('failHint')}")
            return 1
        seg_count = int(row.get("segmentCount") or 0)
        check(seg_count >= 41, f"段数 ≥41（真实 {seg_count} 段 ⇒ 至少 3 批向量化）")

        # ── 4. 切分预览与入库同源 + 调参生效 ──
        print("== 4. 切分预览：与入库同源、调参真的改变计划 ==")
        status, preview_same = api(
            "POST",
            "/segmentation/preview",
            admin,
            {"documentId": doc_id, "segmentLength": 500, "overlap": 60, "strategy": "AUTO"},
        )
        pdata = preview_same.get("data") or {}
        check(status == 200, f"预览端点可用（{status}）")
        check(
            pdata.get("segmentCount") == seg_count,
            f"预览段数 == 入库段数（{pdata.get('segmentCount')} vs {seg_count}）",
        )

        status, preview_small = api(
            "POST",
            "/segmentation/preview",
            admin,
            {"documentId": doc_id, "segmentLength": 300, "overlap": 0, "strategy": "AUTO"},
        )
        status2, preview_big = api(
            "POST",
            "/segmentation/preview",
            admin,
            {"documentId": doc_id, "segmentLength": 800, "overlap": 0, "strategy": "AUTO"},
        )
        small = preview_small.get("data") or {}
        big = preview_big.get("data") or {}
        n_small = int(small.get("segmentCount") or 0)
        n_big = int(big.get("segmentCount") or 0)
        print(f"  · 调参读数：300 字 ⇒ {n_small} 段 · 800 字 ⇒ {n_big} 段 · 500/60 ⇒ {seg_count} 段")
        check(status == 200 and status2 == 200, "两组调参预览都返回 200")
        check(n_small > n_big > 0, f"段长越小段数越多（300→{n_small} > 800→{n_big}）")
        over_small = [s for s in (small.get("segments") or []) if int(s.get("charCount") or 0) > 300]
        over_big = [s for s in (big.get("segments") or []) if int(s.get("charCount") or 0) > 800]
        check(not over_small, f"300 字计划里没有超长段（超长 {len(over_small)} 段）")
        check(not over_big, f"800 字计划里没有超长段（超长 {len(over_big)} 段）")

        # ── 5. 向量化：进度是库里的真数字 ──
        print("== 5. 向量化：真实进度 + 终态一致 ==")
        delay_injected = stub_config({"delay_ms": STUB_DELAY_MS})
        if delay_injected:
            note(f"已向桩注入 delay_ms={STUB_DELAY_MS}（三批 ⇒ 约 0.9s 窗口）")
        else:
            note("桩不可达或未接受 delay_ms ⇒ 本跑不判定中间进度（其余断言不受影响）")

        status, _ = api("POST", f"/documents/{doc_id}/embedding", admin, {})
        check(status == 200, f"开始向量化（{status}）")

        total = seg_count
        samples: list[tuple[str, int, int]] = []
        deadline = time.time() + 180
        final_status = ""
        while time.time() < deadline:
            row = doc_row(admin, kb_id, doc_id)
            st = row.get("status") or "?"
            proc = int(row.get("processedSegments") or 0)
            tot = int(row.get("segmentCount") or 0)
            if not samples or samples[-1][1:] != (proc, tot) or samples[-1][0] != st:
                samples.append((st, proc, tot))
            if st in {"SUCCEEDED", "EMBEDDING_FAILED", "SEGMENT_FAILED"}:
                final_status = st
                break
            time.sleep(0.1)

        intermediate = [s for s in samples if s[0] == "EMBEDDING" and 0 < s[1] < s[2]]
        print(f"  · 采样 {len(samples)} 次：" + " → ".join(f"{s[0]}({s[1]}/{s[2]})" for s in samples[:12]))
        if delay_injected:
            check(
                bool(intermediate),
                f"向量化过程中读到中间进度（0 < processed < {total}），实际样本：{intermediate[:3]}",
            )
        monotonic = all(samples[i][1] <= samples[i + 1][1] for i in range(len(samples) - 1))
        check(monotonic, "processedSegments 单调不减（进度不回退）")
        check(final_status == "SUCCEEDED", f"终态 SUCCEEDED（实际 {final_status}）")
        row = doc_row(admin, kb_id, doc_id)
        proc_final = int(row.get("processedSegments") or 0)
        check(
            proc_final == total and total == int(row.get("segmentCount") or 0),
            f"进度分母一致（processed={proc_final} · segmentCount={row.get('segmentCount')}）",
        )

        segs = segments_of(admin, doc_id)
        check(
            len(segs) == total,
            f"GET /segments 条数 == segmentCount（{len(segs)} vs {total}）",
        )
        not_embedded = [s for s in segs if (s.get("embedding") or "") != "EMBEDDED"]
        check(not not_embedded, f"所有分段都 EMBEDDED（未完成 {len(not_embedded)} 段）")

        # ── 6. 命中测试 ──
        print("== 6. 命中测试 ==")
        items = hit_test(admin, kb_id, "HYBRID", 0.6)
        names = {i.get("docName") for i in items}
        hit_ids = [i.get("segmentId") for i in items if i.get("segmentId")]
        print(f"  · HYBRID 命中 {len(items)} 条，文档 {sorted(n for n in names if n)}")
        check(bool(items), "HYBRID 有命中（不是 0 条）")
        check(
            all(n and stamp in str(n) for n in names),
            f"命中的就是这份文档（实际 {sorted(n for n in names if n)}）",
        )
        check(
            any(NEEDLE in (i.get("content") or "") for i in items),
            "命中内容里含埋进去的那句话（搜到的是真内容，不是别的段）",
        )

        vector_items = hit_test(admin, kb_id, "VECTOR", 0.6)
        note(
            f"VECTOR 阈值 0.6 命中 {len(vector_items)} 条 —— 桩向量语义无关，这条只记读数不断言"
        )

        # ── 7. 停用承载答案的分段后不再命中 ──
        # ★ 判据落在**那句话**上，不落在"结果条数"上：冒充的填充段里到处都有「运维」「标准」，
        #   全文路本来就该命中它们 —— 断"停用后 0 条"会绿在一个与停用无关的现象上，
        #   而且填充文本一改就会假红。
        print("== 7. 停用承载答案的分段后不再命中 ==")
        check(bool(hit_ids), f"命中结果带 segmentId（{len(hit_ids)} 条）")
        needle_ids = [s.get("id") for s in segs if NEEDLE in (s.get("content") or "")]
        check(bool(needle_ids), f"那句话落在 {len(needle_ids)} 个分段里")
        note(f"承载答案的分段：{needle_ids}")
        off_ok = True
        for sid in needle_ids:
            st, _ = api("PATCH", f"/segments/{sid}/enabled", admin, {"enabled": False})
            off_ok = off_ok and st == 200
        check(off_ok, f"{len(needle_ids)} 个分段全部停用成功")

        after = hit_test(admin, kb_id, "HYBRID", 0.6)
        check(
            not any(NEEDLE in (i.get("content") or "") for i in after),
            f"停用后搜不到那句话（剩下 {len(after)} 条，都是别的段）",
        )
        check(
            not ({i.get("segmentId") for i in after} & set(needle_ids)),
            "停用的分段不再出现在结果里",
        )

        # ── 8. 对照：重新启用后那句话回来了 ──
        print("== 8. 对照：重新启用后那句话回来 ==")
        for sid in needle_ids:
            api("PATCH", f"/segments/{sid}/enabled", admin, {"enabled": True})
        back = hit_test(admin, kb_id, "HYBRID", 0.6)
        back_hits = [i for i in back if NEEDLE in (i.get("content") or "")]
        check(bool(back_hits), f"重新启用后那句话又命中（{len(back_hits)} 条）")
        check(
            all(stamp in str(i.get("docName") or "") for i in back_hits),
            "回来的还是这份文档（不是别的库/别的文档）",
        )

    finally:
        # ★ 必须复位：同一 job 里后面的闸门共用这个桩，泄漏的 delay 会把它们拖慢/拖红。
        if delay_injected:
            stub_config({"delay_ms": 0})
        if doc_id:
            api("DELETE", f"/documents/{doc_id}", admin)
        if kb_id:
            api("DELETE", f"/knowledge-bases/{kb_id}", admin)
        if model_id:
            api("DELETE", f"/models/{model_id}", admin)
        try:
            os.remove(md_path)
        except OSError:
            pass
        try:
            os.rmdir(workdir)
        except OSError:
            pass

    print(f"\n通过 {len(ok)}/{len(ok) + len(bad)}")
    for m in notes:
        print("  · 注记：" + m)
    for m in bad:
        print("  - 失败：" + m)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
