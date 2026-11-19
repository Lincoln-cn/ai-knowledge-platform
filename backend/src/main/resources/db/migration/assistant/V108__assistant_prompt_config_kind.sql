-- 提示词表里现在有两种东西，用 kind 分开，不新建表。
--
-- ★ 为什么复用一张表：两类的字段几乎重合（名称 / 正文 / 分类 / 描述 / 图标），
--   分开就是两套管理页、两套分类、两套权限点。而它们的差别只在**给谁看**：
--   ROLE 给模型（应用配置里选用，写进 role_prompt），QUESTION 给终端用户（对话页的问法示例）。
--
-- ★ 为什么不给 QUESTION 的 app_id 加外键：这里存的是"这条模板推荐给哪个应用"，
--   属于候选池的标注，不是运行期的绑定关系；应用被删时模板没有理由跟着失效。
--   （运行期读的是 as_assistant.quick_questions —— 见 as_prompt_config.app_id 的注释。）

alter table as_prompt_config
  add column if not exists kind varchar(16) not null default 'ROLE';

alter table as_prompt_config
  add column if not exists app_id uuid;

create index if not exists idx_as_prompt_config_kind on as_prompt_config (kind);

comment on column as_prompt_config.kind is
  'ROLE = 角色指令（给模型的身份设定，应用配置里选用）；QUESTION = 提问模板（给终端用户的问法示例）';

comment on column as_prompt_config.app_id is
  '提问模板推荐给哪个应用（可空 = 通用）。★ 只是候选池标注，运行期不读它：对话页空态只读 as_assistant.quick_questions，没有才回落到通用模板';

comment on column as_prompt_config.call_word is
  'ROLE 时是角色指令正文（写入应用 role_prompt）；QUESTION 时就是用户会看到的那句问题，{花括号} 内是可替换的占位内容';
