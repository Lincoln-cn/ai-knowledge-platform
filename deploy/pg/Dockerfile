# ════════════════════════════════════════════════════════════════════════════
#  PostgreSQL 镜像（pgvector + zhparser）—— ★ 中文全文检索的前提
# ════════════════════════════════════════════════════════════════════════════
#
#  为什么要有这份自制镜像（`09 §10.2 #3` 的落地形态）：
#    · 设计册定的全文检索是 `to_tsvector('chinese_zh', …)`，而 `chinese_zh` 的
#      parser 只能是 **zhparser** —— 用 `simple` 配置会把**整段中文当成一个 token**。
#    · ★ **实测（本机真容器，2026-10-05）：`pgvector/pgvector:pg16` 里
#      `pg_available_extensions` 有 `pg_trgm`(1.6) 与 `vector`(0.8.6)，
#      **没有 `zhparser`、也没有 `pg_bigm`**；PGDG / Debian 也没有现成包
#      ⇒ **必须自己编译** ⇒ 必须自制镜像。这不是可选项。
#
#  ★★ pgvector 兼容怎么保证的（本镜像的第一条硬要求）：
#    两段式，**builder 与 final 用的是同一个 base 镜像**（`pgvector/pgvector:pg16`）
#    ⇒ final 阶段里的 `vector.so` / `vector.control` / 已装好的 `vector` 扩展
#      是 base **原封不动的那一份**，我们只往里加 zhparser，**不碰 pgvector 一个字节**。
#    ★ 这也是**不能**照抄 zhparser 官方 Dockerfile 的原因：它 final 用官方
#      `postgres:16-bookworm`，而我们 base 是**带 pgvector 的 Debian 包版 PG**
#      （`pg_config --pkglibdir = /usr/lib/postgresql/16/lib`、`--sharedir =
#      /usr/share/postgresql/16`）。跨 base 复制 .so 既要改一堆路径，又多了
#      glibc 与 PG 构建方式不一致的风险 —— 而"换成官方 postgres 镜像"会直接把
#      pgvector 丢掉（`docker-compose.yml` 里那条注释警告的就是这件事）。
#
#  构建（在仓库根执行，构建上下文就是仓库根）：
#     docker build -f deploy/pg/Dockerfile -t ai-knowledge-platform-pg:dev .
#
#  自检（本文件存在的意义 —— 一条命令证明两件事都成立）：
#     python deploy/verify_image_pg.py
#     → pgvector：建表/插入/HNSW/余弦排序全通
#     → zhparser：`to_tsvector('chinese_zh','合同违约金')` = '合同' + '违约金' 两个词
#       （整串原样一个 token = 没生效，与 `simple` 配置同症状）
#
#  ★ 体积（实测）：本镜像曾因"先装后卸"跨 RUN 而到 **1.98 GB** —— 层不会因为
#    后面卸掉而变小（卸掉的只是白障）。改成两段式后 pgvector base 的负载不变、
#    编译期依赖全留在 builder 层 ⇒ final 只剩运行需要的东西。
# ════════════════════════════════════════════════════════════════════════════

# ────────────────────────────────────────────────────────────────────────────
#  阶段 1 · builder（编译期依赖只活在这一层）
# ────────────────────────────────────────────────────────────────────────────
FROM pgvector/pgvector:pg16 AS builder

# ★ 两处源码都**钉版本**（不跟 master 漂）：词典与切分规则会随版本变，
#   换版本等于换检索行为 ⇒ 要换就得是一次有意识的、能对着读数比较的动作。
#   ★ 改这里之后必须重跑 `deploy/verify_image_pg.py`。
ARG SCWS_REF=1.2.3
ARG ZHPARSER_REF=master
ARG DEBIAN_FRONTEND=noninteractive

#   build-essential    : gcc/make
#   postgresql-server-dev-${PG_MAJOR} : PGXS（PG_MAJOR 由 base 镜像提供，实测 =16）
#   pkg-config         : zhparser 的 Makefile 用它发现 scws
#   automake/libtool/m4: **scws 1.2.3 的仓库里没有 configure**，要自己 autoreconf
#                        （zhparser 官方 Dockerfile 那串 `touch README;aclocal;autoconf;…` 就是干这个）
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      build-essential \
      ca-certificates \
      git \
      pkg-config \
      automake \
      libtool \
      m4 \
      "postgresql-server-dev-${PG_MAJOR}" \
 && rm -rf /var/lib/apt/lists/*

# ── SCWS（Simple Chinese Word Segmentation）—— 真正做分词的内核 ──
#   ★ zhparser 只负责把 SCWS 的切分结果翻译成 PG 的 tsvector。
#   ★ `ldconfig`：让运行时加载器找得到 /usr/local/lib/libscws.so
#     （Debian 默认已把 /usr/local/lib 收在 ld.so 配置里，这一步是显式化的保险）
RUN set -eux; \
    git clone --branch "${SCWS_REF}" --single-branch --depth 1 \
      https://github.com/hightman/scws.git /tmp/scws; \
    cd /tmp/scws; \
    touch README; \
    aclocal; autoconf; autoheader; libtoolize; automake --add-missing; \
    ./configure; \
    make -j"$(nproc)"; \
    make install; \
    ldconfig

# ── zhparser ──
#   `make install` 会按 **base 镜像自己的 pg_config** 落到：
#     · zhparser.so                    → $(pg_config --pkglibdir) = /usr/lib/postgresql/16/lib
#     · zhparser--*.sql / .control     → $(pg_config --sharedir)/extension/
#     · dict.utf8.xdb / rules.utf8.ini → $(pg_config --sharedir)/tsearch_data/
#   ★ 第三条最容易漏：词典不进 tsearch_data 时 `CREATE TEXT SEARCH CONFIGURATION`
#     能建成功，但分词结果是**空**（或缺词）—— 又是一种"看起来做了、实际没生效"。
RUN set -eux; \
    git clone --branch "${ZHPARSER_REF}" --single-branch --depth 1 \
      https://github.com/amutu/zhparser.git /tmp/zhparser; \
    cd /tmp/zhparser; \
    make -j"$(nproc)"; \
    make install

# ────────────────────────────────────────────────────────────────────────────
#  阶段 2 · final（★ 同一个 base ⇒ pgvector 原封不动）
# ────────────────────────────────────────────────────────────────────────────
FROM pgvector/pgvector:pg16

# 交付镜像里不该有编译器与 git（内网镜像常要过安全扫描）
# ⇒ 一句 `docker run --rm <img> which gcc git` 应当是空，这也是自检的一项。

# ── 只搬运行需要的东西（路径按 base 的 pg_config，与 builder 逐字一致）──
COPY --from=builder /usr/lib/postgresql/16/lib/zhparser.so /usr/lib/postgresql/16/lib/
COPY --from=builder /usr/share/postgresql/16/extension/zhparser* /usr/share/postgresql/16/extension/
COPY --from=builder /usr/share/postgresql/16/tsearch_data/dict.utf8.xdb /usr/share/postgresql/16/tsearch_data/
COPY --from=builder /usr/share/postgresql/16/tsearch_data/rules.utf8.ini /usr/share/postgresql/16/tsearch_data/
COPY --from=builder /usr/local/lib/libscws.so* /usr/local/lib/

# ★ libscws.so 是新搬进来的 ⇒ 必须刷新动态库缓存，否则
#   `CREATE TEXT SEARCH CONFIGURATION … (PARSER = zhparser)` 会报找不到符号
#   （症状是"扩展装了、配置建不了"，指向性很差）。
RUN ldconfig

# ── initdb 阶段装扩展（★ 不是放在 Flyway 迁移里）──
#   理由（`09 §10.2 #3` 第 1 条）：`CREATE EXTENSION` 需要**超级用户**，
#   而生产环境的应用账号不该是超级用户 —— 所以扩展属"镜像/实例"层面，
#   分词**配置**属"库"层面（配置本身不需要超管 ⇒ 放迁移，见 `12 §4.2`）。
#   ★ 只在**首次初始化数据库**时执行；已存在的 volume 不会被重跑（这一点必须知道，
#     否则会以为"换了镜像就有了"——存量库的补跑见 deploy/README 的升级说明）。
COPY deploy/pg/initdb/10-extensions.sql /docker-entrypoint-initdb.d/10-extensions.sql
