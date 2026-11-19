"""提示词评测链路（ROADMAP §11.63）的一条命令验收。

跑法：
    python scripts/prompt_eval_check.py                 # 自动挑第一个简单应用
    python scripts/prompt_eval_check.py --app <appId>    # 指定执行环境

它验的是**这一条链**：建集 → 加题 → 起运行 → 轮询到完成 → 逐题评分 → 胜率。
每步都断言，失败时说清是哪一步、什么状态码、响应前 200 字 —— 不抛栈。

三条纪律（与 scripts/smoke_check.py 同口径）：
1. **先用桩模型**。跑评测会真的调用 LLM，`debugCompare` 用的是应用配置里的模型；
   若那个应用绑的不是桩模型，脚本默认拒绝跑（`--allow-real-model` 才放行），
   因为"拿真模型验链路"会花钱、也会让读数不稳定。
2. **表还没建就明说**。`as_prompt_eval_*` 四张表来自 `V114`，后端没重启时不存在 ——
   这时要给出「迁移未执行」而不是一个 500 堆栈。
3. **跑完自己清理**：删掉建的评测集（级联删题与运行）。
"""

import json
import sys
import time
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:18088/api/v1"
TIMEOUT = 25


def call(path, token=None, method="GET", body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except urllib.error.URLError as e:
        return 0, f"连不上 {BASE}：{e}"


def login(username, password):
    st, txt = call("/auth/login", method="POST", body={"username": username, "password": password})
    if st != 200:
        raise SystemExit(f"登录失败（{st}）：{txt[:200]}")
    return json.loads(txt)["data"]["accessToken"]


def data(txt):
    return json.loads(txt).get("data")


def arg(name, default=None):
    if name in sys.argv:
        return sys.argv[sys.argv.index(name) + 1]
    return default


def fail(step, st, txt, hint=""):
    print(f"[FAIL] {step} ⇒ {st}")
    print(f"       响应：{txt[:200]}")
    if hint:
        print(f"       {hint}")
    return 1


def main():
    token = login("admin", "admin")
    print(f"已登录 admin（{BASE}）")

    # ① 表在不在：这一步能一次说清「迁移没执行」
    st, txt = call("/prompt-configs?kind=ROLE&page=1&size=1", token)
    if st != 200:
        return fail("读提示词列表", st, txt)
    configs = data(txt)["list"]
    if not configs:
        print("[SKIP] 库里没有角色指令，先建一条再跑")
        return 2
    config = configs[0]
    print(f"提示词：{config['roleName']}（{config['id']}）")

    st, txt = call(f"/prompt-configs/{config['id']}/eval-sets", token)
    if st == 404 and "eval-sets" in txt:
        # 路由表里没有这个端点 ⇒ 进程还是旧代码。它比"表不存在"更早出现，所以先判它。
        print("[SKIP] 后端路由里没有评测端点 —— 后端还没重启（重启会同时执行 V113/V114）")
        print(f"       原始响应：{txt[:160]}")
        return 2
    if st == 500 and ("as_prompt_eval" in txt or "does not exist" in txt):
        print("[SKIP] as_prompt_eval_* 四张表不存在 —— 后端还没执行 V114（重启后端即可）")
        print(f"       原始响应：{txt[:160]}")
        return 2
    if st != 200:
        return fail("读评测集（这就是 V114 若未执行会露出的那一步）", st, txt)

    # ② 执行环境：必须有应用，且模型最好是桩
    app_id = arg("--app")
    if not app_id:
        st, txt = call("/assistants?page=1&size=20", token)
        if st != 200:
            return fail("读应用列表", st, txt)
        apps = data(txt)["list"]
        simple = [a for a in apps if a.get("type") == "SIMPLE"]
        if not simple:
            print("[SKIP] 没有简单应用可用作执行环境")
            return 2
        app = simple[0]
        app_id = app["id"]
        print(f"执行环境：{app['name']}（{app_id}）· 模型 {app.get('modelName') or app.get('modelId')}")

    if "--allow-real-model" not in sys.argv:
        st, txt = call(f"/assistants/{app_id}", token)
        if st == 200:
            model_name = (data(txt) or {}).get("modelName") or ""
            if model_name and "桩" not in model_name and "stub" not in model_name.lower():
                print(f"[SKIP] 这个应用绑的模型看着不是桩（{model_name}）")
                print("       跑评测会真的调模型：先把它切成桩，或加 --allow-real-model 明确放行")
                return 2

    # ③ 建集 → 加题
    set_name = f"链路验收-{int(time.time())}"
    st, txt = call(f"/prompt-configs/{config['id']}/eval-sets", token, "POST", {"name": set_name})
    if st not in (200, 201):
        return fail("建评测集", st, txt)
    set_id = data(txt)["id"]
    print(f"评测集：{set_name}（{set_id}）")

    questions = ["这份合同的违约责任条款对我方是否对等？", "如果对方迟延交付，我们能主张什么？"]
    for q in questions:
        st, txt = call(f"/prompt-eval-sets/{set_id}/items", token, "POST", {"question": q})
        if st not in (200, 201):
            call(f"/prompt-eval-sets/{set_id}", token, "DELETE")
            return fail("加题", st, txt)

    # ④ 起运行（草稿 = 当前正文 + 一句，确保与已保存版不同）
    draft = config["callWord"] + "\n另外：给结论时先说'结论'两个字。"
    st, txt = call(f"/prompt-eval-sets/{set_id}/runs", token, "POST", {"appId": app_id, "draftText": draft})
    if st not in (200, 201):
        call(f"/prompt-eval-sets/{set_id}", token, "DELETE")
        return fail("起运行", st, txt)
    run_id = data(txt)["id"]
    print(f"运行：{run_id}")

    # ⑤ 轮询到完成
    deadline = time.time() + 180
    run = None
    while time.time() < deadline:
        st, txt = call(f"/prompt-eval-runs/{run_id}", token)
        if st != 200:
            call(f"/prompt-eval-sets/{set_id}", token, "DELETE")
            return fail("查运行", st, txt)
        run = data(txt)
        if run["status"] != "RUNNING":
            break
        time.sleep(2)
    if not run or run["status"] == "RUNNING":
        call(f"/prompt-eval-sets/{set_id}", token, "DELETE")
        return fail("等运行完成", 0, "180 秒还没跑完（题多或模型慢）")

    if run["status"] == "FAILED":
        call(f"/prompt-eval-sets/{set_id}", token, "DELETE")
        return fail("运行失败", 0, run.get("failReason") or "(无原因)")

    answers = run["answers"]
    sides = {"SAVED": 0, "DRAFT": 0}
    for a in answers:
        sides[a["side"]] = sides.get(a["side"], 0) + 1
    expect = len(questions)
    ok_answers = sides.get("SAVED") == expect and sides.get("DRAFT") == expect
    print(f"[{'PASS' if ok_answers else 'FAIL'}] 每道题两版都有答案 ⇒ SAVED {sides.get('SAVED')} / DRAFT {sides.get('DRAFT')}（期望各 {expect}）")
    if not ok_answers:
        call(f"/prompt-eval-sets/{set_id}", token, "DELETE")
        return 1

    # ⑥ 评分 → 胜率：第 1 题 BETTER、第 2 题 SAME ⇒ (1 + 0.5) / 2 = 0.75
    item_ids = [a["itemId"] for a in answers if a["side"] == "DRAFT"]
    seen, ordered = set(), []
    for i in item_ids:
        if i not in seen:
            seen.add(i)
            ordered.append(i)
    for item_id, verdict in zip(ordered, ["BETTER", "SAME"]):
        st, txt = call(f"/prompt-eval-runs/{run_id}/items/{item_id}/rating", token, "PUT", {"verdict": verdict, "note": None})
        if st != 200:
            call(f"/prompt-eval-sets/{set_id}", token, "DELETE")
            return fail("评分", st, txt)

    st, txt = call(f"/prompt-eval-runs/{run_id}", token)
    if st != 200:
        call(f"/prompt-eval-sets/{set_id}", token, "DELETE")
        return fail("查汇总", st, txt)
    summary = data(txt)["summary"]
    rate = summary["winRate"]
    ok_rate = rate is not None and abs(rate - 0.75) < 1e-9
    print(f"[{'PASS' if ok_rate else 'FAIL'}] 胜率 ⇒ {rate}（期望 0.75；更好 {summary['better']} · 差不多 {summary['same']} · 更差 {summary['worse']}，已评 {summary['ratedItems']}/{summary['totalItems']}）")

    call(f"/prompt-eval-sets/{set_id}", token, "DELETE")
    print(f"已清理评测集 {set_name}")
    return 0 if ok_rate else 1


if __name__ == "__main__":
    sys.exit(main())
