-- ════════════════════════════════════════════════════════════════════════════
-- identity 模块：认证与授权的最小表集（第 2 周 · 0.1.0「认证链 + 双令牌」）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 这里**只**建"认证链跑通"所需的三张表。刻意没建的东西与理由，一并写在这里 ——
--   因为"为什么现在不做"比"做了什么"更容易在两周后被忘记：
--
--   · iam_role.data_scope / data_scope_rule —— 第 3 周（角色与数据范围）才用。
--     不预埋的理由：iam_role 只有几行，届时 ALTER TABLE ADD COLUMN 是瞬时的；
--     而现在建一个没人读的列，只会让"哪些字段真的在用"变模糊。
--
--   ★ 注意这与 13 册里**相反**的那条判断并不矛盾：kb_segment 的密级必须一开始就有，
--     因为那是几十万行的表、后加要回填，而且它进检索谓词。
--     判断标准是「行数 × 是否进查询谓词」，不是"早晚要用"。
--
--   · iam_permission / iam_role_permission —— 权限点是"代码常量 + 启动时幂等同步"
--     （08 §4.3.1），随第 3 周的角色管理一起落库。
--   · 密级（max_classification / labels）—— 13 册把它排在 M3。
-- ════════════════════════════════════════════════════════════════════════════

create table iam_role
(
  id         uuid        not null,
  name       varchar(64) not null,
  code       varchar(32) not null,
  created_at timestamptz not null,
  created_by uuid,
  updated_at timestamptz not null,
  updated_by uuid,
  deleted_at timestamptz,
  primary key (id)
);

-- ★ 角色的 code 全局唯一（不加 deleted_at 条件）：它是"角色 → 权限点"映射的键，
--   代码里按 code 找角色（Permissions.of(roleCode)）。软删一个 code 再复用会让
--   旧数据指向一个语义已变的角色。
create unique index uq_iam_role_code on iam_role (code);

create table iam_user
(
  id                   uuid         not null,
  username             varchar(64)  not null,
  display_name         varchar(128) not null,
  -- BCrypt 输出固定 60 字符，留到 72 是为了将来换算法时不必再改表
  password_hash        varchar(72)  not null,
  role_id              uuid         not null,
  status               varchar(16)  not null,
  -- 契约 LoginResult.mustChangePassword（frontend/src/api/identity.ts:15）
  must_change_password boolean      not null default false,
  last_login_at        timestamptz,
  created_at           timestamptz  not null,
  created_by           uuid,
  updated_at           timestamptz  not null,
  updated_by           uuid,
  deleted_at           timestamptz,
  primary key (id),
  constraint fk_user_role foreign key (role_id) references iam_role (id),
  -- 契约里 status 就是这三个中文字面量，照抄（frontend/src/api/identity.ts:24）
  constraint chk_user_status check (status in ('正常', '停用', '待审核'))
);

-- ★ 用户名唯一用**部分索引**，而不是列上的 unique 约束。
--   写成 unique(username) 的话，"已删除的账号"会永久占用那个用户名 ——
--   删掉 chenjun 之后就再也建不出叫 chenjun 的账号，而错误信息只会说"用户名已存在"。
create unique index uq_iam_user_username on iam_user (username) where deleted_at is null;

create table iam_refresh_token
(
  id          uuid        not null,
  user_id     uuid        not null,
  token_hash  varchar(64) not null,
  expires_at  timestamptz not null,
  revoked_at  timestamptz,
  replaced_by uuid,
  created_at  timestamptz not null,
  primary key (id),
  constraint fk_refresh_user foreign key (user_id) references iam_user (id),
  -- ★ 轮换链：被换掉的那个令牌指向替换它的新令牌。
  --   它存在的意义是"检测重放"：一个已经 replaced 的令牌再次出现，
  --   说明有人拿到了旧令牌（或者令牌被复制过）⇒ 应当撤销整条链，而不只是拒绝这一次。
  constraint fk_refresh_replaced_by foreign key (replaced_by) references iam_refresh_token (id),
  constraint chk_refresh_expiry check (expires_at > created_at)
);

-- ★ 只存 sha256 哈希，不存明文：库被读走时，不等于令牌被拿走。
--   这里用 sha256 而不是 bcrypt 是**有意的**：刷新令牌是 256 位随机值，
--   本身没有"可猜的密码空间"，不需要慢哈希；而每次刷新都要按哈希查一行 ——
--   用 bcrypt 会让这个查询无法走索引（每次都要全表扫一遍重算）。
create unique index uq_iam_refresh_hash on iam_refresh_token (token_hash);

-- 刷新时查"该用户还有效的令牌"，以及"撤销该用户全部令牌"（改密 / 停用时）
create index idx_iam_refresh_user on iam_refresh_token (user_id) where revoked_at is null;

-- 过期令牌的定期清理
create index idx_iam_refresh_expires on iam_refresh_token (expires_at);
