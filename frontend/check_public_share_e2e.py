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


def create_app(token: str) -> str:
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
            "name": name,
            "description": "S2 公开链接闸门用的临时应用",
            "modelId": None,
            "rolePrompt": "你是知识助理，请基于参考资料回答。",
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
        if frame.get("event") == "refs" or frame.get("type") == "refs":
            refs = frame.get("refs") or frame.get("data") or []
            if isinstance(refs, list):
                return refs
    return []


def main() -> int:
    print("== S2 真栈闸门：公开链接 → 未登录提问 → 撤销后失效 ==")

    token = login()
    print("  已登录（admin）")

    app_id = create_app(token)
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
        check(
            bool(data.get("assistantName") or data.get("name")),
            f"① 上下文里有应用名（实得 {data.get('assistantName') or data.get('name')}）",
        )

    # ── ② 未登录能提问，且答案带引用 ──
    status, payload = call(
        "POST",
        "/conversations/chat",
        token=None,
        body={"question": "报销标准是什么？", "appId": app_id},
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
