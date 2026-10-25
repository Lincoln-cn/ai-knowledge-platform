#!/usr/bin/env python3
"""发布台账闸门：版本三处一致 + CHANGELOG 有对应条目。

这套仓库栽过一次「两套编号」：`backend/pom.xml` 写着 `0.0.1-SNAPSHOT`、
`frontend/package.json` 写着 `0.1.0`、`CHANGELOG.md` 最新一节是 `0.4.4`。
三者各自"看起来没问题"，合起来就是"发布的到底是哪个版本"答不上来。

本闸门只做三件可核对的事：

1. `backend/pom.xml` 的项目 `<version>` == `frontend/package.json` 的 `version`
   == `frontend/package-lock.json` 的根 `version` 与 `packages[""].version`；
2. 版本号不以 `-SNAPSHOT` 结尾（版本号是**发布切点**，不是构建号）；
3. `CHANGELOG.md` 里有一节 `## <version> ...`，且这一节有正文（不是空标题）。

`--self-test` 用临时目录造出"一致 / package.json 漂移 / 缺 CHANGELOG 节 / SNAPSHOT"
四个样本，证明这个闸门**会红**，而不是一条永远为真的检查。
"""

from __future__ import annotations

import json
import re
import sys
import tempfile
from pathlib import Path

POM = Path("backend/pom.xml")
PACKAGE = Path("frontend/package.json")
LOCK = Path("frontend/package-lock.json")
CHANGELOG = Path("CHANGELOG.md")

POM_PROJECT_VERSION = re.compile(
    r"<artifactId>ai-knowledge-platform</artifactId>\s*<version>([^<]+)</version>"
)
CHANGELOG_HEADING = re.compile(r"^##\s+(\S+)(?:\s+(.*))?$", re.MULTILINE)


def read_pom_version(root: Path) -> str | None:
    text = (root / POM).read_text(encoding="utf-8")
    match = POM_PROJECT_VERSION.search(text)
    return match.group(1).strip() if match else None


def read_frontend_versions(root: Path) -> dict[str, str | None]:
    package = json.loads((root / PACKAGE).read_text(encoding="utf-8"))
    lock = json.loads((root / LOCK).read_text(encoding="utf-8"))
    return {
        "frontend/package.json": package.get("version"),
        'frontend/package-lock.json#packages[""].version': lock.get("packages", {})
        .get("", {})
        .get("version"),
        "frontend/package-lock.json#version": lock.get("version"),
    }


def changelog_section(root: Path, version: str) -> str | None:
    text = (root / CHANGELOG).read_text(encoding="utf-8")
    headings = list(CHANGELOG_HEADING.finditer(text))
    for index, heading in enumerate(headings):
        if heading.group(1) == version:
            end = headings[index + 1].start() if index + 1 < len(headings) else len(text)
            return text[heading.end() : end].strip()
    return None


def check_tree(root: Path) -> list[str]:
    problems: list[str] = []

    pom_version = read_pom_version(root)
    if not pom_version:
        problems.append("backend/pom.xml：找不到 ai-knowledge-platform 的项目 <version>")
        return problems

    if pom_version.endswith("-SNAPSHOT") or pom_version.endswith("-snapshot"):
        problems.append(f"版本号 {pom_version} 带 SNAPSHOT：发布切点不能是构建号")

    sources = {"backend/pom.xml": pom_version, **read_frontend_versions(root)}
    for label, value in sources.items():
        if not value:
            problems.append(f"{label}：找不到 version 字段")
        elif value != pom_version:
            problems.append(f"版本不一致：{label} = {value!r}，pom = {pom_version!r}")

    section = changelog_section(root, pom_version)
    if section is None:
        problems.append(f"CHANGELOG.md：没有 `## {pom_version}` 这一节")
    elif len(section) < 40:
        problems.append(
            f"CHANGELOG.md：`## {pom_version}` 这一节几乎没有正文"
            "（CHANGELOG 写“这一版能做什么”，不是一句话占位）"
        )

    return problems


def write_fixture(root: Path, version: str, package_version: str | None = None,
                  changelog_version: str | None = None) -> None:
    (root / "backend").mkdir(parents=True, exist_ok=True)
    (root / "frontend").mkdir(parents=True, exist_ok=True)
    (root / POM).write_text(
        f"<project><artifactId>ai-knowledge-platform</artifactId>"
        f"<version>{version}</version></project>\n",
        encoding="utf-8",
    )
    package_version = package_version or version
    (root / PACKAGE).write_text(
        json.dumps({"name": "frontend", "version": package_version}), encoding="utf-8"
    )
    (root / LOCK).write_text(
        json.dumps(
            {
                "name": "frontend",
                "version": package_version,
                "packages": {"": {"name": "frontend", "version": package_version}},
            }
        ),
        encoding="utf-8",
    )
    changelog_version = changelog_version or version
    (root / CHANGELOG).write_text(
        f"# 变更记录\n\n## {changelog_version} · 第 17 周 · 测试\n\n"
        "这一版能做什么：测试用的正文。它要足够长，才能区分“真的写了这一版”"
        "与“只留了一个标题占位”。\n\n"
        "## 0.0.1 · 第 1 周 · 骨架\n\n旧版本。\n",
        encoding="utf-8",
    )


def self_test() -> int:
    cases = [
        ("一致", {}, []),
        ("package.json 漂移", {"package_version": "0.4.4"}, ["版本不一致"]),
        ("缺 CHANGELOG 节", {"changelog_version": "0.9.9"}, ["没有 `## 0.4.5`"]),
        ("SNAPSHOT", {"version": "0.4.5-SNAPSHOT"}, ["SNAPSHOT"]),
    ]
    failed = 0
    for name, kwargs, expected in cases:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            write_fixture(root, version=kwargs.get("version", "0.4.5"),
                          package_version=kwargs.get("package_version"),
                          changelog_version=kwargs.get("changelog_version"))
            problems = check_tree(root)
            if expected:
                ok = all(any(fragment in problem for problem in problems)
                         for fragment in expected)
            else:
                ok = not problems
            print(f"{'✓' if ok else '✗'} 自测：{name} → {problems or '无问题'}")
            failed += 0 if ok else 1
    return 0 if failed == 0 else 1


def main(argv: list[str]) -> int:
    if "--self-test" in argv:
        return self_test()

    root = Path(__file__).resolve().parent.parent
    problems = check_tree(root)
    if problems:
        print("发布台账不一致：", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        print(
            "\n版本号是发布切点：改版本要同时改 backend/pom.xml、"
            "frontend/package.json、frontend/package-lock.json，并在 CHANGELOG 写这一节。",
            file=sys.stderr,
        )
        return 1

    print(
        f"发布台账一致：pom / package.json / package-lock = "
        f"{read_pom_version(root)}，CHANGELOG 有对应小节"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
