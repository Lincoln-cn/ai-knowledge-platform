"""真栈画布闸门：编排画布在**真后端**上能不能把图渲染出来（#21）。

为什么需要这一条
================
`#20`（编排画布一个节点都渲染不出来）在 `main` 上活了 **9 天**，而 CI 里「五条真栈闸门」
与「11 条浏览器闸门」都在跑 —— 因为它们**没有一条去看画布**：

· 知识库五条真栈闸门：建档 → 解析 → 分段 → 向量化 → 文档动作，与画布无关；
· 画布三条闸门（`check_canvas` / `check_nodeconfig` / `check_node_menu`）：跑在 **mock** 上，
  而 mock 的图是前端夹具自带的（`fixtures/workflow.ts` 里 `as5` 有 9 个节点）——
  它验证的是「前端拿到一张图能不能画出来」，**不是**「真后端给的图能不能画出来」。

⇒ 这一条补的就是那一段：图的来源必须是 `GET /workflow/draft` 的真实返回。

种子图由脚本自己建（不给 `db/seed` 加产品无关的夹具）
====================================================
每条判据跑之前先 `PUT /workflow/draft` 写一份**幂等**的四节点图，再用 `GET` 读回来自证
「后端确实存下了这四个节点」。这样换库、换环境都能跑，闸门也不依赖任何手工建的应用。

判据（任一条不满足即非零退出）
==============================
① 后端真的存下了 4 个节点（`GET /workflow/draft` 的 `nodes` 长度为 4）；
② 画布渲染出的 `.workflow-node-container` 数量 **等于** 契约里那张图的节点数（不是「> 0」）；
③ 任一节点的 ⋮ 菜单能打开，且菜单里有「删除节点」；
④ 全程没有 `pageerror`，控制台里也没有 `找不到…对应的节点，请确认是否已注册此类型节点`
   （`#20` 的那条异常原文）。

用法：python check_workflow_canvas.py [base]
      base 默认 http://localhost:5280（CI 真栈）；本地真栈常是 5281。
      --self-check 造一份**坏图**（把一个节点的类型改成不存在的类型），
      此时本闸门必须**非零退出** —— 否则它只是一条恒绿的装饰。
"""
from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright

ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
SELF_CHECK = "--self-check" in sys.argv
BASE = (ARGS[0] if ARGS else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"
NODE = ".workflow-node-container"
DELETE_LABEL = "删除节点"
# `#20` 的异常原文里最关键的那一段（前端在节点类型未注册时抛它）
UNREGISTERED_MARK = "对应的节点，请确认是否已注册此类型节点"

SEED_NAME = "__画布闸门种子__"
SEED_DESC = "由 check_workflow_canvas.py 自建，闸门跑完可删。"


def _node(nid: str, ntype: str, title: str, x: int, y: int, config: dict | None = None) -> dict:
    """按 `frontend/src/api/workflow.ts` 的 `WorkflowNode` 形状造一个节点。

    ★ 字段名是 `title` / `position`，**不是** `label` / `x` / `y` —— 我第一次建种子图时写错，
    画布一个节点都不渲染，会让人误判成产品 bug。
    """
    return {
        "id": nid,
        "type": ntype,
        "title": title,
        "position": {"x": x, "y": y},
        "config": config or {},
    }


SEED_GRAPH = {
    "nodes": [
        _node("n-start", "START_NODE", "开始", 120, 220),
        _node("n-search", "SEARCH_DATASET_NODE", "知识库检索", 400, 220, {"kbIds": [], "topN": 5}),
        _node("n-ai", "AI_CHAT_NODE", "AI 对话", 680, 220, {"prompt": ""}),
        _node("n-reply", "REPLY_NODE", "回复", 960, 220),
    ],
    "edges": [
        {"id": "e1", "source": "n-start", "target": "n-search"},
        {"id": "e2", "source": "n-search", "target": "n-ai"},
        {"id": "e3", "source": "n-ai", "target": "n-reply"},
    ],
}


def _call(method: str, path: str, body: dict | None = None, token: str | None = None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API + path, method=method, data=data)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as exc:
        detail = exc.read()[:300].decode("utf-8", "replace")
        raise SystemExit(f"★ {method} {path} ⇒ {exc.code}：{detail}") from None


def _ensure_seed_app(token: str) -> str:
    """按名字找一个编排应用，找不到就建一个 —— 闸门不该依赖手工建的 id。"""
    found = _call("GET", "/assistants?type=WORKFLOW&size=200", token=token).get("data") or {}
    items = found.get("list") or found.get("items") or []
    for item in items:
        if item.get("name") == SEED_NAME:
            return item["id"]
    created = _call(
        "POST",
        "/assistants",
        {
            "type": "WORKFLOW",
            "name": SEED_NAME,
            "description": SEED_DESC,
            "icon": "🧩",
            # ★ 编排应用**没有**应用级模型（`#281`）—— 带上 modelId 会被后端拒。
            "kbIds": [],
        },
        token=token,
    )
    print(f"  · 建了种子应用 {created['data']['id']}")
    return created["data"]["id"]


def build_seed_graph() -> dict:
    """返回要写进草稿的图；`--self-check` 时把一个节点类型改成**不存在的类型**。

    ★ 反证为什么选「类型不存在」而不是「删一个节点」：删节点只会让数量从 4 变 3，
    那证明的是"数量断言在工作"；而 `#20` 的病因正是**类型未注册 ⇒ 整页画不出来**，
    所以要用同一个形状去撞它。
    """
    graph = json.loads(json.dumps(SEED_GRAPH))
    if SELF_CHECK:
        graph["nodes"][2]["type"] = "NOT_A_REGISTERED_TYPE"
    return graph


def main() -> int:
    ok: list[tuple[bool, str]] = []

    def check(cond: bool, msg: str) -> None:
        ok.append((cond, msg))
        print(("  ✓ " if cond else "  ✗ ") + msg)

    graph = build_seed_graph()
    token = _call("POST", "/auth/login", {"username": "admin", "password": "admin"})["data"]["accessToken"]
    app_id = _ensure_seed_app(token)

    # ── ① 后端存下了这张图 ──
    print("\n== ① 后端存下了 4 个节点 ==")
    _call("PUT", f"/assistants/{app_id}/workflow/draft", {"graph": graph}, token=token)
    draft = _call("GET", f"/assistants/{app_id}/workflow/draft", token=token)["data"]["graph"]
    stored_nodes = draft.get("nodes") or []
    check(
        len(stored_nodes) == len(graph["nodes"]),
        f"GET /workflow/draft 返回 {len(stored_nodes)} 个节点（期望 {len(graph['nodes'])}）",
    )

    # ── ②③④ 浏览器侧 ──
    with sync_playwright() as pw:
        b = pw.chromium.launch(channel="chrome")
        page = b.new_page(viewport={"width": 1600, "height": 1000})
        errs: list[str] = []
        page.on("pageerror", lambda e: errs.append(str(e)))
        page.on(
            "console",
            lambda m: errs.append(m.text[:200]) if m.type == "error" else None,
        )

        page.goto(f"{BASE}/#/login", wait_until="networkidle")
        page.wait_for_timeout(600)
        page.fill("input[autocomplete='username']", "admin")
        page.fill("input[autocomplete='current-password']", "admin")
        page.click("button:has-text('登录')")
        page.wait_for_timeout(1600)

        # ★ 带 `?n=` 查参数强制一次真导航：纯 hash 变化不产生文档导航，画布可能不重挂载。
        page.goto(f"{BASE}/?n=canvas#{('/assistants/' + app_id + '/workflow')}", wait_until="networkidle")
        try:
            page.wait_for_selector(NODE, timeout=30000)
        except Exception:
            print("  ✗ 画布上一个 .workflow-node-container 都没有出现")
        page.wait_for_timeout(1500)

        print("\n== ② 画布渲染出的节点数 == 图里的节点数 ==")
        rendered = page.locator(NODE).count()
        check(
            rendered == len(SEED_GRAPH["nodes"]),
            f"画布上有 {rendered} 个节点（期望 {len(SEED_GRAPH['nodes'])}）",
        )

        print("\n== ③ 节点的 ⋮ 菜单能打开且含「删除节点」 ==")
        # 菜单弹层 teleported 到 body、多个 popper 同时留 DOM，只有当前打开的有非零宽高
        # ⇒ 判「哪个开着」必须用可见性过滤（同 check_node_menu.py 的教训）。
        opened = []
        for idx in range(min(rendered, len(SEED_GRAPH["nodes"]))):
            title = SEED_GRAPH["nodes"][idx]["title"]
            container = page.locator(NODE).filter(has_text=title).first
            try:
                container.locator(".el-dropdown button").first.click(timeout=5000)
            except Exception:
                opened.append((title, False, ""))
                continue
            page.wait_for_timeout(400)
            visible = [
                item
                for item in page.locator(".el-dropdown-menu").all()
                if item.is_visible()
            ]
            hit = bool(visible) and any(
                visible[0].locator(f".el-dropdown-menu__item:has-text('{DELETE_LABEL}')").count() > 0
                for _ in [0]
            )
            opened.append((title, hit, "" if hit else "菜单里没有「删除节点」"))
            page.keyboard.press("Escape")
            page.wait_for_timeout(200)
        bad = [(t, why) for t, hit, why in opened if not hit]
        # ★ 空真守卫：画布一个节点都没有时 `opened` 是空列表，「没有坏节点」会平凡成立。
        check(
            rendered > 0 and not bad,
            f"{len(opened)} 个节点的 ⋮ 菜单都能打开且有「删除节点」（失败：{bad if bad else '无'}）",
        )

        print("\n== ④ 全程无页面异常、无「节点类型未注册」 ==")
        unregistered = [e for e in errs if UNREGISTERED_MARK in e]
        check(not unregistered, f"控制台没有「{UNREGISTERED_MARK}」（实得 {len(unregistered)} 条）")
        check(not errs, f"全程无 pageerror / console error（实得 {len(errs)} 条：{errs[:3]}）")

        b.close()

    failed = [msg for cond, msg in ok if not cond]
    print(f"\n{'=' * 60}")
    if failed:
        print(f"★ 失败 {len(failed)}/{len(ok)}：")
        for msg in failed:
            print("  · " + msg)
        return 1
    print(f"全部通过（{len(ok)}/{len(ok)}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
