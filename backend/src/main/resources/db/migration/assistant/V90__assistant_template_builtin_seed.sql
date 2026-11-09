-- 2026-11-09 批次 2（#295 模板中心）：三个内置模板。
--
-- ★ 为什么内置模板不带 modelId / kbIds：
--   这两个是**环境相关**的引用（不同部署的模型登记 id、知识库 id 都不一样），
--   写进内置模板就是错的。内置模板只提供"骨架"——角色提示词、检索参数、
--   开场白、推荐问题；建出来之后由部署方补模型与知识库。
--   因此建出来的应用在补模型之前过不了发布校验（`modelId` 必填），这是**对的**。
--
-- ★ payload 是 `assistant-bundle@1`（`AssistantBundleView.BUNDLE_FORMAT`）的形状，
--   与「从应用导出」走同一条反序列化路径 ⇒ 导入侧的校验对它同样生效。
--
-- ★ 迁移号：加之前扫了全局最大号（当时是 V88 的 trigger slots）。
--
-- ★ 为什么这里要补 default：V89 建表时 id 是裸 `uuid PRIMARY KEY`（由应用侧生成）。
--   内置模板是**数据**不是**运行时创建的实体**，insert 时没有应用侧代码给它生成 id，
--   所以在这里补上 default。这比在三条 insert 里各写一次 gen_random_uuid() 更稳 ——
--   以后再加内置模板的人不会漏。

alter table as_template alter column id set default gen_random_uuid();

insert into as_template (name, description, category, source_app_id, payload, builtin)
values
(
    '合同审查（法务通用）',
    '围绕合同条款的合规性提问：主体资格、履约能力、违约责任、争议解决。建出来后请补模型与「合同/法务」知识库。',
    '法务',
    null,
    '{
       "format": "assistant-bundle@1",
       "exportedAt": "2026-11-09T00:00:00Z",
       "includesDocuments": false,
       "config": {
         "name": "合同审查",
         "icon": "📄",
         "description": "围绕合同条款的合规性提问，只依据已入库的制度与范本作答",
         "type": "SIMPLE",
         "modelId": null,
         "kbIds": [],
         "rolePrompt": "你是企业的合同审查助手。只依据检索到的制度、范本与历史合同回答，逐条指出条款出处。资料里没有依据时明确说「资料中未涉及」，不要凭常识补充。涉及金额、期限、比例的条款，务必引用原文数字，不要换算或推断。",
         "promptIds": [],
         "retrieval": {
           "mode": "HYBRID",
           "topN": 5,
           "threshold": 0.6,
           "noRefStrategy": "REFUSE",
           "multiHopEnabled": false,
           "maxHops": 2
         },
         "multiTurn": { "enabled": true, "maxRounds": 3 },
         "thinkingEnabled": false,
         "prologue": "你好，我是合同审查助手。把要审的条款或问题发给我，我会逐条对照制度与范本给出依据。",
         "quickQuestions": [
           "违约责任条款一般怎么写才有效？",
           "合同主体资格审查要看哪些材料？",
           "争议解决条款选仲裁还是诉讼更好？"
         ]
       },
       "dependencies": []
     }'::jsonb,
    true
),
(
    '人事政策问答（HR 通用）',
    '员工自助式提问：假期、考勤、报销、社保公积金、离职流程。建出来后请补模型与「人事制度」知识库。',
    '人事',
    null,
    '{
       "format": "assistant-bundle@1",
       "exportedAt": "2026-11-09T00:00:00Z",
       "includesDocuments": false,
       "config": {
         "name": "人事政策问答",
         "icon": "👥",
         "description": "员工自助查询人事制度：假期、考勤、报销、社保、离职",
         "type": "SIMPLE",
         "modelId": null,
         "kbIds": [],
         "rolePrompt": "你是企业人事政策的答疑助手，面向全体员工。只依据检索到的人事制度回答，回答里给出制度名称与条款号。制度之间若有冲突，如实指出两处说法不同并说明各自适用情形，不要替员工选一个。涉及个人具体待遇时，提示对方咨询 HR 而非直接计算。",
         "promptIds": [],
         "retrieval": {
           "mode": "HYBRID",
           "topN": 5,
           "threshold": 0.6,
           "noRefStrategy": "REFUSE",
           "multiHopEnabled": false,
           "maxHops": 2
         },
         "multiTurn": { "enabled": true, "maxRounds": 3 },
         "thinkingEnabled": false,
         "prologue": "你好，我是人事政策助手。假期怎么算、报销怎么走、离职要哪些手续，都可以问我。",
         "quickQuestions": [
           "年假怎么计算？入职不满一年有吗？",
           "出差报销需要哪些凭证？",
           "离职需要提前多久提出？"
         ]
       },
       "dependencies": []
     }'::jsonb,
    true
),
(
    'IT 运维支持（内部服务台）',
    '面向员工的 IT 自助：账号权限、网络、办公软件、设备报修。建出来后请补模型与「IT 服务规范」知识库。',
    'IT',
    null,
    '{
       "format": "assistant-bundle@1",
       "exportedAt": "2026-11-09T00:00:00Z",
       "includesDocuments": false,
       "config": {
         "name": "IT 运维支持",
         "icon": "🛠️",
         "description": "员工 IT 自助：账号权限、网络接入、办公软件、设备报修",
         "type": "SIMPLE",
         "modelId": null,
         "kbIds": [],
         "rolePrompt": "你是企业内部 IT 服务台的助手。只依据检索到的服务规范与操作手册回答，给出可执行的操作步骤与工单入口。涉及需要人工处理的（如设备更换、权限审批），说明该走哪个流程、由谁审批。不要提供绕过安全策略的做法。",
         "promptIds": [],
         "retrieval": {
           "mode": "HYBRID",
           "topN": 5,
           "threshold": 0.6,
           "noRefStrategy": "REFUSE",
           "multiHopEnabled": false,
           "maxHops": 2
         },
         "multiTurn": { "enabled": true, "maxRounds": 3 },
         "thinkingEnabled": false,
         "prologue": "你好，我是 IT 服务台助手。账号、网络、软件、设备的问题都可以问我，能自助解决的我会给步骤，需要人工的我告诉你走哪个流程。",
         "quickQuestions": [
           "怎么申请一个新的系统账号？",
           "外网访问需要走什么流程？",
           "电脑坏了怎么报修？"
         ]
       },
       "dependencies": []
     }'::jsonb,
    true
);
