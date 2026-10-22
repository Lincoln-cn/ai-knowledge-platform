-- 本地 ONNX 默认模型换成 `multilingual-e5-small`（中英双语）后，补它的单段字符预算（`#172`）。
--
-- ★ 为什么另起一条迁移而不是改 `V37`：`V37` 已经在本机/CI 的库里应用过，
--   改一条**已应用**的迁移会让 Flyway 校验和不匹配（症状是启动直接失败）。
--
-- ★ 为什么值还是 399：`multilingual-e5-small` 的 `config.json` 里
--   `max_position_embeddings = 512`（与 bge-small-zh 相同）⇒ floor(512 × 0.78) = 399。
--   窗口一样不代表模型一样 —— 但**这一列问的是窗口**，所以两个模型同值。
--
-- ★ 与 `V37` 的关系：那条给 `bge-small-zh-v1.5` 登记了 399（保留，作为可选模型）。
--   两条都留着，因为换模型是**运维决定**，而预算是模型属性。
update ai_model
set max_chars = 399
where base_model = 'multilingual-e5-small'
  and max_chars is null;
