-- 模板 payload 的形状修正：`config.multiTurn` 必须是布尔，不是对象。
--
-- ★ 现象：从任何内置模板「建应用」都 500 ——
--   `MismatchedInputException: Cannot deserialize value of type `boolean` from Object value`
--   （reference chain: AssistantBundleView["config"] -> Config["multiTurn"]）。
--
-- ★ 根因：`AssistantBundleView.Config.multiTurn` 声明为 `boolean`（导出应用时写的就是库里
--   `as_assistant.multi_turn` 那一列），而 V90 与 V107 两个种子把 payload 写成了
--   `{"enabled": true, "maxRounds": 3}`。种子里那份 JSON 从来没有被自己反序列化过一次，
--   所以一直没人发现 —— V90 从落地起就是错的，V107 照抄了 V90 的形状，
--   于是 11 个内置模板全都建不出应用。
--
-- ★ 为什么不直接改 V90/V107：它们已经在环境里执行过，改文件会让 Flyway 校验和失配、
--   启动直接失败。历史迁移只加不改，修正由这一条补偿完成；新环境先插错、再被这里改对，
--   最终态是一致的。
--
-- ★ 为什么丢掉 `maxRounds` 不掉东西：轮数不在 bundle 的这一层（`multi_turn` 是布尔列），
--   多轮上限在检索/流式配置里另有位置，种子里那行 `maxRounds` 只是当时手写的想象。
--
-- ★ 幂等：只处理 `jsonb_typeof = 'object'` 的行；已经是布尔的（例如将来有人重新导出一份）
--   一个都不会被碰到。

update as_template
set
  payload =
    jsonb_set(
      payload,
      '{config,multiTurn}',
      to_jsonb((payload -> 'config' -> 'multiTurn' ->> 'enabled')::boolean),
      false
    ),
  updated_at = now()
where
  deleted_at is null
  and jsonb_typeof(payload -> 'config' -> 'multiTurn') = 'object';
