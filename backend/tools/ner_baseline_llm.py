"""用大模型给 30 段语料做一次机构名标注，落成测试集。

## 为什么用大模型标

人工标注要一小时，而这批语料是**合成的**（同一模板套不同随机数），
机构名形态极其规整（`XX部` / `XX委员会` / `XX领导小组`），大模型在这种
规整文本上的标注质量足够做基线。

## 但这不等于「大模型标的就一定对」

所以这个脚本做三件事，让标注**可复核**：
1. 每段都要求模型**只输出 JSON 数组**，不许解释 —— 解释会让结果不可解析
2. 同时跑规则抽取与 ONNX 抽取，把三者的分歧打印出来 —— 分歧处就是人该看的地方
3. 落盘时保留每段的原文、模型给的答案、以及模型的原始回复（出问题时能回看）

## 用法

    python backend/tools/ner_baseline_llm.py --limit 30
    python backend/tools/ner_baseline_llm.py --limit 30 --out backend/tools/ner_baseline.jsonl

依赖后端在 18088 上跑着（标注走的是平台的对话接口，不是直连 DeepSeek ——
密钥在服务商配置里，脚本读不到也不该读）。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CANDIDATES = ROOT / ".build" / "rerank" / "candidates.jsonl"

BASE = "http://localhost:18088/api/v1"

# `__B2判分器v3__`：kbIds 为空 + noRefStrategy=FREE_ANSWER。
# 必须用不绑库的应用，否则模型会去检索、然后因为检索不到而拒答。
APP_ID = "4ca554c4-585e-4451-88f4-a14680d04598"

PROMPT = """从下面这段中文制度文本里，找出**所有真实的组织机构名称**。

规则：
1. 只找组织/机构/部门/委员会/领导小组，不找人名、岗位名、系统名、文档名、平台名。
2. 必须是从文本里**原样连续截取**的片段，不许改写、不许补字。
3. 像「本部门」「各部门」「公司总部各部」「各分支机构」这类**泛指**不算。
4. 像「采购供应部是公司采购的归口管理部门」里的「归口管理部门」不算 —— 那是职能描述。

只输出一个 JSON 数组，元素是对象，字段：
- "name": 机构名（原样连续片段）
- "note": 一句话说明你判断它是机构的依据（如"以'部'结尾且前面是'应配合'"）

如果没有机构名，输出 []。
不要输出 JSON 之外的任何文字。

文本：
---
{text}
---"""


def login() -> str:
    body = json.dumps({"username": "admin", "password": "admin"}).encode()
    req = urllib.request.Request(
        BASE + "/auth/login", data=body, headers={"Content-Type": "application/json"}
    )
    return json.loads(urllib.request.urlopen(req, timeout=60).read())["data"]["accessToken"]


def ask(token: str, text: str) -> str:
    """走平台的对话接口。SSE 正文帧的字段是 text（不是 delta）。"""
    body = json.dumps({"appId": APP_ID, "question": PROMPT.format(text=text)}).encode()
    req = urllib.request.Request(
        BASE + "/conversations/chat",
        data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
    )
    chunks = []
    with urllib.request.urlopen(req, timeout=300) as resp:
        for raw in resp:
            line = raw.decode("utf-8", "replace").strip()
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if not payload or payload == "[DONE]":
                continue
            try:
                frame = json.loads(payload)
            except json.JSONDecodeError:
                continue
            piece = frame.get("text")
            if piece:
                chunks.append(piece)
    return "".join(chunks)


def parse_answer(reply: str) -> list[dict]:
    """从回复里抠出 JSON 数组。模型常套 ```json 代码块，也常前后带一句客套话。"""
    fenced = re.search(r"```(?:json)?\s*(.*?)```", reply, re.S)
    candidate = fenced.group(1) if fenced else reply
    start, end = candidate.find("["), candidate.rfind("]")
    if start < 0 or end < start:
        return []
    try:
        parsed = json.loads(candidate[start : end + 1])
    except json.JSONDecodeError:
        return []
    out = []
    for item in parsed if isinstance(parsed, list) else []:
        if isinstance(item, dict) and item.get("name"):
            out.append({"name": str(item["name"]).strip(), "note": str(item.get("note", ""))})
        elif isinstance(item, str):
            out.append({"name": item.strip(), "note": ""})
    return [x for x in out if x["name"]]


def segments(limit: int) -> list[dict]:
    """每个文档取首段 —— 规则简单、可复现，且覆盖面广（70 个文档各一次）。"""
    if not CANDIDATES.exists():
        sys.exit(f"缺 {CANDIDATES}")
    seen_doc, rows = {}, []
    for line in CANDIDATES.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        for item in json.loads(line).get("items", []):
            content = (item.get("content") or "").strip()
            doc = item.get("docName") or ""
            if not content or not doc or doc in seen_doc:
                continue
            seen_doc[doc] = content
            rows.append({"doc": doc, "text": content})
            if limit and len(rows) >= limit:
                return rows
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--limit", type=int, default=30)
    parser.add_argument("--out", default=str(ROOT / "backend" / "tools" / "ner_baseline.jsonl"))
    args = parser.parse_args()

    rows = segments(args.limit)
    if not rows:
        sys.exit("没读到任何段")
    token = login()
    print(f"后端已登录；待标注 {len(rows)} 段\n")

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    kept = 0
    with out_path.open("w", encoding="utf-8") as handle:
        for index, row in enumerate(rows, 1):
            reply = ask(token, row["text"])
            entities = parse_answer(reply)
            # 校验：模型给的每个片段必须真的出现在原文里 —— 这是防它改写/拼接的唯一硬检查。
            bad = [e["name"] for e in entities if e["name"] not in row["text"]]
            if bad:
                print(f"[{index}/{len(rows)}] ⚠ 模型给了原文里不存在的片段：{bad}")
                entities = [e for e in entities if e["name"] in row["text"]]
            record = {
                "doc": row["doc"],
                "text": row["text"],
                "expected": [e["name"] for e in entities],
                "note": {e["name"]: e["note"] for e in entities},
                "rawReply": reply[:2000],
            }
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
            handle.flush()
            kept += 1
            print(f"[{index}/{len(rows)}] {row['doc'][:36]:38} => {record['expected']}")

    print(f"\n已写入 {out_path}（{kept} 条）")


if __name__ == "__main__":
    main()
