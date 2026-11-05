#!/usr/bin/env python3
"""B1 命中率 / B3 引用准确率读数：拿合成语料跑真栈检索。

为什么要有这个脚本
------------------
`docs/验收台账.md` 里 B1–B3 一直是 ⬜（"等 P1 语料"）。合成语料
（`corpus/synthetic/`）不是 P1，**不能用来出具正式结论**，但它足以回答
一个更小的问题：「检索链路现在能不能把该找到的段找回来」——这个答案不需要
真实业务文档，只需要"问题 ↔ 唯一的答案句"这种可判定的对应关系，而合成语料
的 `qa.jsonl` 正好内建了这一层（每条问题带 `expected_snippet`）。

判据
----
- **B1 = 前 5 条命中里是否有 expected_snippet**（`ROADMAP §二` 的原文口径）。
- **B3 = 第 1 条命中是否既含 snippet、又来自 expected_document**。
  这是引用准确率的**弱化代理**：真正的 B3 要看"引用段落是否支撑那句话"，
  合成语料的锚点句是整段唯一的答案句，所以"该段被召回且排第一"可以当近似。

两种匹配
--------
`exact`（子串原样）、`loose`（去掉空白、全角半角归一后包含）、`core`（再剥掉锚点句的模板前缀）。

★ **为什么要有 `core`**：合成语料的锚点句是模板拼出来的 ——
`就{主体}而言，{断言}`。前半截"就固定资产而言，"不是答案，是引出语；
分段器按 500 字切的时候，这半句很容易落在**上一段**（实测就是这样：命中的段里
有"财务资产部是公司固定资产的归口管理部门"，但没有"就固定资产而言，"）。
三个读数一起看才分得清两件事：**"答案句没被召回"**（core 也低 = 检索问题）
与**"答案句被切断"**（exact 低、core 高 = 分段粒度问题）。把二者混成一个数，
既会低估检索、也会把分段缺陷记到检索头上。

用法
----
    # 灌库（建向量模型 + 建库 + 上传 70 份 + 等解析切分）
    python tools/check_b1_b3_quality.py --stage ingest --kb-name 合成语料-B13

    # 向量化（需要 ollama 里真的有 bge-m3）
    python tools/check_b1_b3_quality.py --stage embed --kb-id <id>

    # 评测
    python tools/check_b1_b3_quality.py --stage eval --kb-id <id> --out .build/b1b3.json
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 上传接口的必填参数（与 `frontend/check_s1_knowledge_chain_e2e.py:69-71` 同源）
ORG_ROOT = "00000000-0000-4000-8000-000000000001"
DOC_TYPE = "制度"
DOC_CLASSIFICATION = "INTERNAL"

_WS = re.compile(r"\s+")
_PUNCT = re.compile(r"[\s，。、；：！？（）()《》〈〉「」『』“”\"'’,.;:!?\-—–_" + "\u3000" + r"]+")


def norm_loose(text: str) -> str:
    """宽松归一：去空白与常见标点，全角数字字母转半角。"""
    s = _PUNCT.sub("", text or "")
    out = []
    for ch in s:
        code = ord(ch)
        if 0xFF01 <= code <= 0xFF5E:
            out.append(chr(code - 0xFEE0))
        else:
            out.append(ch)
    return "".join(out).lower()


_LEAD_IN = re.compile(r"^就[^，]{1,24}而言，")


def core_of(snippet: str) -> str:
    """锚点句的「断言」部分：剥掉模板前缀 `就{主体}而言，`。

    剥不掉的（snippet 本来就没有这个前缀）原样返回 —— 判据不能因为"没匹配上模板"
    而退化成"永远命中"。
    """
    return _LEAD_IN.sub("", snippet or "")


# ──────────────────────────────── HTTP ────────────────────────────────


class Client:
    def __init__(self, base: str) -> None:
        self.base = base.rstrip("/")

    def call(self, method: str, path: str, token: str = "", payload: dict | None = None,
             timeout: int = 120) -> tuple[int, dict]:
        url = self.base + path
        data = None
        headers = {}
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
        except urllib.error.HTTPError as e:
            try:
                return e.code, json.loads(e.read().decode("utf-8") or "{}")
            except Exception:  # noqa: BLE001
                return e.code, {}
        except Exception as e:  # noqa: BLE001
            return -1, {"message": str(e)}

    def login(self, user: str, password: str) -> str:
        """登录，并把令牌与凭据留在实例上 —— 长跑的评测要能在 401 时自己换新令牌。"""
        status, body = self.call("POST", "/auth/login", payload={"username": user, "password": password})
        if status != 200:
            raise SystemExit(f"登录失败：{status} {body}")
        self._user = user
        self._password = password
        self._token = (body.get("data") or {}).get("accessToken") or ""
        return self._token

    def relogin(self) -> str:
        if not getattr(self, "_user", ""):
            raise SystemExit("没有留存凭据，无法自动重新登录")
        print("  · 令牌失效，重新登录", flush=True)
        return self.login(self._user, self._password)

    def authed(self, method: str, path: str, payload: dict | None = None,
               timeout: int = 120, retries: int = 2) -> tuple[int, dict]:
        """带令牌调用，401 自动重新登录、瞬时失败自动重试。

        ★ 为什么需要它：215 条全量评测要跑一个多小时，令牌会在中途过期 ——
        第一版没处理，195 条里 178 条是 `401 未认证`，读数因此只剩 20 条有效。
        一次长跑的读数被一个"跑得久"本身吞掉，是最没道理的一种失败。
        """
        last: tuple[int, dict] = (-1, {})
        for attempt in range(retries + 1):
            status, body = self.call(method, path, getattr(self, "_token", ""), payload, timeout)
            if status == 401:
                self.relogin()
                continue
            if status == 200:
                return status, body
            last = (status, body)
            # 后端 400 里有一类是"向量化超时"，它是瞬时的，重试一次往往就好了
            if status == 400 and "timeout" in json.dumps(body, ensure_ascii=False).lower() and attempt < retries:
                time.sleep(2)
                continue
            if status == -1 and attempt < retries:
                time.sleep(2)
                continue
            break
        return last

    def upload(self, token: str, kb_id: str, path: Path, filename: str) -> tuple[int, dict]:
        boundary = "----b13" + uuid.uuid4().hex
        # ★ docType / classification / orgId 是上传接口的必填参数（缺一个就 400）。
        #   台账只写"上传成功"是看不见这一层的 —— `check_s1` 里同样三件。
        buf = b""
        for key, value in (
            ("docType", DOC_TYPE),
            ("orgId", ORG_ROOT),
            ("classification", DOC_CLASSIFICATION),
        ):
            buf += (
                f"--{boundary}\r\nContent-Disposition: form-data; name=\"{key}\"\r\n\r\n{value}\r\n"
            ).encode("utf-8")
        buf += (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; "
            f"filename=\"{filename}\"\r\nContent-Type: application/octet-stream\r\n\r\n"
        ).encode("utf-8")
        buf += path.read_bytes()
        buf += b"\r\n" + f"--{boundary}--\r\n".encode("utf-8")
        req = urllib.request.Request(
            self.base + f"/knowledge-bases/{kb_id}/documents",
            data=buf,
            method="POST",
            headers={
                "Content-Type": f"multipart/form-data; boundary={boundary}",
                "Authorization": f"Bearer {token}",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=300) as resp:
                return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
        except urllib.error.HTTPError as e:
            try:
                return e.code, json.loads(e.read().decode("utf-8") or "{}")
            except Exception:  # noqa: BLE001
                return e.code, {}
        except Exception as e:  # noqa: BLE001
            return -1, {"message": str(e)}


# ──────────────────────────────── 阶段 ────────────────────────────────


def documents_of(kb_id: str, token: str) -> list[dict]:
    """库里全部文档（分页取尽）。"""
    out: list[dict] = []
    page = 1
    while True:
        status, body = CLIENT.call(
            "GET", f"/knowledge-bases/{kb_id}/documents?page={page}&size=100", token
        )
        if status != 200:
            raise SystemExit(f"读文档列表失败：{status} {str(body)[:200]}")
        data = body.get("data") or {}
        rows = data.get("list") or []
        out.extend(rows)
        total = int(data.get("total") or 0)
        if len(out) >= total or not rows:
            break
        page += 1
    return out


def stage_ingest(token: str, args: argparse.Namespace) -> str:
    stamp = str(int(time.time()))
    kb_id = args.kb_id
    if kb_id:
        print(f"  · 复用已有知识库 {kb_id}")
    else:
        status, created = CLIENT.call(
            "POST",
            "/models",
            token,
            {
                "name": f"B13向量-{stamp}",
                "kind": "EMBEDDING",
                "supplier": "LOCAL_INFERENCE",
                "baseModel": args.embed_model,
                "dimension": args.dimension,
            },
        )
        model_id = (created.get("data") or {}).get("id") or ""
        if status != 200 or not model_id:
            raise SystemExit(f"建向量模型失败：{status} {str(created)[:200]}")
        print(f"  · 向量模型 {model_id}（{args.embed_model} · {args.dimension} 维）")

        status, kb = CLIENT.call(
            "POST",
            "/knowledge-bases",
            token,
            {
                "name": f"{args.kb_name}-{stamp}",
                "category": "内部控制",
                "importType": "LOCAL",
                "embeddingModelId": model_id,
                "maxClassification": "INTERNAL",
            },
        )
        kb_id = (kb.get("data") or {}).get("id") or ""
        if status != 200 or not kb_id:
            raise SystemExit(f"建库失败：{status} {str(kb)[:200]}")
        print(f"  · 知识库 {kb_id}")

    docs = sorted(p for p in Path(args.docs).iterdir() if p.is_file())
    if args.limit_docs:
        docs = docs[: args.limit_docs]
    print(f"  · 待上传 {len(docs)} 份")

    failed: list[str] = []
    started = time.time()
    for i, path in enumerate(docs, 1):
        status, body = CLIENT.upload(token, kb_id, path, path.name)
        ok = status == 200 and bool((body.get("data") or []))
        if not ok:
            failed.append(f"{path.name}: {status} {str(body)[:120]}")
        if i % 10 == 0 or i == len(docs):
            print(f"  · 上传 {i}/{len(docs)}（{time.time() - started:.0f}s）")
    if failed:
        print(f"  ✗ 上传失败 {len(failed)} 份：")
        for line in failed[:5]:
            print("    " + line)

    # 等解析 + 切分落地（status 到 PENDING_EMBEDDING 即表示切分完成）
    print("  · 等解析切分落地…")
    deadline = time.time() + args.ingest_timeout
    while time.time() < deadline:
        rows = documents_of(kb_id, token)
        done = sum(1 for r in rows if (r.get("status") or "") in {"PENDING_EMBEDDING", "SUCCEEDED", "EMBEDDING"})
        bad = [r for r in rows if (r.get("status") or "") in {"SEGMENT_FAILED", "PARSE_FAILED"}]
        print(f"    {done}/{len(rows)} 已切分，失败 {len(bad)}")
        if len(rows) >= len(docs) and done + len(bad) >= len(docs):
            break
        time.sleep(10)
    rows = documents_of(kb_id, token)
    total_segments = sum(int(r.get("segmentCount") or 0) for r in rows)
    print(f"  · 落地：{len(rows)} 份 · 共 {total_segments} 段")
    print(f"\nKB_ID={kb_id}")
    return kb_id


def stage_embed(token: str, kb_id: str, args: argparse.Namespace) -> None:
    rows = documents_of(kb_id, token)
    todo = [r for r in rows if (r.get("status") or "") != "SUCCEEDED"]
    print(f"  · 待向量化 {len(todo)}/{len(rows)} 份", flush=True)
    started = time.time()
    for i, row in enumerate(todo, 1):
        doc_id = row.get("id")
        status, _ = CLIENT.call("POST", f"/documents/{doc_id}/embedding", token, {})
        if status != 200:
            print(f"    ✗ {row.get('name')} 触发失败 {status}", flush=True)
            continue
        deadline = time.time() + 1800
        while time.time() < deadline:
            _, body = CLIENT.call("GET", f"/knowledge-bases/{kb_id}/documents?page=1&size=200&keyword=", token)
            cur = next((x for x in ((body.get("data") or {}).get("list") or []) if x.get("id") == doc_id), {})
            st = cur.get("status") or "?"
            if st in {"SUCCEEDED", "EMBEDDING_FAILED", "SEGMENT_FAILED"}:
                break
            time.sleep(2)
        print(f"    [{i}/{len(todo)}] {row.get('name')} → {st}（{time.time() - started:.0f}s）", flush=True)
        if st != "SUCCEEDED":
            print(f"      failReason={cur.get('failReason')} failHint={cur.get('failHint')}")


def stage_eval(token: str, kb_id: str, args: argparse.Namespace) -> int:
    qa = [json.loads(line) for line in Path(args.qa).read_text(encoding="utf-8").splitlines() if line.strip()]
    if args.limit:
        qa = qa[: args.limit]
    print(f"  · 问题 {len(qa)} 条 · 模式 {args.mode} · topN {args.topn} · 阈值 {args.threshold}")

    records: list[dict] = []
    started = time.time()
    # ★ 215 条 × 单条几秒 ≈ 十几分钟。此前**一条进度都不打**，
    #   于是在外部看来和"卡死"完全一样（`#280` 那轮真的等了一个半小时）。
    print(f"  开始：{len(qa)} 条，每 10 条报一次进度与预计剩余", flush=True)
    for i, item in enumerate(qa, 1):
        status, body = CLIENT.authed(
            "POST",
            "/retrieval/test",
            {
                "question": item["question"],
                "mode": args.mode,
                "threshold": args.threshold,
                "topN": args.topn,
                "noRefStrategy": "REFUSE",
                "kbIds": [kb_id],
            },
        )
        if status != 200:
            records.append({"id": item["id"], "error": f"{status} {str(body)[:120]}"})
            print(f"  [{i}/{len(qa)}] {item['id']} 失败 {status}")
            continue
        hits = (body.get("data") or {}).get("items") or []

        if i % 10 == 0 or i == len(qa):
            spent = time.time() - started
            per = spent / i
            left = per * (len(qa) - i)
            print(f"  [{i}/{len(qa)}] 已用 {spent/60:.1f} 分钟，预计还剩 {left/60:.1f} 分钟", flush=True)
        want_exact = item["expected_snippet"]
        want_loose = norm_loose(want_exact)
        want_core = core_of(want_exact)
        want_core_loose = norm_loose(want_core)

        def find(pred) -> int:
            for h in hits:
                if pred(h.get("content") or ""):
                    return int(h.get("rank") or 0)
            return 0

        rank_exact = find(lambda c: want_exact in c)
        rank_loose = find(lambda c: want_loose and want_loose in norm_loose(c))
        rank_core = find(lambda c: want_core and want_core_loose in norm_loose(c))
        top1 = hits[0] if hits else {}
        records.append(
            {
                "id": item["id"],
                "question": item["question"],
                "expected_document": item["expected_document"],
                "top1_doc": top1.get("docName"),
                "rank_exact": rank_exact,
                "rank_loose": rank_loose,
                "rank_core": rank_core,
                "hits": len(hits),
                "hit5_exact": 0 < rank_exact <= 5,
                "hit5_loose": 0 < rank_loose <= 5,
                "hit5_core": 0 < rank_core <= 5,
                "b3_core": rank_core == 1 and top1.get("docName") == item["expected_document"],
                "doc_top1_right": top1.get("docName") == item["expected_document"],
            }
        )
        if i % 25 == 0 or i == len(qa):
            print(f"  · 评测 {i}/{len(qa)}（{time.time() - started:.0f}s）")

    ok = [r for r in records if "error" not in r]
    n = max(1, len(ok))
    b1_exact = sum(1 for r in ok if r["hit5_exact"])
    b1_loose = sum(1 for r in ok if r["hit5_loose"])
    b1_core = sum(1 for r in ok if r["hit5_core"])
    b3_core = sum(1 for r in ok if r["b3_core"])
    doc_top1 = sum(1 for r in ok if r["doc_top1_right"])
    print(f"\n== B1/B3 读数（{args.mode} · topN {args.topn} · 阈值 {args.threshold}）==")
    print(f"  · 有效 {len(ok)} 条（报错 {len(records) - len(ok)}）")
    print(f"  · **B1 命中率（前 5，core）** = {b1_core}/{n} = **{b1_core / n:.1%}**")
    print(f"  · B1 命中率（前 5，exact 整句） = {b1_exact}/{n} = {b1_exact / n:.1%}")
    print(f"  · B1 命中率（前 5，loose 去标点） = {b1_loose}/{n} = {b1_loose / n:.1%}")
    print(f"  · **B3 引用准确率（第 1 条命中且同文档，core）** = {b3_core}/{n} = **{b3_core / n:.1%}**")
    print(f"  · 旁证：第 1 条命中就是正确文档 = {doc_top1}/{n} = {doc_top1 / n:.1%}")
    misses = [r for r in ok if not r["hit5_core"]][:6]
    if misses:
        print("  · 前 5 空召回样例：")
        for m in misses:
            print(f"    {m['id']} {m['question'][:32]}… → 期望 {m['expected_document'][:20]} · 实得 top1 {str(m['top1_doc'])[:20]}")
    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(
            json.dumps(
                {
                    "kbId": kb_id,
                    "mode": args.mode,
                    "topn": args.topn,
                    "threshold": args.threshold,
                    "corpus": "corpus/synthetic（合成语料，不是 P1）",
                    "b1_core": b1_core / n,
                    "b1_exact": b1_exact / n,
                    "b1_loose": b1_loose / n,
                    "b3_core": b3_core / n,
                    "doc_top1": doc_top1 / n,
                    "total": len(ok),
                    "records": records,
                },
                ensure_ascii=False,
                indent=1,
            ),
            encoding="utf-8",
        )
        print(f"  · 明细写到 {out}")
    return 0 if b1_core / n >= 0.70 else 1


def main() -> int:
    global CLIENT
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:5280/api/v1")
    ap.add_argument("--stage", choices=["ingest", "embed", "eval"], default="eval")
    ap.add_argument("--kb-id", default="")
    ap.add_argument("--kb-name", default="合成语料-B13")
    ap.add_argument("--docs", default=str(ROOT / "corpus/synthetic/documents"))
    ap.add_argument("--qa", default=str(ROOT / "corpus/synthetic/qa.jsonl"))
    ap.add_argument("--limit-docs", type=int, default=0)
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--mode", default="HYBRID")
    ap.add_argument("--topn", type=int, default=10)
    ap.add_argument("--threshold", type=float, default=0.6)
    ap.add_argument("--embed-model", default="bge-m3")
    ap.add_argument("--dimension", type=int, default=1024)
    ap.add_argument("--ingest-timeout", type=int, default=3600)
    ap.add_argument("--out", default="")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="admin")
    args = ap.parse_args()

    CLIENT = Client(args.base)
    token = CLIENT.login(args.user, args.password)
    print(f"== 阶段 {args.stage}（{args.base}）==")
    if args.stage == "ingest":
        stage_ingest(token, args)
        return 0
    if not args.kb_id:
        raise SystemExit("--kb-id 必填（embed / eval）")
    if args.stage == "embed":
        stage_embed(token, args.kb_id, args)
        return 0
    return stage_eval(token, args.kb_id, args)


if __name__ == "__main__":
    sys.exit(main())
