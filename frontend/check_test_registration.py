#!/usr/bin/env python3
"""第 14 道静态闸门：前端测试文件必须真的被 `npm test` 登记（#266）。

<h3>为什么需要这一道</h3>
<p>`frontend/package.json` 的 `test` 脚本**刻意逐条列文件**，不交给 `node --test` 的目录发现：
发现规则与 Node 版本挂钩，猜错就是「0 条测试、exit 0」—— 一个永远不红的闸门
（R58 的判据：修闸门与把闸门改成绿的读数完全一样，唯一的区别是「被测对象坏掉时它会不会报红」）。
代价是**新增测试要同时改两个地方**，而忘了改的那一半没有任何提示。</p>

<p>`#266` 就是这么来的：`src/lib/http-error.test.ts` 一直在仓库里，`npm test` 从没跑过它。
它当时恰好是绿的（2026-10-31 实测 4/4 通过），但「绿」与「根本没跑」在 CI 读数上完全一样。</p>

<h3>红在哪（两条主判据，互为反向）</h3>
<ol>
  <li><b>文件在、`test` 脚本里没有</b> ⇒ 红。这就是「测试写了但从不执行」。</li>
  <li><b>`test` 脚本里有、文件不在</b> ⇒ 红。那行字会让脚本看起来「登记得很全」，
      而 `node --test` 少跑一个文件与全跑完同样是 exit 0。</li>
</ol>
<p>另加两条守卫，防的是<b>闸门自己失效</b>：</p>
<ol start="3">
  <li>`test` 脚本里一个 `.test.ts` 都抓不到 ⇒ 红（有人把脚本改回目录发现，
      这道闸门就再也判不出东西 —— 它必须能自己变红）。</li>
  <li>登记了、但文件里一条 `test(` / `it(` 都没有 ⇒ 红（空壳文件同样是「exit 0 的假绿」）。</li>
</ol>

<h3>本闸门不判什么（写出来，免得被读成"它都管了"）</h3>
<ul>
  <li><b>`.vue` 里的测试</b>：`node --test` 不执行 `.vue`，本工程也从未把测试写进 `.vue`
      （实测 `src/` 下 0 个 `*.test.vue` / `*.spec.vue`）。真要写，得先换测试运行器，那是另一件事。</li>
  <li><b>测试本身测得对不对</b>：这里只回答「它会不会被执行」，不回答「它断言了什么」。</li>
  <li><b>`src/` 之外的测试</b>：登记表只可能与 `src/**/*.test.ts` 对账；别的目录里若有测试，
      既扫不到也不在本闸门的判据里。</li>
</ul>

用法：`python check_test_registration.py` / `python check_test_registration.py --self-check`
"""

from __future__ import annotations

import json
import re
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent  # frontend/
SRC = HERE / "src"
PACKAGE = HERE / "package.json"

# 登记项的形状：脚本里的 `src/lib/xxx.test.ts`（可带 `./` 前缀，也可带反斜杠 —— 见下）。
# ★ 只认「显式文件」，不认 glob / 目录：本工程的判据恰恰是「不许靠发现」。
REGISTERED_RE = re.compile(r"(?:\./)?((?:[\w.-]+/)*[\w.-]+\.test\.ts)")

# 一个测试文件里必须有断言入口，否则它就是个空壳（`node --test` 对它 exit 0）。
# 认 `test(` 与 `it(`（node:test 两个都导出）；不认 describe —— 它只分组，不产生用例。
CASE_RE = re.compile(r"^\s*(?:test|it)\s*\(", re.MULTILINE)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="replace")


def scan_test_files(src: Path) -> list[str]:
    """扫 `src/**/*.test.ts` → 相对 `frontend/` 的 posix 路径（升序，便于读数比对）。"""
    return sorted(p.relative_to(src.parent).as_posix() for p in src.rglob("*.test.ts"))


def load_script(package: Path) -> str | None:
    """读 `package.json` 的 `scripts.test`。None = 键缺失（与空串区分：那是两种毛病）。"""
    data = json.loads(read(package))
    scripts = data.get("scripts") or {}
    script = scripts.get("test")
    return script if isinstance(script, str) else None


def registered_files(script: str) -> list[str]:
    """从 `test` 脚本里抽出登记的文件路径，归一成 `src/...` 形状。**

    ★ 反斜杠先换成 `/`：JSON 里写 `src\\\\lib\\\\a.test.ts` 也应当被认出来，
      否则这条闸门会因为「登记写法换了个分隔符」而漏判（漏判的方向永远是安全的那一侧？不是 ——
      它会让脚本里的那行字与扫描结果对不上，于是既报「文件没登记」又报「登记的文件不存在」，
      两条噪音里藏着一条真问题）。
    """
    normalized = script.replace("\\", "/")
    return [m.group(1) for m in REGISTERED_RE.finditer(normalized)]


def judge(files: list[str], registered: list[str], root: Path) -> list[str]:
    """四条判据。`root` = `frontend/`（自检时是临时夹具根）—— 所有路径都相对它解析。"""
    problems: list[str] = []
    reg_set = set(registered)

    # 判据 ③ 先判：登记表整个空掉时，下面两条都会「什么都没发现」，读数会假装正常。
    if not registered:
        problems.append(
            "`test` 脚本里一个 `.test.ts` 都抓不到 —— 要么它被改回了目录发现（`node --test src`），"
            "要么脚本形状变了。在这两种前提下，这道闸门判不出任何东西（#266）"
        )

    if not files:
        problems.append(
            "`src` 下一个 `*.test.ts` 都没扫到 —— 目录结构或命名变了，"
            "别让它安静通过（这道闸门的全部意义就是「扫得到才算数」）"
        )

    # 判据 ①：文件在、登记里没有 —— 本次要堵的那一类。
    for f in files:
        if f not in reg_set:
            problems.append(
                f"文件在、但 `test` 脚本里没有：{f}"
                "（`npm test` 只跑脚本里显式列出的文件 ⇒ 这个测试从来没被执行过）"
            )
            continue
        # 判据 ④：登记了、却没有断言入口 —— 空壳文件同样是 exit 0。
        cases = len(CASE_RE.findall(read(root / f)))
        if cases == 0:
            problems.append(
                f"{f}：已登记，但文件里一条 `test(` / `it(` 都没有"
                "（`node --test` 对它 pass 0、exit 0 —— 与「测试全过」读数一样）"
            )

    # 判据 ②：登记里有、文件不在 —— 另一种「永不红」。
    for f in registered:
        if not (root / f).is_file():
            problems.append(
                f"`test` 脚本里登记了、但文件不存在：{f}"
                "（这行字让脚本看起来「登记得很全」，而它一个用例都不会跑）"
            )

    return problems


def self_check() -> int:
    """离线自检：在临时夹具上真跑上面四条判据（不碰真实仓库）。

    每条用例都走 `scan_test_files` + `registered_files` + `judge` 这三条实判函数，
    不是另写一套判断 —— 否则自检绿了也不说明实判能红。
    """
    failures: list[str] = []
    ran: list[str] = []
    fixtures: list[tempfile.TemporaryDirectory] = []

    def expect(name: str, ok: bool) -> None:
        # 记下「跑过」：末尾会断言条数，防止某条用例只写在注释里、或夹具异常让整段提前退出。
        ran.append(name)
        if not ok:
            failures.append(name)

    def build(files: dict[str, str], script: str | None) -> Path:
        """造一个假 `frontend/`：`files` 的键是相对 `src/` 的路径；`script=None` 表示不写 `test` 键。"""
        tmp = tempfile.TemporaryDirectory(prefix="test-registration-")
        fixtures.append(tmp)
        root = Path(tmp.name)
        (root / "src").mkdir()
        for rel, content in files.items():
            target = root / "src" / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        scripts: dict[str, str] = {} if script is None else {"test": script}
        (root / "package.json").write_text(
            json.dumps({"name": "fixture", "scripts": scripts}, ensure_ascii=False), encoding="utf-8"
        )
        return root

    def run(root: Path) -> list[str]:
        """按实判的方式跑一遍：扫目录 → 读脚本 → 判。"""
        script = load_script(root / "package.json")
        return judge(scan_test_files(root / "src"), registered_files(script or ""), root)

    TWO = "node --experimental-strip-types --test src/lib/a.test.ts src/lib/b.test.ts"

    # ① 全登记 ⇒ 不报
    root = build({"lib/a.test.ts": "test('a', () => {})", "lib/b.test.ts": "test('b', () => {})"}, TWO)
    problems = run(root)
    expect("全登记 ⇒ 无问题", problems == [])

    # ② 漏登记一个 ⇒ 报，且点名漏的那个、不牵连已登记的那个
    root = build(
        {"lib/a.test.ts": "test('a', () => {})", "lib/b.test.ts": "test('b', () => {})"},
        "node --experimental-strip-types --test src/lib/a.test.ts",
    )
    problems = run(root)
    expect(
        "漏一个 ⇒ 点名漏的那个",
        len(problems) == 1 and "lib/b.test.ts" in problems[0] and "lib/a.test.ts" not in problems[0],
    )

    # ③ 登记了不存在的文件 ⇒ 报，且点名那个不存在的
    root = build({"lib/a.test.ts": "test('a', () => {})"}, TWO)
    problems = run(root)
    expect(
        "登记了不存在的文件 ⇒ 点名",
        len(problems) == 1 and "src/lib/b.test.ts" in problems[0] and "不存在" in problems[0],
    )

    # ④ 脚本改回目录发现（一个文件都没抓到）⇒ 闸门自己必须变红
    root = build({"lib/a.test.ts": "test('a', () => {})"}, "node --experimental-strip-types --test src")
    problems = run(root)
    expect("脚本改成目录发现 ⇒ 报（闸门不许自己失效）", any("一个 `.test.ts` 都抓不到" in p for p in problems))

    # ⑤ 空壳文件（登记了、0 条断言）⇒ 报
    root = build({"lib/empty.test.ts": "// 这里曾经有断言\n"}, "node --test src/lib/empty.test.ts")
    problems = run(root)
    expect("空壳测试文件 ⇒ 报", len(problems) == 1 and "一条 `test(`" in problems[0])

    # ⑥ `package.json` 里没有 `test` 脚本 ⇒ load_script 返回 None（main 据此报红）
    root = build({"lib/a.test.ts": "test('a', () => {})"}, None)
    expect("缺 `test` 键 ⇒ load_script 返回 None", load_script(root / "package.json") is None)

    # ⑦ `it(` 与 `test(` 同等对待（防正则只写了一半）
    root = build({"lib/a.test.ts": "it('a', () => {})"}, "node --test src/lib/a.test.ts")
    expect("`it(` 也算断言入口", run(root) == [])

    for tmp in fixtures:
        tmp.cleanup()

    if len(ran) != SELF_CHECK_TOTAL:
        failures.append(f"自检条数不符：跑了 {len(ran)} 条，应为 {SELF_CHECK_TOTAL}（有用例没被执行到）")
    if failures:
        print(f"✗ 自检失败 {len(failures)}/{len(ran)}：")
        for name in failures:
            print(f"  - {name}")
        return 1
    print(f"✓ 自检通过（{len(ran)} 条：全登记 / 漏一个 / 登记了不存在 / 目录发现 / 空壳 / 缺 test 键 / it 入口）")
    return 0


SELF_CHECK_TOTAL = 7


def main(argv: list[str]) -> int:
    if "--self-check" in argv:
        return self_check()

    if not SRC.is_dir():
        print(f"找不到测试目录：{SRC}", file=sys.stderr)
        return 2
    script = load_script(PACKAGE)
    if script is None:
        print(
            f"✗ {PACKAGE} 的 `scripts` 里没有 `test` —— 这道闸门没有可对账的登记表",
            file=sys.stderr,
        )
        return 2

    files = scan_test_files(SRC)
    registered = registered_files(script)
    problems = judge(files, registered, HERE)

    print(
        f"扫到 `src/**/*.test.ts` {len(files)} 个 · `test` 脚本登记 {len(set(registered))} 个"
        f"（出现 {len(registered)} 次）"
    )
    reg_set = set(registered)
    for f in files:
        print(f"  {'✓' if f in reg_set else '✗'} {f}")

    # 重复登记不判红（那会让同一个文件跑两遍，不是「永不红」），但要说出来 ——
    # 它常常是「改了脚本又追加了一遍」留下的痕迹，而这类痕迹附近最容易漏登记。
    duplicated = sorted({f for f in registered if registered.count(f) > 1})
    if duplicated:
        print(f"\n提示：`test` 脚本里重复登记了 {len(duplicated)} 个文件（会跑两遍，不影响判据）：{', '.join(duplicated)}")

    if problems:
        print(f"\n有问题的条目 {len(problems)} 条：")
        for p in problems:
            print(f"  ✗ {p}")
        print("\n⇒ 测试文件与 `npm test` 登记不一致（#266）")
        return 1

    print("\n✓ 每个测试文件都在 `test` 脚本里；脚本里每个文件都存在且至少有 1 条断言入口")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
