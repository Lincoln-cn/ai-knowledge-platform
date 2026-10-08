-- ════════════════════════════════════════════════════════════════════════════
-- identity：组织机构树（第 19 周 · 形状收口）—— 见 `07 §十`
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么现在就要落形状：今天"责任部门"是 `kb_document.department` 上的一个**自由字符串**
--   （前端只给建议值），而它同时 (a) 进召回前缀 (b) 进 `content_hash` (c) **没法做数据权限** ——
--   字符串比不出"我的部门 + 下级"，也没法当共享主体。⇒ 部门必须实体化（`07 §10.1`）。
--
-- ★ `path` 用**物化路径**而不是递归 CTE：数据权限要在**每一条检索 SQL** 里生效（`13 §三` 的 pre-filter），
--   而"我的部门及下级"在物化路径下就是一次**字符串前缀比较**（planner 用得上索引）。
--   代价是移动部门要重写子树路径（一次 update），而移动是低频操作。
--
-- ★ `code` 稳定、`name` 可改：`code` 进共享主体（grant 的 `subject_id`）与审计，改名不改码。
--   ★ 但要提醒：`name` 会进文档的召回前缀与 `content_hash`（`12 §4.2`）——
--   **改部门名会让该部门下所有文档"前缀变了 ⇒ 需要重新向量化"**，界面上必须提示。
--
-- ★ 与 `DataScope` 的关系：本版**不给它加"部门档"**（那三档是账号级的，加档要同时动令牌声明、
--   仓储查询、前端 scopeForms）。"共享给部门"用**现成的 grant 机制**表达（`SubjectKind.ORG` 早已预留）。
--   真出现"新文档自动对本部门可见"这种**非显式共享**的需求时再加档，且只需改 `ScopeFilter` 一处。
-- ════════════════════════════════════════════════════════════════════════════

create table iam_org
(
  id         uuid         not null,
  -- 自引用；根节点的父为 null
  parent_id  uuid         references iam_org (id),
  -- ★ 稳定机器码：进共享主体（kb_knowledge_base_grant.subject_id）与审计，改名不改码
  code       varchar(64)  not null,
  -- 展示名。★ 会进文档召回前缀 ⇒ 改名要提示"该部门下的文档需要重新向量化"
  name       varchar(64)  not null,
  -- ★ 物化路径（形如 /root/procurement/）："含下级"= path like :prefix || '%'
  path       varchar(512) not null,
  sort       integer      not null default 0,
  -- 停用：不再产生新的共享；★ 既有共享**不**因此变成不可见（停用 ≠ 撤销）
  enabled    boolean      not null default true,
  created_at timestamptz  not null,
  created_by uuid,
  updated_at timestamptz  not null,
  updated_by uuid,
  primary key (id),
  constraint uq_iam_org_code unique (code)
);

-- "我的部门及下级"就是一次前缀比较 ⇒ 这个索引是数据权限那条谓词的支撑
create index idx_iam_org_path on iam_org (path);
create index idx_iam_org_parent on iam_org (parent_id);

comment on table iam_org is
  '组织机构树（07 §十）。★ path 是物化路径，让"含下级"变成一次前缀比较；★ code 稳定、name 可改（改名要重算文档前缀）';
comment on column iam_org.path is
  '物化路径（/root/…/，以 / 结尾）。数据权限的"含下级"= path like :prefix || ''%''（见 ScopeFilter 的 ORG 分支）';
comment on column iam_org.enabled is
  '停用 = 不再产生新的共享；★ 既有共享不因此失效（停用 ≠ 撤销，07 §10.5 判据 3）';

-- 用户的部门归属（可空：还没来得及归类的账号）
alter table iam_user
  add column org_id uuid references iam_org (id);

comment on column iam_user.org_id is
  '用户所属部门。★ 一人一部门（MVP）：多部门归属会让"我的部门及下级"变成集合运算，而共享主体本身已经是集合语义 —— 真要一人多部门应加 iam_user_org 表，不是把这一列改成数组（07 §10.4）';

create index idx_iam_user_org on iam_user (org_id);

-- ────────────────────────── 种子：一棵能用的树 ──────────────────────────
--
-- ★ 逐项照抄前端 `DEPARTMENT_SUGGESTIONS` 的**原值**（`constants/labels.ts`）：
--   理由与 `V20`/`V24` 的默认值同源 —— 两边不一致的症状是"演示时好用、部署后不一样"。
-- ★ 这些名字**同时是** `kb_document.department` 里已经存在的取值（开发库实测），
--   所以下一步（把 department 换成 org_id）能按名字回填上，不需要人工映射表。

insert into iam_org (id, parent_id, code, name, path, sort, enabled, created_at, updated_at)
values ('00000000-0000-4000-8000-000000000001', null, 'ROOT', '总部',
        '/ROOT/', 0, true, now(), now());

insert into iam_org (id, parent_id, code, name, path, sort, enabled, created_at, updated_at)
select gen_random_uuid(), '00000000-0000-4000-8000-000000000001', d.code, d.name,
       '/ROOT/' || d.code || '/', d.sort, true, now(), now()
  from (values
        ('PURCHASING', '采购部', 1),
        ('FINANCE',    '财务部', 2),
        ('LEGAL',      '法务部', 3),
        ('HR',         '人事部', 4),
        ('TECH',       '技术部', 5),
        ('ADMIN',      '行政部', 6),
        ('MARKETING',  '市场部', 7),
        ('PRODUCTION', '生产部', 8)
       ) as d(code, name, sort);
