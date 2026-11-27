-- 更正 kb_relation 的表注释（V96 里那句「检索链路今天不消费它」已经过时）。
--
-- ★★ 为什么用一条新迁移、而不是回去改 V96：
--   已应用的迁移文件**不可修改** —— Flyway 会校验每条迁移的校验和，改一个字符（哪怕只是注释）
--   就让后端起不来：
--     Migration checksum mismatch for migration version 96
--     -> Applied to database : 2069988056
--     -> Resolved locally    : 1951148238
--   2026-11-27 真机实测（#368 那次我改了 V96 的注释，重启直接 BUILD FAILURE）。
--   要更正事实说明，只能新增一条迁移去改它 —— 历史文件是账本，不是文档。
comment on table kb_relation is
  '实体间的受控关系（四类）。不是知识图谱，但检索链路会消费它：自 2026-11-11 起 RelationRecallPort 把命中实体参与过的关系段并入候选（见 RetrievalService）。';
