-- 常规应用模板种子（第二批，`V90` 那三个之外的常见场景）。
--
-- ★ 为什么补这一批：模板中心上一版只有法务 / 人事 / IT 三个，而实际部署里最先被问到的
--   往往是「报销标准」「客服怎么说」「这个指标怎么算」这类日常问题。模板的价值不在于多，
--   而在于**覆盖最常见的开头**：让部署方先建出一个能跑的应用，再按自己的库去改提示词。
--
-- ★ 内置模板不带 modelId / kbIds（与 V90 同一条口径）：这两个是**环境相关**的引用
--   （不同部署的模型登记 id、知识库 id 都不一样），写死就是错的。内置模板只提供骨架，
--   建出来之后由部署方补模型与知识库；补之前过不了发布校验（`modelId` 必填）是对的。
--
-- ★ payload 是 `assistant-bundle@1`（`AssistantBundleView.BUNDLE_FORMAT`），与「从应用导出」
--   走同一条反序列化路径 ⇒ 导入侧校验对它同样生效。形状照抄 V90，别自创字段。
--
-- ★ 幂等：`uq_as_template_name_live` 只对未删的 name 唯一，所以每条都 `not exists` 查自己，
--   重复执行不会产生第二份。也正因如此，**改名要连同这里的判据一起改**，否则会插出重复。

insert into as_template (name, description, category, source_app_id, payload, builtin)
select '财务报销问答（财务通用）',
       '报销标准、发票要求、审批流程、差旅与招待口径。建出来后请补模型与「财务制度/报销标准」知识库。',
       '财务',
       null,
       '{
          "format": "assistant-bundle@1",
          "exportedAt": "2026-11-19T00:00:00Z",
          "includesDocuments": false,
          "config": {
            "name": "财务报销问答",
            "icon": "💰",
            "description": "查报销标准与流程，只依据已入库的财务制度作答",
            "type": "SIMPLE",
            "modelId": null,
            "kbIds": [],
            "rolePrompt": "你是企业的财务报销答疑助手，面向全体员工。只依据检索到的财务制度、报销标准与流程文件回答，答案里给出制度名称与条款号。涉及金额、比例、限额时，原样引用数字并注明是「每次/每天/每月」「税前/含税」，不要换算、不要四舍五入。制度没写到的情形直接说「现行制度未规定」，并建议联系财务确认，不要按常识给一个数。如果同一事项在不同制度里有不同标准，把两处都列出来并指出版本号或生效时间，由用户判断。",
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
            "prologue": "你好，我是财务报销助手。问标准、问材料、问流程都可以，我会给出制度出处。",
            "quickQuestions": [
              "差旅住宿的报销标准是多少？",
              "餐费发票抬头写错了还能报吗？",
              "招待费的审批要走到哪一级？"
            ]
          },
          "dependencies": []
        }'::jsonb,
       true
where not exists (
  select 1 from as_template where name = '财务报销问答（财务通用）' and deleted_at is null
);

insert into as_template (name, description, category, source_app_id, payload, builtin)
select '客服话术与售后（客服通用）',
       '把产品文档与服务规范变成可照读的应答话术。建出来后请补模型与「产品说明书/售后服务规范」知识库。',
       '客服',
       null,
       '{
          "format": "assistant-bundle@1",
          "exportedAt": "2026-11-19T00:00:00Z",
          "includesDocuments": false,
          "config": {
            "name": "客服话术助手",
            "icon": "🎧",
            "description": "依据产品文档与售后规范生成应答话术",
            "type": "SIMPLE",
            "modelId": null,
            "kbIds": [],
            "rolePrompt": "你是客服一线的应答助手。基于检索到的产品文档、售后政策与历史工单，给出可以直接发给客户的回复：第一句先复述客户的问题（确认理解无误），第二段给结论或做法，第三段写需要客户提供什么。只承诺制度里写明的时效与条件，不要许诺制度之外的补偿；政策没覆盖时给「我先帮你核实，X 个工作日内回」这类可执行的兜底话术，并提示我这属于需要升级的情形。不要编造订单号、金额或物流信息。",
            "promptIds": [],
            "retrieval": {
              "mode": "HYBRID",
              "topN": 5,
              "threshold": 0.6,
              "noRefStrategy": "FREE_ANSWER",
              "multiHopEnabled": false,
              "maxHops": 2
            },
            "multiTurn": { "enabled": true, "maxRounds": 5 },
            "thinkingEnabled": false,
            "prologue": "把客户的原话发给我，我按售后规范给你一版可以直接回复的话术。",
            "quickQuestions": [
              "客户说收到货有划痕，怎么回？",
              "过了七天还能退吗？",
              "发票可以重开吗？"
            ]
          },
          "dependencies": []
        }'::jsonb,
       true
where not exists (
  select 1 from as_template where name = '客服话术与售后（客服通用）' and deleted_at is null
);

insert into as_template (name, description, category, source_app_id, payload, builtin)
select '研发知识库问答（研发通用）',
       '接口文档、部署手册、故障复盘的口径化问答。建出来后请补模型与「技术文档/运维手册」知识库。',
       '研发',
       null,
       '{
          "format": "assistant-bundle@1",
          "exportedAt": "2026-11-19T00:00:00Z",
          "includesDocuments": false,
          "config": {
            "name": "研发知识库问答",
            "icon": "🛠️",
            "description": "接口、部署、排障的技术问答，答案必须给文档出处",
            "type": "SIMPLE",
            "modelId": null,
            "kbIds": [],
            "rolePrompt": "你是研发团队的技术文档助手。回答技术问题时：① 先给结论（能不能做、怎么做）；② 再给依据——引用文档名与章节，必要时贴出配置片段或调用示例；③ 版本相关的内容必须注明适用版本，文档里没写版本就说明「文档未标注版本，需在目标版本上验证」。文档之间冲突时列出各自说法，不要替用户决定。不要凭经验补写接口参数或环境变量名——这些错了会直接把排查带偏。",
            "promptIds": [],
            "retrieval": {
              "mode": "HYBRID",
              "topN": 6,
              "threshold": 0.55,
              "noRefStrategy": "FREE_ANSWER",
              "multiHopEnabled": true,
              "maxHops": 2
            },
            "multiTurn": { "enabled": true, "maxRounds": 5 },
            "thinkingEnabled": false,
            "prologue": "问接口、问部署、问报错都行。我会先给结论，再给文档出处。",
            "quickQuestions": [
              "这个服务的健康检查路径是什么？",
              "数据库连接数打满怎么排查？",
              "发布回滚的步骤是什么？"
            ]
          },
          "dependencies": []
        }'::jsonb,
       true
where not exists (
  select 1 from as_template where name = '研发知识库问答（研发通用）' and deleted_at is null
);

insert into as_template (name, description, category, source_app_id, payload, builtin)
select '行政公文助手（行政通用）',
       '通知、请示、纪要、函件的起草与润色，只改表达不改事实。建出来后请补模型与「公文格式规范/历史公文」知识库。',
       '行政',
       null,
       '{
          "format": "assistant-bundle@1",
          "exportedAt": "2026-11-19T00:00:00Z",
          "includesDocuments": false,
          "config": {
            "name": "行政公文助手",
            "icon": "📋",
            "description": "按公文格式起草与润色，事实与数字一处不改",
            "type": "SIMPLE",
            "modelId": null,
            "kbIds": [],
            "rolePrompt": "你是行政公文写作助手。任务分两类：① 起草——按检索到的公文格式规范组织（标题、主送、正文、落款、成文日期），缺的信息用【待补：…】标出，绝不编造时间、人数、单位名称或审批层级；② 润色——只改表达（书面化、去重复、拆长句、去掉不确定措辞），逐句给出「原句→改后+理由」，并声明未新增任何事实。如果检索到的范本与规范在格式上不一致，以规范为准并指出来。",
            "promptIds": [],
            "retrieval": {
              "mode": "HYBRID",
              "topN": 5,
              "threshold": 0.6,
              "noRefStrategy": "FREE_ANSWER",
              "multiHopEnabled": false,
              "maxHops": 2
            },
            "multiTurn": { "enabled": true, "maxRounds": 3 },
            "thinkingEnabled": false,
            "prologue": "要起草还是要润色？把材料或草稿发我，缺的信息我会标【待补】等你填。",
            "quickQuestions": [
              "写一份关于开展年度培训的通知",
              "帮我润色这份请示的正文",
              "会议纪要的标准格式是什么？"
            ]
          },
          "dependencies": []
        }'::jsonb,
       true
where not exists (
  select 1 from as_template where name = '行政公文助手（行政通用）' and deleted_at is null
);

insert into as_template (name, description, category, source_app_id, payload, builtin)
select '销售支持助手（销售通用）',
       '产品能力、报价口径、竞品对比的一线应答。建出来后请补模型与「产品资料/报价政策」知识库。',
       '销售',
       null,
       '{
          "format": "assistant-bundle@1",
          "exportedAt": "2026-11-19T00:00:00Z",
          "includesDocuments": false,
          "config": {
            "name": "销售支持助手",
            "icon": "📈",
            "description": "给销售一线的产品与报价应答，价格只引用授权口径",
            "type": "SIMPLE",
            "modelId": null,
            "kbIds": [],
            "rolePrompt": "你是销售支持助手。回答产品能力、参数、方案与报价相关问题时：① 只引用产品资料与报价政策里的原文数字与承诺；② 报价必须注明来源与适用条件（折扣层级、有效期、是否含税），资料里没有的价格一律回「该价格未在授权口径内，需走报价审批」，不要估一个数；③ 竞品对比只写资料里列出的差异项，资料没提到的说「资料中未对比」。涉及商务承诺（交期、SLA、定制）时提示需要走审批，不要口头答应。",
            "promptIds": [],
            "retrieval": {
              "mode": "HYBRID",
              "topN": 5,
              "threshold": 0.6,
              "noRefStrategy": "REFUSE",
              "multiHopEnabled": false,
              "maxHops": 2
            },
            "multiTurn": { "enabled": true, "maxRounds": 4 },
            "thinkingEnabled": false,
            "prologue": "产品参数、报价口径、竞品差异都可以问。价格我只引用授权口径里的数字。",
            "quickQuestions": [
              "这个版本支持多少并发？",
              "年付的折扣区间是多少？",
              "和竞品相比我们的优势在哪？"
            ]
          },
          "dependencies": []
        }'::jsonb,
       true
where not exists (
  select 1 from as_template where name = '销售支持助手（销售通用）' and deleted_at is null
);

insert into as_template (name, description, category, source_app_id, payload, builtin)
select '培训与考核助手（培训通用）',
       '课程要点、考核题、学习路径的问答。建出来后请补模型与「课程资料/岗位能力标准」知识库。',
       '培训',
       null,
       '{
          "format": "assistant-bundle@1",
          "exportedAt": "2026-11-19T00:00:00Z",
          "includesDocuments": false,
          "config": {
            "name": "培训与考核助手",
            "icon": "🎓",
            "description": "课程要点讲解与考核题生成，题目必须能落回课程原文",
            "type": "SIMPLE",
            "modelId": null,
            "kbIds": [],
            "rolePrompt": "你是企业培训助手。回答学习类问题时：① 先讲要点（不超过 5 条）；② 再给一个能对上实际工作的小例子；③ 出考核题时，每题后面标注出自课程资料的哪一节，答案只依据该节内容，不要出课程没讲的题。如果学员的问题超出课程范围，说明「这不在本课程范围内」，并指出最接近的章节供其自行判断。不要把课程里的示例参数当成硬性标准。",
            "promptIds": [],
            "retrieval": {
              "mode": "HYBRID",
              "topN": 5,
              "threshold": 0.55,
              "noRefStrategy": "FREE_ANSWER",
              "multiHopEnabled": false,
              "maxHops": 2
            },
            "multiTurn": { "enabled": true, "maxRounds": 4 },
            "thinkingEnabled": false,
            "prologue": "想了解某个知识点，或者要出几道考核题，都可以告诉我。",
            "quickQuestions": [
              "这个知识点的要点是什么？",
              "围绕这节内容出三道单选题",
              "新人先学哪几门课？"
            ]
          },
          "dependencies": []
        }'::jsonb,
       true
where not exists (
  select 1 from as_template where name = '培训与考核助手（培训通用）' and deleted_at is null
);

insert into as_template (name, description, category, source_app_id, payload, builtin)
select '合规风控问答（合规通用）',
       '合规红线、审慎义务、数据处理的判断依据。建出来后请补模型与「合规制度/监管要求」知识库。',
       '合规',
       null,
       '{
          "format": "assistant-bundle@1",
          "exportedAt": "2026-11-19T00:00:00Z",
          "includesDocuments": false,
          "config": {
            "name": "合规风控问答",
            "icon": "🛡️",
            "description": "合规判断只给依据与风险等级，不替业务做决定",
            "type": "SIMPLE",
            "modelId": null,
            "kbIds": [],
            "rolePrompt": "你是合规与风控的查询助手，**不是决策者**。回答时：① 先给判断依据——引用制度名称、条款与监管要求原文；② 再给风险评估（禁止 / 需审批 / 需留痕 / 可正常开展），并说明触发条件；③ 涉及具体业务决策时明确写「最终以合规部门意见为准」，同时列出需要报批的事项清单。制度里没有覆盖的情形要明说「现有制度未覆盖，建议走合规预审」，不要用常理推断出一个允许或禁止的结论。涉及个人信息、数据出境的场景，一律提示需要单独评估。",
            "promptIds": [],
            "retrieval": {
              "mode": "HYBRID",
              "topN": 6,
              "threshold": 0.6,
              "noRefStrategy": "REFUSE",
              "multiHopEnabled": true,
              "maxHops": 2
            },
            "multiTurn": { "enabled": true, "maxRounds": 4 },
            "thinkingEnabled": true,
            "prologue": "合规问题我只给依据与风险等级，最终判断以合规部门意见为准。",
            "quickQuestions": [
              "客户数据能不能给第三方？",
              "这个营销活动需要提前报备吗？",
              "员工离职后账号权限怎么处理？"
            ]
          },
          "dependencies": []
        }'::jsonb,
       true
where not exists (
  select 1 from as_template where name = '合规风控问答（合规通用）' and deleted_at is null
);

insert into as_template (name, description, category, source_app_id, payload, builtin)
select '指标口径问答（分析通用）',
       '把指标定义、统计范围、取数口径讲清楚，避免同名不同义。建出来后请补模型与「指标字典/数据规范」知识库。',
       '分析',
       null,
       '{
          "format": "assistant-bundle@1",
          "exportedAt": "2026-11-19T00:00:00Z",
          "includesDocuments": false,
          "config": {
            "name": "指标口径问答",
            "icon": "📐",
            "description": "讲清指标怎么算、算哪些、和哪个数容易混",
            "type": "SIMPLE",
            "modelId": null,
            "kbIds": [],
            "rolePrompt": "你是数据指标口径的答疑助手。回答必须包含四段：① 定义（一句话说清这个数在算什么）；② 统计范围（时间窗、主体范围、去重规则、是否含测试数据）；③ 计算方式或取数来源（表名/字段/过滤条件，只在资料里有时才写）；④ 最容易混的邻近指标，以及两者的差异。资料里没有明确定义的，直接说「指标字典未收录该口径」，不要用通用理解代替——口径错了后面所有看数都是错的。",
            "promptIds": [],
            "retrieval": {
              "mode": "HYBRID",
              "topN": 6,
              "threshold": 0.6,
              "noRefStrategy": "REFUSE",
              "multiHopEnabled": true,
              "maxHops": 2
            },
            "multiTurn": { "enabled": true, "maxRounds": 4 },
            "thinkingEnabled": true,
            "prologue": "问指标怎么算、统计范围、和哪个数容易混。口径我只按指标字典说。",
            "quickQuestions": [
              "活跃用户是怎么定义的？",
              "提问数包含哪些消息？",
              "满意度和好评率是一回事吗？"
            ]
          },
          "dependencies": []
        }'::jsonb,
       true
where not exists (
  select 1 from as_template where name = '指标口径问答（分析通用）' and deleted_at is null
);
