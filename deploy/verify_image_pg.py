#!/usr/bin/env python
"""镜像内自检：PostgreSQL 镜像（pgvector + zhparser）—— 两件事必须同时成立。

★★ 为什么这个脚本值钱：`pgvector` 与 `zhparser` 各自"装上"都不难，难的是
**两个同时可用**，而且**症状都是静默的**：
  · pgvector 缺了 ⇒ 迁移里 `CREATE EXTENSION vector` 会报错（还算吵）
  · zhparser 缺了 ⇒ 全文检索**一条都搜不到**，而向量路照常 ⇒ 混合模式静默退化成纯向量
    （`11 §风险` 第 3 条写的就是这件事）
  · 词典没进 `tsearch_data` ⇒ 扩展装了、配置也建了，但**分词结果为空**

★ 一条踩过的坑（写本脚本时当场踩的）：`psql` 一次传多个 `-c` 时，**非查询语句的
  命令标签（`CREATE TABLE` / `INSERT 0 1` …）也会进 stdout** ⇒ 与查询结果混在一起，
  断言直接失败。所以下面把 DDL（多句、只看退出码）与查询（单句、读 stdout）**分开调用**。

用法（在仓库根）：
    python deploy/verify_image_pg.py            # 用默认 tag
    python deploy/verify_image_pg.py <tag>      # 指定 tag

退出码即结论（本项目闸门纪律）：全过 0，任一条红 1。
"""

from __future__ import annotations

import subprocess
import sys
import time
import uuid

IMAGE = sys.argv[1] if len(sys.argv) > 1 else "ai-knowledge-platform-pg:dev"
DB_USER = "aiplatform"
DB_PASS = "probe-only-not-a-secret"
DB_NAME = "aiplatform"

PASSED: list[str] = []
FAILED: list[str] = []


def ok(msg: str) -> None:
    PASSED.append(msg)
    print(f"PASS  {msg}")


def bad(msg: str) -> None:
    FAILED.append(msg)
    print(f"FAIL  {msg}")


def check(condition: bool, msg: str) -> bool:
    ok(msg) if condition else bad(msg)
    return condition


def run(args: list[str], timeout: int = 120) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout, encoding="utf-8", errors="replace")


def psql_ddl(container: str, *sql: str) -> tuple[int, str]:
    """执行若干条 DDL/DML —— 只看退出码，不读 stdout（命令标签会混进来）。"""
    args = ["docker", "exec", container, "psql", "-U", DB_USER, "-d", DB_NAME, "-tA"]
    for stmt in sql:
        args += ["-c", stmt]
    proc = run(args)
    return proc.returncode, ((proc.stderr or "") or (proc.stdout or "")).strip()


def psql_one(container: str, sql: str) -> str:
    """单条查询 —— 输出的第一行就是答案。"""
    proc = run(["docker", "exec", container, "psql", "-U", DB_USER, "-d", DB_NAME, "-tA", "-c", sql])
    return (proc.stdout or "").strip()


def image_size(container_tag: str) -> str:
    """★ 用 `docker images` 的口径（人看到的那个）。

    实测：`docker image inspect .Size` 报的是**压缩口径**（base 镜像报 156MB，
    而 `docker images` 报 621MB）⇒ 两个数不一样，写进报告时必须说明用的是哪个。
    """
    proc = run(["docker", "images", "--filter", f"reference={container_tag}", "--format", "{{.Size}}"])
    return (proc.stdout or "").strip().splitlines()[0] if proc.stdout.strip() else "?"


def main() -> int:
    print(f"=== 镜像自检：{IMAGE} ===")

    # ────────── 0. 镜像在不在 ──────────
    proc = run(["docker", "image", "inspect", IMAGE, "--format", "{{.Id}}"])
    if proc.returncode != 0:
        bad(f"镜像不存在：{IMAGE}（先 docker build -f deploy/pg/Dockerfile -t {IMAGE} .）")
        return 1
    ok(f"镜像在：{IMAGE}（docker images 口径 {image_size(IMAGE)}）")

    container = f"aip-pg-verify-{uuid.uuid4().hex[:8]}"
    try:
        # ────────── 1. 起一个一次性库 ──────────
        run(["docker", "rm", "-f", container])
        proc = run([
            "docker", "run", "-d", "--name", container,
            "-e", f"POSTGRES_PASSWORD={DB_PASS}",
            "-e", f"POSTGRES_USER={DB_USER}",
            "-e", f"POSTGRES_DB={DB_NAME}",
            IMAGE,
        ])
        if proc.returncode != 0:
            bad(f"容器起不来：{(proc.stderr or '').strip()[:200]}")
            return 1

        ready = False
        for _ in range(60):
            out = run(["docker", "exec", container, "pg_isready", "-U", DB_USER, "-d", DB_NAME]).stdout or ""
            if "accepting connections" in out:
                ready = True
                break
            time.sleep(2)
        if not check(ready, "数据库起得来（pg_isready）"):
            return 1

        # ────────── 2. initdb 装的两个扩展都在 ──────────
        installed = set(psql_one(container, "select extname from pg_extension order by extname;").split())
        check("vector" in installed, "扩展 vector 已装（pgvector 随 base 原样保留）")
        check("zhparser" in installed, "扩展 zhparser 已装（initdb 脚本生效）")

        # ────────── 3. pgvector 真功能（不是"装上了"就算）──────────
        #   造两条互相正交的 768 维向量：对自己的距离必须是 0，对另一条是 1。
        #   ★ 迁移 V16 建的就是这张表的形状（vector(768) + hnsw/cosine），所以这条同时
        #     证明了"迁移能在本镜像里跑通"与"pgvector 真能算"。
        rc, err = psql_ddl(
            container,
            "create table vec_probe (id int primary key, embedding vector(768));",
            "insert into vec_probe select 1, (select array_agg(case when i=1 then 1.0 else 0.0 end) from generate_series(1,768) i)::vector;",
            "insert into vec_probe select 2, (select array_agg(case when i=2 then 1.0 else 0.0 end) from generate_series(1,768) i)::vector;",
            "create index on vec_probe using hnsw (embedding vector_cosine_ops) with (m=16, ef_construction=64);",
        )
        if not check(rc == 0, f"pgvector DDL 可执行（vector(768) + HNSW 索引）{'' if rc == 0 else '：' + err[:160]}"):
            pass
        else:
            out = psql_one(
                container,
                "select id || ':' || round((embedding <=> (select embedding from vec_probe where id=2))::numeric,4) "
                "from vec_probe order by embedding <=> (select embedding from vec_probe where id=2);",
            )
            check(
                out.splitlines() == ["2:0.0000", "1:1.0000"],
                f"pgvector 余弦排序正确（{out.replace(chr(10), ' ')}）",
            )

        # ────────── 4. chinese_zh 分词配置（★ 照 02 §重建字段 的两条 DDL）──────────
        rc, err = psql_ddl(
            container,
            "create text search configuration chinese_zh (parser = zhparser);",
            "alter text search configuration chinese_zh add mapping for n,v,a,i,e,l,x with simple;",
        )
        check(rc == 0, f"能建 chinese_zh 配置并加 mapping{'' if rc == 0 else '：' + err[:160]}")

        out = psql_one(container, "select to_tsvector('chinese_zh','合同违约金');")
        # ★ 精确断言，不用"去空白后包含"（那种写法在**打散/未分词**的产物上也成立 ——
        #   本项目在 OCR 那轮已经栽过一次，教训写在 OcrParserTest 的类注释里）。
        check(out == "'合同':1 '违约金':2", f"zhparser 真分词：合同违约金 → {out!r}")

        simple_out = psql_one(container, "select to_tsvector('simple','合同违约金');")
        check(
            simple_out == "'合同违约金':1",
            f"对照：simple 配置整串一个 token → {simple_out!r}（这就是必须装 zhparser 的理由）",
        )

        # ────────── 5. ★ 把已知上限钉住（反向断言）──────────
        #   SCWS 把「元」标成量词(q)、「每人/每天」标成代词(r)，而 mapping 只放行
        #   n,v,a,i,e,l,x ⇒ 它们不进 tsvector。查询侧同样进不去 ⇒ 对称、不影响命中；
        #   但**单位与频次不参与匹配** ⇒ `12 §835` 已警告"500 元/天 与 500 元/次 会混"。
        #   ★ 若哪天有人放宽 mapping，这条会变红 —— 那正是要回来重新评估的信号。
        unit_out = psql_one(container, "select to_tsvector('chinese_zh','500 元');")
        check(unit_out == "'500':1", f"已知上限（金丝雀）：量词「元」当前不进 token → {unit_out!r}")

        # ────────── 6. 交付镜像里不该有编译器/构建工具 ──────────
        proc = run(["docker", "exec", container, "sh", "-c", "command -v gcc git make autoconf || true"])
        check(proc.stdout.strip() == "", "交付镜像里没有 gcc/git/make/autoconf（编译期依赖留在 builder 层）")

    finally:
        run(["docker", "rm", "-f", container])

    print("\n" + "=" * 60)
    print(f"通过 {len(PASSED)}/{len(PASSED) + len(FAILED)}")
    if FAILED:
        print("★ 失败：")
        for f in FAILED:
            print(f"   - {f}")
        return 1
    print("镜像自检全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
