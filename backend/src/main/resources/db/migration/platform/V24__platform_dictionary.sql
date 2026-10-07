-- ════════════════════════════════════════════════════════════════════════════
-- platform：基础数据字典（第 19 周·形状收口）—— 见 `08 §十`
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么要有它：同一性质的东西（**可维护的枚举清单**）今天有四种实现方式 ——
--   密级是后端枚举、文种是**前端常量**、库分类是"从已有库去重统计"、提示词类型只有 mock。
--   没有一处是后端权威的 ⇒ 加一个文种要发版，而"库里存了什么取值"没人说得清。
--
-- ★ `code` 就是**现在数据里那个值**（文种 '制度'、库分类 '制度规范'），不是新造的机器码。
--   理由：`code` 已经写进了 (a) 历史数据 (b) **召回前缀** (c) `content_hash`。
--   换成 ASCII 码 = 全量数据迁移 + **全表重新向量化**（前缀变了 ⇒ 哈希变了）。
--   密级那一维仍然是 SCREAMING_SNAKE（`PUBLIC`…）—— 因为它要在 SQL 里**比较与排序**，
--   这是 14 册"标识符=线上值=库存值"在两种情形下的各自落法，不是不一致。
--
-- ★ 一条硬规则：**字典项不允许删除，只能停用**。
--   `code` 一旦写进历史数据，删掉它会让那些数据的展示回落成机器码（"制度"变成空）
--   而**没有任何地方报错**。停用（`enabled=false`）表达的是"新数据不要再选它"，
--   既有数据照旧可读 —— 这才是我们要的语义。
-- ════════════════════════════════════════════════════════════════════════════

create table platform_dict_item
(
  id          uuid         not null,
  -- 字典种类：CLASSIFICATION / DOC_TYPE / PROMPT_TYPE / KB_CATEGORY
  kind        varchar(32)  not null,
  -- ★ 机器码 = 库存值 = 召回前缀里的那一段（见文件头）
  code        varchar(64)  not null,
  -- 展示文案：可随时改，**不进库、不进前缀**（同 14 册：文案与机器码分离）
  label       varchar(64)  not null,
  sort        integer      not null default 0,
  -- 停用 = 不再出现在新的下拉里；既有数据照旧可读（见文件头）
  enabled     boolean      not null default true,
  remark      varchar(255),
  created_at  timestamptz  not null,
  created_by  uuid,
  updated_at  timestamptz  not null,
  updated_by  uuid,
  primary key (id),
  -- ★ 同一种类内 code 唯一 —— 它才是业务键，"两条一样的文种"是配置事故而不是功能
  constraint uq_dict_kind_code unique (kind, code),
  constraint chk_dict_kind
    check (kind in ('CLASSIFICATION', 'DOC_TYPE', 'PROMPT_TYPE', 'KB_CATEGORY'))
);

-- 按"种类 + 排序 + 启停"取列表（下拉与维护页都是这个形状）
create index idx_dict_kind_sort on platform_dict_item (kind, sort, code);

comment on table platform_dict_item is
  '基础数据字典（08 §十）。★ code 是历史数据/召回前缀里已经用着的值；项只停用不删除';
comment on column platform_dict_item.code is
  '机器码（库存值）。★ 文种/分类/提示词类型这一列就是今天数据里那个中文值，不是新造的 ASCII 码 —— 换成 ASCII 要全表重向量化';
comment on column platform_dict_item.enabled is
  '停用 = 不再出现在新的下拉；既有数据照旧可读。★ 字典项不允许删除（code 已写进历史数据）';

-- ────────────────────────── 种子数据（对齐前端既有取值，不做"顺手改名"） ──────────────────────────
--
-- ★ 逐项照抄现在前端常量/mock 里的**原值**（`labels.ts` 的 DOC_TYPE_SUGGESTIONS、
--   `mock/fixtures/prompts.ts` 的类型、`mock/fixtures/knowledge.ts` 的分类）——
--   照抄的理由与 V20 的默认值同源：两边不一致的症状是"演示时好用、部署后不一样"。

insert into platform_dict_item (id, kind, code, label, sort, enabled, remark, created_at, updated_at)
values
  -- 密级：★ 只改 label / sort / enabled；**增删档位要连 `classification_rank` 生成列一起迁移**（08 §10.3）
  (gen_random_uuid(), 'CLASSIFICATION', 'PUBLIC',       '公开',  1, true, '对外可公开；★ 档位与排序由迁移固定（V21/V22 的生成列）', now(), now()),
  (gen_random_uuid(), 'CLASSIFICATION', 'INTERNAL',     '内部',  2, true, '仅内部使用', now(), now()),
  (gen_random_uuid(), 'CLASSIFICATION', 'SECRET',       '秘密',  3, true, '★ 工程惯例用语，非法定密级（13 §1.2）', now(), now()),
  (gen_random_uuid(), 'CLASSIFICATION', 'CONFIDENTIAL', '机密',  4, true, '★ 工程惯例用语，非法定密级（13 §1.2）', now(), now()),

  -- 文种（契约里叫 doc_type，界面叫「文种 / 文件类型」）
  (gen_random_uuid(), 'DOC_TYPE', '制度',     '制度',     1, true, null, now(), now()),
  (gen_random_uuid(), 'DOC_TYPE', '合同',     '合同',     2, true, null, now(), now()),
  (gen_random_uuid(), 'DOC_TYPE', '报销规定', '报销规定', 3, true, null, now(), now()),
  (gen_random_uuid(), 'DOC_TYPE', '管理办法', '管理办法', 4, true, null, now(), now()),
  (gen_random_uuid(), 'DOC_TYPE', '操作规程', '操作规程', 5, true, null, now(), now()),
  (gen_random_uuid(), 'DOC_TYPE', '通知公告', '通知公告', 6, true, null, now(), now()),
  (gen_random_uuid(), 'DOC_TYPE', '工作报告', '工作报告', 7, true, null, now(), now()),
  (gen_random_uuid(), 'DOC_TYPE', '培训手册', '培训手册', 8, true, null, now(), now()),

  -- 库分类：今天是从已有库的 category 去重统计出来的（不是可维护清单）⇒ 现在有真源了
  (gen_random_uuid(), 'KB_CATEGORY', '制度规范', '制度规范', 1, true, null, now(), now()),
  (gen_random_uuid(), 'KB_CATEGORY', '产品资料', '产品资料', 2, true, null, now(), now()),
  (gen_random_uuid(), 'KB_CATEGORY', '行业标准', '行业标准', 3, true, null, now(), now()),

  -- 提示词类型：界面上已有这个 tab，但只有 mock（后端未落地）⇒ 归到同一个字典里
  (gen_random_uuid(), 'PROMPT_TYPE', '通用',     '通用',     1, true, null, now(), now()),
  (gen_random_uuid(), 'PROMPT_TYPE', '合同法务', '合同法务', 2, true, null, now(), now()),
  (gen_random_uuid(), 'PROMPT_TYPE', '制度咨询', '制度咨询', 3, true, null, now(), now()),
  (gen_random_uuid(), 'PROMPT_TYPE', '写作辅助', '写作辅助', 4, true, null, now(), now());
