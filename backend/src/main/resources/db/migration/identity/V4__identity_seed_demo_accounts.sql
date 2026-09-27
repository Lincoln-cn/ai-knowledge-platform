-- ════════════════════════════════════════════════════════════════════════════
-- 开发期演示数据：5 个角色 + 4 个演示账号
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么 seed 放在 Flyway 迁移里，而不是"启动时用 ApplicationRunner 插数据"：
--   集成测试需要能登录，而它希望拿到的库状态是**确定的**。迁移天然"只跑一次、顺序固定"；
--   Runner 则要自己写幂等逻辑，执行时机还容易和测试的事务边界打架。
--
-- ★ 账号与前端 mock **逐字段对齐**（frontend/src/mock/fixtures/identity.ts:88-133）：
--   admin/admin · zhangyun|liwei|chenjun/123456，displayName 与角色名照抄。
--   这样前端把 VITE_USE_MOCK 关掉时，登录与用户信息的表现不变。
--
-- ★ 密码哈希是 BCryptPasswordEncoder **预先算好的定值**（BCrypt 每次加盐结果都不同，
--   所以这里必须是算好的，不能等应用启动再算）：
--     admin  → $2a$10$inYAIFSCXz5SAX5CxMxV1OUS8Iokn4SNc3.0oGiRo.kR9Xgtw0TY6
--     123456 → $2a$10$qZwl561617CrWmMlht8Lm.EInZTpyTT8pNji99uVGRKTfV7PM.uvS
--   重新生成的方法记在 CHANGELOG 0.1.0 里。
--
-- ⚠️ 这是**开发期演示口令**：生产部署前必须删掉这些账号，或改掉它们的密码。
-- ════════════════════════════════════════════════════════════════════════════

-- id 用可读的固定 UUID：seed 之间互相引用方便，集成测试也能直接带着某个角色的令牌
-- 去断言，不必先查一遍库。
insert into iam_role (id, name, code, created_at, updated_at)
values ('00000000-0000-0000-0000-000000000001', '管理员', 'ADMIN', now(), now()),
       ('00000000-0000-0000-0000-000000000002', '知识运营', 'KB_OPERATOR', now(), now()),
       ('00000000-0000-0000-0000-000000000003', '应用配置', 'APP_CONFIG', now(), now()),
       -- ★ CREATOR（个人建设者）在 07 册的功能矩阵里有，但前端 mock 目前没有对应账号。
       --   这里**照实建出这个角色**，而不是为了"四个角色刚好整齐"而假装它不存在。
       ('00000000-0000-0000-0000-000000000004', '个人建设者', 'CREATOR', now(), now()),
       ('00000000-0000-0000-0000-000000000005', '普通用户', 'USER', now(), now());

insert into iam_user (id, username, display_name, password_hash, role_id, status,
                      must_change_password, created_at, updated_at)
values ('10000000-0000-0000-0000-000000000001', 'admin', '管理员',
        '$2a$10$inYAIFSCXz5SAX5CxMxV1OUS8Iokn4SNc3.0oGiRo.kR9Xgtw0TY6',
        '00000000-0000-0000-0000-000000000001', '正常', false, now(), now()),
       ('10000000-0000-0000-0000-000000000002', 'zhangyun', '张云',
        '$2a$10$qZwl561617CrWmMlht8Lm.EInZTpyTT8pNji99uVGRKTfV7PM.uvS',
        '00000000-0000-0000-0000-000000000002', '正常', false, now(), now()),
       ('10000000-0000-0000-0000-000000000003', 'liwei', '李伟',
        '$2a$10$qZwl561617CrWmMlht8Lm.EInZTpyTT8pNji99uVGRKTfV7PM.uvS',
        '00000000-0000-0000-0000-000000000003', '正常', false, now(), now()),
       ('10000000-0000-0000-0000-000000000004', 'chenjun', '陈军',
        '$2a$10$qZwl561617CrWmMlht8Lm.EInZTpyTT8pNji99uVGRKTfV7PM.uvS',
        '00000000-0000-0000-0000-000000000005', '正常', false, now(), now());
