"""用本地 Ollama 模型给 30 段语料标注机构名，落一份「机器标注」供人复核。

## 为什么用 Ollama 而不是平台接口

平台接口要起后端（Spring Boot + Flyway 迁移），而 Ollama 是现成的 HTTP 服务
（`http://localhost:11434/api/generate`）。这一步只是**产生一份待复核的草稿**，
不需要走 RAG 链路 —— 走了反而引入检索噪声。

## 这个脚本产出的东西不是最终答案

它产出 `ner_ollama_draft.jsonl`，字段：

- `ollama` —— 本地模型给的机构名
- `expected` —— 人工基线（`ner_testset.jsonl`）
- `agree` / `onlyOllama` / `onlyHuman` —— 三路对比

**最终验证集以人工基线为准，本地模型的输出只用来找「我可能标漏了」的地方。**
本地 1.5B 模型在中文机构名上的表现不会好，这一点脚本本身就会印出来。

## 用法

    python backend/tools/ner_ollama_label.py --model qwen3:4b
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TESTSET = ROOT / "backend" / "tools" / "ner_testset.jsonl"
OUT = ROOT / ".build" / "ner_ollama_draft.jsonl"

OLLAMA = "http://localhost:11434/api/generate"

PROMPT = """从下面的中文文本里找出所有组织机构名称。

要求：
1. 只找组织、部门、委员会、领导小组，不找人名、岗位、系统名、文档名。
2. 必须从原文原样连续截取，不许改写。
3. 「本部门」「各部门」「总部各部」「各分支机构」这类泛指不算。
4. 「归口管理部门」「承办部门」这类职能描述不算。
5. 「XX管理办法」「XX规程」这类文档标题不算。

只输出一个 JSON 数组，元素是字符串。没有就输出 []。
不要输出任何其他内容。

文本：
{text}

JSON："""


def ask(model: str, text: str) -> str:
    # ★ 不要传 `format: "json"`。实测它会让 qwen3:4b 与 qwen2.5:1.5b 返回空数组，
    #   而不传时两个模型都能正确给出 `["财务资产部"]`（只是外面裹了 ```json 围栏，
    #   由 `parse()` 负责剥）。这是「约束输出格式」反而把内容压没了的一个实例。
    body = json.dumps(
        {"model": model, "prompt": PROMPT.format(text=text), "stream": False}
    ).encode()
    req = urllib.request.Request(OLLAMA, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.loads(resp.read())["response"]


def parse(reply: str) -> list[str]:
    """从回复里抠机构名。模型可能给 `["A","B"]`，也可能给 `{"entities":[...]}`。"""
    reply = reply.strip()
    if not reply:
        return []
    try:
        parsed = json.loads(reply)
    except json.JSONDecodeError:
        fenced = re.search(r"\[(.*?)\]", reply, re.S)
        if not fenced:
            return []
        try:
            parsed = json.loads("[" + fenced.group(1) + "]")
        except json.JSONDecodeError:
            return []

    names = []
    if isinstance(parsed, list):
        names = parsed
    elif isinstance(parsed, dict):
        for key in ("entities", "names", "result", "organizations"):
            if isinstance(parsed.get(key), list):
                names = parsed[key]
                break
    out = []
    for item in names:
        if isinstance(item, str):
            out.append(item.strip())
        elif isinstance(item, dict) and item.get("name"):
            out.append(str(item["name"]).strip())
    return [x for x in out if x]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="qwen3:4b")
    parser.add_argument("--out", default=str(OUT))
    args = parser.parse_args()

    if not TESTSET.exists():
        sys.exit(f"缺测试集：{TESTSET}")
    rows = [json.loads(line) for line in TESTSET.read_text(encoding="utf-8").splitlines() if line.strip()]
    print(f"模型 {args.model}；待标 {len(rows)} 段\n")

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    agree = only_ollama = only_human = 0
    with out_path.open("w", encoding="utf-8") as handle:
        for index, row in enumerate(rows, 1):
            try:
                reply = ask(args.model, row["text"])
            except Exception as error:  # noqa: BLE001 - 单段失败不该中断整批
                print(f"[{index}/{len(rows)}] ✗ {type(error).__name__}: {error}")
                continue
            raw = parse(reply)
            # 硬检查：模型给的片段必须真的出现在原文里，否则一定是它编的。
            named = [x for x in raw if x in row["text"]]
            dropped = [x for x in raw if x not in row["text"]]

            human = set(row["expected"])
            got = set(named)
            same, extra, missing = got & human, got - human, human - got
            agree += len(same)
            only_ollama += len(extra)
            only_human += len(missing)

            handle.write(json.dumps({
                "doc": row["doc"],
                "expected": sorted(human),
                "ollama": sorted(got),
                "agree": sorted(same),
                "onlyOllama": sorted(extra),
                "onlyHuman": sorted(missing),
                "hallucinated": dropped,
            }, ensure_ascii=False) + "\n")
            handle.flush()

            mark = "✓" if not extra and not missing else "·"
            print(f"{mark} [{index}/{len(rows)}] {row['doc'][:34]:36} 一致{len(same)} 多{len(extra)} 漏{len(missing)}")
            if extra:
                print(f"        多出 {sorted(extra)}")
            if missing:
                print(f"        漏掉 {sorted(missing)}")
            if dropped:
                print(f"        编造 {dropped}（原文里不存在）")

    print(f"\n=== 一致 {agree} / 模型多出 {only_ollama} / 人工独有 {only_human} ===")
    print(f"草稿已写入 {out_path}")


if __name__ == "__main__":
    main()
