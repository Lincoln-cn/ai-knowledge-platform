#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
逐页字符准确率读数（Issue #71 · L1：先有读数，不调参）。

════════════════════════════════════════════════════════════════════════════
  ★ 这个脚本存在的原因
════════════════════════════════════════════════════════════════════════════

`fixtures/ocr-ground-truth.json` 从第 9 周就在仓库里，但它**只被用来测噪声剥离**
（`NoiseStripperTest`：页眉页脚/页码/目录剔得对不对）。

⇒ ★ **"汉字识别对了多少"从来没有读数**。而它是第 18 周 **B1 命中率 ≥70% / B3 引用准确率 ≥80%**
的前置：语料里必然有扫描件，没有这个基线，命中率变差时**无法归因** ——
会把「入库那一刻 OCR 就错了」记到检索策略头上。

本脚本只做一件事：**把那个数字算出来并打印**。
★ 不调参、不做后处理、不改识别逻辑（那是 L2/L3）。

════════════════════════════════════════════════════════════════════════════
  指标定义（可机械核对）
════════════════════════════════════════════════════════════════════════════

    逐页字符准确率 = 1 − 编辑距离(参考页文本, OCR 页文本) / len(参考页文本)

* 参考 = `ocr-ground-truth.json` 的 `scanned.pdf.<页号>` —— 即 `noisy.pdf` 那一页的**文本层原文**
  （`make_ocr_fixtures.py` 用 pdfium 导出，非手抄；`scanned.pdf` 是它逐页光栅化后的"无文本层"形态）
* OCR  = **本脚本现场对 `scanned.pdf` 跑 Tesseract 的产物**（逐页一次，不跨页拼接）
* 编辑距离 = Levenshtein，**本脚本自带两行滚动数组实现** ⇒ **零新依赖**（不引 rapidfuzz/python-Levenshtein）

★ **两个聚合读数**（"平均 0.93" 会掩盖"有一页 0.4"）：

* **微平均** = 1 − Σ编辑距离 / Σ参考长度（长页权重更大，是"整篇文档的字符准确率"）
* **最差页** = 逐页准确率的最小值

★ **归一化**（两侧共用）→ 得到「逐行规范形」：

1. `\ufeff`（Tesseract 经管道输出时偶发的 BOM）剔除
2. 换行 `\r\n`/`\r` → `\n`
3. 去掉控制字符（`\x0b` 等；Tesseract 会在页与页之间吐 `\x0c` 换页符）
4. `\xa0`（不换行空格）与全角空格 `\u3000` → 普通空格
5. 每行**两侧去空白**，丢掉空行

★ **三档口径**（同一份规范形，只改空白怎么算）：

| 口径 | 做法 | 用途 |
|---|---|---|
| ★ **字符准确率（主）** | 全部空白**折叠成单空格**后逐字比 | 回答"**汉字认对了多少**"；对换行/空格增删免疫 |
| 保留空白 | 换行→单空格，空格个数照旧 | 看"空白差异还剩多少" |
| 去全部空白 | 空白一个不留 | 上界：彻底不追究空白 |

★ **为什么主口径要折叠空白**：OCR 的换行与空格本来就不稳定（Tesseract 在段落之间吐空行、
把「第 7 页」的空格吃掉），那是**版面重建**问题，不是"汉字认错了"。Issue #71 要的是
「逐页**字符**准确率」，所以先把空白归一，字符串退化成"一个字符序列" ⇒ 编辑距离**逐字可比**。
⚠️ 代价：主口径对**多余的换行完全免疫**（第 1 页的 6 处空行不进主指标）。另两档把它显示出来。

★ **误差分类**（只用于报告"差在什么地方"，**不进主指标**）：
`空白` / `全半角标点` / `字符`。★ **只有 `字符` 那一栏是识别错误**（换字 / 丢字 / 多字）。

════════════════════════════════════════════════════════════════════════════
  ★ 为什么是 Python CLI Tesseract，而不是走 Java 的 `TikaMarkdownParser`
════════════════════════════════════════════════════════════════════════════

两条硬约束（都实测过，别当成偷懒）：

1. **生产解析产物是「整篇扁平 Markdown」** —— `TikaMarkdownParser.parse()` 返回
   `ParseResult.markdown()`，**没有逐页文本**。要拿到逐页就得动生产类（改行为）⇒ 本批禁止。
   实测：Tika 的 PDF 输出里每页确实是 `<div class="page">` 事件（`OcrPageInventoryTest` 已利用它做进度），
   所以**逐页可取**，但那需要一个新写的 SAX 收集器 —— 本机跑不了（见第 2 条）。
2. ★ **本机整个测试模块编译不过**（`git log` 最新的 `6dd17c8` `#78` 提交里，
   `QaPairIngestionTest` / `QaPairRetrievalIsolationTest` 调用了 `SecurityContexts.asUser(...)`，
   而 `SecurityContexts` **从来没有这个方法**）⇒ `mvn test` 连测试源码都编不过，
   **任何 Java 读数在本机都跑不起来**，且那两个文件不在本批允许改动的路径里。

而**配置口径完全一致**：Tika 也是 spawn CLI Tesseract（`12 §11.2` 主路径，非 JNI），
`TesseractOCRParser` 传给 CLI 的参数（`javap` 反编译 3.3.1 实测）与本脚本逐条对齐：

| Tika 的调用 | 本脚本 | 出处 |
|---|---|---|
| `-l <languages>` | `-l chi_sim+eng`（`--languages` 可改） | `OcrProperties.languages()` |
| `-c preserve_interword_spaces=1` | 同（`--no-preserve-spacing` 可做反证） | `OcrEngine.configure()` 显式写 true |
| `--dpi <n>` | `--dpi <n>`（默认 300） | `OcrProperties.dpi()`；`12 §11.4` 下限 300 |
| `-c user_defined_dpi=<n>` | 同 | 同上 |
| 图像预处理（deskew，需 ImageMagick） | **本机没有 ImageMagick** ⇒ 生产路径实测也走 "backing off" | `ImagePreprocessor.class`：找不到就退回原图 |

★ 本机实测：`magick` 不在 PATH 上（system32 的 `convert.exe` 是磁盘转换工具，不是 ImageMagick）
⇒ Tika 的 `enableImagePreprocessing(true)` 在这台机器上会打 "backing off to original file"，
**喂给 Tesseract 的就是同一张 300dpi 原图**。

⇒ ★ **读数在本机与 CI 都可复跑**（CI 装了 tesseract + chi_sim，第 9 周的 OCR 用例就是这么跑的），
**不需要 Docker、不需要 Testcontainers、不需要数据库**。

⚠️ **本读数的一条已知上限**（不许当成"扫描质量差"来解释）：
`scanned.pdf` 不是真扫描件，而是**干净数字 PDF 光栅化 + JPEG(q85)** 的合成件。
Tesseract 在它上面出现的整行丢失 / 乱码回吐（见输出里的差异清单）**在真扫描件上未必重现**，
所以这个基线是**合成件上的基线**，不是"生产扫描件的准确率"。

════════════════════════════════════════════════════════════════════════════
  用法
════════════════════════════════════════════════════════════════════════════

    python backend/tools/measure_ocr_accuracy.py
    python backend/tools/measure_ocr_accuracy.py --dump-overlays       # 另存渲染图+OCR/参考文本
    python backend/tools/measure_ocr_accuracy.py --psm 4               # 试另一种版面分析（★ 只读，不改生产）
    python backend/tools/measure_ocr_accuracy.py --no-preserve-spacing # 反证：关掉它中文会被打散

退出码：0 = 量到了；1 = 量不到（缺夹具 / 缺 tesseract / 缺语言包）——★ **不许拿 0 当"量到了"**。
"""

from __future__ import annotations

import argparse
import difflib
import json
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

import pypdfium2 as pdfium

FIXTURES = Path(__file__).resolve().parent.parent / "src" / "test" / "resources" / "fixtures"
GROUND_TRUTH = FIXTURES / "ocr-ground-truth.json"
SCANNED = FIXTURES / "scanned.pdf"

# 生产默认（与 application.yml 的 app.ocr.* 一致；写在这里便于发现漂移）
DEFAULT_LANGUAGES = "chi_sim+eng"
DEFAULT_DPI = 300

# difflib 的操作码 → 中文标签（报告可读性）
TAG_LABEL = {
    "replace": "整段不同",
    "delete": "★ 整行丢失（识别侧没有）",
    "insert": "★ 多出行（参考侧没有）",
}


# ────────────────────────────── 归一化 ──────────────────────────────


def canonicalize(text: str) -> str:
    """逐行 clean：剔 BOM、统一换行与非换行空格、行尾去空白、丢掉空行。

    ★ 两侧共用。它的产物叫「逐行规范形」（一个非空行一个 `\\n`），是**差异可读性**的基础。
    """
    text = text.replace("\ufeff", "")
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = text.replace("\xa0", " ").replace("\u3000", " ")
    text = "".join(ch for ch in text if ch == "\n" or ord(ch) >= 0x20)
    return "\n".join(line.strip() for line in text.split("\n") if line.strip())


def collapse_whitespace(text: str) -> str:
    """**主口径**：把全部空白折叠成单个空格（去掉首尾）。

    ★ 为什么主口径要折叠空白：OCR 的换行与空格**本来就不稳定**（Tesseract 在段落之间吐空行、
    把「第 7 页」的空格吃掉），那是**版面重建**问题，不是"汉字认错了"。
    本读数量的是「逐页**字符**准确率」（Issue #71 原话），所以先把空白归一，
    字符串就退化成"一个字符序列" —— 编辑距离于是**逐字可比**。
    ★ 代价：它对**多余/缺失的换行完全免疫**（+1 个空格与 +1 个换行的代价都一样，都只在归一化时被抹平）。
    所以脚本另给一列**保留空白形态**的读数，用来看"空白差异还剩多少"。
    """
    return " ".join(text.split())


def strip_whitespace(text: str) -> str:
    """第三口径：去掉**全部**空白后比 —— 用来把"空格的增删"也排除干净。"""
    return "".join(text.split())


# ────────────────────────── 编辑距离（零依赖） ──────────────────────────

# 超过这个规模就不算（Levenshtein 是 O(n·m)）：本读数每页 100–300 字，正常远低于它。
MAX_COMPARABLE_LENGTH = 20000


# ────────────────────────── 编辑距离（零依赖） ──────────────────────────


def edit_operations(reference: str, actual: str) -> list[str]:
    """Levenshtein 的编辑操作序列：`=A` 相等 / `-A` 删 / `+A` 增 / `~R>A` 替换。

    两行滚动数组先算距离矩阵的行，再回溯 —— 页文本 100–300 字，规模完全够；
    ★ 不引 python-Levenshtein / rapidfuzz（本项目"零新依赖"）。
    """
    n, m = len(reference), len(actual)
    if n == 0:
        return ["+" + ch for ch in actual]
    if m == 0:
        return ["-" + ch for ch in reference]
    # 前缀长度矩阵：rows[i][j] = distance(reference[:i], actual[:j])
    rows: list[list[int]] = [list(range(m + 1))]
    for i in range(1, n + 1):
        previous = rows[i - 1]
        current = [i] + [0] * m
        for j in range(1, m + 1):
            cost = 0 if reference[i - 1] == actual[j - 1] else 1
            current[j] = min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
        rows.append(current)

    operations: list[str] = []
    i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and reference[i - 1] == actual[j - 1]:
            operations.append("=" + reference[i - 1])
            i, j = i - 1, j - 1
        elif i > 0 and j > 0 and rows[i][j] == rows[i - 1][j - 1] + 1:
            operations.append("~" + reference[i - 1] + ">" + actual[j - 1])
            i, j = i - 1, j - 1
        elif i > 0 and rows[i][j] == rows[i - 1][j] + 1:
            operations.append("-" + reference[i - 1])
            i -= 1
        else:
            operations.append("+" + actual[j - 1])
            j -= 1
    operations.reverse()
    return operations


def edit_distance(reference: str, actual: str) -> int:
    """只要距离时的快路径（不回溯）。"""
    if reference == actual:
        return 0
    if len(reference) < len(actual):
        reference, actual = actual, reference
    if not actual:
        return len(reference)
    previous = list(range(len(actual) + 1))
    for i, ref_ch in enumerate(reference, start=1):
        current = [i]
        for j, act_ch in enumerate(actual, start=1):
            current.append(
                min(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + (0 if ref_ch == act_ch else 1),
                )
            )
        previous = current
    return previous[-1]


# ────────────────────── 误差分类（"差在什么地方"） ──────────────────────

# 全角 ↔ 半角 的成对标点（★ 只用于**分类**，不参与距离计算）
FULLWIDTH_PAIRS = {
    ("：", ":"),
    ("，", ","),
    ("。", "."),
    ("；", ";"),
    ("（", "("),
    ("）", ")"),
    ("！", "!"),
    ("？", "?"),
    ("、", ","),
    ("．", "."),
    ("－", "-"),
}


def classify(reference: str, actual: str) -> str:
    """给一处差异打标签：空白 / 全半角标点 / 字符。

    ★ 判据是"**去掉全部空白后是否还不同**"：只差空白 ⇒ 空白；否则看是不是全半角同源标点；
      都不是 ⇒ 那是真正的识别错误（换字 / 丢字 / 多字）。
    """
    if strip_whitespace(reference) == strip_whitespace(actual):
        return "空白"
    if (reference, actual) in FULLWIDTH_PAIRS or (actual, reference) in FULLWIDTH_PAIRS:
        return "全半角标点"
    return "字符"


def display(text: str) -> str:
    """差异样例的显示形：把空白显式画出来（否则「多了个换行/空格」看起来像"无中生有"）。"""
    return text.replace("\n", "\\n").replace(" ", "·")


@dataclass
class Difference:
    """一处差异（报告用；★ 只报告，不修）。"""

    kind: str
    reference: str
    actual: str


@dataclass
class PageReading:
    """一页的读数。"""

    page: str
    reference_length: int
    distance: int
    accuracy: float
    loose_reference_length: int
    loose_distance: int
    loose_accuracy: float
    flat_reference_length: int
    flat_distance: int
    flat_accuracy: float
    differences: list[Difference] = field(default_factory=list)
    counts: dict[str, int] = field(default_factory=dict)
    reference_lines: list[str] = field(default_factory=list)
    actual_lines: list[str] = field(default_factory=list)


def collect_differences(reference: str, actual: str, limit: int = 8) -> tuple[list[Difference], dict[str, int]]:
    """把编辑操作聚成"连续的差异块"，分类并计数。

    ★ 聚块而不是逐字列：逐字列会把"同一处的 3 个错字"报成 3 条，读起来像 3 个独立问题。
    """
    counts = {"空白": 0, "全半角标点": 0, "字符": 0}
    differences: list[Difference] = []
    pending_reference = ""
    pending_actual = ""

    def flush() -> None:
        nonlocal pending_reference, pending_actual
        if not pending_reference and not pending_actual:
            return
        kind = classify(pending_reference, pending_actual)
        counts[kind] += 1
        # 差异块里把空白显式画出来（见 display()）
        if len(differences) < limit:
            differences.append(
                Difference(kind, display(pending_reference), display(pending_actual))
            )
        pending_reference = ""
        pending_actual = ""

    for operation in edit_operations(reference, actual):
        if operation.startswith("="):
            flush()
        elif operation.startswith("-"):
            pending_reference += operation[1:]
        elif operation.startswith("+"):
            pending_actual += operation[1:]
        elif operation.startswith("~"):
            pending_reference += operation[1:].split(">")[0]
            pending_actual += operation.split(">", 1)[1]
    flush()
    return differences, counts


# ────────────────────────────── OCR ──────────────────────────────


def find_tesseract(explicit: str | None) -> str | None:
    if explicit:
        return explicit if Path(explicit).is_file() else None
    return shutil.which("tesseract")


def tesseract_langs(tesseract: str) -> list[str]:
    try:
        result = subprocess.run(
            [tesseract, "--list-langs"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=60,
        )
    except (OSError, subprocess.SubprocessError):
        return []
    langs = []
    for line in (result.stdout or "").splitlines():
        line = line.strip()
        # 目录行形如 `List of available languages in "..."`；语言码是纯字母数字下划线
        if line and " " not in line and line.replace("_", "").isalnum():
            langs.append(line)
    return langs


def ocr_image(tesseract: str, image: Path, args: argparse.Namespace) -> str:
    """对一张页图跑一次 Tesseract（参数与生产一致，见模块 docstring 的对照表）。"""
    command = [
        tesseract,
        str(image),
        "stdout",
        "-l",
        args.languages,
        "--dpi",
        str(args.dpi),
        "-c",
        f"user_defined_dpi={args.dpi}",
        "-c",
        f"preserve_interword_spaces={1 if args.preserve_spacing else 0}",
    ]
    if args.psm is not None:
        command += ["--psm", str(args.psm)]
    result = subprocess.run(
        command,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=args.timeout,
    )
    if result.returncode != 0:
        raise RuntimeError(f"tesseract 失败：{result.stderr.strip()[:400]}")
    return result.stdout or ""


# ────────────────────────────── 主流程 ──────────────────────────────


def load_reference_pages() -> dict[str, str]:
    data = json.loads(GROUND_TRUTH.read_text(encoding="utf-8"))
    pages = data.get("scanned.pdf")
    if not isinstance(pages, dict):
        raise RuntimeError(f"{GROUND_TRUTH.name} 里没有 scanned.pdf 的逐页原文（形状对不上）")
    return {str(key): str(value) for key, value in pages.items()}


def measure(tesseract: str, args: argparse.Namespace, dump_dir: Path | None) -> list[PageReading]:
    """逐页跑一次 OCR 并算出读数。"""
    reference_pages = load_reference_pages()
    document = pdfium.PdfDocument(str(SCANNED))
    if len(document) != len(reference_pages):
        raise RuntimeError(
            f"页数对不上：scanned.pdf 有 {len(document)} 页，夹具里有 {len(reference_pages)} 页"
        )

    readings: list[PageReading] = []
    for index in range(len(document)):
        page_no = str(index + 1)
        image = document[index].render(scale=args.dpi / 72).to_pil().convert("RGB")

        with tempfile.TemporaryDirectory(prefix="ocr-page-") as scratch:
            image_path = Path(scratch) / f"page-{page_no}.png"
            image.save(image_path, "PNG")
            actual_raw = ocr_image(tesseract, image_path, args)

        reference_page = canonicalize(reference_pages[page_no])
        actual_page = canonicalize(actual_raw)

        # 主口径：折叠空白后逐字比
        reference = collapse_whitespace(reference_page)
        actual = collapse_whitespace(actual_page)
        if max(len(reference), len(actual)) > MAX_COMPARABLE_LENGTH:
            raise RuntimeError(
                f"第 {page_no} 页文本 {len(reference)}/{len(actual)} 字，超出可比规模"
                f"（{MAX_COMPARABLE_LENGTH}）—— 拒绝给出一个算不完的数"
            )
        distance = edit_distance(reference, actual)
        accuracy = 1.0 - (distance / len(reference)) if reference else 0.0

        # 次口径（loose）：换行→单空格，保留空格个数
        loose_reference = " ".join(reference_page.split("\n"))
        loose_actual = " ".join(actual_page.split("\n"))
        loose_distance = edit_distance(loose_reference, loose_actual)
        loose_accuracy = (
            1.0 - (loose_distance / len(loose_reference)) if loose_reference else 0.0
        )

        # 第三口径：去掉全部空白（把"空格增删"也排除）
        reference_flat, actual_flat = strip_whitespace(reference), strip_whitespace(actual)
        flat_distance = edit_distance(reference_flat, actual_flat)
        flat_accuracy = 1.0 - (flat_distance / len(reference_flat)) if reference_flat else 0.0

        # ★ 差异清单用**逐行规范形**两两对齐（换行→单空格）；单字差异只有在这一版上才定位得准
        #   （把空白折叠成单空格会让"折叠掉的空白"也进入 opcode 流，差异块被拆散）。
        #   ⚠️ 所以差异清单里的"空白"块**不要**与主指标的编辑距离逐块对账 ——
        #     空白该怎么算由三档口径回答，这里只回答"错在哪个字上"。
        differences, counts = collect_differences(
            reference_page.replace("\n", " "),
            actual_page.replace("\n", " "),
            limit=args.diff_limit,
        )
        readings.append(
            PageReading(
                page=page_no,
                reference_length=len(reference),
                distance=distance,
                accuracy=accuracy,
                loose_reference_length=len(loose_reference),
                loose_distance=loose_distance,
                loose_accuracy=loose_accuracy,
                flat_reference_length=len(reference_flat),
                flat_distance=flat_distance,
                flat_accuracy=flat_accuracy,
                differences=differences,
                counts=counts,
                reference_lines=reference_page.split("\n") if reference_page else [],
                actual_lines=actual_page.split("\n") if actual_page else [],
            )
        )

        if dump_dir is not None:
            image.save(dump_dir / f"page-{page_no}.png", "PNG")
            (dump_dir / f"page-{page_no}.ocr.txt").write_text(actual_raw, encoding="utf-8")
            (dump_dir / f"page-{page_no}.reference.txt").write_text(
                reference_pages[page_no], encoding="utf-8"
            )
    return readings


def print_reading(readings: list[PageReading], args: argparse.Namespace) -> None:
    print("| 页 | 参考字数 | 编辑距离 | **字符准确率** | 保留空白 | 去全部空白 | 差异块：空白/全半角/字符 |")
    print("|---:|---:|---:|---:|---:|---:|---|")
    for row in readings:
        counts = row.counts
        print(
            f"| {row.page} | {row.reference_length} | {row.distance} | "
            f"**{row.accuracy * 100:.2f}%** | {row.loose_accuracy * 100:.2f}% | "
            f"{row.flat_accuracy * 100:.2f}% | "
            f"{counts.get('空白', 0)}/{counts.get('全半角标点', 0)}/{counts.get('字符', 0)} |"
        )

    total_reference = sum(row.reference_length for row in readings)
    total_distance = sum(row.distance for row in readings)
    total_loose_reference = sum(row.loose_reference_length for row in readings)
    total_loose_distance = sum(row.loose_distance for row in readings)
    total_flat_reference = sum(row.flat_reference_length for row in readings)
    total_flat_distance = sum(row.flat_distance for row in readings)
    micro = 1.0 - total_distance / total_reference if total_reference else 0.0
    micro_loose = (
        1.0 - total_loose_distance / total_loose_reference if total_loose_reference else 0.0
    )
    micro_flat = 1.0 - total_flat_distance / total_flat_reference if total_flat_reference else 0.0
    worst = min(readings, key=lambda row: row.accuracy)

    print()
    print("=== 两个聚合读数 ===")
    print(
        f"  微平均（Σ编辑距离 {total_distance} / Σ参考长度 {total_reference}）"
        f" = {micro * 100:.2f}%"
        f"   （保留空白 {micro_loose * 100:.2f}% · 去全部空白 {micro_flat * 100:.2f}%）"
    )
    print(
        f"  最差页（第 {worst.page} 页，参考 {worst.reference_length} 字，"
        f"编辑距离 {worst.distance}） = {worst.accuracy * 100:.2f}%"
    )
    character_blocks = sum(row.counts.get("字符", 0) for row in readings)
    print(
        f"  差异块合计：字符 {character_blocks} 处 · "
        f"全半角标点 {sum(row.counts.get('全半角标点', 0) for row in readings)} 处 · "
        f"空白 {sum(row.counts.get('空白', 0) for row in readings)} 处"
        f"（★ 只有「字符」那一栏是识别错误）"
    )

    print()
    print("=== 逐页差异样例（★ 只报告，不修）===")
    for row in readings:
        print(f"  ── 第 {row.page} 页（准确率 {row.accuracy * 100:.2f}%）")
        if not row.differences:
            print("     与参考逐字一致")
            continue
        for difference in row.differences:
            print(f"     [{difference.kind}] 参考 {difference.reference!r} → 实际 {difference.actual!r}")
        hidden = sum(row.counts.values()) - len(row.differences)
        if hidden > 0:
            print(f"     （另有 {hidden} 处差异块未列出）")

    print()
    print("=== 行级差异（difflib，忽略空行 —— 用来看整行丢失 / 多出行）===")
    for row in readings:
        print(
            f"  ── 第 {row.page} 页：参考 {len(row.reference_lines)} 个非空行 / "
            f"识别 {len(row.actual_lines)} 个非空行"
        )
        matcher = difflib.SequenceMatcher(
            a=row.reference_lines, b=row.actual_lines, autojunk=False
        )
        for tag, i1, i2, j1, j2 in matcher.get_opcodes():
            if tag == "equal":
                continue
            print(f"     [{TAG_LABEL[tag]}] 参考第 {i1 + 1}–{i2} 行 / 识别第 {j1 + 1}–{j2} 行")
            for line in row.reference_lines[i1:i2]:
                print(f"        参考 {line!r}")
            for line in row.actual_lines[j1:j2]:
                print(f"        识别 {line!r}")


def main() -> int:
    parser = argparse.ArgumentParser(description="OCR 逐页字符准确率读数（Issue #71 L1）")
    parser.add_argument("--languages", default=DEFAULT_LANGUAGES, help="Tesseract 语言参数")
    parser.add_argument("--dpi", type=int, default=DEFAULT_DPI, help="光栅化 DPI（生产下限 300）")
    parser.add_argument("--tesseract", default=None, help="tesseract 可执行文件（默认走 PATH）")
    parser.add_argument("--psm", type=int, default=None, help="页面分割模式（默认不传 = 引擎默认 AUTO）")
    parser.add_argument(
        "--no-preserve-spacing",
        dest="preserve_spacing",
        action="store_false",
        help="★ 反证用：关掉词间空格重建（生产显式写 true，关掉中文会被打散）",
    )
    parser.add_argument("--timeout", type=int, default=180, help="单页 OCR 超时秒数")
    parser.add_argument("--diff-limit", type=int, default=8, help="每页最多列几处差异块")
    parser.add_argument("--dump-overlays", action="store_true", help="另存页图与 OCR/参考文本")
    args = parser.parse_args()

    if not GROUND_TRUTH.exists() or not SCANNED.exists():
        print(f"★ 夹具不在：{GROUND_TRUTH} / {SCANNED}")
        print("  先跑：python backend/tools/generate_parse_fixtures.py 与 make_ocr_fixtures.py")
        return 1

    tesseract = find_tesseract(args.tesseract)
    if not tesseract:
        print("★ 找不到 tesseract 可执行文件 ⇒ 量不了（★ 这不是 0，是没有读数）")
        return 1

    wanted = [lang.strip() for lang in args.languages.split("+") if lang.strip()]
    available = tesseract_langs(tesseract)
    missing = [lang for lang in wanted if lang not in available]
    if missing:
        print(f"★ tesseract 在（{tesseract}），但缺语言包：{'、'.join(missing)}（现有：{available}）")
        print("  缺中文包时中文会退化成方块/乱码，而且不报错 ⇒ 不量")
        return 1

    dump_dir = Path(tempfile.mkdtemp(prefix="ocr-accuracy-")) if args.dump_overlays else None

    print("=== OCR 逐页字符准确率（Issue #71 · L1 基线）===")
    print(f"夹具      ：{SCANNED.name}（3 页 · 无文本层）")
    print(f"参考文本  ：{GROUND_TRUTH.name} 的 scanned.pdf 逐页原文（= noisy.pdf 的文本层）")
    print(
        f"引擎      ：{tesseract}（语言 {args.languages} · dpi {args.dpi} · "
        f"psm {args.psm if args.psm is not None else '默认(AUTO)'} · "
        f"preserve_interword_spaces={1 if args.preserve_spacing else 0}）"
    )
    print("指标      ：1 − 编辑距离 / 参考长度（逐页；Levenshtein，本脚本自带实现，零新依赖）")
    if dump_dir:
        print(f"核对产物  ：{dump_dir}")
    print()

    readings = measure(tesseract, args, dump_dir)
    print_reading(readings, args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
