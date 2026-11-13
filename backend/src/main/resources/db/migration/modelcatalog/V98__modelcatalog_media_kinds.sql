-- modelcatalog 模块：模型种类扩到多模态（#333 多模态四个节点）
--
-- ★ 为什么这三个种类必须进同一个枚举，而不是各起一个"能力标签"：
--   ModelKind 决定的是「这个模型的必填属性是什么」，而必填属性决定了
--   ① 创建时校验什么、② 探测探什么、③ 界面显示哪一列。
--   三个种类都既没有 context_window 也没有 dimension —— 用一个 tag 表达会让
--   chk_ai_model_chat_dimension 这类互斥约束无从下手。

alter table ai_model drop constraint chk_ai_model_kind;

alter table ai_model
  add constraint chk_ai_model_kind
  check (kind in ('CHAT', 'EMBEDDING', 'TTS', 'STT', 'IMAGE'));

-- ★ 多模态三个既没有对话上下文窗口，也不产出向量：两列都必须为空。
--   少这一条，界面可以把 TTS 模型配上 context_window，而 AI_CHAT 节点会在运行期
--   拿它当对话模型用 —— 报错发生在离配置很远的地方。
alter table ai_model
  add constraint chk_ai_model_media_capacity
  check (kind in ('CHAT', 'EMBEDDING') or (context_window is null and dimension is null));

comment on column ai_model.kind is
  '模型种类：CHAT / EMBEDDING / TTS（语音合成）/ STT（语音识别）/ IMAGE（图像生成）；'
  '前两种各自带 context_window 或 dimension，后三种都不带';
