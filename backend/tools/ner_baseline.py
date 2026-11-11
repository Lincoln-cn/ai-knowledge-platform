"""评测规则版与 ONNX 版实体识别，对比同一个测试集。

## 测试集在哪

`backend/tools/ner_testset.jsonl` —— 30 段真实语料 + 人工标注的机构名。
这是**判据的唯一来源**，脚本里不再内嵌任何答案。

## 标注原则（写测试集时逐条遵守）

1. **是**：出现「XX部」「XX委员会」「XX领导小组」且指一个具体的组织。
2. **不是**：
   - 泛指：「本部门」「各部门」「公司总部各部」「各分支机构」「总部各部」「相关部」
   - 职能描述：「归口管理部门」「承办部门」
   - 文档标题（如「固定资产管理办法」）—— 那是文件名不是机构
   - 系统名（「供应链协同平台」「协同办公平台」「统一运维管理平台」）
3. **只标原文里原样连续出现的片段。**
4. **同一个名字在 `expected` 里只出现一次**（这是「能不能认出来」的判据，
   不是出现次数；出现次数是 mention 表的行数，另一件事）。

## 为什么用精确匹配而不是「包含」

识别器输出 `应配合财务资产部` 时，用「包含」判据会把它算成命中 `财务资产部`
—— 那恰好掩盖了「吞并」这个问题。而吞并是规则版的主要失败形态。

## 已知局限

这批语料是合成的，每个文档都有「X 是公司 Y 的归口管理部门」这种句式，
机构名形态高度一致（12 个名字覆盖 38 个实体）。**它证明 ONNX 在规整文本上
更好，但不能证明它在真实语料上也更好。** 要更强的结论得换真实语料。
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TESTSET = ROOT / "backend" / "tools" / "ner_testset.jsonl"

# 与 `EntityExtractor` 同口径：排除虚词与关系标记词的字，再按后缀收尾。
VIRTUAL_WORDS = "的了是在和与及或对为把被将由按据须报经"
STOP_CHARS = "隶属归口下设包括负责主管牵头是指简称"
ORG_SUFFIXES = ("部", "处", "科", "室", "中心", "公司", "集团", "局", "委员会", "领导小组")

ORG_LABELS = ("organization", "company", "government")


def rule_extract(text: str) -> list[str]:
    """规则版：后缀驱动的字符类正则。

    ★ 这个实现**故意保持与生产代码同口径**（`HanlpEntityRecognizer` 的后缀判定 +
      `EntityExtractor` 的字符集）。如果为了让它跑分好看而加强它，读数就没有意义了。
    """
    head = f"[^\\s，。；：、（）()《》“”{VIRTUAL_WORDS}{STOP_CHARS}]"
    tail = "|".join(ORG_SUFFIXES)
    pattern = re.compile(head + r"{2,12}(?:" + tail + ")")
    return sorted({m.group(0) for m in pattern.finditer(text)})


def onnx_extract(rows: list[dict]) -> dict[str, list[str]]:
    """ONNX 版：BERT NER（CCKS 2020 的 29 类）→ 只留三类机构标签 → 去掉被包含的碎片。"""
    model_dir = ROOT / "models" / "bert-chinese-ner-onnx"
    if not (model_dir / "model.onnx").exists():
        print("⚠ 没有 ONNX 模型文件，跳过 ONNX 侧")
        return {}
    try:
        import onnxruntime as ort
        from tokenizers import Tokenizer
    except ImportError as error:
        print(f"⚠ 缺依赖（{error}），跳过 ONNX 侧")
        return {}

    config = json.loads((model_dir / "config.json").read_text(encoding="utf-8"))
    labels = [config["id2label"][str(i)] for i in range(len(config["id2label"]))]
    tokenizer = Tokenizer.from_file(str(model_dir / "tokenizer.json"))
    options = ort.SessionOptions()
    options.intra_op_num_threads = 4
    session = ort.InferenceSession(
        str(model_dir / "model.onnx"), options, providers=["CPUExecutionProvider"]
    )
    input_names = {i.name for i in session.get_inputs()}

    out: dict[str, list[str]] = {}
    for row in rows:
        encoded = tokenizer.encode(row["text"])
        ids = encoded.ids[:510]
        tokens = encoded.tokens[:510]
        feeds = {"input_ids": [ids], "attention_mask": [[1] * len(ids)]}
        if "token_type_ids" in input_names:
            feeds["token_type_ids"] = [[0] * len(ids)]
        logits = session.run(None, feeds)[0][0]
        picked = [labels[max(range(len(r)), key=lambda k: r[k])] for r in logits]

        merged, label_now, text_now = [], None, ""
        for token, label in zip(tokens, picked):
            base = label[2:] if label[:2] in ("B-", "I-") else label
            if label.startswith("B-"):
                if label_now and text_now:
                    merged.append((label_now, text_now))
                label_now, text_now = base, token
            elif label.startswith("I-") and label_now == base:
                text_now += token
            else:
                if label_now and text_now:
                    merged.append((label_now, text_now))
                label_now, text_now = None, ""

        if label_now and text_now:
            merged.append((label_now, text_now))

        cleaned = []
        for label, piece in merged:
            piece = (
                piece.replace("[CLS]", "").replace("[SEP]", "").replace("[PAD]", "").strip()
            )
            if piece and label in ORG_LABELS:
                cleaned.append((label, piece))

        # 去掉被更长的**同类**实体完全包含的碎片（`信息技术` ⊂ `信息技术部`）。
        # 跨 label 不丢 —— 那可能是真实的类别差异，不该在这一层吞掉。
        kept = []
        for label, piece in sorted(cleaned, key=lambda e: -len(e[1])):
            if any(l == label and p != piece and piece in p for l, p in kept):
                continue
            kept.append((label, piece))
        out[row["doc"]] = sorted({p for _, p in kept})
    return out


def score(expected: list[str], got: list[str]) -> dict:
    """精确匹配的 TP / FP / FN 与三率。"""
    want, have = set(expected), set(got)
    tp = len(want & have)
    return {
        "tp": tp,
        "fp": len(have - want),
        "fn": len(want - have),
        "precision": round(tp / len(have), 3) if have else None,
        "recall": round(tp / len(want), 3) if want else None,
        "missed": sorted(want - have),
        "wrong": sorted(have - want),
    }


def load() -> list[dict]:
    if not TESTSET.exists():
        sys.exit(f"缺测试集：{TESTSET}\n先用 30 段语料 + 人工标注生成它。")
    rows = [json.loads(line) for line in TESTSET.read_text(encoding="utf-8").splitlines() if line.strip()]
    if not rows:
        sys.exit(f"测试集是空的：{TESTSET}")
    return rows


def main():
    rows = load()
    onnx_by_doc = onnx_extract(rows)

    totals = {"rule": {"tp": 0, "fp": 0, "fn": 0}, "onnx": {"tp": 0, "fp": 0, "fn": 0}}
    for row in rows:
        expected = row["expected"]
        rule_got = rule_extract(row["text"])
        onnx_got = onnx_by_doc.get(row["doc"], [])
        rule_s = score(expected, rule_got)
        onnx_s = score(expected, onnx_got) if onnx_by_doc else None
        for key, part in (("rule", rule_s), ("onnx", onnx_s)):
            if part:
                for metric in ("tp", "fp", "fn"):
                    totals[key][metric] += part[metric]

        print(f"\n{row['doc']}")
        print(f"  期望 {expected}")
        print(f"  规则 {rule_got}")
        if rule_s["wrong"]:
            print(f"      多 {rule_s['wrong']}")
        if rule_s["missed"]:
            print(f"      漏 {rule_s['missed']}")
        if onnx_s:
            print(f"  ONNX {onnx_got}")
            if onnx_s["wrong"]:
                print(f"      多 {onnx_s['wrong']}")
            if onnx_s["missed"]:
                print(f"      漏 {onnx_s['missed']}")

    print(f"\n=== 汇总（{len(rows)} 段，精确匹配）===")
    for key, label in (("rule", "规则版"), ("onnx", "ONNX 版")):
        t = totals[key]
        p = t["tp"] / (t["tp"] + t["fp"]) if t["tp"] + t["fp"] else 0
        r = t["tp"] / (t["tp"] + t["fn"]) if t["tp"] + t["fn"] else 0
        f1 = 2 * p * r / (p + r) if p + r else 0
        print(f"{label}: TP {t['tp']} FP {t['fp']} FN {t['fn']} ⇒ P {p:.1%} R {r:.1%} F1 {f1:.1%}")


if __name__ == "__main__":
    main()
