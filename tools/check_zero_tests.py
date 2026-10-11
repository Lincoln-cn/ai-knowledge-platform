"""零用例闸门：把「`Tests run: 0` 报成绿」变成红。

用法：

    python tools/check_zero_tests.py backend/target/surefire-reports     # 扫报告目录（CI 用）
    python tools/check_zero_tests.py .git/be-test.log                    # 或扫一份 maven 输出（本机用）

## 为什么必须有它

`@BeforeAll` 里的 `assumeThat(...)` 一旦不成立，JUnit 会**中止整个容器**，而 surefire 打出的正是：

    Tests run: 0, Failures: 0, Errors: 0, Skipped: 0 -- in ...FullTextSearchTest
    BUILD SUCCESS

—— 不含 skip、不含失败、**纯绿**。2026-10-11 实测（Issue #53）：桩（`localhost:11434`）没起时，
**8 个**依赖它的类全部以这种形态「绿」着（`DocumentManagementTest` / `FullTextSearchTest` /
`RetrievalSecurityTest` / `RetrievalHitTestTest` / `EmbeddingPipelineTest` / `OpenAiStubContractTest` /
`UpstreamEmbeddingAdapterTest` …），而它们的用例在 CI 里是真的在跑的。

⇒ **判绿的依据必须是「这个类真的跑了 N 条」**，而不是「构建成功」。这条闸门就是那句话的机器版本，
它同时覆盖三种来源：桩没起（本次实测）、`-Dtest=A,B` 静默不跑（Issue #50）、以及将来某天新加的一个 `assume`。

★ **本机使用的注意**：`target/surefire-reports` 是**累积**的（上一次跑留下的报告还在），
所以在本机要看「**这一次**跑成什么样」，请用 **log 模式**（把 maven 输出落到文件再扫），
或者先删掉 `backend/target/surefire-reports`。CI 里目录是干净的，两种模式等价。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# ★ 确实「不该跑」的类要**逐条写明理由**（Issue #53 判据 ③：要么有读数，要么写明它为什么不该跑）。
#   清单必须能回答「为什么它在里面」，否则它会变成第二个黑洞。
ALLOWED_ZERO_TESTS: dict[str, str] = {
    "cn.lincoln.aiplatform.modelcatalog.ModelCapabilityProbeTest": (
        "M0 探针（ROADMAP §6.1 P3）：只有声明了 AI_PROBE_MODELS（真 Ollama 模型）才跑 —— "
        "类 javadoc 写明「不装模型也要能进测试套件」，否则 mvn verify 会在任何没装 9GB 模型的机器上变红。"
        " ★ 注意：它与要抓的那 8 个类用的是**同一个机制**（@BeforeAll 里的 assumption），"
        "所以这里必须显式列出，而不是让整条闸门失效。"
    ),
}

# maven 的控制台输出会把长行折行（类名可能被截成两半）⇒ 连上下一行再取类名
_CONTINUATION = re.compile(r"^[\w.$]+$")

NO_CLASS = "(取不到类名)"


def _read_text(path: Path) -> str:
    """读一份日志 —— ★ 编码必须自适应，否则这个闸门会**读了却什么都没看见**。

    ★ 为什么必须处理：本机最自然的取数方式 `mvn … > log 2>&1` 在 **PowerShell 5.1** 下写出的是
    **UTF-16LE**（文件头 `FF FE`）。按 UTF-8 去读它不会报错，只会得到一堆乱码 ⇒ 匹配不到 `Tests run: 0`
    ⇒ 闸门安静地报绿。这正是本项目记过的那类"取数通道坏了，却看起来像没问题"（见 MEMORY 的
    "取不到日志 ≠ CI 没问题"条）。
    """
    raw = path.read_bytes()
    if raw.startswith(b"\xff\xfe") or raw.startswith(b"\xfe\xff"):
        return raw.decode("utf-16")
    if raw.startswith(b"\xef\xbb\xbf"):
        return raw.decode("utf-8-sig")
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        # 老工具/GBK 控制台留下的记录：解不出来也要读，不能静默放弃
        return raw.decode("gbk", errors="replace")


def _lines_of(target: Path):
    if target.is_dir():
        files = sorted(target.glob("*.txt"))
        if not files:
            raise SystemExit(f"★ {target} 里没有 surefire 报告（*.txt）—— 先跑测试，或路径写错了？")
        for report in files:
            yield report.name, _read_text(report).splitlines()
    else:
        yield target.name, _read_text(target).splitlines()


def _class_of(joined: str) -> str:
    match = re.search(r"-- in (\S+)", joined)
    return match.group(1).rstrip(".") if match else NO_CLASS


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    target = Path(argv[1])
    if not target.exists():
        print(f"★ 找不到 {target}（报告目录或 maven 日志都行）")
        return 2

    offenders: list[tuple[str, str]] = []
    allowed_seen: list[str] = []
    zero_lines = 0
    zero_totals = 0  # 没有 `-- in 类名` 的那些 = 整套跑成 0 条（与"某个类 0 条"是两件事）

    for _name, lines in _lines_of(target):
        index = 0
        while index < len(lines):
            line = lines[index]
            if "Tests run: 0," in line:
                zero_lines += 1
                joined = line
                following = index + 1
                # 折行：类名被切成两半时把下一行拼回来
                while (
                    following < len(lines)
                    and "-- in" in joined
                    and _CONTINUATION.fullmatch(lines[following].strip() or "")
                ):
                    joined += lines[following].strip()
                    following += 1
                found = _class_of(joined)
                if found == NO_CLASS:
                    zero_totals += 1
                    index = following
                    continue
                hit = next(
                    (key for key in ALLOWED_ZERO_TESTS if found == key or found.endswith(key)),
                    None,
                )
                if hit:
                    allowed_seen.append(hit)
                else:
                    offenders.append((found, joined.strip()))
                index = following
                continue
            index += 1

    print(f"零用例闸门：扫了 {target}")
    if zero_lines == 0:
        print("  没有任何类报 `Tests run: 0` ⇒ 每个跑到的类都真的跑了用例。")
        return 0

    print(f"  共 {zero_lines} 处 `Tests run: 0`（其中「整套 0 条」的总计行 {zero_totals} 处）")
    for key in sorted(set(allowed_seen)):
        print(f"  [允许] {key}")
        print(f"         {ALLOWED_ZERO_TESTS[key]}")

    if not offenders and zero_totals == 0:
        print("  ⇒ 不在白名单里的一个都没有：绿。")
        return 0
    if not offenders:
        print("  ⇒ 没有任何一个类报 0 条，但**整套跑成了 0 条** —— 那同样是「没跑」，不是通过。")
        return 1

    print()
    for found, raw in offenders:
        print(f"  [红] {found}")
        print(f"       {raw}")
    print()
    print(f"★ {len(offenders)} 个类报了 `Tests run: 0` —— 那是「报告说绿、用例一条没跑」，不是通过。")
    print("  最常见的原因：**桩服务没起**（%TEMP%\\aip-stub.cmd，端口 11434）。")
    print("  这些类的 @BeforeAll 里有 assumeThat(桩可达)；假设不成立时 JUnit 会中止整个容器，")
    print("  而 surefire 打出的正是 `Tests run: 0 … Skipped: 0` + `BUILD SUCCESS`。")
    print("  第二种原因：`-Dtest=A,B` 写错/写重（见 Issue #50），或某天新加的一个 assume。")
    print("  确实「不该跑」的类 ⇒ 在 ALLOWED_ZERO_TESTS 里加一条**并写明理由**。")
    return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
