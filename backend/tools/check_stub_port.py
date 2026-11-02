#!/usr/bin/env python3
"""桩服务端口的守卫（`#291`）。

**问题**：`OpenAiStubContractTest` 这一族连的是 `AI_STUB_ROOT`，默认 `http://localhost:11434`。
而 **11434 是 Ollama 的默认端口** —— 开发机上装了 ollama（容器或本机服务）就有了第二个消费者：

- 桩没起、ollama 在跑 ⇒ 测试打到真模型，报 `404: model 'stub-plain' not found`（看起来像代码坏了）；
- 桩起了、ollama 也在 ⇒ 谁先绑上谁赢，另一方的读数全是假的。

⇒ **一个端口不允许有两个消费者**。桩必须是"只有测试会用"的端口。

判据两条：
1. `src/test/**/*.java` 里 `AI_STUB_ROOT` 的**默认值**不得是 `11434`；
2. CI 里 `openai_stub.py --port N` 的 N 必须与那个默认值一致（否则测试连不上桩，而症状是"连不上上游"）。

用法：
  python tools/check_stub_port.py            # 报告，违规 exit 1
  python tools/check_stub_port.py --self-check
"""
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
TESTS = REPO / "backend" / "src" / "test" / "java"
WORKFLOWS = REPO / ".github" / "workflows"

# ★ 11434 = Ollama 的默认端口。它不能同时当桩端口（`#291` 的教训）。
OLLAMA_PORT = "11434"

DEFAULT = re.compile(r'AI_STUB_ROOT"\s*,\s*"http://[^":]+:(\d+)"')
CI_PORT = re.compile(r"openai_stub\.py\s+--port\s+(\d+)")
# ★ 第三处：CI 起了桩之后要用 curl 探活。探的端口与起的端口不一致 ⇒
#   "桩服务起不来" 而桩其实活着（`#291` 第一次修就栽在这里）。
CI_PROBE = re.compile(r"curl\s+-sf\s+http://[^\s:]+:(\d+)/v1/models")


def stub_defaults():
    found = {}
    for path in TESTS.rglob("*.java"):
        for m in DEFAULT.finditer(path.read_text(encoding="utf-8")):
            found.setdefault(m.group(1), []).append(path.relative_to(REPO))
    return found


def ci_ports():
    """返回 (起桩端口, 探活端口)，两组都要看 —— 起在 A、探 B 是最难查的形态。"""
    started, probed = {}, {}
    for path in WORKFLOWS.glob("*.yml"):
        text = path.read_text(encoding="utf-8")
        rel = path.relative_to(REPO)
        for m in CI_PORT.finditer(text):
            started.setdefault(m.group(1), []).append(rel)
        for m in CI_PROBE.finditer(text):
            probed.setdefault(m.group(1), []).append(rel)
    return started, probed


def report(defaults, started, probed):
    problems = []
    defaults = set(defaults)
    started = set(started)
    probed = set(probed)

    for port in sorted(defaults):
        if port == OLLAMA_PORT:
            problems.append(f"测试里 AI_STUB_ROOT 的默认端口用了 {OLLAMA_PORT}（那是 Ollama 的默认端口）")
    if len(defaults) > 1:
        problems.append(f"桩默认端口不止一个：{sorted(defaults)} —— 测试之间会各自连不同地方")
    for port in sorted(started):
        if port == OLLAMA_PORT:
            problems.append(f"CI 把桩起在 {OLLAMA_PORT}（Ollama 的默认端口）")
    # ★ 起在 A、探 B：最难查的形态（#291 第一次修就栽在这里 —— 桩起着，探针永远不通）
    if started and probed and started != probed:
        problems.append(
            f"CI 把桩起在 {sorted(started)}，探活却打 {sorted(probed)} —— 会报「桩服务起不来」而桩其实活着"
        )
    if defaults and started and defaults != started:
        problems.append(
            f"CI 起的桩端口 {sorted(started)} 与测试默认端口 {sorted(defaults)} 对不上 —— 测试会连不上桩"
        )
    return problems


def main(argv):
    defaults, started, probed = stub_defaults(), *ci_ports()
    print(f"测试里的 AI_STUB_ROOT 默认端口：{sorted(defaults) or '（一个都没找到）'}")
    print(f"CI 起的桩端口：{sorted(started) or '（没找到）'}")
    print(f"CI 探活打的端口：{sorted(probed) or '（没找到）'}")

    if "--self-check" in argv:
        print(" 自检（每条都要能红）：")
        cases = [
            ("默认端口用了 ollama 的端口", report({"11434"}, set(), set()), True),
            ("CI 把桩起在 ollama 端口", report(set(), {"11434"}, {"11434"}), True),
            ("起在 A、探 B", report({"11999"}, {"11999"}, {"11434"}), True),
            ("默认与 CI 对不上", report({"11999"}, {"12000"}, {"12000"}), True),
            ("当前仓库状态（应为通过）", report(defaults, started, probed), False),
        ]
        failed = 0
        for name, probs, want_fail in cases:
            got = bool(probs)
            ok = got == want_fail
            print(f"  {'✓' if ok else '✗'} {name}：{'报错' if got else '通过'}")
            if not ok:
                failed += 1
                for p in probs:
                    print(f"      {p}")
        return 0 if failed == 0 else 1

    problems = report(defaults, started, probed)
    if problems:
        print(" ✗ " + " ✗ ".join(problems))
        return 1
    print(" ✓ 桩端口与 Ollama 的默认端口分开了；CI 起的、探的、测试连的是同一个")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
