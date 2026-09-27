# 变更记录

> **本文件写"这一版能做什么"，不写"改了哪些文件"**（`ROADMAP §4.7.1` 判据 4）。
>
> 版本号规则见 `ROADMAP §4.3`：`0.<阶段>.<周>` —— **补丁位 = 周**，每周一个可发布的版本。
> 也就是说，这张表里的每一行都对应"一周结束时可演示的东西"，而不是一次提交。

---

## 0.0.1 · 第 1 周 · M0 骨架

### 这一版能做什么

**起一套空系统**：`docker-compose up -d` 起 PostgreSQL（pgvector 镜像），应用本地启动，
`curl /actuator/health` 返回 `{"status":"UP"}`。数据库里已有 2 个迁移的痕迹、pgvector 扩展已装、
Modulith 的出站事件表已就位。**没有任何业务功能** —— 这一版交付的是"约束"。

**把架构约定变成构建失败**：模块边界、越界依赖、内核纯净性三者都是自动化闸门，
且各自带一条**金丝雀**（故意破坏时必须变红）。

**契约的地基**：响应包络 `{code,message,data,hint?}` 与 8 个错误码已按前端冻结契约实现并断言。

### 交付物

| # | 交付 | 怎么验 |
|---|---|---|
| 1 | **12 个模块**的边界声明（`@ApplicationModule`） | `ModuleBoundaryTest` 断言 12 个模块全部被识别 —— 数量不对说明有 package-info 没生效，此时 `verify()` 的绿是假绿 |
| 2 | `ApplicationModules.verify()` | 同上测试类 |
| 3 | ★ **越界依赖金丝雀** | 同一个规则函数跑两个命名空间：对应用代码断言"必须通过"、对 `canaryfixture` 样本断言"必须失败" |
| 4 | **内核纯净性**：`platform` 禁 `org.springframework.ai.*` / `reactor.*` | `PlatformPurityTest` |
| 5 | **响应包络 + 错误码** | `ApiResponseTest` 逐项对照 `frontend/src/api/types.ts` 的 8 个码 |
| 6 | **迁移 V1**：pgvector 扩展 | `MigrationIntegrationTest` 查 `pg_extension` |
| 7 | **迁移 V2**：Modulith 出站事件表 | 同上；列集由实体生成核定，见下"这一版踩到的坑" |
| 8 | **Security 责任链**（放行 health / auth / public） | 上下文启动 + `curl /actuator/health` 免认证返回 200 |
| 9 | **单例 `ChatClient`**，`base-url` 指向 Ollama 的 `/v1` | 上下文启动即证明装配合法 |
| 10 | **CI**：后端 `mvn verify`（含 Testcontainers 真库）+ 前端 8 个静态闸门 + 类型检查构建 | `.github/workflows/ci.yml` |
| 11 | **Testcontainers 2.x + 真 PG**（`pgvector/pgvector:pg16`） | 16 个测试全绿 |
| 12 | **三份技术结论的探针**与判定口径 | `ModelCapabilityProbeTest`（模型缺席时**整体跳过**，不让构建变红）+ `docs/技术结论/M0-技术结论.md` |

### 验证记录（本地实跑，不采信旧绿）

```
mvn -B verify         → BUILD SUCCESS，Tests run: 16, Failures: 0, Errors: 0
                        Spotless: 25 files clean
前端 8 个静态闸门      → 全部 exit=0
                        （verify_refs / verify_features / verify_permissions /
                          check_template_tags / check_theme / check_design /
                          check_nodeconfig / check_canvas）
curl /actuator/health → HTTP 200 {"groups":["liveness","readiness"],"status":"UP"}
```

### ★ 这一版踩到的坑（都是"照老习惯写就一定错"的那类）

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | `Non-parseable POM`：注释里 `--` 非法 | XML 注释**不能含双连字符**，而我在注释里写了 `--add-exports` | 改成"为 jdk.compiler 打开 exports"的表述 |
| 2 | `Unknown lifecycle phase ".check.skip=true"` | PowerShell 把 `-Dspotless.check.skip=true` 拆开了 | `-D` 参数加引号 |
| 3 | `'构建...' is not recognized as an internal or external command` | **`.cmd` 文件被 cmd.exe 按 OEM 代码页读取**，UTF-8 中文注释被当成命令 | Maven 封装脚本改为**纯 ASCII** |
| 4 | `BATCH RECURSION exceeds STACK limits` | 脚本名为 `mvn.cmd`，而当前目录优先于 PATH ⇒ `call mvn` 调用的是自己 | 改名 `mvn-jdk25.cmd` |
| 5 | `google-java-format ... requires at least 1.30.0` | **1.28.0 不支持 JVM 25** | 钉 1.30.0（不是"越新越好"，是被 JDK 25 下限钉住的） |
| 6 | `'dependencies.dependency.version' for org.testcontainers:junit-jupiter is missing` | **Testcontainers 2.x 把模块 artifact 统一改名为 `testcontainers-<module>`** | 改 `testcontainers-junit-jupiter`，版本由 `testcontainers-bom:2.0.5` 管 |
| 7 | `PostgreSQLContainer<?>` 编译不过 | 2.x 的 `org.testcontainers.postgresql.PostgreSQLContainer` **不再是泛型类** | 去掉类型参数 |
| 8 | `Schema validation: missing table [event_publication]` | `ddl-auto=validate` + `starter-jpa` 已把 Modulith 的事件实体注册进实体扫描 ⇒ **不建表应用起不来** | 建 V2；列集**不照文档抄**，用 Hibernate 生成后核定 |
| 9 | `create table event_publication` 里 `serialized_event` 是 `varchar(255)` | Hibernate 对普通 `String` 的默认长度 —— 但**事件是序列化 JSON，255 必然溢出** | 迁移里改用 `text`，并由集成测试实测 validate 接受 |
| 10 | `ports are not available ... bind: forbidden by its access permissions` | ★ 本机 Windows **保留端口段含 5281-5380**，5432 正在其中 | compose 宿主端口默认改为 **15432**（可用 `AIP_DB_PORT` 覆盖） |

### 环境事实（第 2 周起照这个来，避免重复踩）

| 项 | 值 |
|---|---|
| JDK 25 位置 | `D:\Java\jdk-25`（Temurin 25.0.4.1）；构建用 `backend\mvn-jdk25.cmd`，不改系统 `JAVA_HOME` |
| PG 宿主端口 | **15432**（本机 5432 被系统保留） |
| compose 命令 | **`docker-compose`**（带连字符）—— 本机 CLI 未加载 compose 插件，`docker compose` 会报 unknown command |
| Maven | 3.9.11（系统在 PATH），JDK 由封装脚本固定为 25 |

### ⚠️ 本版发现的漂移与欠账（不修，但要记账）

| # | 事项 | 性质 |
|---|---|---|
| 1 | ★ **前端丢 `hint`**：`08 §4.1` 称 `hint` 是"一等字段"，后端也按它实现，但前端 `src/lib/http.ts` 在**两条构造 `ApiError` 的路径上都没有取 `hint`**（成功响应里的非 0 码、HTTP 错误分支）⇒ 后端填了也到不了界面 | 前端欠账，需 2 行修复 |
| 2 | 契约 `ApiResult<T>` 是 `{code,message,data}`，**不含 `hint`** | 后端多输出一个可选字段，契约允许；但要与第 1 条一起收敛 |
| 3 | `01 §二` 写"11 个模块"，而 `backend/README.md` 已对账为 **12 个**（含 `workflow`）⇒ `01` 的模块清单与依赖矩阵**缺 `workflow` 一行** | 文档待回写；本版按 README 的 12 个实现，`workflow` 的依赖位置为**本轮推定**（取与 `assistant` 相同的依赖集），待架构评审确认 |
| 4 | `ROADMAP §7.1` 说前端"11 个脚本"，实际目录里有 **19 个** `.py` 闸门 | 计数漂移，进 CI 的是其中 8 个静态闸门 |
| 5 | Surefire 报 `going to kill self fork JVM ... elapsed 30 seconds` | Testcontainers/Spring 上下文未干净关闭，拖慢构建 30s；不影响结果，待查 |
| 6 | `ROADMAP §6.1 P3` 说"**两份**技术结论"，`§4.4` 说"**三份**"（多了"能否稳定产出结构化输出"） | 文档内部不一致；本版按 `§4.4` 的三份执行 |
| 7 | `smoke_test.py` / `shot_sweep.py` / `shot_workflow.py` **未进 CI**（需 dev server + 浏览器） | 详见 `ci.yml` 的注释 |
| 8 | `01 §5.2` 的依赖声明示例与包结构示例用的是 `com.example.platform.*`，实际是 `cn.lincoln.aiplatform.*` | 示例性代码，不影响实现；回写 `01` 时一并更正 |

### 三份技术结论（`ROADMAP §6.1` P3）

> ★ **凭证已就绪，结果待出** —— 判定口径与探针都写好了，缺的只是本地模型服务
> （P2：Ollama + 模型 ~9GB）。**不把没跑过的东西写成结论**。
>
> - 判定口径来自冻结契约：`frontend/src/api/models.ts` 的
>   `ThinkingMode = 'NONE' | 'TOGGLEABLE' | 'ALWAYS_ON'`
> - 怎么测 / 看什么 / 判据 / 后果：`docs/技术结论/M0-技术结论.md`
> - 探针：`ModelCapabilityProbeTest`。★ 它在 Ollama 缺席时**整体跳过**，所以不会让 `mvn verify`
>   在任何没装模型的机器上变红；代价是报告里记 `Tests run: 0`（JUnit 类级 assumption 的既有表现）

| # | 结论 | 状态 |
|---|---|---|
| ① | 各模型思考模式三态取值（`NONE` / `TOGGLEABLE` / `ALWAYS_ON`） | ⏳ 探针就绪，待模型 |
| ② | Spring AI 能否下发思考开关（★ **两侧分开测**：框架有没有透出 / 上游认不认这个字段） | ⏳ 探针就绪，待模型 |
| ③ | 能否稳定产出结构化输出（N=20 的失败率与失败形态） | ⏳ 探针就绪，待模型 |

### 未做（刻意的，不是遗漏）

- **不引 `spring-ai-starter-vector-store-pgvector`** 与 **Tika**：M2 有向量表之后再引（`09 §9.4`）
- **不写 `GlobalExceptionHandler`**：它需要"HTTP 状态 ↔ 业务码"的映射规则，而那是端点契约的细节 ——
  第 2 周有第一个端点时用真契约测试来定，现在猜一个等于埋一个静默失效
- **不做应用的容器化**：开发期形态是 **2 个进程**（PG + 应用），见 `ROADMAP §4.6`
- **Checkstyle / SpotBugs**：按需引入（`09 §4.3`），记在 `pom.xml` 的注释里
