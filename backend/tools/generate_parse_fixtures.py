#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成「Tika 解析成 Markdown」测试夹具（第 6 周 · 0.2.1 批 1）。

════════════════════════════════════════════════════════════════════════════
  ★ 为什么夹具要可复现生成，而不是只提交二进制
════════════════════════════════════════════════════════════════════════════
二进制 DOCX/PDF 在 diff 里不可读，坏了也不知道是怎么坏的。本脚本用固定
文本源一次生成全部夹具：要改断言里的期望文本，改这里再跑一遍即可。

  产出目录：backend/src/test/resources/fixtures/
  用法：    python backend/tools/generate_parse_fixtures.py

夹具清单（与 DocumentParseTest 的四条用例一一对应）：
  1. structured.docx   多级标题 + 有序/无序列表 + 表格
  2. structured.pdf    同上内容（未打 PDF tag —— 12 §2.2 已知结构会丢）
  3. gbk-text.txt      GBK 编码中文
  4. encrypted.pdf     打开密码的 PDF ⇒ 解析失败但原因可读
  5. truncated.docx    ZIP 尾部截断的 DOCX ⇒ 失败但可能残留部分正文
"""

from __future__ import annotations

import zipfile
from pathlib import Path

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH
from fpdf import FPDF
from pypdf import PdfReader, PdfWriter

FIXTURES = Path(__file__).resolve().parent.parent / "src" / "test" / "resources" / "fixtures"

# ── 共用正文（三类结构都要能认出来） ──────────────────────────────────────
TITLE_L1 = "第一章 总则"
TITLE_L2 = "1.1 制定目的"
TITLE_L3 = "1.1.1 适用范围"
UL_ITEMS = ["适用公司各部门", "适用正式员工", "适用外包人员须另签协议"]
OL_ITEMS = ["提交申请", "部门审批", "归档备案"]
TABLE = [
    ["项目", "标准", "备注"],
    ["住宿", "500 元/天", "一线城市"],
    ["交通", "实报实销", "需票据"],
]
GBK_LINES = [
    "差旅费报销规定",
    "第一条 本规定适用于公司全体员工。",
    "第二条 出差前须提交书面申请，经部门负责人批准后方可出行。",
    "住宿标准：一线城市每人每天不超过伍佰元。",
]


def build_docx(path: Path) -> None:
    doc = Document()
    doc.add_heading(TITLE_L1, level=1)
    doc.add_heading(TITLE_L2, level=2)
    doc.add_heading(TITLE_L3, level=3)

    p = doc.add_paragraph("本制度规定如下要点：")
    p.alignment = WD_ALIGN_PARAGRAPH.LEFT

    doc.add_heading("无序列表", level=2)
    for item in UL_ITEMS:
        doc.add_paragraph(item, style="List Bullet")

    doc.add_heading("有序列表", level=2)
    for item in OL_ITEMS:
        doc.add_paragraph(item, style="List Number")

    doc.add_heading("标准表", level=2)
    table = doc.add_table(rows=len(TABLE), cols=3)
    table.style = "Table Grid"
    for r, row in enumerate(TABLE):
        for c, cell in enumerate(row):
            table.rows[r].cells[c].text = cell

    doc.save(path)


class _StructuredPdf(FPDF):
    """结构化 PDF。未打 tag —— 与 12 §2.2「普通 PDF 只有段落流」一致。"""

    def footer(self) -> None:  # noqa: D102
        self.set_y(-15)


def build_pdf(path: Path) -> None:
    pdf = _StructuredPdf()
    # simhei.ttf 是 Windows 自带的中文字体，保证夹具里中文能嵌进 PDF
    pdf.add_font("simhei", "", r"C:\Windows\Fonts\simhei.ttf")
    pdf.add_page()
    pdf.set_font("simhei", size=16)
    pdf.cell(0, 10, TITLE_L1, new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("simhei", size=14)
    pdf.cell(0, 10, TITLE_L2, new_x="LMARGIN", new_y="NEXT")
    pdf.cell(0, 10, TITLE_L3, new_x="LMARGIN", new_y="NEXT")

    pdf.set_font("simhei", size=12)
    pdf.cell(0, 8, "本制度规定如下要点：", new_x="LMARGIN", new_y="NEXT")
    for item in UL_ITEMS:
        pdf.cell(0, 8, f"- {item}", new_x="LMARGIN", new_y="NEXT")
    for i, item in enumerate(OL_ITEMS, start=1):
        pdf.cell(0, 8, f"{i}. {item}", new_x="LMARGIN", new_y="NEXT")

    pdf.cell(0, 8, "标准表", new_x="LMARGIN", new_y="NEXT")
    col_w = 60
    for row in TABLE:
        for cell in row:
            pdf.cell(col_w, 8, cell, border=1)
        pdf.ln()
    pdf.output(path)


def build_encrypted_pdf(path: Path) -> None:
    plain = FIXTURES / "_plain_secret.pdf"
    build_pdf(plain)
    reader = PdfReader(plain)
    writer = PdfWriter(clone_from=reader)
    writer.encrypt("secret-password")
    path.write_bytes(b"")
    with path.open("wb") as f:
        writer.write(f)
    plain.unlink(missing_ok=True)


def build_partial_fail_pdf(path: Path) -> None:
    """两页 PDF：第一页完整；第二页内容流 /Length 远大于实际字节。

    ★ 为什么不用截断/坏 zlib/悬空引用/Crypt/JBIG2/inline image：PDFBox 对六者都很宽容
      （实测仍算成功或只静默丢页）。/Length 虚高会让流读取越过真实数据、在文本算符
      之后撞上后续对象字节并抛 IOException —— 此时第 1 页已进 handler（12 §2.1）。
    """
    from pypdf import PdfReader, PdfWriter
    from pypdf.generic import DecodedStreamObject, NameObject, NumberObject

    plain = FIXTURES / "_plain_twopage.pdf"
    pdf = FPDF()
    pdf.add_font("simhei", "", r"C:\Windows\Fonts\simhei.ttf")
    pdf.add_page()
    pdf.set_font("simhei", size=14)
    pdf.cell(0, 10, "第一章 总则", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("simhei", size=12)
    pdf.cell(0, 8, "适用公司各部门", new_x="LMARGIN", new_y="NEXT")
    pdf.add_page()
    pdf.set_font("simhei", size=12)
    pdf.cell(0, 8, "第二页开头这句话应当留下", new_x="LMARGIN", new_y="NEXT")
    pdf.output(plain)

    reader = PdfReader(plain)
    writer = PdfWriter(clone_from=reader)
    page2 = writer.pages[1]

    raw = b"BT /F1 12 Tf 50 700 Td (partial-content-kept) Tj ET\n"
    stream = DecodedStreamObject()
    stream.set_data(raw)
    # ★ 虚高 /Length：读取器会越过 raw 末尾，继续吞后面的 PDF 对象字节直到出错
    stream[NameObject("/Length")] = NumberObject(len(raw) + 5000)
    page2[NameObject("/Contents")] = writer._add_object(stream)  # noqa: SLF001

    with path.open("wb") as f:
        writer.write(f)
    plain.unlink(missing_ok=True)


def build_empty_docx(path: Path) -> None:
    """合法但无正文的 DOCX —— 钉住「0 字符 ≠ 成功」规则（空文档/扫描件无文本层都走这条路）。"""
    from docx import Document as NewDocument

    doc = NewDocument()
    doc.add_paragraph("")
    doc.save(str(path))


def build_partial_fail_docx(src: Path, dest: Path) -> None:
    """中途失败夹具：ZIP 完好，但 word/document.xml 在正文中途被截断。

    ★ 为什么不用 partial-fail.pdf：PDFBox 对坏流/悬空引用/Crypt 一律吞掉、仍算成功，
      做不出「吐一半再抛」。而 OOXML 的 SAX 解析是边读边吐 —— document.xml 截断在
      中途时，前几个段落已经进 handler，随后抛错。这正是「保留部分内容」的真实场景
      （12 §2.1 的 catch 分支）。
    """
    from xml.etree import ElementTree  # noqa: F401  （仅占位：确认 xml 可解析到一半）

    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dest, "w") as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            if info.filename == "word/document.xml":
                # 只截断正文 XML 本体（保留前面的段落/表格），后面裁掉 ⇒ 解析中途抛
                cut = max(1, int(len(data) * 0.55))
                data = data[:cut]
            zout.writestr(info, data)


def build_truncated_docx(src: Path, dest: Path) -> None:
    """把 document.xml 在中间截断后重打包：Word 文档损坏、无法完整解析。

    ★ 为什么不能只截整个 ZIP：POI 对缺中心目录的包会走 stream fallback（实测），
      文字照样抽全 ⇒ 夹具「损坏」不了。改成截断正文 XML —— DOM 加载阶段就会抛错。
    """
    with zipfile.ZipFile(src) as zin:
        doc_xml = zin.read("word/document.xml")

    marker = "<w:t>提交申请</w:t>".encode("utf-8")
    cut = doc_xml.find(marker)
    if cut < 0:
        cut = int(len(doc_xml) * 0.6)
    else:
        cut += len(marker)
    broken_xml = doc_xml[:cut]

    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dest, "w") as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            if info.filename == "word/document.xml":
                data = broken_xml
            zout.writestr(info, data)


def build_gbk_txt(path: Path) -> None:
    path.write_bytes(("\n".join(GBK_LINES) + "\n").encode("gbk"))


def build_md(path: Path) -> None:
    path.write_text(
        f"# {TITLE_L1}\n\n## {TITLE_L2}\n\n### {TITLE_L3}\n\n"
        + "\n".join(f"- {item}" for item in UL_ITEMS)
        + "\n\n"
        + "\n".join(f"{i}. {item}" for i, item in enumerate(OL_ITEMS, start=1))
        + "\n\n| "
        + " | ".join(TABLE[0])
        + " |\n| "
        + " | ".join(["---"] * 3)
        + " |\n"
        + "\n".join("| " + " | ".join(row) + " |" for row in TABLE[1:])
        + "\n",
        encoding="utf-8",
    )


def main() -> None:
    FIXTURES.mkdir(parents=True, exist_ok=True)
    docx_path = FIXTURES / "structured.docx"
    build_docx(docx_path)
    build_pdf(FIXTURES / "structured.pdf")
    build_partial_fail_pdf(FIXTURES / "partial-fail.pdf")
    build_encrypted_pdf(FIXTURES / "encrypted.pdf")
    build_truncated_docx(docx_path, FIXTURES / "truncated.docx")
    build_partial_fail_docx(docx_path, FIXTURES / "partial-fail.docx")
    build_empty_docx(FIXTURES / "empty.docx")
    build_gbk_txt(FIXTURES / "gbk-text.txt")
    build_md(FIXTURES / "structured.md")
    # 纯字节垃圾：比 truncated 更彻底的「打不开」，用于失败原因可读性
    (FIXTURES / "garbage.bin").write_bytes(b"\x00\x01\x02 not a document \xff\xfe")
    for f in sorted(FIXTURES.iterdir()):
        print(f"  {f.name:20s} {f.stat().st_size:7d} bytes")


if __name__ == "__main__":
    main()
