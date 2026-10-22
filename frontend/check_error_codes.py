"""错误码一致性闸门（issue #8）。

核三件事，全部静态可判：

  ① 契约（前端冻结的 `ErrorCode`）与后端枚举（`ErrorCode.java`）**逐值一致**
     —— 任一侧多了码、或同一个名字的值不同，都红
  ② mock 里**只允许出现契约里有的码**
     —— issue #8 就是这么来的：mock 自造了一套 4001–4006「影子编号」，
        而后端一律返 BAD_REQUEST(400) ⇒ 同一份契约两种取值，
        按 code 分支的代码在 mock 与真栈上会走不同分支（而且前端当时不按码分支 ⇒ 完全看不见）
  ③ 后端枚举里出现的每一个码都在契约里（后端不得先行自造码）

★ 这条闸门**能测什么、不能测什么**要说清：
  它保证"取值集合一致"，**不保证"同一种错误在两边是同一个码"** ——
  例如 mock 返 BAD_REQUEST 而后端返 EMBEDDING_LOCKED，两者都在契约里，静态查不出来。
  那一层要靠后端契约测试（`ContractDriftTest`）与真栈闸门。写在这里免得后人误以为它包打天下。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONTRACT = ROOT / "frontend/src/api/types.ts"
BACKEND_ENUM = ROOT / "backend/src/main/java/cn/lincoln/aiplatform/platform/domain/ErrorCode.java"
MOCK_DIR = ROOT / "frontend/src/mock"

ok: list[tuple[bool, str]] = []


def check(cond: bool, msg: str) -> None:
    ok.append((cond, msg))
    print(("  ✓ " if cond else "  ✗ ") + msg)


def contract_codes() -> dict[str, int]:
    """契约 `export const ErrorCode = { NAME: value, ... } as const`"""
    text = CONTRACT.read_text(encoding="utf-8")
    body = re.search(r"export const ErrorCode = \{(.*?)\n\} as const", text, re.S)
    if not body:
        raise SystemExit("★ 解析不出契约里的 ErrorCode —— 闸门本身失效了，先修它")
    found: dict[str, int] = {}
    for name, value in re.findall(r"^\s*([A-Z][A-Z0-9_]*):\s*(-?\d+)", body.group(1), re.M):
        found[name] = int(value)
    return found


def backend_codes() -> dict[str, int]:
    """后端枚举常量：`NAME(123, "文案")`"""
    text = BACKEND_ENUM.read_text(encoding="utf-8")
    body = re.search(r"public enum ErrorCode \{(.*?)\n\s*private final int code;", text, re.S)
    if not body:
        raise SystemExit("★ 解析不出后端 ErrorCode 枚举 —— 闸门本身失效了，先修它")
    return {
        name: int(value)
        for name, value in re.findall(r'^\s*([A-Z][A-Z0-9_]*)\((\d+),\s*"', body.group(1), re.M)
    }


def mock_literal_codes() -> list[tuple[str, int, int]]:
    """mock 里 `new ApiError(<数字>` 的字面量码 —— 返回 (文件, 行号, 码)"""
    hits: list[tuple[str, int, int]] = []
    for path in sorted(MOCK_DIR.rglob("*.ts")):
        text = path.read_text(encoding="utf-8")
        for match in re.finditer(r"new\s+ApiError\(\s*(\d+)", text):
            line = text[: match.start()].count("\n") + 1
            hits.append((path.relative_to(ROOT).as_posix(), line, int(match.group(1))))
    return hits


def main() -> int:
    contract = contract_codes()
    backend = backend_codes()
    print(f"契约 ErrorCode：{len(contract)} 项 · 后端枚举：{len(backend)} 项\n")

    print("== 1. 契约与后端枚举逐值一致 ==")
    only_contract = {k: v for k, v in contract.items() if k not in backend}
    only_backend = {k: v for k, v in backend.items() if k not in contract}
    mismatched = {
        k: (contract[k], backend[k]) for k in contract if k in backend and contract[k] != backend[k]
    }
    check(
        not mismatched,
        "同名码的值一致" + (f"（不一致：{mismatched}）" if mismatched else ""),
    )
    check(
        not only_backend,
        "后端没有自造契约外的码"
        + (f"（多出：{sorted(only_backend)} —— 后端要先改契约" if only_backend else ""),
    )
    if only_contract:
        print(f"     （提示，不算失败）契约里后端尚未实现的码：{sorted(only_contract)}")

    print("\n== 2. mock 只发契约里有的码 ==")
    hits = mock_literal_codes()
    bad = [(f, ln, c) for f, ln, c in hits if c not in contract.values()]
    for f, ln, c in bad:
        print(f"     {f}:{ln}  码 {c} 不在契约里")
    check(
        not bad,
        f"mock 里的字面量错误码（{len(hits)} 处）全部在契约集合内"
        + (f"（{len(bad)} 处越界）" if bad else ""),
    )

    print("\n== 3. mock 是否还在用「影子编号」式的自造成段 ==")
    # 4001–4006 是 issue #8 里的那一段；契约里不存在任何 4xxx 的非 HTTP 码
    shadow = [(f, ln, c) for f, ln, c in hits if 4000 <= c < 4100]
    check(not shadow, f"没有 4xxx 非 HTTP 码（实测 {len(shadow)} 处）")

    print("\n== 4. 同一错误在 mock 与后端同码（#41 T184） ==")
    # 这一节只钉住一条最容易被“各自都在契约里”掩盖的错误：
    # 改密时原密码错。mock 与后端都必须抛 OLD_PASSWORD_MISMATCH(1004)，
    # 不能一边 BAD_REQUEST、一边 OLD_PASSWORD_MISMATCH。
    mock_change_password = (
        ROOT / "frontend/src/mock/handlers/identity.ts"
    ).read_text(encoding="utf-8")
    backend_change_password = (
        ROOT
        / "backend/src/main/java/cn/lincoln/aiplatform/identity/application/AuthService.java"
    ).read_text(encoding="utf-8")
    mock_old_password = "ErrorCode.OLD_PASSWORD_MISMATCH" in mock_change_password
    backend_old_password = (
        "ErrorCode.OLD_PASSWORD_MISMATCH" in backend_change_password
    )
    check(mock_old_password, "mock 的旧密码错误使用 OLD_PASSWORD_MISMATCH")
    check(backend_old_password, "后端的旧密码错误使用 OLD_PASSWORD_MISMATCH")
    check(
        mock_old_password and backend_old_password,
        "同一旧密码错误在 mock 与后端同码（T184）",
    )

    bad_msgs = [m for c, m in ok if not c]
    print("=" * 60)
    print(f"通过 {len(ok) - len(bad_msgs)}/{len(ok)}")
    for m in bad_msgs:
        print("  - " + m)
    return 1 if bad_msgs else 0


if __name__ == "__main__":
    sys.exit(main())
