#!/usr/bin/env python3
"""前后端配对第三格：前端消费点只读读数（issue #79）。

三个维度是「后端端点 / 契约登记 / 前端消费点」。前两个已有闸门，
第三个从来没有读数 —— 所以「后端已落 + 契约已登记 + 页面零引用」
可以全程绿。

本脚本只做只读扫查，**默认不失败**，先给一份可核对的基线：
  1. 静态扫 backend 的 @*Mapping，得到后端端点；
  2. 静态扫 frontend/src/api/*.ts，得到契约函数与 method+url；
  3. 只保留「后端已落且契约已登记」的端点；
  4. 在 frontend/src（api/ 之外）找契约函数的调用点；
  5. 输出「端点 → 契约函数 → 消费点文件:行」或「零命中」。

★ 为什么不用「路径字符串搜索」判红：页面可能通过 API 函数、动态拼接、
  或组件库间接调用，字符串命中/不命中都会误报。这里用**函数名**做第一版，
  仍然只报告不判红；等基线人工过一遍后再谈是否进 CI。

★ 门禁（`#79`）：`--check` 拿 `tools/consumer-baseline.txt` 当基线 ——
  **新增的零消费端点会红**；基线里那条现在有人用了 / 端点没了也会红（清单必须随修复收缩）。

用法：
  python tools/check_contract_consumers.py            # 只读读数
  python tools/check_contract_consumers.py --check     # 门禁：与基线比对，退 0/1
  python tools/check_contract_consumers.py --baseline  # 按当前读数重排基线（人工过一遍再用）
  python tools/check_contract_consumers.py --json
  python tools/check_contract_consumers.py --self-test
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BACKEND_DIR = ROOT / "backend/src/main/java"
FRONTEND_SRC = ROOT / "frontend/src"
FRONTEND_API = FRONTEND_SRC / "api"

ANNOTATION_RE = re.compile(r"@(Get|Post|Put|Patch|Delete)Mapping\s*(?:\(([^)]*)\))?", re.S)
CLASS_MAPPING_RE = re.compile(r"@RequestMapping\s*\(\s*(?:value\s*=\s*)?\"([^\"]*)\"", re.S)
STRING_RE = re.compile(r"\"([^\"]*)\"")
EXPORT_CONST_RE = re.compile(r"export\s+const\s+([A-Za-z_$][\w$]*)\s*=", re.S)
EXPORT_FN_RE = re.compile(r"export\s+function\s+([A-Za-z_$][\w$]*)\s*\(", re.S)
URL_RE = re.compile(r"url\s*:\s*['\"]([^'\"]+)['\"]")
METHOD_RE = re.compile(r"method\s*:\s*['\"]([A-Za-z]+)['\"]")
CONSUMER_RE_TMPL = r"\b{fn}\s*\("


@dataclass(frozen=True)
class Endpoint:
    method: str
    path: str
    controller: str
    line: int


@dataclass(frozen=True)
class ContractCall:
    function: str
    method: str
    path: str
    file: str
    line: int


@dataclass
class ConsumerRow:
    endpoint: Endpoint
    calls: list[ContractCall]
    consumers: list[str]


def normalize_path(raw: str) -> str:
    """把 `/api/v1/a/{id}/b` 与 `/a/:id/b` 归一成同一种形状。"""
    path = raw.strip()
    if path.startswith("/api/v1/"):
        path = path[len("/api/v1") :]
    path = re.sub(r"\{[^}]+\}", "{}", path)
    path = re.sub(r":[A-Za-z_$][\w$]*", "{}", path)
    path = re.sub(r"/+", "/", path)
    if not path.startswith("/"):
        path = "/" + path
    return path.rstrip("/") or "/"


def first_string(args: str | None) -> str:
    if not args:
        return ""
    match = STRING_RE.search(args)
    return match.group(1) if match else ""


def scan_backend(root: Path = BACKEND_DIR) -> list[Endpoint]:
    endpoints: list[Endpoint] = []
    for path in sorted(root.rglob("*Controller.java")):
        text = path.read_text(encoding="utf-8")
        class_match = CLASS_MAPPING_RE.search(text)
        base = class_match.group(1) if class_match else ""
        for match in ANNOTATION_RE.finditer(text):
            method = match.group(1).upper()
            method_path = first_string(match.group(2))
            full = "/".join(part.strip("/") for part in (base, method_path) if part.strip("/"))
            line = text.count("\n", 0, match.start()) + 1
            endpoints.append(
                Endpoint(
                    method=method,
                    path=normalize_path("/" + full),
                    controller=path.name,
                    line=line,
                )
            )
    return endpoints


def scan_frontend_api(api_dir: Path = FRONTEND_API) -> list[ContractCall]:
    calls: list[ContractCall] = []
    for path in sorted(api_dir.glob("*.ts")):
        if path.name in {"index.ts", "types.ts", "stream-types.ts"}:
            continue
        text = path.read_text(encoding="utf-8")
        starts: list[tuple[int, str]] = []
        starts.extend((m.start(), m.group(1)) for m in EXPORT_CONST_RE.finditer(text))
        starts.extend((m.start(), m.group(1)) for m in EXPORT_FN_RE.finditer(text))
        starts.sort()
        for index, (start, function) in enumerate(starts):
            end = starts[index + 1][0] if index + 1 < len(starts) else len(text)
            block = text[start:end]
            url_match = URL_RE.search(block)
            if not url_match:
                continue
            method_match = METHOD_RE.search(block)
            if not method_match:
                continue
            line = text.count("\n", 0, start) + 1
            calls.append(
                ContractCall(
                    function=function,
                    method=method_match.group(1).upper(),
                    path=normalize_path(url_match.group(1)),
                    file=path.name,
                    line=line,
                )
            )
    return calls


def build_report(root: Path = ROOT) -> list[ConsumerRow]:
    backend_dir = root / "backend/src/main/java"
    src_dir = root / "frontend/src"
    api_dir = src_dir / "api"
    # 允许 --self-test 用临时根目录；此时模块级常量要跟着换。
    endpoints = scan_backend(backend_dir)
    calls = scan_frontend_api(api_dir)
    by_key: dict[tuple[str, str], list[ContractCall]] = {}
    for call in calls:
        by_key.setdefault((call.method, call.path), []).append(call)

    rows: list[ConsumerRow] = []
    for endpoint in endpoints:
        matched = by_key.get((endpoint.method, endpoint.path), [])
        if not matched:
            continue
        consumers: list[str] = []
        for call in matched:
            pattern = re.compile(CONSUMER_RE_TMPL.format(fn=re.escape(call.function)))
            for path in sorted(src_dir.rglob("*")):
                if not path.is_file() or path.suffix not in {".ts", ".vue", ".tsx", ".js"}:
                    continue
                if api_dir in path.parents:
                    continue
                text = path.read_text(encoding="utf-8", errors="ignore")
                for hit in pattern.finditer(text):
                    line = text.count("\n", 0, hit.start()) + 1
                    consumers.append(f"{path.relative_to(root).as_posix()}:{line}")
        rows.append(ConsumerRow(endpoint=endpoint, calls=matched, consumers=sorted(set(consumers))))
    return rows


def self_test() -> int:
    """往输入里塞一个确实无人调的假端点，脚本必须把它列出来。"""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "backend/src/main/java/demo").mkdir(parents=True)
        (root / "frontend/src/api").mkdir(parents=True)
        (root / "frontend/src/pages").mkdir(parents=True)
        (root / "backend/src/main/java/demo/FakeController.java").write_text(
            '@RequestMapping("/api/v1/fake")\n'
            "class FakeController {\n"
            '  @GetMapping("/ghost")\n'
            "  void ghost() {}\n"
            '  @GetMapping("/used")\n'
            "  void used() {}\n"
            "}\n",
            encoding="utf-8",
        )
        (root / "frontend/src/api/fake.ts").write_text(
            "export const ghost = () => request({ method: 'GET', url: '/fake/ghost' })\n"
            "export const used = () => request({ method: 'GET', url: '/fake/used' })\n",
            encoding="utf-8",
        )
        (root / "frontend/src/pages/Fake.vue").write_text(
            "<script setup>used()</script>\n", encoding="utf-8"
        )
        rows = build_report(root)
        ghost = next((r for r in rows if r.endpoint.path == "/fake/ghost"), None)
        used = next((r for r in rows if r.endpoint.path == "/fake/used"), None)
        if ghost is None or ghost.consumers:
            print("SELF-TEST FAIL: 假端点 /fake/ghost 没有以零消费被列出")
            return 1
        if used is None or not used.consumers:
            print("SELF-TEST FAIL: 已消费端点 /fake/used 没有被识别")
            return 1
    gate_failures = self_test_gate()
    if gate_failures:
        for item in gate_failures:
            print(f"SELF-TEST FAIL（基线门禁）: {item}")
        return 1
    print("SELF-TEST OK: 假端点被列为零消费；已消费端点能找到调用点；基线门禁三向都能红")
    return 0


BASELINE = ROOT / "tools/consumer-baseline.txt"
ISSUE_RE = re.compile(r"#\d+")


def read_baseline(path: Path = BASELINE) -> dict[tuple[str, str], str]:
    """`METHOD<TAB>PATH<TAB>#issue` —— 键是 (method, path)，值是 Issue 号。"""
    if not path.exists():
        return {}
    entries: dict[tuple[str, str], str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("	")
        if len(parts) != 3:
            raise SystemExit(f"基线格式错（应为 METHOD<TAB>PATH<TAB>#issue）：{line!r}")
        entries[(parts[0].strip(), parts[1].strip())] = parts[2].strip()
    return entries


def check_against_baseline(rows, baseline: dict[tuple[str, str], str]) -> list[str]:
    """两个方向都要红：**新增的零消费**、以及**基线里已经过期的**。"""
    problems: list[str] = []
    zero = {(row.endpoint.method, row.endpoint.path): row for row in rows if not row.consumers}

    for key, row in zero.items():
        if key not in baseline:
            functions = ", ".join(call.function for call in row.calls) or "(无契约函数)"
            problems.append(
                f"{key[0]} {key[1]}：零消费且不在基线里（契约函数 {functions}）—— "
                "要么给它接一个页面入口，要么登记进 tools/consumer-baseline.txt 并写清 Issue"
            )

    for key, issue in baseline.items():
        if not ISSUE_RE.fullmatch(issue):
            problems.append(f"{key[0]} {key[1]}：基线条目没有 Issue 号（{issue!r}）")
        if key not in zero:
            problems.append(
                f"{key[0]} {key[1]}：基线里登记了零消费，但这次读数里它不零消费（或端点已不存在）—— "
                "把基线这条删掉（清单必须随修复收缩）"
            )
    return problems


def render_baseline(rows) -> str:
    lines = [
        "# 前端零消费端点的基线（`tools/check_contract_consumers.py --check` 读它）",
        "# 格式：METHOD<TAB>PATH<TAB>#issue —— 每一条都要能说出「为什么现在可以零消费」",
        "# ★ 接上入口或删掉端点之后，把对应行删掉；否则 --check 会红（清单必须随修复收缩）",
        "",
    ]
    for row in rows:
        if row.consumers:
            continue
        lines.append(f"{row.endpoint.method}	{row.endpoint.path}	#?")
    return "\n".join(lines) + "\n"


def self_test_gate() -> list[str]:
    """门禁方向的自检：新增零消费要红、过期基线要红、有 Issue 号才收。"""
    failures: list[str] = []

    class _Row:
        def __init__(self, method, path, consumers):
            self.endpoint = type("E", (), {"method": method, "path": path})()
            self.consumers = consumers
            self.calls = []

    zero_row = _Row("GET", "/ghost", [])
    used_row = _Row("GET", "/used", ["pages/x.vue:1"])

    problems = check_against_baseline([zero_row], {})
    if not problems or "/ghost" not in problems[0]:
        failures.append("新增零消费没有报")

    problems = check_against_baseline([zero_row], {("GET", "/ghost"): "#1"})
    if problems:
        failures.append(f"登记了 Issue 的基线仍被报：{problems}")

    problems = check_against_baseline([used_row], {("GET", "/used"): "#1"})
    if not problems:
        failures.append("基线过期（现在有人用了）没有报")

    problems = check_against_baseline([zero_row], {("GET", "/ghost"): "issue-1"})
    if not any("Issue 号" in item for item in problems):
        failures.append("基线条目缺 Issue 号没有报")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description="前后端配对第三格：前端消费点只读读数")
    parser.add_argument("--json", action="store_true", help="输出 JSON")
    parser.add_argument("--self-test", action="store_true", help="内置反证")
    parser.add_argument("--check", action="store_true", help="门禁：与基线比对，退 0/1")
    parser.add_argument("--baseline", action="store_true", help="按当前读数重排基线（人工过一遍再用）")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    rows = build_report()
    if args.baseline:
        BASELINE.write_text(render_baseline(rows), encoding="utf-8", newline="\n")
        print(f"已写入 {BASELINE.relative_to(ROOT)}：{sum(1 for r in rows if not r.consumers)} 条（记得逐条补 Issue 号）")
        return 0
    if args.check:
        problems = check_against_baseline(rows, read_baseline())
        if problems:
            print(f"✗ 第三格门禁：{len(problems)} 条对不上\n")
            for item in problems:
                print(f"  - {item}")
            return 1
        print(f"✓ 第三格门禁通过：零消费 {sum(1 for r in rows if not r.consumers)} 条，全部在基线里且仍为零消费")
        return 0
    zero = [row for row in rows if not row.consumers]
    if args.json:
        payload = [
            {
                "endpoint": asdict(row.endpoint),
                "calls": [asdict(call) for call in row.calls],
                "consumers": row.consumers,
            }
            for row in rows
        ]
        print(json.dumps(payload, ensure_ascii=False, indent=2))
        return 0

    print("# 前端消费点读数（#79）")
    print(f"后端端点与契约匹配：{len(rows)} 条；前端零消费：{len(zero)} 条\n")

    domains: dict[str, list[int]] = {}
    for row in rows:
        domain = row.endpoint.path.split("/")[1] or "(root)"
        domains.setdefault(domain, [0, 0])[0] += 1
        if not row.consumers:
            domains[domain][1] += 1
    print("# 按域（端点数 / 零消费数）")
    for domain in sorted(domains):
        total, zero_count = domains[domain]
        print(f"{domain:24} {total:3} / {zero_count}")
    print()

    for row in zero:
        functions = ", ".join(call.function for call in row.calls)
        print(f"ZERO  {row.endpoint.method:6} {row.endpoint.path:55} {functions}")
        print(f"      backend: {row.endpoint.controller}:{row.endpoint.line}")
    print("\n# 有消费的端点（前 20 条）")
    for row in [item for item in rows if item.consumers][:20]:
        print(f"USED  {row.endpoint.method:6} {row.endpoint.path:55} -> {row.consumers[0]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
