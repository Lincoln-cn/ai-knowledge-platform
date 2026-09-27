-- ════════════════════════════════════════════════════════════════════════════
-- Modulith 出站事件表（01 §五 能力 4：「事件不丢」）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 这张表的列集<不是>从官方文档抄的，而是让 Hibernate 对着 jar 里的实体生成的
--   （spring-modulith-events-jpa 2.1.1）。为什么必须这么做：
--
--   Modulith 2.1.1 的 JpaEventPublication 比 1.x 多了三列 —— status /
--   completion_attempts / last_resubmission_date。照 1.x 时代的文档建一张六列的表，
--   表现不是"事件功能不好用"，而是**应用启动就直接失败**
--   （Hibernate schema validation: missing column [status]）。
--   生成 DDL 的命令记在 CHANGELOG 0.0.1 里，换 Modulith 版本时照做一遍即可。
--
-- ★ 为什么第 1 周就得有它，而不是等第一个 @ApplicationModuleListener 出现：
--   我们开了 spring.jpa.hibernate.ddl-auto=validate（schema 由 Flyway 独占），
--   而 starter-jpa 已经把 JpaEventPublication 注册进了实体扫描 ——
--   表不存在 = 上下文起不来。也就是说，"先不建表"不是省事，是应用根本跑不起来。
--   摆在面前的是二选一：建表，或者关掉 validate。后者等于把整个 schema 校验护栏拆掉，
--   换来的是"M2 才发现事件表不对"。这里选建表。
-- ════════════════════════════════════════════════════════════════════════════

create table event_publication
(
  id                     uuid        not null,
  listener_id            varchar(255) not null,
  event_type             varchar(255) not null,
  -- ★ 这一列刻意不用 Hibernate 生成的 varchar(255)：
  --   事件是序列化后的 JSON，255 字符几乎必然溢出。而"事件写不进去"在本项目里
  --   等价于事件丢失 —— 恰恰是这张表存在的唯一理由。
  --   text 与 String 的映射是否被 validate 接受，由 MigrationIntegrationTest 实测把关。
  serialized_event       text        not null,
  publication_date       timestamp(6) with time zone not null,
  completion_date        timestamp(6) with time zone,
  last_resubmission_date timestamp(6) with time zone,
  completion_attempts    integer     not null default 0,
  status                 varchar(255) check (status in ('PUBLISHED', 'PROCESSING', 'COMPLETED', 'FAILED', 'RESUBMITTED')),
  primary key (id)
);

-- Modulith 的重投机制查的就是"还没完成的事件"（completion_date is null），
-- 于是用部分索引而不是全列索引：未完成的只是极少数行。
create index event_publication_incomplete_idx
  on event_publication (publication_date)
  where completion_date is null;
