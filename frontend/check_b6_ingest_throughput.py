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
会把 B6 压成假红**（不是假绿，但同样是坏读数）⇒ 本脚本开头显式复位。

★ 2026-10-28 起：桩**不再是必有**。11434 上可能直接是 Ollama（真上游）——
那时根本没有注入口可残留 ⇒ 如实打一行「没有注入面可自证」，读数照算（不再"复位失败就不算读数"，
否则一台干净机器永远跑不了这条闸门）。

━━━ 向量后端用哪个（★ 一句话口径，别混） ━━━
- **吞吐（本脚本）用本地 ONNX**：进程内推理，没有网络、没有第三方排队 ⇒ 它量的是"平台自己的入库能力"。
- **召回准确度（B1/B3 的语料评测）才用 `bge-m3`**：那是模型能力的读数。
⇒ 两个数字回答两个问题，**不许互相顶替**。夹具会先问 `GET /models/local-onnx` 它服务哪个名字，
再按那个名字复用/新建向量模型；`⓪e` 这条判据就是钉住"本跑的向量化后端确实是本地 ONNX"。

用法：
    python check_b6_ingest_throughput.py
    AIP_B6_LIMIT=3 AIP_B6_SECTIONS=150 python check_b6_ingest_throughput.py
    AIP_EMBED_BASE_MODEL=bge-m3 AIP_EMBED_DIMENSION=768 python check_b6_ingest_throughput.py   # 有意走远端
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


def check(ok: bool, message: str) -> bool:
    """打印一条判据并**返回它**。

    ★ 必须返回 bool：本脚本有一处 `if not check(...)` 是"落地失败就提前收工"的短路，
      而 `check` 早先只打印、返回 `None` ⇒ `not None` 恒为真 ⇒ **这条闸门在拿到第一个读数之前
      就自己 exit 1 了**（2026-10-28 首跑实测：解析已落地 `PENDING_EMBEDDING`、判据还是 ✓，
      下面却直接 return 1）。这类"断言只打印不回报"的写法本身就是 silent-failure 的孪生形态。
    """
    if ok:
        print(f"  ✓ {message}")
    else:
        print(f"  ✗ {message}")
        problems.append(message)
    return ok


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


def local_onnx_status(token: str) -> dict:
    """进程内 ONNX 的现状（`GET /models/local-onnx`）：`{enabled, mode, model, filesPresent, ready, …}`"""
    _, body = call("GET", "/models/local-onnx", token)
    return data_of(body)


def ensure_embedding_model(token: str) -> tuple[str, str, bool]:
    """取一个**真能算**的向量模型，返回 `(id, 说明, 是否本跑新建)`；优先复用已加载的那个。

    ★★ 两条口径（2026-10-28 用户拍板，写在这里是因为很容易混）

    ① **吞吐读数（本脚本）用本地 ONNX** —— 进程内推理，没有网络、没有第三方限流，
       它量的才是"平台自己的入库能力"。换成一个远端向量服务，这个数字里就混进了别人的排队时间。
    ② **召回准确度那一档才用 `bge-m3`** —— 那是**模型能力**的读数（B1/B3 的语料评测），
       与吞吐不是一回事。★ 两种读数不许挤进一个数字：一个是"多快"，一个是"多准"。

    ★ 为什么不是硬编码 `bge-m3/768`：后端按 `baseModel == 本地实现服务的那个名字` 路由
      （`RoutingEmbeddingAdapter`），名字对不上就静默走去上游（而桩/上游早撤了）。
      所以先问 `GET /models/local-onnx` 要它服务的名字，再按那个名字挑/建。
    """
    _, body = call("GET", "/models?kind=EMBEDDING&size=50", token)
    rows = data_of(body).get("list") or []
    onnx = local_onnx_status(token)
    served = str(onnx.get("model") or "")
    if onnx.get("ready"):
        note(f"本地 ONNX 就绪：model={served} · {onnx.get('modelPath')}")
    else:
        note(f"本地 ONNX 不可用（{onnx.get('reason') or '未启用'}）⇒ 退回按环境变量建远端向量模型")

    for row in rows:
        if row.get("status") != "LOADED":
            continue
        if served and str(row.get("baseModel")) != served:
            continue
        note(
            f"向量模型复用：{row.get('name')}（baseModel={row.get('baseModel')} · "
            f"dim={row.get('dimension')} · {row.get('status')}）"
        )
        return str(row.get("id")), f"{row.get('name')} / {row.get('baseModel')}", False

    base_model = os.environ.get("AIP_EMBED_BASE_MODEL") or served or "bge-m3"
    raw_dimension = os.environ.get("AIP_EMBED_DIMENSION")
    dimension = int(raw_dimension) if raw_dimension else (384 if served else 768)
    status, body = call(
        "POST",
        "/models",
        token,
        {
            "name": f"B6向量-{int(time.time())}",
            "kind": "EMBEDDING",
            "supplier": "LOCAL_INFERENCE",
            "baseModel": base_model,
            "dimension": dimension,
        },
    )
    model_id = str(data_of(body).get("id") or "")
    if status not in (200, 201) or not model_id:
        raise SystemExit(f"建向量模型失败（{status}）：{str(body)[:300]}")
    note(f"向量模型新建：{model_id}（baseModel={base_model} · dim={dimension}）")

    status, body = call("POST", f"/models/{model_id}/load", token, {})
    if status not in (200, 202):
        raise SystemExit(f"加载向量模型失败（{status}）：{str(body)[:300]}")
    deadline = time.time() + 120
    while time.time() < deadline:
        _, listing = call("GET", "/models?kind=EMBEDDING&size=50", token)
        for row in data_of(listing).get("list") or []:
            if str(row.get("id")) == model_id and row.get("status") == "LOADED":
                return model_id, f"{base_model}（新建）", True
        time.sleep(0.5)
    raise SystemExit(f"向量模型 {model_id} 在 120s 内没变成 LOADED —— 本跑不算读数")


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
    #   ★ 2026-10-28 起：桩不再是"必有"。11434 上可能是 Ollama（真上游）——
    #   那时**根本没有注入口可残留**，所以"复位失败"不等于"读数不可信"，
    #   但也不能假装复位过：如实写一行"没有注入面可自证"。
    if stub_config({"delay_ms": 0, "chunk_delay_ms": 0, "fail_rate": 0.0}):
        note("桩已复位（delay_ms=0 · chunk_delay_ms=0 · fail_rate=0）")
    else:
        note(
            f"桩不在（{STUB}/__stub/config 不可达）⇒ 本次上游不是桩，没有延迟注入面可残留；"
            "吞吐读数照算，但「上游没被人为拖慢」这一格本跑无法自证"
        )

    token = login()
    stamp = str(int(time.time()))
    workdir = tempfile.mkdtemp(prefix="b6ingest-")
    md_path = os.path.join(workdir, f"b6-{stamp}.md")

    model_id = ""
    model_created = False
    kb_id = ""
    try:
        model_id, model_note, model_created = ensure_embedding_model(token)
        kb_id = create_kb(token, model_id)
        note(f"夹具：库 {kb_id} · 向量模型 {model_id}（{model_note}）")

        # ★ ⓪e：本跑的向量化后端到底是谁。红在这里说明"读数来自远端向量服务"，
        #   那它就不是"平台自己的入库能力"——数字仍然有效，只是名字得换。
        onnx = local_onnx_status(token)
        served = str(onnx.get("model") or "")
        check(
            bool(onnx.get("ready")) and served in model_note,
            f"⓪e 向量化后端 = 本地 ONNX（{served or '未启用'}；实得夹具 {model_note}）"
            " —— 吞吐口径要求进程内推理，远端服务会把别人的排队时间算进我们的读数",
        )

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
        # ★ 只删本跑新建的：复用的那个可能是别的夹具/走查在用的（本地 ONNX 那条通常只有一条 LOADED）
        if model_id and model_created:
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
