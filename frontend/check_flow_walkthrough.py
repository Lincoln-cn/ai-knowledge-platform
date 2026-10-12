"""全站页面走查（真栈）—— 回答「哪些页面在浏览器里走不通」。

为什么需要它：既有的闸门各管一段（`check_knowledge_*` 管知识库、`check_models_form` 管模型、
`verify_permissions` 管路由门禁、`smoke_test` 只跑 mock 构建产物），
**没有一个回答"逐页走一遍、哪些页是坏的"**。而用户报问题的方式恰恰是「这个功能用不了」——
所以需要一个能一次跑出全貌的工具。

每一页记录三件事（不看代码、只看运行事实）：
  1. 页面是否渲染出关键内容（特征元素/关键词）
  2. 这一页发出的接口里**失败**的那些（状态 >= 400，含 URL 与状态码）
  3. 控制台/页面错误

★ 断言分三档，避免"把欠账装成红"或"把坏页装成绿"：
  · **必须能用**（`MUST_WORK`）：今天的演示路径，坏了就 FAIL
  · **已知未落地**（`KNOWN_PENDING`）：后端还没实现（各自有周次计划），只记录不判失败 ——
    但**必须真的失败**：如果哪天它不再报失败，说明该更新清单了（否则清单会变成永久黑洞）。
  · **页面已落地**（`PAGE_LIVE`）：加载时一声不响、**点主按钮才现形**的那一档。
    第 10 周检索域落地后它从"点一下必然 404"翻成了"点一下必须真的返回 2xx"
    —— 而这一档最容易被误判成"这页是好的"，所以判据落在**接口有没有真的返回**上，
    不是"没看到报错"。

★ 这一版修掉的三个问题（上一版跑不起来）：
  1. `self_tier` 未定义 ⇒ 第一页就 NameError，输出只剩"环境"两行（假绿：退出码非 0 但看着像跑过）
  2. MUST_WORK 的断言实际在看**控制台错误**，报的却是**失败接口**的数字 ⇒ 断言与消息不是一件事
  3. 路由清单不全（漏了 `/knowledge/hittest`、`/change-password`）⇒ 漏页永远不被看见

用法：
    python check_flow_walkthrough.py                  # 默认 http://localhost:5280
    python check_flow_walkthrough.py http://127.0.0.1:5280
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"

# ★ 命中测试要"真出结果"，就得有"库 + 文档 + 分段 + 向量"这条链的夹具。
#   ⇒ 本档**自己造一份最小的**（建模型 → 建库 → 上传真实 DOCX → 等解析 → 向量化 → 等成功），
#     然后用 `?kbId=` 把这个库指定给页面（页面本来就支持从 URL 读 kbId）。
#   ★ 为什么不复用别的闸门留下的库：它们跑完会清理夹具（`已清理夹具库（200）`），
#     而"库里恰好没数据"会让断言变成随环境漂的东西 —— 那正是这一档最初要防的。
DOCX = Path(__file__).resolve().parent.parent / "backend/src/test/resources/fixtures/structured.docx"


def api(method: str, path: str, token: str, body: dict | None = None) -> tuple[int, dict]:
    """调后端 REST（走 dev server 的代理 ⇒ 与页面同一条路）。"""
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


def api_upload(path: str, token: str, file: Path) -> tuple[int, dict]:
    """真实 multipart 上传（与「上传文档」弹窗同一个端点）。"""
    boundary = "----walkthrough0boundary"
    buf = b""
    for key, value in (
        ("docType", "制度"),
        # ★ 责任部门改成 org_id（UUID）：V25 种子里只有 ROOT 是**确定性** id（其余部门是 gen_random_uuid()）
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
    """轮询文档状态。

    ★ 用**列表端点**而不是 `GET /documents/{id}` —— 后者不存在（文档端点都挂在知识库下：
      `GET /knowledge-bases/{kbId}/documents`）。第一版按"应该有"写了，结果拿回一个空状态
      （表现为 `实际 ?`），白白多跑一轮。
    """
    deadline = time.time() + seconds
    status = "?"
    while time.time() < deadline:
        _, body = api("GET", f"/knowledge-bases/{kb_id}/documents?page=1&size=100", token)
        rows = ((body.get("data") or {}).get("list")) or []
        for row in rows:
            if row.get("id") == doc_id:
                status = row.get("status") or "?"
                break
        if status in expected:
            return status
        time.sleep(1.5)
    return status


def seed_hit_test_fixture(token: str) -> tuple[str, str] | None:
    """造一份"能被检索到"的最小夹具，返回 (kb_id, question)。

    ★ 顺序就是那条真链：建向量模型 → 建库 → 上传 → **等解析落地** → 向量化 → **等向量化成功**。
      少任何一步，命中测试都只会返回空 —— 而"空"看起来和"没命中"一模一样。
    ★ 每一步都把状态打出来：闸门自己的失败必须自解释（否则"夹具没造出来"就成了一句无信息的话）。
    """
    def fail(step: str, status: int, body: dict) -> None:
        message = body.get("message") or body
        print(f"  [NG] 夹具第「{step}」步失败：status={status} body={str(message)[:200]}")

    if not DOCX.exists():
        fail(f"夹具文件（{DOCX.name}）不存在", -1, {})
        return None
    stamp = str(int(time.time()))
    status, created = api(
        "POST",
        "/models",
        token,
        {
            "name": f"走查向量-{stamp}",
            "kind": "EMBEDDING",
            "supplier": "LOCAL_INFERENCE",
            "baseModel": "bge-m3",
            "dimension": 768,
        },
    )
    model_id = (created.get("data") or {}).get("id")
    if status != 200 or not model_id:
        fail("建向量模型", status, created)
        return None

    status, kb = api(
        "POST",
        "/knowledge-bases",
        token,
        {
            "name": f"走查库-{stamp}",
            "category": "制度规范",
            "importType": "LOCAL",
            "embeddingModelId": model_id,
            "maxClassification": "SECRET",
        },
    )
    kb_id = (kb.get("data") or {}).get("id")
    if status != 200 or not kb_id:
        fail("建知识库", status, kb)
        return None

    status, uploaded = api_upload(f"/knowledge-bases/{kb_id}/documents", token, DOCX)
    # ★ 这个端点的 data 是**数组**（一次可传多个文件），不是对象 —— 取值要按数组写
    data = uploaded.get("data")
    if isinstance(data, list):
        doc_id = data[0].get("id") if data else None
    else:
        doc_id = (data or {}).get("id")
    if status != 200 or not doc_id:
        fail("上传文档", status, uploaded)
        return None

    parsed = wait_doc_status(token, kb_id, doc_id, {"PENDING_EMBEDDING", "SEGMENT_FAILED"}, 60)
    if parsed != "PENDING_EMBEDDING":
        fail(f"等解析落地（实际 {parsed}）", -1, {})
        return None
    status, started = api("POST", f"/documents/{doc_id}/embedding", token, {})
    embedded = wait_doc_status(token, kb_id, doc_id, {"SUCCEEDED", "EMBEDDING_FAILED"}, 90)
    if embedded != "SUCCEEDED":
        fail(f"等向量化成功（实际 {embedded}）", status, started)
        return None
    print(f"  · 夹具就绪：kbId={kb_id} docId={doc_id}（已解析 + 已向量化）")
    # ★ 问句用夹具文档里真有的词（报销/标准），否则"没命中"会被误读成"检索坏了"
    return kb_id, "报销标准"

# ── 必须能用（今天的演示路径：后端已实现的那几块）──
MUST_WORK: list[tuple[str, str, str]] = [
    ("/knowledge", "知识库列表", "知识库"),
    ("/models", "模型管理", "模型"),
    ("/admin", "系统管理", "用户"),
    # ★ 基础数据（第 19 周新增）：字典项管理。★ 它之所以归「必须能用」而不是「已知未落地」：
    #   后端 `/dictionaries` 与页面同时落地，而且它是建档下拉的真源 —— 它坏了，建档就选不出文种。
    ("/basedata", "基础数据", "字典"),
    # 改密页在加载时不发请求（表单要用户填），但它的接口 PUT /users/me/password 已实现 ⇒ 归入"必须能用"
    ("/change-password", "修改密码", "密码"),
    # ★ 第 15 周 0.4.3：应用管理页的加载链路（GET /assistants 列表）已落地 ⇒ 从 KNOWN_PENDING 移过来。
    #   是**闸门自己**要求移的："已不再报失败 => 请把它从 KNOWN_PENDING 移走" —— 那条断言防的正是
    #   "曾经欠过、现在早已实现，而清单还挂着它"的黑洞（同族：ContractDriftTest 的未实现前缀）。
    ("/assistants", "应用管理", "应用"),
]

# ── 已知后端未落地：**加载时就会报失败**（各自有周次计划，只记录不判失败）──
KNOWN_PENDING: list[tuple[str, str, str]] = [
    ("/", "工作台", "工作台"),
    ("/conversation", "对话", "对话"),
    ("/assistants/prompts", "提示词", "提示词"),
    ("/market", "应用市场", "应用"),
    ("/analytics", "运营看板", "看板"),
    ("/feedback", "反馈处理", "反馈"),
]

ALL_PAGES = MUST_WORK + KNOWN_PENDING
MUST_ROUTES = {r for r, _, _ in MUST_WORK}

# ── 「页面已落地」：加载时一声不响，**点主按钮才现形** ──
# ★ 这一档的前身是「页面在、功能未落地」（那时点一下必然 404）。第 10 周 0.3.2 检索域落地后，
#   命中测试页真的能用了 ⇒ 判据**反过来**：点主按钮必须成功，且接口必须真的返回 2xx。
#   ★ 而这条判据"能红"是有现场证据的：它上一版就是"必须失败"，
#     在检索域落地的那次 CI 上报了「点「测试」已不再报失败 => 该域可能已落地，请更新本档」
#     —— 是这个闸门把我们赶到这里的，不是我们"顺手放宽"。
PAGE_LIVE: list[tuple[str, str, str, str]] = [
    # (路由, 名称, 要点的按钮, 必须返回 2xx 的接口片段)
    ("/knowledge/hittest", "命中测试", "测试", "retrieval/test"),
]


def main() -> int:
    ok: list[tuple[bool, str]] = []
    rows: list[tuple[str, str, str, list[str], list[str]]] = []

    def check(cond: bool, msg: str) -> bool:
        # ★ 必须 return：曾经这里返回 None，而调用处写的是 `if not check(...): return 1`
        #   ⇒ 恒真 ⇒ 闸门在"环境"那步就退出了，输出只有两行却看着像"跑过"。
        #   闸门自己的返回值是它最容易骗过人的地方。
        ok.append((bool(cond), msg))
        print(("  [OK] " if cond else "  [NG] ") + msg)
        return bool(cond)

    with sync_playwright() as pw:
        browser = pw.chromium.launch(channel="chrome")
        page = browser.new_page(viewport={"width": 1600, "height": 950})
        console_errors: list[str] = []
        ok_calls: list[str] = []
        failed_calls: list[str] = []
        # ★ 走查必须区分「演示账号本来就无权访问」与「后端真的坏了」：
        #   403 是权限门禁按设计工作，不是缺陷。但也不能一律放过 ——
        #   演示路径上的页面若是 403，那说明演示账号的权限配置坏了，仍然要红。
        forbidden_calls: list[str] = []

        def on_console(m) -> None:
            if m.type == "error":
                loc = m.location
                url = loc.get("url", "") if isinstance(loc, dict) else ""
                if "favicon" not in url:
                    console_errors.append(f"{m.text} @ {url}")

        def on_response(r) -> None:
            if "/api/v1" not in r.url:
                return
            path = r.url.split("/api/v1")[-1]
            if r.status == 403:
                forbidden_calls.append(f"{r.status} {path}")
            elif r.status >= 400:
                failed_calls.append(f"{r.status} {path}")
            else:
                # ★ 成功的调用也要记：新一档（PAGE_LIVE）的判据是"这个接口**真的返回了**"，
                #   而只记失败的话，那种断言写不出来 —— 只能退化成"没报错就算过"。
                ok_calls.append(f"{r.status} {path}")

        page.on("console", on_console)
        page.on("pageerror", lambda e: console_errors.append(f"pageerror: {e}"))
        page.on("response", on_response)

        try:
            # ── 登录（真栈：token 不是 mock_ 前缀）──
            page.goto(f"{BASE}/#/login", wait_until="networkidle")
            page.wait_for_timeout(800)
            page.fill("input[autocomplete='username']", "admin")
            page.fill("input[autocomplete='current-password']", "admin")
            page.click("button:has-text('登录')")
            page.wait_for_timeout(2500)
            token = page.evaluate("() => localStorage.getItem('access_token') || ''")
            print("== 环境 ==")
            if not check(bool(token) and not token.startswith("mock_"), "连的是真后端（token 非 mock_）"):
                browser.close()
                return 1
            print(f"  · base = {BASE}")

            # ── 逐页走查（★ 每页独立容错：一页坏了不挡其余，否则"走查"本身会变成单点）──
            print("\n== 逐页走查 ==")
            for route, name, marker in ALL_PAGES:
                tier = "必须能用" if route in MUST_ROUTES else "已知未落地"
                print(f"  ... {route} {name}", flush=True)
                console_errors.clear()
                failed_calls.clear()
                forbidden_calls.clear()
                try:
                    page.goto(f"{BASE}/#{route}", wait_until="domcontentloaded")
                    # ★ 不用 networkidle：分析看板这类页面会持续轮询/请求未实现接口，
                    #   networkidle 可能永远等不到 ⇒ 整页走查卡死在一个页面上
                    page.wait_for_timeout(3000)
                    body = page.inner_text("body")
                    rendered = len(body.strip()) > 60
                    hit = marker in body
                except Exception as exc:  # noqa: BLE001
                    rows.append(
                        (route, name, tier, [f"页面异常：{exc}"], [], [])
                    )
                    print(f"  [NG] {route:<24} {name:<10} 页面异常：{str(exc)[:80]}")
                    continue
                errs = [e[:120] for e in console_errors[:3]]
                rows.append(
                    (route, name, tier, sorted(set(failed_calls)), errs, sorted(set(forbidden_calls)))
                )
                flag = "[OK]" if (hit and rendered) else "[NG]"
                print(
                    f"  {flag} {route:<24} {name:<10} 关键内容={'有' if hit else '无'} "
                    f"失败接口={len(set(failed_calls))} 403={len(set(forbidden_calls))} "
                    f"控制台错误={len(console_errors)}"
                )

            # ── 必须能用的页面：接口与控制台都要干净 ──
            print("\n== 必须能用的页面 ==")
            for route, name, marker in MUST_WORK:
                row = next(r for r in rows if r[0] == route)
                _, _, _, fails, errs, forbids = row
                check(not fails, f"{name}（{route}）没有失败接口 —— 实际：{fails}")
                check(not forbids, f"{name}（{route}）没有 403（演示账号应有全权）—— 实际：{forbids}")
                check(not errs, f"{name}（{route}）控制台无错误 —— 实际：{errs}")

            # ── 已知未落地：必须真的失败，否则清单该更新了 ──
            print("\n== 已知未落地（防止清单变黑洞）==")
            for route, name, _ in KNOWN_PENDING:
                row = next(r for r in rows if r[0] == route)
                if not row[3]:
                    check(False, f"{name}（{route}）已不再报失败 => 请把它从 KNOWN_PENDING 移走")
                else:
                    print(f"  · {name:<10} 仍未落地：{row[3][:3]}")

            # ── 页面已落地：点主按钮必须成功，且接口必须真的返回（不再是"点一下必然 404"）──
            print("\n== 页面已落地（点一下才现形）==")
            # ★ 先造夹具：没有"被向量化过的库"，命中测试只会返空 —— 而空结果与"检索坏了"在读数上一样。
            seeded = seed_hit_test_fixture(token)
            if seeded is None:
                check(False, "命中测试夹具没造出来（建模型/建库/上传/解析/向量化 任一步失败）")
            else:
                kb_id, question = seeded
                print(f"  · 夹具库 = {kb_id}（已向量化），问句 = {question}")

                # ★ 三种模式都要能出结果（ROADMAP 第 10 周的演示判据）。
                #   在页面上下文里调接口 ⇒ 与页面同一条路（同源、带同一个 token）。
                #   ★★ 向量路这里用 threshold=0：**桩模型的向量是 sha256 造的、语义无关**
                #      ⇒ 不同文本之间的余弦相似度普遍接近 0，0.6 的阈值下必然一条不剩
                #      —— 那是桩的性质，不是检索坏了。阈值 0 时它必须能给出候选（证明
                #      "问题→向量→按维度路由到表→排序"这条链真的通了）。
                #      ★ 相关性本身由后端 `RetrievalHitTestTest` 用**构造出的相似度**断言
                #      （那里能把"两路都命中"造出来），这里只验真栈通不通。
                mode_thresholds = {"VECTOR": 0.0, "FULLTEXT": 0.6, "HYBRID": 0.6}
                counts = page.evaluate(
                    """async ([kbId, question, thresholds]) => {
                        const token = localStorage.getItem('access_token') || '';
                        const out = {};
                        for (const mode of ['VECTOR', 'FULLTEXT', 'HYBRID']) {
                          const res = await fetch('/api/v1/retrieval/test', {
                            method: 'POST',
                            headers: {'Content-Type': 'application/json',
                                      'Authorization': 'Bearer ' + token},
                            body: JSON.stringify({question, mode, threshold: thresholds[mode], topN: 5,
                                                  kbIds: [kbId]})
                          });
                          const json = await res.json();
                          out[mode] = {status: res.status, items: (json.data?.items || []).length,
                                       message: json.message || ''};
                        }
                        return out;
                    }""",
                    [kb_id, question, mode_thresholds],
                )
                for mode, result in counts.items():
                    # ★ 向量路的判据与另两路**刻意不同**（不是放宽，是它在这个环境里不可断言）：
                    #   桩模型的向量是 sha256 造的、**语义无关** ⇒ 问题向量与分段向量的余弦距离
                    #   都在 1.0 附近（实测：自比 0.0000，互比 0.99/1.02/1.05）⇒ 相似度 ≈0±噪声，
                    #   阈值 0 也常常一条都不过。那是桩的性质，**不是检索坏了**：
                    #   后端日志实测 `命中=4`（向量检索确实跑到了、也返回了候选）。
                    #   ⇒ 这里断言"接口通 + 真的走到了"（200），把**相关性**留给
                    #     `RetrievalHitTestTest`（它用构造出的指定相似度断言，包括 RRF 的可观测特征）。
                    if mode == "VECTOR":
                        check(
                            result["status"] == 200,
                            f"VECTOR 模式接口可用（status={result['status']} items={result['items']}"
                            f" —— 桩向量语义随机，命中数不作断言；相关性由 RetrievalHitTestTest 守）",
                        )
                    else:
                        check(
                            result["status"] == 200 and result["items"] >= 1,
                            f"{mode} 模式命中 ≥1 条（threshold={mode_thresholds[mode]}"
                            f" status={result['status']} items={result['items']}"
                            f"{' msg=' + result['message'] if result['message'] else ''}）",
                        )

                for route, name, action, api_fragment in PAGE_LIVE:
                    failed_calls.clear()
                    console_errors.clear()
                    ok_calls.clear()
                    page.goto(f"{BASE}/#{route}?kbId={kb_id}", wait_until="domcontentloaded")
                    page.wait_for_timeout(2500)
                    try:
                        page.fill(
                            "input[placeholder*='测试问题'], input[placeholder*='问题']", question
                        )
                        page.click(f"button:has-text('{action}')")
                        page.wait_for_timeout(4000)
                    except Exception as exc:  # noqa: BLE001
                        check(False, f"{name}（{route}）点「{action}」这一步做不下去：{str(exc)[:60]}")
                        continue
                    check(
                        not failed_calls,
                        f"{name}（{route}）点「{action}」没有失败接口 —— 实际：{sorted(set(failed_calls))[:3]}",
                    )
                    check(
                        not console_errors,
                        f"{name}（{route}）点「{action}」控制台无错误 —— 实际：{console_errors[:2]}",
                    )
                    # ★ 核心判据：接口**真的返回了 2xx**。只判"没报错"不够 ——
                    #   页面完全可能因为别的原因没发这个请求，那时"没报错"照样成立。
                    success = [c for c in dict.fromkeys(ok_calls) if api_fragment in c]
                    check(
                        bool(success),
                        f"{name}（{route}）点「{action}」后 {api_fragment} 真的返回了 2xx"
                        f" —— 实际：{sorted(set(ok_calls))[:4]}",
                    )

                    # ★ 第 12 周（0.4.0）：命中测试页必须把策略轨迹**显示出来**。
                    #   为什么这一条要进闸门：`trace` 是"这一层到底做了什么"的唯一凭据，
                    #   而它从回包到界面这一段没有任何自动化断言 —— 回包里有、界面上没有，
                    #   表现就是"这周的答案莫名变差了，而没人知道当时降级了"（10 §6.3）。
                    if route == "/knowledge/hittest":
                        body_text = page.evaluate("() => document.body.innerText")
                        check(
                            "查询理解" in body_text,
                            f"{name}（{route}）结果区显示了策略轨迹（界面文案里含「查询理解」）",
                        )
                        # ★ 第 14 周（0.4.2）：多跳那一层的两个数（轮次 + 停止原因）也必须看得见。
                        #   ROADMAP 第 14 周的演示判据就是"埋点看到轮次与停止原因"，而
                        #   "trace 字段回到了前端、界面上却没显示"正是那种"接口对了、功能没做到"的失败
                        #   （trace 的字段名改了而页面没跟着改，同样会在这里变红）。
                        check(
                            "多跳" in body_text and "停止" in body_text,
                            f"{name}（{route}）结果区显示了多跳的轮次与停止原因（界面文案含「多跳」「停止」）",
                        )

                # 收尾：清掉夹具库（与其它闸门同口径：闸门不留垃圾数据）
                # ★ 排障时用 KEEP_FIXTURE=1 保留它 —— 否则"为什么检索返空"这种问题
                #   连现场都被自己清掉了（本次就是靠它查出向量路返空的真因）。
                if os.environ.get("KEEP_FIXTURE"):
                    print(f"  · KEEP_FIXTURE=1 ⇒ 保留夹具库 {kb_id} 供排障")
                else:
                    st, _ = api("DELETE", f"/knowledge-bases/{kb_id}", token)
                    print(f"  · 已清理夹具库（{st}）")
        finally:
            print("\n== 明细（失败接口 / 403 / 控制台）==")
            for route, name, tier, fails, errs, forbids in rows:
                print(f"  {route:<24} {name:<10} [{tier}]")
                for f in fails[:6]:
                    print(f"      接口 [NG] {f}")
                for f in forbids[:4]:
                    print(f"      接口 [403] {f}")
                for e in errs:
                    print(f"      控制台 [NG] {e}")
            browser.close()

    bad = [m for c, m in ok if not c]
    print("=" * 68)
    print(f"通过 {len(ok) - len(bad)}/{len(ok)}")
    for m in bad:
        print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
