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

用法：
  python tools/check_contract_consumers.py
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
    print("SELF-TEST OK: 假端点被列为零消费；已消费端点能找到调用点")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="前后端配对第三格：前端消费点只读读数")
    parser.add_argument("--json", action="store_true", help="输出 JSON")
    parser.add_argument("--self-test", action="store_true", help="内置反证")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    rows = build_report()
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
