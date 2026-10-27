"""A4 的浏览器侧闸门 —— 「引用能点开、点得到」（回到原句 · 跳转到分段管理 · 落到原文）。

━━━ 为什么必须有一条**浏览器**闸门 ━━━
A4 在 `docs/验收台账.md` 里的原话是「引用能点开回到原文」。这条链的三段各自都有"完成"的痕迹：
`ChatService#retrieve` 把 `segmentId` / `documentId` / `kbId` 装进 `SourceRef`；
`conversation/Index.vue#jumpToSegment` 会把它们拼成 `/knowledge?doc=…&segment=…&kb=…`；
`knowledge/Index.vue` 有参数解析与定位态文案。而 `check_flow_walkthrough.py` 已经证过**接收端**：
直接敲那条 URL 能落到定位态。

缺的恰恰是**中间那一下点击**：从真实会话页里点「跳转到分段管理」/「回到原句」，看会不会真发生什么。
★ 这个缺口是 `#91` 的同族，而且我们真在这里挖到了一个：`RefPanel` 会 `emit('back')`、
`AnswerBody` 也 `defineExpose({ scrollToCite })`，但**页面没监听 `back`**、`MessageList` 也从未把
调用转给 `AnswerBody` ⇒ 「回到原句」点下去什么都不发生、也不报错。静态闸门
（`check_design.py`）只能断"这个按钮必须存在"，而"存在"与"有反应"是两件事。

━━━ 判据六条 ━━━
① **服务端：引用带齐深链三元组**（`segmentId` / `documentId` / `kbId`）—— 缺 `kbId` 时界面会
   把知识库引用当成"会话域引用"（那条结构性判据是 `!ref.kbId`），于是「跳转到分段管理」按钮直接消失；
   缺 `documentId` 时深链少一格，接收端要靠反查补，那是另一条路径、也要单独读数。
② **服务端：按引用能回读原文**：`GET /segments/{segmentId}` 200、有 `content`、且 `documentId` 与
   引用上的那个一致 —— 否则"跳过去定位到的是别的文档"也不会报错。
③ **界面：引用面板按来源给标签**（知识库引用 = 「知识库：<库名>」而不是「来源：」）＋ 按钮在。
④ **界面：「回到原句」不是装饰**：点它之后那张引用卡进入 active 态，且正文里的 `[N]` 角标被点亮
   （`cite-flash`）。★ 角标这一半依赖回答正文里**真的有** `[N]`（桩现在会标，见
   `backend/tools/openai_stub.py#_answer_text`）；没有角标时这里记**注记**而不是判红 ——
   那说明"模型没标出处"，是另一条账，不是"按钮没接上"。
⑤ **界面：「跳转到分段管理」真的到位置**：hash 变成 `#/knowledge` 且 query 带 `doc` / `segment` / `kb`，
   页面出现「已按引用定位到分段」（或「无法定位」的可读提示 —— `#91` 的判据原文：静默跳首页不算）。
⑥ **控制台无错误**：这条会把"参数名对不上、页面炸了但看着像空态"这类问题揪出来。

★ 用真栈 + 真浏览器（Playwright/chromium）。用法：
    python check_refs_jump_e2e.py                 # 默认 http://localhost:5280
    python check_refs_jump_e2e.py http://127.0.0.1:5280
退出码：0 = 全绿；1 = 有红。
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

#: 上游桩（真栈里 `openai_stub.py` 跑在 11434）。新建对话模型时写进 `apiBase`。
STUB = (os.environ.get("AIP_STUB_URL") or "http://127.0.0.1:11434").rstrip("/")
STUB_API_BASE = STUB + "/v1"

#: 演示账号（真栈夹具）。
USER = "admin"
PASSWORD = "admin"

SOFTWARE_NAME = "A4 引用可跳转闸门"
ORG_ROOT = "00000000-0000-4000-8000-000000000001"
DOC_TYPE = "制度"
DOC_CLASSIFICATION = "INTERNAL"
KB_CATEGORY = "制度规范"

#: ★ 问句必须与库里的实际内容对得上：问一个库里没有的词，"0 条引用"会被误读成"检索坏了"。
QUESTION = "报销标准"

#: 夹具正文（与 S1/S2/S3 同文，已被真栈证过"能召回 1 条引用"）。
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

problems: list[str] = []


def check(ok: bool, message: str) -> bool:
    print(f"  {'✓' if ok else '✗'} {message}")
    if not ok:
        problems.append(message)
    return ok


def note(message: str) -> None:
    """只记读数、不判红（用它的时候要在注释里写清为什么这一条不该判红）。"""
    print(f"  [注记] {message}")


def call(
    method: str,
    path: str,
    token: str | None = None,
    body: dict | None = None,
    raw: bool = False,
) -> tuple[int, str]:
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(API + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except urllib.error.URLError as e:
        raise SystemExit(f"连不上 {API}：{e}（先起真栈：后端 8080 + 前端 5280 + 桩 11434）") from e


def data_of(payload: str) -> dict:
    try:
        return json.loads(payload).get("data") or {}
    except (json.JSONDecodeError, AttributeError):
        return {}


def stub_call(method: str, path: str) -> tuple[int, str]:
    req = urllib.request.Request(STUB + path, data=b"{}" if method == "POST" else None, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except urllib.error.URLError as e:
        return 0, str(e)


def login() -> str:
    status, payload = call("POST", "/auth/login", body={"username": USER, "password": PASSWORD})
    if status != 200:
        raise SystemExit(f"登录失败（{status}）：{payload[:300]}")
    token = (data_of(payload) or {}).get("accessToken") or (data_of(payload) or {}).get("token")
    if not token:
        raise SystemExit(f"登录没返回令牌：{payload[:300]}")
    return token


def ensure_chat_model(token: str) -> tuple[str, bool]:
    """取一个**已加载**的对话模型；没有就建一个再加载。返回 `(id, created)`。"""
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
                "name": f"{SOFTWARE_NAME}-对话-{int(time.time())}",
                "kind": "CHAT",
                "supplier": "LOCAL_INFERENCE",
                "baseModel": "stub-plain",
                "apiBase": STUB_API_BASE,
            },
        )
        if status not in (200, 201):
            raise SystemExit(f"建对话模型失败（{status}）：{payload[:300]}")
        model_id, created = str(data_of(payload).get("id") or ""), True
    status, payload = call("POST", f"/models/{model_id}/load", token, {})
    if status not in (200, 202):
        raise SystemExit(f"加载对话模型失败（{status}）：{payload[:300]}")
    deadline = time.time() + 120
    while time.time() < deadline:
        _, payload = call("GET", "/models?kind=CHAT&size=50", token)
        for row in data_of(payload).get("list") or []:
            if str(row.get("id")) == model_id and row.get("status") == "LOADED":
                return model_id, created
        time.sleep(0.5)
    raise SystemExit(f"对话模型 {model_id} 在 120s 内没变成 LOADED")


def ensure_embedding_model(token: str) -> tuple[str, bool]:
    _, payload = call("GET", "/models?kind=EMBEDDING&size=50", token)
    for row in data_of(payload).get("list") or []:
        return str(row["id"]), False
    status, payload = call(
        "POST",
        "/models",
        token,
        {
            "name": f"{SOFTWARE_NAME}-向量-{int(time.time())}",
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
    boundary = "----a4refs" + str(int(time.time() * 1000))
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
    except urllib.error.HTTPError as e:
        raise SystemExit(f"上传失败（{e.code}）：{e.read().decode('utf-8', 'replace')[:300]}") from e
    rows = data_of(payload)
    rows = rows.get("list") if isinstance(rows, dict) else rows
    if not rows:
        raise SystemExit(f"上传没返回文档：{payload[:200]}")
    return str(rows[0]["id"])


def wait_document(token: str, kb_id: str, doc_id: str, wanted: set[str], seconds: float) -> dict:
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


def build_kb(token: str, workdir: str) -> tuple[str, str, str, bool]:
    """自造「能引用的内容」：向量模型 + 空库 + 一份真文档 + 向量化。"""
    model_id, created_model = ensure_embedding_model(token)
    status, payload = call(
        "POST",
        "/knowledge-bases",
        token,
        {
            "name": f"{SOFTWARE_NAME}-库-{int(time.time())}",
            "category": KB_CATEGORY,
            "description": "A4 引用跳转闸门的临时知识库（跑完即删）",
            "importType": "LOCAL",
            "embeddingModelId": model_id,
            "maxClassification": "SECRET",
        },
    )
    if status not in (200, 201):
        raise SystemExit(f"建知识库失败（{status}）：{payload[:300]}")
    kb_id = str(data_of(payload).get("id") or "")
    path = os.path.join(workdir, "a4refs.md")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(FIXTURE_TEXT)
    doc_id = upload_text(token, kb_id, path)
    doc = wait_document(token, kb_id, doc_id, {"PENDING_EMBEDDING", "SEGMENT_FAILED"}, 180)
    if doc.get("status") != "PENDING_EMBEDDING":
        raise SystemExit(f"夹具文档解析失败（{doc.get('status')}）：{doc.get('failReason')}")
    call("POST", f"/documents/{doc_id}/embedding", token, {})
    doc = wait_document(token, kb_id, doc_id, {"SUCCEEDED", "EMBEDDING_FAILED"}, 180)
    if doc.get("status") != "SUCCEEDED":
        raise SystemExit(f"夹具文档向量化失败（{doc.get('status')}）：{doc.get('failReason')}")
    return kb_id, doc_id, model_id, created_model


def create_assistant(token: str, model_id: str, kb_id: str) -> str:
    status, payload = call(
        "POST",
        "/assistants",
        token,
        {
            "name": f"{SOFTWARE_NAME}-应用-{int(time.time())}",
            "icon": "🔗",
            "description": "A4 引用跳转闸门（跑完即删）",
            "type": "SIMPLE",
            "modelId": model_id,
            "kbIds": [kb_id],
            "rolePrompt": "你是知识助理，请基于参考资料回答，并在句末标注出处。",
            "promptIds": [],
            "retrieval": {"mode": "HYBRID", "topK": 5, "threshold": 0},
            "multiTurn": True,
            "thinkingEnabled": False,
            "prologue": "",
            "quickQuestions": [],
        },
    )
    if status not in (200, 201):
        raise SystemExit(f"建应用失败（{status}）：{payload[:400]}")
    return str(data_of(payload).get("id") or "")


def create_conversation(token: str, app_id: str) -> str:
    status, payload = call("POST", "/conversations", token, {"appId": app_id})
    if status in (200, 201):
        conv_id = str(data_of(payload).get("id") or "")
        if conv_id:
            return conv_id
    # ★ 回落：有的版本要求先有问答才建；列出同应用最近一条（并如实记注记）。
    _, payload = call("GET", f"/conversations?appId={app_id}&size=5", token)
    rows = data_of(payload).get("list") or []
    if rows:
        note(f"建会话没拿到 id（{status}），回落用最近一条 {rows[0].get('id')}")
        return str(rows[0]["id"])
    raise SystemExit(f"建会话失败（{status}）：{payload[:300]}")


def sse_frames(payload: str) -> list[dict]:
    """把 SSE 文本拆成帧。★ 帧的类型就是**键名**（`refs` / `text` / `status`），没有 `event` 字段。"""
    frames: list[dict] = []
    for line in payload.splitlines():
        line = line.strip()
        if not line.startswith("data:"):
            continue
        body = line[len("data:") :].strip()
        if not body or body == "[DONE]":
            continue
        try:
            frame = json.loads(body)
        except json.JSONDecodeError:
            continue
        if isinstance(frame, dict):
            frames.append(frame)
    return frames


def refs_of(frames: list[dict]) -> list[dict]:
    for frame in frames:
        if "refs" in frame and isinstance(frame["refs"], list):
            return frame["refs"]
    return []


def meta_of(frames: list[dict]) -> dict:
    for frame in frames:
        if "messageId" in frame and "refs" not in frame and "text" not in frame:
            return frame
    return {}


def main() -> int:
    print("== A4 真栈闸门：引用能点开、点得到（回到原句 · 跳转到分段管理 · 落到原文）==")
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        raise SystemExit("缺依赖：pip install playwright && python -m playwright install chromium")

    token = login()
    print("  已登录（admin）")

    workdir = tempfile.mkdtemp(prefix="a4refs-")
    model_id, created_chat_model = ensure_chat_model(token)
    kb_id, doc_id, embedding_model_id, created_embedding_model = build_kb(token, workdir)
    app_id = create_assistant(token, model_id, kb_id)
    print(f"  夹具：应用 {app_id} · 知识库 {kb_id} · 文档 {doc_id} · 模型 {model_id}")

    conv_id = ""
    answer_id = ""
    refs: list[dict] = []
    try:
        conv_id = create_conversation(token, app_id)
        stub_call("POST", "/__stub/reset")
        status, payload = call(
            "POST",
            "/conversations/chat",
            token,
            {"appId": app_id, "conversationId": conv_id, "question": QUESTION},
            raw=True,
        )
        check(status == 200, f"提问一次拿到答案（{status}）{'' if status == 200 else '：' + payload[:200]}")
        if status != 200:
            return 1
        frames = sse_frames(payload)
        meta = meta_of(frames)
        answer_id = str(meta.get("messageId") or "")
        refs = refs_of(frames)
        check(bool(answer_id), f"答案有 messageId（{answer_id or '空'}）")
        check(bool(refs), f"答案带引用（{len(refs)} 条）")
        text = "".join(str(f.get("text") or "") for f in frames)
        check("[" in text and "]" in text, f"答案正文里带 `[N]` 角标（{'有' if '[' in text else '无'}）{'' if '[' in text else ' —— 没有角标就没有『回到原句』可落'}")

        # ── ① 服务端：引用带齐深链三元组 ──
        print("\n== ① 服务端：引用的三个 id ==")
        first = refs[0] if refs else {}
        seg_id = str(first.get("segmentId") or "")
        doc_ref = str(first.get("documentId") or "")
        kb_ref = str(first.get("kbId") or "")
        check(bool(seg_id), f"引用带 segmentId（{seg_id or '空'}）")
        check(
            bool(doc_ref),
            f"引用带 documentId（实得 {doc_ref or '空'}）"
            " —— 缺它时深链少一格，接收端只能靠反查补",
        )
        check(
            bool(kb_ref),
            f"引用带 kbId（实得 {kb_ref or '空'}）"
            " —— ★ 缺它时界面把知识库引用判成『会话域』（结构性判据是 !ref.kbId），"
            "『跳转到分段管理』按钮会直接消失",
        )
        check(
            kb_ref == kb_id if kb_ref else False,
            f"引用的 kbId 就是夹具库（期望 {kb_id}）",
        )

        # ── ② 服务端：按引用能回读原文，且指向同一篇文档 ──
        print("\n== ② 服务端：按引用回读原文 ==")
        if seg_id:
            status, payload = call("GET", f"/segments/{seg_id}", token)
            seg = data_of(payload)
            check(status == 200, f"GET /segments/{seg_id} ⇒ 200（实得 {status}）")
            check(bool(seg.get("content")), "回读的分段有正文")
            check(
                str(seg.get("documentId") or "") == doc_ref if doc_ref else False,
                f"回读的分段属于引用指向的那篇文档（分段 documentId={seg.get('documentId')} · 引用 {doc_ref or '空'}）",
            )

        # ── ③④⑤⑥ 浏览器 ──
        deep = f"{BASE}/#/conversation?appId={app_id}&conversationId={conv_id}&messageId={answer_id}"
        console_errors: list[str] = []
        with sync_playwright() as pw:
            browser = pw.chromium.launch()
            # ★ 视口必须够宽：`wide` 是 `min-width: 1100`，窄屏走抽屉分支（另一条 DOM）。
            page = browser.new_page(viewport={"width": 1440, "height": 900})
            page.on(
                "console",
                lambda m: (
                    console_errors.append(f"{m.text} @ {m.location.get('url', '')}")
                    if m.type == "error" and "favicon" not in m.location.get("url", "")
                    else None
                ),
            )
            page.on("pageerror", lambda e: console_errors.append(f"pageerror: {e}"))
            try:
                # 先登录（真栈 token 不是 mock_ 前缀）
                page.goto(f"{BASE}/#/login", wait_until="networkidle")
                page.wait_for_timeout(600)
                page.fill("input[autocomplete='username']", USER)
                page.fill("input[autocomplete='current-password']", PASSWORD)
                page.click("button:has-text('登录')")
                page.wait_for_timeout(2500)
                page_token = page.evaluate("() => localStorage.getItem('access_token') || ''")
                check(
                    bool(page_token) and not page_token.startswith("mock_"),
                    "浏览器连的是真后端（token 非 mock_）",
                )

                print("\n== ③ 界面：引用面板与按钮 ==")
                page.goto(deep, wait_until="domcontentloaded")
                # ★ 不用 networkidle：会话页会持续拉历史/消息，等不到 idle。
                page.wait_for_timeout(4000)
                body = page.inner_text("body")
                check("引用来源" in body, "界面上出现了「引用来源」面板")
                labels = page.locator(".ref-side .kb").all_inner_texts()
                check(bool(labels), f"引用卡片有来源标签（{labels[:2]}）")
                check(
                    any(item.strip().startswith("知识库：") for item in labels),
                    "知识库来源的引用标签是「知识库：<库名>」而不是「来源：」"
                    f" —— 实得 {labels[:2]}（后者说明界面把它当会话域，kbId 没到）",
                )
                jump = page.locator(".ref-side button.jump", has_text="跳转到分段管理")
                check(jump.count() > 0, "「跳转到分段管理」按钮在")

                print("\n== ④ 界面：「回到原句」不是装饰 ==")
                back = page.locator(".ref-side button.jump", has_text="回到原句")
                check(back.count() > 0, "「回到原句」按钮在")
                card = page.locator(".ref-side .ref-card").first
                if back.count() > 0:
                    before_active = card.evaluate("(el) => el.classList.contains('active')")
                    back.first.click()
                    page.wait_for_timeout(600)
                    after_active = card.evaluate("(el) => el.classList.contains('active')")
                    check(
                        after_active,
                        "点「回到原句」之后那张引用卡进入 active 态"
                        f"（点前 {before_active} → 点后 {after_active}）"
                        " —— 一直是 false 说明这个按钮没有任何监听（本闸门首跑前就是如此）",
                    )
                    # ★ 角标这一半只在正文真有 `[N]` 时才有意义：没有角标 = "模型没标出处"（另一条账），
                    #   不是"按钮没接上"，所以记注记而不是判红。
                    cite = page.locator(
                        f'[data-message-id="{answer_id}"] .cite[data-ref="1"]'
                    )
                    if cite.count() > 0:
                        cite.first.scroll_into_view_if_needed()
                        back.first.click()
                        try:
                            page.wait_for_selector(
                                f'[data-message-id="{answer_id}"] .cite.cite-flash',
                                timeout=1500,
                            )
                            check(True, "正文里的 `[1]` 角标被点亮（cite-flash）")
                        except Exception:  # noqa: BLE001
                            check(False, "正文里的 `[1]` 角标没被点亮（cite-flash 未出现）")
                    else:
                        note("正文里没有 `.cite` 角标（回答没标出处）⇒ 角标高亮这一条没跑到，不判红")

                    print("\n== ⑤ 界面：「跳转到分段管理」真的到位置 ==")
                    page.click(".ref-side button.jump:has-text('跳转到分段管理')")
                    page.wait_for_timeout(3000)
                    url = page.url
                    check("/knowledge" in url, f"跳到了知识库页（{url.split('#')[-1][:120]}）")
                    query = url.split("?", 1)[-1] if "?" in url else ""
                    for key in ("doc", "segment", "kb"):
                        check(f"{key}=" in query, f"深链 query 带 `{key}`（实得 …{query[-90:]}）")
                    deep_text = page.evaluate("() => document.body.innerText")
                    positioned = "已按引用定位到分段" in deep_text
                    unlocatable = ("无法定位" in deep_text) or ("找不到该分段" in deep_text)
                    check(
                        positioned or unlocatable,
                        "界面上落到了定位态或给了「无法定位」的可读提示"
                        f" —— 实际 positioned={positioned} unlocatable={unlocatable}"
                        "（静默停在列表页 = #91 的根因）",
                    )

                print("\n== ⑥ 控制台 ==")
                check(not console_errors[:3], f"控制台无错误 —— 实际：{console_errors[:3]}")
            finally:
                browser.close()
    finally:
        # 清夹具：会话 → 应用 → 文档 → 库 → 我建的模型（顺序有讲究，后面的被前面引用着就删不掉）。
        cleanup(token, f"/conversations/{conv_id}" if conv_id else "")
        cleanup(token, f"/assistants/{app_id}")
        cleanup(token, f"/documents/{doc_id}")
        cleanup(token, f"/knowledge-bases/{kb_id}")
        if created_embedding_model:
            cleanup(token, f"/models/{embedding_model_id}")
        if created_chat_model:
            cleanup(token, f"/models/{model_id}")
        md_path = os.path.join(workdir, "a4refs.md")
        if os.path.exists(md_path):
            os.remove(md_path)
        os.rmdir(workdir)

    if problems:
        print(f"\n结果：红 —— {len(problems)} 条")
        for item in problems:
            print(f"  [FAIL] {item}")
        return 1
    print("\n结果：绿 —— A4 六条判据全部成立（引用三元组齐全 · 能回读原文 · 标签按来源 · 「回到原句」有反应 · 「跳转到分段管理」落到位置 · 无控制台错误）")
    return 0


def cleanup(token: str, path: str) -> None:
    """删夹具。★ 不判红：删除失败是"垃圾多一条"，不是被测对象坏了（与其它闸门同口径）。"""
    if not path:
        return
    status, payload = call("DELETE", path, token)
    if status not in (200, 202, 204, 404):
        note(f"清理 {path} 失败（{status}）：{payload[:120]}")


if __name__ == "__main__":
    sys.exit(main())
