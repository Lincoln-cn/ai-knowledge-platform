"""#91 深链「接收端那一半」的静态断言 —— 键名一致 + 接收端真的读了它 + 真的按 id 取回分段。

纯静态：不启 dev server、不开浏览器、不联网（浏览器闸门在并行真栈测试期间不许跑）。
用法：python check_deeplink_segments.py
退出码：0 = 全绿；1 = 有红。

━━━ 为什么要有这一条 ━━━
「点来源跳不到」这种缺陷的读数与「跳得到」完全一样：**页面不报错、控制台不报错**。
本项目的头号病 `silent-failure` 就是它 —— 会话页把 `?doc=` / `?segment=` 拼进了 URL，
知识库页一个键都不读，于是跳转是一次**静默 no-op**。

所以断言必须钉住**两端**，而不是钉住某一端的实现：
  ① 发送端拼的键名集合 ⊆ 接收端读的键名集合 —— 键名改一个字母，这条立刻红；
  ② 接收端的「切哪个 Tab」这个判据真的依赖深链键 —— 只把键读进来却不用它，等于没读；
  ③ 接收端按契约正路把目标分段**取回来**（`GET /segments/:id`，不是"列出全部"）；
  ④ mock 里必须有同一对 method + path 的响应 —— 否则 `VITE_USE_MOCK=true` 下这条路径没有读数
     （「加了契约却没同步 mock」正是本工程反复踩的坑）。

★ 不许为了让这条变绿而削弱它：①③④ 任何一条被删，都得先说清"为什么这个判据不再成立"。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"

# 发送端：两处 `jumpToSegment`。public 那处是免登录会话页，同样是"点了就跳"，
# 所以它必须一起被钉住 —— 只钉一处会给另一处留下同样的静默 no-op。
SENDER_FILES = [
    SRC / "pages" / "conversation" / "Index.vue",
    SRC / "pages" / "conversation" / "public" / "Index.vue",
]

# 接收端：深链的落点页
RECEIVER_FILE = SRC / "pages" / "knowledge" / "Index.vue"

API_DIR = SRC / "api"
MOCK_HANDLERS = SRC / "mock" / "handlers"

# 发送端函数的声明处 / 它必须跳去的页面
SENDER_DECL = re.compile(r"const\s+jumpToSegment\s*=")
SENDER_TARGET = re.compile(r"path\s*:\s*['\"]/knowledge['\"]")

# `query.doc = ref.documentId` —— 发送端拼进 URL 的键
QUERY_ASSIGN = re.compile(r"\bquery\.([A-Za-z_$][\w$]*)\s*=(?!=)")
# `route.query.doc` —— 接收端从 URL 读的键
ROUTE_READ = re.compile(r"\broute\.query\.([A-Za-z_$][\w$]*)")

# 契约里的导出函数与其 URL / method 字面量
API_EXPORT = re.compile(r"export\s+const\s+([A-Za-z_$][\w$]*)\s*=")
API_URL = re.compile(r"url\s*:\s*['\"]([^'\"]+)['\"]")
API_METHOD = re.compile(r"method\s*:\s*['\"]([A-Z]+)['\"]")

# mock 路由元组：['GET', '/segments/:id', …]
MOCK_ROUTE = re.compile(r"['\"]([A-Z]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]")

problems: list[str] = []


def fail(msg: str) -> None:
    problems.append(msg)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def strip_comments(text: str) -> str:
    """去掉 `//` `/* */` 与模板里的 `<!-- -->`，保留行号。

    ★ 必须做：判据是"代码里读没读这个键"。注释里写一句 `route.query.doc` 就把红判成绿，
      那正是"为了变绿而削弱断言"的形状（本工程踩过一次同类：注释里的反模式被算成违规）。
    """
    text = re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), text, flags=re.S)
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "*":
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append("\n" * text.count("\n", i, end))
            i = end
            continue
        if ch == "/" and nxt == "/":
            end = text.find("\n", i)
            end = n if end < 0 else end
            i = end
            continue
        if ch in "\"'`":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == ch:
                    j += 1
                    break
                j += 1
            out.append(text[i:j])
            i = j
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def balanced(text: str, open_idx: int, open_ch: str, close_ch: str) -> str:
    """从 open_ch 开始做一次配平，返回该块正文（字符串里的括号不参与计数）。"""
    depth = 0
    i = open_idx
    n = len(text)
    while i < n:
        ch = text[i]
        if ch in "\"'`":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == ch:
                    j += 1
                    break
                j += 1
            i = j
            continue
        if ch == open_ch:
            depth += 1
        elif ch == close_ch:
            depth -= 1
            if depth == 0:
                return text[open_idx : i + 1]
        i += 1
    return text[open_idx:]


def arrow_body(text: str, decl_end: int) -> str:
    """`const f = (...) => { ... }`：取 `=>` 之后那个花括号块。

    ★ 不能直接找"声明之后的第一个 `{`"以外的写法：先找 `(` 会拿到**参数表**，
      于是发送端拼的键一个都提不出来（本是空集合，却会显示成"通过"）。
    """
    arrow = text.find("=>", decl_end)
    if arrow < 0:
        return ""
    brace = text.find("{", arrow)
    if brace < 0:
        return ""
    return balanced(text, brace, "{", "}")


def computed_body(text: str, decl_end: int) -> str:
    """`const x = computed<T>(...)`：取实参那对圆括号（`computed(() => (…))` 没有花括号）。"""
    paren = text.find("(", decl_end)
    if paren < 0:
        return ""
    return balanced(text, paren, "(", ")")


def sender_keys() -> set[str]:
    """发送端拼进 URL 的键名集合（两个 `jumpToSegment` 的并集）。"""
    keys: set[str] = set()
    for path in SENDER_FILES:
        if not path.exists():
            fail(f"发送端不存在：{path.relative_to(ROOT).as_posix()}（这条判据没有读数了）")
            continue
        code = strip_comments(read(path))
        m = SENDER_DECL.search(code)
        if not m:
            fail(
                f"{path.relative_to(ROOT).as_posix()} 里找不到 `const jumpToSegment` —— "
                "发送端的载体没了，这条断言就失去了对照面"
            )
            continue
        body = arrow_body(code, m.end())
        if not SENDER_TARGET.search(body):
            fail(
                f"{path.relative_to(ROOT).as_posix()} 的 jumpToSegment 没有跳去 `/knowledge` —— "
                "它已不是本页要核的那条深链"
            )
            continue
        found = set(QUERY_ASSIGN.findall(body))
        print(f"  发送端 {path.relative_to(ROOT).as_posix():<44} 拼的键：{sorted(found)}")
        keys |= found
    return keys


def receiver_keys() -> set[str]:
    """接收端从 URL 读的键名集合（`route.query.*`）。"""
    if not RECEIVER_FILE.exists():
        fail(f"接收端不存在：{RECEIVER_FILE.relative_to(ROOT).as_posix()}")
        return set()
    return set(ROUTE_READ.findall(strip_comments(read(RECEIVER_FILE))))


def tab_decision_reads_deeplink(keys: set[str]) -> tuple[bool, str]:
    """`activeTab` 的判据是否真的依赖深链键。

    两种写法都认：① 判据里直接 `route.query.<key>`；
                  ② 判据里引用了某个「读了 `<key>` 的 computed」。
    不接受"把键读进一个变量却没人用" —— 那正是只做了一半。
    """
    code = strip_comments(read(RECEIVER_FILE))
    m = re.search(r"const\s+activeTab\s*=\s*computed", code)
    if not m:
        return False, "找不到 `const activeTab = computed` —— Tab 的判据没有读数"
    body = computed_body(code, m.end())

    direct = set(ROUTE_READ.findall(body)) & keys
    if direct:
        return True, f"判据里直接读了 {sorted(direct)}"

    # 间接：body 引用了哪个 computed，而那个 computed 读了深链键
    helpers: dict[str, set[str]] = {}
    for hm in re.finditer(r"const\s+([A-Za-z_$][\w$]*)\s*=\s*computed", code):
        hbody = computed_body(code, hm.end())
        helpers[hm.group(1)] = set(ROUTE_READ.findall(hbody))
    for name, read_keys in helpers.items():
        if name in body and read_keys & keys:
            return True, f"判据引用了 `{name}`（它读了 {sorted(read_keys & keys)}）"
    return False, f"判据既没直接读 {sorted(keys)}，也没引用任何读了它们的 computed"


def contract_segments_by_id() -> list[tuple[str, str, str]]:
    """契约里「按 id 取一条分段」的导出函数：[(函数名, method, url)]。"""
    hits: list[tuple[str, str, str]] = []
    if not API_DIR.exists():
        fail(f"契约目录不存在：{API_DIR.relative_to(ROOT).as_posix()}")
        return hits
    for path in sorted(API_DIR.rglob("*.ts")):
        code = strip_comments(read(path))
        marks = [(m.group(1), m.start()) for m in API_EXPORT.finditer(code)]
        for idx, (name, start) in enumerate(marks):
            end = marks[idx + 1][1] if idx + 1 < len(marks) else len(code)
            block = code[start:end]
            url = API_URL.search(block)
            method = API_METHOD.search(block)
            if not url or not method:
                continue
            if method.group(1) == "GET" and re.fullmatch(r"/segments/:[A-Za-z_$][\w$]*", url.group(1)):
                hits.append((name, method.group(1), url.group(1)))
    return hits


def mock_routes() -> set[tuple[str, str]]:
    routes: set[tuple[str, str]] = set()
    if not MOCK_HANDLERS.exists():
        fail(f"mock 目录不存在：{MOCK_HANDLERS.relative_to(ROOT).as_posix()}")
        return routes
    for path in sorted(MOCK_HANDLERS.rglob("*.ts")):
        for method, url in MOCK_ROUTE.findall(strip_comments(read(path))):
            routes.add((method, url))
    return routes


def main() -> int:
    print("── #91 深链接收端 ──")
    print("[1] 发送端拼的键 / 接收端读的键")
    sent = sender_keys()
    got = receiver_keys()
    print(f"  接收端 {RECEIVER_FILE.relative_to(ROOT).as_posix():<44} 读的键：{sorted(got)}")
    if not sent:
        fail("发送端一个键都没拼出来 —— 断言会空过（不许把空集合当通过）")
    else:
        missing = sorted(sent - got)
        if missing:
            fail(
                f"接收端没有读这些键：{missing} —— 发送端拼了却没人读，"
                "就是「点了没反应」的静默 no-op（#91 的根因）"
            )
        else:
            print(f"  [OK] 发送端 {sorted(sent)} ⊆ 接收端 {sorted(got)}")

    print()
    print("[2] Tab 判据是否真的依赖深链键")
    if sent:
        ok, why = tab_decision_reads_deeplink(sent)
        if ok:
            print(f"  [OK] {why}")
        else:
            fail(f"深链参数没有参与「切哪个 Tab」的判据：{why}  ⇒ 跳到了却停在文档页，看不到目标")
    else:
        fail("跳过：发送端键集合为空，第 ② 条无从判定")

    print()
    print("[3] 接收端按契约正路取回目标分段（GET /segments/:id）")
    by_id = contract_segments_by_id()
    if not by_id:
        fail(
            "契约里找不到「按 id 取一条分段」的接口（GET /segments/:id）—— "
            "要么它被删了，要么接收端只能「列出全部」而取不回目标那一条"
        )
        print("  [FAIL] 契约无此接口")
    else:
        page = strip_comments(read(RECEIVER_FILE))
        called = [h for h in by_id if re.search(r"\b" + re.escape(h[0]) + r"\s*\(", page)]
        print(f"  契约：{[f'{n} → {m} {u}' for n, m, u in by_id]}")
        if called:
            print(f"  [OK] 接收端调用了：{sorted(h[0] for h in called)}")
        else:
            fail(
                f"接收端没有调用 {sorted(h[0] for h in by_id)} —— "
                "深链只是「切了个 Tab」，目标分段没有被取回来展示"
            )

    print()
    print("[4] mock 同步（VITE_USE_MOCK=true 下要有响应）")
    routes = mock_routes()
    for name, method, url in by_id:
        if (method, url) in routes:
            print(f"  [OK] mock 注册了 {method} {url}（供 {name} 使用）")
        else:
            fail(
                f"mock 没有注册 {method} {url} —— mock 模式下 {name} 会 404，"
                "这条路径在 VITE_USE_MOCK=true 下没有读数"
            )

    print()
    if problems:
        print(f"结果：红 —— {len(problems)} 条问题")
        for p in problems:
            print(f"  [FAIL] {p}")
        return 1
    print("结果：绿 —— 两端键名一致、接收端读了它、并按契约取回了目标分段")
    return 0


if __name__ == "__main__":
    sys.exit(main())
