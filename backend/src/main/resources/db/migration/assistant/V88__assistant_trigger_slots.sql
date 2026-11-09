-- ★ 触发器「留缝」（#295 的一半）。
--
-- 这一版的**唯一目的**是让"应用可以被定时 / 事件触发"这件事在数据模型上有个位置，
-- 而不是添一个真调度器。为什么现在做：
--   1. 触发方式决定了"谁有权启动一轮问答"——这是**数据模型**问题，晚定就要改已经写好的
--      应用配置路径与导入导出包；
--   2. 应用导出包（批次 3 的 schemaVersion）一旦发出去，再加列就要做兼容分支。
--
-- 为什么**只加列、不加调度器**：
--   `ROADMAP §11.7.1` 的准入判据是「§二 清单里有它的位置」或「现在不做会返工」。
--   触发器属于后者（形状要现在定），而**实现**（cron 解析、并发去重、错过补跑、
--   失败重试、审计）是 V1.0 的活。
--
-- ★ 当前状态：`trigger_type` / `trigger_cron` 两列**没有任何写入方，也没有任何读取方**。
--   钉住这一点的是 `backend/src/test/java/cn/lincoln/aiplatform/assistant/TriggerSlotTest.java`
--   里那条 `noSchedulerConsumesTheTriggerSlots`——它扫全部主代码找 @Scheduled / @EnableScheduling，
--   找到就红。★ 留缝最危险的失败形状，是半年后有人把它读成"已经支持定时触发了"。

ALTER TABLE as_assistant
    ADD COLUMN IF NOT EXISTS trigger_type varchar(16),
    ADD COLUMN IF NOT EXISTS trigger_cron varchar(64);

COMMENT ON COLUMN as_assistant.trigger_type IS
    '触发方式：null = 只能由人点（当前唯一形态）· CRON = 定时 · EVENT = 事件驱动（两者均为 V1.0 预留，今日无写入方与读取方）';
COMMENT ON COLUMN as_assistant.trigger_cron IS
    'CRON 触发的时区化 cron 表达式；trigger_type 不是 CRON 时必须为 null（V1.0 预留）';
