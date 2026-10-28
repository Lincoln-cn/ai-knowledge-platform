"""B6 的真栈读数 —— **入库吞吐 ≥3 段/秒**（`ROADMAP §2.3`）。

━━━ 这个数字测的是哪一段 ━━━
"入库"在这里是**一条两段式的链**，两段的时间分别记、也分别打印：
  ① **上传 → 解析落地（含切分）**：`POST /knowledge-bases/{id}/documents` ⇒ 文档到
     `PENDING_EMBEDDING`，此时 `segmentCount` 已经是最终段数；
  ② **显式向量化**：`POST /documents/{id}/embedding` ⇒ 到 `SUCCEEDED`。
⇒ 判据断的是**全链**（①+②）的段/秒；②单独也打印一次。
   ★ 只报②会虚好（向量化之外的解析、切分、落库全被藏起来），只报①会漏掉最贵的那一段。
   ★ 两段分开打印的另一半理由：B6 红了要知道慢在哪一段，否则"改一改再跑"就是碰运气。

━━━ 分母与分子 ━━━
- 分子 = `segmentCount`（**库里记的最终段数**，不是文件里的章节数，也不是我数出来的行数）。
- 分母 = 墙上时间。轮询用 **0.1s** 间隔 —— 用 `time.sleep(1)` 会让"1 秒的活"读成"2 秒的活"，
  吞吐被自己的轮询间隔砍掉一半。

━━━ 先清掉桩上可能残留的注入 ━━━
本 job 的闸门是顺序跑的，前面的 S1/S3 会往桩注入 `delay_ms` / `chunk_delay_ms`。**残留的延迟
会把 B6 压成假红**（不是假绿，但同样是坏读数）⇒ 本脚本开头显式复位；复位不成功就**不算读数**
（退出码 1 + 明确消息），而不是硬跑出一个自己都不信的数字。

用法：
    python check_b6_ingest_throughput.py
    AIP_B6_LIMIT=3 AIP_B6_SECTIONS=150 python check_b6_ingest_throughput.py
退出码：0 = 达标；1 = 有红 / 读数不可信 / 环境起不来。
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import time
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"
STUB = os.environ.get("AIP_STUB_URL", "http://127.0.0.1:11434").rstrip("/")

USER = "admin"
PASSWORD = "admin"

#: 判据下限（`ROADMAP §2.3`）。可覆盖只是为了做"故意调高它必须变红"的反证。
LIMIT = float(os.environ.get("AIP_B6_LIMIT", "3"))
#: 夹具规模：每节约 400 字 ⇒ 约 1 段/节 ⇒ 150 节 ≈ 150 段（≥7 批，够摊掉固定开销）。
SECTIONS = int(os.environ.get("AIP_B6_SECTIONS", "150"))

ORG_ROOT = "00000000-0000-4000-8000-000000000001"
DOC_TYPE = "制度"
DOC_CLASSIFICATION = "INTERNAL"

POLL = 0.1
PARSE_DEADLINE = 300.0
EMBED_DEADLINE = 900.0

problems: list[str] = []


def check(ok: bool, message: str) -> None:
    if ok:
        print(f"  ✓ {message}")
    else:
        print(f"  ✗ {message}")
        problems.append(message)


def note(message: str) -> None:
    print(f"  · {message}")


def call(method: str, path: str, token: str = "", body: dict | None = None) -> tuple[int, dict]:
    headers: dict[str, str] = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        API + path,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        method=method,
        headers=headers,
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")
        except Exception:  # noqa: BLE001
            return e.code, {}
    except Exception as e:  # noqa: BLE001
        return -1, {"message": f"{type(e).__name__}: {e}"}


def data_of(body: dict) -> dict:
    value = body.get("data")
    return value if isinstance(value, dict) else {}


def stub_config(payload: dict) -> bool:
    """改桩的行为（它自己的注入口）。★ 不可达 ⇒ False，调用方必须如实记"没读到"。"""
    req = urllib.request.Request(
        STUB + "/__stub/config",
        data=json.dumps(payload).encode("utf-8"),
        method="POST",
        headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status == 200
    except Exception:  # noqa: BLE001
        return False


def login() -> str:
    status, body = call("POST", "/auth/login", "", {"username": USER, "password": PASSWORD})
    token = data_of(body).get("accessToken")
    if status != 200 or not token:
        raise SystemExit(f"登录失败（{status}）：{str(body)[:200]} —— 真栈没起，本跑不算读数")
    return str(token)


def build_markdown(path: str, sections: int) -> int:
    """造一份**真实字节**的 Markdown：每节 ~400 字（< 默认 500 的装箱目标）⇒ 约 1 段/节。

    ★ 为什么不用更短的文本：段太少时"固定开销"（建连接、首批 prepare）会主导读数，
      吞吐被固定开销拖低，测出来的其实是延迟而不是吞吐。
    """
    filler = (
        "本节规定值班交接与巡检记录的填写要求：交接班必须在系统内逐项勾选，"
        "巡检记录需附现场照片与处置结论，异常项要写明发现时间、责任人、"
        "临时处置措施与复查结果。未按期复查的条目由属地运维负责人跟进，"
        "跨区域事项报省级平台协调，所有记录保留不少于三年备查。"
        "交接清单由上一班次填写、下一班次确认，双方签署后不可再改；"
        "如需更正，必须新增一条更正记录并写明原因，不得覆盖原始内容。"
        "巡检项按区域、设备类型与风险等级分组，重点设备每班一次、一般设备每日一次；"
        "发现隐患先做临时隔离，再按流程上报。本节内容为合成文本，不含任何真实单位信息。"
    )
    lines = ["# 入库吞吐夹具（B6）", ""]
    for i in range(1, sections + 1):
        lines.append(f"## 第 {i} 条 巡检与交接规范")
        lines.append("")
        lines.append(f"（第 {i} 节）{filler}")
        lines.append("")
    text = "\n".join(lines)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)
    return len(text)


def upload(token: str, kb_id: str, file_path: str, filename: str) -> tuple[int, dict]:
    """multipart 上传（手写 boundary：不为一个闸门引 requests）。"""
    boundary = "----b6ingest0boundary"
    buf = b""
    for key, value in (
        ("docType", DOC_TYPE),
        ("orgId", ORG_ROOT),
        ("classification", DOC_CLASSIFICATION),
    ):
        buf += (
            f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'
        ).encode("utf-8")
    buf += (
        f'--{boundary}\r\nContent-Disposition: form-data; name="files"; '
        f'filename="{filename}"\r\nContent-Type: text/markdown\r\n\r\n'
    ).encode("utf-8")
    with open(file_path, "rb") as fh:
        buf += fh.read()
    buf += b"\r\n" + f"--{boundary}--\r\n".encode("utf-8")
    req = urllib.request.Request(
        API + f"/knowledge-bases/{kb_id}/documents",
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
        return -1, {"message": f"{type(e).__name__}: {e}"}


def doc_row(token: str, kb_id: str, doc_id: str) -> dict:
    _, body = call("GET", f"/knowledge-bases/{kb_id}/documents?page=1&size=100", token)
    for row in data_of(body).get("list") or []:
        if str(row.get("id")) == doc_id:
            return row
    return {}


def create_embedding_model(token: str) -> str:
    status, body = call(
        "POST",
        "/models",
        token,
        {
            "name": f"B6向量-{int(time.time())}",
            "kind": "EMBEDDING",
            "supplier": "LOCAL_INFERENCE",
            "baseModel": "bge-m3",
            "dimension": 768,
        },
    )
    model_id = str(data_of(body).get("id") or "")
    if status not in (200, 201) or not model_id:
        raise SystemExit(f"建向量模型失败（{status}）：{str(body)[:300]}")
    return model_id


def create_kb(token: str, model_id: str) -> str:
    status, body = call(
        "POST",
        "/knowledge-bases",
        token,
        {
            "name": f"B6吞吐库-{int(time.time())}",
            "category": "制度规范",
            "importType": "LOCAL",
            "embeddingModelId": model_id,
            "maxClassification": "SECRET",
        },
    )
    kb_id = str(data_of(body).get("id") or "")
    if status != 200 or not kb_id:
        raise SystemExit(f"建知识库失败（{status}）：{str(body)[:300]}")
    return kb_id


def main() -> int:
    print(f"== B6 真栈闸门：入库吞吐 ≥{LIMIT:g} 段/秒（{BASE}）==")

    # ★ 第一步就是复位，而不是等到需要的时候 —— 残留的 delay_ms 会让这条闸门红在别人的夹具上。
    if not stub_config({"delay_ms": 0, "chunk_delay_ms": 0, "fail_rate": 0.0}):
        raise SystemExit(
            f"桩不可达（{STUB}/__stub/config）⇒ 无法确认没有延迟注入 ⇒ 吞吐读数不可信，本跑不算"
        )
    note("桩已复位（delay_ms=0 · chunk_delay_ms=0 · fail_rate=0）")

    token = login()
    stamp = str(int(time.time()))
    workdir = tempfile.mkdtemp(prefix="b6ingest-")
    md_path = os.path.join(workdir, f"b6-{stamp}.md")

    model_id = ""
    kb_id = ""
    try:
        model_id = create_embedding_model(token)
        kb_id = create_kb(token, model_id)
        note(f"夹具：库 {kb_id} · 向量模型 {model_id}")

        chars = build_markdown(md_path, SECTIONS)
        note(f"夹具文档：{SECTIONS} 节 · {chars} 字")

        # ── ① 上传 → 解析落地（这一段也算进"入库"）──
        upload_started = time.perf_counter()
        status, uploaded = upload(token, kb_id, md_path, f"B6吞吐-{stamp}.md")
        data = uploaded.get("data")
        doc_id = str(
            (data[0].get("id") if isinstance(data, list) and data else None)
            or (data or {}).get("id")
            or ""
        )
        check(status == 200 and bool(doc_id), f"上传成功（{status}）")

        parse_started = upload_started
        deadline = time.time() + PARSE_DEADLINE
        row: dict = {}
        while time.time() < deadline:
            row = doc_row(token, kb_id, doc_id)
            if row.get("status") in {"PENDING_EMBEDDING", "SEGMENT_FAILED", "EMBEDDING_FAILED"}:
                break
            time.sleep(POLL)
        parse_seconds = time.perf_counter() - parse_started
        landed = row.get("status")
        if not check(landed == "PENDING_EMBEDDING", f"解析落地到 PENDING_EMBEDDING（实际 {landed}）"):
            note(f"failReason={row.get('failReason')} failHint={row.get('failHint')}")
            return 1

        segments = int(row.get("segmentCount") or 0)
        check(segments > 0, f"段数 > 0（实得 {segments} 段）")
        check(
            segments >= 40,
            f"段数 ≥40（实得 {segments} 段）—— 段太少时固定开销主导读数，那测的是延迟不是吞吐",
        )

        # ── ② 显式向量化：从触发到 SUCCEEDED ──
        status, _ = call("POST", f"/documents/{doc_id}/embedding", token, {})
        check(status == 200, f"开始向量化（{status}）")
        embed_started = time.perf_counter()

        deadline = time.time() + EMBED_DEADLINE
        final_status = ""
        processed = 0
        while time.time() < deadline:
            row = doc_row(token, kb_id, doc_id)
            final_status = str(row.get("status") or "")
            processed = int(row.get("processedSegments") or 0)
            if final_status in {"SUCCEEDED", "EMBEDDING_FAILED", "SEGMENT_FAILED"}:
                break
            time.sleep(POLL)
        embed_seconds = time.perf_counter() - embed_started
        total_seconds = time.perf_counter() - upload_started

        check(final_status == "SUCCEEDED", f"向量化终态 SUCCEEDED（实际 {final_status}）")
        check(
            processed == segments,
            f"进度收敛到最后一段（processed={processed} · segmentCount={segments}）",
        )

        # ── 读数 ──
        embed_rate = segments / embed_seconds if embed_seconds > 0 else 0.0
        total_rate = segments / total_seconds if total_seconds > 0 else 0.0
        chars_per_second = chars / total_seconds if total_seconds > 0 else 0.0
        note(
            f"① 上传→解析落地：{parse_seconds:.2f}s · ② 向量化：{embed_seconds:.2f}s · "
            f"全链：{total_seconds:.2f}s"
        )
        note(
            f"向量化吞吐 {embed_rate:.1f} 段/秒 · **全链吞吐 {total_rate:.1f} 段/秒** · "
            f"{chars_per_second:.0f} 字/秒（{segments} 段 / {chars} 字）"
        )
        check(
            total_rate >= LIMIT,
            f"**B6 入库吞吐 ≥{LIMIT:g} 段/秒**（全链实得 {total_rate:.2f} 段/秒；"
            f"只算向量化是 {embed_rate:.2f}）",
        )
    finally:
        if kb_id:
            call("DELETE", f"/knowledge-bases/{kb_id}", token)
        if model_id:
            call("DELETE", f"/models/{model_id}", token)

    if problems:
        print(f"\n结果：红 —— {len(problems)} 条")
        for item in problems:
            print(f"  [FAIL] {item}")
        return 1
    print("\n结果：绿 —— B6 有读数且达标")
    return 0


if __name__ == "__main__":
    sys.exit(main())
