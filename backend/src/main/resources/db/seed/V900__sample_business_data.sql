-- ════════════════════════════════════════════════════════════════════════════
-- 业务示例数据（发布台账里唯一还欠的一行：`ROADMAP` §10 第 7 行 / 工程行「示例数据种子」）
-- ════════════════════════════════════════════════════════════════════════════
--
-- 为什么这份 seed **不在** `db/migration/**` 的默认路径里：
--   它灌的是**业务数据**（库 / 文档 / 分段 / 应用 / 提示词），而集成测试与它共用同一套迁移。
--   若默认执行，`KnowledgeBaseManagementTest`「第一页 size=2」、`AssistantPublishCheckTest`
--   「page.list() 两条」这类**按条数断言**的用例会红 —— 那不是产品坏了，而是"库里有别人的数据"
--   被断言成了"库里没有别人的数据"。
--   ⇒ 与 `V4__identity_seed_demo_accounts.sql` 头注释的口径不冲突：那份 seed 说明「seed 应该走
--     Flyway 而不是启动时 Runner」（只跑一次、顺序固定、不与测试事务边界打架），本文件同样走
--     Flyway；区别只在**位置**（`db/seed` 而非 `db/migration`）与**开关**（`app.seed.sample-data=true`
--     才把本目录加进 Flyway 的 locations，见
--     `cn.lincoln.aiplatform.platform.seed.SampleDataFlywayConfiguration`）。
--
-- ★ 打开的方式（三选一，都是显式动作）：
--     `SEED_SAMPLE_DATA=true`（application.yml 的占位符）
--     `--app.seed.sample-data=true`（命令行）
--     IDE 里加 VM 参数 `-Dapp.seed.sample-data=true`
--   关着的时候 Flyway 连这个目录都不看 ⇒ 默认部署与全部测试的行为逐字节不变。
--
-- ★ 数据形状的两条取舍：
--   1. **两个模型都是 `NOT_LOADED`**：示例数据不替用户决定"接哪一个推理服务"。库、文档、分段、
--      应用装配全都建好，只差把 `api_base` 指过去再点「加载」—— 这正是新用户第一次打开系统要
--      走的那条路，也避免 seed 里出现任何密钥或内网地址。
--   2. **文档停在 `PENDING_EMBEDDING`（processed_segments = 0）**：向量化是用户的显式动作
--      （`EmbeddingRunner` 只能由 `POST /documents/{id}/embedding` 触发）。示例数据替用户"跑完"
--      会让界面看不到「待向量化 → 向量化中 → 已就绪」这条状态机，而它是最容易出静默 bug 的一段。
--
-- ★ uuid 全取可读的固定值（`5ee1…` 模型 / `5ee2…` 库 / `5ee3…` 文档 / `5ee4…` 分段 /
--   `5ee5…` 提示词类型 / `5ee6…` 提示词 / `5ee7…` 应用），做法与 `V4`/`V25` 一致。
--   前缀刻意避开 `0000…`/`1000…`（V4 的角色与账号）与 `00000000-0000-4000-8000-…`（组织），
--   免得"示例数据"与"身份基础数据"在 uuid 上看起来是同一族。
--
-- ★ 计数列（`document_count` / `segment_count` / `char_count` / `char_length`）**不手写死值**：
--   全部由末尾的 UPDATE 从真实行算回来。手写常量会在改正文时静默过期，而列表页读的就是这几列。
-- ════════════════════════════════════════════════════════════════════════════

-- ── 0 · 两个模型占位（不加载、无密钥）────────────────────────────────────────
insert into ai_model (id, name, kind, supplier, base_model, api_base, status, enabled,
                      context_window, dimension, max_chars, thinking, tools, builtin, remark,
                      created_at, updated_at)
values ('5ee10000-0000-4000-8000-000000000001', '示例对话模型（待配置）', 'CHAT',
        'LOCAL_INFERENCE', 'qwen2.5:7b', 'http://localhost:11434/v1', 'NOT_LOADED', false,
        8192, null, null, 'NONE', 'UNSUPPORTED', false,
        '示例数据。把 api-base 指到你的推理服务后点「加载」即可用；它不携带任何密钥。',
        now(), now()),
       ('5ee10000-0000-4000-8000-000000000002', '示例向量模型 bge-m3（待配置）', 'EMBEDDING',
        'LOCAL_INFERENCE', 'bge-m3', 'http://localhost:11434/v1', 'NOT_LOADED', false,
        null, 1024, 400, 'NONE', 'UNSUPPORTED', false,
        '示例数据。向量模型必须先加载才允许向量化；单段字符预算按 400 字登记（#172）。',
        now(), now());

-- ── 1 · 三个知识库（三种密级上限，正好覆盖可见性判定的三档）─────────────────
insert into kb_knowledge_base (id, name, category, description, icon, import_type,
                               embedding_model_id, max_classification, enabled, owner_id,
                               visibility, document_count, segment_count, char_count,
                               has_vectorized_content, created_at, updated_at)
values ('5ee20000-0000-4000-8000-000000000001', '制度规范示例库', '制度规范',
        '示例数据：差旅、报销、值班等内部制度的片段，用来演示建库 → 上传 → 分段 → 检索。',
        '📘', 'LOCAL', '5ee10000-0000-4000-8000-000000000002', 'SECRET', true,
        '10000000-0000-0000-0000-000000000001', 'PRIVATE', 0, 0, 0, false, now(), now()),
       ('5ee20000-0000-4000-8000-000000000002', '产品手册示例库', '产品资料',
        '示例数据：产品功能说明的片段，用来演示「应用只绑一个库」时的检索边界。',
        '📗', 'LOCAL', '5ee10000-0000-4000-8000-000000000002', 'INTERNAL', true,
        '10000000-0000-0000-0000-000000000001', 'PRIVATE', 0, 0, 0, false, now(), now()),
       ('5ee20000-0000-4000-8000-000000000003', '行业标准示例库', '行业标准',
        '示例数据：公开标准的片段，密级上限 PUBLIC —— 用来对比「公开库」与「受限库」的可见性。',
        '📙', 'LOCAL', '5ee10000-0000-4000-8000-000000000002', 'PUBLIC', true,
        '10000000-0000-0000-0000-000000000001', 'PRIVATE', 0, 0, 0, false, now(), now());

-- ── 2 · 三份文档（已解析、待向量化）──────────────────────────────────────────
-- ★ `department_legacy` 留空（V27：它不是从自由字符串迁来的）；部门走 `org_id` = 总部。
-- ★ `scope_kind/scope_id` 是 V34 起的三列域标记：库域必须满足 `scope_id = kb_id`（库层有 CHECK）。
insert into kb_document (id, kb_id, name, source, doc_type, department_legacy, classification,
                         status, fail_reason, fail_hint, char_count, segment_count,
                         processed_segments, enabled, parsed_content, org_id,
                         scope_kind, scope_id, owner_id,
                         created_at, updated_at)
select v.id::uuid, v.kb_id::uuid, v.name, 'local', v.doc_type, null, v.classification,
       'PENDING_EMBEDDING', null, null, char_length(v.parsed_content), 3, 0, true,
       v.parsed_content, '00000000-0000-4000-8000-000000000001'::uuid,
       'KB', v.kb_id::uuid, '10000000-0000-0000-0000-000000000001'::uuid,
       now(), now()
from (values
  ('5ee30000-0000-4000-8000-000000000001', '5ee20000-0000-4000-8000-000000000001',
   '差旅与费用报销管理办法.md', '制度', 'INTERNAL',
   '差旅与费用报销管理办法（示例）。第二章 市内交通：市内交通费每天不超过八十元。'
   '第三章 住宿：住宿费上限为每晚五百元，超标部分自理。第四章 报销时限：出差结束后十个工作日内提交。'),
  ('5ee30000-0000-4000-8000-000000000002', '5ee20000-0000-4000-8000-000000000002',
   '知识助理产品说明.md', '产品手册', 'INTERNAL',
   '知识助理产品说明（示例）。能力一：把制度、手册、标准整理成可检索的片段并给出引用。'
   '能力二：以应用为载体把库、模型、提示词装配成问答机器人。能力三：公开链接可以把问答能力分享给未登录访客。'),
  ('5ee30000-0000-4000-8000-000000000003', '5ee20000-0000-4000-8000-000000000003',
   '信息安全等级保护基本要求摘录.md', '行业标准', 'PUBLIC',
   '信息安全等级保护基本要求（示例摘录）。要求一：按信息的重要程度分级并逐级施加保护。'
   '要求二：访问控制遵循最小权限，权限变更要留痕。要求三：审计记录至少保存六个月。')
) as v(id, kb_id, name, doc_type, classification, parsed_content);

-- ── 3 · 九条分段（每份文档三条）──────────────────────────────────────────────
-- ★ `search_text` 直接等于 `content`：示例数据不假装自己做过前缀渲染（真实链路的前缀由
--   `SearchText.prefix` 按「文种·部门 code·标题｜章节路径」拼，见 V27 的设计说明）。
-- ★ `content_hash` = `md5(id) || md5(id)`（64 位十六进制），只为满足列宽与"看起来像真的"。
insert into kb_segment (id, document_id, kb_id, ordinal, title, title_path, content, search_text,
                        content_hash, char_count, is_table, is_code, oversized_risk, enabled,
                        embedding_status, classification, scope_kind, scope_id, owner_id,
                        created_at, updated_at)
select v.id::uuid, v.document_id::uuid, v.kb_id::uuid, v.ordinal::int, v.title, v.title_path,
       v.content, v.content, md5(v.id) || md5(v.id), char_length(v.content),
       false, false, false, true, 'PENDING_EMBEDDING', v.classification, 'KB',
       v.kb_id::uuid, '10000000-0000-0000-0000-000000000001'::uuid, now(), now()
from (values
  ('5ee40000-0000-4000-8000-000000000001', '5ee30000-0000-4000-8000-000000000001',
   '5ee20000-0000-4000-8000-000000000001', 1, '第二章 市内交通',
   '差旅与费用报销管理办法/第二章 市内交通', 'INTERNAL',
   '市内交通费每天不超过八十元。超出部分由本人承担，特殊情况需在报销单上说明并由部门负责人签字。'),
  ('5ee40000-0000-4000-8000-000000000002', '5ee30000-0000-4000-8000-000000000001',
   '5ee20000-0000-4000-8000-000000000001', 2, '第三章 住宿',
   '差旅与费用报销管理办法/第三章 住宿', 'INTERNAL',
   '住宿费上限为每晚五百元。同性别同行人员原则上合住；超标部分自理，不得以其他名目补差。'),
  ('5ee40000-0000-4000-8000-000000000003', '5ee30000-0000-4000-8000-000000000001',
   '5ee20000-0000-4000-8000-000000000001', 3, '第四章 报销时限',
   '差旅与费用报销管理办法/第四章 报销时限', 'INTERNAL',
   '出差结束后十个工作日内提交报销。逾期未提交的，需附情况说明；跨年度的费用不得报销。'),
  ('5ee40000-0000-4000-8000-000000000004', '5ee30000-0000-4000-8000-000000000002',
   '5ee20000-0000-4000-8000-000000000002', 1, '能力一：检索与引用',
   '知识助理产品说明/能力一：检索与引用', 'INTERNAL',
   '把制度、手册、标准整理成可检索的片段，回答时给出引用编号，点开能回到原文的那一段。'),
  ('5ee40000-0000-4000-8000-000000000005', '5ee30000-0000-4000-8000-000000000002',
   '5ee20000-0000-4000-8000-000000000002', 2, '能力二：应用装配',
   '知识助理产品说明/能力二：应用装配', 'INTERNAL',
   '以应用为载体，把知识库、对话模型、提示词装配成一个问答机器人，并可为它签发公开链接。'),
  ('5ee40000-0000-4000-8000-000000000006', '5ee30000-0000-4000-8000-000000000002',
   '5ee20000-0000-4000-8000-000000000002', 3, '能力三：公开分享',
   '知识助理产品说明/能力三：公开分享', 'INTERNAL',
   '公开链接可以让未登录的访客直接提问；凭证可随时撤销，撤销后同一个链接立即失效。'),
  ('5ee40000-0000-4000-8000-000000000007', '5ee30000-0000-4000-8000-000000000003',
   '5ee20000-0000-4000-8000-000000000003', 1, '要求一：分级保护',
   '等级保护基本要求/要求一：分级保护', 'PUBLIC',
   '按信息的重要程度分级，并逐级施加保护措施；分级结果要书面记录。'),
  ('5ee40000-0000-4000-8000-000000000008', '5ee30000-0000-4000-8000-000000000003',
   '5ee20000-0000-4000-8000-000000000003', 2, '要求二：最小权限',
   '等级保护基本要求/要求二：最小权限', 'PUBLIC',
   '访问控制遵循最小权限原则；权限的授予与变更都要留痕，定期复核。'),
  ('5ee40000-0000-4000-8000-000000000009', '5ee30000-0000-4000-8000-000000000003',
   '5ee20000-0000-4000-8000-000000000003', 3, '要求三：审计留痕',
   '等级保护基本要求/要求三：审计留痕', 'PUBLIC',
   '审计记录至少保存六个月，且不得被有权限的人员之外的任何人修改。')
) as v(id, document_id, kb_id, ordinal, title, title_path, classification, content);

-- ── 4 · 提示词类型与两条提示词（`#215` 之后它们真的会进 system prompt）──────
insert into as_prompt_type (id, type_name, icon, created_at, updated_at)
values ('5ee50000-0000-4000-8000-000000000001', '示例话术类型', '🧩', now(), now());

insert into as_prompt_config (id, role_name, call_word, type_id, description, icon,
                              created_at, updated_at)
values ('5ee60000-0000-4000-8000-000000000001', '严谨口径',
        '只依据参考资料作答；资料里没有的内容直接说「资料未涉及」，不要推测。',
        '5ee50000-0000-4000-8000-000000000001', '示例数据：防止模型自由发挥的口径约束。', '🧩',
        now(), now()),
       ('5ee60000-0000-4000-8000-000000000002', '引用口径',
        '回答里每个结论后用 [1] 这样的编号标注出处；没有出处的结论不要写。',
        '5ee50000-0000-4000-8000-000000000001', '示例数据：让引用编号出现在正文里。', '🧩',
        now(), now());

-- ── 5 · 两个应用（SIMPLE：绑定对话模型 + 库 [+ 提示词]）────────────────────
insert into as_assistant (id, name, icon, description, type, model_id, role_prompt,
                          multi_turn, thinking_enabled, prologue, quick_questions, tags,
                          retrieval_mode, retrieval_threshold, retrieval_top_n,
                          retrieval_no_ref_strategy, retrieval_query_count,
                          retrieval_decompose_enabled, retrieval_max_sub_questions,
                          builtin, published, owner_id, visibility, created_at, updated_at)
values ('5ee70000-0000-4000-8000-000000000001', '制度问答示例', '📘',
        '示例数据：只绑「制度规范示例库」的问答应用，回答带引用。',
        'SIMPLE', '5ee10000-0000-4000-8000-000000000001',
        '你是企业制度问答助手。回答要短，先给结论再给出处。',
        true, false, '问我差旅、报销、值班相关的规定。',
        array['住宿费上限是多少？', '市内交通怎么报？'], array['制度'],
        'HYBRID', 0.60, 5, 'REFUSE', 3, true, 3,
        false, false, '10000000-0000-0000-0000-000000000001', 'PRIVATE', now(), now()),
       ('5ee70000-0000-4000-8000-000000000002', '产品说明问答示例', '📗',
        '示例数据：绑「产品手册示例库」+ 两条提示词的应用，用来对比提示词是否生效。',
        'SIMPLE', '5ee10000-0000-4000-8000-000000000001',
        '你是产品介绍助手，用一句话回答，然后列三条要点。',
        true, false, '可以问我这个平台能做什么。',
        array['公开链接怎么撤销？'], array['产品'],
        'HYBRID', 0.60, 5, 'REFUSE', 3, true, 3,
        false, false, '10000000-0000-0000-0000-000000000001', 'PRIVATE', now(), now());

insert into as_assistant_kb (assistant_id, knowledge_base_id)
values ('5ee70000-0000-4000-8000-000000000001', '5ee20000-0000-4000-8000-000000000001'),
       ('5ee70000-0000-4000-8000-000000000002', '5ee20000-0000-4000-8000-000000000002');

-- ★ 第二个应用绑两条提示词：示例数据里**故意留一个"只有它绑了提示词"的应用**，
--   这样界面上「提示词」这一栏不是空的时候，一眼能看出它对应哪一格行为（`#215`）。
insert into as_assistant_prompt (assistant_id, prompt_config_id)
values ('5ee70000-0000-4000-8000-000000000002', '5ee60000-0000-4000-8000-000000000001'),
       ('5ee70000-0000-4000-8000-000000000002', '5ee60000-0000-4000-8000-000000000002');

-- ── 6 · 计数列从真实行算回来（理由见文件头）────────────────────────────────
update kb_knowledge_base k
set document_count = (select count(*) from kb_document d
                      where d.kb_id = k.id and d.deleted_at is null),
    segment_count  = (select count(*) from kb_segment s where s.kb_id = k.id),
    char_count     = (select coalesce(sum(s.char_count), 0) from kb_segment s where s.kb_id = k.id)
where k.id in ('5ee20000-0000-4000-8000-000000000001',
               '5ee20000-0000-4000-8000-000000000002',
               '5ee20000-0000-4000-8000-000000000003');
