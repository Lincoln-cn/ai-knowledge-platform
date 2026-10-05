"""在**镜像内**跑通完整 OCR 入库链（T249.5 / issue #13）。

为什么要单独验一次：镜像里"装了 tesseract"不等于"入库链真的跑得通" ——
OCR 是系统级依赖，而这条链还要经过 DB、上游桩、Flyway 迁移与异步流水线。
本轮实测的就是它：扫描件在**容器里**被识别、分块、落库。

用法：python verify_image_ocr.py [base]      默认 http://localhost:18080
"""
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:18080").rstrip("/")
API = BASE + "/api/v1"
FIXTURES = Path(__file__).resolve().parent.parent / "backend/src/test/resources/fixtures"

ok: list[tuple[bool, str]] = []


def check(cond: bool, msg: str, detail: str = "") -> bool:
    ok.append((bool(cond), msg))
    print(("  ✓ " if cond else "  ✗ ") + msg + (f"    {detail}" if detail else ""))
    return bool(cond)


def call(method, path, token=None, body=None):
    req = urllib.request.Request(
        API + path,
        data=json.dumps(body).encode() if body is not None else None,
        method=method,
        headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})},
    )
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            return resp.status, json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode() or "{}"
        try:
            return e.code, json.loads(raw)
        except Exception:  # noqa: BLE001
            return e.code, {"raw": raw[:200]}


def main() -> int:
    fixture = FIXTURES / "scanned.pdf"
    if not fixture.exists():
        print(f"★ 夹具不存在：{fixture}")
        return 1

    print("== 0. 登录（容器内的后端） ==")
    st, login = call("POST", "/auth/login", body={"username": "admin", "password": "admin"})
    token = ((login.get("data") or {}).get("accessToken")) or ""
    if not check(st == 200 and bool(token), f"登录成功（{st}）", json.dumps(login, ensure_ascii=False)[:160]):
        return 1

    print("\n== 1. 建库 + 上传扫描件（全部走容器内的后端） ==")
    ts = str(int(time.time()))
    st, created = call(
        "POST",
        "/knowledge-bases",
        token,
        {
            "name": f"镜像OCR库-{ts}",
            "category": "制度规范",
            "description": "镜像内 OCR 端到端验证",
            "icon": "📘",
            "importType": "LOCAL",
            "embeddingModelId": "00000000-0000-0000-0000-000000000001",
            "maxClassification": "INTERNAL",
        },
    )
    kb_id = ((created.get("data") or {}).get("id")) or ""
    if not check(st == 200 and bool(kb_id), f"建库成功（{st}）", str(created)[:160]):
        return 1

    # multipart 上传
    boundary = "----ImageVerify"
    body = bytearray()
    for name, value in (("docType", "制度"), ("department", "采购部"), ("classification", "INTERNAL")):
        body += f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode()
    body += (
        f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="scanned.pdf"\r\n'
        "Content-Type: application/pdf\r\n\r\n"
    ).encode()
    body += fixture.read_bytes()
    body += f"\r\n--{boundary}--\r\n".encode()

    req = urllib.request.Request(
        API + f"/knowledge-bases/{kb_id}/documents",
        data=bytes(body),
        method="POST",
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Authorization": f"Bearer {token}",
        },
    )
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=90) as resp:
        uploaded = json.loads(resp.read().decode() or "{}")
    submit_ms = int((time.time() - t0) * 1000)
    doc_id = ((uploaded.get("data") or [{}])[0]).get("id")
    check(bool(doc_id), f"上传返回文档 id（上传接口耗时 {submit_ms} ms —— 异步，不等 OCR）", str(uploaded)[:160])

    print("\n== 2. 等解析落地（OCR 在容器里跑） ==")
    status = ""
    started = time.time()
    while time.time() - started < 240:
        st, docs = call("GET", f"/knowledge-bases/{kb_id}/documents?page=1&size=20", token)
        rows = ((docs.get("data") or {}).get("list")) or []
        mine = next((d for d in rows if d.get("id") == doc_id), {})
        status = mine.get("status") or ""
        ocr_total = mine.get("ocrTotalPages")
        ocr_done = mine.get("ocrDonePages")
        if status in ("PENDING_EMBEDDING", "SEGMENT_FAILED"):
            break
        time.sleep(1)
    elapsed = round(time.time() - started, 1)

    check(status == "PENDING_EMBEDDING", f"扫描件入库成功（{elapsed}s，状态 {status}）")
    check(ocr_total == 3, "契约字段 ocrTotalPages = 3（进度分母，来自快扫）", f"实测 {ocr_total}")
    check(
        mine.get("segmentCount", 0) > 0,
        "分块已落库（OCR 文本真的进了分段）",
        f"segmentCount={mine.get('segmentCount')}",
    )

    print("\n== 3. 分段正文里是识别出来的中文 ==")
    st, docs = call("GET", f"/knowledge-bases/{kb_id}/documents?page=1&size=20", token)
    st, segs = call("GET", f"/segments?documentId={doc_id}&page=1&size=5", token)
    seg_rows = ((segs.get("data") or {}).get("list")) or []
    text = " ".join((s.get("content") or "") for s in seg_rows)
    check(bool(seg_rows), f"能读到分段（{len(seg_rows)} 条）", text[:80].replace("\n", " "))
    check("制度" in text or "适用" in text or "报销" in text, "分段正文含识别出的中文", text[:120].replace("\n", " "))

    print("\n== 4. 清理 ==")
    st, _ = call("DELETE", f"/knowledge-bases/{kb_id}", token)
    check(st == 200, f"清理夹具库（{st}）")

    bad = [m for c, m in ok if not c]
    print("=" * 60)
    print(f"通过 {len(ok) - len(bad)}/{len(ok)}")
    for m in bad:
        print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
