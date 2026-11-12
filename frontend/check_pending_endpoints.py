#!/usr/bin/env python3
"""第 10 道静态闸门：后端「还没实现的端点」有没有被前端调用（`#160` 判据 3）。

<h3>为什么需要这一道</h3>
<p>`ContractDriftTest.KNOWN_MISSING` 是**后端欠账**的口径（"哪个端点还没写"），
它不表达"这会让页面报错"。而前端一旦调用这样的端点就是一次 404：
未处理的 rejection 会变成页面级 `PAGEERROR`，用户看到的只是"这块空着"——
与"这个功能还没做"看起来一模一样，处置却完全不同（一个等排期，一个要隔离依赖）。

<h3>判据（每条都要能红）</h3>
<ol>
  <li>台账里的端点被前端<strong>声明</strong>了，却没登记在本脚本的 {@code PENDING} 账里 ⇒ 红；</li>
  <li>账里那条<strong>已有调用方</strong>，但没写 {@code guard}（降级/提示落在哪）⇒ 红；</li>
  <li>账里那条<strong>已经不在台账</strong>（后端补上了）⇒ 红 —— 清单必须随修复收缩；</li>
  <li>账里那条已<strong>没有前端声明</strong>（契约删了）⇒ 红。</li>
</ol>

<h3>为什么不判"页面渲染出来了"</h3>
<p>那是浏览器读数，需要起服务；本工程的前端闸门在本机绑不了端口（`ROADMAP.md:2006`），
所以那一条永远在真栈那一侧。这里只回答一个静态问题：**谁会调到还没写的端点**。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

FRONTEND = Path(__file__).resolve().parent
ROOT = FRONTEND.parent
DRIFT = ROOT / "backend/src/test/java/cn/lincoln/aiplatform/ContractDriftTest.java"
API_DIR = FRONTEND / "src/api"
PAGES = FRONTEND / "src/pages"

# ──────────────────────────────────────────────────────────────────────────────
# 账：台账端点落到前端之后的处置。
#
# `issue` 是这批欠账的 Issue 号（与后端台账必须一致 —— 两处写不同号就没有真源了）。
# `guard` 写给读的人看：**这个端点在真栈上 404 时，用户看到什么**。
#         空字符串 = 目前没有调用方，等排期；一旦有人调用，判据 ② 会要求补上。
#         不要写"已处理"这类没有位置的话 —— 要能被 grep 到。
# ──────────────────────────────────────────────────────────────────────────────
PENDING = {
    # ★ 2026-11-12：最后一条 POST /assistants/{*}/workflow/debug 随 0.7.0 落地，
    #   已从这里删掉（后端台账 ContractDriftTest.KNOWN_MISSING 同一时刻摘的同一条）。
    #   前端页面侧的降级处理（"试用调不到就提示没开通"）可以撤了 —— 它现在能真的调通。
    #   ★ 空账不是"闸门坏了"：见 read_backend_ledger 的 docstring。

    # ★ 已落：POST /assistants/{*}/workflow/publish 随 #198 第四块后端交付（2026-10-31），
    #   故从本账里摘掉 —— 后端台账（ContractDriftTest.KNOWN_MISSING）同一时刻摘的同一条。
    # ★ 同日补记：当时这里写着"前端仍无页面调用 `publishWorkflow`"，那条**已经不成立** ——
    #   编排页的「发布」现在先调 `validateWorkflow`、通过了才调 `publishWorkflow`
    #   （`pages/workflow/Index.vue` 的 `publicHandle`）。本账只收"后端还没有的端点"，
    #   这条已实现，所以只留说明、不新增条目。
    # ★ 已落：GET /assistants/{*}/workflow/records 随 #198 第七块后端交付（2026-10-31），故从本账里摘掉
    #   —— 与后端台账（ContractDriftTest.KNOWN_MISSING）同一时刻、同一条。
    #   ★ 语义提醒（写给读代码的人）：这条端点**今天永远返回空列表** —— 运行记录表没有写入方
    #   （执行引擎在 V1.0）。前端契约函数 `listWorkflowRecords` 与页面入口都还没接，
    #   后者登记在 `tools/consumer-baseline.txt`。debug 那条仍未实现，不动。
    # ★ 已落：POST /assistants/{*}/workflow/versions/{*}/rollback 随 #198 第六块后端交付
    #   （2026-10-31），故从本账里摘掉 —— 与后端台账（ContractDriftTest.KNOWN_MISSING）同一时刻、
    #   同一条。★ 语义提醒（写给读代码的人）：这个回滚**只写草稿、不发布**，线上不受影响。
    # ★ 已落：POST /files 随 #187 后端交付（2026-11-01，V63 + FileStorePort），故从本账里摘掉
    #   —— 与后端台账（ContractDriftTest.KNOWN_MISSING）同一时刻、同一条。
    #   ★ 语义提醒（写给读代码的人）：它只**存字节 + 元数据**，返回的 id 今天没有任何消费方
    #   （ChatController 收下 attachmentIds 但不用）⇒ 上传成功 **不等于**附件能参与问答。
    #   那条边界写在 FileController 的类注释里。
}


def read_backend_ledger() -> list[tuple[str, str]]:
    """从 ContractDriftTest 的 KNOWN_MISSING 字段块里读逐条台账。

    ★ 只读字段块，不读整个文件 —— 里面的金丝雀测试也用 `missing("GET", "/users", …)` 造数据，
    整文件扫会把那些假端点当成真欠账。

    ★★ 台账**允许为空**（2026-11-12 起就是空的：最后一个待实现端点
    `POST /assistants/{*}/workflow/debug` 随 0.7.0 落地）。空不是"正则失配"——
    要区分这两件事，靠的是"`KNOWN_MISSING =` 还在不在"和"块里有没有 `List.of(`"，
    不是靠"读出来几条"。上一版把这两件事混成了一个判断，于是台账一清空，
    这道闸门就报"已失效"，而它其实什么都没坏。
    """
    text = DRIFT.read_text(encoding="utf-8")
    if "KNOWN_MISSING =" not in text:
        # 形状真的变了：字段被改名或删掉了，正则再怎么调也读不到
        raise LookupError("ContractDriftTest 里找不到 KNOWN_MISSING = 字段")
    start = text.index("KNOWN_MISSING =")
    tail = text.find("/**", start)
    block = text[start:] if tail < 0 else text[start:tail]
    if "List.of(" not in block:
        raise LookupError("KNOWN_MISSING 字段块里没有 List.of(，形状对不上")
    return re.findall(r'missing\("([A-Z]+)",\s*"([^"]+)"', block)



def segs_backend(path: str) -> list[str]:
    return ["*" if s == "{*}" else s for s in path.strip("/").split("/")]


def segs_frontend(url: str) -> list[str]:
    """前端 url 模板归一成段列表：`${x}` 与 `:x` 都算通配段。"""
    url = url.split("?")[0]
    url = re.sub(r"\$\{[^}]*\}", "*", url)
    url = re.sub(r":([A-Za-z_][A-Za-z0-9_]*)", "*", url)
    return url.strip("/").split("/")


def same_path(a: list[str], b: list[str]) -> bool:
    return len(a) == len(b) and all(x == "*" or y == "*" or x == y for x, y in zip(a, b))


def collect_declarations() -> list[tuple[str, int, str, str]]:
    """扫前端 api 层的每一条 `url:` 声明 → (文件, 行号, url 模板, 所属导出函数名)。"""
    decls: list[tuple[str, int, str, str]] = []
    for path in sorted(API_DIR.rglob("*.ts")):
        if path.name.endswith(".test.ts"):
            continue
        src = path.read_text(encoding="utf-8")
        for m in re.finditer(r"url:\s*[`'\"]([^`'\"]+)[`'\"]", src):
            line = src[: m.start()].count("\n") + 1
            names = list(re.finditer(r"export const (\w+)", src[: m.start()]))
            fn = names[-1].group(1) if names else "?"
            decls.append((path.relative_to(FRONTEND).as_posix(), line, m.group(1), fn))
    return decls


def count_callers(fn: str) -> int:
    """这个导出函数在 `src/pages` 下被多少个文件调用（跳过 import 行与定义行）。"""
    if fn == "?":
        return 0
    pattern = re.compile(r"\b" + re.escape(fn) + r"\s*\(")
    hits: set[str] = set()
    for path in PAGES.rglob("*"):
        if path.suffix not in {".vue", ".ts"}:
            continue
        src = path.read_text(encoding="utf-8")
        for m in pattern.finditer(src):
            line_start = src.rfind("\n", 0, m.start()) + 1
            line_end = src.find("\n", m.start())
            line = src[line_start : line_end if line_end != -1 else len(src)]
            if "import" in line or "export const" in line:
                continue
            hits.add(path.relative_to(FRONTEND).as_posix())
    return len(hits)


def check() -> int:
    try:
        ledger = read_backend_ledger()
    except LookupError as e:
        # ★ 退出码 3 = **闸门自身失效**（读不懂被检查的对象了），与 1（检查不通过）区分开。
        #   这是 `#332` 的核心：以前两者都返回 1，"闸门坏了"与"产品坏了"在 CI 上长得一样，
        #   维护者会朝错的方向修（`NAV` 期望表那次就是）。0 = 通过，1 = 不合规，3 = 闸门失效。
        print(f"✗ 闸门自身失效：台账读不出来 —— {e}")
        print("  这不是「检查不通过」，是「我读不懂被检查的对象了」。先修本脚本的正则或锚点。")
        return 3
    decls = collect_declarations()

    # ★ 台账为空是**合法状态**（2026-11-12 起就是空的）：所有待实现端点都已落地。
    #   "读出来 0 条"与"正则失配"必须分开判断 —— 前者由 read_backend_ledger 正常返回 [],
    #   后者由它 raise LookupError。上一版把两件事混成 `if not ledger`，于是台账一清空
    #   这道闸门就自报失效，而实际上什么都没坏。
    if not ledger:
        print("✓ 后端台账已清空（没有待实现端点）；前端这边的欠账账本同样应当为空")

    problems: list[str] = []
    declared: dict[str, list[str]] = {}

    for method, path in ledger:
        target = segs_backend(path)
        matched = [d for d in decls if same_path(target, segs_frontend(d[2]))]
        if not matched:
            continue
        key = f"{method} {path}"
        called = max(count_callers(d[3]) for d in matched)
        whereabouts = ", ".join(f"{d[0]}:{d[1]}" for d in matched)
        declared[key] = [str(called), whereabouts]

        entry = PENDING.get(key)
        if entry is None:
            problems.append(
                f"台账端点被前端声明，但没登记处置：{key}\n    声明处：{whereabouts}\n"
                f"    ⇒ 在 check_pending_endpoints.py 的 PENDING 里补一条（issue + guard）"
            )
            continue
        if not re.fullmatch(r"#\d+", entry["issue"]):
            problems.append(f"{key} 的 issue 号不是形如 #123：{entry['issue']!r}（账目必须指回一条真 Issue）")
        if called > 0 and not entry["guard"].strip():
            problems.append(
                f"{key} 已经有 {called} 个调用方，但账里没写 guard\n"
                f"    调用在：{whereabouts} 所声明的函数\n"
                f"    ⇒ 404 会变成未处理的 rejection ⇒ 页面「点了没反应」。补一条可读提示再登记"
            )

    # 判据 ③④：账里那些现在对不上的
    ledger_keys = {f"{m} {p}" for m, p in ledger}
    for key, entry in PENDING.items():
        if key not in ledger_keys:
            problems.append(
                f"账里还留着 {key}（{entry['issue']}），但它已不在后端台账里\n"
                f"    ⇒ 后端补上了（或这条台账被删了）：把账里这条删掉，并复核页面侧的降级还能不能撤"
            )
        elif key not in declared:
            problems.append(
                f"账里还留着 {key}，但前端已经没有这个声明了\n"
                f"    ⇒ 契约删了就删账；否则这是一条永远查不到的死账"
            )

    print(f"后端台账 {len(ledger)} 条 · 前端声明 {len(decls)} 条 · 账 {len(PENDING)} 条")
    for key in sorted(declared):
        called, whereabouts = declared[key]
        mark = "有调用" if called != "0" else "未调用"
        print(f"  {key:52s} {mark:6s} {whereabouts}")

    if problems:
        print()
        for p in problems:
            print(f"✗ {p}")
        return 1
    return 0


def shape_check() -> int:
    """形状自检：把锚点行故意改坏，闸门必须以退出码 3 失败。

    ★ 为什么不写在 `check()` 里：`check()` 读的是仓库真实文件，跑它不能证明"读不懂时会报 3" ——
    只有真的把锚点拿走一次，才能证明这条分支存在且生效（`#332` 判据 2、3）。

    做法：复制一份 ContractDriftTest 到临时目录、删掉 `KNOWN_MISSING =` 那一行，
    再让 `read_backend_ledger` 对着它跑（通过替换模块级 `DRIFT` 路径）。
    """
    import tempfile

    global DRIFT

    text = DRIFT.read_text(encoding="utf-8")
    if "KNOWN_MISSING =" not in text:
        print("✗ 形状自检无法进行：真实文件里就没有 KNOWN_MISSING =（它已经坏了）")
        return 3

    broken = text.replace("KNOWN_MISSING =", "KNOWN_MISSING_RENAMED =", 1)
    original = DRIFT
    try:
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "ContractDriftTest.java"
            fake.write_text(broken, encoding="utf-8")
            DRIFT = fake
            try:
                read_backend_ledger()
            except LookupError:
                print("✓ 形状自检：锚点被改名后 read_backend_ledger 如期抛 LookupError（check 会返回 3）")
                return 0
            print("✗ 形状自检失败：锚点被改名了，read_backend_ledger 却没抛错 —— 这道闸门察觉不到形状变化")
            return 1
    finally:
        DRIFT = original


def self_check() -> int:
    """内置样本：三段匹配逻辑各自给出预期结果（这道闸门自己的金丝雀）。"""
    cases = [
        (segs_backend("/files"), segs_frontend("/files"), True),
        (segs_backend("/assistants/{*}/workflow/draft"), segs_frontend("/assistants/:appId/workflow/draft"), True),
        (segs_backend("/assistants/{*}/workflow/draft"), segs_frontend("/assistants/${id}/workflow/draft"), True),
        # 段数不同必须不匹配 —— 否则 `/workflow/versions` 会吃掉 `/workflow/versions/3`
        (segs_backend("/assistants/{*}/workflow/versions"), segs_frontend("/assistants/1/workflow/versions/3"), False),
        # 字面段不同必须不匹配
        (segs_backend("/files"), segs_frontend("/files/x"), False),
        (segs_backend("/workflow/node-types"), segs_frontend("/workflow/nodes"), False),
    ]
    bad = 0
    for a, b, expected in cases:
        got = same_path(a, b)
        if got != expected:
            bad += 1
            print(f"✗ 自测失败：{a} vs {b} 期望 {expected} 实得 {got}")
    print(f"自测：{len(cases) - bad}/{len(cases)} 通过")

    # ★ 形状自检也进 --self-check（#332）：它证明的是「读不懂时会报 3」这条分支真的存在。
    shape_rc = shape_check()
    if bad or shape_rc != 0:
        return 1
    return 0


def main() -> int:
    if "--self-check" in sys.argv:
        return self_check()
    return check()


if __name__ == "__main__":
    sys.exit(main())
