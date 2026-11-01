-- ════════════════════════════════════════════════════════════════════════════
-- #73 判据 ④：配额与用量的「缝」—— 只建列，不接线（2026-10）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 这一批**故意不实现任何拦截**。列在这里的理由只有一条：
--   这两张表的形状是"归属资源"（kb_knowledge_base 与 ai_model 都有自己的 owner/创建者），
--   配额与用量天然挂在它们身上；等到真要做「资源与成本上限」时再补列，
--   就要对**已经在跑的数据**做一次带默认值的迁移 + 回填口径裁决。
--   现在加是零成本（表还是小的、语义还没被消费者写死），以后加是返工。
--
-- ★ 语义（三列都不给"魔法默认值"）：
--   * quota 列为 NULL = **不限**。★ 不许用 0 表示不限 —— 0 的自然读法是"一个都不许"，
--     而"零额度"恰恰是真实存在的配置（例如给试用账号）。
--   * used 列 not null default 0 —— "已经用了多少"必须有一个可读的数，
--     即使现在没有任何代码在写它（它今天的值就是 0，含义是"还没开始记账"）。
--
-- ★ 判据（`#73`）：一条断言钉住"当前行为是不拦截"。
--   → backend/src/test/java/cn/lincoln/aiplatform/platform/infrastructure/metrics/QuotaSeamTest.java
--   它扫主代码，断言这三列**一个都没有被读取**。等到哪天有人真的接线了，
--   那条断言会红 —— 那一刻必须连同本文档与 `08 §4.10.3` 一起改，
--   而不是让"留缝"悄悄变成"已实现"（本项目头号病：留着口子被读成已经做了）。
-- ════════════════════════════════════════════════════════════════════════════

-- 知识库：存储配额（字节）+ 已用（字节）
alter table kb_knowledge_base
  add column storage_quota_bytes bigint,
  add column storage_used_bytes  bigint not null default 0;

comment on column kb_knowledge_base.storage_quota_bytes is
  '#73：NULL = 不限；0 = 零额度（合法配置，不是"未设置"）。当前无代码读取';
comment on column kb_knowledge_base.storage_used_bytes is
  '#73：已用字节。当前无代码写入（今天恒为 0 的含义是"还没开始记账"）';

-- 模型：每日调用配额（次）+ 今日已用（次）
--
-- ★ 为什么给模型而不是给用户：成本发生在上游调用，而调用总是"用某个模型"发的；
--   按用户计量要等身份面（多租户）定型，按模型计量今天就能对上账。
alter table ai_model
  add column daily_call_quota int,
  add column calls_today      integer not null default 0;

comment on column ai_model.daily_call_quota is
  '#73：NULL = 不限；当前无代码读取（判据 ④ 的断言钉住这一点）';
comment on column ai_model.calls_today is
  '#73：当日调用次数。当前无代码写入；真做计数时要定"按哪个时区日切"，故先不写';
