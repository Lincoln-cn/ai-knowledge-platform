"""多跳检索的真栈闸门（第 14 周 · 0.4.2）—— 回答「答案跨两段时，多跳真的把它捞回来了吗」。

为什么需要一个**真栈**闸门：`MultiHopTest` / `MultiHopJudgeTest` 用手写假实现验的是"停止条件与埋点"，
两处都**不碰网络、不碰库**。而这一层在真实链路上还隔着三段：**真 SQL 检索两轮 → 真的调一次模型判定
→ 埋点回包**。那三段（"第 2 轮到底有没有发出去、发的是什么、新分段有没有进结果"）只有从头走一遍才验得到。

★ 判据就是 ROADMAP 第 14 周那句演示："难例（答案跨两段）⇒ 多跳命中；埋点看到轮次与停止原因"。
  所以本闸门的核心读数是一对**对照**，而不是"接口返回了 200"：

    同一个问题、同一个库、同样两段语料
      关多跳 ⇒ 答案段**不在**结果里（第 1 轮只命中给线索的那一段）
      开多跳 ⇒ 答案段**在**结果里 + hopRounds=2 + 停止原因是 MAX_HOPS

  这一对是这份闸门唯一无法伪造的东西：`hopRounds=2` 可以由别的原因产生，
  但"关的时候没有、开的时候有"只能由多跳造成。

★ 夹具（`fixtures/multihop.md`）是**刻意**这么写的：
  1. 线索段含 `A-102`，答案段**不含**（否则第 1 轮就命中了，多跳无从体现）；
  2. 答案段的词（附件三 / 违约金 / 上限）**不是**问题里的词（问题问"处罚标准"）⇒ 第 1 轮够不着它；
  3. 有 3 段 ⇒ 第 1 轮命中数必然 < 3 ⇒ **触发条件是确定的**（不靠分数抖动）；
  4. ★ **文档标题必须是中性的（"附件汇编"）** —— 第一版写成「A-102 号设备采购合同」，CI 立刻抓到：
     分段的索引文本里带着**章节路径**（`SearchText.prefix` 拼的是 标题｜章节路径）⇒ 每一段都含
     `A-102 号设备采购合同` ⇒ 答案段被第 1 轮直接命中，"难例"这个前提就没了。
     **标题是"每一段都有"的文本，所以它不能含问题里的词。**
  5. ⇒ 前提不靠"我以为"：运行时用两个单词查询分别证明线索段与答案段**是两段**（见下），
     同段就直接红 —— 否则本闸门会绿在一个虚假的前提上。

★ 检索模式用 FULLTEXT 而不是 HYBRID：桩向量是按 hash 造的、与语义无关，
  HYBRID 会引入一次**随机**的"这条分段恰好分数高" ⇒ 闸门会随机红（本项目对随机红比不红更警惕）。
  而这一条验的是**多跳**、不是向量召回；全文路的分数是"查询词命中覆盖率"，完全确定。

★ 桩的 `entity_override` 是这份闸门的前提：按 schema 造的占位值是 `sufficient=true`，
  多跳永远停在第 1 轮 —— 那样闸门会绿在一个"什么都没验"的状态上（这里最危险的失败模式）。
  它让判定回「不够 + 下一轮查附件三」，于是第 2 轮真的会发出去。

用法：
    python check_multihop_e2e.py                      # 默认 http://localhost:5280
    python check_multihop_e2e.py http://127.0.0.1:5280
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"

FIXTURE = Path(__file__).resolve().parent.parent / "backend/src/test/resources/fixtures/multihop.md"

STUB_API_BASE = "http://localhost:11434/v1"
STUB_CONFIG = "http://localhost:11434/__stub/config"
STUB_RESET = "http://localhost:11434/__stub/reset"

# ★ 问"处罚标准"，而答案段里写的是"违约金上限" —— 词面不通，只有靠第 2 轮才够得着
QUESTION = "A-102 号设备的处罚标准是多少"

# 夹具前提用的两个单词查询（各自只应命中一段；它们命中同一段 ⇒ "答案跨两段"不成立）
CLUE_ONLY = "A-102"
ANSWER_ONLY = "百分之五"

# 第 2 轮要查什么：由桩脚本化给出（这正是"模型给出下一轮查询"那一步）
NEXT_QUERY = "附件三 违约金 上限"

# 答案段里的独有片段：断言"它有没有进结果"就靠它（第 1 轮够不着它，第 2 轮才够得着）
ANSWER_MARK = "百分之五"


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


def stub_post(url: str, body: dict) -> tuple[int, dict]:
    """改桩的行为。桩不可达时明确报出来 —— 否则本闸门会"绿在没覆盖到的状态上"。"""
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except Exception as e:  # noqa: BLE001
        return -1, {"message": str(e)}


def upload(path: str, token: str, file: Path) -> tuple[int, dict]:
    boundary = "----multihop0boundary"
    buf = b""
    for key, value in (
        # ★ 文种与库分类沿用既有闸门用过的值：它们是**字典**里的项，
        #   自造一个（如"合同"）会先撞上字典校验，把失败原因指到与检索无关的地方
        ("docType", "制度"),
        # ★ 责任部门是 org_id（UUID）：种子里只有 ROOT 是确定性 id
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
        # ★ 用 FULLTEXT 而不是 HYBRID：桩向量是按 hash 造的、与语义无关，
        #   HYBRID 会引入一次**随机**的"这条分段恰好分数高" ⇒ 闸门会随机红。
        #   而这一条验的是**多跳**，不是向量召回 —— 全文路的分数是"查询词命中覆盖率"，完全确定。
        "mode": "FULLTEXT",
        "threshold": 0.6,
        "topN": 5,
        "noRefStrategy": "REFUSE",
        # ★ 关掉查询理解（queryCount=0）：第 1 轮就检**原始问题本身**。
        #   两个理由：① 确定性 —— 否则第 1 轮发的是桩按 schema 造的占位查询（stub-1…），
        #   它是"一条都不命中 ⇒ 退到 D4 兜底"的间接路径；② 更贴近演示 ——
        #   真实情形是"第 1 轮命中线索段、第 2 轮命中答案段"，两段都在结果里。
        "queryCount": 0,
        "kbIds": [kb_id],
    }
    body.update(params)
    status, payload = api("POST", "/retrieval/test", token, body)
    if status != 200:
        raise SystemExit(f"命中测试失败：status={status} body={str(payload)[:300]}")
    return payload.get("data") or {}


def answers_in(items: list[dict]) -> bool:
    """结果里有没有答案段（按那段独有的片段认）。"""
    return any(ANSWER_MARK in (item.get("content") or "") for item in items)


def main() -> int:
    ok: list[str] = []
    bad: list[str] = []

    def check(cond: bool, msg: str) -> bool:
        (ok if cond else bad).append(msg)
        print(("  [OK] " if cond else "  [NG] ") + msg)
        return cond

    admin = login("admin", "admin")

    # ── 前提：桩必须可达且能脚本化（否则这份闸门验不到"第 2 轮真的发出去了"） ──
    status, _ = stub_post(STUB_RESET, {})
    if status != 200:
        print(f"  [NG] 桩服务不可达（{STUB_RESET}）—— 本闸门需要桩才能造出'判定说不够'的情形")
        return 1
    status, config = stub_post(STUB_CONFIG, {"entity_override": {"sufficient": False, "nextQuery": NEXT_QUERY}})
    if status != 200:
        print(f"  [NG] 桩的 entity_override 没设上：status={status} body={str(config)[:200]}")
        return 1
    print(f"  · 桩已脚本化：判定回 sufficient=false + nextQuery=「{NEXT_QUERY}」")

    try:
        # ── 夹具①：一个**已加载**的对话模型（缺了它判定必然失败，本闸门会绿得毫无信息） ──
        stamp = str(int(time.time()))
        status, created = api(
            "POST",
            "/models",
            admin,
            {
                "name": f"多跳对话-{stamp}",
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

        # ── 夹具②：难例语料（3 段：线索段 / 答案段 / 无关段） ──
        status, created = api(
            "POST",
            "/models",
            admin,
            {
                "name": f"多跳向量-{stamp}",
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
                "name": f"多跳库-{stamp}",
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

        if not FIXTURE.exists():
            print(f"  [NG] 夹具文件不存在：{FIXTURE}")
            return 1
        status, uploaded = upload(f"/knowledge-bases/{kb_id}/documents", admin, FIXTURE)
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
        print(f"  · 夹具就绪：kbId={kb_id} docId={doc_id}（难例语料已解析 + 已向量化）")

        # ── 夹具前提：线索与答案必须**在不同的分段里** —— 否则"答案跨两段"这句话不成立，
        #    而本闸门会绿在一个虚假的前提上（第一版夹具就是这样：标题进了每段的索引文本） ──
        clue_ids = {
            item["segmentId"] for item in (hit_test(admin, kb_id, question=CLUE_ONLY).get("items") or [])
        }
        answer_ids = {
            item["segmentId"] for item in (hit_test(admin, kb_id, question=ANSWER_ONLY).get("items") or [])
        }
        check(
            bool(clue_ids) and bool(answer_ids) and not (clue_ids & answer_ids),
            f"★ 夹具前提：线索段与答案段确实是**两段**（线索 {len(clue_ids)} 段 / 答案 {len(answer_ids)} 段 / "
            f"交集 {len(clue_ids & answer_ids)}）—— 交集非空就说明难例无从体现",
        )

        # ── 对照组的"关"：多跳关着 ⇒ 答案段够不着（第 1 轮只命中给线索的那一段） ──
        off = hit_test(admin, kb_id, multiHopEnabled=False)
        off_trace = off.get("trace") or {}
        off_items = off.get("items") or []
        print(f"  · 关多跳：命中 {len(off_items)} 条，trace={off_trace}")
        check(
            str(off_trace.get("hopStopReason") or "").startswith("NOT_ENABLED"),
            f"关多跳时埋点写的是「没开」而不是留空（{off_trace.get('hopStopReason')}）",
        )
        check(
            off_trace.get("hopRounds") == 1,
            f"关多跳时轮次恒为 1（{off_trace.get('hopRounds')}）",
        )
        check(
            not answers_in(off_items),
            f"★ 关多跳时答案段够不着（结果里没有「{ANSWER_MARK}」）—— 这正是难例的定义",
        )
        check(
            len(off_items) > 0,
            f"但线索段照样命中（{len(off_items)} 条）—— 否则'够不着答案'与'检索链坏了'分不清",
        )

        # ── 对照组的"开"：多跳开着 + 桩给出下一轮查询 ⇒ 答案段必须被捞回来 ──
        on = hit_test(admin, kb_id, multiHopEnabled=True, maxHops=2)
        on_trace = on.get("trace") or {}
        on_items = on.get("items") or []
        print(f"  · 开多跳：命中 {len(on_items)} 条，trace={on_trace}")
        check(
            on_trace.get("hopTriggered") is True,
            f"多跳被触发了（命中数 < 3 或最高分 < 阈值；实际 hopTriggered={on_trace.get('hopTriggered')}）",
        )
        check(
            on_trace.get("hopRounds") == 2,
            f"★ 第 2 轮真的发出去了（hopRounds={on_trace.get('hopRounds')}）",
        )
        check(
            str(on_trace.get("hopStopReason") or "").startswith("MAX_HOPS"),
            f"停止原因写出机器可判的前缀 + 人读的说明（{on_trace.get('hopStopReason')}）",
        )
        check(
            (on_trace.get("hopNewSegments") or 0) >= 1,
            f"第 2 轮带来了新分段（hopNewSegments={on_trace.get('hopNewSegments')}）",
        )
        check(
            answers_in(on_items),
            f"★★ 多跳真的把答案捞回来了（结果里有「{ANSWER_MARK}」）—— 对比关着那一组，这就是多跳的全部价值",
        )

        # ── 越界参数要明确 400（不能"收下但按别的值跑"） ──
        status, payload = api(
            "POST",
            "/retrieval/test",
            admin,
            {
                "question": QUESTION,
                "mode": "FULLTEXT",
                "threshold": 0.6,
                "topN": 5,
                "kbIds": [kb_id],
                "maxHops": 9,
            },
        )
        check(status == 400, f"maxHops=9 被明确拒绝（实际 {status}：{str(payload.get('message'))[:60]}）")

        # 收尾：清掉夹具库（与其它闸门同口径：闸门不留垃圾数据）
        if os.environ.get("KEEP_FIXTURE"):
            print(f"  · KEEP_FIXTURE=1 ⇒ 保留夹具库 {kb_id} 供排障")
        else:
            st, _ = api("DELETE", f"/knowledge-bases/{kb_id}", admin)
            print(f"  · 已清理夹具库（{st}）")
    finally:
        # ★ 无论成败都要把桩恢复干净：entity_override 残留会让**后面的**闸门看到假的模型行为
        stub_post(STUB_CONFIG, {"entity_override": None})

    print(f"\n通过 {len(ok)}/{len(ok) + len(bad)}")
    for m in bad:
        print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
