"""查询理解的真栈闸门（第 12 周 · 0.4.0）—— 回答「一次提问到底发了几条查询、拆了几个子问题」。

为什么需要一个**真栈**闸门：`QueryUnderstandingParserTest` 验的是"给定模型输出 ⇒ 我解析出什么"，
`QueryUnderstandingServiceTest` 验的是"上游坏掉时我退到哪一档"，两者都**不碰网络**。
而这一层的真链路上还隔着三段：**挑对话模型 → 真发一次 /chat/completions → 埋点回包**。
那三段（"模型到底有没有被调、调的是哪个、结果有没有进回包"）只有从头走一遍才验得到。

★ 判据就是 ROADMAP 第 12 周那句演示："问一个多意图问题 ⇒ 埋点里看到 **M 个子问题 × N 条查询**"。
  所以本闸门断言的是 `trace` 里的三个数字，不是"接口返回了 200"。

★ 夹具的两处刻意安排：
  1. **自己注册并加载一个对话模型**（`stub-plain`，指向桩服务）—— 开发库里原本没有任何
     `kind=CHAT` 的模型，而"没有对话模型"会让这一层如实降级到 D4。若不先造它，
     本闸门会**绿在一个什么都没验的状态上**（那种绿是这里最危险的失败模式）。
  2. **上传一份真 DOCX 并等向量化完成** —— 不然"检索到 0 条"与"查询理解没生效"长得一样。

用法：
    python check_query_understanding_e2e.py                      # 默认 http://localhost:5280
    python check_query_understanding_e2e.py http://127.0.0.1:5280
"""
from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"

DOCX = (
    Path(__file__).resolve().parent.parent
    / "backend/src/test/resources/fixtures/structured.docx"
)

STUB_API_BASE = "http://localhost:11434/v1"

# 多意图问题：两个互不相干的意图 —— 这一层该把它们拆开（10 §3.3.1）
QUESTION = "差旅报销标准和采购审批流程分别是什么"


def api(method: str, path: str, token: str, body: dict | None = None) -> tuple[int, dict]:
    """调后端 REST（走 dev server 的代理 ⇒ 与页面同一条路）。★ 未登录时不发空的 Authorization 头。"""
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
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")
        except Exception:  # noqa: BLE001
            return e.code, {}
    except Exception as e:  # noqa: BLE001
        return -1, {"message": str(e)}


def upload(path: str, token: str, file: Path) -> tuple[int, dict]:
    boundary = "----queryunderstanding0boundary"
    buf = b""
    for key, value in (
        ("docType", "制度"),
        # ★ 责任部门改成 org_id（UUID）：种子里只有 ROOT 是确定性 id
        ("orgId", "00000000-0000-4000-8000-000000000001"),
        ("classification", "INTERNAL"),
    ):
        buf += (
            f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'
        ).encode("utf-8")
    buf += (
        f'--{boundary}\r\nContent-Disposition: form-data; name="files"; '
        f'filename="{file.name}"\r\nContent-Type: application/octet-stream\r\n\r\n'
    ).encode("utf-8")
    buf += file.read_bytes() + b"\r\n" + f"--{boundary}--\r\n".encode("utf-8")
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


def wait_doc_status(token: str, kb_id: str, doc_id: str, expected: set[str], seconds: int) -> str:
    deadline = time.time() + seconds
    status = "?"
    while time.time() < deadline:
        _, body = api("GET", f"/knowledge-bases/{kb_id}/documents?page=1&size=100", token)
        for row in ((body.get("data") or {}).get("list")) or []:
            if row.get("id") == doc_id:
                status = row.get("status") or "?"
                break
        if status in expected:
            return status
        time.sleep(1.5)
    return status


def login(username: str, password: str) -> str:
    status, body = api("POST", "/auth/login", "", {"username": username, "password": password})
    token = (body.get("data") or {}).get("accessToken")
    if status != 200 or not token:
        raise SystemExit(f"登录失败（{username}）：status={status} body={str(body)[:200]}")
    return token


def hit_test(token: str, kb_id: str, **params) -> dict:
    body = {
        "question": QUESTION,
        "mode": "HYBRID",
        "threshold": 0.6,
        "topN": 5,
        "noRefStrategy": "REFUSE",
        "kbIds": [kb_id],
    }
    body.update(params)
    status, payload = api("POST", "/retrieval/test", token, body)
    if status != 200:
        raise SystemExit(f"命中测试失败：status={status} body={str(payload)[:300]}")
    return payload.get("data") or {}


def main() -> int:
    ok: list[str] = []
    bad: list[str] = []

    def check(cond: bool, msg: str) -> bool:
        (ok if cond else bad).append(msg)
        print(("  [OK] " if cond else "  [NG] ") + msg)
        return cond

    admin = login("admin", "admin")

    # ── 夹具①：一个**已加载**的对话模型（缺了它就只会看到 D4 降级，本闸门会绿得毫无信息） ──
    stamp = str(int(time.time()))
    status, created = api(
        "POST",
        "/models",
        admin,
        {
            "name": f"查询理解对话-{stamp}",
            "kind": "CHAT",
            "supplier": "LOCAL_INFERENCE",
            "baseModel": "stub-plain",
            "apiBase": STUB_API_BASE,
        },
    )
    chat_model = (created.get("data") or {}).get("id")
    if status != 200 or not chat_model:
        print(f"  [NG] 建对话模型失败：status={status} body={str(created)[:200]}")
        return 1
    status, loaded = api("POST", f"/models/{chat_model}/load", admin, {})
    if status != 200:
        print(f"  [NG] 加载对话模型失败：status={status} body={str(loaded)[:200]}")
        return 1
    print(f"  · 对话模型就绪：{chat_model}（stub-plain，已加载）")

    # ── 夹具②：一个有内容、可检索的库 ──
    status, created = api(
        "POST",
        "/models",
        admin,
        {
            "name": f"查询理解向量-{stamp}",
            "kind": "EMBEDDING",
            "supplier": "LOCAL_INFERENCE",
            "baseModel": "bge-m3",
            "dimension": 768,
        },
    )
    embed_model = (created.get("data") or {}).get("id")
    status, kb = api(
        "POST",
        "/knowledge-bases",
        admin,
        {
            "name": f"查询理解库-{stamp}",
            "category": "制度规范",
            "importType": "LOCAL",
            "embeddingModelId": embed_model,
            "maxClassification": "INTERNAL",
        },
    )
    kb_id = (kb.get("data") or {}).get("id")
    if status != 200 or not kb_id:
        print(f"  [NG] 建知识库失败：status={status} body={str(kb)[:200]}")
        return 1

    if not DOCX.exists():
        print(f"  [NG] 夹具文件不存在：{DOCX}")
        return 1
    status, uploaded = upload(f"/knowledge-bases/{kb_id}/documents", admin, DOCX)
    data = uploaded.get("data")
    doc_id = (data[0].get("id") if isinstance(data, list) and data else None) or (data or {}).get("id")
    if status != 200 or not doc_id:
        print(f"  [NG] 上传失败：status={status} body={str(uploaded)[:200]}")
        return 1
    parsed = wait_doc_status(admin, kb_id, doc_id, {"PENDING_EMBEDDING", "SEGMENT_FAILED"}, 90)
    if parsed != "PENDING_EMBEDDING":
        print(f"  [NG] 等解析落地失败（实际 {parsed}）")
        return 1
    api("POST", f"/documents/{doc_id}/embedding", admin, {})
    embedded = wait_doc_status(admin, kb_id, doc_id, {"SUCCEEDED", "EMBEDDING_FAILED"}, 120)
    if embedded != "SUCCEEDED":
        print(f"  [NG] 等向量化成功失败（实际 {embedded}）")
        return 1
    print(f"  · 夹具就绪：kbId={kb_id} docId={doc_id}（已解析 + 已向量化）")

    # ── 判据：埋点里能看到 M 个子问题 × N 条查询 ──
    result = hit_test(admin, kb_id, queryCount=3, decomposeEnabled=True, maxSubQuestions=3)
    trace = result.get("trace")
    print(f"  · trace = {trace}")
    if not check(trace is not None, "回包里带上了策略轨迹（trace）"):
        return 1

    # ★ 这里**不断言** degradeLevel=0：桩模型按 schema 造的是占位查询（stub-1…），
    #   中文语料上必然一条都不命中 ⇒ 会走到"退回原始问题"那条兜底（D4）。
    #   所以下面分两件事断言：
    #   ① 这一层**真的产出了 M × N**（只有真调到了模型并解析成功才可能）；
    #   ② 那段兜底**真的救回了内容**（D4 + 命中 > 0）—— 它正是被这条闸门的前身逼出来的。
    #   "正常路径 D0"由单测覆盖（QueryUnderstandingServiceTest 用合法 JSON 喂它）。
    check(
        trace.get("degradeLevel", 0) == 4 and "没命中" in (trace.get("degradeReason") or ""),
        f"改写后的查询全部没命中时退回了原始问题单路（D4，原因：{trace.get('degradeReason')}）",
    )
    check(
        len(result.get("items") or []) > 0,
        f"兜底真的救回了内容（{len(result.get('items') or [])} 条）—— "
        f"'改写把结果改没了'必须能被兜住，否则这一层是净损失",
    )
    check(
        trace.get("subQuestionCount", 0) >= 2,
        f"多意图问题被拆成了多个子问题（M={trace.get('subQuestionCount')}）",
    )
    check(
        trace.get("queryCount", 0) >= 2,
        f"发出去的是多条查询（N={trace.get('queryCount')}）—— 这正是'埋点里看到 M × N'",
    )
    check(
        trace.get("pathCount") == trace.get("queryCount"),
        f"总路数等于实际查询数（{trace.get('pathCount')} vs {trace.get('queryCount')}，上限 6 内不该被截）",
    )
    # ★ 这里**不**断言"命中了内容"：桩模型按 schema 造的是占位查询（stub-1…），
    #   中文语料上必然召回不到 —— 那是夹具的性质（同"桩向量语义无关"），不是这一层的问题。
    #   要验"检索本身是通的"，用下面 queryCount=0 那条：它跑的是**原始问题**。

    # ── 关掉这一层：应当**不是降级**，而是原样单路；且原始问题必须真的召回得到 ──
    off = hit_test(admin, kb_id, queryCount=0)
    off_trace = off.get("trace") or {}
    off_items = off.get("items") or []
    print(f"  · queryCount=0 的 trace = {off_trace}，命中 {len(off_items)} 条")
    check(
        off_trace.get("degradeLevel") == 0,
        "queryCount=0 不算降级（关功能与模型坏掉是两件事，混在一起会让降级率失去意义）",
    )
    check(
        off_trace.get("pathCount") == 1,
        f"关掉查询理解后只发一路（pathCount={off_trace.get('pathCount')}）",
    )
    check(
        len(off_items) > 0,
        f"原始问题照样召回得到（{len(off_items)} 条）—— 否则'没命中'与'检索链坏了'分不清",
    )

    # ── 分解开关：关掉之后子问题必须合并成 1 ──
    merged = hit_test(admin, kb_id, queryCount=3, decomposeEnabled=False)
    merged_trace = merged.get("trace") or {}
    print(f"  · decomposeEnabled=false 的 trace = {merged_trace}")
    check(
        merged_trace.get("subQuestionCount") == 1,
        f"关掉分解后子问题合并为 1（M={merged_trace.get('subQuestionCount')}）",
    )

    # ── 越界参数要明确 400（不能"收下但按别的值跑"） ──
    status, payload = api(
        "POST",
        "/retrieval/test",
        admin,
        {"question": QUESTION, "mode": "HYBRID", "threshold": 0.6, "topN": 5, "kbIds": [kb_id], "queryCount": 9},
    )
    check(status == 400, f"queryCount=9 被明确拒绝（实际 {status}：{str(payload.get('message'))[:60]}）")

    print(f"\n通过 {len(ok)}/{len(ok) + len(bad)}")
    for m in bad:
        print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
