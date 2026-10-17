"""把 `corpus/documents/` 里的样例铺进一个真知识库，并核对 `corpus/qa.jsonl`。

━━━ 它解决什么问题 ━━━
`ROADMAP §6.1` 要的 P1 语料（≥50 份真实文档 + ≥50 个标注问题）还没到位，但**解析与检索链路
可以先在样例文档上跑通**：把 `corpus/documents/` 上传、等解析、读回 `charCount`，
再用 `corpus/qa.jsonl` 的标注问题去检索，看命中的是不是期望的那份文档。

━━━ 为什么写成脚本而不是塞进 CI 闸门 ━━━
1. `corpus/` **不进 git**（真实语料可能涉密）⇒ CI 里没有这些文件，闸门无处可跑；
2. 所以它是**本机工具**：你起好三个服务后跑一次，拿一份"解析+命中"的读数。

━━━ 前置（按项目纪律：服务由你点启动器起，本脚本只发 HTTP）━━━
- 后端 8080 · 前端 5280 · 桩/Ollama 11434
- 账号 admin / admin（与其它真栈闸门同口径）

用法：
    python scripts/parse_corpus.py                     # 默认 http://localhost:5280
    python scripts/parse_corpus.py --base http://127.0.0.1:5280
    python scripts/parse_corpus.py --keep              # 跑完不删知识库（默认删）
退出码：0 = 全部解析成功且问答对全部命中；1 = 有失败。
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

USER = "admin"
PASSWORD = "admin"
ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / "corpus" / "documents"
QA = ROOT / "corpus" / "qa.jsonl"

# ★ 解析是**异步**的：轮询到终态，不用固定 sleep。
#   本仓已有教训（`#170`）：固定等待是"间歇红"的常见来源。
POLL_INTERVAL_SEC = 2
POLL_TIMEOUT_SEC = 180

# 解析终态。★ 非终态（PENDING/PARSING）继续等；FAILED 是终态但**算失败**。
TERMINAL = {"PARSED", "PARSED_EMPTY", "FAILED", "READY", "DONE"}


def fail_exit(message: str) -> None:
    print(f"  ✗ {message}")
    raise SystemExit(1)


def call(base: str, method: str, path: str, token: str | None = None, body: dict | None = None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        base + "/api/v1" + path,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        method=method,
        headers=headers,
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8") or "{}")
    except Exception as e:  # noqa: BLE001
        return -1, {"message": str(e)}


def upload(base: str, kb_id: str, token: str, path: pathlib.Path, meta: dict[str, str]):
    """真上传（multipart）。

    ★ 必须带文件字节：0.2.1 起「建档即解析」只在带了字节时才发生 ——
      只传 names 的轻量建档不会解析，`charCount` 必然是 0（`check_knowledge_parse.py` 的注释记过这条）。

    ★★ 文件名同时给两种写法（RFC 5987）：`filename="…"` 是 ASCII 兜底，
    `filename*=UTF-8''…` 是给非 ASCII 名的正解。★ 实测踩过：只给裸 UTF-8 字节时，
    服务端按 ISO-8859-1 解会把中文名读成乱码。
    """
    boundary = "----corpusparse0boundary"
    encoded = urllib.parse.quote(path.name, safe="")
    buf = b""
    for k, v in meta.items():
        buf += f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
    buf += (
        f'--{boundary}\r\nContent-Disposition: form-data; name="files"; '
        f'filename="{path.name}"; filename*=UTF-8\'\'{encoded}\r\n'
        f"Content-Type: application/octet-stream\r\n\r\n"
    ).encode()
    buf += path.read_bytes() + b"\r\n"
    buf += f"--{boundary}--\r\n".encode()
    req = urllib.request.Request(
        base + f"/api/v1/knowledge-bases/{kb_id}/documents",
        data=buf,
        method="POST",
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": f"Bearer {token}",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        # ★★ 读错误体本身也会失败：后端 500 时连接可能已被中断（实测
        #   `ConnectionAbortedError: [WinError 10053]`）。第一版没防这个 ⇒
        #   整轮 16 份的上传**崩在第 2 份**，连"哪几份成功"都没拿到。
        try:
            raw = e.read().decode("utf-8", errors="replace")
        except Exception:  # noqa: BLE001
            raw = ""
        try:
            return e.code, json.loads(raw or "{}")
        except json.JSONDecodeError:
            return e.code, {"message": raw[:300] or f"HTTP {e.code}（响应体不可读）"}
    except Exception as e:  # noqa: BLE001
        return -1, {"message": f"{type(e).__name__}: {e}"}


def ensure_embedding_model(base: str, token: str) -> str:
    """找一个可用的 EMBEDDING 模型；没有就建一个（与 `check_knowledge_parse.py` 同口径）。"""
    status, payload = call(base, "GET", "/models?size=100", token)
    rows = (payload.get("data") or {}).get("list") or []
    for row in rows:
        if row.get("kind") == "EMBEDDING" and row.get("status") == "LOADED":
            return row["id"]
    created_status, created = call(
        base,
        "POST",
        "/models",
        token,
        {
            "name": f"语料解析向量-{int(time.time())}",
            "kind": "EMBEDDING",
            "supplier": "LOCAL_INFERENCE",
            "baseModel": "bge-m3",
            "dimension": 1024,
        },
    )
    model_id = (created.get("data") or {}).get("id")
    if not model_id:
        fail_exit(f"没有可用的向量模型，且新建失败（{created_status}）：{str(created)[:200]}")
    return model_id


def wait_parsed(base: str, kb_id: str, token: str) -> list[dict]:
    """轮询文档列表直到全部到终态（或超时）。★ 不用固定 sleep。"""
    deadline = time.time() + POLL_TIMEOUT_SEC
    while True:
        status, payload = call(base, "GET", f"/knowledge-bases/{kb_id}/documents?page=1&size=50", token)
        if status != 200:
            fail_exit(f"读文档列表失败（{status}）：{str(payload)[:200]}")
        rows = (payload.get("data") or {}).get("list") or []

        def status_code(row: dict) -> str:
            """取状态码。

            ★★ 实测踩过：`status` 有时是对象 `{"code": …}`、有时是裸字符串 ⇒
            第一版直接 `(r.get("status") or {}).get("code")` 在裸字符串上会抛
            `AttributeError: 'str' object has no attribute 'get'`，整轮在上传完之后崩掉，
            连"解析结果"都打不出来。
            """
            raw = row.get("status")
            if isinstance(raw, dict):
                return str(raw.get("code") or "")
            return str(raw or "")

        pending = [r for r in rows if status_code(r).upper() not in TERMINAL]
        if not pending:
            return rows
        if time.time() > deadline:
            print(f"  ★ 超时：仍有 {len(pending)} 份未到终态 —— {[r.get('name') for r in pending]}")
            return rows
        time.sleep(POLL_INTERVAL_SEC)


def snippet_in(normalized_haystack: str, snippet: str) -> bool:
    import re

    def norm(t: str) -> str:
        return re.sub(r"\s+", " ", t).strip()

    return norm(snippet) in norm(normalized_haystack)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:5280")
    parser.add_argument("--keep", action="store_true", help="跑完保留知识库（默认删除）")
    args = parser.parse_args()
    base = args.base.rstrip("/")

    print("== 语料解析与问答对核对 ==")
    if not DOCS.is_dir():
        fail_exit(f"找不到语料目录：{DOCS}")

    files = sorted(p for p in DOCS.iterdir() if p.is_file() and not p.name.startswith("_"))
    if not files:
        fail_exit(f"{DOCS} 里没有文档")
    print(f"  语料 {len(files)} 份：{[p.name for p in files]}")

    status, payload = call(base, "POST", "/auth/login", body={"username": USER, "password": PASSWORD})
    if status != 200:
        code = payload.get("code")
        # ★★ 本地最常见的一条失败：后端**开着验证码**（`app.security.captcha.enabled` 默认 true，
        #   等保要求），而本脚本走的是 API 登录、没有人工输码的环节。
        #   CI 里那条后端是带 `--app.security.captcha.enabled=false` 起的（`ci.yml:155`）。
        if code == 1003 or "验证码" in str(payload.get("message", "")):
            fail_exit(
                "后端开着验证码，API 登录过不去（实测返回 1003）。\n"
                "   两种解法，任选其一后**重启后端**：\n"
                "     ① 起后端前设环境变量：  set CAPTCHA_ENABLED=false\n"
                "     ② 或给启动参数加：      --app.security.captcha.enabled=false\n"
                "   ★ 这正是 CI 的做法（见 .github/workflows/ci.yml:155）。\n"
                "   ★ 只在**本地自动化**关它 —— 生产关掉等于放弃等保的一条测评项。"
            )
        fail_exit(f"登录失败（HTTP {status}）：后端没起或账号不对。返回：{str(payload)[:200]}")
    token = payload["data"]["accessToken"]
    print("  已登录（admin）")

    model_id = ensure_embedding_model(base, token)
    ts = int(time.time())
    status, payload = call(
        base,
        "POST",
        "/knowledge-bases",
        token,
        {
            "name": f"语料解析-{ts}",
            "category": "制度规范",
            "description": "corpus/documents 的解析与命中核对",
            "importType": "LOCAL",
            "embeddingModelId": model_id,
            # ★ 给到 SECRET：样例文档的密级不一，库上限低了会在入库时被挡（那是"策略生效"，
            #   不是解析失败 —— 但会把本次核对搅浑）
            "maxClassification": "SECRET",
        },
    )
    kb_id = (payload.get("data") or {}).get("id")
    if not kb_id:
        fail_exit(f"建库失败（{status}）：{str(payload)[:200]}")
    print(f"  已建知识库 {kb_id}")

    meta = {
        "docType": "制度",
        "orgId": "00000000-0000-4000-8000-000000000001",
        "classification": "INTERNAL",
    }
    uploaded: dict[str, str] = {}
    for path in files:
        code, body = upload(base, kb_id, token, path, meta)
        rows = body.get("data") or []
        doc_id = (rows[0] or {}).get("id") if rows else None
        if doc_id:
            uploaded[path.name] = doc_id
            print(f"  ↑ {path.name}  → doc={doc_id}")
        else:
            print(f"  ✗ {path.name}  上传失败（{code}）：{str(body)[:160]}")

    print("\n  等解析（轮询，不靠固定 sleep）…")
    rows = wait_parsed(base, kb_id, token)

    print(f"\n{'文档':40s} {'状态':14s} {'字符数':>8s}  失败原因")
    problems: list[str] = []
    for row in rows:
        status_field = row.get("status")
        code = (status_field.get("code") if isinstance(status_field, dict) else status_field) or "?"
        chars = row.get("charCount") or 0
        reason = row.get("failReason") or ""
        print(f"{str(row.get('name'))[:40]:40s} {str(code)[:14]:14s} {chars:8d}  {str(reason)[:40]}")
        # ★ "0 字符"与"解析失败"是两件事：前者可能是扫描件/空文档，后者有原因。
        if str(code).upper() in {"FAILED"}:
            problems.append(f"{row.get('name')}：解析失败 —— {reason or row.get('failHint') or '无原因'}")
        elif chars == 0:
            problems.append(f"{row.get('name')}：解析出 0 字符（无文字层？）")

    # ── 问答对核对：用标注问题检索，看命中是不是期望的那份文档 ──
    if QA.exists():
        print("\n== 问答对核对（检索，HYBRID，threshold=0）==")
        qa_rows = [json.loads(l) for l in QA.read_text(encoding="utf-8").splitlines() if l.strip()]
        for row in qa_rows:
            doc = row.get("expected_document")
            code, payload = call(
                base,
                "POST",
                "/retrieval/test",
                token,
                {
                    "question": row["question"],
                    # ★ threshold=0：桩的向量语义是随机的，0.6 下必然一条不剩
                    #   （`check_flow_walkthrough.py` 的注释记过这条）。这里只验"链路通 + 命中哪份文档"。
                    "mode": "HYBRID",
                    "threshold": 0,
                    "topN": 5,
                    "kbIds": [kb_id],
                },
            )
            hits = (payload.get("data") or {}).get("list") or (payload.get("data") or {}).get("hits") or []
            names = [str((h or {}).get("docName") or (h or {}).get("documentName") or "") for h in hits]
            hit = doc in names if names else False
            joined = " ".join(str((h or {}).get("content") or "") for h in hits)
            in_text = snippet_in(joined, row["expected_snippet"]) if joined else False
            mark = "✓" if hit else "✗"
            print(f"  {mark} {row['id']} 期望={doc} 命中文档={names[:3]} 片段在命中里={in_text}")
            if not hit:
                problems.append(f"{row['id']}：检索没命中期望文档 {doc}")

    if not args.keep:
        call(base, "DELETE", f"/knowledge-bases/{kb_id}", token)
        print(f"\n  已删除知识库 {kb_id}（--keep 可保留）")
    else:
        print(f"\n  保留知识库 {kb_id}（--keep）")

    print()
    if problems:
        print(f"结果：红 —— {len(problems)} 条")
        for item in problems:
            print("  [FAIL]", item)
        return 1
    print("结果：绿 —— 全部文档解析出正文，问答对全部命中期望文档")
    return 0


if __name__ == "__main__":
    sys.exit(main())
