"""S2 的真栈端到端闸门 —— 「公开链接 → 未登录提问得带引用答案 → 撤销后立刻失效」。

━━━ 为什么必须有一条**真栈**闸门 ━━━
`ROADMAP §2.1` 的 S2 是 MVP 三场景之一，而它此前**只有 mock 侧的读数**：
`check_publish.py` 跑的是**编排画布的发布**（不是公开链接），而"生成公开链接"那条链
（`/assistants/{appId}/credentials`）与"未登录提问"那条链（`POST /conversations/chat` +
`X-Access-Credential` 头）**没有任何一条闸门在 CI 里点过**。

★ 这正是 `#3` 记过的那类风险：**mock 比真后端宽松** ⇒ 假绿灯。
  mock 里"撤销后失效"是一个 if；真栈里它涉及 `PublicTokenResolver` + 冻结密级 + 每次访问实时再滤。
  两者的读数在界面上完全一样。

━━━ 判据五条（缺一条都能骗过）━━━
① **未登录能解析出上下文**：`GET /public/{token}` 不带 Authorization ⇒ 200，且拿到应用名与冻结密级；
② ★★ **未登录能提问，且答案带引用**：`POST /conversations/chat` 只带 `X-Access-Credential`
   ⇒ SSE 里必须有 `refs` 帧，且 refs **非空**（"答了但一条引用都没有"不算 S2 达标，
   而"整条链路坏了"也会让"应有引用"这条变绿 —— 所以两条一起断）；
②b ★★ **应用绑定的提示词正文真的进了上游 prompt**：读桩的 `GET /__stub/requests`，
   请求体里必须出现那条提示词独有的标记（`#215`：此前绑定了却从不参与拼装，
   而 API 回执、界面、库里都只证明"绑了"，只有这里能证明"用了"）；
③ ★★ **撤销后立即失效**：撤销凭证后同一个 token 再解析 ⇒ **必须失败**（不是"还能用一会儿"）；
④ ★ **撤销前的那次成功是必要的对照**：没有它，一条"永远 404"的实现能让 ③ 永远绿。

★ 本闸门**不点浏览器**：S2 要验的是"外部访客这条路通不通"，而访客用的是 HTTP + SSE，
  不是某个页面的某个按钮。★ 用 `urllib` 直打 API，与 `check_classification_e2e.py` 同一条做法
  （走 dev server 的代理 ⇒ 与页面同一条路）。

用法：
    python check_public_share_e2e.py                      # 默认 http://localhost:5280
    python check_public_share_e2e.py http://127.0.0.1:5280
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

# ★ 上游桩（`backend/tools/openai_stub.py`）。判据 ②b 要读**它收到的请求体** ——
#   "提示词绑定了"这件事在界面上、在 API 回执里都看得见，唯独只有这里能回答
#   "它到底有没有进 prompt"。`AIP_STUB_URL` 与其它闸门的习惯一致，便于本地换端口。
STUB = (os.environ.get("AIP_STUB_URL") or "http://127.0.0.1:11434").rstrip("/")

#: ★ 提示词正文里只放这一串标记：它**不在知识库内容里**，所以"上游请求里出现它"
#:   只能来自应用绑定的提示词 —— 否则无法把"提示词生效了"与"文档里刚好有这句话"分开。
PROMPT_MARKER = "S2提示词闸门标记：今年是丙午年"

# 演示账号（与 ROADMAP 的演示一致）。★ 用 admin：它能建应用、能签发凭证。
USER = "admin"
PASSWORD = "admin"

SOFTWARE_NAME = "S2 公开链接闸门"

#: 桩的 OpenAI 兼容根地址：新建对话模型时写进 `apiBase`，否则上游请求无处可去。
STUB_API_BASE = STUB + "/v1"

#: ★ 上传建档的三个必填项（`CreateWizard` 的默认值）：组织根的确定性 UUID + 制度 + 内部密级。
ORG_ROOT = "00000000-0000-4000-8000-000000000001"
DOC_TYPE = "制度"
DOC_CLASSIFICATION = "INTERNAL"
KB_CATEGORY = "制度规范"

#: 夹具正文：必须含 `QUESTION`（报销标准）里的实词，否则"引用为空"会是**夹具**的问题、
#: 而不是检索的问题 —— 两者在读数上长得一模一样。与 `check_s3_conversation_chain_e2e.py` 同文，
#: 那份已被真栈证过"能召回 1 条引用"。
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

# ★ 问句必须与**库里的实际内容**对得上：问一个库里没有的词，"0 条引用"会被误读成
#   "检索坏了"，而它其实是"这句话确实没命中"。`报销标准` 与 `check_classification_e2e.py`
#   用的是同一个词（那份夹具上传的是同一批 fixture）。
QUESTION = "报销标准"

problems: list[str] = []


def check(ok: bool, message: str) -> None:
    if ok:
        print(f"  ✓ {message}")
    else:
        print(f"  ✗ {message}")
        problems.append(message)


def call(
    method: str,
    path: str,
    token: str | None = None,
    body: dict | None = None,
    credential: str | None = None,
    raw: bool = False,
) -> tuple[int, str]:
    """调后端 REST（走 dev server 的代理 ⇒ 与页面同一条路）。

    ★ 三个头各有各的语义，别混：
      · `Authorization: Bearer` = **登录用户**；未登录时**不许**发空值（会让登录端点自己 401）；
      · `X-Access-Credential` = **公开链接访客**（无 JWT，`lib/sse.ts` 的免登录形状）；
      · 两者都不带 = **真正的匿名** —— 判据 ③ 就是要它。
    """
    headers: dict[str, str] = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if credential:
        headers["X-Access-Credential"] = credential
    req = urllib.request.Request(
        API + path,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        method=method,
        headers=headers,
    )
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            payload = resp.read().decode("utf-8", errors="replace")
            return resp.status, payload if raw else payload
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")
    except Exception as e:  # noqa: BLE001
        return -1, str(e)


def login() -> str:
    status, payload = call("POST", "/auth/login", body={"username": USER, "password": PASSWORD})
    if status != 200:
        raise SystemExit(f"登录失败（{status}）：{payload[:200]}")
    return json.loads(payload)["data"]["accessToken"]


def data_of(payload: str) -> dict:
    try:
        return json.loads(payload).get("data") or {}
    except (json.JSONDecodeError, AttributeError):
        return {}


def ensure_chat_model(token: str) -> tuple[str, bool]:
    """取一个**已加载**的对话模型；没有已加载的就建一个再加载。返回 `(id, created)`。

    ★ 为什么要自己兜（2026-10-27 S3 首跑就挂在这一步，日志逐字「没有已加载的对话模型（共 0 个）」）：
      `ci.yml` 里**没有任何 seed 步骤**，真库启动时一个模型都没有。而"没有模型"与
      "模型加载坏了"在读数上长得一样 —— 前者是夹具没搭好，后者才是产品缺陷。
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
                "name": f"{SOFTWARE_NAME}-对话-{int(time.time())}",
                "kind": "CHAT",
                "supplier": "LOCAL_INFERENCE",
                # ★ `stub-plain` + 桩的 `apiBase`：与 `check_query_understanding_e2e.py` 同款造法
                "baseModel": "stub-plain",
                "apiBase": STUB_API_BASE,
            },
        )
        if status not in (200, 201):
            raise SystemExit(f"建对话模型失败（{status}）：{payload[:300]}")
        model_id, created = str(data_of(payload).get("id") or ""), True
        if not model_id:
            raise SystemExit(f"建对话模型没返回 id：{payload[:300]}")
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
    """取一个可用的向量模型；没有就建一个。返回 `(id, created)`。"""
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
    """multipart 上传一个 .md（手写 boundary —— 标准库里没有现成的 multipart 编码）。"""
    boundary = "----s2share" + str(int(time.time() * 1000))
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


def build_kb(token: str, workdir: str) -> tuple[str, str, str, bool]:
    """自造「能引用的内容」：向量模型 + 空库 + 一份真文档 + 向量化。

    ★★ 为什么不"找一个 documentCount>0 的库"（这是本轮改的）：`ci.yml` 的 `gates` 是**顺序**跑的，
      每条闸门在 finally 里删干净自己的夹具 ⇒ "找一个有文档的库"能不能找到，取决于**前面那条**删得干不干净。
      那意味着 S2 的红可能来自别人的清理，而不是 S2 自己 —— 而这两种红在日志里长得一样。
      自造夹具多花几秒（一个模型 + 一个库 + 一份 400 字的文档 + 一次向量化），换来的是"红只可能来自本闸门"。
    """
    model_id, created_model = ensure_embedding_model(token)
    status, payload = call(
        "POST",
        "/knowledge-bases",
        token,
        {
            "name": f"{SOFTWARE_NAME}-库-{int(time.time())}",
            "category": KB_CATEGORY,
            "description": "S2 公开链接闸门的临时知识库（跑完即删）",
            "importType": "LOCAL",
            "embeddingModelId": model_id,
            "maxClassification": "SECRET",
        },
    )
    if status not in (200, 201):
        raise SystemExit(f"建知识库失败（{status}）：{payload[:300]}")
    kb_id = str(data_of(payload).get("id") or "")

    path = os.path.join(workdir, "s2share.md")
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


def cleanup(token: str, path: str) -> None:
    """删夹具：失败只提示、不判红。

    ★ 为什么不判红：清不掉的后果是"下几次多几个应用"，而判红会把它记成**产品缺陷** ——
    两者处置完全不同。但它必须**打印出来**，否则"每跑一次多一个"会静默累积成噪声源。
    """
    status, payload = call("DELETE", path, token)
    if status not in (200, 202, 204):
        print(f"  [注记] 清夹具 {path} 没成功（{status}）：{payload[:160]}")


def create_prompt(token: str) -> tuple[str, str]:
    """建一个「提示词类型 + 提示词」，返回 `(typeId, configId)`。

    ★★ 为什么这一格必须补（`#215`）：S2 此前建应用时 `promptIds: []` —— 一条都没绑，
    于是「应用能带提示词运行」这件事**从来没有被点过**。而补上之后暴露的真缺陷是：
    `ChatService` 当时只读 `rolePrompt`，绑定的提示词正文**根本不参与拼装** ——
    界面显示已选、问答行为一字不变、且不报错（本项目头号病 `silent-failure` 的形状）。

    ★ 先建**新的**类型而不是复用已有那条：闸门要能重复跑，也要能把自己的夹具删干净
    （删类型前必须先删它下面的模板，复用别人的类型会牵连别的数据）。
    """
    stamp = int(time.time())
    status, payload = call(
        "POST",
        "/prompt-types",
        token,
        {"typeName": f"{SOFTWARE_NAME}类型-{stamp}", "icon": "🧩"},
    )
    if status not in (200, 201):
        raise SystemExit(f"建提示词类型失败（{status}）：{payload[:300]}")
    type_id = json.loads(payload)["data"]["id"]

    status, payload = call(
        "POST",
        "/prompt-configs",
        token,
        {
            "roleName": f"{SOFTWARE_NAME}角色-{stamp}",
            "callWord": PROMPT_MARKER,
            "typeId": type_id,
            "description": "S2 闸门用：验证绑定真的进了上游 prompt",
            "icon": "🧩",
        },
    )
    if status not in (200, 201):
        raise SystemExit(f"建提示词失败（{status}）：{payload[:300]}")
    return type_id, json.loads(payload)["data"]["id"]


def stub_call(method: str, path: str) -> tuple[int, str]:
    """直接打上游桩（★ 不经 dev server 代理：`/__stub/*` 不是产品 API）。"""
    req = urllib.request.Request(
        STUB + path, data=b"" if method == "POST" else None, method=method
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as failure:
        return failure.code, failure.read().decode("utf-8", "replace")
    except urllib.error.URLError as failure:
        return 0, f"连不上上游桩（{STUB}）：{failure}"


def chat_requests() -> list[dict]:
    """桩收到的 `/chat/completions` 请求（按到达顺序）。

    ★ 只取 chat：取数链路同时会打 `/embeddings`，把那些混进来会让"没有 chat 请求"
    与"有请求但没提示词"混成一个读数。
    """
    status, payload = stub_call("GET", "/__stub/requests")
    if status != 200:
        return []
    rows = (json.loads(payload).get("requests") or [])
    return [row for row in rows if str(row.get("path") or "").endswith("/chat/completions")]


def create_app(token: str, model_id: str, kb_id: str, prompt_ids: list[str]) -> str:
    """建一个最小应用（S2 的第一步：建应用）。

    ★ 名字带时间戳：闸门要能重复跑，重名会让"按名字找应用"在下一次读到自己上次建的。
    """
    name = f"{SOFTWARE_NAME}-{int(time.time())}"
    status, payload = call(
        "POST",
        "/assistants",
        token,
        {
            # ★ 契约 `UpsertAssistantBody` 里的字段**全是必填**（`api/assistants.ts`）——
            #   第一次 CI 跑就因为漏了 `type` 红：逐字「应用类型必填（SIMPLE / WORKFLOW）」。
            #   ⇒ 按契约填全，而不是"只填看起来必要的几个"。
            "name": name,
            "icon": "📄",
            "description": "S2 公开链接闸门用的临时应用",
            # ★ S2 走的是**问答**链路（不是编排画布）⇒ SIMPLE。
            "type": "SIMPLE",
            "modelId": model_id,
            "kbIds": [kb_id],
            "rolePrompt": "你是知识助理，请基于参考资料回答。",
            # ★★ `#215`：这一格此前恒为 `[]`，于是"绑定 → 生效"从来没有被验过
            "promptIds": prompt_ids,
            "retrieval": {"mode": "HYBRID", "topK": 5, "threshold": 0},
            "multiTurn": True,
            "thinkingEnabled": False,
            "prologue": "",
            "quickQuestions": [],
        },
    )
    if status not in (200, 201):
        raise SystemExit(f"建应用失败（{status}）：{payload[:300]}")
    return json.loads(payload)["data"]["id"]


def issue_credential(token: str, app_id: str) -> tuple[str, str]:
    """签发一个公开凭证。★ `expiresAt: null` = 不设过期（本闸门要验的是**撤销**，不是过期）。"""
    status, payload = call(
        "POST", f"/assistants/{app_id}/credentials", token, {"expiresAt": None}
    )
    if status not in (200, 201):
        raise SystemExit(f"签发凭证失败（{status}）：{payload[:300]}")
    data = json.loads(payload)["data"]
    # ★ 凭证的明文 token 与它的 id 是两样东西：前者给访客用，后者给撤销用
    return data["token"], data["id"]


def sse_refs(payload: str) -> list[dict]:
    """从 SSE 文本里抠出 `refs` 帧的引用列表。

    ★ 逐行解析而不是整段 JSON：SSE 是 `data: {...}` 一行一帧；整段 `json.loads` 必失败，
      而"失败后返回空列表"会让"应该有引用"这条**永远红**、变成噪声。
    """
    for line in payload.splitlines():
        line = line.strip()
        if not line.startswith("data:"):
            continue
        try:
            frame = json.loads(line[len("data:") :].strip())
        except json.JSONDecodeError:
            continue
        # ★★ 帧的形状是**按内容命名的键**，不是 `event`/`type` 字段（实测四种帧）：
        #   `{"messageId","traceId","model"}` · `{"refs":[…]}` · `{"text":…}` ·
        #   `{"status","messageId","tokens","durationMs"}`
        #   ⇒ 判"这是不是 refs 帧"要看**有没有 `refs` 键**。
        #   ★ 第一版按 `event == "refs"` 找，永远不命中 —— 而它的症状与"产品不带引用"**一模一样**。
        if "refs" in frame and isinstance(frame["refs"], list):
            return frame["refs"]
    return []


def sse_frame_kinds(payload: str) -> list[str]:
    """把这一段 SSE 里的**帧类型**按顺序列出来（诊断用）。

    ★ 为什么需要它：`refs` 为 0 条时有两种可能，读数上一样、处置完全不同 ——
      ① **我的解析漏了**（帧在，但键名/结构不是我猜的那样）；
      ② **真的没有检索到**（帧不在，说明检索那一步没给东西）。
      打印帧类型一眼就能分开这两件事，比再猜一轮便宜得多。
    """
    kinds: list[str] = []
    for line in payload.splitlines():
        line = line.strip()
        if not line.startswith("data:"):
            continue
        raw = line[len("data:") :].strip()
        try:
            frame = json.loads(raw)
        except json.JSONDecodeError:
            kinds.append(f"<非 JSON: {raw[:40]}>")
            continue
        # ★ 帧没有类型字段，类型就是**键名**（见 `sse_refs` 的注释）
        keys = list(frame)
        kinds.append("refs" if "refs" in keys else "text" if "text" in keys else "+".join(keys[:4]))
    return kinds


def main() -> int:
    print("== S2 真栈闸门：公开链接 → 未登录提问 → 撤销后失效 ==")

    token = login()
    print("  已登录（admin）")

    model_id, created_chat_model = ensure_chat_model(token)
    print(f"  已取到对话模型 {model_id}（{'本闸门新建' if created_chat_model else '复用'}）")

    workdir = tempfile.mkdtemp(prefix="s2share-")
    kb_id, doc_id, embedding_model_id, created_embedding_model = build_kb(token, workdir)
    print(f"  已建知识库 {kb_id} 并上传/向量化文档 {doc_id}")

    prompt_type_id, prompt_id = create_prompt(token)
    print(f"  已建提示词 {prompt_id}（类型 {prompt_type_id}）")

    app_id = create_app(token, model_id, kb_id, [prompt_id])
    print(f"  已建应用 {app_id}（已绑 1 条提示词）")

    credential, credential_id = issue_credential(token, app_id)
    print("  已签发公开凭证")

    # ★ 清空桩的请求记录：不然后面读到的那条可能是**别的闸门**（或本闸门更早的轮次）留下的，
    #   而"看到提示词"这件事就变得无法归因到本轮这一次提问。
    stub_call("POST", "/__stub/reset")

    try:
        # ── ① 未登录能解析出上下文（★ 判据 ④ 的对照：这一条绿，才说明 ③ 的红不是"永远 404"）──
        status, payload = call("GET", f"/public/{credential}")
        check(
            status == 200,
            f"① 未登录解析公开链接 ⇒ 200（实得 {status}）{'' if status == 200 else '：' + payload[:160]}",
        )
        if status == 200:
            data = json.loads(payload).get("data") or {}
            # ★ 字段名取自契约 `PublicContext.appName`（不是 assistantName/name）——
            #   第一版猜错了，CI 逐字报「实得 None」。
            check(
                bool(data.get("appName")),
                f"① 上下文里有应用名（实得 {data.get('appName')}）",
            )
            check(
                bool(data.get("maxClassification")) is not False,
                f"① 公开面必须给出冻结密级（契约必填，实得 {data.get('maxClassification')}）",
            )

        # ── ② 未登录能提问，且答案带引用 ──
        status, payload = call(
            "POST",
            "/conversations/chat",
            token=None,
            body={"question": QUESTION, "appId": app_id},
            credential=credential,
            raw=True,
        )
        check(
            status == 200,
            f"② 未登录提问 ⇒ 200 + SSE（实得 {status}）{'' if status == 200 else '：' + payload[:160]}",
        )
        if status == 200:
            refs = sse_refs(payload)
            check(
                len(refs) > 0,
                f"② ★★ 答案必须**带引用**（S2 原文『得带引用答案』）—— 实得 {len(refs)} 条",
            )
            if not refs:
                # ★ 诊断（不是判据）：帧类型 + 所选库 + 问题 —— 用来分开"解析漏了"与"真的没检索到"
                print(f"  [诊断] SSE 帧类型顺序：{sse_frame_kinds(payload)}")
                print(f"  [诊断] 本轮用的知识库 {kb_id} · 问题「{QUESTION}」")
            check(
                "done" in payload,
                "② 流要收在 done 帧上（否则『答完了』这件事没有被确认）",
            )

            # ── ②b ★★ 应用绑定的提示词**正文**必须真的进了上游 prompt（`#215`）──
            #   ★ 这一条只能在桩的请求记录上断：API 回执、界面、库里都只说明"绑了"，
            #     不说明"用了"。修复前这里恒红（`ChatService` 只拼 `rolePrompt`）。
            requests = chat_requests()
            joined = "\n".join(json.dumps(row.get("body"), ensure_ascii=False) for row in requests)
            check(
                bool(requests),
                f"②b 上游桩至少收到一条 chat 请求（实得 {len(requests)} 条）",
            )
            check(
                PROMPT_MARKER in joined,
                "②b ★★ 绑定的提示词正文必须出现在上游请求体里（不回退成『只是存下来了』）"
                + ("" if PROMPT_MARKER in joined else f" —— 请求体里找不到标记「{PROMPT_MARKER}」"),
            )
            if PROMPT_MARKER not in joined:
                # ★ 诊断：把最后一条请求体的开头打出来 —— 分开"提示词没进"与"拼进去但被截断/改写"
                last = json.dumps(requests[-1].get("body"), ensure_ascii=False) if requests else ""
                print(f"  [诊断] 最后一条 chat 请求体开头：{last[:400] or '（空）'}")

        # ── ③ 撤销后立即失效 ──
        status, payload = call(
            "POST", f"/assistants/{app_id}/credentials/{credential_id}/revoke", token
        )
        check(
            status == 200,
            f"③ 撤销凭证 ⇒ 200（实得 {status}）{'' if status == 200 else '：' + payload[:160]}",
        )

        status, payload = call("GET", f"/public/{credential}")
        check(
            status in (403, 404, 410),
            f"③ ★★ 撤销后同一个 token 再解析 ⇒ 必须失败（实得 {status}，期望 403/404/410）"
            f"{' —— 『撤销后还能用一会儿』就是 S2 判据 ② 不成立' if status == 200 else ''}",
        )
    finally:
        # ★ 清夹具（这一条以前没有，每跑一次就多一个应用 / 提示词 / 类型 / 库 / 模型）。
        #   顺序有讲究：**先删应用**（它引用提示词与库），再删文档 → 库 → 提示词模板 → 类型 → 我建的模型。
        #   ★ 模型最后删：它被库引用着的时候删不掉。
        cleanup(token, f"/assistants/{app_id}")
        cleanup(token, f"/documents/{doc_id}")
        cleanup(token, f"/knowledge-bases/{kb_id}")
        cleanup(token, f"/prompt-configs/{prompt_id}")
        cleanup(token, f"/prompt-types/{prompt_type_id}")
        if created_embedding_model:
            cleanup(token, f"/models/{embedding_model_id}")
        if created_chat_model:
            cleanup(token, f"/models/{model_id}")
        md_path = os.path.join(workdir, "s2share.md")
        if os.path.exists(md_path):
            os.remove(md_path)
        os.rmdir(workdir)

    # ── 汇总 ──
    if problems:
        print(f"\n结果：红 —— {len(problems)} 条")
        for item in problems:
            print(f"  [FAIL] {item}")
        return 1
    print("\n结果：绿 —— S2 五条判据全部成立（未登录可解析 · 可提问带引用 · 提示词真进了上游 · 撤销后立即失效 · 有成功对照）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
