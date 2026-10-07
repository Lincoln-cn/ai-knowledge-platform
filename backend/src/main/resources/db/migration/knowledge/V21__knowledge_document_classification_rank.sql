-- ════════════════════════════════════════════════════════════════════════════
-- knowledge 模块：kb_document.classification_rank —— 密级的排序口径（第 11 周 · 0.3.3）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么需要它：密级是 varchar（'PUBLIC' / 'INTERNAL' / 'SECRET' / 'CONFIDENTIAL'），
--   而检索谓词要的是**数值比较**（「不高于我的 clearance」）。没有这一列，就得在
--   **每一条** SQL 里各写一遍 CASE —— 检索有两条路（向量 / 全文）加可见性判定，共三处，
--   而"两路同谓词"是 `13 §三` 硬要求（只给一路加条件就是"看着能过滤"的静默漏洞）。
--   ⇒ 排序映射下沉到库层，谓词就成了一个 `<=` 比较。
--
-- ★ 生成了两列（本迁移 + `V22` 的 `kb_segment.classification_rank`），口径**逐字相同**：
--   谓词跑在 segment 那一边（`V22`：`content_tsv` 与 `kb_id` 都在它上面，两条路都在那里过滤），
--   文档这一边是它的一致性对照物（也是将来"文档列表按 clearance 过滤"的落点）。
--   ★ 两列的 CASE 是**刻意重复**的：PG 的生成列不允许子查询，一列无法由另一列算出。
--   口径同步不靠"记得改两处"，靠 `ClassificationRankTest` —— 它逐个值断言
--   「库里的 rank == Java 的 `ClassificationLevel.ordinal()`」，任一边漂了都会红。
--
-- ★ `else 99`（而不是 `else 0`）：真出现没预料的取值时，**它对所有人都不可见**（fail-closed）。
--   表上已有 check 约束保证只有这四个值，所以这一支不可达 —— 但"不可达时也要偏安全"是免费的。
--
-- ★ 这里不加索引：密级只有 4 档（低基数），检索谓词用的是 segment 那一列
--   （`V22` 上建了 `(kb_id, classification_rank)`），文档这一列目前只被对照/巡检读。
-- ════════════════════════════════════════════════════════════════════════════

alter table kb_document
  add column classification_rank smallint
    generated always as (
      case classification
        when 'PUBLIC' then 0
        when 'INTERNAL' then 1
        when 'SECRET' then 2
        when 'CONFIDENTIAL' then 3
        else 99
      end) stored;

comment on column kb_document.classification_rank is
  '密级排序（0=PUBLIC … 3=CONFIDENTIAL，与 Java ClassificationLevel.ordinal() 逐位一致）。★ 检索的两条路都靠它做同一个谓词（13 §三 硬要求）；未知取值落 99 = 对所有人都不可见（fail-closed）';
