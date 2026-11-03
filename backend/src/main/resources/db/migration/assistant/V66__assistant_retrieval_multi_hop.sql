-- ════════════════════════════════════════════════════════════════════════════
-- assistant 模块：应用的「多跳迭代」两列（#283 · 0.4.7）
-- ════════════════════════════════════════════════════════════════════════════
--
-- 与 `knowledge/V65__knowledge_base_retrieval_multi_hop.sql` 同形状、同默认值、同约束。
-- 两个表各一份，而不是只做知识库那一份：应用配置里的检索参数是**应用自己的**覆盖
-- （`ChatPolicyResolver` / `effectiveRetrieval` 那一层按应用取），只给知识库加列会让
-- "应用页没有这个开关、知识库页有" —— 又是同一族的不一致。
--
-- ★ 迁移号必须**全局唯一**（`locations: classpath:db/migration` 是同一个目录树），
--   所以这里接着 knowledge 的 V65 用 V66，不能两个模块都写 V65。
--   （本项目踩过两次：V54 撞 main 的 `knowledge/V54__*`、V29 撞 `assistant/V29__*`。）
-- ════════════════════════════════════════════════════════════════════════════

alter table as_assistant
  -- 是否开启多跳迭代。默认关（理由同 knowledge/V65 的文件头）
  add column retrieval_multi_hop_enabled boolean not null default false,
  -- 多跳轮数上限（含首轮），1..3
  add column retrieval_max_hops smallint not null default 2;

alter table as_assistant
  add constraint chk_as_retrieval_max_hops
    check (retrieval_max_hops >= 1 and retrieval_max_hops <= 3);

comment on column as_assistant.retrieval_multi_hop_enabled is
  '是否开启多跳迭代（#283）。开启后每轮多一次模型调用；默认关';
comment on column as_assistant.retrieval_max_hops is
  '多跳轮数上限（含首轮），1..3；默认 2';
