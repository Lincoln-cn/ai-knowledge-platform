-- ════════════════════════════════════════════════════════════════════════════
-- knowledge 模块：kb_document 的 OCR 进度事实（第 9 周 · 0.3.1）
-- ════════════════════════════════════════════════════════════════════════════
--
-- ★ 为什么需要这三列：扫描件的解析是**分钟级**的（实测：3 页扫描件全量 OCR 约 11 秒，
--   每页约 3.6 秒），而在此之前界面上只有一句"分段中"——用户面对几分钟空白。
--   `12 §11.8` 要求「必须按页回写（"已识别 3/10 页"）」，所以进度必须是**库里的事实**，
--   与 `processed_segments` 同理（刷新页面不重置靠的就是它）。
--
-- ★ 契约只加**两个可选字段**（`ocrTotalPages?` / `ocrDonePages?`），状态机**不加新状态**：
--   在途态用现成的 `SEGMENTING`（分段中）——它本来就是"解析/分块进行中"，
--   而且 `DocumentStatus` 的注释早就写明「等改成异步流水线时它就是在途态，届时不必改状态机」。
--
-- 三列各自是什么（不要混）：
--   ocr_total_pages  需求：这份文档**需要** OCR 的页数（"已识别 N/M 页"的分母）
--                    ★ 由一次**关掉 OCR 的快扫**得出（`pdf:charsPerPage` 每页字符数 < 10 的页数），
--                      耗时约 0.3 秒，与全量解析（数秒~数十秒）不是一个量级；
--                      判据取的是 Tika 自己用的同一份数据 ⇒ 不会与它的口径漂移。
--   ocr_done_pages   进度：**已完成** OCR 的页数（每页处理完回写一次，覆盖写）
--   ocr_page_count   事实：其中**真的走了 OCR** 的页数（来自 Tika 元数据 `pdf:ocrPageCount`）
--                    ★ 它是"这份文档是不是 OCR 产物"的唯一依据 ⇒ 分块器据此走 OCR 规则集
--                      （`12 §11.5`：文本型 L1→L2→L3→L4；OCR 型 L2→L1'→L4）
--
-- ★ 三个默认 0 = "不涉及 OCR"，而不是"未知"：非 PDF 文档与纯文本 PDF 都是 0，
--   界面上据此不显示进度行（不显示一条恒为 0/0 的噪声）。
--
-- ★ 不改 V13：已应用的迁移改动会让 Flyway 校验和失败，只能新增。
-- ════════════════════════════════════════════════════════════════════════════

alter table kb_document
  add column ocr_total_pages integer not null default 0,
  add column ocr_done_pages  integer not null default 0,
  add column ocr_page_count  integer not null default 0;

comment on column kb_document.ocr_total_pages is
  '需要 OCR 的页数（进度分母，12 §11.8）。由关掉 OCR 的快扫（pdf:charsPerPage < 10）得出，非 PDF / 纯文本 PDF 为 0';

comment on column kb_document.ocr_done_pages is
  '已完成 OCR 的页数（进度，覆盖写、不回退）。逐页回写，刷新页面不重置';

comment on column kb_document.ocr_page_count is
  '其中真的走了 OCR 的页数（Tika 元数据 pdf:ocrPageCount）。>0 ⇒ 文档是 OCR 产物，分块器走 OCR 规则集（12 §11.5）';
