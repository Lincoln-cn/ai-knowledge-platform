#!/usr/bin/env python3
"""`#280` 的前置取证：那 49 条"文档对、段没进前 5"的答案句，到底在库里长什么样。

不跑检索、不调模型，只读库。要回答三件事：

1. **答案句在哪一段** —— 它在不在库里（存在性），在的话是哪一段、第几段；
2. **那段有多长** —— 是不是"很长的一段里埋了一句话"（检索单元问题）；
3. **答案句有没有被切断** —— 如果一段里只找到半句、另一半在邻段，那是切分粒度问题。

用法：
  python tools/probe_b3_gaps.py                # 全部
  python tools/probe_b3_gaps.py --limit 5      # 只看前 5 条
"""
import argparse
import json
import subprocess
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PSQL = ["docker", "exec", "aip-postgres", "psql", "-U", "aiplatform", "-d", "aiplatform", "-t", "-A", "-F", "|"]


def sql(q):
    # ★ 把 SQL 走 stdin 而不是 -c：Windows 下把含中文的参数交给 docker exec
    #   会在编码转换上出岔子（表现为"查得到、查不到"随机），stdin 是二进制的。
    out = subprocess.run(PSQL, input=q.encode("utf-8"), capture_output=True, text=False)
    if out.returncode != 0:
        raise SystemExit(f"psql 失败：{out.stderr.strip()}")
    return [line.split("|") for line in out.stdout.strip().split("\n") if line.strip()]


def load_gaps(path, kb_id):
    d = json.loads(path.read_text(encoding="utf-8"))
    gaps = [
        r
        for r in d["records"]
        if (not isinstance(r.get("rank_core"), int) or r["rank_core"] == 0)
        and r.get("doc_top1_right")
    ]
    qa = {}
    for line in (ROOT / "corpus" / "synthetic" / "qa.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            row = json.loads(line)
            qa[row["id"]] = row
    return d, gaps, qa


def segments_of(doc_name, kb_id):
    doc = sql(
        "select id from kb_document where kb_id='%s' and name='%s' limit 1" % (kb_id, doc_name.replace("'", "''"))
    )
    if not doc:
        return None, []
    doc_id = doc[0][0]
    rows = sql(
        "select ordinal, char_count, coalesce(title,''), left(replace(content, chr(10), ' '), 60) "
        "from kb_segment where document_id='%s' order by ordinal" % doc_id
    )
    segs = [{"ordinal": int(r[0]), "chars": int(r[1]), "title": r[2], "head": r[3]} for r in rows]
    # ★ 两个坑：① content 里的换行会把 psql 输出切成多行；② content 里可能有 "|"，
    #   用 -F 分隔会被切列。所以这里只取一列，行内换行查询侧就替换掉
    #   （答案句本身不含换行，对匹配没有影响），ordinal 用序号推算。
    rows = sql(
        "select replace(replace(content, chr(10), ' '), chr(13), ' ') "
        "from kb_segment where document_id='%s' order by ordinal" % doc_id
    )
    full = [(i + 1, r[0]) for i, r in enumerate(rows)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--readings", default=str(ROOT / ".build" / "b1b3.json"))
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    d, gaps, qa = load_gaps(Path(args.readings), None or "")
    kb_id = d["kbId"]
    # load_gaps 需要 kb_id 才能查段，先重读一次
    d, gaps, qa = load_gaps(Path(args.readings), kb_id)
    print(f"库 {kb_id}；读数 {d['mode']} topN={d['topn']} 阈值={d['threshold']}")
    print(f"「文档对、段没进前5」= {len(gaps)} 条（全量 {d['total']}）\n")

    if args.limit:
        gaps = gaps[: args.limit]

    exists, broken, long_seg, tabled, titles = 0, 0, 0, 0, Counter()
    not_found = []
    for g in gaps:
        row = qa.get(g["id"], {})
        snippet = (row.get("expected_snippet") or "").strip()
        if not snippet:
            continue
        doc_id, full, segs = segments_of(g["expected_document"], kb_id)
        if not full:
            not_found.append((g["id"], "文档不在库"))
            continue
        hit_at = [o for o, c in full if snippet in c]
        if hit_at:
            exists += 1
            seg = next((s for s in segs if s["ordinal"] == hit_at[0]), None)
            if seg:
                titles[seg["title"]] += 1
                if seg["chars"] > 1200:
                    long_seg += 1
                if "表" in (seg["title"] or "") or seg["head"].lstrip().startswith("|"):
                    tabled += 1
            if args.verbose:
                print(f"  ✓ {g['id']} 第 {hit_at[0]} 段（{seg['chars'] if seg else '?'} 字）{seg['title'][:20] if seg else ''}")
        else:
            # 答案句不在任何一段里 ⇒ 被切断了。取前半句看落在哪
            half = snippet[: max(6, len(snippet) // 2)]
            part = [o for o, c in full if half in c]
            if part:
                broken += 1
                if args.verbose:
                    print(f"  ✗ {g['id']} 答案句被切断：前半句在第 {part[0]} 段，完整句不在任何段")
            else:
                not_found.append((g["id"], "答案句不在库（连前半句也没有）"))

    n = len([g for g in gaps if (qa.get(g["id"], {}).get("expected_snippet") or "").strip()])
    print(f"可判定的 {n} 条里：")
    print(f"  ① 答案句**完整地在某一段**里：{exists}（{exists/n:.1%}）")
    print(f"  ② 答案句**被切断**（前半句在、整句不在）：{broken}（{broken/n:.1%}）")
    print(f"  ③ 连前半句都找不到：{len(not_found)}（{len(not_found)/n:.1%}）")
    if exists:
        print(f"\n① 里落在「长段（>1200 字）」的：{long_seg}（{long_seg/exists:.1%}）")
        print(f"① 里落在「表格段」的：{tabled}（{tabled/exists:.1%}）")
        print("① 按所在段标题分布（前 8）：")
        for t, c in titles.most_common(8):
            print(f"    {c:3d}  {(t or '(无标题)')[:40]}")
    if not_found:
        print("\n找不到的样例：")
        for i, why in not_found[:5]:
            print(f"    {i}  {why}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
