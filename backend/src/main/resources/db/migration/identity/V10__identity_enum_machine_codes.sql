-- ════════════════════════════════════════════════════════════════════════════
-- identity 模块：枚举取值改机器码 —— iam_user.status
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么不改 V3（旧迁移）：Flyway 对已执行的迁移做校验和比对，改了旧文件
--   会让所有环境的迁移历史失效（"checksum mismatch"）。旧文件一律不动，改用新增迁移。
--   与 V7 的口径一致。
--
-- ★ 为什么必须翻译存量数据：开发库里已有 4 个演示账号（V4 seed）带着旧码
--   （'正常'）。若只改约束不改数据，旧码会被新约束拒掉，登录/用户管理直接炸。
--
-- ★ 执行顺序：先 drop 旧约束 → 翻译数据 → 再加新约束。
--
-- ★ 新码口径（14 册 §2.1）：UserStatus 三态 ACTIVE / DISABLED / PENDING_REVIEW，
--   标识符 = 线上值 = 库存值。展示文案（"正常/停用/待审核"）只在前端 label 映射里。
-- ════════════════════════════════════════════════════════════════════════════

-- ── 1. 先 drop 旧约束 ──

alter table iam_user drop constraint chk_user_status;

-- ── 2. 翻译存量数据 ──

update iam_user set status = 'ACTIVE'         where status = '正常';
update iam_user set status = 'DISABLED'       where status = '停用';
update iam_user set status = 'PENDING_REVIEW' where status = '待审核';

-- ── 3. 加新约束 ──

alter table iam_user
  add constraint chk_user_status check (status in ('ACTIVE', 'DISABLED', 'PENDING_REVIEW'));

comment on column iam_user.status is
  '用户状态：ACTIVE / DISABLED / PENDING_REVIEW（展示文案见前端 label 映射，不进库）';
