#!/usr/bin/env python3
"""空态与错误态盘点（`docs/ROADMAP.md` §五 F4：「逐页清单 + 缺口」）。

## 这条闸门管什么

两件事，分两级：

**① 错误态（硬闸）** —— 有接口调用的页面，必须为「拉不到数据」准备过呈现。
   漏了它的后果是本工程的头号病：静默失败 —— 页面上什么都没有，用户不知道自己
   是"没有数据"还是"接口挂了"。

**② 空态（清单）** —— 列表/表格在没有数据时说了什么。这一项不判红，因为
   `el-table` 自带「暂无数据」（`main.ts:26` 配了 zhCn），页面**确实有**空态；
   它缺的是"下一步点哪里"。这类事逐条核对比一刀切更有用，所以记成账目（见
   `TABLE_EMPTY_DEBT`，账在 `#256`），修好一个删一条。

## 红在哪（每一条都能核对）

1. 有接口、无错误态信号、又不在 `PAGE_ERROR_GAPS` 上 ⇒ 红
2. 账上的条目没有 Issue 号 ⇒ 红（本工程纪律：问题的真源是 GitHub Issues）
3. 账上已登记的缺口，现在信号出现了 ⇒ 红（**清单必须随修复收缩**）
4. 用了 `el-table` 却没有一处 `#empty`、又不在 `TABLE_EMPTY_DEBT` 上 ⇒ 红（新页面不许静默漏账）

## 为什么不只看 `*/Index.vue`

列表常被拆进页面私有组件（`pages/assistants/prompts/Index.vue` 的列表在
`pages/assistants/components/prompts/PromptConfigPanel.vue`）。所以证据 =
页面文件 **加它相对导入的、仍在 `src/pages` 下的 .vue**。

用法：`python check_states.py` / `python check_states.py --list`
"""

import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PAGES_ROOT = HERE / "src" / "pages"

# ---------------------------------------------------------------------------
# 信号：这一层只回答"想过这件事没有"，不回答"想得对不对"。
# 想得对不对只能读图 —— ROADMAP 那条纪律：11 个脚本全绿时图上仍能一眼看到问题。
# ---------------------------------------------------------------------------
EMPTY_SIGNALS = [
    r'class="empty"',
    r"\bclass=\"[^\"]*\bempty\b",
    r"\bEmptyArt\b",
    r"<el-empty",
    r"#empty",
    r'v-if="!.*\.length',
    r'v-if="!\s*[A-Za-z_$][\w$]*\s*"',
    r"暂无",
    r"还没有",
    r"没有数据",
    r"一条都没有",
]

ERROR_SIGNALS = [
    r"loadError",
    r"errorText",
    r"errorMessage",
    r"loadFailed",
    r'<el-alert[^>]*type="error"',
    r'class="notice err"',
    r"ElMessage\.error",
    r"\bMsgError\(",
    r"\bmsgError\(",
    r"无法加载",
    r"加载失败",
    r"读取失败",
    r"拉取失败",
]

API_CALL_RE = re.compile(r"\b[A-Za-z][A-Za-z0-9]*Api\.[A-Za-z][A-Za-z0-9]*\(")
IMPORT_RE = re.compile(r"""(?:from|import)\s+['"](\.[^'"]+)['"]""")
TABLE_RE = re.compile(r"<el-table\b")
EMPTY_SLOT_RE = re.compile(r"#empty\b")
# 空表格的「下一步」有两种合法写法：`#empty` 插槽，或 `empty-text` 里直接写明动作。
# ★ 只认插槽会把已经修好的东西继续挂在账上（账目虚挂 = 账目失效）。
# 只认静态字符串；`'暂无' + title` 这种动态拼接按"没写引导"处理（保守）。
# ★ 「点」单字不能当动作词 —— 「暂无权限点」里的「点」是名词，会把它误判成"已写好引导"。
TABLE_HINT_RE = re.compile(
    r"""empty-text="[^"]*(?:点击|点右上角|点这里|点下面|上传|新建|创建|添加|开始|导入|配置|去创建|去添加|去新建)"""
)

# ---------------------------------------------------------------------------
# 账 1：页面级 —— 有接口但还没为「拉不到数据」准备呈现（键 = 相对 src/pages）
# ---------------------------------------------------------------------------
PAGE_ERROR_GAPS = {}

# ---------------------------------------------------------------------------
# 账 2：表格空态 —— 用了 el-table 却没有任何「有下一步」的空态（键 = 相对 src/pages）
#       账在 #256；每修好一个文件就删一条（清单必须随修复收缩）
# ---------------------------------------------------------------------------
TABLE_EMPTY_DEBT = {
    "analytics/Index.vue": {"issue": "#256", "note": "指标表格（有数据才展示，倾向整块不渲染）"},
}

# ---------------------------------------------------------------------------
# 豁免：有意的「不做空态」。豁免与债的区别是**有意的**，所以不需要 Issue 号，
#       但必须写明理由；它们同样要能红（文件改名/不再含 el-table 就报错）。
# ---------------------------------------------------------------------------
TABLE_EMPTY_EXEMPT = {
    "assistants/components/BundlePanel.vue": "校验结果里的依赖表 —— 有依赖才会出现这张表，空表没有意义",
    "assistants/components/prompts/PromptTypeDialog.vue": "对话框内表格，旁边就是新增入口",
    "workflow/components/nodes/base-node/component/ApiInputFieldTable.vue": "节点配置对话框内的表格",
    "workflow/components/nodes/base-node/component/UserInputFieldTable.vue": "节点配置对话框内的表格",
}


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def collect_evidence(page: Path, seen: set) -> set:
    """页面文件 + 它相对导入的、仍在 `src/pages` 下的 .vue。"""
    if page in seen:
        return seen
    seen.add(page)
    for spec in IMPORT_RE.findall(read(page)):
        base = (page.parent / spec).resolve()
        candidates = [base] if base.suffix == ".vue" else [base.with_suffix(".vue"), base / "index.vue"]
        for cand in candidates:
            if cand.is_file() and cand.suffix == ".vue" and PAGES_ROOT in cand.parents:
                collect_evidence(cand, seen)
    return seen


def hit(text: str, signals: list) -> bool:
    return any(re.search(pat, text) for pat in signals)


def main() -> int:
    if not PAGES_ROOT.is_dir():
        print(f"找不到页面目录：{PAGES_ROOT}", file=sys.stderr)
        return 2

    pages = sorted(PAGES_ROOT.glob("*/Index.vue")) + sorted(PAGES_ROOT.glob("*/*/Index.vue"))
    problems = []
    rows = []
    all_files = {}

    for page in pages:
        rel = page.relative_to(PAGES_ROOT).as_posix()
        files = collect_evidence(page, set())
        for f in files:
            all_files.setdefault(f, set()).add(rel)
        blob = "\n".join(read(f) for f in files)
        api_calls = len(API_CALL_RE.findall(blob))
        has_empty = hit(blob, EMPTY_SIGNALS)
        has_error = hit(blob, ERROR_SIGNALS)

        if api_calls == 0:
            verdict = "n/a（无接口）"
        elif has_error:
            verdict = "齐"
        elif rel in PAGE_ERROR_GAPS:
            verdict = "缺口（已登记）"
        else:
            verdict = "★ 未登记"
            problems.append(
                f"{rel}：有 {api_calls} 处接口调用，但没有任何错误态呈现，且不在 check_states.py 的账上"
                "（拉不到数据时页面会什么都不说 —— 静默失败）"
            )

        if rel in PAGE_ERROR_GAPS and has_error:
            problems.append(f"{rel}：已登记为错误态缺口，但现在有信号了 —— 请删掉 PAGE_ERROR_GAPS 里的这条")

        rows.append((rel, api_calls, has_empty, has_error, verdict, len(files)))

    # 表格空态：按文件核账。★ 扫**整个 src/pages**，不跟 import 链 ——
    # 深层组件（如 workflow 节点里的表格）不在任何页面的相对导入上，
    # 只跟 import 会漏掉它们，账上就会挂着永远查不到的条目。
    table_debt = []
    seen_debt = set()
    seen_exempt = set()
    for path in sorted(PAGES_ROOT.glob("**/*.vue")):
        text = read(path)
        tables = len(TABLE_RE.findall(text))
        if tables == 0:
            continue
        slots = len(EMPTY_SLOT_RE.findall(text)) + len(TABLE_HINT_RE.findall(text))
        rel = path.relative_to(PAGES_ROOT).as_posix()
        if slots == 0 and rel not in TABLE_EMPTY_DEBT and rel not in TABLE_EMPTY_EXEMPT:
            problems.append(
                f"{rel}：用了 {tables} 个 el-table 却没有任何 #empty / 带指引的 empty-text，也不在 TABLE_EMPTY_DEBT 上"
                "（空表格只有「暂无数据」，没有下一步）"
            )
        elif slots > 0 and rel in TABLE_EMPTY_DEBT:
            problems.append(f"{rel}：已有自定义空态，却还挂在 TABLE_EMPTY_DEBT 上 —— 请删掉这条")
        if rel in TABLE_EMPTY_DEBT:
            seen_debt.add(rel)
            table_debt.append((rel, tables, slots))
        if rel in TABLE_EMPTY_EXEMPT:
            seen_exempt.add(rel)

    # 账上条目必须真的被扫到 —— 否则文件改名/删除后，这条债会永远挂在账上
    for rel in TABLE_EMPTY_DEBT:
        if rel not in seen_debt:
            problems.append(f"{rel}：挂在 TABLE_EMPTY_DEBT 上，但扫不到这个文件（或它已没有 el-table）—— 请删掉这条")

    # 豁免也一样要能红：文件改名/删掉后豁免会悄悄失效，然后新文件又没人管
    for rel in TABLE_EMPTY_EXEMPT:
        if rel not in seen_exempt:
            problems.append(f"{rel}：挂在 TABLE_EMPTY_EXEMPT 上，但扫不到这个文件（或它已没有 el-table）—— 请删掉这条豁免")

    for rel, info in list(PAGE_ERROR_GAPS.items()) + list(TABLE_EMPTY_DEBT.items()):
        if not info.get("issue"):
            problems.append(f"{rel}：账上没有 Issue 号（问题的真源是 GitHub Issues）")

    if "--list" in sys.argv:
        print(f"{'页面':<40}{'接口':>5}{'空':>4}{'错':>4}  {'结论':<14}{'文件':>4}")
        print("-" * 84)
        for rel, api_calls, has_empty, has_error, verdict, nfiles in rows:
            print(
                f"{rel:<40}{api_calls:>5}{'✓' if has_empty else '·':>4}"
                f"{'✓' if has_error else '·':>4}  {verdict:<14}{nfiles:>4}"
            )
        print()

    print("=" * 84)
    with_api = sum(1 for r in rows if r[1] > 0)
    print(
        f"页面 {len(rows)} 个 · 有接口 {with_api} 个 · 错误态齐 {sum(1 for r in rows if r[4] == '齐')} 个"
    )
    if table_debt:
        print(f"\n表格空态债（{len(table_debt)} 个文件，账在 #256 —— 修好一个删一条）：")
        for rel, tables, slots in table_debt:
            print(f"  · {rel:<58} 表格 {tables} · 空态 {slots}  {TABLE_EMPTY_DEBT[rel]['note']}")
    if problems:
        print("\n有问题的条目：")
        for p in problems:
            print(f"  ✗ {p}")
        print("=" * 84)
        return 1
    print("\n✓ 有接口的页面都为「拉不到数据」准备过呈现；表格空态债见上面的账（未修完不隐藏）")
    print("=" * 84)
    return 0


if __name__ == "__main__":
    sys.exit(main())
