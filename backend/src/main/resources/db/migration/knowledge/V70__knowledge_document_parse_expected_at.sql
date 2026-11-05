-- #304 「待解析」卡死：加一列「期望解析开始的时刻」，让回收器能分辨"正常等待"与"任务没起来"
--
-- 背景（#304）：onUploaded 是事件监听器，拿到数据库连接才能提交解析任务。池满时异常抛在
--   代理外层（事务没开起来）⇒ 状态没改、解析任务从未创建、失败记录也无从落 —— 文档永远停在
--   PENDING_PARSE（界面"解析中"无限转圈）。#303 的回收器只扫 SEGMENTING（"任务已创建但
--   中途死掉"），扫不到"任务从未存在"这一类；而 created_at 对"正常等待"与"任务没起来"是同一个值，
--   DB 层面区分不了（#304 原文），所以要这一列。
--
-- 列的三类语义（本列的全部约定，与 Document#parseExpectedAt 注释同源）：
--   ① 有字节、期望解析 —— 建档时解析事件已投出，那一刻写入本列；超期仍停在 PENDING_PARSE
--      且无在跑任务 = 解析任务从未起来 ⇒ 由 StuckPendingParseReaper 判成可重试的失败
--      （原因文案「解析任务未能启动」，与 #303 的「处理任务已中断」区分）。
--   ② 只登记元数据（无字节）—— 本列为 NULL ⇒ 永远停在 PENDING_PARSE 是正常的（等字节），
--      回收器必须跳过（查询的 is not null 条件钉住这条）。
--   ③ 已开始解析 —— 状态已离开 PENDING_PARSE ⇒ 由 #303 的「分段中」那套按 updated_at 停摆判，与本列无关。
alter table kb_document add column parse_expected_at timestamptz;

comment on column kb_document.parse_expected_at is
  '#304：期望解析开始的时刻。三类语义 —— ① 非空 = 有字节、期望解析，超期仍停在 PENDING_PARSE
   且无在跑任务即「解析任务从未起来」，由 StuckPendingParseReaper 判成可重试的失败；
   ② NULL = 只登记元数据、没有字节，永远停在 PENDING_PARSE 是正常的，回收器必须跳过；
   ③ 状态已离开 PENDING_PARSE 的（解析中/分段失败等）由 #303 的 StuckSegmentingReaper 按
   updated_at 停摆管，与本列无关。判超期用本列而不是 created_at：建档时刻分不开「正常等待」与「任务没起来」';
