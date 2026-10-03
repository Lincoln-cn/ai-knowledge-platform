#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 OCR 链的扫描件夹具（第 9 周 · 0.3.1）。

════════════════════════════════════════════════════════════════════════════
  ★ 为什么要"造"夹具，而不是拍一份真扫描件
════════════════════════════════════════════════════════════════════════════

OCR 链的验收判据是「上传扫描件 → 已识别 N/M 页 → 入库成功」，而**扫描件的定义是"没有文本层"** ——
仓库里原有的 PDF 全都有文本层（`structured.pdf` / `noisy.pdf` 逐页文本量 99–211 字），
`scanned-like.pdf` 则是**空的**（0 文本、869 字节，没有可识别的内容）。

⇒ 做法：把**带文本层的** PDF 逐页光栅化成图片，再拼成"只有图、没有文本层"的 PDF。
这样造出来的夹具正好是**最有用的一种**：★ **答案已知**（原文就是源 PDF 的文本），
所以"OCR 出来的 Markdown 是否含原来的章节标题"可以写成断言，而不是靠人眼比对。

════════════════════════════════════════════════════════════════════════════
  两个夹具，各对应判据里的一档
════════════════════════════════════════════════════════════════════════════

| 夹具 | 形态 | 对应判据（`ROADMAP` 第 9 周） |
|---|---|---|
| `scanned.pdf` | 3 页**全部**无文本层 | ① 逐页判定（每页都该走 OCR）③ OCR 文本走另一套分块规则 |
| `mixed.pdf` | 第 1 页**有**文本层，第 2–3 页**无** | ① ★ **混合型 PDF 逐页处理** —— 只有后两页该走 OCR |
| `ocr-ground-truth.json` | 上面两个夹具**逐页的原文** | ② OCR → Markdown 的质量断言（不要人眼比对） |

★ 源用的是 `noisy.pdf` 而不是 `structured.pdf`：它有 **3 页**、而且**每页都重复页眉页脚**
（「某某集团内部管理制度」/「内部资料 请勿外传」/ 页码）—— 顺手把 `12 §11.6` 的
「页眉页脚去重」也变成了可断言的东西（扫描件每页都有 ⇒ 重复次数天然够）。

════════════════════════════════════════════════════════════════════════════
  用法
════════════════════════════════════════════════════════════════════════════

    python backend/tools/make_ocr_fixtures.py            # 写入 src/test/resources/fixtures/
    python backend/tools/make_ocr_fixtures.py --dpi 200  # 想要更小的文件

★ 它**是幂等的**：重复跑覆盖同一批文件，输出与输入一致则字节一致（不掺时间戳）。
"""
from __future__ import annotations

import argparse
import io
import json
import sys
from pathlib import Path

import pypdfium2 as pdfium
from PIL import Image

FIXTURES = Path(__file__).resolve().parent.parent / "src/test/resources/fixtures"
SOURCE = FIXTURES / "noisy.pdf"


def render_page(doc: pdfium.PdfDocument, index: int, dpi: int) -> Image.Image:
    """把一页光栅化成图片（`scale = dpi / 72`，PDF 用户单位是 1/72 英寸）。"""
    page = doc[index]
    bitmap = page.render(scale=dpi / 72)
    return bitmap.to_pil().convert("RGB")


def save_image_only_pdf(images: list[Image.Image], out: Path, dpi: int) -> None:
    """把一串图片拼成"只有图"的 PDF。

    ★ 不写任何文本对象 —— 这正是"扫描件"的定义。Pillow 对 RGB 走 JPEG（DCTDecode），
    与真实扫描件的压缩方式一致，体积也压得住。
    """
    images[0].save(
        out,
        "PDF",
        save_all=True,
        append_images=images[1:],
        resolution=dpi,
        quality=85,
    )


def page_text(doc: pdfium.PdfDocument, index: int) -> str:
    return doc[index].get_textpage().get_text_range()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dpi", type=int, default=300, help="光栅化 DPI（判据要求 ≥300）")
    args = parser.parse_args()

    if not SOURCE.exists():
        print(f"源 PDF 不在：{SOURCE}")
        return 1

    source = pdfium.PdfDocument(str(SOURCE))
    pages = [render_page(source, i, args.dpi) for i in range(len(source))]
    print(f"源 {SOURCE.name}：{len(pages)} 页，光栅化 @ {args.dpi} dpi，每页 {pages[0].size}")

    # ── ① 全扫描件：3 页全是图 ──
    scanned = FIXTURES / "scanned.pdf"
    save_image_only_pdf(pages, scanned, args.dpi)

    # ── ② 混合型：第 1 页保留原文（有文本层），第 2–3 页换成图 ──
    mixed_images = FIXTURES / "_mixed_images.pdf"
    save_image_only_pdf(pages[1:], mixed_images, args.dpi)

    import pypdf

    reader = pypdf.PdfReader(str(SOURCE))
    image_reader = pypdf.PdfReader(str(mixed_images))
    writer = pypdf.PdfWriter()
    writer.add_page(reader.pages[0])  # ★ 带文本层的那一页
    for page in image_reader.pages:  # 只有图的两页
        writer.add_page(page)
    mixed = FIXTURES / "mixed.pdf"
    with mixed.open("wb") as handle:
        writer.write(handle)
    mixed_images.unlink()

    # ── ③ 逐页原文（质量断言的"答案"）──
    ground_truth = {
        "note": "由 make_ocr_fixtures.py 从 noisy.pdf 生成；scanned/mixed 只是同一批页面的两种形态",
        "source": SOURCE.name,
        "dpi": args.dpi,
        "scanned.pdf": {str(i + 1): page_text(source, i) for i in range(len(source))},
        "mixed.pdf": {
            "1": page_text(source, 0),
            "2": page_text(source, 1),
            "3": page_text(source, 2),
        },
        "expect": {
            "scanned.pdf": "逐页文本量都 < 10 ⇒ 三页都该走 OCR",
            "mixed.pdf": "第 1 页有文本层 ⇒ 只有第 2、3 页该走 OCR",
        },
    }
    truth = FIXTURES / "ocr-ground-truth.json"
    truth.write_text(
        json.dumps(ground_truth, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    # ── 自检：造出来的东西必须真的"没有文本层" ──
    print("\n生成结果（逐页文本长度 —— ★ 图页必须是 0，否则这个夹具根本没在验 OCR）：")
    failed = False
    for name, want in ((scanned, [0, 0, 0]), (mixed, [len(page_text(source, 0)), 0, 0])):
        doc = pdfium.PdfDocument(str(name))
        got = [len(page_text(doc, i)) for i in range(len(doc))]
        size_kb = name.stat().st_size // 1024
        flag = "ok " if got == want else "★ NG"
        if got != want:
            failed = True
        print(f"  {flag} {name.name:14} 页数={len(doc)} 逐页文本={got} 期望={want} {size_kb} KB")

    print(f"\n逐页原文写入 {truth.name}（{len(ground_truth['scanned.pdf'])} 页）")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
