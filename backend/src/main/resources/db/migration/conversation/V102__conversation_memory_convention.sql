-- 记忆定稿：会话层真正有写入方（0.9.0 · #296）
--
-- 背景（2026-11-16 复核发现的两个"永远空"）：
--
-- ① 抽取器只产出 FACT / PREFERENCE / CONSTRAINT / GOAL，而它们全部落到 USER 或
--    CROSS_SESSION 层 ⇒ **CONVERSATION 层从来没有写入方**：表在、召回有查询、
--    结果永远是空。"会话记忆"名存实亡。
-- ② 画像（recomputeProfile）只从 PERSONA 层读，而没有任何路径写 PERSONA ⇒
--    自动画像永远是空串，注入的那段"关于这个人"从来没出现过。
--
-- 本迁移只解决 ① 的一半（给会话层一个合法的种类）；② 在代码里修（画像改从 USER 层
-- 推导 —— 那里已经有 FACT / PREFERENCE / CONSTRAINT，正是画像该用的原料）。
--
-- ★ 为什么不复用 EVENT：EVENT 是"发生过的事"（上周问过消防法处罚条款），
--   而 CONVENTION 是"这个会话里说定的做法"（按 2025 版算 / 用中文回答）。
--   前者是历史，后者是规则 —— 混在一起会让"忘掉一条旧事件"和"违反一条约定"变得无法区分。
--
-- ★ 为什么 CONVENTION 只允许出现在会话层（见下方 chk_cv_memory_convention_scope）：
--   一个"只在这次会话成立的约定"被升到 USER 层，下一次对话就会按一个早已结束的
--   约定办事，而用户完全不知道 —— 这是最难查的那种错。

alter table cv_memory
    drop constraint chk_cv_memory_kind;

alter table cv_memory
    add constraint chk_cv_memory_kind
        check (kind in ('FACT', 'PREFERENCE', 'CONSTRAINT', 'GOAL', 'EVENT', 'SUMMARY', 'CONVENTION'));

-- ★ 本条是这个迁移的真正目的：约定不许离开会话层。
--   它同时挡住两件事：把 CONVENTION 写到 USER/CROSS_SESSION/PERSONA（约定泄漏成全局），
--   以及把别的种类塞进 CONVERSATION 层冒充约定（那会让"会话记忆"变成第二个消息窗口）。
alter table cv_memory
    add constraint chk_cv_memory_convention_scope
        check (
            (kind = 'CONVENTION' and level = 'CONVERSATION')
            or (kind <> 'CONVENTION' and level <> 'CONVERSATION')
        );

comment on column cv_memory.kind is
    'FACT=可核对的一句话 / PREFERENCE=表达倾向 / CONSTRAINT=不能违反的边界 / GOAL=在推进的事 / EVENT=发生过的事 / SUMMARY=一段对话的提炼 / CONVENTION=这个会话里说定的做法（只许出现在会话层）';
