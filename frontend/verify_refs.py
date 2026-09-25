"""mock 层跨模块引用一致性校验。

**为什么需要这个**：9 个模块的 mock 是由多个并行任务分别写的。每个模块自己跑起来
都正常、页面也都能渲染，但**跨模块引用的 id 如果对不上，界面只会静默显示「—」**，
不报错、不白屏 —— 类型检查、构建、页面渲染校验全都抓不到。

实际踩到的：三个 fixture 用了三套应用 id（`as1` / `a1` / `app1`）、
两套知识库 id（`kb1` / `kb-1`）、两套会话 id（`c1` / `cv1`）。
其中反馈模块引用的会话 id 根本不存在，导致「跳转对话」全部指向空。

规则：每个实体有一个**所有者**（定义处），其它地方只能引用所有者定义的 id。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

FIXTURES = Path(__file__).resolve().parent / "src" / "mock" / "fixtures"
HANDLERS = Path(__file__).resolve().parent / "src" / "mock" / "handlers"

# 实体 → 定义它的文件（所有者）
OWNERS = {
    "app": "assistants.ts",
    "kb": "knowledge.ts",
    "conversation": "conversations.ts",
    "model": "models.ts",
}

# 在哪些字段名里算「引用该实体」（键名 → 实体）
REF_FIELDS = {
    "appId": "app",
    "appIds": "app",
    "kbId": "kb",
    "kbIds": "kb",
    "promotedToKbId": "kb",
    "conversationId": "conversation",
    "modelId": "model",
}


def scrape_ids(text: str, entity: str) -> set[str]:
    """从所有者文件里抓它定义的一批 id。

    实体的 id 通常写成 `id: 'xxx'`；知识库/会话/应用都是这个形状。
    """
    return set(re.findall(r"\bid:\s*'([^']+)'", text))


def scrape_refs(text: str) -> list[tuple[str, str, str]]:
    """抓出所有引用：返回 [(实体, 被引 id, 键名)]，跳过 null / 空数组"""
    found: list[tuple[str, str, str]] = []
    for key, entity in REF_FIELDS.items():
        # 单值：appId: 'x'
        for match in re.finditer(rf"\b{key}:\s*'([^']+)'", text):
            found.append((entity, match.group(1), key))
        # 数组：kbIds: ['a', 'b']
        for match in re.finditer(rf"\b{key}:\s*\[([^\]]*)\]", text):
            for item in re.findall(r"'([^']+)'", match.group(1)):
                found.append((entity, item, key))
    return found


# 有一个 fixture 是**故意违法**的：`fixtures/workflow.ts` 的 `sampleInvalidGraph`
# 用来验证「校验能拦下发布」—— 它刻意含两个 start-node、成环、孤立节点、
# 以及引用不存在的模型/知识库。**那些悬空 id 是它的用途，不是缺陷。**
DELIBERATE_INVALID_EXPORT = "sampleInvalidGraph"


def split_deliberate_invalid(text: str) -> tuple[str, str]:
    """把「故意违法」的那个具名导出整块挖出来。

    返回 (剩下的、被挖走的)。

    ⚠️ **只挖这一个具名块，不要整文件跳过** —— 同一个文件里还有合法的图，
    整文件跳过会连带放过它的真悬空引用（那就把检查作废了）。
    配套：被挖走的引用数要**打印出来**，让排除可见而不是静默 ——
    静默的例外迟早会变成藏真问题的地方。
    """
    marker = re.search(rf"export\s+const\s+{DELIBERATE_INVALID_EXPORT}\b", text)
    if not marker:
        return text, ""
    # 块尾 = 下一个顶层 export（或文件末尾）
    nxt = re.search(r"\nexport\s", text[marker.end():])
    end = marker.end() + nxt.start() if nxt else len(text)
    return text[: marker.start()] + text[end:], text[marker.start():end]


def main() -> int:
    owner_ids: dict[str, set[str]] = {}
    for entity, filename in OWNERS.items():
        file = FIXTURES / filename
        if not file.exists():
            print(f"⚠ 未找到所有者文件 {filename}")
            continue
        owner_ids[entity] = scrape_ids(file.read_text(encoding="utf-8"), entity)

    print("=" * 84)
    print("mock 跨模块引用一致性")
    print("=" * 84)
    for entity, ids in owner_ids.items():
        label = {"app": "应用", "kb": "知识库", "conversation": "会话", "model": "模型"}[entity]
        print(f"[已定义] {label:<6}({OWNERS[entity]:<20}) {len(ids):>2} 个：{', '.join(sorted(ids)) or '—'}")

    problems: list[str] = []
    checked = 0
    skipped = 0
    for folder in (FIXTURES, HANDLERS):
        for file in sorted(folder.glob("*.ts")):
            raw = file.read_text(encoding="utf-8")
            text, deliberate = split_deliberate_invalid(raw)
            skipped += len(scrape_refs(deliberate))
            for entity, ref_id, key in scrape_refs(text):
                # 该文件的作者若就是所有者，跳过（自己定义自己引用）
                if file.name == OWNERS.get(entity):
                    continue
                # 非所有者文件里出现的 id 定义不算引用（如 analytics 自己的 seed id）
                if ref_id in owner_ids.get(entity, set()):
                    checked += 1
                    continue
                # 前端的临时/本地 id 约定：带前缀的本地对象不算跨模块引用
                problems.append(f"{folder.name}/{file.name} :: {key} = '{ref_id}' 在 {OWNERS[entity]} 里不存在")

    print()
    if problems:
        print(f"✗ 发现 {len(problems)} 处悬空引用：")
        for p in dict.fromkeys(problems):
            print("   " + p)
    else:
        print("✓ 无悬空引用")

    print(f"\n共核对 {checked} 处有效引用")
    if skipped:
        print(
            f"按设计跳过 {skipped} 处 —— 位于「故意违法」的样例图 {DELIBERATE_INVALID_EXPORT}"
            f"（它存在的意义就是含悬空 id，用于验证校验能拦下发布）"
        )
    print("=" * 84)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
