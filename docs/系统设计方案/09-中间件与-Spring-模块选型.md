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
| 10 | 限流 / 熔断 | **分层**：上游的负载均衡/限流/熔断**交给网关**（JAiRouter），我们只留"**超时 + 一次重试**"（Resilience4j） | Sentinel | 两层熔断会互相干扰、故障时分不清是谁断的（§3.10） |
| 11 | 调度 | **Spring `TaskScheduler`**（多实例加 ShedLock） | Quartz / XXL-Job | 调度需求很轻（重试、补偿、日聚合） |
| 12 | 可观测 | **Actuator + Micrometer**（Prometheus / OTLP） | ELK 全家桶 | 指标与追踪先行；日志先结构化输出到 stdout |
| 13 | 模型服务 | **OpenAI 兼容网关（JAiRouter）** —— **不直连推理服务** | — | 官方的 `spring-ai-starter-model-openai` 把 `base-url` 指向网关即可，零自研（§3.13） |
| 14 | 网关 / 静态托管 | **Nginx / Caddy** | Spring Cloud Gateway | 单体不需要网关；前端是静态产物 |
| 15 | 会话状态 | **无状态 JWT** | Redis / Spring Session | 但**刷新令牌轮换要落 PG**（见 §三.15） |

### 最终部署形态（容器数）

```
postgres(pgvector)  +  应用  +  模型网关(JAiRouter)
```

**3 个进程**（前身系统约 32 个容器 / 17 个微服务）。这不是"省"，是**每多一个组件都要有人会修它**。

> **口径说明**（★ 因为 §3.13 改成了经网关接入）：这 3 个是**平台自己的进程**。
> **上游推理服务（Ollama / vLLM / 外部 API）由网关对接，不算平台的进程** ——
> 它们可能是内网已有实例、或网关自带的 adapter 指向的对象。
> 平台**只认一个 OpenAI 兼容入口**，这正是"不直连推理服务"的收益：**上游换实现，平台零改动**。

---

## 三、中间件逐项分析

### 3.1 PostgreSQL 16+（唯一必需的存储）

**为什么是它**：关系数据 + JSON 元数据 + 全文检索 + 向量检索 + 行锁 + 幂等表 + 出站事件表 —— 一张库全包。
**版本**：16+（`pgvector` 与更好的 JSON 路径能力）。
**扩展**：★ **只需要 `vector` 一个** —— `vector` / `hstore` / `uuid-ossp` 是 `PgVectorStore` 的 `initializeSchema` 会建的三个，
而我们**关掉它、表由 Flyway 建**（§9.2 / §9.4）：`hstore` 与 `uuid-ossp` 都用不上（主键由应用侧生成）。

### 3.2 向量检索：pgvector 同库（改向：**不手写 SQL**）

**原设计**（`backend/README.md` 的技术选型表，**本轮收口时该表已改写**）："JdbcTemplate（向量检索**手写 SQL**）—— 向量相似度查询用 ORM 表达不划算"。
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
**中文分词**（★ 本轮已定，见 §10.2 #3）：**不装 PG 扩展** —— 用**应用侧 2-gram 切分**后写入 `simple` 配置的 tsvector。
为什么不能就这么用 `simple`：`to_tsvector('simple', …)` 对中文会把**整段当成一个 token**，这一路等于检索不到东西。
`zhparser` / `pg_jieba` 都要**手工编译 C 扩展**（PG 镜像就不再是官方镜像）⇒ **留作可逆的升级路径**，不作起步选择。
**什么时候换 ES/OpenSearch**：语料上百万段、且需要相关性调参与聚合分析时。`retrieval` 的三模式接口不变。

### 3.7 对象存储：本地磁盘 + 端口（不引 MinIO）

`FileStorePort`（`platform`）+ 本地磁盘适配器。**单机验证完全够用**，接口留好之后换 S3/MinIO **零改动调用方**。
**纪律**（`03` 机制八）：**先存文件、后落业务记录**。

### 3.8 配置中心：不引 Nacos

单体 + 容器：配置来自 `application.yml` + 环境变量（12-factor）。**敏感值**走环境变量/Secret。
**什么时候引**：多实例且要**不重启改配置**时（那时 Spring Cloud Config 或 K8s ConfigMap 都行）。

### 3.9 注册中心：不引

单体没有服务发现。**拆服务那天再说**——而那一天，Modulith 的模块边界就是现成的拆分线。

### 3.10 限流 / 熔断：**分层**，Resilience4j 只留"超时 + 一次重试"

**修订**（因为 §3.13 决定经 OpenAI 兼容网关接入）：**熔断 / 限流 / 负载均衡交给网关**
（JAiRouter 已有 per-service / per-instance 的 token-bucket 限流与 `failureThreshold` 熔断，
且它离上游更近、信息更全）。**我们不再在应用侧重复实现一层熔断** —— 两层熔断的后果是
**阈值互相干扰、故障时不知道谁断的**。

| 层 | 谁负责 | 管什么 |
|---|---|---|
| 上游实例的负载 / 限流 / 熔断 / 降级 | **JAiRouter** | 哪个实例、还能不能打 |
| 我们到网关这一跳 | **Resilience4j**（只剩 TimeLimiter + 有限 Retry） | **防网关本身卡住**：超时、一次重试（幂等的 GET 与显式幂等的写） |

**为什么还要留超时与重试**：网关是**一个独立进程**，它会重启、会发版、会网络抖动。
没有超时，一个卡住的调用仍然会吃掉虚拟线程与连接池。
**不引 Sentinel**：体量与心智成本远超需求。

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
| 日志 | **结构化 JSON 到 stdout**（容器收集）：★ 用 **Boot 原生** `logging.structured.format.console=ecs`（3.4 起内置，**不引第三方 encoder**，见 §10.2 #4）。起步不引 ELK |
| 业务指标 | 首字延迟、检索耗时、入库吞吐、结构化输出失败率（`analytics/quality` 读的就是它们） |

### 3.13 模型服务：**经 OpenAI 兼容网关**（不直连推理服务）

**决策**（用户拍板）：**不直接对接模型推理服务**，统一经 **OpenAI 兼容协议**接入一个网关。
本项目自己的开源项目 **JAiRouter**（Apache-2.0）正是为这件事存在的。

**★ 本轮实地调研结论**（读的是 `D:\IdeaProjects\model-router` 源码，不是它的宣传）：

| 它的 OpenAI 面 | 端点 |
|---|---|
| 对话 | `POST /v1/chat/completions`（支持流式——它本身是流式代理，有 SSE 与 chunk 转换器） |
| **向量** | **`POST /v1/embeddings`** ★ 意味着**向量化也可以走网关**，不必让 `ingestion` 直连 Ollama |
| 模型清单 | `GET /v1/models` → OpenAI 原生 `{object:"list", data:[{id, object, created, owned_by, service_type, adapter}]}` |
| 其它 | `/v1/rerank` · `/v1/audio/speech` · `/v1/audio/transcriptions` · `/v1/images/generations` · `/v1/images/edits` · **`/v1/messages`（Anthropic 兼容）** |

**它已经做了、我们因此不必做的**（★ 来自 `config/model-services-base.yml` 与源码）：

| 能力 | 它的做法 |
|---|---|
| 负载均衡 | `random` / `round-robin` / **`least-connections`** / `ip-hash` |
| 上游适配器 | `normal` / `gpustack` / `ollama` / `vllm` / `xinference` / `localai` |
| 限流 | per-service / per-instance **token-bucket**（可含 client-ip） |
| 熔断 | `failureThreshold` / `timeout` / `successThreshold` |
| 降级 | `fallback` |
| 运维面 | **配置热重载 + 配置版本与回滚** · RBAC · 审计 · 调用记录 · 可观测 |

**⇒ 这直接改掉两件事**：
1. **我们的熔断器降级**（见 §3.10 的修订）：熔断/限流/负载均衡**交给网关**，它离上游更近、信息更全；
   我们只保留"**超时 + 一次重试**"（防网关本身卡住）。
2. **`modelcatalog` 的职责要重新划**（详见 §5.4）：我们要的是**平台语义的模型资产**，
   不是上游实例——后者是网关的事。

#### 3.13.1 我们怎么接（仍然是官方能力，零自研）

`spring-ai-starter-model-openai` 的 **`base-url` 指向 JAiRouter** 即可 —— 用**官方 starter**，
不自写 HTTP 客户端，也不自写 SSE 解析。

#### 3.13.2 职责边界（**这条必须写清楚，否则两边会重复造**）

| 概念 | 归谁 | 说明 |
|---|---|---|
| 上游实例、base-url、权重、健康 | **JAiRouter** | 我们**不感知**实例 |
| 负载均衡 / 限流 / 熔断 / 降级 / 故障转移 | **JAiRouter** | 我们**不重复实现** |
| 平台侧「模型资产」：名称、类型（对话/向量）、**思考三态**、**工具调用能力**、**维度**、**上下文窗口**、备注、启用态 | **我们（`modelcatalog`）** | 这些是**业务语义**，网关不认识 |
| 「基础模型目录」（建模型时可选底座） | **我们** | `GET /v1/models` 只给 `id/object/created/owned_by/service_type/adapter`，**不含能力元数据** ⇒ 能力那部分必须我们自己维护 |
| 实际推理调用 | 经 **JAiRouter** | 对话 + 向量都走它 |
| 计量（谁问的、哪个应用、多少 token） | **我们（`analytics`）** | 网关的调用记录是**运维视角**；我们的是**业务视角**（按用户/应用聚合） |

#### 3.13.3 认证分层：**不透传平台用户身份**

后端对网关是**一条受信任的服务调用**：用一个**服务账号 / API Key**，而不是把每个平台用户的身份
透传给网关。
**理由**：否则网关的 RBAC 会成为**第二套权限源**，而我们的数据范围（`ownerId`/`dataScope`）是**平台概念**，
网关不可能知道。两层权限各管各的，边界清楚。

#### 3.13.4 `POST /models/:id/load` 的语义：**已定**（★ 本轮拍板）

**它是"平台启用位 + 上游可用性校验"，不是"把权重载入显存"。**

| 为什么不能按字面理解 | 说明 |
|---|---|
| "加载"是**实例级**动作 | Ollama 的"加载"= 把权重读进显存；而经网关后上游可能是 **vLLM / Xinference / GPUStack**（进程启动即常驻，**没有"加载"这个概念**）或**外部 API**（更没）⇒ 语义**跨上游不统一** |
| 但前端契约把四态冻结了 | `ModelStatus = 未加载 \| 加载中 \| 已加载 \| 加载失败`（`api/models.ts:11`），页面有完整的"点加载 → 加载中 → 自动轮询 → 已加载 / 加载失败 + 重新加载"体验（`pages/models/Index.vue`，`smoke_test.py:265-270` 正在断言它） |

**⇒ 四态保留，语义重映射到平台侧**（前端**一行不改**）：

| 契约态 | 平台含义 |
|---|---|
| `未加载` | **平台未启用**该模型资产 |
| `加载中` | 正在做**启用前的可用性校验**（问网关：该基础模型有没有可用实例） |
| `已加载` | **已启用，且上游清单里有它** |
| `加载失败` | **网关不可达**，或上游清单里没有这个基础模型 |

**职责边界**：平台**不负责**"把它载入显存"（那是网关与上游运维的事，§3.13.2 已划）；
平台只声明**意愿**（启用位）+ **校验可用性**。这样对任何上游适配器都成立。

##### ⚠️ 连带审出的一个真缺陷：**"已加载"的模型删不掉，而且没有卸载入口**

- `DELETE /models/:id` 在 `已加载` 时被拒，提示"**请先卸载**或切换到其它模型后再删除"（`mock/handlers/models.ts:161`）
- 但契约里**只有 `load`、没有 `unload`**（`api/models.ts` 的 8 个端点），页面上**也只有"加载 / 重新加载"**
  ⇒ **建模型 → 加载 → 再也删不掉**，而提示语指向一个**不存在的入口**

**修法**（语义定清后自然成立）：删除的拒绝条件应是 **"被应用 / 知识库引用"**（这才是真的引用完整性约束），
**而不是"已加载"**。"停用"只是一次 `UPDATE`，删除时顺带清掉启用位即可。
**前端侧同步改两处**：`mock/handlers/models.ts` 的拒绝条件 + `models/Index.vue` 的失败文案
（现文案"检查模型服务是否可用、**显存**是否足够"在经网关后应是"检查**模型网关**是否可用、该基础模型**是否在上游清单中**"）。

> **顺带记一个可接受的限制（不算缺陷）**：页面只在存在"加载中"时才轮询，
> 所以"已加载"**不会自己变成"加载失败"**（上游实例被驱逐时）。
> 状态实际是**打开页面那一刻的快照**，刷新才更新 —— 记入已知。

#### 3.13.5 网关的 `/v1/embeddings` 覆盖度

**已定**：**不留"直连例外"** —— 向量化与对话一样，统一走网关（§3.13 已确认它有 `POST /v1/embeddings`）。
若某个向量模型只能直连，那应该先在网关里加一个 adapter，而不是在平台里开第二条链路。

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

### 4.0 总表（★ 坐标已按 **Spring Boot 4** 版本线核实，2026-09）

> **版本线已定**（用户拍板"就用最新的"）：**Spring Boot 4.1.x + Spring Framework 7.x + Spring Security 7.x**。
> ★ 本表坐标是**对着 Boot 4 核实过的** —— Boot 4 做了一次 **starter 大改名**，
> **照 Boot 3 的坐标写会编译不过或缺自动配置**（改名列见最后一列）。

| 模块 / 库（Boot 4 坐标） | 用? | 替掉了什么自研 | ★ Boot 4 改名 / 注意 |
|---|---|---|---|
| `spring-boot-starter-webmvc`（**MVC**） | ✅ | —（ADR-01：MVC + 虚拟线程，**不用 WebFlux**） | ★ 原 `spring-boot-starter-web` 已改名（旧名弃用） |
| `spring-modulith-starter-jpa`（**2.1.x**） | ✅ | 模块边界校验、**出站事件表**、架构文档生成 | ★ 2.1.1 官方支持 Boot 4（`events-jpa` 依赖 `boot-hibernate:4.1.1`） |
| `spring-boot-starter-security` + **`spring-boot-starter-security-oauth2-resource-server`** + `oauth2-jose` | ✅ | JWT 验签/解析、过滤链、密码编码（见 `08 §4.2`） | ★ 原 `spring-boot-starter-oauth2-resource-server`（无 `security-` 前缀）已改名 |
| `spring-boot-starter-data-jpa` | ✅ | 聚合读写、`AuditorAware` 审计、`Specification` 数据范围过滤 | ⚠️ 管理的 Hibernate 是 **7.4.5**（**不是 8**），JPA 3.2 |
| `spring-ai-starter-model-openai`（经网关，§3.13） | ✅ | 模型调用、流式、**结构化输出** | ★ 坐标已转正（**2.0.1**，不再需要 milestone 仓） |
| **`spring-ai-starter-vector-store-pgvector`** | ✅ **★改向** | **向量索引 / 相似度查询 / 元数据过滤 / 批量 upsert**（原本打算手写 SQL） | ⚠️ **`initialize-schema` 默认 `false`** ⇒ **保持关闭**，表由 Flyway 建（§9.4）；★ **列名硬编码、维度是表级** ⇒ 见 **§九** |
| `spring-boot-starter-validation` | ✅ | 请求校验（Jakarta Validation） | 无变化 |
| `spring-boot-starter-actuator` + `micrometer-registry-prometheus` + **`spring-boot-starter-opentelemetry`** | ✅ | 指标、追踪、健康探针 | ★ OTLP 改走新 starter；`management.tracing.enabled` → **`management.tracing.export.enabled`** |
| `spring-boot-starter-cache` + `caffeine` | ✅ | 进程内缓存，换 Redis 时业务零改动 | 无变化 |
| **Framework 7 原生 `@Retryable` / `@EnableResilientMethods`** | ✅ | `@Retryable` 重试（模型调用、解析失败） | ★ **`spring-retry` 不再被 Boot 管版本** ⇒ 优先用 Framework 7 内置的；若仍用 `spring-retry` 则自钉版本 |
| **`resilience4j-spring-boot4`**（**2.4.x**） | ✅ | 熔断/限流/舱壁 | ★ artifactId 由 `resilience4j-spring-boot3` 改为 **`-boot4`** |
| **`spring-boot-starter-flyway`** + `flyway-database-postgresql` | ✅ | 数据库迁移（`01`/`02` 已定） | ★ **只写 `flyway-core` 会丢自动配置**，必须用 starter |
| `spring-boot-testcontainers` + `testcontainers-postgresql`（`@ServiceConnection`） | ✅ | **集成测试用真 PG**，不 mock 数据库 | ★ Testcontainers **2.x** |
| `spring-boot-docker-compose` | ✅ | 本地开发自动起 PG（dev services） | 无变化 |
| **`spring-boot-starter-restdocs`**（4.0.x） | ✅ | **从测试生成接口文档** —— 与前端契约对账、防漂移 | ★ 改用 starter（Boot ≥ 4.0.4） |
| **`spring-boot-starter-security-test`**（测试期） | ✅ | `@WithMockUser` 等测试支持 | ★ 测试侧也已按 `security-` 前缀改名 |
| `shedlock-spring`（**7.x**） | ⏳ 多实例时 | 定时任务"同一时刻只跑一个实例" | ★ 7.x 的兼容矩阵含 Boot 4 |
| `spring-boot-starter-webflux` | ❌ | 已由 ADR-01 否掉 | ⚠️ 但 Spring AI 的**流式客户端**会把 `spring-webflux`（**库**，非 starter）带进依赖树 —— **这不等于选了 WebFlux 栈**：只要 `spring-boot-starter-webmvc` 在，Boot 仍以 Servlet 栈启动 |
| Spring Cloud（Gateway / Config / Registry / OpenFeign） | ❌ | — | 单体不需要 |
| `spring-boot-starter-batch` | ❌ | — | 见 §4.2 |
| `spring-statemachine` | ❌ | — | 见 §4.2（★ 且已归档） |
| `spring-integration` | ❌ | — | 不引 MQ 就不需要它 |
| `spring-session` / Redis | ❌ | — | 无状态 JWT |
| `spring-data-envers` | ❌ | — | 版本历史已有显式版本聚合 |
| Spring Authorization Server | ✅（**能力已并入 Security 7 本体**） | — | 见 §4.3 —— 只需装配，**不用引独立项目** |

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
| **Spring Statemachine** | **两条理由，任一条都足够**：**① 已归档**（★ 查证：`spring-projects/spring-statemachine` `archived=true`，README 首行写着 "no longer maintained"，homepage 指向 `spring-attic`）——**"用成熟模块"这条要求本身就把它排除了**；**② 技术上也错配**：我们的流程图是**用户在运行时画的数据**，而 Statemachine 的机器是**构建时的配置/代码**。详见 §5.3 | 对象在**有限状态间迁移**且**状态定义稳定**时（订单 / 审批 / 工单）。**那时也要看**：项目已归档 ⇒ 应选别的（如显式状态表 + 守卫，或 Camunda/Flowable 这类真正的流程引擎） |
| **Spring Integration** | 它是"企业集成模式"的框架（channel / router / transformer）—— **前提是有多个系统要接**。我们既没有 MQ，也没有外部系统 | 引入 MQ 或要接多个外部系统时 |
| **Spring Cloud 全家桶** | 单体 + 一个进程：没有服务发现、没有网关、没有分布式配置的必要 | 真的拆成多服务时 |
| **Spring Session / Redis** | 无状态 JWT；刷新令牌轮换落 PG 就够 | 需要"服务端强制踢下线"、"在线用户列表"这类**会话可见性**能力时 |
| **Spring Data Envers** | 需要历史的只有"应用发布版本"与"流程版本"，它们**已经是显式聚合**（`WorkflowVersion` / 发布记录），审计范围明确 | 需要"任意实体任意字段的自动历史"时 |
| **WebFlux** | ADR-01 已定：IO 密集用虚拟线程，**不用响应式**（可维护性优先） | 不打算改 |

### 4.3 可以晚点用（记下引入时机，不预留代码）

| 模块 | 引入时机 | 触发信号 |
|---|---|---|
| **授权服务器能力**（原独立项目 `spring-authorization-server`） | 若要**把 API 开放给第三方**（不只是自家前端） | ★ **已并入 Spring Security 7 本体** —— 我们既然已定 Boot 4 / Security 7 版本线，**这项能力就在依赖里**，不必引独立项目、也不必单独管版本。⚠️ 区分清楚：**JWT 资源服务器**（我们已用，`08 §4.2`）与**授权服务器**是两件事，后者只在"要给第三方发令牌"时才装配。在那之前，`08 §4.2.3` 的"自写薄层做刷新令牌轮换"仍然成立 |
| `shedlock-spring` | 应用多实例部署时 | 副本数 > 1 且用了定时任务 |
| Spring Cloud Config / K8s ConfigMap | 要"不重启改配置"时 | 配置变更频率上来了 |
| Micrometer → 完整可观测栈 | 单机排障不够用时 | 需要跨服务追踪 / 日志检索 |
| 独立向量库（Milvus/Qdrant） | 单表向量到千万级，或索引要跨库共享 | `retrieval` 的 P95 超出预算 |

### 4.4 ★ Boot 4 的破坏性变更里，**影响我们这几处用法**的（本轮查证）

> 版本线已定 Boot 4（§8.1）。下面这些**不是可选项** —— 照 Boot 3 的写法会**编译不过或丢自动配置**。

| 我们的用法 | Boot 4 / Framework 7 / Security 7 的变化 | 要怎么做 |
|---|---|---|
| **web 层** | `spring-boot-starter-web` → **`spring-boot-starter-webmvc`** | 换坐标（旧名弃用）。★ 由此可推断：Boot 4 已把 web 栈**按 MVC / WebFlux 分开命名** —— 与 ADR-01 的立场一致（见 §4.5） |
| **安全过滤链** | `and()` **已删除**（只剩 lambda DSL）· `authorizeRequests` 删除 → `authorizeHttpRequests` · `requestMatchers` 底层换成 **`PathPatternRequestMatcher`**（`MvcRequestMatcher` / `AntPathRequestMatcher` 已移除）· `AuthorizationManager#check` → **`#authorize`** · starter 改名 **`spring-boot-starter-security-oauth2-resource-server`** · 序列化走 **Jackson 3**（`tools.jackson`） | `08 §4.2` 的责任链示例按这几条重写（**是 API 变化，不是设计变化**） |
| **方法级授权** | `@EnableMethodSecurity` **默认仍启用** `@PreAuthorize` / `@PostAuthorize` / `@PreFilter` / `@PostFilter`（JSR-250 仍需显式开） | `08 §4.3` 的用法**不变** ✅ |
| **虚拟线程** | **`spring.threads.virtual.enabled` 属性名未变** | `03` 的用法**不变** ✅ |
| **JPA / Hibernate** | 管理的 Hibernate 是 **7.4.5**（**不是 8**），JPA 3.2；`EntityManagerFactory` / `EntityManager` 可直接注入 | 聚合映射不受影响；注意 JPA 3.2 的少量行为差异 |
| **观测** | OTLP 改走 **`spring-boot-starter-opentelemetry`**；`management.tracing.enabled` → **`management.tracing.export.enabled`** | 配置项改名 |
| **Flyway** | 必须用 **`spring-boot-starter-flyway`**（只写 `flyway-core` 会**丢自动配置**） | 换坐标 |
| **REST Docs** | 改用 **`spring-boot-starter-restdocs`** | 换坐标 |
| **重试** | **`spring-retry` 不再被 Boot 管版本**；Framework 7 内置 `@Retryable` / `@EnableResilientMethods` | 优先用 Framework 7 原生的（少一个第三方库） |
| **SpEL** | 默认**最多求值 10000 步**（自带一道熔断） | 与 §5.3.1 的安全边界叠加使用 |
| **`RestTemplate`** | 已弃用 | 我们本来就用 `RestClient` ✅ |
| **Spring AI / PgVector** | **`initialize-schema` 默认改为 `false`**（原先默认建表）· **列名不可定制**（硬编码 `id/content/metadata/embedding`）· **维度是表级** | **保持关闭**（表由 Flyway 建，§9.4）；误开也无害（DDL 全是 `IF NOT EXISTS`）。⚠️ "检索一条都搜不到"时**先查表是否存在、维度是否匹配** —— 完整约束与迁就方式见 **§九** |
| **Undertow** | Boot 4 **已删除 Undertow**（与 Servlet 6.1 不兼容） | 我们用 Tomcat 11 ✅（**别照旧文档配 Undertow**） |
| **Jackson** | Jackson 3 优先（2 已弃用） | 自定义序列化器要迁到 `tools.jackson` 包 |

### 4.5 ★ web 栈复核：**MVC，不是 WebFlux**（ADR-01 结论不变，但理由要写全）

**答案：MVC。** 决定性理由**不是流式**，而是**持久化**：

| 判据 | MVC（选它） | WebFlux（否） |
|---|---|---|
| **持久化** ★决定性 | Spring Data JPA（阻塞 JDBC）+ `Specification` 数据范围过滤 + Modulith `starter-jpa` 事务事件 | 要换 **R2DBC** ⇒ **等于放弃 JPA**：聚合映射、`@Transactional`、`Specification`、Modulith 事务事件**全要重写** |
| 流式（SSE） | `SseEmitter` / `StreamingResponseBody`（Servlet 异步）**本来就够** —— 前端契约是纯 SSE 文本帧，后端逐帧 flush 即可 | 并不因此更强 |
| 等模型返回 | 虚拟线程让"阻塞等"变便宜（**IO 密集**，非 CPU 密集） | 收益重复（虚拟线程已解决同一问题） |
| 领域层纯净 | 领域层无框架类型 | `Mono` / `Flux` 会渗透进 domain 与 application |

**⚠️ 一个容易误判的点**：★ Boot 4 把 starter 拆成了 `spring-boot-starter-webmvc`，
而 **Spring AI 的流式客户端会把 `spring-webflux`（库）带进依赖树**。
**看到依赖树里有 webflux ≠ 选了 WebFlux 栈** —— 只要 `spring-boot-starter-webmvc` 在，
Boot 的 `WebApplicationType` 判定仍是 **SERVLET**（`DispatcherServlet` 与 `DispatcherHandler` 同时存在时，Servlet 优先）。

**MVC 下做 SSE 的四个实操要点**（`03` 机制七 / §6.3 已有，这里汇总）：

1. `spring.threads.virtual.enabled=true`（**属性名 Boot 4 未变**）
2. 契约要求**任何终止路径恰好一次 `done`** ⇒ 后端必须在 **60s 空闲**（`IDLE_TIMEOUT_MS`，心跳 20s×3）时
   **主动结束并吐 `done`**，不能靠连接超时兜底
3. 反向代理**必须关响应缓冲**（`proxy_buffering off` / `X-Accel-Buffering: no`）
4. 到网关的流式调用用 **`RestClient`**（官方同步客户端）；**不自写 SSE 解析**

**什么时候才该换 WebFlux**：不打算改。除非出现"第三方高频短请求 + 必须非阻塞"的极端场景，
且愿意付"放弃 JPA"的代价 —— 那不划算。

---

## 五、★ 本轮查证/调研得到的四处改向

> 这两条都来自**实际读官方文档**（Spring AI 2.0.1 Reference），不是凭印象。它们直接减少自研量。

### 5.1 向量检索：**不手写 SQL**，用 `PgVectorStore`

**原设计**（`backend/README.md` 的技术选型表，**本轮收口时该表已改写**）：`JdbcTemplate`（向量检索**手写 SQL**），理由是"向量相似度查询用 ORM 表达不划算"。

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

> ⚠️ **本节只讲了"它能替我们做什么"**。它还有**三条硬约束**（列名不可定制 / 维度是表级 / `initialize-schema` 默认关闭），
> 其中第一条**直接改掉了 `02` 的向量表设计** —— 完整内容在 **§九**，动向量这块之前务必先读那一节。

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

### 5.3 编排执行引擎：**不需要 Spring Statemachine**（两条独立理由）

**问题**（用户提出）：既然有前端 workflow（17 种节点目录、已实现 7 种、条件分支、V1.0 要真实执行），
是不是该用 Spring Statemachine？

**答案：不需要。有两条理由，任一条都足够。**

#### 理由一（事实）：它已经不再维护

★ 本轮查证：`spring-projects/spring-statemachine` 的 **`archived=true`**，README **首行**写着
**"Spring Statemachine is no longer maintained"**，homepage 指向 `spring-attic.github.io`
（Spring 的"阁楼"）。
（负对照：同批查的 `spring-boot` / `spring-modulith` / `spring-security` / `spring-ai` **全部 `archived=false`**
且 `pushed_at` 都在最近两天内 ⇒ 这个检查是有效的，不是"看谁都说归档"。）

⇒ 仅凭"用户要求用成熟且维护中的模块"这一条，它就已经出局。

#### 理由二（技术，更根本）：状态机与图执行是两种东西

| 维度 | Spring Statemachine | 我们的 workflow |
|---|---|---|
| **定义时机** | **构建时**——`StateMachineFactory` 在代码/配置里定义 states/transitions | **运行时**——用户在画布上画、存进库的 JSON（契约已冻结） |
| **建模对象** | 有限**状态** + 事件 + 转移 | **有向图**：节点 + 边 + 执行上下文 |
| 条件分支 | guard（静态定义） | 条件节点 + **运行时求值** |
| 并行/扇出 | `fork` / `join`（静态区域） | **动态扇出**（V1.0） |
| 持久化 | 需 `StateMachinePersister` + context（**另一套存储**） | 与业务同事务的执行记录（`GET /workflow/records` 契约已冻结） |
| 调试/单步 | 监听 transition（绕） | 图解释器可**逐节点**暂停、查看上下文 |

**一句话判据**：
> **"状态迁移"是对象在一个固定状态集里往前走；"图执行"是按一张用户画的图去跑任务。**
> 前者是 Statemachine 的领域，后者需要一个**解释器**。

而且还有一个反向的坑：要支持"用户画任意图"，就得**为每张图动态构建 `StateMachineFactory`**，
并把执行上下文塞进 `ExtendedState`（`Map<String,Object>`）——**强类型丢了**，
而换来的只是替掉"转移判定"这很小的一块。

#### 那该用什么：先把"能替掉它的候选"列全，再说为什么都不合适（★ 本轮查证补事实）

**先说结论**：**不为这件事选"一个引擎"，而是选一组零件** ——
"遍历 + 执行上下文"这一层是必须自己写的**业务逻辑**（任何流程引擎内部也有这一层）。
零件清单见 §5.3.1。

| 候选 | ★ 查证到的事实（2026-09） | 结论 |
|---|---|---|
| **Flowable 8.0.0**（BPMN 引擎） | 核心 **Apache-2.0**（商业线是独立产品，不污染开源核心）· **8.0 起只支持 Spring Boot 4 / Framework 7** ⇒ 与我们的版本线恰好一致 · starter 仍是 `org.flowable:flowable-spring-boot-starter` · BPMN 嵌入式常用 schema = **39 张 `ACT_*`**（全量 56 ACT + 6 FLW）· `BpmnModel` 是**纯 POJO**，可**不写 XML 程序化建流程**再 `createDeployment()` · `delegateExpression` 可绑 Spring bean | **引擎路线首选**（也是唯一可嵌入且活跃的），但代价见下 |
| **Camunda 7** | CE **已 EOL**（7.24 为最后特性版 2025-10-14）· 仓库 **`archived`** · EE 支持到 2030-04-09 | **排除** |
| **Camunda 8 / Zeebe** | 发布物是独立分发包，**必须独立部署集群** | **排除**（违反"3 个进程"） |
| **Temporal**（持久化执行） | SDK Apache-2.0；进程内的 `temporal-test-server` / dev server 官方定位是**测试与开发** | **排除**——生产要 Server 集群。**真到"长时挂起 + saga 补偿"时它是最优选**（V2.0 级） |
| **Activiti 8.8.1** / **Kogito·jBPM** | 仍在维护但节奏弱 / 生态迁移中 | 不作为首选 |
| **Netflix Conductor / Apache Airflow** | 同为**独立服务**；Airflow 还是"离线调度" | 排除 |
| **Spring Batch** | 批处理模型（Job / Step / Chunk），不是图执行 | 排除 |
| **Spring Integration** | 企业集成模式（channel / router / transformer），前提是"有多个系统要接" | 排除 |

##### 若一定要"现成引擎"：**Flowable 是最不坏的那个**，但代价要算清

| 项 | 数字 / 说明 |
|---|---|
| 引入的表 | **39 张 `ACT_*`**（BPMN 嵌入式常用集）—— `09` 定的部署形态是 **3 个进程 + 1 个库**，它一次给这个库加 39 张表 |
| 翻译层 | 我们的图 JSON → `BpmnModel` → `BpmnXMLConverter.convertToXML` → `createDeployment()`，约 **800–1500 行**（节点类型映射、边→`sequenceFlow`、条件→网关 + UEL、节点间数据→`variables`、节点执行器→`delegateExpression`） |
| 版本治理 | **每次改图 = 一次新部署**，`ACT_RE_PROCDEF` 版本递增、旧实例续跑旧版 ⇒ 必须自建清理与治理 |
| **会失去** | **没有原生"逐节点单步"调试器**（要靠 `ExecutionListener` 截 `DelegateExecution` + 历史表反查）；执行上下文进 `variables`（`Map`）⇒ **强类型丢了** |
| **会换回** | 事务、重试、定时器、历史审计、崩溃续跑（这些自研要补） |

**⇒ 现在不划算**：我们没有一个"人等流程"的节点，全是机器执行。
翻牌判据见下面"升级信号"——出现**人工审批 / 长时挂起**时 Flowable 第一顺位；
出现 **saga 补偿 / 小时级崩溃续跑**时转向 **Temporal**（那时才值得多一个进程）。

#### 5.3.1 零件清单：把自研压到最薄（★ 本轮查证）

| 关注点 | 零件 | 版本 / 许可 / 注意（★ 2026-09 查证） |
|---|---|---|
| 图算法：拓扑序、环检测、可达性、子图 | **JGraphT** | **1.5.3** · 许可 **LGPL-2.1-or-later OR EPL-2.0 双选 ⇒ 选 EPL-2.0**（避开 LGPL 在服务端的链接争议）· 可用 `TopologicalOrderIterator` / `CycleDetector` / `ConnectivityInspector` / `AsSubgraph` |
| 条件表达式求值 | **SpEL**（Spring 官方 `SpelExpressionParser`） | ⚠️ 必须配 **`SimpleEvaluationContext`**，安全边界见下 |
| 节点执行器装配 | **Spring DI** | 每种节点类型一个 `@Component`，装配成 `Map<String, NodeExecutor>` |
| 并行 / 扇出（V1.0） | 虚拟线程 + **`CompletableFuture`** | ⚠️ **不要用 `StructuredTaskScope`**：★ 查证 JEP 505 = JDK 25 **第五次 Preview**、JEP 525 = JDK 26 **第六次 Preview** ⇒ **到 JDK 26 仍未转正**，生产用要 `--enable-preview` |
| 重试 / 超时 | `spring-retry` + Resilience4j | §4.0 已选 |
| 执行记录 / 单步 / 上下文 | 自家聚合 + JPA（**与业务同事务**） | `GET /workflow/records` 契约已冻结 |
| 模型 / 检索节点 | `modelcatalog`（经网关）/ `retrieval` | 已有 |

##### ⚠️ SpEL 的安全边界（这一条不写清就是 RCE 面）

★ 官方文档（`framework-docs/.../core/expressions/evaluation.adoc` 的 Security Considerations）明确：

> 表达式求值可调用构造器、方法与属性（反射）⇒ **对"不可信来源"的表达式求值本质危险，通常应当避免**，可导致 **RCE**。
> `SimpleEvaluationContext` 排除了 `T()` 类型引用、构造器与 bean 引用，
> 但官方同时说明它只是 **best-effort** —— 例如 read-only 数据绑定**不检查形似 getter 的方法的副作用**。

**我们的条件节点里填的内容，是用户在画布上输入的 = 不可信来源。** 所以：

1. **默认形态是结构化比较，不是自由表达式**：条件 = 左值（**上游节点输出的白名单字段**）+ 操作符 + 右值。
   MVP/V1.0 只允许这一种形态 ⇒ **连表达式解析都不需要**，也就没有 SpEL 这一层风险。
2. 若确实要放开自由表达式：`SimpleEvaluationContext.forReadOnlyDataBinding()` +
   上下文**只放显式准备的变量**（不放 bean、不放对象引用）+ 表达式长度与求值超时。
3. **禁止**：任何面向用户输入的路径出现 `StandardEvaluationContext`（它能 `T()` 与 bean 调用）。

**三条约束同时成立，把上面全部排除**：

① 图是**运行时的数据**（用户画的 JSON、存库、按版本发布）—— 不是部署期的定义
② 必须**嵌入式**（`09` 已定 3 个进程）
③ 图格式**已冻结**（`frontend/src/api/workflow.ts`）—— 不能改成 BPMN

#### 建议：自己写一个「薄的图解释器」，周边能力全用现成的

**这不是"自研框架"，而是业务逻辑** —— 任何流程引擎都要有人写这一层。核心只有三件事：
**遍历 + 条件求值 + 节点执行**（几十到一两百行，一次性成本，完全可控）。
**但周边能力一个都别自研**（这才对得起"少自研"）：

| 关注点 | 用什么 | 说明 |
|---|---|---|
| 图遍历 / 调度 / 上下文 | 自己写（**薄**）：节点执行器注册表 + 遍历 + 上下文 | 业务逻辑，框架替不了 |
| **图算法 / 条件求值 / 节点执行器装配 / 并行 / 重试** | **见 §5.3.1 零件清单** | 一处真相，不在两处重复维护 |
| 模型调用（ai-chat 节点） | `modelcatalog` → **OpenAI 兼容网关**（§3.13 已定） | 已有 |
| 检索（search-dataset 节点） | **`retrieval` 模块**（已定） | 已有 |
| 发布 / 版本 / 回滚 | **已有聚合**（`WorkflowVersion`） | 不用新造 |
| 执行记录 / 逐节点耗时 | 自家聚合（`GET /workflow/records` **契约已冻结**） | 与业务同事务 |
| 画布内调试 | 解释器天然支持"逐节点暂停 / 单步 / 看上下文" | 这正是自写解释器**比状态机更好做**的地方 |
| **节点执行状态** | 枚举 + **显式状态守卫**（同 `03` 机制三对文档状态的手法） | 不引状态机；守卫表比状态机更容易读懂与调试 |

#### 升级信号（什么时候该回到上表重新选）

出现下面任一条 ⇒ 说明"薄解释器"不够用了：

| 信号 | 指向 |
|---|---|
| **人工审批 / 长时挂起**（流程要等人点"同意"才继续） | Camunda / Flowable（BPMN 是为此而生的） |
| **跨系统 saga / 补偿事务**（要回滚已执行成功的步骤） | Temporal（持久化执行 + 补偿） |
| **执行时长到小时/天级**，且要求**崩溃后从断点续跑** | Temporal |
| 需要**业务人员自己用图形化工具改流程**（不是开发者改） | BPMN 引擎 + 它的建模器 |

> **回滚方案要提前想清**：真到那天，"图格式已冻结"会变成改造的阻力。
> 所以**现在就把解释器写成"图 → 执行计划 → 执行"两段**：
> 前面那段（读图、校验、算依赖）与后面那段（执行、记录）分开 ⇒ 将来换引擎只换后者。

#### 什么时候才真的该上状态机（留个判据，避免这条被读成"永远不用"）

**状态集稳定（配置在代码里）+ 状态迁移本身就是核心复杂度 + 状态数上到十几个且带超时/互斥/并行区域**
—— 也就是**订单 / 审批 / 工单**那类。
⚠️ 真到那天**也不能选 Spring Statemachine（已归档）**：应评估 **Camunda / Flowable**（BPMN，
真正的流程引擎，支持动态部署）或"显式状态表 + 守卫"。

> 顺带说清：**文档七态（入库状态机）同样不需要它** —— 七个状态 + 简单转移，用
> 枚举 + 显式状态守卫 + 领域事件（`03` 机制三已写）表达得更清楚，
> 而且**迁移必须与 JPA 事务、领域事件一致**，引入 Statemachine 的 context 持久化只会多一套存储。

### 5.4 `modelcatalog` 的职责边界（因为经网关接入而必须重划）

**冲突点**：`/models` 域有 8 个端点，其中 `POST /models/:id/load`、`POST /models/probe`、
`GET /models/base-models` **看起来**与 JAiRouter 的模型管理重叠。

**划法**：

| 端点 / 概念 | 归谁 | 说明 |
|---|---|---|
| `GET /models`（平台可见的模型清单） | **我们** | 带能力元数据与启用态，供应用引用 |
| `POST /v1/models`（上游有哪些模型） | **JAiRouter** | 我们**不在平台里维护第二份上游清单** |
| `GET /models/base-models`（基础模型目录） | **我们** | 底座候选 + **能力元数据**（上下文窗口/维度/供应商）——网关只给 `id/type/adapter` |
| `POST /models/probe`（能力探测：思考三态 / 工具调用） | **我们** | 这是**业务准入**判据（`03` 机制四的 M-I5），网关不管 |
| `POST /models/:id/load`（加载） | **我们**（已定，见 §3.13.4） | 语义 = **平台启用位 + 上游可用性校验**；**不操作上游实例** —— 那是网关的事，且各适配器语义不同 |
| 上限/超时/重试策略 | **JAiRouter** | 我们只留"到网关这一跳"的超时 + 一次重试 |

**判据**：
> **「平台模型资产」与「上游模型实例」是两种东西。**
> 前者要被**应用引用**、要声明**能力**、要参与**权限与数据范围**（`ownerId`/`visibility`）；
> 后者是**运维对象**（哪个实例、健康、权重）。
> **把它们混在一张表里，正是前身系统"模型管理页既管实例又管资产"的病根。**

---

## 六、部署形态与三个环境细节

### 6.1 容器清单

```
postgres:16 + pgvector          ← 唯一中间件（业务数据 + 向量 + 全文 + 锁 + 幂等 + 出站事件）
app (spring boot 4, 虚拟线程)    ← 单体
jairouter (模型网关)             ← ★ OpenAI 兼容入口；上游的负载均衡/限流/熔断/降级都在它这里
nginx / caddy                   ← TLS + 前端静态 + 反向代理（★ 必须关 SSE 缓冲，见 §6.3）
```

> **上游推理服务（Ollama / vLLM / 外部 API）在网关之后，不是平台的容器**（见 §二 口径说明）。
> ★ 另注意：**Boot 4 已删除 Undertow**（与 Servlet 6.1 不兼容）⇒ 用 **Tomcat 11**，别照旧文档配 Undertow。

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
| 中间件数量 | `backend/README.md`：PG(+pgvector) + 模型服务 | ✅ **确认**，并补上"缓存用 Caffeine"；限流/熔断改为**分层**（上游交网关，§3.10） |
| 事件不引 MQ | ADR-03 | ✅ **确认**（用 Modulith 出站事件表） |
| **向量检索实现** | **手写 SQL（JdbcTemplate）** | ⚠️ **改向**：用 `PgVectorStore`（§5.1） |
| **结构化输出** | **自研四层防御** | ⚠️ **改向**：官方转换器 + `validateSchema()`，只自写宽松解析（§5.2） |
| 鉴权 | `01 §3.1`：Spring Security + JWT | ✅ 确认，细节见 `08 §4.2`（责任链、不自研）；Security 7 的 API 变化见 §4.4 |
| 对象存储 | 端口 + 本地磁盘 | ✅ 确认 |
| 持久化 | JPA + JdbcTemplate 手写向量 SQL | ⚠️ 前半确认；后半由 §5.1 取代 |
| 构建 | Maven（`01 §3.1`） | ✅ 确认 |
| **模型服务** | **内网 Ollama 直连** | ⚠️ **改向**：经 **OpenAI 兼容网关**（JAiRouter，本项目自己的开源项目）——**不直连推理服务**（§3.13） |
| **限流 / 熔断** | Resilience4j 承担熔断 | ⚠️ **修订**：**熔断/限流/负载均衡交给网关**，我们只留"到网关这一跳"的超时 + 一次重试（§3.10） |
| **编排执行引擎** | 未定 | ❌ **不用 Spring Statemachine**（已归档 + 技术错配，§5.3）；**改用"薄解释器 + 零件"**（§5.3.1） |
| 授权服务器 | 曾建议"要开放第三方 API 时用 Spring Authorization Server" | ⚠️ **修正**：该独立项目**已并入 Spring Security 7 本体**。我们既已定 Security 7 版本线 ⇒ **能力在依赖里，不用引独立项目**（§4.3） |
| **Spring Boot 版本线** | 未定（3.x / 4.x） | ★ **已定：Boot 4.1.x / Framework 7.x / Security 7.x / JDK 25** —— 生态已核实**无硬阻塞**（§4.0 / §8.1） |
| **`POST /models/:id/load` 语义** | 待定 | ✅ **已定**：**平台启用位 + 上游可用性校验**，不操纵上游实例（§3.13.4 / §8.1） |
| **新建资源默认可见性** | 曾被列为待拍板 | ✅ **不是待拍板** —— **契约里已写死 `private`**（§8.2）⇒ 后端照实现，**不要做成可配置项** |
| **`api/legacy/*` 垫片** | 待定（删 / 留） | ✅ **保留 + 上锁** —— 本轮核实：它**不产生任何额外后端端点**，对后端零负担（§8.3） |
| **web 栈** | ADR-01：MVC + 虚拟线程 | ✅ **复核确认**，并把"不用 WebFlux"的**决定性理由（JPA/事务）**写全（§4.5） |
| **文档解析 / MIME 校验** | 未定（`04:357` 只说"解析器分级"） | ★ **本轮定**：`tika-core` + PDFBox + POI；**不做 OCR、不做表格抽取**（§10.2 #1 / #2） |
| **中文全文检索** | `zhparser` 或 `pg_jieba`（待办） | ★ **本轮定**：应用侧 **2-gram** + `simple`（**不装扩展**，§10.2 #3） |
| **主键 ID 策略** | UUID（版本未定） | ★ **本轮定**：**时间有序 UUID**，用 Hibernate 生成器（**零新依赖**，§10.2 #5） |
| **日志实现** | "结构化 JSON"（库未定） | ★ **本轮定**：**Boot 原生** `logging.structured.format.console=ecs`（§10.2 #4） |
| **工程工具链** | 未定（ROADMAP 只写"CI 跑通"） | ★ **本轮定**：Maven 3.9.16 · Spotless · Checkstyle · SpotBugs · GitHub Actions · Temurin 25（§10.1 E） |
| **契约漂移检测** | 未定（`ROADMAP §7.2` 列为闸门） | ★ **本轮定**：**自研**（解析 TS 契约 ↔ `RequestMappingHandlerMapping`，§10.2 #8） |
| **向量表形状** | `02`：`vec_embedding(segment_id, model_id, dimension, …)` | ⚠️ **改**：列名迁就框架四列 + **按维度分表 + 路由**（§九） |

---

## 八、待拍板与待办（★ 2026-09 刷新：选型已在 §九 / §十 全量收口）

### 8.1 已定（本轮拍板 / 本轮查证后关闭）

| # | 事项 | 结论 | 出处 |
|---|---|---|---|
| 1 | **版本线** | **Spring Boot 4.1.x + Spring Framework 7.x + Spring Security 7.x**（用户拍板"就用最新的"）。★ 已核实周边生态 **无硬阻塞**：Modulith **2.1.1** / Spring AI **2.0.1** / Resilience4j **2.4.0** 均已支持 Boot 4。⚠️ 反向的一条：**Boot 3.5 的 OSS 支持已于 2026-06-30 结束** ⇒ 留 3.5 反而没有社区补丁 | §4.0 |
| 2 | **JDK 基线** | Boot 4 最低 JDK 17；虚拟线程在 **21 已转正**；Framework 7 生产推荐 **25** ⇒ 建议 **JDK 25（LTS）** | §4.0 |
| 3 | **授权服务器** | 原独立项目 **已并入 Security 7 本体** ⇒ **不引独立项目**（能力已在依赖里）。要开放第三方 API 时装配 Security 7 内置能力；在那之前按 `08 §4.2.3` 自写薄层做刷新令牌轮换 | §4.3 |
| 4 | **`POST /models/:id/load` 的语义** | 定为 **「平台启用位 + 上游可用性校验」**，**不尝试操纵上游实例**（"载入显存"是网关/上游的事，且不同适配器语义不统一 —— vLLM/外部 API 根本没有"加载"）。四态映射见 §3.13.4 | §3.13.4 |
| 5 | **新建资源的默认可见性** | **`private` + `ownerId = 当前用户`**。**这不是待拍板项** —— 前端契约里已经写死了（三处注释都写明"由后端写入，契约里没有对应的请求字段"），见 §8.2 | §8.2 |
| 6 | **`api/legacy/*` 垫片** | **保留**（本轮核实：它**不产生任何额外后端端点**，对后端零负担） | §8.3 |
| 7 | **中文全文检索** | ★ **装 PG 扩展 `zhparser`（真分词）+ 自制 PG 镜像**（用户拍板）。扩展进镜像 initdb、text search 配置进 Flyway、**启动自检**；词典变更后需重建 tsvector | §10.2 #3 |
| 8 | **日志格式** | **Boot 原生结构化 JSON**（`logging.structured.format.console=ecs`）—— ★ 3.4 起内置，**不引第三方 encoder** | §10.2 #4 |
| 9 | **文档解析与 MIME 校验** | **`tika-core`（嗅探）+ PDFBox 3.0.8 + POI 5.5.1**；Markdown 由 `tika-core` 内嵌的 commonmark 承担 | §10.2 #1 · #2 |
| 10 | **主键 UUID 采用时间有序** | Hibernate `@UuidGenerator(style = TIME)`（或自定义 v7）—— **零新依赖** | §10.2 #5 |
| 11 | **工程工具链** | Maven 3.9.16 · Spotless 3.10.3 + google-java-format · Checkstyle 14.1.0 · SpotBugs 4.10.4 · GitHub Actions · Temurin 25 | §10.1 E |
| 12 | **向量的落地形状** | **按维度分表 + `vectorindex` 路由**；表由 Flyway 建、列名迁就框架四列 | §九 |

> ★ **本轮之后的收口状态**：§10.1 的全量表**每一行都已填满**，剩下**只有两件**需要拍板（§10.3）。
> 也就是说：**M0 已经具备开工条件**。

### 8.2 后端必须照契约实现的默认值（不是可配置项）

| 项 | 值 | 契约出处 |
|---|---|---|
| 新建知识库 / 应用 / 会话的 `visibility` | **`private`** | `frontend/src/api/knowledge.ts:27-29` · `assistants.ts:53` · `conversations.ts:41-44` |
| `ownerId` | **当前登录用户，且不可变**（转让是显式动作） | `07 §3.1` · `api/types.ts` 的 `OwnedResource` |
| 请求体里**不出现** `visibility` / `ownerId` | 前端不传，后端也不该要求 | 同上 |

> ⇒ 这条的价值在于：**别把它做成可配置项**。数据权限放开容易，收回难（`07 §8.1`）。

### 8.3 `api/legacy/*` 垫片：保留 + 上锁（★ 本轮核实）

**先纠正一个误解**：它看起来像"后端要兼容的旧接口"，**实际不是** ——
本轮逐个读了这个目录的三个文件，**它们打的全部是新契约端点，一个旧 URL 都没有**：

| legacy 函数 | 实际打到哪 | 在 115 端点契约里？ |
|---|---|---|
| `getAiList`（3 处调用） | `GET /models?size=200` | ✅ |
| `detail` / `addSubmit` / `workFlowPublish` | `GET`/`PUT /assistants/:appId/workflow/draft` · `POST …/publish` | ✅ |
| `getWorkFlowVersion` / `getVersionGraph` | `GET /assistants/:appId/workflow/versions[/:version]` | ✅ |
| `allList` | `listKnowledgeBases()` → `GET /knowledge` | ✅ |
| `getDictionary` | **不发请求**（前端常量表） | — 零后端依赖 |

**⇒ 结论：对后端是零负担。** 它只做两件事：
① 把响应**再包一层**（`res.data.data`，给旧组件读）② 字段**改名**（`modelName` / `model_type` / `baseName` / `knowledgeIcon` / `dictValue`）。
**后端只实现单层 `{code,message,data}`，双层由这一层在前端构造** —— 这条必须写进后端文档，
免得后端"顺手兼容双层"。

**为什么现在不删**：删它 = 改 **6 个调用点**（`workflow/Index.vue`、`PublishHistory.vue`、
`SelectProviderDialog.vue` + 3 个节点组件），而这 6 处都在 **6,796 行的旧画布代码**里。
收益是删约 150 行，**风险是动画布的数据访问** —— 而画布正要进入"真实执行"的开发期。
它是**刻意的边界标记**（三个文件头都写明"唯一删除点"）。

**要做的只有两件**：
1. **上锁（机械检查）**：`api/legacy/*` 只允许被 `pages/workflow/**` 引用，新增引用即失败；导出函数清单不得增加。
2. **给清理触发条件**：V1.0 落地"节点执行器"那一轮，顺手把 6 个调用点正规化到 `@/api/*`，再整个目录一次删除。

⚠️ **一个要记下的已知点**：`getDictionary` **不发请求**（供应商字典是前端常量）。
若 V1.0 真需要"后端下发字典"，**这个垫片会退化成"假装成字典的常量表"** ——
到那时要做的不是给它接后端，而是废掉它。

### 8.4 仍然待办（不阻塞 M0）

> ★ 本轮收口后，原六项里有**两项关闭**：中文分词扩展（→ §10.2 #3 定 2-gram）·
> 日志格式（→ §10.2 #4 定用 Boot 原生）。**剩下四项**：

| # | 事项 | 建议 | 期限 |
|---|---|---|---|
| 1 | **JAiRouter 网关的部署与获取**（同机 / 独立机 / 已有实例） | **M0 就要定** —— 决定 `modelcatalog` 的 base-url、探活方式、超时与重试参数，也决定 dev 环境怎么起 | **M0** |
| 2 | Resilience4j 的熔断阈值 | M1 定（模型调用是第一个外部依赖），先保守默认 | M1 |
| 3 | 缓存：是否给"模型列表"加缓存 | 建议**先不加**（单实例 + 进程内 Caffeine）；真慢了再加，`Cache` 抽象零改动 | M1 |
| 4 | 网关的 `/v1/embeddings` 是否覆盖我们全部向量模型（§3.13.5） | **建议不留直连例外**，统一走网关 | M2 前 |

## 九、向量存储的落地约束（★ `PgVectorStore` 的硬编码，以及我们怎么迁就它）

> **为什么单独一节**：§5.1 的改向（"不手写 SQL，用 `PgVectorStore`"）只回答了**它能替我们做什么**。
> 本轮为了把选型收口，去读了 **Spring AI 2.0.1 的源码与官方文档**，发现它有三条**硬约束** ——
> 其中第一条**直接改掉 `02` 册的向量表设计**。这一节是 §5.1 的必读补充。

### 9.1 三条硬约束（★ 逐条来自源码/官方文档，不是印象）

| # | 硬约束 | 证据 | 对我们的影响 |
|---|---|---|---|
| **1** | **列名不可定制** —— 建表 DDL、`INSERT`、`DELETE`、`RowMapper`、`SchemaValidator` 五处**全部硬编码** `id / content / metadata / embedding`。Builder 上只有 `schemaName` · `vectorTableName` · `idType` · `dimensions` · `distanceType` · `indexType` · `initializeSchema` · `maxDocumentBatchSize`（**没有** `tableName` / `idColumnName` / `contentColumnName` …） | `PgVectorStore.java` L277-278 / L324 / L616-619；`PgVectorSchemaValidator` L106-110 | ⚠️ **`02 §5.3` 的 `vec_embedding(segment_id, model_id, dimension, embedding, created_at)` 对不上** ⇒ 表设计必须改（§9.2） |
| **2** | **维度是表级** —— `vector(N)` 是**列**类型。★ 官方原文：*"Dimensions are set to the embedding column on table creation. If you change the dimensions you would have to re-create the `vector_store` table as well."* | `pgvector.html` 配置表；`PgVectorStore` L498-515 | 一张表**只能装一个维度** ⇒ 不同维度的向量模型不能共表（§9.3） |
| **3** | **`initializeSchema` 默认 `false`**（1.x 是 `true`，这是 2.0 的破坏性变更）；它建的 DDL **全部带 `IF NOT EXISTS`**（幂等，不会与 Flyway 打架） | `PgVectorStore.afterPropertiesSet()` L429-456；`upgrade-notes` | **表由我们的 Flyway 建** ⇒ §4.0 写的"**必须显式打开**"要**修正为"保持关闭"**（§9.4） |

**另有两条恰好与我们既有设计吻合**（不用改，但要记下来 —— 它们证明原设计是对的）：

| 既有设计 | 框架实际行为 | 结论 |
|---|---|---|
| `03 §3.3`："`segment_id` 是向量表主键 ⇒ 天然 upsert 幂等" | 写入 SQL 是 `INSERT … ON CONFLICT (id) DO UPDATE`（同 id **覆盖写、不报错**） | ✅ **完全吻合** —— 主键值直接用 `segmentId` |
| `03 §3.4`："进度由后端事实回写、按批处理" | **没有进度回调 API**，但按 `maxDocumentBatchSize` 切批判量写入 | ✅ 我们本来就要自己分片计数 —— **回调不是必需的** |

### 9.2 表设计改为"迁就框架的四列"

```sql
-- Flyway · vectorindex 模块自己的 migration 目录
CREATE TABLE vec_embedding_768 (
    id        uuid PRIMARY KEY,        -- ★ 值 = segmentId（应用侧生成，与 kb_segment 一对一）
    content   text NOT NULL,           -- 分段文本（框架读写它；顺带省掉"检索后回表取文本"这一步）
    metadata  json,                    -- 可过滤的业务字段：kb_id / document_id / model_id …
    embedding vector(768) NOT NULL
);
CREATE INDEX idx_vec_768_hnsw ON vec_embedding_768
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 64);  -- 参数我们显式写
CREATE INDEX idx_vec_768_kb ON vec_embedding_768 ((metadata::jsonb -> 'kb_id'));   -- ★ 过滤走 jsonpath
```

三条要记的：

| 项 | 怎么做 | 为什么 |
|---|---|---|
| `model_id` / `dimension` / `created_at` 放哪 | **放 `metadata`**，不另加列 | ★ 框架只写那四列；加列虽可行，但会让 `schema-validation=true` **失败**（它校验的是"表**恰好**有那四列"）。放 metadata 还换来一个好处：**可以直接用过滤表达式查**"哪些向量是旧模型生成的、需要重灌" |
| `metadata` 的值类型 | **一律存字符串**（UUID 也 `toString()`） | 框架把 metadata 序列化成 JSON ⇒ 读回来是 `String` 而不是 `UUID`，比较也在 JSON 层。**混类型会静默失配** |
| null 值 | 用**哨兵值**，不要用 `null` | ★ PgVector 的过滤转换器**没实现 `IS NULL` / `IS NOT NULL` / `NOT`**（遇到会抛 `Not supported expression type`） |

> ★ **顺带纠正 §二 中间件总表的一句**：那里写的扩展是 `vector` / `hstore` / `uuid-ossp` ——
> 那是**框架 `initializeSchema` 会建的三个**。**我们关掉它之后，PG 只需要 `vector` 一个**：
> `hstore` 与 `uuid-ossp` 都用不上（主键由应用侧生成，不需要 `uuid_generate_v4()`）。

### 9.3 多向量模型：**按维度分表 + 路由收在一处**

**问题**：`KnowledgeBase.embeddingModelId` 是**每个库各自绑定**的（契约允许同时存在多个向量模型），
而**维度是表级的** ⇒ 两个库分别用 768 与 1024 维模型时，向量**无法共表**。

**定法：按维度分表，路由收在 `vectorindex`。**

| 层 | 做什么 |
|---|---|
| 表 | `vec_embedding_768` / `vec_embedding_1024` … —— **一个维度一张表**，由 Flyway 建 |
| Bean | 一张表一个 `PgVectorStore` Bean（各自的 `vectorTableName` + `dimensions`） |
| **路由** | **`vectorindex` 暴露唯一入口："给定 `modelId` / 维度 → 返回对应的 `VectorStore`"**；`ingestion` 与 `retrieval` **永远不直接注入 `VectorStore`** |
| 同维度多模型 | **共表**，用 `metadata.model_id` 过滤区分（省表、省 Bean） |

**为什么路由必须收在一处**：★ 框架的自动配置是 `@ConditionalOnMissingBean` 的**单个** `vectorStore` Bean；
多个 store 只能靠 `@Qualifier` 手工区分 —— **"该用哪个 store"这个判断一旦散落，就是两处真相**
（同 `07 §3.3`"可见性判定只在后端一处实现"的纪律）。
这也正好让 `vectorindex` 的存在落地：§5.1 已把它降级为**薄封装**，而**路由就是这层薄封装的核心职责**。

⚠️ **另一个上限要进能力校验**：★ 官方写明 **HNSW 索引维度上限 2000**。
向量模型的维度是 `modelcatalog` 的能力元数据（§3.13.2）⇒ **注册时就该拦下超 2000 维的模型**。

### 9.4 配置项（以及 §4.0 的一处修正）

```yaml
spring:
  ai:
    vectorstore:
      pgvector:
        initialize-schema: false        # ★ 保持关闭：表由 Flyway 建（§4.0 的"必须显式打开"在此修正）
        schema-validation: true         # 启动即校验"恰好四列"，防列漂移
        table-name: vec_embedding_768   # 单表场景的默认值；多维度时由 vectorindex 用 Builder 建 Bean
        dimensions: 768
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        max-document-batch-size: 64     # ★ 默认 10000 太大（理由见下）
```

**`max-document-batch-size` 为什么必须调小**：★ 框架的流程是"**整批一次调 `embed`**，再分批写库"（源码 L261-274）。
默认 10000 意味着**一次向网关发一万个分段的嵌入请求** —— 对上游不友好，也让 `03 §3.4` 的"每批回写进度"失去粒度。
**取 32–64**，与"每批回写 `processedSegments`"对齐。

> **一句留给将来的话**：列名硬编码意味着将来若真需要 `segment_id` 这样的列名，**只能绕过 `PgVectorStore`**（走 `getNativeClient()`）。
> 现在不是问题 —— 眼下它带来的收益（索引 / 距离 / 过滤 / 批量 / upsert 全由官方承担）远大于列名的审美。

---

## 十、技术选型收口总表（★ 2026-09 · M0 开工判据）

### 10.0 "选型做完"的判据

> **每一个会用到的能力都有名字；每一个"不需要"的都有理由与证据。**
> 下面 §10.1 **每一行都必须填满**（填"选型"或"不需要 + 证据"），**不留空格** ——
> **这一节就是 M0 的开工判据**：哪一行空着，哪个模块就不具备开工条件。

### 10.1 全量对账（六组 · ★ 本节为收口，未标的行出处见同组备注）

**A · 运行时与框架**

| 能力 | 选型 | 状态 |
|---|---|---|
| 语言 / 运行时 | **Java 25**（LTS；虚拟线程自 21 已转正） | 已定（§8.1） |
| **JDK 发行版** | **Eclipse Temurin 25** | ★ 本轮定 |
| 框架 | Spring Boot **4.1.x**（Framework 7.x / Security 7.x） | 已定（§4.0） |
| 模块化 | Spring Modulith **2.1.x** | 已定（§4.0） |
| Web | **Spring MVC** + 虚拟线程（`spring-boot-starter-webmvc`） | 已定（§4.5） |
| 持久化 | Spring Data JPA（Hibernate **7.4.5** / JPA 3.2） | 已定（§4.0） |
| 校验 | `spring-boot-starter-validation`（Jakarta Validation） | 已定（§4.0） |
| 序列化 | **Jackson 3**（`tools.jackson`，Boot 4 默认） | 已定（§4.4） |
| **连接池** | **HikariCP**（Boot 默认，不换） | ★ 本轮登记 |
| 调度 | Spring `TaskScheduler`（多实例加 ShedLock 7.x） | 已定（§3.11） |
| 缓存 | **Caffeine**（进程内；不引 Redis） | 已定（§3.3） |
| 事件 / 异步 | Modulith 事务事件 + **出站事件表**（不引 MQ） | 已定（§3.4） |

**B · 数据与文本**（本轮补齐的**三项真缺口**都在这一组）

| 能力 | 选型 | 状态 |
|---|---|---|
| 关系库 | **PostgreSQL 16 + pgvector** | 已定（§3.1） |
| **PG 扩展** | **只有 `vector`**（§9.2：不用 `hstore` / `uuid-ossp`） | ★ 本轮定 |
| 迁移 | **Flyway**（`spring-boot-starter-flyway` + `flyway-database-postgresql`），每模块一个目录，**向量表也由它建** | 已定 + ★ 本轮定（§9.2） |
| **主键 ID** | **UUID，时间有序** —— Hibernate `@UuidGenerator(style = TIME)`；要 v7 时用自定义 `UuidValueGenerator`。**零新依赖** | ★ 本轮定（§10.2 #5） |
| 向量存储 | **`PgVectorStore`** + **按维度分表 + `vectorindex` 路由** | 已定 + ★ 本轮定（§9.3） |
| **中文全文检索** | ★ **PG 扩展 `zhparser`（真分词）+ 自制 PG 镜像**：扩展在镜像 initdb、配置在 Flyway、启动自检 | ★ 已拍板（§10.2 #3） |
| 切分 | 自研（字符长度 50–4096 / 重叠 0–1000，`ChunkStrategy` 已定） | 已定（`02:460`） |
| **token 计数** | **不需要** —— 我们按**字符**切分与守卫上下文，不做 token 预算 | ★ 本轮确认 |
| **文档解析** | ★ **`tika-core` + 三个细粒度 parser 模块**（`tika-parser-pdf-module` / `-microsoft-module` / `-text-module`）—— **不用** `tika-parsers-standard-package`（4.0 起是 POM 聚合包） | ★ **已改向**（§10.2 #1） |
| **解析产物** | ★ **Markdown**（`ToMarkdownContentHandler`）—— 让**标题层级与表格**成为分块器可用的信号 | ★ 见 `12 §二` |
| **Markdown 解析** | 由 `tika-parser-text-module` 承担（`tika-core` 内嵌 commonmark 做序列化） | ★ 已改向（§10.2 #1） |
| **MIME 内容校验** | `tika-core` 的 `Detector`（magic bytes）**+ 解析器兜底** | ★ 本轮定（§10.2 #2） |
| **OCR** | **不需要** —— MVP 明确不支持扫描件；扫描件给**可读失败原因** + "转 Markdown 再传"的替代路径 | 不需要（`04:357`） |
| **PDF 结构化** | ★ **分级**：默认段落流 → tagged PDF 开 `extractMarkedContent` → **叠一层中文编号启发式**（第X条 / （一）/ 1.1） | ★ 见 `12 §2.3` |
| **PDF 表格抽取** | **不做结构化** —— 表格按文本流出来（官方明示"不计算单元格/行边界"）；`04` 的"解析器分级"落成 §2.3 的三级 | 不需要（MVP） |
| **DOCX 列表层级** | ⚠️ **已知上限**：Tika 不产出 `ul/ol/li`（只把编号当字符写进段首）⇒ M0 量化占比后再决定是否加 POI 补丁 | 见 `12 §2.5` |
| **Excel / Word / PDF 导出** | **不需要** —— 契约里**零个导出端点**（唯一的"导出"是应用 JSON 包 `GET /assistants/:id/export`） | 不需要（`api/assistants.ts:326`） |
| V1.1 的 PPT 生成 | **复用 POI 的 XSLF**（同一依赖，零新增） | 只需登记（§10.4） |
| V1.2 的爬虫 | jsoup（静态页）+ 浏览器自动化（JS 渲染页） | 只需登记（§10.4） |

**C · 安全与身份**

| 能力 | 选型 | 状态 |
|---|---|---|
| 认证 | Spring Security 7 责任链 + `oauth2ResourceServer().jwt()`（Nimbus） | 已定（§4.0 / `08 §4.2`） |
| 令牌 | 双令牌（access 30m / refresh 7d），**刷新令牌轮换落 PG** | 已定（§3.15） |
| 授权 | `@PreAuthorize` + 官方 `AuthorizationManager`；权限点为**代码常量、启动幂等同步** | 已定（`08 §4.3`） |
| 数据范围 | `AccessScopePort`（接口在 `platform`，**唯一实现在 `identity`**） | 已定（`08 §4.4`） |
| 公开令牌 | `PublicTokenResolver`（**一个入口**，两套凭证合并） | 已定（`08 §4.2.3`） |
| **密码存储** | `BCryptPasswordEncoder`（官方，强度用默认值） | ★ 本轮登记 |
| CORS / CSRF | Security 的 `cors()` + CSRF 关闭（令牌式无状态 API） | 已定（`08 §4.2`） |
| **接口限流（我们自己的 API）** | **Resilience4j 的 `RateLimiter`**，**先只用于"登录"与"公开令牌"两个面** | ★ 本轮定 |
| 验证码 / 短信 / 邮件 / 找回密码 | **不需要** —— 契约里 **0 个**相关端点（登录只有 `username` + `password`） | 不需要 |

**D · AI 与检索**

| 能力 | 选型 | 状态 |
|---|---|---|
| 模型调用 | Spring AI **2.0.1** + `spring-ai-starter-model-openai`（**`base-url` 指向 JAiRouter**） | 已定（§3.13） |
| 网关 | **JAiRouter**（负载均衡 / 限流 / 熔断 / 降级都在它这一层） | 已定（§3.13.2） |
| 结构化输出 | 官方 `BeanOutputConverter` + `validateSchema()` + **只自写一层宽松解析** | 已定（§5.2） |
| **混合检索融合** | **RRF，自研**（两路排名加权求和，约 30 行） | ★ 本轮登记 |
| rerank | **不做** —— 契约里 `reranker-node` 属"规划中"节点（`implemented: false`） | 不需要（MVP） |
| 网关认证 | 服务账号 / API Key，**不透传平台用户身份** | 已定（§3.13.3） |

**E · 工程与交付**（★ 本轮补齐的一大块 —— 这一组以前几乎是空的）

| 能力 | 选型 | 状态 |
|---|---|---|
| 构建 | **Maven 3.9.16**（★ 4.x 仍是 RC，官方明确"不用于生产"） | ★ 本轮定 |
| 格式化 | **Spotless 3.10.3 + google-java-format**（★ JDK 16+ 需 `--add-exports jdk.compiler/...`） | ★ 本轮定 |
| 静态检查 | **Checkstyle 14.1.0 + SpotBugs 4.10.4** | ★ 本轮定 |
| 单元测试 | JUnit 5 + AssertJ + Mockito（Boot `starter-test` 自带） | ★ 本轮定 |
| 集成测试 | **Testcontainers 2.x** + `@ServiceConnection`；镜像用 `pgvector/pgvector:pg16` | 已定 + ★ 本轮补镜像 |
| 接口文档 | **REST Docs**（Boot 4 用 `spring-boot-starter-restdocs`） | 已定（§4.0） |
| **契约漂移检测** | **自研**（约 100 行）：`frontend/src/api/**` 的 method+url ↔ 启动后的 `RequestMappingHandlerMapping`，不一致即失败 | ★ 本轮定（§10.2 #8） |
| CI | **GitHub Actions**（`actions/setup-java@v6` + `distribution: temurin` + `java-version: 25`） | ★ 本轮定 |
| 应用镜像 | 多阶段构建 → **`eclipse-temurin:25-jre`** | ★ 本轮定 |
| 本地编排 | `docker-compose.yml`：app + postgres(pgvector) + jairouter | 已定（§6.2） |
| 依赖漏洞扫描 | **OWASP Dependency-Check**（或 Trivy 的 `fs` 模式） | ★ 本轮定 |
| 许可证合规 | `license-maven-plugin` 生成**后端**依赖清单，并入根 `THIRD-PARTY-NOTICES.md` | ★ 本轮定 |
| **日志** | **Boot 原生结构化 JSON**：`logging.structured.format.console=ecs`（★ 3.4 起内置，**不需要 `logstash-logback-encoder`**） | ★ 本轮定（§10.2 #4） |
| 可观测 | Actuator + Micrometer → Prometheus / OTLP（`spring-boot-starter-opentelemetry`） | 已定（§3.12） |
| **E2E（对真后端跑三场景）** | **复用前端已有的 Python + Playwright 脚本**（`smoke_test.py` 等，`VITE_USE_MOCK=false`） | ★ 本轮定 |
| **前端测试栈** | **维持现状**（`vue-tsc` + 11 个自写脚本 + Playwright CLI），**不引 vitest / jest** | ★ 本轮定 |
| **前端 lint** | **暂不加**（现状只有 `vue-tsc`）；要加就是 ESLint + Prettier | ★ 本轮定（记录取舍） |
| 压测 | JMeter 或 k6（B7 = 连续 200 次问答） | 只需登记（M6） |
| 备份 / 恢复 | `pg_dump` + 文件存储目录同步（脚本即可，不引工具） | 只需登记（运维） |

**F · 明确不需要（以及"为什么不需要"的证据）**

| 被排除的东西 | 证据 |
|---|---|
| 消息队列 / 注册中心 / 配置中心 | `09 §3.4` · §3.8 · §3.9（ADR-03） |
| Redis / Spring Session | `09 §3.3` · §3.15（无状态 JWT） |
| 独立向量库（Milvus / Qdrant） | ADR-02（pgvector 同库） |
| Elasticsearch | `09 §3.6`（PG 全文起步） |
| MinIO / OSS | `09 §3.7`（端口 + 本地磁盘） |
| WebFlux | ADR-01 / `09 §4.5`（**决定性理由是 JPA 与事务**） |
| Spring Batch / Statemachine / Integration / Cloud | `09 §4.2`（Statemachine ★ 已归档） |
| Sentinel / Seata / Vault / Apollo | `09 §3.5` · §3.8 · §3.10 |
| **WebSocket** | 契约里**零个** WS 端点（实时只有 SSE + 定时轮询） |
| **OCR（PaddleOCR 等）** | `ROADMAP:297`；契约的失败提示是"转为可检索 PDF 或 Markdown" |
| **验证码 / 短信 / 邮件 / 找回密码** | 契约里 **0 个**相关端点 |
| **i18n / 多语言** | 契约与页面**单语言**（`main.ts` 只有 `zhCn`，无 i18n 资源） |
| **Excel / CSV / Word / PDF 报表导出** | 契约里 **0 个**导出端点 |
| 多租户 | `08 §4.12`（软关闭，V2.0） |

### 10.2 本轮新定的八项（逐项给理由与证据）

**#1 文档解析：`tika-core` + **三个细粒度 parser 模块**（★ 2026-09 二次改向）**

> ⚠️ **本节改过一次向，记录在此**：最初写的是"精准组合：`tika-core` + PDFBox + POI（自己抽文本）"。
> **那个结论有缺陷** —— ★ 只引 `tika-core` 的话，**`AutoDetectParser` 没有任何 `Parser` 实现可用**
> （`ToMarkdownContentHandler` 只是"XHTML → Markdown"的**序列化器**，不含解析器），
> 等于**把 Tika 的解析能力全部绕开**。项目负责人的要求是对的：**多用 Tika 的能力，少引自写胶水**。

- **选**（解析 MVP 的 5 种格式所需的最小集）：

  | 依赖 | 负责 |
  |---|---|
  | **`tika-core`** | MIME 嗅探 · `AutoDetectParser` · **`ToMarkdownContentHandler`** · 编码检测（`CharsetDetector`）· 语言检测 |
  | **`tika-parser-pdf-module`** | PDF（★ 内部即 **PDFBox 3.0.8**，与 `§4.0` 的选型一致） |
  | **`tika-parser-microsoft-module`** | `.docx` / `.doc`（★ 内部即 **POI 5.5.1**） |
  | **`tika-parser-text-module`** | `.md` / `.txt` / `.csv` |

- **不选 `tika-parsers-standard-package`**：★ 4.0 起它是 **POM 聚合包**（须 `<type>pom</type>`），拖进
  **OCR / CAD / 音视频 / 邮件 / 字体 / 加密**等模块 —— 而我们**明确不做 OCR**。
  ⇒ **规避它的正确方式是引细粒度模块，而不是放弃 Tika**（Tika 3.0 起就已模块化）
- ★ **关键事实**：`tika-parser-pdf-module` 与 `-microsoft-module` **内部锁定的正是 PDFBox 3.0.8 与 POI 5.5.1**
  ⇒ 换到 Tika **不是换一套技术**，而是**同一批底层库之上多了一层 Tika 的统一管线**（装配、识别、结构序列化）
- **装配**：`AutoDetectParser` 靠 **SPI（`ServiceLoader`）** 从 classpath 装配 Parser ⇒
  **不需要配置**；★ 注意 **4.0 已删除 `TikaConfig` 的 XML 配置 API**（改 JSON），照旧文档写会踩
- **版本（★ 2026-09 查证，均 Apache-2.0）**：Tika **4.0.0**（Java 17+，与 JDK 25 兼容；3.3.2 是维护线）·
  ★ `ToMarkdownContentHandler` 引入于 **3.3.0**（`CHANGES.txt` 原文"Add a markdown option for content handlers"，TIKA-4563；
  其 javadoc 写 `@since 3.2` 与发布记录**不一致** ⇒ 以 3.3.0 为准）·
  ★ 4.0 的 `CHANGES.txt`：**默认 content handler 改为 Markdown**（TIKA-4663）且 **Markdown 输出快约 30×**（TIKA-4868）
- ★ **产物是 Markdown，不是纯文本** —— 这是本次改向的真正收益：**结构（标题层级/表格）成为分块器可用的信号**。
  完整设计（分块算法、结构化前缀、标签、摘要）见 **`12-文档加工链设计.md`**
- **必须写进 M0 解析测试的三条已知上限**（★ 逐条来自源码核实）：
  1. ★ **DOCX 的列表会丢层级**：Tika 的 Microsoft 模块**不产出 `ul/ol/li`**，只把 `1.`/`·` 等编号当**字符**写进段首
     ⇒ 用 Word 自动编号的文档受影响；**中文合同多是手工编号（"第X条"）⇒ 不受影响**。M0 要**量化占比**再决定补不补
  2. ★ **普通 PDF 只有段落流**（`<p>` + page div），**Tika 没有字号推断标题的启发式**；
     只有 **tagged PDF**（PDF/UA）开 `PDFParserConfig.setExtractMarkedContent(true)` 才能出标题/表格
     ⇒ 内网多数 PDF 无 tag，所以要**叠一层中文编号启发式**（"第X章/第X条/（一）/1.1"）
  3. **必丢**：图片正文（只剩 `![]()` 占位）、**页眉页脚**（降级 `div`，需自行剥离）、多栏阅读顺序、脚注、文本框

**#2 MIME 内容校验：嗅探 + 解析器兜底（两层）**

- 白名单里 `.doc` 是 **OLE2 容器**（magic `D0 CF 11 E0`）、`.docx` 是 **ZIP**（`PK\x03\x04`）⇒ magic bytes 能区分
- 但 OLE2 里也可能是 `.xls` / `.ppt`，ZIP 里也可能是 `.xlsx` / `.pptx` ⇒ **嗅探只给"像什么"**
- 所以**第二层才是可靠的**：**真的去打开它**（POI / PDFBox 打不开就是坏文件）。
  机制八要防的是"**改扩展名绕过**"，而只有真正解析一次才能证明它是不是那种文件

**#3 中文全文检索：PG 扩展（`zhparser`）+ 自制 PG 镜像**（★ 用户拍板：**要真分词**）

- **先说问题**：`02` 原先写的 `to_tsvector('simple', …)` 对中文**实际不工作** ——
  中文没有空格，整段中文会被当成**一个 token**，于是"全文检索"这一路永远检索不到东西。
  所以这里必须选一条：**装分词扩展**（真分词）还是**应用侧切分**（2-gram，零扩展）。
- **拍板：装扩展，走真分词。** 运维方式是**自制 PG 镜像**：冻结 PG 大版本 + 一个打包脚本，
  扩展与 init SQL 进镜像，将来升级只更新打包 —— **这是这类扩展的标准做法**（Dockerfile 骨架见下）。
- ★ **扩展选 `zhparser`，不选 `pg_jieba`**（2026-09 查证）：

  | | **`zhparser`** | `pg_jieba` |
  |---|---|---|
  | 维护 | ★ **2.4（2026-06）**，官方 CI **覆盖 PG 16 / 17 / 18** | ✗ 末次代码 **2022-11**；PG16/17 只有 **open issue** |
  | 许可 | PostgreSQL License | BSD-3-Clause |
  | 分词引擎 | SCWS 1.2.3 | cppjieba |
  | 现成镜像 | ★ 有官方 Dockerfile 可照抄（多阶段） | 无 |

  ⚠️ 顺带记一个坑：`pg_jieba` 的 parser 名是 **`jieba`**（不是 `pg_jieba`），社区在 PG17/18 上靠自救 ——
  **"用维护中的东西"这条原则在这里正好适用**：两者做同一件事，选维护好的那个。

- **三条必须写进设计的约束**（这一节最值钱的部分）：

  | # | 约束 | 怎么做 |
  |---|---|---|
  | 1 | ★ **`CREATE EXTENSION` 需要超级用户**（两个扩展的 control 都**没有 `trusted=true`**，官方文档写"ordinarily requires superuser"） | ⇒ **扩展必须在镜像 initdb 阶段建好**（`/docker-entrypoint-initdb.d/`），**绝不能放 Flyway** —— 应用连接的库用户不是超管 |
  | 2 | **`CREATE TEXT SEARCH CONFIGURATION` 可以放 Flyway**（配置本身不需要超管） | ⇒ 清晰的切分是「**扩展在镜像、配置在迁移**」：`CREATE TEXT SEARCH CONFIGURATION chinese_zh (PARSER = zhparser)` + `ALTER … ADD MAPPING FOR n,v,a,i,e,l,x WITH simple`（★ 自定义词的 token 类型是 **`x`**，不加这条进不了 tsvector） |
  | 3 | ★ **启动必须自检**（本轮新增要求） | 断言 `pg_extension` 里有 `zhparser` 与 `vector`，并跑一次 `to_tsvector('chinese_zh','合同违约金')` 断言分词正确。**缺扩展时的症状是"全文检索一条都搜不到"** —— 正是本项目最怕的静默失效，所以它要与 `01 §五` 的架构测试同等待遇 |

- **词典可以加业务词，但 tsvector 不会自动重算**（★ `zhparser#59`）：
  合同 / 制度 / 报销规定这类语料**专有名词多**，**能加词典正是本方案相对 2-gram 的主要收益**。
  但 tsvector 是**写入时物化**的 ⇒ 改词典后必须重建（`UPDATE … SET content_tsv = to_tsvector('chinese_zh', content)` 或 `REINDEX`）。
  ⇒ **`kb_segment` 需要一个"重建全文索引"的运维动作**（与 `reembedKnowledgeBase` 同族：由 `TaskScheduler` 跑、按知识库触发）。
- **镜像与打包**：base 取 `pgvector/pgvector` 的**带版本 tag**（如 `0.8.6-pg16-bookworm`，**别用浮动的 `pg16`**），
  多阶段编 SCWS + zhparser（照抄 zhparser 官方 Dockerfile）。
  ⚠️ **PGDG / Debian 没有现成包**（本轮查过 `postgresql-16-zhparser` / `postgresql-16-jieba`，均无）
  ⇒ "自制镜像 + 打包脚本"不是偏好，而是**唯一可行路径**。
- **退路（保留）**：万一打包在某台机器上卡住，**2-gram 是保底方案** —— 它可以先用起来把链路跑通
  （不改表结构，只改写入侧与查询侧），之后换扩展时重建一次 tsvector 即可。**写进设计是因为它便宜**。

**#4 结构化日志：用 Boot 原生的，不引第三方 encoder**

- ★ **Boot 3.4 起内置**：`logging.structured.format.console`，可选 **`ecs` / `logstash` / `gelf`**
- ⇒ §3.12 与 §8.4#4 那句"格式实现库未定"**关闭**：**默认 `ecs`**，**不引 `logstash-logback-encoder`**
- 仍然只是**运维边界**要确认：起步是否只到 stdout（**建议是**，由容器收集；要检索日志时再说）

**#5 UUID 用时间有序：零新依赖**

- **背景**：主键一律 UUID（`02 §5.1`），而 v4 **完全随机** ⇒ B-tree 随机插入、页分裂、索引膨胀；
  `08 §4.11` 一直要找的"ID 生成策略"没有名字
- ★ 事实一：**JDK 25 的 `java.util.UUID` 没有 v7**（只有 `randomUUID()`=v4、`nameUUIDFromBytes()`=v3）
- ★ 事实二：**PG 16/17 也没有 `uuidv7()`**（那是 PG 18 才有的函数）
- ★ 事实三（关键）：**Hibernate 7 自带** —— `@UuidGenerator(style = TIME)`，官方原文是
  *"time-based (IETF RFC 4122 version 1, variant2)"*；还可用 `algorithm = …UuidValueGenerator` **自实现 v7**
- ⇒ **定法**：`platform` 的 ID 策略 = **时间有序 UUID**，实现走 Hibernate 的生成器（或 20 行自定义 v7），
  **不引 `uuid-creator`**
- **收益**：插入近似"追加"，`kb_segment` / `vec_embedding` 这类**写入量大的表**索引更健康

**#6 向量表：见 §九**（表结构、按维度分表、配置项都在那一节，本节不重复）

**#7 工程工具链：见 §10.1 的 E 组**（Maven 3.9.16 / Spotless / Checkstyle / SpotBugs / GitHub Actions / Temurin 25）

**#8 契约漂移检测：自研一个小测试，不引 Spring Cloud Contract**

- `ROADMAP §7.2` 把它列为后端闸门之一，但**实现方式一直没定**
- **选自研**，三段：① 从 `frontend/src/api/*.ts` 抓 `method:` + `url:`（契约里这两项是**字面量**，好抓）
  ② 在 `@SpringBootTest` 里读 `RequestMappingHandlerMapping` 拿**真实映射** ③ 双向比对，缺一即失败
- **为什么不引工具**：契约的真源是**前端那份 TS**，而"它怎么表达端点"只有我们清楚；
  引 Spring Cloud Contract 反倒要它去理解我们的目录约定，还要动前端工程
- **顺带的好处**：它与"**每个写端点都必须声明权限**"那条架构测试（`08 §4.3.2`）**同源** ——
  一次拿到"端点清单"，两条检查都能跑

### 10.3 本轮拍板的两件（★ 已定）

| # | 事项 | 拍板结果 | 附带要求（必须一起落） |
|---|---|---|---|
| 1 | **中文全文检索** | **装 PG 扩展、走真分词** —— 用 **`zhparser`**，不用 `pg_jieba`（后者已近 4 年未维护）；运维走**自制 PG 镜像**（冻结大版本 + 打包脚本） | ★ 三条约束：**扩展在镜像 initdb**（要超管）· **配置在 Flyway**（不要超管）· **启动自检**（缺扩展＝静默失效）；外加"**词典变更后重建 tsvector**"的运维动作（§10.2 #3） |
| 2 | **向量表怎么放** | **按维度分表 + `vectorindex` 路由** | 路由必须**收在一处** —— 否则"该用哪个 store"会散落成两处真相（§9.3） |

> ⇒ 至此 **§10.1 的每一行都有结论**，**技术选型收口完成**。

### 10.4 只需登记、不在 M0 范围

| 项 | 版本点 | 触发条件 |
|---|---|---|
| PPT / Word 生成 | V1.1 —— 复用 **POI XSLF**（已在依赖里） | `documentgen` 模块立项 |
| 在线爬虫 | V1.2 —— jsoup（静态）+ 浏览器自动化（JS 渲染） | `crawler` 模块立项 |
| MCP 接入 | V1.2 —— 协议仍在演进（`06 §5.0`） | 同上 |
| 独立向量库 | 单表向量到千万级 | `retrieval` 的 P95 超预算 |
| 压测 | M6 —— JMeter 或 k6 | B7 验收（连续 200 次问答） |
| 备份 / 恢复 | 运维 —— `pg_dump` + 文件目录同步 | 部署上线前 |

### 10.5 与其它文档的对账（★ 本轮**已同步**）

> 下表列的是**本轮已经改完**的对照关系 —— 记下来是为了让"改了什么、为什么改"可追溯，**不是待办**。
> **唯一没同步的是 `innerdoc/` 下的内部笔记**（见 §10.6 的提醒）。

| 文档 | 本轮改了什么 | 依据 |
|---|---|---|
| `backend/README.md` | 技术选型表：**Java 21 → 25**、**Boot 3.x → 4.1.x**，去掉"待确认"，补 web 栈 / 文档解析 / 迁移 / 测试 / CI | ★ 改之前它写着 **Java 21 + Boot 3.x**，与 `09` 直接矛盾 —— 现已同步 |
| `backend/README.md:13` | 部署进程口径统一为 **3 个平台进程 + Nginx** | 与 `09 §二`一致（"4 个容器"是含反向代理的旧算法） |
| `00 §3.1` | 补齐本轮新定的行（文档解析 / **中文全文检索** / UUID / 日志 / 工具链），并指向 `09 §十` | 总览表要能落到收口表 |
| `00 §七` 文档索引 · `README.md:49,95` | 系统设计**10 册**（不是 8 册）；`09` 的描述补"选型收口" | 索引与实际不符 |
| `02:583-594` | `content_tsv` 改为基于 **`chinese_zh` 分词配置**（`PARSER = zhparser`）的生成列 + GIN，并补一段"扩展在镜像 / 配置在 Flyway / 启动自检 / 词典变更要重建" | 否则中文全文检索不工作（§10.2 #3） |
| `02:590-604` | `vec_embedding` → **按维度分表**，列名迁就框架四列 | `PgVectorStore` 硬约束（§9.2） |
| `04:357,365` | R3 的"解析器选型"**关掉**：写清选了谁、不支持什么（表格 / 扫描件） | 风险表里不能留着"未定" |
| `ROADMAP §7.2` | 契约漂移检测的**实现方式**写清（自研）；后端测试栈点名 | 闸门要有可执行形式 |
| `09 §4.0` | `initialize-schema`：从"**必须显式打开**"→"**保持关闭 + Flyway 建表**" | §9.4 纠正 |
| `09 §二` | PG 扩展：`vector` / `hstore` / `uuid-ossp` → **只有 `vector`** | §9.2 纠正 |
| `09 §8.4` | 待办关掉两项（中文分词、日志），其余保留 | 收口 |
| `THIRD-PARTY-NOTICES.md` | 补**后端**依赖清单（M0 用 `license-maven-plugin` 生成） | 现在只有前端 |
| **新增 `10-RAG-检索策略设计.md`** | 策略层成册（查询理解 / 分解与配额合并 / 多跳 / 预算与降级 / **契约影响清单**）；并连带回写 `02`（`RetrievalConfig` 加字段）、`03 机制二`（加指针）、`04`（B1 口径与问答对）、`ROADMAP`（M3 范围） | 选型收口后发现的最大设计缺口（见 `10 §1.1`） |
| **新增 `11-RAG的SpringAI落地设计.md`** | 落地层成册：Spring AI 2.0.1 的 Advisor 体系与六个 RAG 零件接口（★ 全部对着 javadoc/源码核实）、四个矛盾的解法、组件清单、线程模型、`refs` 端到端链路、测试策略；并回写 `10`（多跳粒度、依赖矩阵核查结论） | 把 `10` 的策略挂到具体扩展点上（见 `11 §一`） |
| **新增 `12-文档加工链设计.md`** | 加工侧成册：**解析改向 Tika 统一管线（产物 Markdown）**、分块算法（四层优先级 + 表格/代码保护）、**结构化前缀**（零 LLM 拿召回大头）、标签的三种用法、摘要三层方案、**28 项遗漏检查清单**；并回写 `02`（字段与 DDL 增量）、`04 R3`、`11 §5.2 R4` | 见 `12 §八` —— 原设计在这条链上空白 16 项 |
| `11` 连带确认的选型事实 | ★ 官方 advisor 默认值（memory `+200` / tool `+300` / RAG `0`）· 流式**只有 Reactive**、**无 MVC `SseEmitter` 官方示例** · **2.0 删除项**（`internalToolExecutionEnabled` / `SpringBeanToolCallbackResolver` / **`PromptChatMemoryAdvisor` 不存在**）· ★ 官方已有 `SemanticCacheAdvisor` 与 `StructuredOutputValidationAdvisor`（别自研） | 见 `11 §2` —— 这些是**会写错代码**的地方 |

### 10.6 三条"不是选型问题、但会卡住后端实现"的契约缺口

> 这三条是**盘点契约时顺手发现的**（不在选型范围，但会在 M2/M4b 撞上）。
> ⚠️ 另有一份 **`innerdoc/docs/契约缺口清单.md`（C1–C20）已经过期** ——
> 其中 C1（提示词域）、C4（停止生成）、C5（重新生成）、C13（会话附件上传）**在前端契约里都已经补齐**，
> 后端**不要照那份旧清单去实现**。它需要一次整体复核。

| # | 缺口 | 证据 | 影响 |
|---|---|---|---|
| 1 | **重生成 / 续写的请求体缺字段** | `stream-types.ts:155-157` 与 mock 里有 `mode` / `targetMessageId`，但 `lib/sse.ts:170-176` 实际发送时**没带** | 若"重生成"要走同一个 SSE 端点，**现在这条链是断的** —— 实现 M4b 前要定 |
| 2 | **三处文件类型清单不一致** | 知识库上传 `.pdf/.docx/.doc/.md/.txt`；聊天附件**多一个 `.pptx`**；工作流 `fileAccept` 还含 `xlsx/csv/html` | `GET /upload-policy` 只覆盖第一处，**另两处是前端写死的** ⇒ 后端要决定是否统一 |
| 3 | **没有乐观锁** | 契约无版本号 / `If-Match` 字段，而画布**每 60s 自动保存草稿**（`workflow/Index.vue:252-254`） | **两人同时编辑会静默后写覆盖前写**。MVP 可接受，但要**显式记账** |

---

### 一条贯穿的原则

> **能不加的组件就不加；能不加的代码就别写。**
> 本篇两处改向（向量、结构化输出）都是这条原则的产物 ——
> **它们不是"用框架更时髦"，而是"我们本来要写的那部分，官方已经写完并测过了"。**

