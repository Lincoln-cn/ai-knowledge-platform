-- ════════════════════════════════════════════════════════════════════════════
-- 演示账号的部门归属（`07 §10.4` 身份消费位③）+ 一棵**有三级**的演示树
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么必需（这不是"补一条演示数据"，而是让 `T301` 后半那条链**在真栈上有材料**）：
--   `iam_user.org_id` 在 V25 建了列，但**没有任何账号被归类** ⇒ `AccessScopeAdapter` 解析出的
--   祖先链恒为空集 ⇒ "共享给部门（含下级）"这条判定**在真实环境里一次都不会生效**
--   （单测全绿、产品上没人用 —— 正是 Issue #38 与项目头号病的形状）。
--
-- ★ 树要有**三级**（总部 > 技术部 > 研发组）：V25 的树是两层的，而"含下级"这条口径在两层的树上
--   **验不出来** —— 共享给"总部"等于谁都看得见，共享给"技术部"只看它自己，两者都不需要祖先链。
--   三级树才有"共享给技术部 ⇒ 研发组的人看得见"这个读数（`07 §10.5` 判据 1）。
--
-- ★ 账号归属是**为判据服务的**（不是随手分的）：
--     liwei    → 研发组（技术部的**下级**）★ 判据 1 的关键账号：共享给技术部时他必须看得见
--     zhangyun → 技术部  （共享目标本身）
--     chenjun  → 市场部  （**兄弟部门**）★ 对照组：共享给技术部时他必须看不见
--     admin    → 总部    （DataScope=ALL，归属不影响它看得见什么；归到根是为了不留下"没归类"的演示账号，
--                          否则"有没有生效"这件事在真栈上又要靠猜）
--
-- ⚠️ 演示数据，与 V4 的演示口令同一条免责：生产部署前要清掉。
-- ════════════════════════════════════════════════════════════════════════════

-- 研发组挂在技术部下。★ 技术部的 id 是 V25 用 gen_random_uuid() 生成的 ⇒ 这里只能按 code 找它，
-- 而**新节点自己用固定 UUID**：真栈闸门要能直接引用它（`00000000-0000-4000-8000-000000000009`），
-- 不必先查一次库 —— 与 V25 给 ROOT 固定 UUID 是同一条理由。
insert into iam_org (id, parent_id, code, name, path, sort, enabled, created_at, updated_at)
select '00000000-0000-4000-8000-000000000009',
       tech.id,
       'TECH_RD',
       '研发组',
       tech.path || 'TECH_RD/',
       1,
       true,
       now(),
       now()
  from iam_org tech
 where tech.code = 'TECH';

-- 账号归属：按 username 找账号、按 code 找部门（两边都不用写死 uuid）
update iam_user u
   set org_id = o.id,
       updated_at = now()
  from iam_org o
 where o.code = 'TECH_RD'
   and u.username = 'liwei'
   and u.deleted_at is null;

update iam_user u
   set org_id = o.id,
       updated_at = now()
  from iam_org o
 where o.code = 'TECH'
   and u.username = 'zhangyun'
   and u.deleted_at is null;

update iam_user u
   set org_id = o.id,
       updated_at = now()
  from iam_org o
 where o.code = 'MARKETING'
   and u.username = 'chenjun'
   and u.deleted_at is null;

update iam_user u
   set org_id = o.id,
       updated_at = now()
  from iam_org o
 where o.code = 'ROOT'
   and u.username = 'admin'
   and u.deleted_at is null;
