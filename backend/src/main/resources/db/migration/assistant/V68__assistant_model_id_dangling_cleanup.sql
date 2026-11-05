-- ════════════════════════════════════════════════════════════════════════════
-- assistant 模块：清掉历史悬空 as_assistant.model_id（#301 第三件）
-- ════════════════════════════════════════════════════════════════════════════
--
-- 背景：#300 之前的「直接删模型」行为在历史数据上已经发生过 30 次 ——
--   as_assistant.model_id 指向已不存在的 ai_model 行（命名形态：探针应用-* /
--   probe-role-* / B4B5 延迟闸门-* / A3 失败闸门-* / S3 整链闸门-* / B7稳定性-*）。
--   危害不是"功能坏"，是**读数会骗人**：任何按「有 model_id 的应用数」统计的口径
--   都会把这 30 行算进去；#300 的引用计数弹框也会把它们算成"有引用"。
--
-- ★ 为什么必须先放宽约束：V29 的 chk_as_model_by_type 写死
--   （type='SIMPLE' and model_id is not null）—— 直接 set model_id = null 会被它挡下
--   （实测 30 行全是 SIMPLE）。放宽后 SIMPLE 的 model_id 可空，语义 =「待重新绑定模型」。
--   ★ 放宽的理由：本表 model_id 从来没有外键，"指向不存在的 id"（悬空）比 null 更坏 ——
--   原约束防得住 null、防不住悬空，而修复动作恰恰只能落成 null。
--   WORKFLOW 必须为 null 这一半**不动**（模型在各节点里，语义没变）。
--   ★ 后端写路径不受影响：Assistant.requireTypeModelPair 仍要求新建/编辑的 SIMPLE 带模型 ——
--   库层放宽只为放行这批历史行，"待重新绑定"不是合法的用户输入。
--
-- ★ schema 没有「需要重新绑定」标记列（as_assistant 无 remark / status 列）⇒ 只置空：
--   这些应用会以「取不到模型」的形态在界面报错（详情 / 提问时提示缺模型），需人工重绑。
--   ★ 不删这些应用（用户明令）；不动别的表。
--
-- ★ 为什么落迁移而不是运维脚本（二选一）：约束改动必须走迁移（schema 变更），
--   数据置空与它同批才能保证「任意环境执行完本迁移后口径一致」；
--   运维脚本要人跑，会留下"约束已放宽、数据没清"的半态。
--
-- ★ 可重复执行：update 只打悬空行（再跑 = 0 行）；约束重建用 drop if exists + add ——
--   第二遍先删掉上一遍加的再重建，同样成立。
--   迁移号接在 V67 之后（全局唯一；`locations: classpath:db/migration` 是同一目录树）。
-- ════════════════════════════════════════════════════════════════════════════

-- ① 放宽 chk_as_model_by_type：SIMPLE 允许 model_id 为空（= 待重新绑定），WORKFLOW 仍必须为空
alter table as_assistant drop constraint if exists chk_as_model_by_type;
alter table as_assistant
  add constraint chk_as_model_by_type check (
    (type = 'SIMPLE')
      or (type = 'WORKFLOW' and model_id is null)
    );

-- ② 置空悬空 model_id（#301 取证时为 30 行：全部 SIMPLE、多数已软删）
update as_assistant a
   set model_id = null
 where a.model_id is not null
   and not exists (select 1 from ai_model m where m.id = a.model_id);

-- ③ 口径随约束一起改：comment 不改，下一个人读到的还是「简单应用必填」
comment on column as_assistant.model_id is
  '对话模型。★ WORKFLOW 必须为空（模型在各节点里，见 chk_as_model_by_type）；SIMPLE 为空 = 待重新绑定（#301 清悬空后允许，写路径仍拦）';
