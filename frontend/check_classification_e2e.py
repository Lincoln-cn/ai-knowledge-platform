"""密级 pre-filter 的真栈闸门（第 11 周 · 0.3.3）—— 回答「换个人登进来，同一份库看到的东西一样吗」。

为什么需要一个**真栈**闸门：`RetrievalSecurityTest` 已经在真 PG + 真桩上验过密级谓词，但它
**自己用 SQL 造夹具、直接调端口**。而这条链实际跑起来还隔着一层：JWT 里的 clearance → `AccessScopePort`
→ 检索谓词。那一段（"令牌里的密级到底有没有进 SQL"）只有**从登录开始走**才验得到。

夹具是一件事的两个密级版本：**同一份 DOCX 上传两次**，一份 `PUBLIC`、一份 `SECRET`。
内容完全相同 ⇒ 全文路对两份都会命中 ⇒ 于是"谁看得见哪一份"就**只由密级决定**，
不受"哪份更像问句"的干扰。

★ 断言分两半，缺一半都能骗过：
  · **越权 0 命中**（判据 ①）—— 低 clearance 的账号在三种模式下都不许看到 SECRET 那份；
  · **该看见的还在** —— 否则"整个检索坏了"也会让上半条变绿。
  ★ 而"还在"只对 **FULLTEXT / HYBRID** 断言：桩向量是 `sha256` 确定性但**语义无关**的，
    阈值 0 也常常一条都不命中（见 `RetrievalSecurityTest` 的类注释），所以 VECTOR 模式
    只断言"没有越权"，不断言"有可见" —— 那是**环境事实**，不是本闸门要验的东西。

★ 为什么用 `liwei` / `zhangyun` 而不是 ROADMAP 演示里写的 `chenjun`：
  建库时 `ownerId = JWT.sub`、`visibility = PRIVATE` 都是**后端写死的**（`08 §4.4.4` 一律收窄），
  而 `chenjun`（USER，`dataScope=shared`）看不见别人建的私有库 ⇒ 他拿到空结果，
  但那空是**数据范围**拦的，**密级谓词根本没被验到**。所以密级这一段要用 `dataScope=all` 的账号
  （`liwei`=INTERNAL、`zhangyun`=SECRET、`admin`=CONFIDENTIAL）。`chenjun` 仍跑一遍，
  作为"数据范围先拦掉"的一行记录 —— 它的读数**不该**被当成密级生效的证据。

用法：
    python check_classification_e2e.py                      # 默认 http://localhost:5280
    python check_classification_e2e.py http://127.0.0.1:5280
"""
from __future__ import annotations

import json
import shutil
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:5280").rstrip("/")
API = BASE + "/api/v1"

DOCX = (
    Path(__file__).resolve().parent.parent
    / "backend/src/test/resources/fixtures/structured.docx"
)

# 问了文档里真有的词（否则"没命中"会被误读成"检索坏了"）
QUESTION = "报销标准"

MODES = ("VECTOR", "FULLTEXT", "HYBRID")


def api(method: str, path: str, token: str, body: dict | None = None) -> tuple[int, dict]:
    """调后端 REST（走 dev server 的代理 ⇒ 与页面同一条路）。

    ★ 未登录时**不能**发一个空的 `Authorization` 头：那会让 Bearer 过滤器直接判认证失败，
      于是 `/auth/login` 自己也拿回 401「未认证」—— 症状像"密码错了"，其实是头写错了。
    """
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(
        API + path,
        data=json.dumps(body).encode("utf-8") if body is not None else None,
        method=method,
        headers=headers,
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


def upload(path: str, token: str, file: Path, classification: str) -> tuple[int, dict]:
    """multipart 上传（★ 密级是**参数**：本闸门的关键就是同一份内容上传成两个不同的密级）。"""
    boundary = "----classification0boundary"
    buf = b""
    for key, value in (
        ("docType", "制度"),
        # ★ 责任部门改成 org_id（UUID）：种子里只有 ROOT 是确定性 id
        ("orgId", "00000000-0000-4000-8000-000000000001"),
        ("classification", classification),
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
    """轮询文档状态（★ 用列表端点：文档端点都挂在知识库下）。"""
    deadline = time.time() + seconds
    status = "?"
    while time.time() < deadline:
        _, body = api("GET", f"/knowledge-bases/{kb_id}/documents?page=1&size=100", token)
        for row in ((body.get("data") or {}).get("list")) or []:
            if row.get("id") == doc_id:
                status = row.get("status") or "?"
                break
        if status in expected:
            return status
        time.sleep(1.5)
    return status


def login(username: str, password: str) -> str:
    status, body = api("POST", "/auth/login", "", {"username": username, "password": password})
    token = (body.get("data") or {}).get("accessToken")
    if status != 200 or not token:
        raise SystemExit(f"登录失败（{username}）：status={status} body={str(body)[:200]}")
    return token


def hit_test(token: str, kb_id: str, mode: str) -> list[dict]:
    """命中测试。★ 阈值：VECTOR 用 0（桩向量语义无关，卡阈值只会永远空），其余用 0.6。"""
    status, body = api(
        "POST",
        "/retrieval/test",
        token,
        {
            "question": QUESTION,
            "mode": mode,
            "threshold": 0.0 if mode == "VECTOR" else 0.6,
            "topN": 10,
            "noRefStrategy": "REFUSE",
            "kbIds": [kb_id],
        },
    )
    if status != 200:
        raise SystemExit(f"命中测试失败（{mode}）：status={status} body={str(body)[:300]}")
    return (body.get("data") or {}).get("items") or []


def doc_names(items: list[dict]) -> set[str]:
    return {item.get("docName") or "?" for item in items}


def main() -> int:
    ok: list[str] = []
    bad: list[str] = []

    def check(cond: bool, msg: str) -> bool:
        (ok if cond else bad).append(msg)
        print(("  [OK] " if cond else "  [NG] ") + msg)
        return cond

    if not DOCX.exists():
        print(f"  [NG] 夹具文件不存在：{DOCX}")
        return 1

    admin = login("admin", "admin")

    # ── 夹具：一份内容、两个密级 ──
    stamp = str(int(time.time()))
    status, created = api(
        "POST",
        "/models",
        admin,
        {
            "name": f"密级闸门向量-{stamp}",
            "kind": "EMBEDDING",
            "supplier": "LOCAL_INFERENCE",
            "baseModel": "bge-m3",
            "dimension": 768,
        },
    )
    model_id = (created.get("data") or {}).get("id")
    if status != 200 or not model_id:
        print(f"  [NG] 建向量模型失败：status={status} body={str(created)[:200]}")
        return 1

    status, kb = api(
        "POST",
        "/knowledge-bases",
        admin,
        {
            "name": f"密级闸门库-{stamp}",
            "category": "制度规范",
            "importType": "LOCAL",
            "embeddingModelId": model_id,
            # ★ ceiling 要放到 SECRET：它管"允许装多密的"，与"谁能看"正交（13 §6）
            "maxClassification": "SECRET",
        },
    )
    kb_id = (kb.get("data") or {}).get("id")
    if status != 200 or not kb_id:
        print(f"  [NG] 建知识库失败：status={status} body={str(kb)[:200]}")
        return 1

    # 同一份 DOCX 存成两个名字 ⇒ 列表里能按 docName 分辨（同一文件名会被当成同一篇）
    tmpdir = Path(tempfile.mkdtemp(prefix="aip-class-"))
    try:
        fixtures = []
        for classification, name in (
            ("PUBLIC", f"密级闸门-公开-{stamp}.docx"),
            ("SECRET", f"密级闸门-机密-{stamp}.docx"),
        ):
            target = tmpdir / name
            shutil.copyfile(DOCX, target)
            status, uploaded = upload(f"/knowledge-bases/{kb_id}/documents", admin, target, classification)
            data = uploaded.get("data")
            doc_id = (data[0].get("id") if isinstance(data, list) and data else None) or (
                data or {}
            ).get("id")
            if status != 200 or not doc_id:
                print(f"  [NG] 上传（{classification}）失败：status={status} body={str(uploaded)[:200]}")
                return 1
            fixtures.append((classification, name, doc_id))

        # ── 真链的必经三步：等解析 → 触发向量化 → 等向量化成功 ──
        for classification, name, doc_id in fixtures:
            parsed = wait_doc_status(admin, kb_id, doc_id, {"PENDING_EMBEDDING", "SEGMENT_FAILED"}, 90)
            if parsed != "PENDING_EMBEDDING":
                print(f"  [NG] 等解析落地失败（{classification} 实际 {parsed}）")
                return 1
            api("POST", f"/documents/{doc_id}/embedding", admin, {})
            embedded = wait_doc_status(admin, kb_id, doc_id, {"SUCCEEDED", "EMBEDDING_FAILED"}, 120)
            if embedded != "SUCCEEDED":
                print(f"  [NG] 等向量化成功失败（{classification} 实际 {embedded}）")
                return 1
            print(f"  · 夹具就绪：{name} 密级={classification} docId={doc_id}")

        # 回读一次库里的密级，确认"上传时确实按参数落了"（否则后面测的根本不是密级）
        _, docs = api("GET", f"/knowledge-bases/{kb_id}/documents?page=1&size=100", admin)
        stored = {
            row.get("name"): row.get("classification")
            for row in ((docs.get("data") or {}).get("list")) or []
        }
        print(f"  · 库里的密级：{stored}")

        public_doc, secret_doc = fixtures[0][1], fixtures[1][1]

        # ── 逐个账号、逐个模式 ──
        # (账号, 口令, 读到的 clearance, 该看见哪些, 断言"该看见的还在"吗)
        accounts = [
            ("liwei", "123456", "INTERNAL(1)", [public_doc], True),
            ("zhangyun", "123456", "SECRET(2)", [public_doc, secret_doc], True),
            ("admin", "admin", "CONFIDENTIAL(3)", [public_doc, secret_doc], True),
        ]
        for username, password, clearance, should_see, assert_present in accounts:
            token = login(username, password)
            for mode in MODES:
                items = hit_test(token, kb_id, mode)
                names = doc_names(items)
                print(f"  · {username}（{clearance}）{mode}: {len(items)} 条 {sorted(names)}")

                # 判据 ①：越权 0 命中
                check(
                    secret_doc not in names or secret_doc in should_see,
                    f"{username}（{clearance}）{mode} 越权命中 0 条",
                )
                # "该看见的还在"：只对全文/混合断言（理由见文件头）
                if assert_present and mode != "VECTOR":
                    for name in should_see:
                        check(
                            name in names,
                            f"{username}（{clearance}）{mode} 看得见 {name}",
                        )

        # ── chenjun：数据范围先拦掉（**不是**密级的证据，如实记一行）──
        token = login("chenjun", "123456")
        items = hit_test(token, kb_id, "HYBRID")
        print(
            f"  · chenjun（USER / dataScope=shared）HYBRID: {len(items)} 条"
            f" —— 这个 0 是**数据范围**拦的（看不见 admin 建的私有库），不是密级谓词的证据"
        )
        check(
            secret_doc not in doc_names(items),
            "chenjun 也拿不到机密内容（数据范围 + 密级两重，任一生效都不会看到）",
        )
    finally:
        shutil.rmtree(tmpdir, ignore_errors=True)

    print(f"\n通过 {len(ok)}/{len(ok) + len(bad)}")
    for m in bad:
        print("  - " + m)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
