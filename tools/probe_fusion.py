"""融合（RRF）反例读数（`#293` 的第一步）：同一批问句，一路找得到、融合后反而更靠后。

问的问题只有一个：**HYBRID 的名次，是不是比两路里较好的那一路更差？**

`§11.10.78` 从统计上看到过这件事（HYBRID 的命中率既低于全文路、也不高于向量路），
但统计读数说不清"融合把哪一条按到了什么位置"。本脚本逐条给出三个名次：

    全文名次 / 向量名次 / 融合名次

判「反例」的口径（写死在 `VERDICT` 里，改它要同时改 issue `#293`）：

· 两路都能找到（名次都 > 0）⇒ 融合名次 **不得差于较好那一路** 的下一名；
· 只有一路能找到 ⇒ 融合名次不得比"那一路"再差一名以上；
· 两路都找不到 ⇒ 不判（那是召回问题，不是融合问题）。

默认只跑 `b1b3-clean.json` 里 `rank_core=0` 的那批（21 条）—— 它们才是可疑样本，
全量 195 条跑三遍约 65 分钟，不适合当"看一眼"的工具。`--all` 跑全量。

用法：
    python tools/probe_fusion.py .build/b1b3-clean.json            # 21 条反例候选
    python tools/probe_fusion.py .build/b1b3-clean.json --all      # 195 条全量
    python tools/probe_fusion.py .build/b1b3-clean.json --out .build/fusion.json
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_b1_b3_quality import Client, core_of, norm_loose  # noqa: E402

QA = Path(__file__).resolve().parent.parent / "corpus" / "synthetic" / "qa.jsonl"
MODES = ("FULLTEXT", "VECTOR", "HYBRID")


def load_snippets() -> dict[str, str]:
    out: dict[str, str] = {}
    with QA.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            rec = json.loads(line)
            out[rec["id"]] = rec.get("expected_snippet") or ""
    return out


def rank_of(items: list[dict], want: str) -> int:
    """返回 want 在第几条出现（1 起）；0 = 前 topN 条里没有。"""
    for idx, item in enumerate(items, start=1):
        if want and want in norm_loose(item.get("content") or ""):
            return idx
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("report", help="check_b1_b3_quality.py 产出的 json")
    ap.add_argument("--all", action="store_true", help="跑全量，不只 rank_core=0 的那批")
    ap.add_argument("--topn", type=int, default=20)
    ap.add_argument("--base", default="http://127.0.0.1:18088/api/v1",
                    help="后端 API 基址（含 /api/v1）；CI 里是 5280 那条真栈代理")
    ap.add_argument("--out", default="", help="把逐条读数写成 json")
    args = ap.parse_args()

    report = json.loads(Path(args.report).read_text(encoding="utf-8"))
    kb_id = report["kbId"]
    records = report["records"]
    pool = records if args.all else [r for r in records if r.get("rank_core") == 0]
    snippets = load_snippets()

    client = Client(args.base)
    status, payload = client.call("POST", "/auth/login",
                                  payload={"username": "admin", "password": "admin"})
    if status != 200:
        print(f"★ 登录失败 {status}：{payload}")
        return 2
    token = payload["data"]["accessToken"]

    print(f"库 {kb_id} · 跑 {len(pool)} 条 · topN {args.topn} · 报「反例」= 融合比两路较好者更差\n")
    print(f"{'id':14s} {'全文':>4s} {'向量':>4s} {'融合':>4s}  判定")
    rows: list[dict] = []
    counter = 0
    for rec in pool:
        want = core_of(snippets.get(rec["id"], ""))
        if not want:
            continue
        ranks: dict[str, int] = {}
        for mode in MODES:
            st, body = client.call(
                "POST",
                "/retrieval/test",
                token=token,
                payload={
                    "question": rec["question"],
                    "mode": mode,
                    "threshold": 0.0,
                    "topN": args.topn,
                    "noRefStrategy": "REFUSE",
                    "kbIds": [kb_id],
                },
            )
            items = (body.get("data") or {}).get("items") or [] if st == 200 else []
            ranks[mode] = rank_of(items, want)

        ft, vec, hy = ranks["FULLTEXT"], ranks["VECTOR"], ranks["HYBRID"]
        found = [r for r in (ft, vec) if r > 0]
        if not found:
            verdict = "-（两路都找不到，属召回）"
        elif hy == 0:
            verdict = "★ 反例（融合后前 N 都没有）"
            counter += 1
        elif hy > min(found):
            verdict = f"★ 反例（融合 {hy} > 最好一路 {min(found)}）"
            counter += 1
        else:
            verdict = "ok"
        rows.append({"id": rec["id"], "question": rec["question"], **ranks, "verdict": verdict})
        print(f"{rec['id']:14s} {ft:4d} {vec:4d} {hy:4d}  {verdict}")

    print(f"\n反例 {counter} / {len(rows)} 条")
    if args.out:
        Path(args.out).write_text(
            json.dumps({"kbId": kb_id, "topn": args.topn, "counterexamples": counter,
                        "rows": rows}, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        print(f"逐条读数 → {args.out}")
    return 0 if counter == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
