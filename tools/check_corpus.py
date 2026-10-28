#!/usr/bin/env python
"""离线校验一份语料目录（``documents/`` + ``qa.jsonl`` + ``manifest.json``）。

用途：把"语料够不够、问答标签对不对"变成一条能红的命令，而不是靠人翻文件。
设计上故意**不连后端**：入库链路（解析、切分、向量化）由
``scripts/parse_corpus.py`` 负责，这里只回答"磁盘上的语料本身是否自洽"。

校验项
------
1. 文档数 ≥ ``--min-docs``（默认 50，对齐 `04 §6.1` 的 P1 口径）。
2. 总字数 ≥ ``--min-chars``（默认 2,000,000，同样来自 `04 §6.1`）。
3. 格式覆盖：PDF / DOCX / Markdown / TXT 各至少一份（后端四种解析路径都要被走到）。
4. 每份文档都能抽出文本，且 PDF 有文本层（页数 > 0 且抽取字数 > 500）。
5. 抽取字数与 ``manifest.json`` 记录的字数偏差不超过 5%（防止"生成了但内容没落盘"）。
6. ``qa.jsonl``：``id`` 唯一；``expected_document`` 逐字存在于 ``documents/``；
   ``expected_snippet`` 去空白后仍是该文档正文的逐字子串（B3 判据）；
   ``expected_snippet`` 全语料唯一（否则命中哪份文档有歧义）。
7. ``manifest.json`` 若存在：``kind == "synthetic"`` 且 ``is_p1_corpus is False``
   —— 合成语料不得冒充 P1。

用法::

    python tools/check_corpus.py                       # 校验 corpus/synthetic
    python tools/check_corpus.py --corpus corpus       # 校验真实语料（含 min 口径）
    python tools/check_corpus.py --strict-synthetic    # 缺 manifest 也算失败

退出码：0 = 全部通过；1 = 有失败项（逐条打印）。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from gen_corpus import docx_text, pdf_text, squash  # noqa: E402

SUPPORTED = {".pdf", ".docx", ".md", ".markdown", ".txt", ".doc"}
FORMAT_LABELS = {
    ".pdf": "PDF",
    ".docx": "DOCX",
    ".doc": "DOC",
    ".md": "Markdown",
    ".markdown": "Markdown",
    ".txt": "TXT",
}


def extract_text(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix == ".pdf":
        return pdf_text(path)
    if suffix == ".docx":
        return docx_text(path)
    if suffix in (".md", ".markdown", ".txt"):
        return path.read_text(encoding="utf-8", errors="replace")
    raise ValueError(f"不支持的扩展名：{path.name}")


def load_jsonl(path: Path) -> list[dict]:
    rows: list[dict] = []
    for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = line.strip()
        if not line:
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError as exc:
            raise ValueError(f"{path.name}:{lineno} 不是合法 JSON：{exc}") from exc
    return rows


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="离线校验语料目录")
    parser.add_argument("--corpus", default="corpus/synthetic", help="语料目录（默认 corpus/synthetic）")
    parser.add_argument("--min-docs", type=int, default=50, help="最少文档数（默认 50）")
    parser.add_argument("--min-chars", type=int, default=2_000_000, help="最少总字数（默认 2000000）")
    parser.add_argument("--strict-synthetic", action="store_true", help="缺 manifest.json 视为失败")
    args = parser.parse_args(argv)

    root = Path(args.corpus)
    if not root.is_absolute():
        root = (Path.cwd() / root).resolve()
    doc_dir = root / "documents"
    qa_path = root / "qa.jsonl"
    manifest_path = root / "manifest.json"

    failures: list[str] = []
    notes: list[str] = []

    def fail(message: str) -> None:
        failures.append(message)

    # -- 目录与文件 -------------------------------------------------------
    if not doc_dir.is_dir():
        print(f"[FAIL] 没有 documents 目录：{doc_dir}")
        return 1
    if not qa_path.is_file():
        print(f"[FAIL] 没有 qa.jsonl：{qa_path}")
        return 1

    docs = sorted(p for p in doc_dir.iterdir() if p.is_file() and p.suffix.lower() in SUPPORTED)
    skipped = [p.name for p in doc_dir.iterdir() if p.is_file() and p.suffix.lower() not in SUPPORTED]
    if skipped:
        notes.append(f"跳过 {len(skipped)} 个不支持的扩展名：{', '.join(sorted(skipped)[:5])}")

    if len(docs) < args.min_docs:
        fail(f"文档数不足：{len(docs)} < {args.min_docs}")

    # -- 逐份抽取 ---------------------------------------------------------
    extracted: dict[str, str] = {}
    chars_by_doc: dict[str, int] = {}
    formats: dict[str, int] = {}
    for path in docs:
        label = FORMAT_LABELS.get(path.suffix.lower(), path.suffix)
        formats[label] = formats.get(label, 0) + 1
        try:
            text = extract_text(path)
        except Exception as exc:  # noqa: BLE001 - 校验器要把异常变成一条失败项
            fail(f"{path.name}: 抽取失败 -> {exc}")
            continue
        normalized = squash(text)
        extracted[path.name] = normalized
        chars_by_doc[path.name] = len(normalized)
        if not normalized:
            fail(f"{path.name}: 抽取结果为空（PDF 可能是扫描件、没有文本层）")
            continue
        if path.suffix.lower() == ".pdf" and len(normalized) < 500:
            fail(f"{path.name}: PDF 文本层过薄（{len(normalized)} 字）")

    for label in ("PDF", "DOCX", "Markdown", "TXT"):
        if formats.get(label, 0) == 0:
            fail(f"缺 {label} 格式的样本（后端对应的解析路径没有被走到）")

    total_chars = sum(chars_by_doc.values())
    if total_chars < args.min_chars:
        fail(f"总字数不足：{total_chars:,} < {args.min_chars:,}")

    # -- manifest ---------------------------------------------------------
    manifest: dict = {}
    if manifest_path.is_file():
        try:
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            fail(f"manifest.json 不是合法 JSON：{exc}")
        if manifest:
            if manifest.get("kind") != "synthetic":
                notes.append(f"manifest.kind = {manifest.get('kind')!r}（非 synthetic，按真实语料处理）")
            elif manifest.get("is_p1_corpus") is not False:
                fail("manifest.json 声称是 P1 语料：合成语料不得冒充 P1（is_p1_corpus 必须为 false）")
            recorded = {d["name"]: d for d in manifest.get("documents", []) if "name" in d}
            for name, chars in chars_by_doc.items():
                entry = recorded.get(name)
                if entry is None:
                    fail(f"{name}: manifest.json 里没有这条记录（产物与清单不一致）")
                    continue
                expected = entry.get("chars", 0)
                if expected <= 0:
                    fail(f"{name}: manifest 记录的字数无效（{expected}）")
                elif abs(chars - expected) / expected > 0.05:
                    fail(f"{name}: 抽取字数 {chars:,} 与 manifest 记录 {expected:,} 偏差超过 5%")
            for name in recorded:
                if name not in chars_by_doc:
                    fail(f"manifest.json 记录了 {name}，但 documents/ 里没有这个文件")
    elif args.strict_synthetic:
        fail("缺 manifest.json（--strict-synthetic）")
    else:
        notes.append("没有 manifest.json，跳过字数一致性核对")

    # -- 问答对 -----------------------------------------------------------
    try:
        qa_rows = load_jsonl(qa_path)
    except ValueError as exc:
        print(f"[FAIL] {exc}")
        return 1

    if not qa_rows:
        fail("qa.jsonl 是空的")

    seen_ids: set[str] = set()
    snippet_owner: dict[str, str] = {}
    per_doc_questions: dict[str, int] = {}
    required = ("id", "question", "expected_document", "expected_snippet")
    for index, row in enumerate(qa_rows, start=1):
        missing = [key for key in required if not str(row.get(key, "")).strip()]
        if missing:
            fail(f"qa.jsonl 第 {index} 条缺字段：{', '.join(missing)}")
            continue
        qid = str(row["id"])
        if qid in seen_ids:
            fail(f"qa.jsonl: id 重复 -> {qid}")
        seen_ids.add(qid)

        doc_name = str(row["expected_document"])
        if doc_name not in extracted:
            fail(f"{qid}: expected_document 不存在或未被成功抽取 -> {doc_name}")
            continue
        snippet = squash(str(row["expected_snippet"]))
        if snippet not in extracted[doc_name]:
            fail(f"{qid}: expected_snippet 不是 {doc_name} 的逐字子串 -> {snippet}")
        if snippet in snippet_owner:
            fail(f"{qid}: expected_snippet 与 {snippet_owner[snippet]} 重复 -> {snippet}")
        snippet_owner[snippet] = qid
        per_doc_questions[doc_name] = per_doc_questions.get(doc_name, 0) + 1

    docs_without_qa = sorted(set(extracted) - set(per_doc_questions))
    if docs_without_qa:
        fail(f"{len(docs_without_qa)} 份文档没有任何问答对：{', '.join(docs_without_qa[:5])}")

    # -- 汇总 -------------------------------------------------------------
    print(f"语料目录：{root}")
    print(f"文档：{len(docs)} 份 / 抽取合计 {total_chars:,} 字 / 格式 {formats}")
    print(f"问答：{len(qa_rows)} 条 / 覆盖 {len(per_doc_questions)} 份文档")
    if notes:
        print("提示：")
        for note in notes:
            print(f"  - {note}")
    if failures:
        print(f"\n未通过 {len(failures)} 项：")
        for item in failures:
            print(f"  [FAIL] {item}")
        return 1
    print("\n全部检查通过。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
