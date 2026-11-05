"""issue #227 的验收读数：向量化并发闸门在真栈上到底压住了什么。

━━━ 它回答哪三个问题 ━━━
① **70 份、一次不重试**：全部 `SUCCEEDED`、`EMBEDDING_FAILED` 为 0，而且
   `vec_embedding_<dim>` 里该库的行数 == 该库 `kb_segment` 行数（写进向量表的数量对得上）；
② 一批任务跑的时候，`pg_stat_activity` 里**我们自己那套连接**的活跃数峰值不超过池上限；
③ ★ 把池压到 2 再灌 20 份：应该**变慢**，不是**失败** —— 并且要给出"慢了多少"的斜率。

━━━ 为什么这三个读数需要脚本，而不是点一遍界面 ━━━
- 判据②要的是**批次执行期间的峰值**，不是事后的值：事后查 `pg_stat_activity` 永远是空。
  所以采样必须**与灌入并发进行**（本脚本每 200ms 采一次，峰值从样本里取）。
- 判据③是"同一个 N 跑两次不同池大小"的对照 ⇒ 两次读数必须是**同一条命令、同一套口径**，
  否则差异会被"哪次多点了几下、哪次网络抖了"吃掉。
- 判据①里 `EMBEDDING_FAILED` 有**两类成因**，混在一起数就看不出闸门有没有用：
  · **并发类**：`Failed to obtain JDBC Connection` / `CannotGetJdbcConnectionException` /
    `向量化失败：数据库连接池被同时入库的任务占满…` / `HikariPool` / `Connection is not available`
    —— 这是本 issue 要压住的那一类；
  · **模型不存在类**：`向量模型不存在：<id>`（issue #228，同一根因的另一副面孔，**与并发无关**）。
  本脚本把两类**分开计数**，并且**不把模型缺失算进并发失败**。

━━━ 用法 ━━━
    # 离线自检（不需要任何服务；CI 跑这条）
    python backend/tools/check_embedding_gate.py --self-check

    # 池=10、闸门=4、70 份（判据 ①②）
    python backend/tools/check_embedding_gate.py --base http://127.0.0.1:18098 \
        --documents 70 --pool-size 10 --gate-capacity 4 --out gate-pool10-70.json

    # 池=10 的 20 份基线（判据 ③ 的对照）
    python backend/tools/check_embedding_gate.py --base http://127.0.0.1:18098 \
        --documents 20 --pool-size 10 --gate-capacity 4 --out gate-pool10-20.json

    # 池=2 的 20 份（判据 ③）：后端要先以 DB_POOL_SIZE=2 重启
    python backend/tools/check_embedding_gate.py --base http://127.0.0.1:18098 \
        --documents 20 --pool-size 2 --gate-capacity 4 --out gate-pool2-20.json \
        --compare gate-pool10-20.json

退出码：0 = 本次运行的判据全部通过；1 = 有判据没过（或读数不完整）；2 = 参数/环境错误。

★★ 三条"读数纪律"（每一轮都靠它们防"跑得通、不报错、读数是假的"）：
  · **不重试**：触发只发一次。判据①问的就是"一次不重试能不能全过"，脚本自己重试等于
    把这条判据悄悄改掉了；
  · **空样本必须报错**：采样一条都没拿到时，峰值不能默认成 0（那会让判据②永远绿）；
  · **0 == 0 不算相等**：`kb_segment` 与向量表都为空时，判据①必须判**不通过**
    （"库里什么都没有"显然不等于"都向量化了"）。
"""

from __future__ import annotations

import argparse
import json
import pathlib

import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent

USER = "admin"
PASSWORD = "admin"

#: 后端 `DocumentService.MAX_PAGE_SIZE` 也是 100。70 份以内一页就够，但仍按分页读全量：
#: 写死 `size=50` 时第 51 份起会**安静地看不到**（"少看了文档"和"都好了"读数一样）。
MAX_PAGE_SIZE = 100

POLL_INTERVAL_SEC = 1.0
#: ★ 轮询重试次数。**只给"读状态"用，不给"触发"用** —— 判据①要求的是触发不重试；
#:   池被占满时读列表会 500，那时重试才是如实读数（不重试会把"过载"读成"脚本崩了"）。
POLL_RETRIES = 30
DEFAULT_TIMEOUT_SEC = 900.0

#: 解析终态（`DocumentStatus` 七态）。非终态继续等；两个 *FAILED 是终态但算失败。
PARSE_SETTLED = {
    "PENDING_EMBEDDING",
    "EMBEDDING",
    "SUCCEEDED",
    "SEGMENT_FAILED",
    "EMBEDDING_FAILED",
}
EMBEDDING_SETTLED = {"SUCCEEDED", "SEGMENT_FAILED", "EMBEDDING_FAILED"}

# ── 失败分类用的字面量（★ 全是原始报错里的串，不是我们的措辞）─────────────
#: 并发/资源不足类：`EmbeddingRunner.isResourceExhaustion` 的提示 + Hikari/JDBC 的原始串。
CONCURRENCY_MARKERS = (
    "Failed to obtain JDBC Connection",  # 2026-10-28 那批的原始报错（issue 正文）
    "CannotGetJdbcConnectionException",
    "数据库连接池被同时入库的任务占满",  # EmbeddingRunner 给资源不足换的人话
    "HikariPool",
    "Connection is not available",
    "Unable to acquire JDBC Connection",
)
#: 模型不存在类（issue #228）：与并发无关，必须单独计数。
MODEL_MARKERS = ("向量模型不存在", "no such embedding model", "EmbeddingModelNotFound")

#: 上传时给的元数据（与 scripts/parse_corpus.py 同口径）。
UPLOAD_META = {
    "docType": "制度",
    "orgId": "00000000-0000-4000-8000-000000000001",
    "classification": "INTERNAL",
}


# ═══════════════════════════════════════════════════════════════════════
# 一、纯函数：分类 · 统计 · 判据（★ 离线自检就测这一节）
# ═══════════════════════════════════════════════════════════════════════


def classify_reason(reason: str | None, hint: str | None = None) -> str:
    """把一份文档的失败原因归成 `concurrency` / `model_missing` / `other` / `none`。

    ★ 顺序有意义：**先认并发、再认模型缺失**，最后兜底 other。
      两类不会互相吞掉（同一串里通常也不会同时出现），但归类顺序必须固定，
      否则"两个 agent 各跑一遍读出的数不一样"。
    ★ 只在 `reason`/`hint` 里找**原始串**，不找我们的措辞 —— 措辞会改，原始串不会。
    """
    text = f"{reason or ''}\n{hint or ''}"
    if not text.strip():
        return "none"
    if any(marker in text for marker in CONCURRENCY_MARKERS):
        return "concurrency"
    if any(marker in text for marker in MODEL_MARKERS):
        return "model_missing"
    return "other"


def summarize_samples(samples: list[dict], pool_size: int) -> dict:
    """从采样里取峰值。

    ★★ 空样本**必须报错**：`max([])` 的默认值写成 0 会让判据②永远绿
      （"一条都没采到"和"一条活跃连接都没有"读数一样 —— 本项目头号病）。
    """
    if not samples:
        raise ValueError("采样为空：峰值无法计算（不能默认成 0）")
    active_all = [s["active_all"] for s in samples]
    active_mine = [s["active_mine"] for s in samples]
    mine_pids = [s["mine_pids"] for s in samples]
    return {
        "count": len(samples),
        "interval_ms": samples[0].get("interval_ms"),
        "active_all_peak": max(active_all),
        "active_mine_peak": max(active_mine),
        "active_mine_mean": round(sum(active_mine) / len(active_mine), 2),
        "mine_pids_peak": max(mine_pids),
        "mine_pids_min": min(mine_pids),
        "pool_limit": pool_size,
        #: 采样点里"活跃连接数**已经顶到池上限**"的次数 ⇒ 池被打满的证据，
        #: 也是判据③（池=2 必须变慢）的机理解释。
        "samples_at_pool_limit": sum(1 for v in active_mine if v >= pool_size),
        "samples_active_nonzero": sum(1 for v in active_mine if v > 0),
    }


def evaluate(result: dict) -> dict:
    """把一次运行的原始读数翻译成三条判据的通过/不通过。

    ★ 判据①里"行数相等"是**两个正数相等**：
      `segments == 0 and vectors == 0` 必须判不通过（这不是"全部向量化"，是"什么都没进来"）。
    """
    docs = result["documents"]
    db = result["db"]
    trig = result["trigger"]
    pool = result["config"]["pool_size"]
    samples = result["samples"]

    seg = db["segments"]
    vec = db["vectors_by_metadata"]

    criterion1_detail = {
        "documents_total": docs["total"],
        "documents_succeeded": docs["succeeded"],
        "documents_embedding_failed": docs["embedding_failed"],
        "failed_concurrency": len(result["failures"]["concurrency"]),
        "failed_model_missing": len(result["failures"]["model_missing"]),
        "trigger_5xx": trig["status_5xx"],
        "segments": seg,
        "vectors_by_metadata": vec,
        "vectors_by_join": db["vectors_by_join"],
    }
    criterion1 = (
        docs["total"] > 0
        and trig["status_5xx"] == 0
        and docs["succeeded"] == docs["total"]
        and len(result["failures"]["concurrency"]) == 0
        and len(result["failures"]["model_missing"]) == 0
        and seg > 0
        and seg == vec
    )

    criterion2 = (
        samples["count"] > 0
        and samples["active_mine_peak"] <= pool
        and samples["mine_pids_peak"] <= pool
    )

    return {
        "criterion1_no_failure_and_rows_match": {
            "ok": bool(criterion1),
            "detail": criterion1_detail,
        },
        "criterion2_peak_within_pool": {
            "ok": bool(criterion2),
            "detail": {
                "active_mine_peak": samples["active_mine_peak"],
                "mine_pids_peak": samples["mine_pids_peak"],
                "pool_limit": pool,
                "samples": samples["count"],
                "samples_at_pool_limit": samples["samples_at_pool_limit"],
            },
        },
    }


def slope_compare(current: dict, baseline: dict) -> dict:
    """判据③：池=2 的 20 份 vs 池=10 的 20 份 —— 变慢多少、有没有失败。

    ★ 每份耗时是唯一可比的口径（两次的份数相同才谈得上斜率；不同份数时也照样按
      "每份秒数"比，但要显式记下份数不同这件事）。
    """
    cur_docs = current["documents"]["total"]
    base_docs = baseline["documents"]["total"]
    if cur_docs <= 0 or base_docs <= 0:
        raise ValueError("份数为 0：每份耗时无从计算（不许把 0 当分母或当成 0 秒）")
    cur_per_doc = current["batch"]["elapsed_sec"] / cur_docs
    base_per_doc = baseline["batch"]["elapsed_sec"] / base_docs
    ratio = cur_per_doc / base_per_doc if base_per_doc > 0 else None
    return {
        "current": {
            "pool_size": current["config"]["pool_size"],
            "documents": cur_docs,
            "elapsed_sec": current["batch"]["elapsed_sec"],
            "per_doc_sec": round(cur_per_doc, 3),
            "failed_concurrency": len(current["failures"]["concurrency"]),
            "succeeded": current["documents"]["succeeded"],
        },
        "baseline": {
            "pool_size": baseline["config"]["pool_size"],
            "documents": base_docs,
            "elapsed_sec": baseline["batch"]["elapsed_sec"],
            "per_doc_sec": round(base_per_doc, 3),
            "failed_concurrency": len(baseline["failures"]["concurrency"]),
            "succeeded": baseline["documents"]["succeeded"],
        },
        "per_doc_ratio": round(ratio, 2) if ratio is not None else None,
        #: 判据③的判法：**没有并发失败**，且**变慢了**（每份耗时 ≥ 基线的 1.2 倍，
        #: 1.2 是一个刻意留低的门槛 —— 它只排除"噪声级差异"，不预设慢多少）。
        "ok": (
            cur_docs == base_docs
            and len(current["failures"]["concurrency"]) == 0
            and current["documents"]["succeeded"] == cur_docs
            and current["batch"]["elapsed_sec"] > baseline["batch"]["elapsed_sec"] * 1.2
        ),
    }


# ═══════════════════════════════════════════════════════════════════════
# 二、HTTP（stdlib；与真栈闸门同口径：admin/admin）
# ═══════════════════════════════════════════════════════════════════════


def call(
    base: str,
    method: str,
    path: str,
    token: str | None = None,
    body: dict | None = None,
    timeout: float = 120,
) -> tuple[int, dict]:
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
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        try:
            raw = e.read().decode("utf-8", errors="replace")
        except Exception:  # noqa: BLE001  后端 500 时连接可能已被中断（实测 WinError 10053）
            raw = ""
        try:
            return e.code, json.loads(raw or "{}")
        except json.JSONDecodeError:
            return e.code, {"message": raw[:300] or f"HTTP {e.code}（响应体不可读）"}
    except Exception as e:  # noqa: BLE001
        return -1, {"message": f"{type(e).__name__}: {e}"}


def login(base: str) -> str:
    status, payload = call(base, "POST", "/auth/login", body={"username": USER, "password": PASSWORD})
    if status != 200:
        code = payload.get("code")
        hint = ""
        if code == 1003 or "验证码" in str(payload.get("message", "")):
            hint = (
                "\n   ★ 后端开着验证码：起后端时加 --app.security.captcha.enabled=false"
                "（只在本地自动化关它）"
            )
        raise SystemExit(f"登录失败（HTTP {status}）：{str(payload)[:200]}{hint}")
    return payload["data"]["accessToken"]


def ensure_embedding_model(base: str, token: str, model_name: str, dimension: int) -> str:
    """找本机 ONNX 那个向量模型；没有就建一个。

    ★ 只按 `baseModel` 名匹配、**不看 status**：向量模型是懒加载的，
      `NOT_LOADED` 只表示"还没被用过"。按 status 过滤会让每次运行都新建一个模型
      （实测堆过 45 个 `本地ONNX向量-<时间戳>` 残留）。
    """
    status, payload = call(base, "GET", "/models?size=100", token)
    if status != 200:
        raise SystemExit(f"读模型列表失败（{status}）：{str(payload)[:200]}")
    rows = (payload.get("data") or {}).get("list") or []
    for row in rows:
        if (
            row.get("kind") == "EMBEDDING"
            and (row.get("baseModel") or "").strip().lower() == model_name.strip().lower()
        ):
            return row["id"]
    created_status, created = call(
        base,
        "POST",
        "/models",
        token,
        {
            "name": f"本地ONNX向量-{int(time.time())}",
            "kind": "EMBEDDING",
            "supplier": "LOCAL_INFERENCE",
            "baseModel": model_name,
            "dimension": dimension,
        },
    )
    model_id = (created.get("data") or {}).get("id")
    if not model_id:
        raise SystemExit(f"没有可用的向量模型，且新建失败（{created_status}）：{str(created)[:300]}")
    return model_id


def create_library(base: str, token: str, model_id: str, name: str) -> str:
    status, payload = call(
        base,
        "POST",
        "/knowledge-bases",
        token,
        {
            "name": name,
            "category": "制度规范",
            "description": "issue #227 并发闸门读数",
            "importType": "LOCAL",
            "embeddingModelId": model_id,
            # ★ 给到 SECRET：样例文档密级不一，库上限低了会在入库时被挡（那是"策略生效"，
            #   不是本次要读的东西 —— 但会把读数搅浑）。
            "maxClassification": "SECRET",
        },
    )
    kb_id = (payload.get("data") or {}).get("id")
    if not kb_id:
        raise SystemExit(f"建库失败（{status}）：{str(payload)[:300]}")
    return kb_id


def upload(base: str, kb_id: str, token: str, path: pathlib.Path) -> tuple[int, dict]:
    """真上传（multipart，带字节）。

    ★ 文件名给两种写法（RFC 5987）：`filename="…"` 是 ASCII 兜底，
      `filename*=UTF-8''…` 给非 ASCII 名的正解 —— 只给裸 UTF-8 字节时服务端按
      ISO-8859-1 解会把中文名读成乱码（scripts/parse_corpus.py 记过）。
    """
    boundary = "----gate227boundary"
    encoded = urllib.parse.quote(path.name, safe="")
    buf = b""
    for k, v in UPLOAD_META.items():
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
        with urllib.request.urlopen(req, timeout=300) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        try:
            raw = e.read().decode("utf-8", errors="replace")
        except Exception:  # noqa: BLE001
            raw = ""
        try:
            return e.code, json.loads(raw or "{}")
        except json.JSONDecodeError:
            return e.code, {"message": raw[:300] or f"HTTP {e.code}"}
    except Exception as e:  # noqa: BLE001
        return -1, {"message": f"{type(e).__name__}: {e}"}


def status_of(row: dict) -> str:
    """取状态码。★ `status` 有时是 `{"code": …}`、有时是裸串（两种都出现过）。"""
    raw = row.get("status")
    if isinstance(raw, dict):
        return str(raw.get("code") or "")
    return str(raw or "")


#: ★ 轮询期间的瞬时失败（典型：池被占满时**连读列表都 500**）不算判据里的失败 —— 触发才是一次不重试。
#:   这里逐条记下来，报告里单独一行，因为"连列表都读不出来"本身就是过载的读数。
POLL_ERRORS: list[dict] = []


def list_documents(
    base: str, kb_id: str, token: str, retries: int = 0, retry_sleep: float = 2.0
) -> list[dict]:
    rows: list[dict] = []
    page = 1
    while True:
        status, payload = call(
            base, "GET", f"/knowledge-bases/{kb_id}/documents?page={page}&size={MAX_PAGE_SIZE}", token
        )
        if status != 200:
            if retries > 0:
                POLL_ERRORS.append({"page": page, "status": status, "message": str(payload)[:120]})
                retries -= 1
                time.sleep(retry_sleep)
                continue
            raise SystemExit(f"读文档列表失败（{status}）：{str(payload)[:200]}")
        data = payload.get("data") or {}
        chunk = data.get("list") or []
        rows.extend(chunk)
        try:
            total = int(data.get("total"))
        except (TypeError, ValueError):
            total = None
        if not chunk or len(chunk) < MAX_PAGE_SIZE or (total is not None and len(rows) >= total):
            return rows
        page += 1


# ═══════════════════════════════════════════════════════════════════════
# 三、pg_stat_activity 采样（与灌入并发进行）
# ═══════════════════════════════════════════════════════════════════════

PSQL_CONTAINER = "aip-postgres"
PSQL_USER = "aiplatform"
PSQL_DB = "aiplatform"

#: ★ `pid<>pg_backend_pid()` 排除采样自己这一条；`application_name` 用来**只数我们自己那套连接** ——
#:   本机同时有别的 agent 的后端在跑（各占 10 条连接），不区分就会把别人的读成我们的。
SAMPLER_SQL = (
    "select to_char(clock_timestamp(),'HH24:MI:SS.MS'),"
    "count(*) filter (where state='active' and datname='{db}' and pid<>pg_backend_pid()),"
    "count(*) filter (where state='active' and datname='{db}' and application_name='{app}'"
    " and pid<>pg_backend_pid()),"
    "count(distinct pid) filter (where datname='{db}' and application_name='{app}')"
    " from pg_stat_activity;\n"
)


class PgSampler:
    """复用一个持久 psql 会话，每 `interval_ms` 采一次活跃连接数。

    ★ 为什么不每次起一个 `docker exec`：实测单次约 0.9s，比 200ms 的采样间隔还长
      ⇒ 采样会退化成"每 0.9 秒一条"，峰值大概率漏掉。持久会话单轮约 200ms。
    """

    def __init__(self, app_name: str, interval_ms: int = 200):
        self.app_name = app_name
        self.interval_ms = interval_ms
        self.samples: list[dict] = []
        self.error: str | None = None
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def _run(self) -> None:
        cmd = [
            "docker",
            "exec",
            "-i",
            PSQL_CONTAINER,
            "psql",
            "-X",
            "-q",
            "-U",
            PSQL_USER,
            "-d",
            PSQL_DB,
            "-t",
            "-A",
            "-F",
            "|",
            "-f",
            "-",
        ]
        try:
            proc = subprocess.Popen(
                cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                encoding="utf-8",
                bufsize=1,
            )
        except Exception as e:  # noqa: BLE001
            self.error = f"采样会话起不来：{type(e).__name__}: {e}"
            return

        sql = SAMPLER_SQL.format(db=PSQL_DB, app=self.app_name)
        try:
            while not self._stop.is_set():
                t0 = time.time()
                assert proc.stdin and proc.stdout
                proc.stdin.write(sql)
                proc.stdin.flush()
                line = proc.stdout.readline()
                if not line:
                    self.error = "采样会话提前结束（psql 没有输出）"
                    break
                parts = line.strip().split("|")
                if len(parts) != 4:
                    self.error = f"采样输出形状不符：{line.strip()[:120]!r}"
                    break
                self.samples.append(
                    {
                        "t": parts[0],
                        "active_all": int(parts[1]),
                        "active_mine": int(parts[2]),
                        "mine_pids": int(parts[3]),
                        "interval_ms": self.interval_ms,
                    }
                )
                elapsed = time.time() - t0
                wait = self.interval_ms / 1000.0 - elapsed
                if wait > 0:
                    self._stop.wait(wait)
        finally:
            try:
                if proc.stdin:
                    proc.stdin.close()
                proc.wait(timeout=20)
            except Exception:  # noqa: BLE001
                proc.kill()

    def start(self) -> "PgSampler":
        self._thread = threading.Thread(target=self._run, name="pg-sampler", daemon=True)
        self._thread.start()
        # 等第一帧：起会话本身有 ~200ms 开销，先拿到基线再开始灌
        deadline = time.time() + 15
        while not self.samples and time.time() < deadline and not self.error:
            time.sleep(0.05)
        return self

    def stop(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=20)


def psql(sql: str) -> list[list[str]]:
    """一次性 psql（走 stdin；**不用 `-c`** —— 中文/引号在 `-c` 里会被 shell 层吃掉）。"""
    cp = subprocess.run(
        [
            "docker",
            "exec",
            "-i",
            PSQL_CONTAINER,
            "psql",
            "-X",
            "-q",
            "-U",
            PSQL_USER,
            "-d",
            PSQL_DB,
            "-t",
            "-A",
            "-F",
            "|",
            "-f",
            "-",
        ],
        input=sql,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=180,
    )
    if cp.returncode != 0:
        raise SystemExit(f"psql 失败：{cp.stderr.strip()[:300]}")
    return [line.split("|") for line in cp.stdout.strip().splitlines() if line]


def db_counts(kb_id: str, dimension: int) -> dict:
    """该库的分段数与向量数（两种口径都取，防止"只有一种口径对"）。

    ★ 向量表的键是 `vec_embedding_<dimension>`，随维度路由（384 对应 e5-small）。
    ★ `metadata->>'kb_id'` 是 issue 正文用的口径；再给一份 join 口径交叉核对。
    """
    table = f"vec_embedding_{dimension}"
    rows = psql(
        "select "
        f"(select count(*) from kb_segment where kb_id='{kb_id}'), "
        f"(select count(*) from {table} where metadata->>'kb_id'='{kb_id}'), "
        f"(select count(*) from {table} v join kb_segment s on s.id=v.id where s.kb_id='{kb_id}');\n"
    )
    values = rows[0]
    return {
        "segments": int(values[0]),
        "vectors_by_metadata": int(values[1]),
        "vectors_by_join": int(values[2]),
        "vector_table": table,
        "dimension": dimension,
    }


# ═══════════════════════════════════════════════════════════════════════
# 四、一次完整运行
# ═══════════════════════════════════════════════════════════════════════


def trigger_all(base: str, token: str, doc_ids: list[str], workers: int = 16) -> dict:
    """把 N 个 `POST /documents/{id}/embedding` **尽快、各发一次**地打出去。

    ★★ **不重试**：判据①问的是"一次不重试"。脚本自己重试等于把判据悄悄改掉。
    ★ 触发本身也可能 500（2026-10-28 那批里有 1 份就死在触发这一步）⇒ 单独计数。
    """

    def one(doc_id: str) -> dict:
        t0 = time.time()
        status, body = call(base, "POST", f"/documents/{doc_id}/embedding", token, {}, timeout=120)
        return {
            "document_id": doc_id,
            "status": status,
            "latency_sec": round(time.time() - t0, 2),
            "message": str(body.get("message") or body)[:300] if status not in (200, 202) else "",
        }

    t0 = time.time()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        results = [f.result() for f in [pool.submit(one, d) for d in doc_ids]]
    window = time.time() - t0

    accepted = [r for r in results if r["status"] in (200, 202)]
    failed = [r for r in results if r["status"] not in (200, 202)]
    return {
        "requested": len(doc_ids),
        "accepted": len(accepted),
        "failed": failed,
        "status_5xx": sum(1 for r in results if r["status"] >= 500),
        "status_4xx": sum(1 for r in results if 400 <= r["status"] < 500),
        "status_network_error": sum(1 for r in results if r["status"] < 0),
        "window_sec": round(window, 2),
        "max_trigger_latency_sec": max((r["latency_sec"] for r in results), default=0),
    }


def wait_settled(base: str, kb_id: str, token: str, timeout: float) -> tuple[list[dict], float, bool]:
    deadline = time.time() + timeout
    started = time.time()
    while True:
        rows = list_documents(base, kb_id, token, retries=POLL_RETRIES)
        busy = [r for r in rows if status_of(r).upper() not in EMBEDDING_SETTLED]
        if not busy:
            return rows, time.time() - started, True
        if time.time() > deadline:
            return rows, time.time() - started, False
        time.sleep(POLL_INTERVAL_SEC)


def settle_doc(
    base: str, kb_id: str, token: str, doc_id: str, timeout: float
) -> str:
    """等一份文档的解析落定（`--upload-serial` 用）。返回最后一次看到的状态。"""
    deadline = time.time() + timeout
    last = ""
    while True:
        rows = list_documents(base, kb_id, token, retries=POLL_RETRIES)
        current = next((r for r in rows if r.get("id") == doc_id), None)
        last = status_of(current).upper() if current else last
        if last in PARSE_SETTLED or time.time() > deadline:
            return last
        time.sleep(POLL_INTERVAL_SEC)


def run(args: argparse.Namespace) -> tuple[dict, int]:
    base = args.base.rstrip("/")
    corpus_dir = pathlib.Path(args.corpus)
    if not corpus_dir.is_absolute():
        corpus_dir = ROOT / corpus_dir
    docs_dir = corpus_dir
    if not docs_dir.is_dir():
        raise SystemExit(f"找不到语料目录：{docs_dir}")

    files = sorted(p for p in docs_dir.iterdir() if p.is_file() and not p.name.startswith("_"))
    if not files:
        raise SystemExit(f"{docs_dir} 里没有文档")
    files = files[: args.documents]

    print(f"== issue #227 并发闸门读数 ==")
    print(f"  后端 {base} · 语料 {docs_dir} · 取 {len(files)} 份")
    print(f"  声明池上限 {args.pool_size} · 声明闸门容量 {args.gate_capacity}（容量取自配置，未从运行时读回）")

    token = login(base)
    model_id = ensure_embedding_model(base, token, args.model_name, args.dimension)
    kb_id = create_library(base, token, model_id, f"gate227-{int(time.time())}")
    print(f"  知识库 {kb_id} · 模型 {model_id}")

    uploaded: dict[str, str] = {}
    for path in files:
        code, body = upload(base, kb_id, token, path)
        rows = body.get("data") or []
        doc_id = (rows[0] or {}).get("id") if rows else None
        if doc_id:
            uploaded[path.name] = doc_id
            if args.upload_serial:
                # ★ 一份一份等到解析落定再传下一份：**把"解析那一段的并发"排除在外**。
                #   灌入侧没有闸门（`EmbeddingConcurrencyGate` 只管向量化），池很小时
                #   20 份同时解析就能把池打满 —— 那时测到的"失败"属于解析阶段，
                #   不是判据③要量的"向量化批次"（实测见报告 §五 的池=2/闸门=4 那一轮）。
                settle_doc(base, kb_id, token, doc_id, args.timeout)
        else:
            print(f"  ✗ 上传失败 {path.name}（{code}）：{str(body)[:160]}")
    print(f"  已上传 {len(uploaded)}/{len(files)} 份，等解析…")

    deadline = time.time() + args.timeout
    while True:
        rows = list_documents(base, kb_id, token, retries=POLL_RETRIES)
        pending = [r for r in rows if status_of(r).upper() not in PARSE_SETTLED]
        if not pending:
            break
        if time.time() > deadline:
            print(f"  ★ 解析超时：仍有 {len(pending)} 份未到终态")
            break
        time.sleep(POLL_INTERVAL_SEC)

    parsed_ok = [r for r in rows if status_of(r).upper() == "PENDING_EMBEDDING"]
    print(f"  解析完成 {len(parsed_ok)}/{len(rows)} 份（可触发向量化的）")

    doc_ids = [r["id"] for r in parsed_ok]
    if not doc_ids:
        raise SystemExit("没有可触发向量化的文档（解析全失败？）—— 读数无从谈起")

    # ★ 采样器在**触发之前**起来：命中"灌入这段时间"的峰值，而不是事后的空值。
    sampler = PgSampler(args.application_name, args.sample_interval_ms).start()
    if sampler.error:
        print(f"  ★ 采样会话有问题：{sampler.error}")

    print(f"\n  触发向量化 {len(doc_ids)} 份（★ 各发一次，不重试）…")
    trigger = trigger_all(base, token, doc_ids, workers=args.trigger_workers)
    print(
        f"  触发：接受 {trigger['accepted']} · 失败 {len(trigger['failed'])}"
        f"（5xx {trigger['status_5xx']} / 4xx {trigger['status_4xx']}）"
        f" · 触发窗口 {trigger['window_sec']}s"
    )

    # ★ 触发那一刻的那一帧也要算进采样（第一帧常常正好是"所有任务刚落地"）。
    rows, elapsed, settled = wait_settled(base, kb_id, token, args.timeout)
    sampler.stop()
    if sampler.error:
        print(f"  ★ 采样出错：{sampler.error}")

    documents = []
    failures = {"concurrency": [], "model_missing": [], "other": []}
    for row in rows:
        code = status_of(row).upper()
        reason = str(row.get("failReason") or "")
        hint = str(row.get("failHint") or "")
        kind = classify_reason(reason, hint) if code.endswith("FAILED") else "none"
        documents.append(
            {
                "name": row.get("name"),
                "document_id": row.get("id"),
                "status": code,
                "char_count": row.get("charCount"),
                "fail_reason": reason[:300],
                "fail_hint": hint[:300],
                "failure_kind": kind,
            }
        )
        if kind in failures:
            failures[kind].append({"name": row.get("name"), "reason": reason[:300]})

    # 采样收尾：允许会话在 stop 后再吐一帧（stop 会 join，样本已经全在）
    try:
        samples = summarize_samples(sampler.samples, args.pool_size)
    except ValueError as e:
        samples = {"error": str(e), "count": 0, "active_mine_peak": None}

    db = db_counts(kb_id, args.dimension)

    result = {
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "base": base,
        "config": {
            "pool_size": args.pool_size,
            "gate_capacity": args.gate_capacity,
            "documents_requested": args.documents,
            "application_name": args.application_name,
            "sample_interval_ms": args.sample_interval_ms,
            "model_name": args.model_name,
            "dimension": args.dimension,
        },
        "library": {"id": kb_id, "model_id": model_id, "documents_uploaded": len(uploaded)},
        "trigger": trigger,
        "poll": {"errors": len(POLL_ERRORS), "detail": POLL_ERRORS[:20]},
        "batch": {
            "elapsed_sec": round(elapsed, 2),
            "per_doc_sec": round(elapsed / max(1, len(doc_ids)), 3),
            "settled_all": settled,
            "documents_in_batch": len(doc_ids),
        },
        "documents": {
            "total": len(documents),
            "succeeded": sum(1 for d in documents if d["status"] == "SUCCEEDED"),
            "embedding_failed": sum(1 for d in documents if d["status"] == "EMBEDDING_FAILED"),
            "segment_failed": sum(1 for d in documents if d["status"] == "SEGMENT_FAILED"),
            "pending": sum(1 for d in documents if d["status"] not in EMBEDDING_SETTLED),
            "rows": documents,
        },
        "failures": failures,
        "db": db,
        "samples": samples,
        "sample_trace_head": sampler.samples[:5],
    }
    result["criteria"] = evaluate(result)

    if args.compare:
        baseline_path = pathlib.Path(args.compare)
        baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
        result["comparison"] = slope_compare(result, baseline)

    return result, (0 if all(c["ok"] for c in result["criteria"].values()) else 1)


# ═══════════════════════════════════════════════════════════════════════
# 五、报告
# ═══════════════════════════════════════════════════════════════════════


def print_report(result: dict) -> None:
    c1 = result["criteria"]["criterion1_no_failure_and_rows_match"]
    c2 = result["criteria"]["criterion2_peak_within_pool"]
    docs = result["documents"]
    db = result["db"]
    s = result["samples"]
    trig = result["trigger"]

    def mark(ok: bool | None) -> str:
        return "通过" if ok else "未通过"

    n = docs["total"]
    print("\n== 判据读数 ==")
    print(f"{'判据':46s} {'结论':6s} 读数")
    print(
        f"{f'① {n} 份一次不重试，FAILED=0，行数相等':46s} {mark(c1['ok']):6s} "
        f"成功 {docs['succeeded']}/{docs['total']} · 并发失败 {len(result['failures']['concurrency'])}"
        f" · 模型缺失 {len(result['failures']['model_missing'])}"
        f" · 触发 5xx {trig['status_5xx']}"
        f" · kb_segment {db['segments']} vs {db['vector_table']} {db['vectors_by_metadata']}"
        f"（join 口径 {db['vectors_by_join']}）· 耗时 {result['batch']['elapsed_sec']}s"
    )
    print(
        f"{'② 批次期间活跃连接峰值 ≤ 池上限':46s} {mark(c2['ok']):6s} "
        f"峰值 {s.get('active_mine_peak')} / 池 {s.get('pool_limit')}"
        f" · 我方连接数峰值 {s.get('mine_pids_peak')}"
        f" · 顶到上限 {s.get('samples_at_pool_limit')}/{s.get('count')} 帧"
        f" · 活跃均值 {s.get('active_mine_mean')}"
    )
    comp = result.get("comparison")
    poll = result.get("poll") or {}
    if poll.get("errors"):
        print(f"\n  ★ 轮询期间读状态失败 {poll['errors']} 次（过载的旁证；不算判据①的失败）")
    if comp:
        cur_docs = comp["current"]["documents"]
        label = f"③ 池=2、{cur_docs} 份：变慢而不失败"
        print(
            f"{label:46s} {mark(comp['ok']):6s} "
            f"每份 {comp['current']['per_doc_sec']}s vs 基线 {comp['baseline']['per_doc_sec']}s"
            f"（{comp['per_doc_ratio']}×）· 并发失败 {comp['current']['failed_concurrency']}"
            f" · 成功 {comp['current']['succeeded']}/{comp['current']['documents']}"
        )
    else:
        print(f"{'③ 池=2、同 N：变慢而不失败':46s} {'待对照':6s} 需要 --compare <池=10 的基线.json>")

    if result["failures"]["concurrency"]:
        print("\n  ★ 并发失败明细：")
        for item in result["failures"]["concurrency"]:
            print(f"    - {item['name']}：{item['reason']}")
    if result["failures"]["model_missing"]:
        print("\n  ★ 模型缺失明细（#228，与并发无关，单独计数）：")
        for item in result["failures"]["model_missing"]:
            print(f"    - {item['name']}：{item['reason']}")
    if result["failures"]["other"]:
        print("\n  ★ 其他失败明细：")
        for item in result["failures"]["other"]:
            print(f"    - {item['name']}：{item['reason']}")

    if s.get("error"):
        print(f"\n  ★ 采样读数不完整：{s['error']}")
    if not result["batch"]["settled_all"]:
        print("  ★ 批次超时：仍有文档没到终态（耗时读数只是「至少这么慢」）")


# ═══════════════════════════════════════════════════════════════════════
# 六、离线自检（读数纪律；CI 跑这条）
# ═══════════════════════════════════════════════════════════════════════


def self_check() -> int:
    """不碰网络、不碰数据库，只测本文件里那几条**判据实现**。

    ★ 每一条都对应一种"跑得通、不报错、读数是假的"的形状：
      ① 把模型缺失算进并发失败 ⇒ "闸门有效"的结论被 #228 污染；
      ② 空样本取峰值默认成 0 ⇒ 判据②永远绿；
      ③ `0 == 0` 判成行数相等 ⇒ 空库也能"通过"判据①；
      ④ 每份耗时除零 ⇒ 判据③的斜率变成 inf / 崩掉；
      ⑤ 采样峰值超过池上限仍判通过 ⇒ 判据②形同不存在。
    """
    failures: list[str] = []

    def expect(label: str, got, want) -> None:
        if got != want:
            failures.append(f"{label}：期望 {want!r}，实际 {got!r}")

    # ① 两类失败必须分开
    expect(
        "① 并发类（原始串）",
        classify_reason("向量化失败：Failed to obtain JDBC Connection"),
        "concurrency",
    )
    expect(
        "① 并发类（CannotGetJdbcConnectionException）",
        classify_reason("org.springframework.jdbc.CannotGetJdbcConnectionException: Failed to obtain JDBC Connection"),
        "concurrency",
    )
    expect(
        "① 模型缺失必须算 model_missing（不许算并发）",
        classify_reason("向量化失败：向量模型不存在：50abc1ea-486f-4228-9eed-888ea1242137"),
        "model_missing",
    )
    expect("① 无原因", classify_reason(None, None), "none")
    expect("① 其他失败", classify_reason("向量化失败：文档内容为空"), "other")

    # ② 空样本必须报错，不能 peak=0
    try:
        summarize_samples([], 10)
    except ValueError:
        pass
    else:
        failures.append("② 空采样没有报错 —— 峰值会被读成 0，判据②永远绿")

    samples = [
        {"active_all": 3, "active_mine": 2, "mine_pids": 10, "interval_ms": 200},
        {"active_all": 12, "active_mine": 10, "mine_pids": 10, "interval_ms": 200},
    ]
    summarized = summarize_samples(samples, 10)
    expect("② 峰值取最大（不是最后一条）", summarized["active_mine_peak"], 10)
    expect("② 顶到上限的帧数", summarized["samples_at_pool_limit"], 1)

    # ③ 正常批次：全成功 + 行数相等 ⇒ 判据①通过
    def make_result(seg=10, vec=10, succeeded=10, total=10, peak=4, pool=10, concurrency=()):
        return {
            "config": {"pool_size": pool},
            "documents": {
                "total": total,
                "succeeded": succeeded,
                "embedding_failed": total - succeeded,
            },
            "trigger": {"status_5xx": 0},
            "failures": {"concurrency": list(concurrency), "model_missing": [], "other": []},
            "db": {"segments": seg, "vectors_by_metadata": vec, "vectors_by_join": vec},
            "samples": {
                "count": 5,
                "active_mine_peak": peak,
                "mine_pids_peak": pool,
                "pool_limit": pool,
                "samples_at_pool_limit": 0,
                "active_mine_mean": 2.0,
            },
        }

    ok = evaluate(make_result())
    if not ok["criterion1_no_failure_and_rows_match"]["ok"]:
        failures.append("③ 正常批次被判据①判成未通过")
    if not ok["criterion2_peak_within_pool"]["ok"]:
        failures.append("③ 正常批次被判据②判成未通过")

    # ④ 0 == 0 不许算"行数相等"
    empty = evaluate(make_result(seg=0, vec=0, succeeded=0, total=0))
    if empty["criterion1_no_failure_and_rows_match"]["ok"]:
        failures.append("④ 空库被判据①判成通过 —— 0 == 0 不是「都向量化了」")

    # ⑤ 行长不齐 / 有并发失败 / 峰值超池，都必须判不通过
    if evaluate(make_result(seg=11, vec=10))["criterion1_no_failure_and_rows_match"]["ok"]:
        failures.append("⑤ 行数不等（11 vs 10）被判据①判成通过")
    if evaluate(make_result(concurrency=["x"]))["criterion1_no_failure_and_rows_match"]["ok"]:
        failures.append("⑤ 有并发失败却被判据①判成通过")
    if evaluate(make_result(peak=11, pool=10))["criterion2_peak_within_pool"]["ok"]:
        failures.append("⑤ 峰值 11 超过池 10，判据②仍判通过")

    # ⑥ 判据③的斜率：除零必须报错；同 N 的 2 倍耗时应判通过
    def make_run(pool, docs, elapsed, failed=0):
        return {
            "config": {"pool_size": pool},
            "documents": {"total": docs, "succeeded": docs - failed},
            "batch": {"elapsed_sec": elapsed},
            "failures": {"concurrency": ["x"] * failed},
        }

    try:
        slope_compare(make_run(2, 0, 10), make_run(10, 20, 10))
    except ValueError:
        pass
    else:
        failures.append("⑥ 份数为 0 时没有报错 —— 每份耗时会被读成 0 或崩掉")
    if not slope_compare(make_run(2, 20, 40), make_run(10, 20, 10))["ok"]:
        failures.append("⑥ 池=2 用 4 倍时间且 0 失败，判据③却判未通过")
    if slope_compare(make_run(2, 20, 10), make_run(10, 20, 10))["ok"]:
        failures.append("⑥ 池=2 与基线一样快，判据③仍判通过（没有「变慢」这个读数）")
    if slope_compare(make_run(2, 20, 40, failed=3), make_run(10, 20, 10))["ok"]:
        failures.append("⑥ 池=2 变慢但仍有失败，判据③仍判通过（失败也是变慢的一种，必须区分）")

    for line in failures:
        print(f"  ✗ 自检失败：{line}")
    if failures:
        return 1
    print("  自检通过：两类失败分开 · 空样本报错 · 0==0 不通过 · 峰值超池不通过 · 判据③区分「慢」与「失败」")
    return 0


# ═══════════════════════════════════════════════════════════════════════


def main() -> int:
    parser = argparse.ArgumentParser(description="issue #227 向量化并发闸门读数")
    parser.add_argument("--base", default="http://127.0.0.1:18098")
    parser.add_argument("--documents", type=int, default=70, help="本次灌多少份")
    parser.add_argument("--corpus", default="corpus/synthetic/documents", help="语料目录（相对仓库根）")
    parser.add_argument("--pool-size", type=int, default=10, help="本次后端声明的连接池上限")
    parser.add_argument("--gate-capacity", type=int, default=4, help="本次后端声明的闸门容量")
    parser.add_argument("--application-name", default="gate227", help="后端的 DB_URL ApplicationName")
    parser.add_argument("--sample-interval-ms", type=int, default=200)
    parser.add_argument("--trigger-workers", type=int, default=16)
    parser.add_argument("--upload-serial", action="store_true", help="一份一份等解析落定再传下一份（把解析侧的并发排除在读数外）")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SEC)
    parser.add_argument("--model-name", default="multilingual-e5-small")
    parser.add_argument("--dimension", type=int, default=384)
    parser.add_argument("--out", default="", help="把完整读数写成 JSON")
    parser.add_argument("--compare", default="", help="判据③的基线 JSON（池=10 的那次）")
    parser.add_argument("--self-check", action="store_true", help="只跑离线自检")
    args = parser.parse_args()

    if args.self_check:
        print("== 读数纪律自检（issue #227）==")
        return self_check()

    result, code = run(args)
    print_report(result)

    if args.out:
        out_path = pathlib.Path(args.out)
        if not out_path.is_absolute():
            out_path = ROOT / out_path
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n  读数已写入 {out_path}")

    return code


if __name__ == "__main__":
    sys.exit(main())
