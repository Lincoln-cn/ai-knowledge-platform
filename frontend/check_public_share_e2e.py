"""S2 的真栈端到端闸门 —— 「公开链接 → 未登录提问得带引用答案 → 撤销后立刻失效」。

━━━ 为什么必须有一条**真栈**闸门 ━━━
`ROADMAP §2.1` 的 S2 是 MVP 三场景之一，而它此前**只有 mock 侧的读数**：
`check_publish.py` 跑的是**编排画布的发布**（不是公开链接），而"生成公开链接"那条链
（`/assistants/{appId}/credentials`）与"未登录提问"那条链（`POST /conversations/chat` +
`X-Access-Credential` 头）**没有任何一条闸门在 CI 里点过**。

★ 这正是 `#3` 记过的那类风险：**mock 比真后端宽松** ⇒ 假绿灯。
  mock 里"撤销后失效"是一个 if；真栈里它涉及 `PublicTokenResolver` + 冻结密级 + 每次访问实时再滤。
  两者的读数在界面上完全一样。

━━━ 判据四条（缺一条都能骗过）━━━
① **未登录能解析出上下文**：`GET /public/{token}` 不带 Authorization ⇒ 200，且拿到应用名与冻结密级；
② ★★ **未登录能提问，且答案带引用**：`POST /conversations/chat` 只带 `X-Access-Credential`
   ⇒ SSE 里必须有 `refs` 帧，且 refs **非空**（"答了但一条引用都没有"不算 S2 达标，
   而"整条链路坏了"也会让"应有引用"这条变绿 —— 所以两条一起断）；
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
import sys
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"

# 演示账号（与 ROADMAP 的演示一致）。★ 用 admin：它能建应用、能签发凭证。
USER = "admin"
PASSWORD = "admin"

SOFTWARE_NAME = "S2 公开链接闸门"

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


def first_chat_model(token: str) -> str:
    """取一个**已加载**的对话模型 id。

    ★ 为什么要它：契约的 `UpsertAssistantBody.modelId` 是**必填**，而"应用选了哪个模型"
      正是 S2 的第一步（建应用 → 选模型）。★ 取不到就直接退出并打出清单 ——
      "建应用失败: modelId 为空"这种报错会让人去查应用，而不是查模型目录。
    """
    status, payload = call("GET", "/models?size=50", token)
    if status != 200:
        raise SystemExit(f"取模型目录失败（{status}）：{payload[:200]}")
    rows = (json.loads(payload).get("data") or {}).get("list") or []
    for row in rows:
        if row.get("kind") == "CHAT" and row.get("status") == "LOADED":
            return row["id"]
    raise SystemExit(f"没有已加载的对话模型（共 {len(rows)} 个模型）：真栈夹具要求先加载一个 CHAT 模型")


def first_kb_with_documents(token: str) -> str:
    """取一个**真的有文档**的知识库。

    ★★ 为什么这一步不能省：S2 要断的是「未登录提问得**带引用**答案」。
      第一版把 `kbIds` 设成空数组 ⇒ 应用没绑任何库 ⇒ 检索没有可引用的东西 ⇒
      refs 恒 0 条。★ 那不是产品缺陷，是**闸门自己没搭好前提** ——
      而"前提没搭好"与"链路坏了"在读数上长得一模一样，正是本仓头号病的形状。

    ★ 选"有文档的"而不是"第一个"：空库同样会让 refs 恒 0。
    """
    status, payload = call("GET", "/knowledge-bases?size=50", token)
    if status != 200:
        raise SystemExit(f"取知识库列表失败（{status}）：{payload[:200]}")
    rows = (json.loads(payload).get("data") or {}).get("list") or []
    for row in rows:
        if (row.get("documentCount") or 0) > 0:
            return row["id"]
    raise SystemExit(f"没有含文档的知识库（共 {len(rows)} 个库）：S2 要求有可引用的内容")


def create_app(token: str, model_id: str, kb_id: str) -> str:
    """建一个最小应用（S2 的第一步：建应用）。

    ★ 名字带时间戳：闸门要能重复跑，重名会让"按名字找应用"在下一次读到自己上次建的。
    """
    import time

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

    model_id = first_chat_model(token)
    print(f"  已取到对话模型 {model_id}")

    kb_id = first_kb_with_documents(token)
    print(f"  已取到知识库 {kb_id}")

    app_id = create_app(token, model_id, kb_id)
    print(f"  已建应用 {app_id}")

    credential, credential_id = issue_credential(token, app_id)
    print("  已签发公开凭证")

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

    # ── ③ 撤销后立即失效 ──
    status, payload = call("POST", f"/assistants/{app_id}/credentials/{credential_id}/revoke", token)
    check(status == 200, f"③ 撤销凭证 ⇒ 200（实得 {status}）{'' if status == 200 else '：' + payload[:160]}")

    status, payload = call("GET", f"/public/{credential}")
    check(
        status in (403, 404, 410),
        f"③ ★★ 撤销后同一个 token 再解析 ⇒ 必须失败（实得 {status}，期望 403/404/410）"
        f"{' —— 『撤销后还能用一会儿』就是 S2 判据 ② 不成立' if status == 200 else ''}",
    )

    # ── 汇总 ──
    if problems:
        print(f"\n结果：红 —— {len(problems)} 条")
        for item in problems:
            print(f"  [FAIL] {item}")
        return 1
    print("\n结果：绿 —— S2 四条判据全部成立（未登录可解析 · 可提问带引用 · 撤销后立即失效 · 有成功对照）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
