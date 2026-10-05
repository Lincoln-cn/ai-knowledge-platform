-- ════════════════════════════════════════════════════════════════════════════
--  首次初始化数据库时装扩展（镜像的 /docker-entrypoint-initdb.d/）
-- ════════════════════════════════════════════════════════════════════════════
--
--  ★ 为什么扩展在这、而分词配置在 Flyway 迁移里（09 §10.2 #3 的两分法）：
--    · `CREATE EXTENSION` **要超级用户** ⇒ 属"实例/镜像"层面，在这做一次；
--    · `CREATE TEXT SEARCH CONFIGURATION` **不要超管** ⇒ 属"库"层面，放迁移，
--      这样生产环境的应用账号不必是超级用户也能把库迁到最新。
--
--  ★ 只跑一次：官方 entrypoint 仅在数据目录为空（首次 initdb）时执行本目录。
--    存量 volume 不会重跑 —— 换镜像后要补跑的话见 deploy/README 的升级说明。
--
--  `vector` 在 pgvector 镜像里本来就有；这里显式写一遍是为了
--  "两个扩展都可用"这件事在同一处可核对（V1 迁移里那句是幂等的，重复无害）。

create extension if not exists vector;
create extension if not exists zhparser;
