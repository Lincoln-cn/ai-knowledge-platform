"""检索执行计划闸门（0.6.0）—— 断两件事，不断耗时。

┌──────────────────────────────────────────────────────────────────────────┐
│ 为什么有这个闸门（2026-11-11 实录）                                       │
│                                                                          │
│ 探 0.6.0 的检索读数时，我把 join 写错了（`v.id = s.id`，真实链路不是这么连）│
│ 结果：HNSW 扫了 61 个候选、回表 61 次全部失败、**返回 0 行且查询"成功"**、  │
│ 耗时 128ms 全浪费。sql 一丁点错都没报。                                   │
│                                                                          │
│ 这与 `#313`（表格竖线被转义 ⇒ `is_table` 全 0）是同一族：不报错、结果空。  │
│ ⇒ 两条断言：                                                             │
│   ① 向量排序必须走 `Index Scan using …hnsw`（走 Seq Scan 说明过滤条件把    │
│      索引废掉了，召回会变成"扫全表再排序"）                                │
│   ② 返回行数不能是 0（防"返回空且不报错"）                                 │
│                                                                          │
│ ★ 故意**不**比对耗时阈值。耗时与机器、缓存、并发都有关，CI 上做绝对阈值    │
│   只会得到一个经常误报的闸门 —— 而一个会误报的闸门比没有闸门更危险。       │
└──────────────────────────────────────────────────────────────────────────┘

用法：
    python tools/check_retrieval_plan.py --psql "docker exec -i aip-postgres psql -U aiplatform -d aiplatform"
    python tools/check_retrieval_plan.py --self-check
"""

import argparse
import re
import shlex
import subprocess
import sys

# 三条探针：(名字, SQL, 期望)
#
# ★ 为什么用 `vec_embedding_384`：合成语料是 e5-small 384 维，向量落在这张表。
#   换成维度不对的表会得到"回表 0 行"——那正是本闸门要抓的症状，不是这里要测的东西。
QUERIES = [
    (
        "纯向量排序（无过滤）",
        """
        EXPLAIN (ANALYZE, COSTS OFF)
        SELECT id FROM vec_embedding_384
        ORDER BY embedding <=> (SELECT embedding FROM vec_embedding_384 LIMIT 1)
        LIMIT 10
        """,
        {"must_use_index": "hnsw", "must_have_rows": True},
    ),
    (
        "kb 等值过滤 + 向量排序",
        """
        EXPLAIN (ANALYZE, COSTS OFF)
        SELECT id FROM vec_embedding_384
        WHERE (metadata)::jsonb ->> 'kb_id' IS NOT NULL
        ORDER BY embedding <=> (SELECT embedding FROM vec_embedding_384 LIMIT 1)
        LIMIT 10
        """,
        {"must_use_index": "hnsw", "must_have_rows": True},
    ),
]


class ToolError(Exception):
    """工具自身的问题（连不上库、psql 不在、表不存在）。与"产品问题"分开。"""


def run_psql(psql_cmd: list[str], sql: str) -> str:
    try:
        p = subprocess.run(
            psql_cmd + ["-t", "-A", "-c", sql.strip()],
            capture_output=True,
            text=True,
            timeout=120,
        )
    except FileNotFoundError as e:
        raise ToolError(f"找不到 psql：{e}") from e
    except subprocess.TimeoutExpired as e:
        raise ToolError(f"psql 超时（120s）：{e}") from e
    if p.returncode != 0:
        raise ToolError(f"psql 失败（{p.returncode}）：{(p.stderr or p.stdout).strip()[:400]}")
    return p.stdout


def parse_plan(output: str):
    """从 EXPLAIN 文本里抽出四件事：计划节点名、是否走 hnsw、总的实际行数、执行耗时。"""
    text = output.replace("\r\n", "\n")
    uses_hnsw = "hnsw" in text.lower()
    seq_scan = re.search(r"Seq Scan on vec_embedding", text) is not None
    # LIMIT 那一行的 rows= 是"实际返回条数"，最外层 Limit 节点在文本开头
    outer = re.search(r"Limit\s+\([^)]*rows=(\d+)", text)
    rows = int(outer.group(1)) if outer else None
    ms = re.search(r"Execution Time:\s*([\d.]+)\s*ms", text)
    return {
        "uses_hnsw": uses_hnsw,
        "seq_scan": seq_scan,
        "rows": rows,
        "ms": float(ms.group(1)) if ms else None,
    }


def check_one(psql_cmd, name, sql, expect) -> list[str]:
    """返回失败原因列表（空 = 通过）。"""
    out = run_psql(psql_cmd, sql)
    p = parse_plan(out)
    fails = []
    if expect.get("must_use_index") and not p["uses_hnsw"]:
        hint = "（出现了 Seq Scan —— 过滤条件把向量索引废掉了）" if p["seq_scan"] else ""
        fails.append(f"{name}：没有走 hnsw 索引{hint}")
    if expect.get("must_have_rows"):
        if p["rows"] is None:
            fails.append(f"{name}：读不到实际返回条数（EXPLAIN 格式变了？）")
        elif p["rows"] == 0:
            fails.append(
                f"{name}：返回 0 行却查询成功 —— 与 #313 同族的静默退化，"
                "先看 EXPLAIN 里 HNSW 的 rows= 是不是被回表过滤光了"
            )
    return fails


# ── self-check：证明这个闸门"被测对象坏掉时会红" ──

BROKEN_CASES = [
    (
        "强制关掉索引扫描 ⇒ 必须报「没有走 hnsw」",
        "SET enable_indexscan = off; SET enable_bitmapscan = off; "
        "EXPLAIN (ANALYZE, COSTS OFF) SELECT id FROM vec_embedding_384 "
        "ORDER BY embedding <=> (SELECT embedding FROM vec_embedding_384 LIMIT 1) LIMIT 10",
        {"must_use_index": "hnsw", "must_have_rows": True},
    ),
    (
        "过滤造成回表全灭 ⇒ 必须报「返回 0 行」",
        "EXPLAIN (ANALYZE, COSTS OFF) SELECT id FROM vec_embedding_384 "
        "WHERE (metadata)::jsonb ->> 'kb_id' = '00000000-0000-0000-0000-000000000000' "
        "ORDER BY embedding <=> (SELECT embedding FROM vec_embedding_384 LIMIT 1) LIMIT 10",
        {"must_have_rows": True},
    ),
]


def self_check(psql_cmd) -> int:
    print("=== 自检：每条正证 + 对应的坏条件必须红 ===")
    ok = 0
    total = 0
    for i, (name, sql, expect) in enumerate(QUERIES, 1):
        total += 1
        try:
            fails = check_one(psql_cmd, name, sql, expect)
        except ToolError as e:
            print(f"  {i}. {name} —— 环境问题：{e}")
            continue
        if fails:
            print(f"  {i}. {name} —— ✗ 正证都不过：{fails[0]}")
        else:
            print(f"  {i}. {name} —— ✓")
            ok += 1

    print("=== 坏条件 ===")
    for i, (desc, sql, expect) in enumerate(BROKEN_CASES, 1):
        total += 1
        try:
            fails = check_one(psql_cmd, desc, sql, expect)
        except ToolError as e:
            print(f"  B{i}. {desc} —— 环境问题：{e}")
            continue
        if fails:
            print(f"  B{i}. {desc} —— ✓ 如期报红（{fails[0][:70]}…）")
            ok += 1
        else:
            print(f"  B{i}. {desc} —— ✗ 该红没红，闸门抓不到这个坏条件")

    print(f"\n自检 {ok}/{total}")
    return 0 if ok == total else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--psql",
        default="docker exec -i aip-postgres psql -U aiplatform -d aiplatform",
        help="psql 命令前缀（默认真库容器）",
    )
    ap.add_argument("--self-check", action="store_true")
    args = ap.parse_args()

    psql_cmd = shlex.split(args.psql)
    if args.self_check:
        return self_check(psql_cmd)

    all_fails = []
    for name, sql, expect in QUERIES:
        try:
            p = parse_plan(run_psql(psql_cmd, sql))
        except ToolError as e:
            print(f"✗ {name}：环境问题 —— {e}")
            return 2
        fails = check_one(psql_cmd, name, sql, expect)
        mark = "✓" if not fails else "✗"
        ms = f"{p['ms']:.1f}ms" if p["ms"] is not None else "?"
        rows = p["rows"] if p["rows"] is not None else "?"
        print(f"{mark} {name}：{'走 hnsw' if p['uses_hnsw'] else '未走索引'} · {rows} 行 · {ms}")
        all_fails.extend(fails)

    if all_fails:
        print("\n失败明细：")
        for f in all_fails:
            print(f"  ✗ {f}")
        return 1
    print("\n✓ 检索执行计划正常（向量排序走 hnsw，且返回非空）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
