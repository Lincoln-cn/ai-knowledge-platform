-- 通用问答的「召回」配置：开关 / 库范围 / 准确度阈值 / 条数
--
-- ★ 为什么需要这几列（实测症状）
--
--   用户问「编写一个 java 的 hello world」，回答却是「创建一个 Vue 单文件组件…」。
--   根因不是模型：**通用问答在隐式做全库检索** —— `ChatService.retrieve` 里
--   `requestedKbIds` 为空时会走 `RetrievalScope.shared` 支（= 用户可见的**全部**知识库），
--   于是把 `语料解析-1792250686` 里的一堆无关分段（Vue 文档、Gutenberg 电子书、markdown 示例）
--   以 **0.25 分**硬塞进 prompt。0.25 是"沾一点边就算命中"的分数，模型于是照着它复述。
--
--   用户的要求：「不配置就不走召回，配置了才走召回」+「召回准确度设置」+「知识库范围配置」。
--
-- ★ 默认值刻意是"不召回"（retrieval_enabled = false）：
--   通用问答的语义是"只有模型自身知识"（前端空态文案就是这么写的：
--   「未挂知识库时回答只有模型自身知识，属正常现象」）。
--   之前的隐式全库检索**与这句文案直接矛盾**，而且没有任何地方说明它发生了。
alter table cv_chat_settings
    -- 通用问答是否走召回。★ false = 一条引用都不检索（默认）
    add column if not exists retrieval_enabled boolean not null default false,
    -- 通用问答的召回范围（知识库 id 列表）。★ 空 = 用该用户可见的全部库（仅在启用召回时才有意义）
    add column if not exists retrieval_kb_ids uuid[],
    -- 相似度下限：低于它的命中直接丢弃。★ 0.25 那种"沾边"分数就该被挡在这里
    add column if not exists retrieval_threshold numeric(4, 3) not null default 0.300,
    -- 召回条数上限
    add column if not exists retrieval_top_n smallint not null default 5;

alter table cv_chat_settings
    add constraint chk_chat_settings_retrieval_threshold
        check (retrieval_threshold >= 0 and retrieval_threshold <= 1),
    add constraint chk_chat_settings_retrieval_top_n
        check (retrieval_top_n between 1 and 20);

comment on column cv_chat_settings.retrieval_enabled is
    '通用问答是否走召回。★ 默认 false：不配置就不检索（与前端"未挂知识库时回答只有模型自身知识"的文案一致）';
comment on column cv_chat_settings.retrieval_kb_ids is
    '通用问答的召回范围；★ 空 = 该用户可见的全部库（只在 retrieval_enabled 时生效）';
comment on column cv_chat_settings.retrieval_threshold is
    '相似度下限，低于它的命中丢弃。★ 实测 0.25 分的命中会让模型复述无关内容';
