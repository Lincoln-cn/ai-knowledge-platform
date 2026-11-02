#!/usr/bin/env python3
"""扫查「页面退化成说明书 + 功能躲在自动弹出的浮层里」（`#290`，来源 `#289`）。

判据（三条，只读）：

1. **进页面就把浮层弹出来** —— `onMounted` 里调了 `xxx.value?.open()` 之类的打开动作。
   症状：用户还没点任何东西，半屏已经被盖住。
2. **页面正文没有实质内容** —— 模板里既没有 `el-table`、也没有 `v-for` 渲染的列表、
   也没有 `el-form`。落在这条上，说明"关掉浮层之后页面上不剩可操作的东西"。
3. **正文里有一段自我说明** —— 含「这个页面做什么」「本页做什么」这类自述标题。
   一条页面需要给自己写一页说明书，通常是因为它自己没有内容。

命中任意 **两条及以上** ⇒ 报可疑。只命中一条的单独列出来供人工判读（不判错）。

用法：
  python tools/check_page_substance.py            # 打印报告，命中可疑则 exit 1
  python tools/check_page_substance.py --list     # 只列全部页面与命中项，永不 exit 1
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent   # tools/ → backend/ → 仓库根
ROOT = REPO / "frontend" / "src" / "pages"

AUTO_OPEN = re.compile(r"onMounted\s*\(\s*\(\)\s*=>\s*\{?[^)]*?\.(?:value\s*\?\.)?\s*open\s*\(", re.S)
SUBSTANCE = [
    ("el-table", re.compile(r"<el-table\b")),
    ("v-for 列表", re.compile(r"v-for=")),
    ("el-form", re.compile(r"<el-form\b")),
    # ★ 页面把正文交给自己的子组件也算有实质（`<ProviderPanel />` 这种）——
    #   只看 Index.vue 会把"已经把功能拆出去了"误判成"这个页面是空的"。
    (
        "本地子组件",
        re.compile(r"<[A-Z][A-Za-z0-9]*(?:Panel|List|Table|Grid|Drawer|Section|Board|Detail|Editor)\b"),
    ),
]
SELF_EXPLAIN = re.compile(r"这个页面做什么|本页做什么|这个页面是做什么|说明这个页面")


def scan_text(text: str):
    """对一个页面源码判三条。抽成纯文本入口，是为了让 `--self-check` 能造样本。"""
    template = text.split("<script", 1)[0]
    # ★ 先剥 HTML 注释：注释里讨论这个病（比如 `#289` 的改动说明）不该算"页面在自述"
    template = re.sub(r"<!--.*?-->", "", template, flags=re.S)
    hits = []
    if AUTO_OPEN.search(text):
        hits.append("① 进页面自动开浮层")
    present = [name for name, pat in SUBSTANCE if pat.search(template)]
    if not present:
        hits.append("② 正文没有表格/列表/表单")
    if SELF_EXPLAIN.search(template):
        hits.append("③ 正文带着自我说明")
    return hits, present


def scan(path: Path):
    return scan_text(path.read_text(encoding="utf-8"))


def self_check():
    """★ 闸门自己也要能被断言（`#223` 的同一条纪律）：三条判据各自都要"能红"。"""
    good = '<template><div class="page"><el-table :data="rows" /></div></template>'
    bad = (
        '<template><div class="page">'
        '<h3>这个页面做什么</h3>'
        '<p>说明文字</p>'
        '</div></template>'
        '<script setup>onMounted(() => { panel.value?.open() })</script>'
    )
    # ① 只抓"自动打开"：靠按钮打开的浮层不算（那是有意的交互）
    manual = (
        '<template><div class="page"><el-table :data="rows" /></div></template>'
        '<script setup>onMounted(() => { fetchList() })</script>'
    )
    # 注释里提到这个病不算"页面在自述"
    commented = (
        '<template><div class="page">'
        '<!-- 之前这里写着「这个页面做什么」 -->'
        '<el-table :data="rows" />'
        '</div></template>'
    )
    # 正文交给子组件也算有实质
    delegated = '<template><div class="page"><ProviderPanel /></div></template>'

    checks = [
        ("正常页面不误报", scan_text(good), ([], ["el-table"])),
        ("三条全命中", scan_text(bad), (["① 进页面自动开浮层", "② 正文没有表格/列表/表单", "③ 正文带着自我说明"], [])),
        ("手动打开的浮层不算 ①", scan_text(manual)[0], []),
        ("注释里的话不算 ③", scan_text(commented)[0], []),
        ("委派给子组件算有实质", scan_text(delegated)[1], ["本地子组件"]),
    ]
    failed = 0
    for name, got, want in checks:
        ok = got == want
        print(f"  {'✓' if ok else '✗'} {name}：{got}")
        if not ok:
            print(f"      期望 {want}")
            failed += 1
    return failed


def main(argv):
    if "--self-check" in argv:
        print("页面实质闸门 · 自检")
        failed = self_check()
        return 0 if failed == 0 else 1
    listing = "--list" in argv
    pages = sorted(
        p for p in ROOT.rglob("Index.vue") if "components" not in p.parts
    )
    suspicious, single = [], []
    for page in pages:
        hits, present = scan(page)
        rel = page.relative_to(REPO)
        if len(hits) >= 2:
            suspicious.append((rel, hits, present))
        elif hits:
            single.append((rel, hits, present))

    print(f"扫了 {len(pages)} 个页面（`frontend/src/pages/**/Index.vue`）\n")
    if listing:
        for page in pages:
            hits, present = scan(page)
            print(f"  {page.relative_to(REPO)}  →  {hits or '—'}  实质：{present or '无'}")

    print(f"★★ 可疑（命中 ≥2 条）：{len(suspicious)} 个")
    for rel, hits, present in suspicious:
        print(f"  · {rel}")
        for h in hits:
            print(f"      {h}")
        print(f"      正文里的实质元素：{present or '一个都没有'}")

    print(f"\n★ 只命中一条（人工判读，不算错）：{len(single)} 个")
    for rel, hits, present in single:
        print(f"  · {rel}  →  {hits[0]}")

    if listing:
        return 0
    return 1 if suspicious else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
