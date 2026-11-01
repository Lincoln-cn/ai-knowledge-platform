"""测试专用类型闸门 —— 主代码零引用、却只被测试 import 的类型必须登记。

## 为什么要有这道闸门

`#270` 的教训：`knowledge/infrastructure/NoiseStripper`（257 行、自带一套剥噪声规则）
在主代码里**零引用**，生产走的是 `knowledge/domain/NoiseStripper`，可
`ParseMetricsTest` 一直拿它当被测对象 ⇒ 报告上的读数（噪声剥离 12/12）量的是**另一份实现**，
生产那份实际只有 11/12。那份多出来的「版权声明行」规则，最后变成了 `#271` 要拍板的争议。

一句话：**测试量错了对象，读数照样是绿的**。这类缺陷没有任何现有闸门能发现 ——
编译过、测试过、覆盖率甚至更高，只是量的不是线上那份代码。

## 判据

1. 候选（测试 import、主代码零引用、且不是靠容器生效的装配件）必须逐条登记在
   `backend/tools/test-only-types-baseline.txt` 里，否则退 1。
   ★ 登记格式 = `全限定类名` + 两个空格 + `# 理由`。
2. 基线里的条目如果**不再是候选**（类被删了、或被主代码引用了）⇒ 退 1。
   与 `tools/mock-shape-skip-baseline.txt` 同一条规矩：**基线必须随清理收缩**，
   否则它会慢慢变成一张「曾经有问题」的名单，没人再核对。
3. 基线文件缺失 ⇒ 退 1（缺基线等于判据消失，不许当成"空名单"）。
4. 扫到 0 个主代码类、或 0 个测试类 ⇒ 退 1（路径一变就以「全空一致」收场，`#262` 的老形状）。

## 豁免（不算候选）

- 类体里出现 `@Component` / `@Configuration` / `@Bean`：Spring 装配件，靠容器生效，
  主代码本来就没人写它的名字。
- `implements XxxPort`：端口实现，靠注入生效（本项目「端口在 platform、实现放提供方」的铁律）。

★ 豁免只看**类文件自己的文本**，不做符号解析 —— 故意保守：宁可少抓，也不要一张动不动就红的网。

## 用法

    python backend/tools/check_test_only_types.py            # 实判
    python backend/tools/check_test_only_types.py --self-check  # 离线自检（6 条）
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
MAIN_ROOT = ROOT / "backend" / "src" / "main" / "java"
TEST_ROOT = ROOT / "backend" / "src" / "test" / "java"
BASELINE = ROOT / "backend" / "tools" / "test-only-types-baseline.txt"

PACKAGE_PREFIX = "cn.lincoln.aiplatform."

_IMPORT_RE = re.compile(r"^import\s+(" + re.escape(PACKAGE_PREFIX) + r"[\w.]+);", re.M)
_IDENT_RE = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\b")
_WIRING_RE = re.compile(r"@(?:Component|Configuration|Bean)\b")
_PORT_RE = re.compile(r"implements\s+[A-Za-z0-9_.,\s]*?\b[A-Za-z0-9_]*Port\b")


def main_classes(main_root: pathlib.Path) -> dict[str, pathlib.Path]:
    """全限定类名 → 文件。"""
    found: dict[str, pathlib.Path] = {}
    for path in pathlib.Path(main_root).rglob("*.java"):
        fqcn = str(path.relative_to(main_root).with_suffix("")).replace("\\", "/").replace("/", ".")
        found[fqcn] = path
    return found


def name_uses(main_root: pathlib.Path, classes: dict[str, pathlib.Path]) -> dict[str, int]:
    """简单类名在**别的主代码文件**里出现过几次。0 = 主代码零引用。"""
    texts = {fqcn: path.read_text(encoding="utf-8", errors="ignore") for fqcn, path in classes.items()}
    uses: dict[str, int] = {}
    for fqcn, path in classes.items():
        simple = fqcn.rsplit(".", 1)[-1]
        pattern = re.compile(r"\b" + re.escape(simple) + r"\b")
        count = sum(1 for other, text in texts.items() if classes[other] != path and pattern.search(text))
        uses[fqcn] = count
    return uses


def test_imports(test_root: pathlib.Path, classes: dict[str, pathlib.Path]) -> dict[str, int]:
    """主代码类型 → 被多少个测试文件 import。"""
    imports: dict[str, int] = {}
    for path in pathlib.Path(test_root).rglob("*.java"):
        text = path.read_text(encoding="utf-8", errors="ignore")
        for match in _IMPORT_RE.finditer(text):
            fqcn = match.group(1)
            if fqcn in classes:
                imports[fqcn] = imports.get(fqcn, 0) + 1
    return imports


def is_wiring(path: pathlib.Path) -> bool:
    text = path.read_text(encoding="utf-8", errors="ignore")
    return bool(_WIRING_RE.search(text)) or bool(_PORT_RE.search(text))


def find_candidates(main_root: pathlib.Path, test_root: pathlib.Path) -> tuple[list[str], int]:
    """返回（候选全限定类名，豁免掉的装配件数）。"""
    classes = main_classes(main_root)
    if not classes:
        raise SystemExit(f"✗ 在主代码目录里一个 java 文件都没扫到：{main_root}（路径变了？）")
    imports = test_imports(test_root, classes)
    if not imports:
        raise SystemExit(f"✗ 测试里一个主代码类型都没 import：{test_root}（路径变了？）")
    uses = name_uses(main_root, classes)
    candidates: list[str] = []
    exempted = 0
    for fqcn, count in sorted(imports.items()):
        if uses.get(fqcn, 0) > 0:
            continue
        if is_wiring(classes[fqcn]):
            exempted += 1
            continue
        candidates.append(fqcn)
    return candidates, exempted


def load_baseline(path: pathlib.Path) -> dict[str, str]:
    """全限定类名 → 理由。"""
    path = pathlib.Path(path)
    if not path.exists():
        raise SystemExit(
            f"✗ 基线文件不存在：{path}\n"
            "  缺基线等于判据消失 —— 不许当成「空名单」放行（#270）"
        )
    entries: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "#" in line:
            name, reason = line.split("#", 1)
            name, reason = name.strip(), reason.strip()
        else:
            name, reason = line, ""
        if not name:
            continue
        if not reason:
            raise SystemExit(f"✗ 基线条目没有写理由：{line}\n  没有理由的豁免等于永久豁免（#270）")
        entries[name] = reason
    return entries


def judge(candidates: list[str], baseline: dict[str, str]) -> list[str]:
    """返回问题清单；空 = 通过。"""
    problems: list[str] = []
    for fqcn in candidates:
        if fqcn not in baseline:
            problems.append(
                f"测试 import 了、主代码零引用，却没登记：{fqcn} —— "
                "这条要么是「第二份实现」（测试量的不是线上那份，#270），要么是死代码"
            )
    candidates_set = set(candidates)
    for fqcn, reason in sorted(baseline.items()):
        if fqcn not in candidates_set:
            problems.append(
                f"基线里的 {fqcn} 已经不是候选了（类被删，或被主代码引用了）—— 请删掉这一行"
                f"（原理由：{reason}）"
            )
    return problems


SAMPLE_BASELINE = {"cn.lincoln.aiplatform.x.LegacyHelper": "历史遗留，等 #999 删"}


def self_check() -> int:
    cases: list[tuple[str, list[str], dict[str, str] | None, bool]] = [
        ("① 候选与基线一致 ⇒ 通过", ["cn.lincoln.aiplatform.x.LegacyHelper"], SAMPLE_BASELINE, True),
        (
            "② 出现新候选 ⇒ 报红",
            ["cn.lincoln.aiplatform.x.LegacyHelper", "cn.lincoln.aiplatform.y.Fresh"],
            SAMPLE_BASELINE,
            False,
        ),
        ("③ 基线条目已过期 ⇒ 报红（基线必须收缩）", [], SAMPLE_BASELINE, False),
        ("④ 候选为空、基线为空 ⇒ 通过", [], {}, True),
        ("⑤ 基线缺文件 ⇒ 报红", ["cn.lincoln.aiplatform.x.A"], None, False),
    ]
    failures = 0
    for label, candidates, baseline, expect_pass in cases:
        if baseline is None:
            try:
                load_baseline(ROOT / "backend" / "tools" / "zz-no-such-baseline.txt")
            except SystemExit:
                ok = not expect_pass
            else:
                ok = False
        else:
            problems = judge(candidates, baseline)
            ok = (not problems) == expect_pass
        mark = "✓" if ok else "✗"
        if not ok:
            failures += 1
        print(f"  {mark} {label}")

    # ⑥ 装配件豁免：@Component 与 implements …Port 不算候选，普通工具类算
    tmp = pathlib.Path(tempfile.mkdtemp(prefix="tot-"))
    wiring = tmp / "Wired.java"
    wiring.write_text("@Component\npublic class Wired {}\n", encoding="utf-8")
    port_impl = tmp / "Adapter.java"
    port_impl.write_text("public class Adapter implements UserDisplayNamePort {}\n", encoding="utf-8")
    plain = tmp / "Plain.java"
    plain.write_text("public final class Plain {}\n", encoding="utf-8")
    wiring_ok = is_wiring(wiring) and is_wiring(port_impl) and not is_wiring(plain)
    if not wiring_ok:
        failures += 1
    print(f"  {'✓' if wiring_ok else '✗'} ⑥ 装配件豁免：@Component / implements …Port 豁免，普通工具类不豁免")

    if failures:
        print(f"✗ 自检失败 {failures} 条")
        return 1
    print(f"✓ 自检通过（{len(cases) + 1} 条）")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="测试专用类型闸门（#270）")
    parser.add_argument("--self-check", action="store_true", help="离线自检，不扫仓库")
    args = parser.parse_args()
    if args.self_check:
        return self_check()

    candidates, exempted = find_candidates(MAIN_ROOT, TEST_ROOT)
    baseline = load_baseline(BASELINE)
    problems = judge(candidates, baseline)
    print(
        f"候选（测试 import、主代码零引用、非装配件）：{len(candidates)} 个 · "
        f"豁免装配件：{exempted} 个 · 基线：{len(baseline)} 条"
    )
    if problems:
        for problem in problems:
            print(f"✗ {problem}")
        return 1
    print("✓ 没有未登记的测试专用类型")
    return 0


if __name__ == "__main__":
    sys.exit(main())
