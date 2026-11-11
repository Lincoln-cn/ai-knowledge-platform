"""ONNX NER 与 HanLP 在制度文本上的对比评测。

为什么要有这个脚本：换识别器之前必须先知道「换完能多认出什么、多认错什么」。
判据不是「谁赢」，而是 **ONNX 独有、HanLP 漏掉的实体有多少条** —— 如果只有三五条，
串联两个实现不值得做（多一层就多一个出错的地方）。

用法：
    python backend/tools/ner_eval.py --limit 30
    python backend/tools/ner_eval.py --limit 0 --json .build/ner-eval.json

数据源默认 `.build/rerank/candidates.jsonl`（195 条 × 每条 20 候选段，段正文约 406 字），
取段正文非空的去重后的前 N 段。

输出三张表：
    1. ONNX 抽到的实体（按类型分组，含置信度）
    2. 只用后缀规则的抽取结果（规则内联在这里，与 Java 侧 EntityExtractor 的规则一致）
    3. 两者对照：ONNX 独有 / 规则独有 / 都认

已知局限（写在这里而不是藏起来）：
    * HanLP 那一侧这里用**后缀规则复刻**，不是真的调 HanLP 分词。理由是 Java 侧的分词
      需要起 JVM，而这一轮要判断的是「ONNX 能补多少」—— 后缀规则是它的下限（HanLP 分词
      只会比它更宽，不会更窄），所以这里的「ONNX 独有」是**乐观估计**，实际收益可能更低。
    * ONNX 的 B-/I- 拼接按 token 做，而 BERT 用的是 WordPiece（≈单字）⇒ 边界可能切在
      词中间。脚本会把每个实体连同它周围的原文一起打出来，便于人工判断边界对不对。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MODEL_DIR = ROOT / "models" / "bert-chinese-ner-onnx"
CANDIDATES = ROOT / ".build" / "rerank" / "candidates.jsonl"

# 与 EntityExtractor 保持一致的后缀表（内置默认那份）。
ORG_SUFFIXES = ("部", "处", "科", "室", "中心", "公司", "集团", "局", "委员会", "领导小组")

# CCKS 2020 里属于「组织/机构」的三类。其余 26 类一律丢弃 —— 这一点是本脚本的
# 第一道过滤，也是 OnnxNerRecognizer 必须做的一件事：模型的高召回靠「什么都愿意抽」。
ORG_LABELS = ("organization", "company", "government")

# 模型输出的最大长度。BERT 的位置编码是 512，超过会被截断。
MAX_TOKENS = 510


def load_transformer():
    from tokenizers import Tokenizer

    path = MODEL_DIR / "tokenizer.json"
    if not path.exists():
        sys.exit(f"缺 {path}：先下模型（见 ROOT/models/bert-chinese-ner-onnx/）")
    return Tokenizer.from_file(str(path))


def load_session():
    import onnxruntime as ort

    path = MODEL_DIR / "model.onnx"
    if not path.exists():
        sys.exit(f"缺 {path}：先下模型")
    options = ort.SessionOptions()
    # 与本项目 OnnxRerankerAdapter 一致：显式限定线程数，否则会用满机器、
    # 在 CI 上反而慢（重排那次的实测是 15.10s → 10.18s）。
    options.intra_op_num_threads = max(1, (__import__("os").cpu_count() or 4) // 2)
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def id2label() -> list[str]:
    config = json.loads((MODEL_DIR / "config.json").read_text(encoding="utf-8"))
    mapping = config.get("id2label")
    if not mapping:
        sys.exit("config.json 里没有 id2label：ONNX NER 的标签顺序是模型自带的，"
                 "不读就只能硬编码，硬编码必然错。")
    # id2label 的 key 是字符串形式的数字，按数值排序才是真实顺序。
    return [mapping[str(i)] for i in range(len(mapping))]


def predict(session, tokenizer, labels, text):
    """返回 [(label, 原文片段)]。B-/I- 在这里拼接，切在词中间也照原样拼。"""
    encoded = tokenizer.encode(text)
    tokens = encoded.tokens
    if len(tokens) > MAX_TOKENS:
        tokens = tokens[:MAX_TOKENS]

    # tokenizers 的 ids 含 [CLS]/[SEP]，ONNX 输入名按模型来取。
    ids = encoded.ids[:MAX_TOKENS]
    input_names = {i.name for i in session.get_inputs()}
    feeds = {
        "input_ids": [ids],
        "attention_mask": [[1] * len(ids)],
    }
    if "token_type_ids" in input_names:
        feeds["token_type_ids"] = [[0] * len(ids)]

    logits = session.run(None, feeds)[0][0]
    picked = [labels[max(range(len(row)), key=lambda k: row[k])] for row in logits]

    merged, current_label, current_text = [], None, ""
    for token, label in zip(tokens, picked):
        base = label[2:] if label[:2] in ("B-", "I-") else label
        if label.startswith("B-"):
            if current_label and current_text:
                merged.append((current_label, current_text))
            current_label, current_text = base, token
        elif label.startswith("I-") and current_label == base:
            current_text += token
        else:
            if current_label and current_text:
                merged.append((current_label, current_text))
            current_label, current_text = None, ""
    if current_label and current_text:
        merged.append((current_label, current_text))

    # 丢弃 WordPiece 的特殊 token，再去掉首尾空白。
    cleaned = []
    for label, piece in merged:
        piece = piece.replace("[CLS]", "").replace("[SEP]", "").replace("[PAD]", "").strip()
        if piece:
            cleaned.append((label, piece))
    return drop_overlaps(cleaned)


def drop_overlaps(entities):
    """丢掉被更长的同类实体**完全包含**的那些。

    第一版没有这一步，读数里出现了 `生` / `生产` / `生产制造` / `生产制造部` 四个重叠
    span 同时被当成实体 —— 它们是同一处文本，不是四处。把它们算成「ONNX 独有」是
    虚高的收益，因为真正新增的只有最长的那个。

    只丢「被更长且同类实体包含」的，不丢部分重叠的（部分重叠往往是真的两个实体挨着，
    比如「公司总部各部」）。
    """
    kept = []
    for label, piece in sorted(entities, key=lambda e: -len(e[1])):
        if any(
            other_label == label and other_piece != piece and piece in other_piece
            for other_label, other_piece in kept
        ):
            continue
        kept.append((label, piece))
    return sorted(kept, key=lambda e: e[1])


# 与 EntityExtractor 的 VIRTUAL_WORDS 一致。第一版脚本漏了这一条，规则侧抽出
# 「应配合财务资产部」「负责编制本部」这类跨虚词吞并的串 —— 那不是规则的能力上限，
# 是我把规则的下限写漏了。评测脚本如果比产品更差，测出来的差距就是假的。
VIRTUAL_WORDS = "的了是在和与及或对为把被将由按据须报经"

# 与 EntityExtractor 一致：关系标记词的每个字都不能出现在机构名里。
STOP_CHARS = "隶属归口下设包括负责主管牵头是指简称"


def rule_extract(text):
    """只用后缀规则抽机构名 —— 这是 HanLP 那条链路的下限。

    字符集与 EntityExtractor 的 HEAD_CHARS 同义：从标点或空白之后开始，连续取
    汉字/字母，但**不含虚词与关系标记字**，遇到后缀字结束。
    """
    head = f"[^\\s，。；：、（）()《》“”{VIRTUAL_WORDS}{STOP_CHARS}]"
    tail = "|".join(ORG_SUFFIXES)
    pattern = re.compile(head + r"{2,12}(?:" + tail + ")")
    return sorted({m.group(0) for m in pattern.finditer(text)})


def load_segments(limit):
    if not CANDIDATES.exists():
        sys.exit(f"缺 {CANDIDATES}：先用 tools/check_b1_b3_quality.py --stage eval 落一份候选")
    seen, segments = set(), []
    for line in CANDIDATES.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        for item in row.get("items", []):
            content = (item.get("content") or "").strip()
            if not content or content in seen:
                continue
            seen.add(content)
            segments.append(content)
            if limit and len(segments) >= limit:
                return segments
    return segments


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=30, help="评测前 N 段（0 = 全部）")
    parser.add_argument("--json", default="", help="把结果写到这个路径")
    args = parser.parse_args()

    segments = load_segments(args.limit)
    if not segments:
        sys.exit("没读到任何段：candidates.jsonl 是空的？")

    tokenizer = load_transformer()
    session = load_session()
    labels = id2label()
    print(f"模型标签 {len(labels)} 类；评测 {len(segments)} 段\n")

    started = time.time()
    rows = []
    for index, text in enumerate(segments, 1):
        predicted = [(label, piece) for label, piece in predict(session, tokenizer, labels, text)
                     if label in ORG_LABELS]
        onnx_orgs = sorted({piece for _, piece in predicted})
        rule_orgs = rule_extract(text)
        rows.append({
            "index": index,
            "text": text,
            "onnx": [{"label": label, "name": piece} for label, piece in predicted],
            "onnxOrgs": onnx_orgs,
            "ruleOrgs": rule_orgs,
            "onnxOnly": sorted(set(onnx_orgs) - set(rule_orgs)),
            "ruleOnly": sorted(set(rule_orgs) - set(onnx_orgs)),
            "both": sorted(set(onnx_orgs) & set(rule_orgs)),
        })
        print(f"[{index}/{len(segments)}] onnx={onnx_orgs} rule={rule_orgs}")
        if rows[-1]["onnxOnly"]:
            print(f"        ★ ONNX 独有：{rows[-1]['onnxOnly']}")
        if rows[-1]["ruleOnly"]:
            print(f"        ○ 规则独有：{rows[-1]['ruleOnly']}")

    elapsed = time.time() - started
    onnx_only = sorted({n for r in rows for n in r["onnxOnly"]})
    rule_only = sorted({n for r in rows for n in r["ruleOnly"]})
    both = sorted({n for r in rows for n in r["both"]})

    print("\n=== 汇总 ===")
    print(f"耗时 {elapsed:.1f}s / {len(rows)} 段 ⇒ {elapsed / max(1, len(rows)) * 1000:.0f}ms/段")
    print(f"ONNX 独有 {len(onnx_only)} 个：{onnx_only}")
    print(f"规则独有 {len(rule_only)} 个：{rule_only}")
    print(f"两者都认 {len(both)} 个：{both}")

    if args.json:
        out = Path(args.json)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps({
            "segments": len(rows),
            "elapsedSeconds": round(elapsed, 1),
            "msPerSegment": round(elapsed / max(1, len(rows)) * 1000),
            "onnxOnly": onnx_only,
            "ruleOnly": rule_only,
            "both": both,
            "rows": rows,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n已写入 {out}")

    # ★ 判据的落点：ONNX 独有的实体数。只有三五条 ⇒ 串联不值得做。
    if len(onnx_only) < 5:
        print("\n⚠ ONNX 独有 < 5 条：按 §11.33 的判据，串联两个实现不值得做。")


if __name__ == "__main__":
    main()
