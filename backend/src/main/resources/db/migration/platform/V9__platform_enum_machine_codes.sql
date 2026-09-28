-- ════════════════════════════════════════════════════════════════════════════
-- platform 模块：枚举取值改机器码 —— data_scope / max_classification
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么不改 V6（旧迁移）：Flyway 对已执行的迁移做校验和比对，改了旧文件
--   会让所有环境的迁移历史失效（"checksum mismatch"），开发库、测试库、
--   任何已跑过 V6 的库都升不上去。所以旧文件一律不动，改用新增迁移。
--
-- ★ 为什么必须翻译存量数据：开发库里已有 5 个角色（V4 seed）带着旧码
--   （'all'/'own'/'shared'、'公开'/'内部'/'秘密'/'机密'）。若只改约束不改数据，
--   旧码会被新约束拒掉，应用启动即失败。翻译是"改约束"的前置条件。
--
-- ★ 执行顺序：先 drop 旧约束 → 翻译数据 → 再加新约束。
--   反过来（先翻数据）会被旧约束拒掉 —— 旧约束只认旧码，新码进不去。
--
-- ★ 新码口径（14 册 §2.1）：标识符 = 线上值 = 库存值，一律 SCREAMING_SNAKE_CASE。
--   展示文案不进库。
-- ════════════════════════════════════════════════════════════════════════════

-- ── 1. 先 drop 旧约束（否则新码写不进去） ──

alter table iam_role drop constraint chk_role_data_scope;
alter table iam_role drop constraint chk_role_classification;

-- ── 2. 翻译存量数据 ──

update iam_role set data_scope = 'OWN'    where data_scope = 'own';
update iam_role set data_scope = 'SHARED' where data_scope = 'shared';
update iam_role set data_scope = 'ALL'    where data_scope = 'all';

update iam_role set max_classification = 'PUBLIC'       where max_classification = '公开';
update iam_role set max_classification = 'INTERNAL'     where max_classification = '内部';
update iam_role set max_classification = 'SECRET'       where max_classification = '秘密';
update iam_role set max_classification = 'CONFIDENTIAL' where max_classification = '机密';

-- ── 3. 加新约束（只认新码） ──

alter table iam_role
  add constraint chk_role_data_scope check (data_scope in ('OWN', 'SHARED', 'ALL')),
  add constraint chk_role_classification
    check (max_classification in ('PUBLIC', 'INTERNAL', 'SECRET', 'CONFIDENTIAL'));

-- 列默认值也跟着换，否则"插行时省略该列"会落入旧码
alter table iam_role alter column data_scope set default 'ALL';
alter table iam_role alter column max_classification set default 'PUBLIC';

-- ── 4. 更新列注释里的旧口径描述 ──

comment on column iam_role.data_scope is
  '数据范围三档（07 §3.2）：OWN 个人沙箱 / SHARED 自己+被共享 / ALL 全平台';
comment on column iam_role.max_classification is
  '角色密级上限（clearance）。★ 与 kb_knowledge_base.max_classification（ceiling）正交，不要混。取值 PUBLIC/INTERNAL/SECRET/CONFIDENTIAL，声明顺序即密级高低';
