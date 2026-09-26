# backend · 后端服务（规划中）

本目录先占位，尚未开始开发。后缀 `frontend/` 一起构成平台的前后端两侧。

---

## 打算做成什么

依据 `docs/系统设计方案/01-架构设计（DDD·Spring Modulith）.md`：**模块化单体**，不是微服务。

| 维度 | 前身系统 | 本后端 |
|---|---|---|
| 部署进程 | 约 32 个容器 | **3 个平台进程**（PG + 应用 + 模型网关）+ 反向代理 |
| 后端服务 | 17 个独立微服务 | **1 个模块化单体** |
| 框架 | 商业框架（无可源码，私有制品仓） | 自建，无商业构件依赖 |
| 模块边界 | 靠约定与人的自觉 | **框架强制**（CI 跑架构测试） |
| 中间件 | PG / Redis / Nacos / RabbitMQ / Neo4j / MinIO / Sentinel / Seata | **PostgreSQL(+pgvector) + OpenAI 兼容网关（JAiRouter）** |
| 多租户 | 开启（行级隔离） | 不预埋（软关闭，V2.0 再说） |
| 管理后台 | 两套（一套还没有导航入口） | **单后台，角色控制可见性** |
| 事件 | MQ | 事务事件 + 出站事件表（不引 MQ） |

## 计划的模块（MVP，12 个）

**一处对账**：此前两份文档的模块清单不一致 —— `系统设计方案/01-架构设计` 列 11 个（含 `platform` 共享内核与
`vectorindex`），本文件列 10 个（含 `workflow`、不含前两者）。**合并后的正解是 12 个**，理由如下：

- `platform` 是**共享内核**（不属于任何业务域），`vectorindex` 是**基础设施模块**（无对外端点，
  `ingestion` 与 `retrieval` 通过它的 `api` 包调用）—— 两者都必须存在，只是不出现在端点清单里。
- `workflow` 只交付**配置面**（执行引擎在 V1.0），但它是独立业务域，保留。
- `prompts` **不单列模块**：它是 `assistant` 的配置数据。前端为它留了独立契约文件
  （`api/prompts.ts`）不代表后端需要独立模块 —— 写进 `assistant` 的 `api` 包即可。

| 模块 | 职责 | 对应前端契约 |
|---|---|---|
| `platform` | 共享内核：值对象/事件基类、异常、ID、时钟、审计、`FileStorePort`。**无对外端点** | —（跨模块共享） |
| `vectorindex` | 向量表读写、HNSW 索引维护、批量写入与进度回写。**无对外端点** | —（被 `ingestion`/`retrieval` 使用） |
| `identity` | 登录、令牌刷新、用户、角色、权限点 | `api/identity.ts` |
| `modelcatalog` | 模型注册/加载/能力探测（思考三态、维度、上下文窗口） | `api/models.ts` |
| `knowledge` | 知识库、分类、启停 | `api/knowledge.ts` |
| `ingestion` | 上传、解析、切分、向量化、**真实进度**、失败与重试 | `api/ingestion.ts` |
| `retrieval` | 向量 / 全文 / 混合三模式、参数、命中测试 | `api/retrieval.ts` |
| `assistant` | 应用配置、发布前校验、公开凭证、导入导出 | `api/assistants.ts` |
| `conversation` | 流式问答、引用、多轮、停止/重生成 | `api/conversations.ts` + `api/stream.ts` |
| `feedback` | 赞踩、处理闭环（状态流转、指派、沉淀） | `api/feedback.ts` |
| `analytics` | 用量指标、趋势、质量观测 | `api/analytics.ts` |
| **`workflow`** | **流程定义存取、发布前校验、版本与回滚、画布内调试、流程记录；V1.0 补执行引擎** | `api/workflow.ts` |

**`workflow` 的特别说明**：它与其它模块的交付节奏不同 —— **配置界面已进 MVP，执行引擎仍是 V1.0**。
理由是"难的是执行引擎，不是配置界面"，而产品需要"简单应用配提示词、复杂应用配流程"两档并存：

| | 状态 | 说明 |
|---|---|---|
| 流程定义格式 | **已冻结** | `frontend/src/api/workflow.ts`。前端按它存取画布；后端按它实现存储与引擎。**改它 = 破坏性变更** |
| 配置界面 | **已在 MVP 内** | 画布 + 节点面板 + 存草稿/校验/发布/版本回滚 + 画布内调试 + 流程记录 |
| 执行引擎 | **V1.0** | 真实按图执行（含条件分支运行时判定）。在它到位前，`Assistant.type = 'workflow'` 的应用只能配置与发布，**不能真正问答** |

节点体系：枚举 17 种，**已实现 7 种**（`start` `base` `ai-chat` `search-dataset` `question` `condition` `reply`），
其余 10 种（函数库/函数/重排/应用调用/文档提取/图片理解/表单/TTS/STT/图片生成）在契约里**保留枚举并标 `implemented: false`**，
界面上标注「规划中」且不可拖拽 —— 保留枚举是为了让规划可见，不可拖拽是为了不让界面暗示一个不存在的能力。
**不要**把这 10 种当成未完成的 bug 去补实现，它们不在 V1.0 的引擎范围内。

`Assistant.type`（`simple` / `workflow`）**创建时选定、之后不可更改**；改类型等于换了应用，应新建。

后续版本再加：`toolkit`（V1.0 工具调用）、`documentgen`（V1.1）、`crawler`（V1.2）。
`workflow` 已从这份"后续"清单里移出 —— 它的**配置面进 MVP**，但**执行引擎仍在 V1.0**，见下。

## 关键路径与工期

**里程碑的唯一权威版本在 [`../docs/ROADMAP.md`](../docs/ROADMAP.md)** —— 那份把三个域（`prompts`、`workflow`、
`vectorindex`）都落到了里程碑上，并给编排配置面补了位（`04` 的里程碑表漏了它，因此总工期 13.5 → 15 周）。
本文件不再重复，避免两处再次漂移。

一句话版：**M0 骨架 1 周 → M1 身份与模型 → M2 知识库与入库 ★ → M3 检索 ★ → M4a 应用配置 / M4b 问答链路 ★
→ M5 反馈与看板 → M5.5 编排配置面 → M6 端到端验证**，关键路径 **M2 → M3 → M4b**。

## 开始之前必须先拿到的东西

已重新评估 —— `需求规格说明书/10` 列的四项里，**三项对本项目不构成阻断**，因为这套后端是**自建、schema 与
权限点都由代码定义**（表命名与 Flyway 见 `docs/系统设计方案/02:500-524`；权限点是代码常量、启动时同步，
见 `frontend/src/constants/permissions.ts`）。它们只在**做旧系统数据迁移**时才需要：

| `10` 的阻断项 | 是否仍阻断 |
|---|---|
| 15 张业务表缺 `CREATE TABLE` | **否** —— 新后端自建 schema，旧表结构只在迁移时需要 |
| 业务菜单树未交付 | **否** —— 导航来自路由 + `meta.permission`，权限点由后端启动时同步，不需要配数据库 |
| 业务字典 / 系统参数缺失 | **否** —— 知识库分类是新系统里的用户自建 CRUD，不是预置字典 |

**真正要提前准备的只有三件**（详见 `docs/ROADMAP.md` §六）：本地模型服务（5 个 Ollama 模型约 9GB）、
**测试语料 ≥50 份 + 问题集 ≥50 个**（业务人员参与，周期最长，**M2 期间就要启动**）、
以及 M0 必须交付的两份技术结论（思考三态取值 / Spring AI 能否下发思考开关）。


## 技术选型（★ 已定 · 2026-09 收口）

> **唯一真源是 [`../docs/系统设计方案/09-中间件与-Spring-模块选型.md`](../docs/系统设计方案/09-中间件与-Spring-模块选型.md)**：
> 它的 **§十** 是全量收口表（每个能力都有名字，含"明确不需要"的证据），**§四** 是 Boot 4 的 starter 坐标与破坏性变更，
> **§九** 是向量存储的硬约束。**本节只列结论，避免两处漂移。**

| 项 | 选型 | 说明 |
|---|---|---|
| 语言 | **Java 25**（LTS，Eclipse Temurin 25） | 虚拟线程是本次选型的支点；Framework 7 的生产推荐版本 |
| 框架 | **Spring Boot 4.1.x**（Framework 7.x / Security 7.x） | ★ Boot 4 做过一次 **starter 大改名**，坐标以 `09 §4.0` 为准（照 Boot 3 写法会编译不过） |
| Web | **Spring MVC + 虚拟线程**（`spring-boot-starter-webmvc`） | **不用 WebFlux** —— 决定性理由不是流式而是**持久化**（选它等于放弃 JPA），见 `09 §4.5` |
| 模块化 | **Spring Modulith 2.1.x** | 模块边界可被框架校验，不靠自觉；还给出站事件表与架构文档生成 |
| 持久化 | **Spring Data JPA**（Hibernate 7.4.x）+ **Flyway**（每模块一个 migration 目录） | **向量表也由 Flyway 建**（关掉框架的自动建表，见 `09 §9.4`） |
| 数据库 | **PostgreSQL 16 + pgvector**，扩展**只要 `vector`** | 关系数据与向量同库，少一个组件（ADR-02） |
| 向量检索 | **Spring AI 的 `PgVectorStore`**（**不手写 SQL**） | ★ 但它的**列名硬编码、维度是表级** ⇒ **按维度分表 + `vectorindex` 路由**，见 `09 §九` |
| 模型接入 | **Spring AI 2.0.1** + **OpenAI 兼容网关（JAiRouter）** | **不直连推理服务**：`spring-ai-starter-model-openai` 的 `base-url` 指向网关即可；负载均衡/熔断/限流由网关承担。职责边界见 `09 §3.13` |
| 鉴权 | **Spring Security 责任链**（`SecurityFilterChain` + `oauth2ResourceServer().jwt()`）+ JWT 双令牌 | **不自研安全组件**：验签、解析、密码编码、401/403、方法级授权全用官方；前端 `lib/http.ts` 已按这个行为实现（`08 §4.2`）。Security 7 的 API 变化见 `09 §4.4` |
| 数据范围 | `OwnedResource`（`platform`）+ `AccessScopePort`（**唯一实现放 `identity`**） | 列表按 `dataScope` 拼进查询；看不见的资源返 **404**（`08 §4.4`） |
| **文档解析** | **`tika-core`（MIME 嗅探）+ Apache PDFBox 3 + Apache POI 5.5.1** | MVP 支持 `.pdf / .docx / .doc / .md / .txt`；**不做 OCR、不做表格抽取**；理由见 `09 §10.2 #1` |
| **中文全文检索** | 应用侧 **2-gram** 切分 + PG `simple` 配置的 tsvector | **不装 PG 扩展**（`zhparser`/`pg_jieba` 都要手工编译，留作升级路径），见 `09 §10.2 #3` |
| 构建 / 质量 | **Maven 3.9.16** · Spotless + google-java-format · Checkstyle 14.1.0 · SpotBugs 4.10.4 | ★ Maven 4.x 仍是 RC，不用于生产（`09 §10.1 E`） |
| 测试 | JUnit 5 + **Testcontainers 2.x**（起**真 PG**）+ REST Docs + **自研契约漂移检测** | 不用 H2/mock 测数据库 —— 我们的复杂度几乎全在数据库上（`09 §10.2 #8`） |
| CI | **GitHub Actions**（`setup-java` + temurin 25） | 前端 11 个脚本 + 后端四类闸门 |
| 可观测 | Actuator + Micrometer（Prometheus / OTLP）+ **Boot 原生结构化 JSON 日志** | `logging.structured.format.console=ecs` —— ★ 3.4 起内置，**不引第三方 encoder** |

---

**注意**：前身后端 49% 的文件带商业框架的授权头，且框架源码完全不在仓库里。**本后端的做法是自建，不继承它的代码**；已有代码只用于理解业务语义。
