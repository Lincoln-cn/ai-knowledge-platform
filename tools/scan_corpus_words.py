"""扫描合成语料里"叠词"与明显的模板错配（对四种格式统一抽取后比对）。

判据来自本仓踩过的坑：
  ① 叠词：申请申请 / 的的 / 了了 / 单单位 …
  ② SOP 措辞错配：消火栓已停机 / 电缆已停机（非电动对象不该有"停机"）
"""

from __future__ import annotations

import pathlib
import re
import sys

sys.path.insert(0, str(pathlib.Path("tools").resolve()))
from gen_corpus import docx_text, normalize, pdf_text  # noqa: E402

DOCS = pathlib.Path("corpus/synthetic/documents")
BAD_WORDS = ["申请申请", "了了", "和和", "在在", "对对的", "各各", "单单位", "部部门"]
NON_POWERED = ["消火栓", "电缆", "接地网", "避雷器", "灭火器"]


def read(path: pathlib.Path) -> str:
    suffix = path.suffix.lower()
    if suffix == ".pdf":
        return pdf_text(str(path))
    if suffix == ".docx":
        return docx_text(str(path))
    return path.read_text(encoding="utf-8", errors="replace")


def main() -> int:
    problems = 0
    for path in sorted(DOCS.iterdir()):
        if not path.is_file():
            continue
        text = normalize(read(path))
        for word in BAD_WORDS:
            count = text.count(word)
            if count:
                problems += 1
                print(f"[叠词] {path.name}: {word} × {count}")
        # ★ "标的的" 是合法的（"标的" + 的结构助词），不能当叠词 —— 用负向断言排除"标/目"后的"的的"。
        for match in re.finditer(r"(?<![标目])的的", text):
            problems += 1
            print(f"[叠词] {path.name}: {match.group(0)}")
        for obj in NON_POWERED:
            for match in re.finditer(obj + r"(仍|已)?停机", text):
                problems += 1
                print(f"[错配] {path.name}: {match.group(0)}")
    print(f"扫描完成：{len(list(DOCS.iterdir()))} 份，问题 {problems} 处")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
