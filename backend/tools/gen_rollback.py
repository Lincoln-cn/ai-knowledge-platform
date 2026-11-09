#!/usr/bin/env python3
"""生成 Flyway 迁移的逆向（回滚）脚本草稿。

## 为什么是"草稿"

回滚的正确性取决于**数据当时是什么状态**，而静态分析只看得到 DDL。
例如 `alter table t drop column c` 的逆向是 `add column c type`，但**值回不来** ——
这一点写在每个语句的 `-- ! 数据不可恢复` 注释里，需要人读。

因此本工具的定位是「**把机械部分做对，把危险部分标出来**」，不是"一键回滚"。

## 支持的正向语句（生成对应逆向）

    create table t (...)                  → drop table if exists t
    create index i on t (...)             → drop index if exists i
    create unique index i on t (...)      → drop index if exists i
    alter table t add column c ...        → alter table t drop column if exists c
    alter table t drop column c           → alter table t add column c <原文类型>   ← 值不可恢复
    alter table t rename column a to b    → alter table t rename column b to a
    alter table t add constraint n ...    → alter table t drop constraint if exists n
    comment on ... is '...'               → 无（注释不影响数据）

## 不支持的语句 —— 遇到就**报错退出**，不静默跳过

    create view / materialized view · create function / trigger / extension
    insert / update / delete（数据迁移，逆向取决于业务语义）
    drop table / drop column（正向就是破坏性的，它自己的回滚无意义）
    alter column type / set default / set not null（类型收窄可能失败，要人判）

★ 判据：**"没报错"不等于"全都回滚得了"** —— 不认识的语句必须让整个生成失败，
  否则最危险的那条会被淹没在一片绿里（本项目在 `#322` 和反证脚本上都栽过这个形状）。

## 用法

    python backend/tools/gen_rollback.py --out .build/rollback
    python backend/tools/gen_rollback.py --only assistant/V89 --out .build/rollback
    python backend/tools/gen_rollback.py --self-check     # 跑夹具，验八种语句与三种拒绝

输出文件名：`U<version>__<description>.sql`（Flyway undo 的命名习惯），
内容按**逆向顺序**排列（一个文件里多条 DDL 时，后面的先撤）。
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MIGRATION_DIR = ROOT / "backend" / "src" / "main" / "resources" / "db" / "migration"

#: 一条迁移文件里各个语句的切分点：行首的关键字（不区分大小写）。
STATEMENT_START = re.compile(
    r"^\s*(create|alter|insert|update|delete|drop|comment|grant|revoke|do)\b",
    re.IGNORECASE,
)

#: 完全不产生逆向语句的（对数据与结构都无影响）。
NOOP_PREFIXES = ("comment",)


class Unsupported(Exception):
    """遇到本工具不敢自动回滚的语句。"""


@dataclass(frozen=True)
class Statement:
    text: str
    line: int


def split_statements(sql: str) -> list[Statement]:
    """按行首关键字把 SQL 切成语句。

    ★ 不做完整的 SQL 词法分析 —— 本仓的迁移是手写 DDL，没有存储过程体内嵌分号
      （遇到 `$$` 会直接报错，见 `generate`），所以按行切足够。
    """
    statements: list[Statement] = []
    buf: list[str] = []
    start_line = 0
    for lineno, line in enumerate(sql.splitlines(), start=1):
        stripped = line.strip()
        if stripped.startswith("--") or not stripped:
            # 注释与空行跟着上一条语句走，丢弃即可（回滚脚本里不需要正向注释）
            continue
        if STATEMENT_START.match(line):
            if buf:
                statements.append(Statement(" ".join(buf).strip(), start_line))
            buf = []
            start_line = lineno
        buf.append(stripped)
    if buf:
        statements.append(Statement(" ".join(buf).strip(), start_line))
    return statements


def _one(pattern: str, text: str, what: str) -> re.Match[str]:
    match = re.match(pattern, text, re.IGNORECASE | re.DOTALL)
    if not match:
        raise Unsupported(f"看不懂 {what}：{text[:90]}")
    return match


def invert(text: str) -> list[str]:
    """把一条正向语句翻成逆向语句。返回空列表表示"无需回滚"。"""
    low = text.lower()

    if low.startswith(NOOP_PREFIXES):
        return []

    if low.startswith("create table"):
        m = _one(r"create\s+table\s+(?:if\s+not\s+exists\s+)?([\w.\"]+)", text, "create table")
        return [f"drop table if exists {m.group(1)};"]

    if low.startswith("create unique index") or low.startswith("create index"):
        m = _one(r"create\s+(?:unique\s+)?index\s+(?:if\s+not\s+exists\s+)?([\w.\"]+)", text, "create index")
        return [f"drop index if exists {m.group(1)};"]

    if low.startswith("alter table"):
        m = _one(r"alter\s+table\s+(?:if\s+exists\s+)?([\w.\"]+)\s+(.*)", text, "alter table")
        table, body = m.group(1), m.group(2).strip()

        add_col = re.match(r"add\s+column\s+(?:if\s+not\s+exists\s+)?([\w\"]+)", body, re.IGNORECASE)
        if add_col:
            return [f"alter table {table} drop column if exists {add_col.group(1)};"]

        drop_col = re.match(r"drop\s+column\s+(?:if\s+exists\s+)?([\w\"]+)(?:\s+(.*))?$", body, re.IGNORECASE | re.DOTALL)
        if drop_col:
            name = drop_col.group(1)
            col_type = (drop_col.group(2) or "").strip().rstrip(";")
            if not col_type:
                raise Unsupported(
                    f"drop column 没写类型，逆向需要它：{text[:90]} —— 请手工补 add column 的类型"
                )
            return [
                f"-- ! 数据不可恢复：下列语句只恢复列，值回不来",
                f"alter table {table} add column {name} {col_type};",
            ]

        rename = re.match(r"rename\s+column\s+([\w\"]+)\s+to\s+([\w\"]+)", body, re.IGNORECASE)
        if rename:
            return [f"alter table {table} rename column {rename.group(2)} to {rename.group(1)};"]

        add_con = re.match(r"add\s+constraint\s+([\w\"]+)", body, re.IGNORECASE)
        if add_con:
            return [f"alter table {table} drop constraint if exists {add_con.group(1)};"]

        raise Unsupported(f"alter table 的这一种我还不认识：{text[:90]}")

    # 剩下的全部拒绝：drop table / insert / update / delete / create view / create function …
    raise Unsupported(f"不支持回滚的语句类型：{text[:90]}")


@dataclass(frozen=True)
class Result:
    source: Path
    statements: int
    undo: list[str]


def generate(sql: str, source: Path) -> Result:
    statements = split_statements(sql)
    if "$$" in sql:
        # 存储过程体内有分号，按行切会切坏 —— 与其生成错的，不如不做
        raise Unsupported(f"{source.name} 含 `$$`（可能是函数体），按行切语句不适用")
    # 逆向顺序要按**语句组**倒，不能把整份列表反转 —— 否则 `invert` 返回的
    # 多行组（例如 "注释 + alter column"）内部顺序也被倒过来，注释跑到语句后面，
    # 读起来像在解释下一句。这条被自检的 "drop column 带类型" 用例抓到。
    groups = [invert(stmt.text) for stmt in statements]
    undo = [line for group in reversed(groups) for line in group]
    return Result(source, len(statements), undo)


# --------------------------------------------------------------------------- 自检


FIXTURES: list[tuple[str, str, list[str] | None]] = [
    (
        "create table",
        "create table as_demo (id uuid primary key, name text not null);",
        ["drop table if exists as_demo;"],
    ),
    (
        "create index",
        "create index ix_demo_name on as_demo (name);",
        ["drop index if exists ix_demo_name;"],
    ),
    (
        "create unique index",
        "create unique index ux_demo_name on as_demo (name) where deleted_at is null;",
        ["drop index if exists ux_demo_name;"],
    ),
    (
        "add column",
        "alter table as_demo add column note varchar(64);",
        ["alter table as_demo drop column if exists note;"],
    ),
    (
        "rename column",
        "alter table as_demo rename column note to remark;",
        ["alter table as_demo rename column remark to note;"],
    ),
    (
        "add constraint",
        "alter table as_demo add constraint ck_demo_note check (note <> '');",
        ["alter table as_demo drop constraint if exists ck_demo_note;"],
    ),
    (
        "drop column 带类型",
        "alter table as_demo drop column note varchar(64);",
        [
            "-- ! 数据不可恢复：下列语句只恢复列，值回不来",
            "alter table as_demo add column note varchar(64);",
        ],
    ),
    ("comment 不产生逆向", "comment on table as_demo is '演示';", []),
    # 以下三种必须拒绝
    ("拒绝 insert", "insert into as_demo (id) values (gen_random_uuid());", None),
    ("拒绝 create view", "create view v_demo as select * from as_demo;", None),
    ("拒绝 drop column 无类型", "alter table as_demo drop column note;", None),
]


def self_check() -> int:
    failures = 0
    for name, sql, expected in FIXTURES:
        try:
            got = generate(sql, Path("fixture.sql")).undo
        except Unsupported as exc:
            if expected is not None:
                print(f"FAIL {name}: 不该拒绝却拒绝了 —— {exc}")
                failures += 1
            else:
                print(f"ok   {name}: 按预期拒绝")
            continue
        if expected is None:
            print(f"FAIL {name}: 该拒绝却生成了 {got}")
            failures += 1
        elif got != expected:
            print(f"FAIL {name}: 期望 {expected} 实得 {got}")
            failures += 1
        else:
            print(f"ok   {name}")
    print(f"\n自检 {len(FIXTURES) - failures}/{len(FIXTURES)}")
    return 1 if failures else 0


# --------------------------------------------------------------------------- main


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="生成 Flyway 迁移的回滚脚本草稿")
    parser.add_argument("--out", type=Path, help="输出目录")
    parser.add_argument("--only", help="只处理版本前缀，例如 assistant/V89")
    parser.add_argument("--self-check", action="store_true", help="只跑夹具自检")
    args = parser.parse_args(argv)

    if args.self_check:
        return self_check()
    if not args.out:
        parser.error("需要 --out（或 --self-check）")

    files = sorted(MIGRATION_DIR.rglob("V*.sql"))
    if args.only:
        files = [f for f in files if args.only.replace("\\", "/") in f.as_posix()]
    if not files:
        print(f"没有匹配的迁移文件（{MIGRATION_DIR}）", file=sys.stderr)
        return 1

    args.out.mkdir(parents=True, exist_ok=True)
    ok = 0
    refused: list[tuple[Path, str]] = []

    for path in files:
        rel = path.relative_to(MIGRATION_DIR)
        try:
            result = generate(path.read_text(encoding="utf-8"), path)
        except Unsupported as exc:
            refused.append((rel, str(exc)))
            continue
        version, _, desc = rel.name.partition("__")
        dest = args.out / f"U{version[1:]}__{desc}"
        header = (
            f"-- 由 backend/tools/gen_rollback.py 生成，**请人读一遍再执行**\n"
            f"-- 正向：{rel.as_posix()}\n"
            f"-- 语句数：{result.statements}\n\n"
        )
        dest.write_text(header + "\n".join(result.undo) + "\n", encoding="utf-8", newline="\n")
        ok += 1

    print(f"生成 {ok} 个 / 共 {len(files)} 个迁移")
    if refused:
        print(f"\n拒绝 {len(refused)} 个（不认识就不猜）：", file=sys.stderr)
        for rel, why in refused:
            print(f"  {rel.as_posix()}: {why}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
