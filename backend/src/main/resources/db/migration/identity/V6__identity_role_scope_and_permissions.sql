-- ════════════════════════════════════════════════════════════════════════════
-- 数据范围 + 密级 + 权限点目录（第 3 周 · 0.1.2）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么这一次"提前"把密级加进角色表，而上一次（V3）刻意没加：
--   判断标准是「会不会改查询的形状」。dataScope 与 maxClassification **都要进检索谓词**，
--   且它们最终由**同一个谓词构建器**消费（13 §8.3）—— 分两次做 = 同一个函数被改两遍，
--   而第二次改的人未必知道第一次的约定。
--
--   对照：iam_role 本身只有几行，所以"后加列"这件事在存储上从不是问题；
--   真正的问题是**逻辑**会分两次写。这也正是 08 §4.4 反复强调"数据范围别推"的原因
--   （对 kb_segment 那种几十万行的表，后加还要回填 —— 那里是存储与逻辑同时出问题）。
-- ════════════════════════════════════════════════════════════════════════════

alter table iam_role
  add column data_scope         varchar(16) not null default 'all',
  add column data_scope_rule    varchar(512),
  add column max_classification varchar(16) not null default '公开',
  add column labels             text[];

alter table iam_role
  add constraint chk_role_data_scope check (data_scope in ('own', 'shared', 'all')),
  add constraint chk_role_classification
    check (max_classification in ('公开', '内部', '秘密', '机密'));

comment on column iam_role.data_scope is
  '数据范围三档（07 §3.2）：own 个人沙箱 / shared 自己+被共享 / all 全平台';
comment on column iam_role.data_scope_rule is
  '范围规则的逃生口（07 §8.3）。MVP 只存不解析 —— 真需要表达式时才实现解析器';
comment on column iam_role.max_classification is
  '角色密级上限（clearance）。★ 与 kb_knowledge_base.max_classification（ceiling）正交，不要混';
comment on column iam_role.labels is
  '可涉的密级标签。★ 空集表示不按标签限制（绝大多数角色的状态）';

-- ── 各角色的初始范围与密级 ──
-- dataScope 来自前端 mock fixtures（r1~r4 已按此写死）；CREATOR 由 07 §4.2 的数据面矩阵推出。
-- ★ maxClassification 是**本轮按角色定位推断的初始值**，不是从某处抄来的 ——
--   它必须由业务确认，界面上（角色管理页）可随时改。
update iam_role set data_scope = 'all',    max_classification = '机密' where code = 'ADMIN';
update iam_role set data_scope = 'all',    max_classification = '秘密' where code = 'KB_OPERATOR';
update iam_role set data_scope = 'all',    max_classification = '内部' where code = 'APP_CONFIG';
update iam_role set data_scope = 'own',    max_classification = '内部' where code = 'CREATOR';
update iam_role set data_scope = 'shared', max_classification = '公开' where code = 'USER';

-- ════════════════════════════════════════════════════════════════════════════
--  权限点目录
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 主键用 code 而不是 UUID：它是**代码常量**（08 §4.3.1），永远稳定、天然唯一，
--   而 UUID 只会让"这个权限点是什么"需要多查一次。02 §5.1 的 UUID 主键约定
--   针对的是"由用户创建、id 不承载语义"的聚合根，目录表不属于那一类。
--
-- ★ 这两张表的内容**由启动时的幂等同步维护**（PermissionCatalog），不是由迁移 seed。
--   理由：权限点的真源是**代码**，不是数据库。放在迁移里意味着"新增一个权限点要写一条迁移"，
--   而原项目"新增功能忘配权限 ⇒ 功能隐形"的病正是这么来的。
--   迁移只负责建表。
create table iam_permission
(
  code       varchar(64) not null,
  label      varchar(64) not null,
  module     varchar(32) not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  primary key (code)
);

comment on table iam_permission is
  '权限点目录（只读镜像）。★ 真源是代码里的 Permissions 常量，启动时幂等同步到这里，供角色管理页渲染勾选框';

create table iam_role_permission
(
  role_id         uuid        not null,
  permission_code varchar(64) not null,
  created_at      timestamptz not null,
  primary key (role_id, permission_code),
  constraint fk_role_permission_role foreign key (role_id) references iam_role (id),
  constraint fk_role_permission_code foreign key (permission_code) references iam_permission (code)
);

-- 「这个权限点被哪些角色持有」—— 角色管理页与"能否安全删除权限点"的判断都要用
create index idx_role_permission_code on iam_role_permission (permission_code);
