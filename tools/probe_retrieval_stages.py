"""真栈分段耗时探针：打一次 /retrieval/test，把 stages 打印成表。

用法：python tools/probe_retrieval_stages.py [问句]

★ 它存在的理由：优化之前先看清「时间花在哪」。2026-11-28 第一次跑出来的是
重排 56%、查询理解 36%、检索 15%、扩召回 1% —— 与"召回太慢"的直觉相反，
直接改变了下一步动哪里。回包里的 stages 字段就是给这个脚本用的。
"""

import json
import sys
import time
import urllib.request

BASE = "http://localhost:18088/api/v1"
KB = "52f02f99-2468-46a5-91e9-e85f935c7761"  # 真语料库（20 篇法律/制度）
QUESTION = sys.argv[1] if len(sys.argv) > 1 else "这部法律是什么时候通过、后来又经过几次修改和修订的？"


def post(path, body, token=None, timeout=180):
    data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(BASE + path, data=data, method="POST")
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def main():
    login = post("/auth/login", {"username": "admin", "password": "admin"})
    payload = login.get("data") or {}
    token = payload.get("accessToken") or payload.get("token")

    body = {
        "question": QUESTION,
        "kbIds": [KB],
        "mode": "HYBRID",
        "topN": 10,
        "threshold": 0.6,
        "queryCount": 3,
        "decomposeEnabled": True,
        "maxSubQuestions": 3,
    }
    started = time.time()
    envelope = post("/retrieval/test", body, token)
    data = envelope.get("data") or envelope
    wall = (time.time() - started) * 1000

    stages = data.get("stages") or []
    print("问句：%s" % QUESTION)
    print("库：%s（真语料 20 篇）" % KB)
    print("命中 %d 段 · 路数 %d · 降级 %d" % (
        len(data.get("items") or []),
        (data.get("trace") or {}).get("pathCount", -1),
        (data.get("trace") or {}).get("degradeLevel", -1),
    ))
    print()
    if not stages:
        print("★ 回包里没有 stages —— 后端跑的还是旧类？")
        print("总耗时（顶层 elapsedMs）= %s ms · wall = %.0f ms" % (data.get("elapsedMs"), wall))
        return

    total = data.get("elapsedMs", -1)
    summed = 0
    for stage in stages:
        if stage["name"] == "合计":
            continue
        summed += stage["ms"]
        print("  %-16s %8d ms  %s" % (stage["name"], stage["ms"],
                                      "×%d" % stage["count"] if stage["count"] > 1 else ""))
    print()
    print("  各阶段之和        %8d ms" % summed)
    print("  合计（整次）      %8d ms" % total)
    print("  未单独计的部分    %8d ms   ← 合计 − 各阶段之和" % (total - summed))
    print("  wall（含网络）    %8.0f ms" % wall)


main()
