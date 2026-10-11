-- ════════════════════════════════════════════════════════════════════════════
-- knowledge：kb_document.department（自由字符串）→ org_id（外键）—— 见 `07 §11.2`（T300）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么必须换：今天「责任部门」是这一列上的一个**自由字符串**（前端只给建议值），
--   而它同时被三处消费，每一处都因此是错的（issue #37）：
--     ① 进召回前缀（`SearchText.prefix` 的「文种·部门·标题｜章节路径」）
--     ② 进 `content_hash`（`SEG-I7`：前缀组成变 ⇒ hash 必须变）
--     ③ **没法做数据权限** —— 字符串比不出"我的部门 + 下级"，也不能当共享主体
--   ⇒ 根因只有一句：**它把「身份」与「表达」混成了一个字段** —— 可变、可重名、可自由输入的
--     展示名被当成了身份。
--
-- ★★ 「为什么名字不能当身份」—— 三条实证，每条都**不报错**，只是结果错：
--   1. **改名 ⇒ 该部门下所有文档的向量与哈希全部失效**：而改名是一次纯展示性改动，
--      界面既不会提示、也不会有任何报错（issue #37 的危害 1）。
--   2. **重名 ⇒ 静默越权**：两个都叫「技术部」的部门，在所有**按名字比较**的地方会被混成一个
--      ⇒ A 部门看到 B 部门的文档，**且不报错**。`V25` 因此加了兄弟间 partial unique index。
--   3. **回填/判定一旦按名字走，就会随展示名漂移** —— 所以本迁移的回填是**一次性**的，
--      且匹配不上的**不猜**（原值留在存档列里，由巡检列出）。
--   ⇒ 结论：前缀与 `content_hash` 里只放**稳定 `code`**（全局唯一、不可改）；人读的部门名改在
--     **生成链路渲染时**由 `org_id` 查出来（与密级/文种走字典渲染是同一套模式）。
--     代码侧的对应改动：`SearchText.prefix/contentHash` 的第二个参数（`T300`）。
--
-- ★ 三列的形状与理由：
--   · `department_legacy`：原值**一个都不丢**（它既是回填依据，也是"未匹配清单"的原始证据）。
--     ★ 必须**可空**：新文档没有"旧的自由字符串" —— 留着 NOT NULL 会让建档直接插不进去
--       （症状是"新文档一律写不进库"），而 null 正好表达"它不是从自由字符串迁来的"。
--   · `org_id`：**可空**（存量回填不上时置空，由巡检列出）—— 但**新建档必须给**：
--     应用层强制（`Document` 的构造器校验 + `DocumentService.register` 的存在性校验）。
--     库层不设 NOT NULL 只是因为**存量数据先于这一列存在**，不代表新数据允许为空。
--   · 索引 `idx_kb_document_org`：按部门过滤/巡检要走索引（`07 §8.5`：判定必须能下推成 SQL 谓词，
--     不许"取全量再在内存里筛"）。与 `idx_doc_kb` 同口径取 partial（列表只查未删的）。
--
-- ★ 回填按 `name` 匹配（一次性），**且只认"全库唯一"的名字**：`iam_org` 允许**跨父重名**
--   （两个分公司各自有个「行政部」是合理的组织形态）⇒ 一个名字匹配到多行时**不许任选一行** ——
--   那正是"猜"，而症状是文档被静默划到另一个部门的归属下。这类行留在 `department_legacy` 里等巡检。
-- ════════════════════════════════════════════════════════════════════════════

-- 1 · 原值存档（数据一个不丢）。改名不动数据，只换名字与可空性
alter table kb_document rename column department to department_legacy;
alter table kb_document alter column department_legacy drop not null;

-- 2 · 归属改成外键（指向组织树）
alter table kb_document add column org_id uuid references iam_org (id);

-- 3 · 回填：按 name 匹配 → org_id。★ 只认全库唯一的名字（重名不猜、不任选一行）
update kb_document d
   set org_id = o.id
  from iam_org o
 where o.name = d.department_legacy
   and d.org_id is null
   and (select count(*) from iam_org dup where dup.name = o.name) = 1;

create index idx_kb_document_org on kb_document (org_id) where deleted_at is null;

-- 4 · 三处注释：为什么名字不能当身份（本迁移的全部理由都写在这里）
comment on table kb_document is
  '文档聚合根。建档必填三项（文种/责任部门/密级）+ ceiling 校验（13 §6）；责任部门自 V27 起是 org_id 外键（07 §11.2）—— ★ 身份与表达分开：身份是 org_id/code（不可改），部门名只是表达（可改、可重名）';

comment on column kb_document.org_id is
  '责任部门（组织树外键，07 §11.2）。★ 这是**归属**，不是可见性：文档能不能被看见由所属知识库的共享决定（org_id 不进检索谓词）。★ 前缀与 content_hash 里放的是该部门的稳定 code（不是 name：改名会连坐、重名会静默越权；也不是 path：移动部门会连坐整棵子树）';

comment on column kb_document.department_legacy is
  'V27 之前的自由字符串原值（存档列，只读）。★ 存在的理由：回填按 name 匹配，匹配不上的（自造部门/错字/重名）**不许猜、不许静默丢** —— 原值留在这里、org_id 置空，由巡检列出。新文档此列为 null（它不是从自由字符串迁来的）';
