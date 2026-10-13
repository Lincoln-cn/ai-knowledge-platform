-- ════════════════════════════════════════════════════════════════════════════
-- assistant 模块：as_access_credential —— 免登录访问凭证（第 16 周 · 0.4.4）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 契约：frontend/src/api/assistants.ts 的 AccessCredential（id / token / enabled /
--   expiresAt / maxClassification / accessCount / lastAccessAt / revokedAt / createdAt）。
--   字段名与语义逐字对齐那个接口，本文件是它的落库形状。
--
-- ★★ max_classification 是本表存在的**核心理由**（13 §5.2 第 ① 级「创建时冻结」）：
--   它是「这条凭证被允许看到的**最敏感**内容」= **创建者当时的 clearance**，
--   由后端自己取（ScopeFilter.maxClassification），**不接受前端传**。
--   ⇒ 有它，访客每次访问才能「用这个值重新过滤一遍」（13 §5.2 第 ② 级）；
--     没有它，分享链接只能靠「创建时命中过」放行 —— 而 13 §5.2 的「⚠️ 不得」正是这一句。
--   ★ 且它**后加不可追溯**（13 §6.3 第 6 点：已发出的链接没有这份记录，事后补不回来）
--     ⇒ 所以必须在**第一次生成分享**时就在位，这是本迁移不改 V29、另开 V30 的原因。
--
-- ★ 归属：凭证属于某个应用 ⇒ 归属与可见性判定**走那个应用**（platform.access.OwnedResource）。
--   本表**不放** owner_id / visibility：放一份等于允许"凭证的归属与应用不一致"这个状态存在，
--   而那种状态没有人能说清该怎么判。判定入口见 AccessCredentialService.requireVisibleApp。
--
-- ★ 软删口径与 as_assistant 一致（deleted_at）；撤销（revoked_at）是**另一件事**：
--   软删 = 这一行不该再被任何列表看见；撤销 = 这一行是一个**终态事实**，必须被看得见
--   （契约的 revokedAt 会下发，界面上要显示"已撤销"）。
--
-- ★ 枚举取值一律 SCREAMING_SNAKE_CASE（14 册 §2.1）：max_classification 遵守。
-- ════════════════════════════════════════════════════════════════════════════

create table as_access_credential
(
  id                 uuid          not null,
  assistant_id       uuid          not null references as_assistant (id) on delete cascade,
  -- ★★ 免登录令牌的明文。
  --   ⚠️ 这里存的是**可取回的值**，取舍写在下面 comment 与实体的 javadoc 里：
  --   契约的列表接口要返回 token（AccessCredential.token 是必填字段），而 MVP 的界面
  --   「凭证管理」要把链接显示出来给人复制。若将来改成只存哈希，契约的 token 语义必须
  --   变成「仅创建时返回一次」—— 那是一处**契约变更**，不是一次内部重构。
  token              varchar(128)  not null,
  -- 停用（可再启用）与撤销（终态）是两件事，各有各的列
  enabled            boolean       not null default true,
  -- 有效期；null = 长期有效（契约 expiresAt: string | null 的注释）
  expires_at         timestamptz,
  -- ★★ 创建时冻结的密级上限（= 创建者当时的 clearance）。见文件头。
  max_classification varchar(16)   not null,
  -- 计数与最近一次访问：契约 accessCount / lastAccessAt 要下发
  access_count       bigint        not null default 0,
  last_access_at     timestamptz,
  -- ★ 撤销是**终态**：撤销后不可重新启用，只能重新生成（契约 revokeCredential 的注释）
  revoked_at         timestamptz,

  created_at         timestamptz   not null,
  created_by         uuid,
  updated_at         timestamptz   not null,
  updated_by         uuid,
  -- 软删：仅聚合根使用（02 §5.1），与 as_assistant 同口径
  deleted_at         timestamptz,
  -- ★ 乐观锁：本表有一条真实的"读-改-写"竞争路径 —— 访客访问（记 access_count）与
  --   管理员撤销可能同时发生。没有它，一次撤销可能被一次并发访问覆盖掉（撤销丢了，而不报错）
  version            bigint        not null default 0,
  primary key (id),

  constraint chk_as_credential_classification
    check (max_classification in ('PUBLIC', 'INTERNAL', 'SECRET', 'CONFIDENTIAL')),
  -- 令牌不能是空串：空串会在按 token 查找时命中一条"谁都能用"的凭证
  constraint chk_as_credential_token_length check (char_length(token) >= 16),
  -- ★ 撤销终态写成库层约束：撤销过的行不许再是 enabled（领域方法也拦一道，
  --   但库层这道防的是"将来某条新写入路径漏判"—— 与 chk_as_model_by_type 同一条理由）
  constraint chk_as_credential_revoked_is_disabled check (revoked_at is null or enabled = false)
);

-- ★ 按 token 解析是公开面的**唯一**检索路径 ⇒ 它必须唯一且走索引。
--   ★ 不加 deleted_at 条件的部分索引：软删过的凭证若允许 token 复用，
--     一条"已删"的链接会在新凭证出现后**复活**（指向另一条凭证）。
--     永久占用一个 token 串是这里唯一 fail-closed 的选择。
create unique index uq_as_credential_token on as_access_credential (token);

-- 凭证管理页按应用列出（契约 listCredentials）
create index idx_as_credential_assistant on as_access_credential (assistant_id)
  where deleted_at is null;

comment on table as_access_credential is
  '免登录访问凭证。★ 冻结 max_classification = 创建者当时的 clearance（13 §5.2 第①级）';
comment on column as_access_credential.token is
  '★ 存的是可取回的明文：契约的列表接口要返回 token（凭证管理页要让人复制链接）。'
  '若将来改成只存哈希，契约的 token 语义要变成「仅创建时返回一次」—— 那是一处契约变更';
comment on column as_access_credential.max_classification is
  '★★ 创建时冻结的密级上限 = 创建者当时的 clearance。访客每次访问都用它**重新过滤**（13 §5.2 第②级），'
  '且不接受前端传。★ 后加不可追溯（13 §6.3 第 6 点）';
comment on column as_access_credential.revoked_at is
  '撤销时间。★ 撤销是终态：不可重新启用，只能重新生成（契约 revokeCredential 的注释）';
comment on column as_access_credential.expires_at is
  '有效期。null = 长期有效（契约 expiresAt: string | null）';
