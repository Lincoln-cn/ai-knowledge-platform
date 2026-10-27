#!/usr/bin/env python3
"""S3 真栈闸门（MVP · `ROADMAP §2.1`）：终端用户那一整条链。

`docs/ROADMAP.md:43` 对 S3 的定义是七件事，本脚本按顺序逐条给它一条读数：

  ① **流式作答** ② **引用可展开溯源**（引用先于正文、且 refs 里的 segmentId 真能读回内容）
  ③ **连续追问 5 轮**（同一会话里累积，不是每轮新起一个会话）
  ④ **生成中停止保留已生成内容**（终态 `STOPPED`、不发 `error`、已吐的字一个不少地留在库里）
  ⑤ **重新生成**（覆盖同一条消息，不新增一问一答）
  ⑥ **赞/取消赞刷新后仍在**（写进去 → 重新拉消息读回来）
  ⑦ **会话评分**（同一会话再提交是**覆盖**，不是追加）

★★ 与既有闸门的分工（不要重复，也不要以为一条就够）：
  - `check_regenerate_e2e.py` 已经把 ⑤ 挖到"坏 targetMessageId 必须 4xx"那一层；
    这里只断"整链里它接得住"，深挖仍归那条。
  - `ConversationStopEndpointTest`（JUnit）管服务端的判定顺序；这里管**端到端**：
    真的从一条 SSE 流里停住、真的在库里留下已生成的字。
  - ★ 浏览器侧（引用栏能不能点开、按钮在不在）由 `check_flow_walkthrough.py` 与
    `check_reload_keeps_state_e2e.py` 管；本脚本不点浏览器，它是**数据面**的读数。

★★ 为什么需要桩的 `chunk_delay_ms`（本轮给 `backend/tools/openai_stub.py` 新增的注入口）：
  没有它，上游把整段正文一次 flush 全到 ⇒ 「生成中停止」只可能停在"零字"或"全字"两种，
  而 S3 要验的恰恰是**中间态**（已生成的要保留）。`delay_ms` 做不到：它只推迟回答的**开始**。
  本脚本用完必须复位成 0 —— 同一个 job 里后面的闸门共用这一个桩。

★★ 为什么自己造库、自己传文档（而不是"找一个 documentCount>0 的库"）：
  `ci.yml` 的 `gates` 是**顺序**跑的，前一条闸门在 finally 里把夹具删干净之后，
  "找一个有文档的库"完全可能一个都找不到 —— 那时红的会是**夹具**而不是 S3。
  自造夹具多花几秒（一个模型 + 一个库 + 一份 400 字的文档 + 一次向量化），
  换来的是"这条闸门的红只可能来自它自己"。

用法：
    python check_s3_conversation_chain_e2e.py [http://localhost:5280]
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"

#: 桩的根地址（注入帧间延迟用）。
STUB = os.environ.get("AIP_STUB_URL", "http://127.0.0.1:11434").rstrip("/")
#: 桩的 OpenAI 兼容根地址：新建对话模型时要写进 `apiBase`，否则上游请求无处可去。
STUB_API_BASE = STUB + "/v1"

USER = "admin"
PASSWORD = "admin"
#: ★ 问句必须与库里的实际内容对得上：问一个库里没有的词，"0 条引用"会被误读成"检索坏了"。
QUESTION = "报销标准"
#: 追问用同一句话 + 序号：只要后端返回 200，就证明"带着 conversationId 的这一轮"被接住了。
FOLLOW_UP = "那差旅住宿的上限呢"

#: ★ 本条闸门**自己造库、自己传文档、自己向量化**，不去借别的闸门留下的数据：
#:   `ci.yml` 的 `gates` 是**顺序**跑的，前一条闸门在 finally 里把夹具删干净之后，
#:   "找一个 documentCount>0 的库"就可能一个都找不到（==> 一条与 S3 无关的红）。
#:   代价是每次多花几秒，换来的是"这条闸门的红只可能来自它自己"。
ORG_ROOT = "00000000-0000-4000-8000-000000000001"
DOC_TYPE = "制度"
DOC_CLASSIFICATION = "INTERNAL"
KB_CATEGORY = "制度规范"
#: 夹具正文：★ 必须含 `QUESTION` 与 `FOLLOW_UP` 里的实词（报销 / 差旅 / 住宿 / 上限），
#: 否则引用为空会是夹具的问题、而不是检索的问题。
FIXTURE_TEXT = """# 差旅与费用报销管理办法

## 一、适用范围

本办法适用于本单位全体在职人员的国内差旅与日常费用报销。

## 二、差旅报销标准

市内交通费每天不超过八十元，凭票据实报实销；住宿费上限为每晚五百元，超出部分自理。

## 三、报销流程

出差结束后五个工作日内，通过知识助理平台提交报销申请并上传票据扫描件。

## 四、其他

本办法由财务部门负责解释，自发布之日起施行。
"""

#: 帧间延迟（毫秒）。桩按 4 个字符一片切正文，而**桩的答案只有 45 字左右** = 约 12 片 ⇒
#: 60ms 只有 0.72 秒的"正在吐"窗口，主线程在这段窗口里发停止请求**来不及**（实测 `accepted=false`，
#: 终态是 GENERATED 而不是 STOPPED，见 2026-10-27 run `40514976243`）。
#: 300ms ⇒ 约 3.6 秒窗口，比一次 HTTP 往返（真栈实测几十毫秒）宽两个数量级。
#: ★ 不能设太小：停止请求是另一次 HTTP 往返，窗口比往返还短的话，请求到的时候这一轮已经收尾，
#:   回执会是 `accepted=false`（= 停晚了），于是这条闸门变成在测"网络有多快"。
CHUNK_DELAY_MS = 300

problems: list[str] = []


def check(ok: bool, message: str) -> bool:
    if ok:
        print(f"  ✓ {message}")
    else:
        print(f"  ✗ {message}")
        problems.append(message)
    return ok


def note(message: str) -> None:
    print(f"  · {message}")


# ──────────────────────────────── HTTP ────────────────────────────────


def call(method: str, path: str, token: str | None = None, body: dict | None = None) -> tuple[int, str]:
    """调后端 REST（走 dev server 代理 ⇒ 与页面同一条路）。"""
    headers: dict[str, str] = {"Content-Type": "application/json"}
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
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except Exception as e:  # noqa: BLE001
        return -1, str(e)


def data_of(payload: str) -> dict:
    try:
        return (json.loads(payload) or {}).get("data") or {}
    except json.JSONDecodeError:
        return {}


def stub_config(payload: dict) -> bool:
    """改桩的行为（帧间延迟）。桩不可达/不认这个 key ⇒ False，调用方必须如实记「没读到」。"""
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


# ──────────────────────────────── SSE ────────────────────────────────


def parse_frame(line: str) -> dict | None:
    line = line.strip()
    if not line.startswith("data:"):
        return None
    raw = line[len("data:") :].strip()
    if not raw or raw == "[DONE]":
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return None


def sse_frames(payload: str) -> list[dict]:
    """SSE 逐行解析。

    ★ 帧的形状是**按内容命名的键**（实测）：`{"messageId","traceId","model"}` ·
      `{"refs":[…]}` · `{"text":…}` · `{"status","messageId","tokens","durationMs"}` ——
      **没有** `event`/`type` 字段。按 `event` 找永远不命中（S2 那条闸门踩过这个坑）。
    """
    return [f for f in (parse_frame(line) for line in payload.splitlines()) if f]


def meta_of(frames: list[dict]) -> dict:
    for frame in frames:
        if "messageId" in frame and "refs" not in frame and "text" not in frame:
            return frame
    return {}


def refs_of(frames: list[dict]) -> list:
    for frame in frames:
        if "refs" in frame and isinstance(frame["refs"], list):
            return frame["refs"]
    return []


def text_of(frames: list[dict]) -> str:
    return "".join(str(f.get("text") or "") for f in frames if "text" in f)


def done_of(frames: list[dict]) -> dict:
    for frame in reversed(frames):
        if "status" in frame and "messageId" in frame:
            return frame
    return {}


def error_frames(frames: list[dict]) -> list[dict]:
    return [f for f in frames if "error" in f]


def first_text_index(frames: list[dict]) -> int:
    for index, frame in enumerate(frames):
        if "text" in frame:
            return index
    return -1


def first_refs_index(frames: list[dict]) -> int:
    for index, frame in enumerate(frames):
        if "refs" in frame:
            return index
    return -1


def chat(token: str, body: dict) -> tuple[int, list[dict]]:
    """整段读完的一次提问（不需要中途停止时用它）。"""
    status, payload = call("POST", "/conversations/chat", token, body)
    return status, sse_frames(payload)


def chat_collect(
    token: str, body: dict, deadline: float = 120.0, sink: list[dict] | None = None
) -> list[dict]:
    """开一条 SSE 但**逐帧**收（停止测试要这条路径）。返回收到的帧（到流结束为止）。

    ★★ `sink` 是本轮修掉的一个真 bug（2026-10-27 run `40518816715` 第 ④ 步三条红）：
      原实现让线程去 `stop_frames.extend(chat_collect(...))`，而 `chat_collect` 是**整条流读完才返回**的
      ⇒ 主线程那 60 秒里看到 `stop_frames` 一直是空的 ⇒ "等到吐出 3 片再停"退化成了"等流结束再停"
      ⇒ 停止请求到的时候这一轮早就 GENERATED 了（`accepted=False`）。读数上表现为
      「停止前已吐出正文（45 字）」= 证据本身，45 字是**全文**，不是 3 片。
      ⇒ 逐帧收必须**边到边写进共享列表**，不能等函数返回。
    """
    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        "Authorization": f"Bearer {token}",
    }
    req = urllib.request.Request(
        API + "/conversations/chat",
        data=json.dumps(body).encode("utf-8"),
        method="POST",
        headers=headers,
    )
    frames: list[dict] = sink if sink is not None else []
    resp = urllib.request.urlopen(req, timeout=deadline)
    try:
        for raw in resp:
            frame = parse_frame(raw.decode("utf-8", errors="replace"))
            if frame is not None:
                frames.append(frame)
    finally:
        resp.close()
    return frames


# ──────────────────────────────── 夹具 ────────────────────────────────


def ensure_chat_model(token: str) -> tuple[str, bool]:
    """取一个**已加载**的对话模型；已加载的没有就加载一个，一个都没有就建一个。

    返回 `(model_id, created)` —— `created=True` 时 finally 里要删掉。

    ★ 为什么必须自己兜这一层（2026-10-27 首次跑挂在这里，日志逐字「没有已加载的对话模型」）：
      `ci.yml` 里**没有任何 seed 步骤**，真库启动时一个已加载的 CHAT 模型都没有；
      而没加载的模型发不出流式响应，于是"第 1 轮提问 500"会被误读成"流式坏了"。
      造法与 `check_query_understanding_e2e.py:157-177` 一致（`stub-plain` + 桩的 `apiBase`），
      `POST /models/{id}/load` 是异步的，所以这里轮询到 LOADED 才算就绪（不固定 sleep）。
    """
    _, payload = call("GET", "/models?kind=CHAT&size=50", token)
    rows = data_of(payload).get("list") or []
    for row in rows:
        if row.get("status") == "LOADED":
            return str(row["id"]), False

    if rows:
        model_id, created = str(rows[0]["id"]), False
    else:
        status, payload = call(
            "POST",
            "/models",
            token,
            {
                "name": f"S3整链-对话-{int(time.time())}",
                "kind": "CHAT",
                "supplier": "LOCAL_INFERENCE",
                "baseModel": "stub-plain",
                "apiBase": STUB_API_BASE,
            },
        )
        if status not in (200, 201):
            raise SystemExit(f"建对话模型失败（{status}）：{payload[:300]}")
        model_id, created = str(data_of(payload).get("id") or ""), True
        if not model_id:
            raise SystemExit(f"建对话模型没返回 id：{payload[:300]}")

    status, loaded = call("POST", f"/models/{model_id}/load", token, {})
    if status not in (200, 202):
        raise SystemExit(f"加载对话模型失败（{status}）：{loaded[:300]}")
    deadline = time.time() + 120
    while time.time() < deadline:
        _, payload = call("GET", "/models?kind=CHAT&size=50", token)
        for row in data_of(payload).get("list") or []:
            if str(row.get("id")) == model_id and row.get("status") == "LOADED":
                return model_id, created
        time.sleep(0.5)
    raise SystemExit(f"对话模型 {model_id} 在 120s 内没变成 LOADED")


def ensure_embedding_model(token: str) -> tuple[str, bool]:
    """取一个可用的向量模型；没有就建一个（返回值第二项 = 是不是我建的，删的时候要用）。"""
    _, payload = call("GET", "/models?kind=EMBEDDING&size=50", token)
    for row in data_of(payload).get("list") or []:
        if row.get("status") in ("LOADED", "UNLOADED", "LOADING", None):
            return str(row["id"]), False
    status, payload = call(
        "POST",
        "/models",
        token,
        {
            "name": f"S3整链-向量-{int(time.time())}",
            "kind": "EMBEDDING",
            "supplier": "LOCAL_INFERENCE",
            "baseModel": "bge-m3",
            "dimension": 768,
        },
    )
    if status not in (200, 201):
        raise SystemExit(f"建向量模型失败（{status}）：{payload[:300]}")
    return str(data_of(payload).get("id") or ""), True


def upload_text(token: str, kb_id: str, path: str) -> str:
    """multipart 上传一个 .md（手写 boundary —— 标准库里没有现成的 multipart 编码）。"""
    boundary = "----s3chain" + str(int(time.time() * 1000))
    head = b""
    for key, value in (
        ("docType", DOC_TYPE),
        ("orgId", ORG_ROOT),
        ("classification", DOC_CLASSIFICATION),
    ):
        head += (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode("utf-8")
        )
    with open(path, "rb") as handle:
        content = handle.read()
    body = (
        head
        + (
            f"--{boundary}\r\n"
            f'Content-Disposition: form-data; name="files"; filename="{os.path.basename(path)}"\r\n'
            "Content-Type: text/markdown\r\n\r\n"
        ).encode("utf-8")
        + content
        + b"\r\n"
        + f"--{boundary}--\r\n".encode("utf-8")
    )
    req = urllib.request.Request(
        API + f"/knowledge-bases/{kb_id}/documents",
        data=body,
        method="POST",
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": f"Bearer {token}",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            payload = resp.read().decode("utf-8", errors="replace")
            rows = (json.loads(payload) or {}).get("data") or []
            if isinstance(rows, dict):
                rows = rows.get("list") or []
            if not rows:
                raise SystemExit(f"上传没返回文档：{payload[:200]}")
            return str(rows[0]["id"])
    except urllib.error.HTTPError as e:
        raise SystemExit(f"上传失败（{e.code}）：{e.read().decode('utf-8', 'replace')[:300]}") from e


def wait_document(token: str, kb_id: str, doc_id: str, wanted: set[str], seconds: float) -> dict:
    """轮询文档状态直到落进 `wanted`（★ 不用固定 sleep：解析快慢随机器变）。"""
    deadline = time.time() + seconds
    last: dict = {}
    while time.time() < deadline:
        _, payload = call("GET", f"/knowledge-bases/{kb_id}/documents?page=1&size=50", token)
        for row in data_of(payload).get("list") or []:
            if str(row.get("id")) == doc_id:
                last = row
                if row.get("status") in wanted:
                    return row
        time.sleep(0.2)
    raise SystemExit(f"文档 {doc_id} 在 {seconds}s 内没到 {wanted}（最后状态 {last.get('status')}）")


def build_kb(token: str, stamp: str, workdir: str) -> tuple[str, str, str, bool]:
    """自造一条"能引用的内容"：向量模型 + 空库 + 一份真文档 + 向量化。

    返回 `(kb_id, doc_id, model_id, created_model)`。
    """
    model_id, created_model = ensure_embedding_model(token)
    status, payload = call(
        "POST",
        "/knowledge-bases",
        token,
        {
            "name": f"S3整链-库-{stamp}",
            "category": KB_CATEGORY,
            "description": "S3 整链闸门的临时知识库（跑完即删）",
            "importType": "LOCAL",
            "embeddingModelId": model_id,
            "maxClassification": "SECRET",
        },
    )
    if status not in (200, 201):
        raise SystemExit(f"建知识库失败（{status}）：{payload[:300]}")
    kb_id = str(data_of(payload).get("id") or "")

    path = os.path.join(workdir, "s3chain.md")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(FIXTURE_TEXT)
    doc_id = upload_text(token, kb_id, path)
    # ★ 解析落地即可：向量化是**用户显式动作**，后端不会自动推进。
    doc = wait_document(token, kb_id, doc_id, {"PENDING_EMBEDDING", "SEGMENT_FAILED"}, 180)
    if doc.get("status") != "PENDING_EMBEDDING":
        raise SystemExit(f"夹具文档解析失败（{doc.get('status')}）：{doc.get('failReason')}")
    call("POST", f"/documents/{doc_id}/embedding", token, {})
    doc = wait_document(token, kb_id, doc_id, {"SUCCEEDED", "EMBEDDING_FAILED"}, 180)
    if doc.get("status") != "SUCCEEDED":
        raise SystemExit(f"夹具文档向量化失败（{doc.get('status')}）：{doc.get('failReason')}")
    return kb_id, doc_id, model_id, created_model


def create_assistant(token: str, model_id: str, kb_id: str, stamp: str) -> str:
    status, payload = call(
        "POST",
        "/assistants",
        token,
        {
            "name": f"S3 整链闸门-{stamp}",
            "icon": "💬",
            "description": "S3 整链闸门用的临时应用（跑完即删）",
            "type": "SIMPLE",
            "modelId": model_id,
            "kbIds": [kb_id],
            "rolePrompt": "你是知识助理，请基于参考资料回答。",
            "promptIds": [],
            "retrieval": {"mode": "HYBRID", "topK": 5, "threshold": 0},
            "multiTurn": True,
            "thinkingEnabled": False,
            "prologue": "",
            "quickQuestions": [],
        },
    )
    if status not in (200, 201):
        raise SystemExit(f"建应用失败（{status}）：{payload[:300]}")
    return str(data_of(payload).get("id") or "")


def create_conversation(token: str, app_id: str) -> str:
    """显式建会话。

    ★ 多轮与停止都要 `conversationId`，而 `meta` 帧**不给**它（只给 messageId）。
      不显式建就只能建完再按 appId 回查列表，那是第二处真相源、还带排序假设。
    """
    status, payload = call("POST", "/conversations", token, {"appId": app_id})
    if status in (200, 201):
        cid = str(data_of(payload).get("id") or "")
        if cid:
            return cid
    # 回落：老后端若不支持显式建会话，就按 appId 取最近一条（★ 如实记一笔，别装作走的是主路径）
    note(f"POST /conversations 返回 {status} ⇒ 回落到「按 appId 取最近一条」建会话")
    _, listing = call("GET", f"/conversations?appId={app_id}&page=1&size=5", token)
    rows = data_of(listing).get("list") or []
    if not rows:
        raise SystemExit("建会话失败：没有可复用的会话")
    return str(rows[0]["id"])


def list_messages(token: str, conversation_id: str) -> list[dict]:
    status, payload = call("GET", f"/conversations/{conversation_id}/messages?page=1&size=100", token)
    if status != 200:
        raise SystemExit(f"读消息列表失败（{status}）：{payload[:200]}")
    return data_of(payload).get("list") or []


# ──────────────────────────────── 主链 ────────────────────────────────


def main() -> int:
    print("== S3 真栈闸门：终端用户那一整条链（流式 · 引用溯源 · 5 轮 · 停止 · 重生成 · 赞 · 评分）==")

    status, payload = call("POST", "/auth/login", body={"username": USER, "password": PASSWORD})
    if status != 200:
        raise SystemExit(f"登录失败（{status}）：{payload[:200]}")
    token = str(data_of(payload).get("accessToken") or "")
    if not token:
        raise SystemExit("登录成功但没拿到 accessToken")
    print("  已登录（admin）")

    model_id, created_chat = ensure_chat_model(token)
    print(f"  夹具：对话模型 {model_id}（{'本闸门新建并加载' if created_chat else '复用已加载的'}）")
    stamp = str(int(time.time()))
    workdir = tempfile.mkdtemp(prefix="s3chain-")
    kb_id, doc_id, embedding_model_id, created_model = build_kb(token, stamp, workdir)
    print(f"  夹具：知识库 {kb_id} · 文档 {doc_id}（已向量化）")
    app_id = create_assistant(token, model_id, kb_id, stamp)
    conversation_id = create_conversation(token, app_id)
    print(f"  夹具：应用 {app_id} · 会话 {conversation_id}")

    chunk_delay_injected = False
    try:
        # ── ① 流式作答 + ② 引用先于正文、且能溯源 ──
        print("== ① 第 1 轮：流式作答 + 引用可溯源 ==")
        status, frames = chat(token, {"appId": app_id, "conversationId": conversation_id, "question": QUESTION})
        if not check(status == 200, f"第 1 轮提问 ⇒ 200 + SSE（实得 {status}）"):
            print(f"  [诊断] {json.dumps(frames)[:300]}")
            return 1
        meta = meta_of(frames)
        answer_1 = str(meta.get("messageId") or "")
        check(bool(answer_1), f"meta 帧给出本轮消息 id（实得 {answer_1}）")
        body_1 = text_of(frames)
        check(bool(body_1), f"正文是**流式分片**到达（拼出 {len(body_1)} 字）")
        done_1 = done_of(frames)
        check(done_1.get("status") == "GENERATED", f"终态 GENERATED（实得 {done_1.get('status')}）")

        refs = refs_of(frames)
        check(bool(refs), f"带引用（{len(refs)} 条）")
        i_refs, i_text = first_refs_index(frames), first_text_index(frames)
        # ★ 契约（M4b）：refs 必须先于第一个正文帧 —— 否则用户会先读到答案、再看到"其实有出处"，
        #   "引用先出现"这条交互承诺就落空了。
        check(
            i_refs >= 0 and i_text >= 0 and i_refs < i_text,
            f"引用帧先于第一个正文帧（refs@{i_refs} < text@{i_text}）",
        )
        seg_id = str((refs[0] or {}).get("segmentId") or "") if refs else ""
        check(bool(seg_id), "引用里带 segmentId（可展开溯源的前提）")
        if seg_id:
            seg_status, seg_payload = call("GET", f"/segments/{seg_id}", token)
            seg = data_of(seg_payload)
            check(seg_status == 200 and bool(seg.get("content")), f"按引用回读分段内容（{seg_status}）")
            check(
                str(seg.get("documentId") or "") != "",
                f"分段挂得住文档（documentId={seg.get('documentId')}）",
            )

        # ── ③ 连续追问 5 轮 ──
        print("== ③ 连续追问：同一会话里累积 5 轮 ==")
        rounds = 1
        for turn in range(2, 6):
            q = f"{FOLLOW_UP}（第 {turn} 轮）"
            status, frames = chat(
                token, {"appId": app_id, "conversationId": conversation_id, "question": q}
            )
            ok_round = status == 200 and done_of(frames).get("status") == "GENERATED"
            check(ok_round, f"第 {turn} 轮 ⇒ 200 + GENERATED（实得 {status} / {done_of(frames).get('status')}）")
            rounds += 1
        msgs = list_messages(token, conversation_id)
        users = [m for m in msgs if m.get("role") == "USER"]
        answers = [m for m in msgs if m.get("role") == "ASSISTANT"]
        check(
            len(users) == 5 and len(answers) == 5,
            f"5 问 5 答都落在一个会话里（用户 {len(users)} 条 · 助手 {len(answers)} 条）",
        )
        check(
            all(m.get("status") == "GENERATED" for m in answers),
            "所有助手消息都是 GENERATED（没有卡在 GENERATING 的）",
        )
        _, conv_payload = call("GET", f"/conversations/{conversation_id}", token)
        conv = data_of(conv_payload)
        # ★ 追问不是"再开一个会话"：会话的轮数必须真的长起来。★ 判据用 >= 5 而不是 == 5，
        #   因为 turnCount 是否把"停止/重生成"那几轮也算进去属于实现口径，本闸门不锁死它。
        check(
            int(conv.get("turnCount") or 0) >= rounds,
            f"会话轮数 ≥ {rounds}（实得 {conv.get('turnCount')}）",
        )
        ordered = [m.get("role") for m in msgs]
        check(
            ordered[:2] == ["USER", "ASSISTANT"],
            f"消息按时间正序（首两条 {ordered[:2]}）",
        )

        # ── ④ 生成中停止：保留已生成内容 ──
        print("== ④ 生成中停止：终态 STOPPED + 已生成的字留住 ==")
        chunk_delay_injected = stub_config({"chunk_delay_ms": CHUNK_DELAY_MS, "delay_ms": 0})
        if chunk_delay_injected:
            note(f"已向桩注入 chunk_delay_ms={CHUNK_DELAY_MS}（否则整段正文一次到齐，没有中间态可停）")
        else:
            note("桩不接受 chunk_delay_ms ⇒ 停止只能停在零字或全字，本轮只断终态与不回 error")

        stop_frames: list[dict] = []
        stop_error: list[str] = []

        def reader() -> None:
            try:
                # ★ 帧写进共享列表（`sink=`），主线程才可能"边吐边看"—— 见 `chat_collect` 的说明
                chat_collect(
                    token,
                    {"appId": app_id, "conversationId": conversation_id, "question": "请详细说明报销流程"},
                    sink=stop_frames,
                )
            except Exception as e:  # noqa: BLE001
                stop_error.append(str(e))

        worker = threading.Thread(target=reader, daemon=True)
        worker.start()
        # 等到"已经吐出几个字"再停 —— 这是本步的全部意义所在
        deadline = time.time() + 60
        while time.time() < deadline:
            if len([f for f in stop_frames if "text" in f]) >= 3:
                break
            if stop_error:
                break
            time.sleep(0.05)

        meta_stop = meta_of(stop_frames)
        stop_message_id = str(meta_stop.get("messageId") or "")
        sent_before = text_of(stop_frames)
        check(bool(stop_message_id), f"停止前已拿到 messageId（实得 {stop_message_id}）")
        check(bool(sent_before), f"停止前已吐出正文（{len(sent_before)} 字）")

        stop_status, stop_payload = call(
            "POST",
            f"/conversations/{conversation_id}/messages/{stop_message_id}/stop",
            token,
        )
        stop_receipt = data_of(stop_payload)
        check(stop_status == 200, f"停止请求被接住（{stop_status}）")
        check(
            stop_receipt.get("accepted") is True,
            f"服务端收下了停止信号（accepted={stop_receipt.get('accepted')}）",
        )
        worker.join(timeout=120)
        check(not stop_error, f"流正常收尾（异常：{stop_error[:1]}）")
        done_stop = done_of(stop_frames)
        check(done_stop.get("status") == "STOPPED", f"终态 STOPPED（实得 {done_stop.get('status')}）")
        # ★ 用户主动停止**不是故障**：契约明确"不发 error"。发了 error 就等于把用户意图记成一次失败，
        #   而 A3（失败可见）的判据会因此永远混进噪声。
        check(not error_frames(stop_frames), f"停止不产生 error 帧（实得 {len(error_frames(stop_frames))} 帧）")

        sent_final = text_of(stop_frames)
        msgs_after_stop = list_messages(token, conversation_id)
        stopped_msg = next((m for m in msgs_after_stop if m.get("id") == stop_message_id), {})
        check(bool(stopped_msg), "停止后的消息能在列表里读到")
        check(
            stopped_msg.get("status") == "STOPPED",
            f"库里记的是 STOPPED（实得 {stopped_msg.get('status')}）—— 不是 FAILED、也不是 GENERATED",
        )
        check(
            bool(sent_final) and str(stopped_msg.get("content") or "").startswith(sent_final),
            f"已生成的内容留住了（库里 {len(str(stopped_msg.get('content') or ''))} 字 ≥ 已收到 {len(sent_final)} 字）",
        )
        if chunk_delay_injected:
            # ★ 这里**不再加一条"停在中间"的断言**：`accepted=True` 本身就是那条判据 ——
            #   服务端只有当消息仍是 GENERATING 时才置信号（`ChatStopService` 的 accepted 语义），
            #   而"仍是 GENERATING"= 这一轮还在吐。再写一句 `len > 0` 是同义反复，只会让绿看起来更多。
            note(f"停止时已生成 {len(sent_final)} 字 · 库里 {len(str(stopped_msg.get('content') or ''))} 字")

        # ★ 复位：后面的闸门（以及本脚本后面的步骤）共用这个桩
        if chunk_delay_injected:
            stub_config({"chunk_delay_ms": 0})
            chunk_delay_injected = False

        # ── ⑤ 重新生成：覆盖同一条 ──
        print("== ⑤ 重新生成：覆盖同一条消息 ==")
        status, regen_frames = chat(
            token,
            {
                "appId": app_id,
                "conversationId": conversation_id,
                "question": "",
                "mode": "REGENERATE",
                "targetMessageId": answer_1,
            },
        )
        check(status == 200, f"重新生成 ⇒ 200 + SSE（实得 {status}）")
        regen_meta = meta_of(regen_frames)
        check(
            str(regen_meta.get("messageId") or "") == answer_1,
            f"覆盖的是同一条消息（期望 {answer_1}，实得 {regen_meta.get('messageId')}）",
        )
        check(bool(refs_of(regen_frames)), "重新生成会重新检索 ⇒ 带引用")

        # ── ⑥ 赞 / 取消赞 / 踩：刷新后仍在 ──
        print("== ⑥ 赞与取消赞：写进去、重新拉回来还在 ==")
        target = answer_1
        up_status, up_payload = call("POST", f"/messages/{target}/feedback", token, {"type": "UP"})
        check(up_status == 200, f"点赞 ⇒ 200（实得 {up_status}）")
        check(data_of(up_payload).get("feedback") == "UP", f"回执里是 UP（{data_of(up_payload).get('feedback')}）")
        refreshed = next((m for m in list_messages(token, conversation_id) if m.get("id") == target), {})
        check(refreshed.get("feedback") == "UP", f"重新拉消息仍是 UP（{refreshed.get('feedback')}）")

        down_status, down_payload = call("POST", f"/messages/{target}/feedback", token, {"type": "DOWN"})
        check(
            down_status == 200 and data_of(down_payload).get("feedback") == "DOWN",
            f"改踩 ⇒ 互斥覆盖成 DOWN（{down_status} / {data_of(down_payload).get('feedback')}）",
        )
        none_status, none_payload = call("POST", f"/messages/{target}/feedback", token, {"type": None})
        check(none_status == 200, f"取消赞 ⇒ 200（实得 {none_status}）")
        refreshed = next((m for m in list_messages(token, conversation_id) if m.get("id") == target), {})
        check(refreshed.get("feedback") is None, f"取消后重新拉消息是 None（{refreshed.get('feedback')}）")

        # ── ⑦ 会话评分：再提交是覆盖 ──
        print("== ⑦ 会话评分：同一会话再提交是覆盖，不是追加 ==")
        first_status, first_payload = call(
            "POST", f"/conversations/{conversation_id}/feedback", token, {"score": 5, "comment": "闸门写的评价"}
        )
        first = data_of(first_payload)
        check(first_status == 200, f"首次评分 ⇒ 200（实得 {first_status}）")
        check(first.get("score") == 5, f"回执分数 5（{first.get('score')}）")
        second_status, second_payload = call(
            "POST", f"/conversations/{conversation_id}/feedback", token, {"score": 3, "comment": "改一次"}
        )
        second = data_of(second_payload)
        check(second_status == 200 and second.get("score") == 3, f"再次评分 ⇒ 覆盖为 3（{second.get('score')}）")
        check(
            first.get("createdAt") == second.get("createdAt"),
            f"覆盖不改 createdAt（{first.get('createdAt')} == {second.get('createdAt')}）",
        )
        check(
            str(second.get("updatedAt")) >= str(first.get("updatedAt")),
            f"覆盖会推 updatedAt（{first.get('updatedAt')} → {second.get('updatedAt')}）",
        )

    finally:
        if chunk_delay_injected:
            stub_config({"chunk_delay_ms": 0})
        # ★ 逐件删：本 job 的闸门顺序跑，留下的应用/会话会污染后面的列表类断言。
        if conversation_id:
            call("DELETE", f"/conversations/{conversation_id}", token)
        # ★ 重生成在后端会**另起一条消息**（若实现退化成 NEW）⇒ 再删一次列表里同应用的全部会话，
        #   让"跑一次就多一堆 S3 会话"这件事不发生在 CI 里。
        if app_id:
            _, listing = call("GET", f"/conversations?appId={app_id}&page=1&size=50", token)
            for row in data_of(listing).get("list") or []:
                call("DELETE", f"/conversations/{row.get('id')}", token)
            call("DELETE", f"/assistants/{app_id}", token)
        # ★ 夹具的删除顺序由后端决定：模型"被知识库/应用引用"时删不掉，所以先删应用（上面）→
        #   再删文档 → 再删库 → 最后删我建的模型。
        if doc_id:
            call("DELETE", f"/documents/{doc_id}", token)
        if kb_id:
            call("DELETE", f"/knowledge-bases/{kb_id}", token)
        if created_model and embedding_model_id:
            call("DELETE", f"/models/{embedding_model_id}", token)
        # ★ 只删**自己建的**对话模型：复用的那个是别人的夹具，删掉会让后面的闸门找不到可用模型。
        if created_chat and model_id and model_id != embedding_model_id:
            call("DELETE", f"/models/{model_id}", token)
        try:
            os.remove(os.path.join(workdir, "s3chain.md"))
        except OSError:
            pass
        try:
            os.rmdir(workdir)
        except OSError:
            pass

    if problems:
        print(f"\n结果：红 —— {len(problems)} 条不成立")
        for item in problems:
            print(f"  [FAIL] {item}")
        return 1
    print("\n结果：绿 —— S3 七件事在同一会话里逐条成立（流式 · 引用溯源 · 5 轮 · 停止保留 · 重生成 · 赞 · 评分）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
