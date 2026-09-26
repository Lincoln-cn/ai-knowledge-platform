# 09 · 中间件与 Spring 模块选型

> **为什么单独一篇**：`00 §三` 与 `01 §3.1` 已经定过技术栈，但那是**倾向清单**；本项目的中间件历史包袱很重
> （前身系统 8 个中间件），所以"**哪些真的不需要**"比"哪些需要"更值钱。本篇逐项分析，并回答一个问题：
> **有哪些 Spring 官方/成熟模块可以替掉我们原本打算自己写的东西。**
>
> **判据**（用户要求）：优先 Spring 官方与成熟模块，**少自研**。
> **查证标注**：本篇凡标 ★ 的结论，都是本轮**读过官方文档**得到的（不是凭记忆）；未标的是选型判断。

---

## 一、总原则：什么时候才该引入一个中间件

四条，**全部成立才引**：

| # | 判据 | 反过来说 |
|---|---|---|
| 1 | **单库真的做不到** | 能用现有 PG 表达清楚的能力（JSON、全文、向量、锁、队列、幂等），**不加组件** |
| 2 | **不引会造成架构性问题**（不是"性能不够好看"） | 慢一点可以优化；**"做不到"才引组件** |
| 3 | **它是运维可承受的** | 每多一个组件，就多一份部署/备份/监控/升级/排障 |
| 4 | **现在就需要**——不是"将来可能需要" | 预留的是**接口**，不是中间件实例 |

> **前身系统的教训正是这条的反面**：8 个中间件（PG/Redis/Nacos/RabbitMQ/Neo4j/MinIO/Sentinel/Seata）
> 里，**没有一个是 MVP 非要不可的**。其中 Neo4j 只用来存一份关系、Nacos 只用来下配置、
> Seata 在单体里根本用不上。

---

## 二、中间件总表（结论）

| # | 关注点 | 选 | 不引 | 一句话理由 |
|---|---|---|---|---|
| 1 | 关系数据库 | **PostgreSQL 16+** | — | 唯一必需的存储 |
| 2 | 向量检索 | **pgvector（同库）** | Milvus / Qdrant / ES / Neo4j | 与业务数据**同事务**，少一个组件（ADR-02） |
| 3 | 缓存 | **Caffeine（进程内）** | Redis | 单体单实例，进程内更快也更简单 |
| 4 | 消息 / 异步 | **Modulith 事务事件 + 出站事件表** | RabbitMQ / Kafka | 单体不需要 MQ（ADR-03） |
| 5 | 分布式锁 | **PG 行锁 / advisory lock** | Redisson / Seata | 单实例不需要分布式锁；多实例用 PG 就够 |
| 6 | 全文检索 | **PG 全文（`tsvector`）** | Elasticsearch | 起步量级用不着独立检索引擎 |
| 7 | 对象存储 | **本地磁盘 + `FileStorePort`** | MinIO 起步就上 | 端口留好，换 S3/MinIO 零改动 |
| 8 | 配置中心 | **Spring Boot 配置 + 环境变量** | Nacos / Apollo | 单体 + 容器，配置随镜像/环境变量走 |
| 9 | 注册中心 | **不引** | Nacos / Eureka | 单体没有"服务发现"这件事 |
| 10 | 限流 / 熔断 | **Resilience4j** | Sentinel | 模型服务是关键外部依赖，需要一个熔断器 |
| 11 | 调度 | **Spring `TaskScheduler`**（多实例加 ShedLock） | Quartz / XXL-Job | 调度需求很轻（重试、补偿、日聚合） |
| 12 | 可观测 | **Actuator + Micrometer**（Prometheus / OTLP） | ELK 全家桶 | 指标与追踪先行；日志先结构化输出到 stdout |
| 13 | 模型服务 | **Ollama（内网）+ 可换外部 API** | — | 由 Spring AI 抽象隔离 |
| 14 | 网关 / 静态托管 | **Nginx / Caddy** | Spring Cloud Gateway | 单体不需要网关；前端是静态产物 |
| 15 | 会话状态 | **无状态 JWT** | Redis / Spring Session | 但**刷新令牌轮换要落 PG**（见 §三.15） |

### 最终部署形态（容器数）

```
postgres(pgvector)  +  应用  +  模型服务
```

**3 个进程**（前身系统约 32 个容器 / 17 个微服务）。这不是"省"，是**每多一个组件都要有人会修它**。

---

## 三、中间件逐项分析

### 3.1 PostgreSQL 16+（唯一必需的存储）

**为什么是它**：关系数据 + JSON 元数据 + 全文检索 + 向量检索 + 行锁 + 幂等表 + 出站事件表 —— 一张库全包。
**版本**：16+（`pgvector` 与更好的 JSON 路径能力）。
**扩展**：`vector`、`hstore`、`uuid-ossp`（★ 官方文档明确列的三个，见 §四.5）。

### 3.2 向量检索：pgvector 同库（改向：**不手写 SQL**）

**原设计**（`backend/README.md:101`）："JdbcTemplate（向量检索**手写 SQL**）—— 向量相似度查询用 ORM 表达不划算"。
**本轮改向**（★ 官方文档查证）：**用 Spring AI 的 `PgVectorStore`**，它内部就是 JdbcTemplate，
而且已经把我们要手写的东西都做好了：

| 我们原本要手写 | Spring AI 现成 |
|---|---|
| `CREATE EXTENSION` + 建表 + 建 HNSW 索引 | `initializeSchema=true` 自动装扩展、建表、建索引 |
| `ORDER BY embedding <=> ? LIMIT k` | `SearchRequest.builder().query(..).topK(k).similarityThreshold(t)` |
| 元数据过滤（只搜某知识库） | **可移植的 filter DSL**：`filterExpression("kbId == 'kb1'")` 或 `FilterExpressionBuilder`，**自动转成 PG JSON path** |
| 维度换模型时的表重建 | `dimensions` 配置 + 明确的"换维度必须重建表"提示 |
| 需要原生 SQL 时怎么办 | `getNativeClient()` 返回 `Optional<JdbcTemplate>` —— **逃生口也在** |

**为什么这条重要**：`vectorindex` 模块从"手写向量 SQL 的基础设施模块"变成**薄封装**，
风险（维度/索引/过滤注入/分页）全部由官方承担。

**什么时候才换独立向量库**：单表向量数到千万级、或需要在多个 PG 之外共享索引时。
**现在不引**——`vectorindex` 的 `api` 包已经把调用方与实现隔开，到时候换实现不动 `ingestion`/`retrieval`。

### 3.3 缓存：Caffeine（进程内），不引 Redis

**需求实际是什么**：① 模型列表/能力等**低频变化**的读数据 ② 权限点与角色（登录时已下发，服务端判定也要读）。
**为什么不用 Redis**：单体单实例下，进程内缓存的**延迟更低、无序列化、无网络故障面**。
**什么时候换 Redis**：应用多实例部署、且需要跨实例一致失效时。**那时再加**——
用 Spring 官方的 `Cache` 抽象，换实现是加一个 starter + 改配置，**业务代码零改动**。

### 3.4 消息 / 异步：不引 MQ（ADR-03 确认）

**为什么**：本项目的异步都是"**事务提交后通知**"，没有跨系统、没有削峰、没有多消费者组。
**用什么**：Spring Modulith 的事务事件 + **事件发布注册表（出站事件表）**——★ 这一项 `01 §五 #4` 已定：
用 Modulith 的注册表把事件落库，进程崩溃后未处理的事件可重发，**这就是 MQ 的最小可用替代**。
**什么时候换 MQ**：拆服务、或事件要跨进程/跨系统投递时。事件的命名与载荷已经按"事实"设计（过去式），
**迁移时不用改事件语义**，只换投递层。

### 3.5 分布式锁：单实例不需要；多实例用 PG

**现状**：单实例 ⇒ 进程内锁（`ReentrantLock`）就够，**甚至很多时候连锁都不需要**（靠唯一约束 + 幂等）。
**多实例时用什么**：PG 的 `SELECT ... FOR UPDATE` / advisory lock，或 ShedLock（见 §三.11）。
**不引 Redisson / Seata**：前者要 Redis，后者（分布式事务）在单体里没有用武之地——**同一个库、同一个事务**。

### 3.6 全文检索：PG `tsvector` 起步

混合检索的第二路（全文）用 PG 的 `tsvector` + GIN 索引即可。
**中文分词**：PG 需要 `zhparser` 或 `pg_jieba` 扩展——**这是唯一需要单独确认的扩展**（标为待办，见 §八）。
**什么时候换 ES/OpenSearch**：语料上百万段、且需要相关性调参与聚合分析时。`retrieval` 的三模式接口不变。

### 3.7 对象存储：本地磁盘 + 端口（不引 MinIO）

`FileStorePort`（`platform`）+ 本地磁盘适配器。**单机验证完全够用**，接口留好之后换 S3/MinIO **零改动调用方**。
**纪律**（`03` 机制八）：**先存文件、后落业务记录**。

### 3.8 配置中心：不引 Nacos

单体 + 容器：配置来自 `application.yml` + 环境变量（12-factor）。**敏感值**走环境变量/Secret。
**什么时候引**：多实例且要**不重启改配置**时（那时 Spring Cloud Config 或 K8s ConfigMap 都行）。

### 3.9 注册中心：不引

单体没有服务发现。**拆服务那天再说**——而那一天，Modulith 的模块边界就是现成的拆分线。

### 3.10 限流 / 熔断：Resilience4j

**需要它的唯一理由**：**模型服务是外部依赖，且会慢、会失败**。没有熔断器时，一个卡住的模型调用会
**把虚拟线程与连接池吃满**，连带把整个应用拖死。
**放哪**：`modelcatalog` 的适配器层（`03` 机制四的"端口+适配器"正好是它的位置）。
**不引 Sentinel**：体量与心智成本远超需求；Resilience4j 是库、不是平台。

### 3.11 调度：Spring `TaskScheduler`（+ 多实例 ShedLock）

**实际需求**：入库失败重试的补偿扫描、日聚合、出站事件表的重发扫描。
**用**：`@Scheduled` / `TaskScheduler`（官方）。
**多实例部署时**加 **ShedLock**（社区但成熟，基于 PG 表实现"同一时刻只有一个实例执行"）。
**不引 Quartz / XXL-Job**：没有"集群调度 + 可视化运维"的需求。

### 3.12 可观测：Actuator + Micrometer

| 层 | 选 |
|---|---|
| 指标 | **Micrometer** → Prometheus |
| 追踪 | **Micrometer Tracing** → OTLP（`traceId` **跨异步传递**，`03` 机制七） |
| 健康 | **Actuator**（`/health` 给容器探针用） |
| 日志 | 结构化 JSON 到 stdout（由容器收集），**起步不引 ELK** |
| 业务指标 | 首字延迟、检索耗时、入库吞吐、结构化输出失败率（`analytics/quality` 读的就是它们） |

### 3.13 模型服务

**内网 Ollama**（开发与私有化部署），通过 Spring AI 的 `ChatModel` / `EmbeddingModel` 抽象接入，
**可换外部 API 而不动业务代码**。
**待办**：`backend/README.md §「开始之前必须先拿到的东西」` 已经列了——本地模型服务（5 个 Ollama 模型约 9GB）。

### 3.14 网关 / 静态托管

**不引 Spring Cloud Gateway**：单体不需要网关。反向代理 + 前端静态产物交给 **Nginx / Caddy**
（同时承担 TLS、压缩、SSE 的缓冲关闭——**这一条对 SSE 很关键**，见 §六.3）。

### 3.15 会话状态：无状态 JWT（但刷新令牌要落库）

**不用 Redis 存会话**：JWT 无状态，前端按 401 静默刷新实现（`03` §6.1）。
**但有一件事必须落库**：**刷新令牌轮换**——刷新时换发新 refresh，就得记录"哪些 refresh 已用过/已作废"，
否则旧 refresh 一直能用（`03` §6.1 说的"防止旧刷新令牌被长期盗用"就落空了）。
**落 PG**（一张表），不引 Redis。

---

## 四、Spring 模块选型

### 4.0 总表

| 模块 / 库 | 用? | 替掉了什么自研 |
|---|---|---|
| `spring-boot-starter-web`（**MVC**） | ✅ | —（ADR-01：MVC + 虚拟线程，**不用 WebFlux**） |
| `spring-modulith-starter-jpa` | ✅ | 模块边界校验、**出站事件表**、架构文档生成 |
| `spring-boot-starter-security` + `oauth2-resource-server` + `oauth2-jose` | ✅ | JWT 验签/解析、过滤链、密码编码（见 `08 §4.2`） |
| `spring-boot-starter-data-jpa` | ✅ | 聚合读写、`AuditorAware` 审计、`Specification` 数据范围过滤 |
| `spring-ai-starter-model-ollama`（+ 可选 openai 等） | ✅ | 模型调用、流式、**结构化输出** |
| **`spring-ai-starter-vector-store-pgvector`** | ✅ **★改向** | **向量表/索引/相似度查询/元数据过滤**（原本打算手写 SQL） |
| `spring-boot-starter-validation` | ✅ | 请求校验（Jakarta Validation） |
| `spring-boot-starter-actuator` + `micrometer-registry-prometheus` + `micrometer-tracing-bridge-otel` | ✅ | 指标、追踪、健康探针 |
| `spring-boot-starter-cache` + `caffeine` | ✅ | 进程内缓存，换 Redis 时业务零改动 |
| **`spring-retry`** | ✅ | `@Retryable` 重试（模型调用、解析失败） |
| **`resilience4j-spring-boot3`**（或 Spring Cloud Circuit Breaker 抽象） | ✅ | 熔断/限流/舱壁 |
| `flyway-core` | ✅ | 数据库迁移（`01`/`02` 已定） |
| `spring-boot-testcontainers` + `testcontainers-postgresql`（`@ServiceConnection`） | ✅ | **集成测试用真 PG**，不 mock 数据库 |
| `spring-boot-docker-compose` | ✅ | 本地开发自动起 PG（dev services） |
| `spring-restdocs` | ✅ | **从测试生成接口文档** —— 与前端契约对账、防漂移 |
| `shedlock-spring` | ⏳ 多实例时 | 定时任务"同一时刻只跑一个实例" |
| `spring-boot-starter-webflux` | ❌ | 已由 ADR-01 否掉 |
| Spring Cloud（Gateway / Config / Registry / OpenFeign） | ❌ | 单体不需要 |
| `spring-boot-starter-batch` | ❌ | 见 §4.2 |
| `spring-statemachine` | ❌ | 见 §4.2 |
| `spring-integration` | ❌ | 不引 MQ 就不需要它 |
| `spring-session` / Redis | ❌ | 无状态 JWT |
| `spring-data-envers` | ❌ | 版本历史已有显式版本聚合 |
| Spring Authorization Server | ❌（暂） | 见 §4.3 |

### 4.1 用（以及它替掉了什么）

**① `spring-modulith-starter-jpa`** —— 一个 starter 同时给到三件我们本来要自己做/自己维护的东西：
模块边界校验（`ApplicationModules.verify()`）· **事件发布注册表（出站事件表，`01 §五 #4` 的落地）** ·
架构文档生成（构建期产出组件图，**文档与实现不会脱节**）。

**② Spring Security 全套（`08 §4.2` 已详述）** —— 验签、解析、密码、401/403、方法级授权全部官方。

**③ `spring-ai-starter-vector-store-pgvector`** —— ★ 详见 §五.1，**这是本轮最值钱的一条改向**。

**④ `spring-retry` + `resilience4j`** —— 原设计里"失败与重试"是 `ingestion` 自己写的逻辑。
**分层**：`spring-retry` 管"这一次调用重试几次"（瞬时故障），`resilience4j` 管"这个依赖整体还能不能打"（熔断）。
两者都不是自研。

**⑤ `spring-boot-testcontainers` + `@ServiceConnection`** —— 我们**几乎全部的复杂度都在数据库上**
（向量、JSON 路径、全文、事务事件、行锁）。**用 H2 或 mock 测这些等于没测**。
官方 `@ServiceConnection` 让"起一个真 PG 容器并接上"只需一个注解。

**⑥ `spring-restdocs`** —— 前端已经有一份**冻结的契约**（`frontend/src/api/**`）。
后端如果只靠人写文档，两边一定会漂移。REST Docs 从测试生成，**改接口不改测试 ⇒ 文档不会更新**，
这个耦合正是我们要的。

### 4.2 明确不用（以及为什么）

| 不用 | 为什么 | 什么时候才该用 |
|---|---|---|
| **Spring Batch** | 入库流水线是**事件驱动的小任务**（解析一个文档、向量化一批分段），不是"读—处理—写"的批作业；Batch 的 Job/Step/Chunk 模型在这里只会多一层概念 | 出现"每月对全量语料重跑一次"这类**真正的批作业**时 |
| **Spring Statemachine** | 文档七态是**简单枚举流转**（守卫 + 事件），`03` 机制三已用显式状态守卫表达清楚。Statemachine 是重框架，会为一个枚举引入一层状态机配置与调试面 | 状态与转移复杂到"用表表达不清楚"时 |
| **Spring Integration** | 它是"企业集成模式"的框架（channel / router / transformer）—— **前提是有多个系统要接**。我们既没有 MQ，也没有外部系统 | 引入 MQ 或要接多个外部系统时 |
| **Spring Cloud 全家桶** | 单体 + 一个进程：没有服务发现、没有网关、没有分布式配置的必要 | 真的拆成多服务时 |
| **Spring Session / Redis** | 无状态 JWT；刷新令牌轮换落 PG 就够 | 需要"服务端强制踢下线"、"在线用户列表"这类**会话可见性**能力时 |
| **Spring Data Envers** | 需要历史的只有"应用发布版本"与"流程版本"，它们**已经是显式聚合**（`WorkflowVersion` / 发布记录），审计范围明确 | 需要"任意实体任意字段的自动历史"时 |
| **WebFlux** | ADR-01 已定：IO 密集用虚拟线程，**不用响应式**（可维护性优先） | 不打算改 |

### 4.3 可以晚点用（记下引入时机，不预留代码）

| 模块 | 引入时机 | 触发信号 |
|---|---|---|
| **Spring Authorization Server** | 若要**把 API 开放给第三方**（而不是只有自己的前端） | 需要 OAuth2 授权码/客户端凭证时。**注意**：它也能替掉我们现在唯一要自写的"刷新令牌轮换"（`08 §4.2.3`）—— 所以如果 M1 就决定要开放，**在这里反而是少自研** |
| `shedlock-spring` | 应用多实例部署时 | 副本数 > 1 且用了定时任务 |
| Spring Cloud Config / K8s ConfigMap | 要"不重启改配置"时 | 配置变更频率上来了 |
| Micrometer → 完整可观测栈 | 单机排障不够用时 | 需要跨服务追踪 / 日志检索 |
| 独立向量库（Milvus/Qdrant） | 单表向量到千万级，或索引要跨库共享 | `retrieval` 的 P95 超出预算 |

---

## 五、★ 本轮查证得到的两处改向

> 这两条都来自**实际读官方文档**（Spring AI 2.0.1 Reference），不是凭印象。它们直接减少自研量。

### 5.1 向量检索：**不手写 SQL**，用 `PgVectorStore`

**原设计**（`backend/README.md:101`）：`JdbcTemplate`（向量检索**手写 SQL**），理由是"向量相似度查询用 ORM 表达不划算"。

**这条判断是对的，但结论错了** —— 不划算的是 **ORM**，而我们本来要手写的正是**官方已经写好并测试过的那一层**：

| 关注点 | `PgVectorStore` 提供（★ 查证） |
|---|---|
| 扩展与表 | `initializeSchema=true` 自动 `CREATE EXTENSION vector / hstore / uuid-ossp` 并建表建索引 |
| 索引 | `index-type`: `NONE` / `IVFFlat` / `HNSW`（默认 HNSW）；★ 官方提示 **HNSW 索引上限 2000 维** |
| 距离 | `distance-type`：`COSINE_DISTANCE`（默认）/ `EUCLIDEAN_DISTANCE` / `NEGATIVE_INNER_PRODUCT` |
| 维度 | `dimensions`；★ 官方明确：**改维度要重建表** |
| 查询 | `SearchRequest.builder().query(q).topK(k).similarityThreshold(t)` |
| **元数据过滤** | **可移植的过滤表达式**（文本 DSL 或 `FilterExpressionBuilder`）★ **自动转成 PG JSON path** —— "只在本知识库内检索"就靠它 |
| 批量写入 | `max-document-batch-size`（默认 10000） |
| **逃生口** | `getNativeClient()` → `Optional<JdbcTemplate>` ★ 真需要原生 SQL 时不必绕过它 |

⇒ **`vectorindex` 从"手写 SQL 的基础设施模块"降级为薄封装**（装配 `VectorStore` + 把知识库域概念映射成 metadata 过滤）。
**风险（维度/索引/过滤注入/批量）由官方承担**，我们只负责正确的**用法**。

**注意**：`filterExpression` 是**字符串**——拼它时必须走 `FilterExpressionBuilder`（结构化构造），
**不要字符串拼接用户输入**（那是注入面）。

### 5.2 结构化输出：**不自研"四层防御"**，用官方转换器

**原设计**（`03` 机制五）：自研"四层防御 + 健壮解析"来对付模型输出里的 JSON 污染。

**★ 查证结果**：官方已经把绝大部分做好了：

| 需求 | 官方提供 |
|---|---|
| 让模型按结构输出 | `ChatClient....call().entity(SomeRecord.class)`（高层） / `BeanOutputConverter<T>`（低层） |
| 从 Java 类生成 JSON Schema 指令 | `BeanOutputConverter` 自动生成 `DRAFT_2020_12` schema 并拼进 prompt |
| 复杂泛型 | `ParameterizedTypeReference` |
| 非 Bean 形状 | `MapOutputConverter` / `ListOutputConverter`（逗号分隔） |
| **schema 校验与自纠** | ★ 官方的 **schema validation & self-correction**（`validateSchema()`） |
| **让模型侧原生结构化输出** | ★ 官方的 **provider-native structured output**（`useProviderStructuredOutput()`） |
| **markdown 代码围栏包裹的 JSON** | ★ 官方文档**自己给了例子**：写一个 `StructuredOutputConverter` 去掉围栏后委托 `BeanOutputConverter`；且**只要委托 `getJsonSchema()`**，上面两个可靠性开关就仍然生效 |
| 属性顺序 | `@JsonPropertyOrder` |

⇒ **我们的"四层防御"缩到一层**：写一个**宽松 JSON 转换器**（去围栏/取第一个完整 JSON 对象）+ 打开 `validateSchema()`。
**不要再自研一整套解析与纠错**。

**仍由我们负责的**：探测**失败时的可观测**（`03` 机制五的"失败可观测"）与**准入拦截**
（思考模式强制开启会污染结构化输出 → M-I5）——那是业务规则，官方不管。

---

## 六、部署形态与三个环境细节

### 6.1 容器清单

```
postgres:16 + pgvector          ← 唯一中间件（业务数据 + 向量 + 全文 + 锁 + 幂等 + 出站事件）
app (spring boot, 虚拟线程)      ← 单体
ollama (模型服务)                ← 模型；可换外部 API
nginx / caddy                   ← TLS + 前端静态 + 反向代理
```

### 6.2 本地开发（官方 dev services）

`spring-boot-docker-compose` 让本地跑起 PG（★ 官方推荐姿势）；集成测试用 `@ServiceConnection` 起临时容器。

### 6.3 SSE 与反向代理（**很容易踩**）

**Nginx / Caddy 必须关掉对 SSE 的响应缓冲**（`proxy_buffering off` / `X-Accel-Buffering: no`）。
否则流式回答会被代理**攒成一坨再吐**——前端会"卡住不动然后一次性出现全部内容"，
而**后端日志里一切正常**。这类问题会浪费很多时间去找后端，值得写进部署清单。

---

## 七、与既有决策的对账

| 项 | 原状态 | 本篇结论 |
|---|---|---|
| 中间件数量 | `backend/README.md`：PG(+pgvector) + 模型服务 | ✅ **确认**，并补上"缓存用 Caffeine、限流用 Resilience4j"两处 |
| 事件不引 MQ | ADR-03 | ✅ **确认**（用 Modulith 出站事件表） |
| **向量检索实现** | **手写 SQL（JdbcTemplate）** | ⚠️ **改向**：用 `PgVectorStore`（§5.1） |
| **结构化输出** | **自研四层防御** | ⚠️ **改向**：官方转换器 + `validateSchema()`，只自写宽松解析（§5.2） |
| 鉴权 | `01 §3.1`：Spring Security + JWT | ✅ 确认，细节见 `08 §4.2`（责任链、不自研） |
| 对象存储 | 端口 + 本地磁盘 | ✅ 确认 |
| 持久化 | JPA + JdbcTemplate 手写向量 SQL | ⚠️ 前半确认；后半由 §5.1 取代 |
| 构建 | Maven（`01 §3.1`） | ✅ 确认 |

---

## 八、待拍板与待办

| # | 事项 | 建议 |
|---|---|---|
| 1 | **中文全文检索的分词扩展** | 需确认选 `zhparser` 或 `pg_jieba`（§3.6）——**这是唯一一个需要额外确认的 PG 扩展**，M2 前定 |
| 2 | 是否 M1 就引入 **Spring Authorization Server** | 若计划把 API 开放给第三方 ⇒ **引入它反而更少自研**（它替掉我们唯一要自写的刷新令牌轮换）；若只服务自家前端 ⇒ 按 `08 §4.2.3` 写薄层 |
| 3 | Resilience4j 的熔断阈值 | M1 定（模型调用是第一个外部依赖），先用保守默认值 |
| 4 | 日志：起步是否只到 stdout | 建议**是**（容器收集）；ELK 等到真需要检索日志时再说 |
| 5 | 模型服务的部署形态（Ollama 同机 / 独立机 / 外部 API） | M0 就要定，它影响 `modelcatalog` 的探活与超时策略 |
| 6 | 缓存：是否给"模型列表"加缓存 | 建议**先不加**（本地 Ollama + 单实例，省一次缓存一致性问题；真慢了再加，Spring `Cache` 抽象零改动） |

### 一条贯穿的原则

> **能不加的组件就不加；能不加的代码就别写。**
> 本篇两处改向（向量、结构化输出）都是这条原则的产物 ——
> **它们不是"用框架更时髦"，而是"我们本来要写的那部分，官方已经写完并测过了"。**

