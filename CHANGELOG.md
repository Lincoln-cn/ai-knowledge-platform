# 变更记录

> **本文件写"这一版能做什么"，不写"改了哪些文件"**（`ROADMAP §4.7.1` 判据 4）。
>
> 版本号规则见 `ROADMAP §4.3`：`0.<阶段>.<周>` —— **补丁位 = 周**，每周一个可发布的版本。
> 也就是说，这张表里的每一行都对应"一周结束时可演示的东西"，而不是一次提交。

---

## 0.1.1 · 等保补齐（第 2 周范围内的追加，不是新的一周开始）

### 这一版能做什么

**登录多了三道等保要求的关，口令强度有了硬约束**：

```
GET  /api/v1/auth/captcha               → { id, image:"data:image/png;base64,…" }
POST /api/v1/auth/login                 → 不带验证码 ⇒ 400 + code 1003
                                         验证码只能用一次（重放 400）
                                         密码连错 3 次 ⇒ 403 + code 1001（锁定 30 分钟）
                                         锁定期间**正确密码也进不去**
POST/PUT /api/v1/users/me/password      → 弱口令 ⇒ 400 + code 1002（带具体原因）
                                         改密成功 ⇒ 该用户全部刷新令牌被撤销
```

### 交付物

| # | 交付 | 关键点 |
|---|---|---|
| 1 | **契约**：`ErrorCode` 加 `BAD_REQUEST(400)` / `ACCOUNT_LOCKED(1001)` / `PASSWORD_TOO_WEAK(1002)` / `CAPTCHA_INVALID(1003)`；`LoginRequest` 加可选的 `captchaId`/`captchaCode`；新增 `getCaptcha()` | ★ 用户点名要的——契约是唯一真源，所以**先改契约再改实现** |
| 2 | **迁移 V5**：`iam_user` 加 `failed_login_attempts` / `locked_until` | ★ 落库而非放内存：锁定是"跨越重启的约束"，重启一次不该送攻击者一轮新机会 |
| 3 | **`PasswordPolicy`** | 长度 ≥8、四类字符中至少 3 类、不含账号名；★ 上限 64 是因为 BCrypt 在 72 字节处**静默截断** |
| 4 | **`CaptchaService`** | Java2D 画 PNG + Caffeine TTL 2 分钟 + **一次性消费**；★ 不引 kaptcha —— JDK 与 Caffeine 已经够用 |
| 5 | **`AuthService` 登录四道关** | 验证码 → **锁定检查** → 密码 → 账号状态。★ 锁定放在密码校验**之前**：已锁定就不必再跑最贵的 BCrypt |
| 6 | **`AuthSecurityRecorder`** | ★ 独立事务（`REQUIRES_NEW`），见下"踩到的坑" |
| 7 | **`PUT /users/me/password`** + 强度校验 | 改密成功后撤销该用户全部刷新令牌 |
| 8 | **27 个新测试** | 口令强度 9 · 验证码 7 · 等保端到端 11 |
| 9 | ★ **前端登录页接入验证码** | `pages/login/Index.vue` 加验证码图与输入框（图形比例与后端一致）；**任何失败之后都换一张** —— 验证码是一次性的，不换会让用户陷入"怎么输都说验证码不对"的死循环。mock 侧补 `/auth/captcha`，让 mock 模式下的页面形态与真后端一致 |

### 验证记录（本地实跑）

```
mvn -B -f backend/pom.xml verify          → BUILD SUCCESS · Tests run: 65, Failures: 0
                                            （0.1.0 的 38 个 + 本周 27 个）
前端 typecheck / 3 个静态闸门 / 11 个浏览器闸门 / build:mock → 全部通过
登录页实测（Playwright）                   → 验证码图渲染 ✅ 输入框在 ✅
                                            填完能登录并跳首页 ✅ 无控制台错误
```

### ★ 这一版踩到的坑（都是"功能看起来是对的"那类）

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | ★★ **登录失败 3 次锁不上账号** | `login()` 是事务方法，而它在**记录失败计数之后抛异常** ⇒ 异常把这次写入一起**回滚**了，计数永远是 0 | 抽 `AuthSecurityRecorder`，用 `REQUIRES_NEW` 独立事务。★ **同一个 bug 还藏在重放检测里**："撤销全部会话"也被回滚掉 ⇒ 攻击者手里那个令牌依然有效。两处都修了 |
| 2 | `ACCOUNT_LOCKED` 返回 **400** 而不是 403 | 我加了码，却忘了更新 `GlobalExceptionHandler` 的状态映射 ⇒ 它悄悄落进 `default`。"加了码、忘了改映射"不会报任何异常 | 改成"**码值本身是 4xx 就直接对齐 HTTP**"，这类码以后不必再改表；只有语义与 HTTP 不一致的（如 `ACCOUNT_LOCKED`）才显式列出 |
| 3 | ★ **测试恢复步骤失败，导致后续测试连锁失败** | 一个测试在 `finally` 里用 `123456` 把口令改回去，而**新加的强度校验拒绝了它**（6 位纯数字）⇒ 口令卡在被改成的那个值上 | 复位改为 `@BeforeEach` 里**直接写库**（绕过业务接口）。★ 顺带暴露一件真事：**seed 里的演示口令本身不满足等保强度** —— 这符合预期（它们是开发数据，V4 已注明生产必须删除），但它意味着"改回去"这件事只能由测试自己安排 |
| 4 | `OptionalAssert.contains` 全部断言失败 | AssertJ 的 `assertThat(Optional).contains(x)` 是"**值等于** x"，不是"含子串" | 改用 `.orElseThrow()` 取出消息再查子串 —— 后者在"应该违规却通过了"时也会明确失败 |
| 5 | 一条测试数据算错了字符类别 | `"abcdefg!"` 是"小写 + 特殊"= **2 类**，我按 3 类写的 | 改为 `"abcdefg1!"`。类别计数现在有单独的测试守着 |

### ⚠️ 待确认 / 欠账

| # | 事项 | 说明 |
|---|---|---|
| 1 | ★ **演示口令 `123456` 不满足强度要求** | 它是与前端 mock 对齐的开发数据（`mustChangePassword=false`）。这本身没问题，但**等保测评时演示账号也在范围内** —— 要么改 seed 口令，要么确保测评环境里没有它们 |
| 2 | **锁定只按账号，没有按 IP** | 等保要求"限制登录失败次数"，按账号已满足。但攻击者可以换账号名遍历（不存在的账号不会被锁）—— 按 IP 限流是另一层，未做 |
| 3 | **没有口令定期更换 / 历史口令检查** | 等保三级可能要求"定期更换"与"不得与最近 N 次重复"。后者要存口令历史（本身是新攻击面），且 NIST SP 800-63B 对强制轮换持保留态度 ⇒ 等测评明确要求再按口径补 |
| 4 | **验证码是唯一一处"内存态"** | 重启丢失。这是**可接受**的（2 分钟就过期，代价几乎为零），与锁定必须落库的理由正好相反 —— 判断标准是"它是否承载需要跨越重启的约束" |
| 5 | `CaptchaService.peekCodeForTesting` 是公开方法 | 为了让自动化测试能验"验证码开启时的行为"。名字里带 `ForTesting` 就是让任何人一眼看出它不该被生产代码调用 |

### 未做（刻意的）

- **不引 kaptcha / easy-captcha**：需要的东西只有三件（随机字符、Java2D、带过期的键值表），JDK 与 Caffeine 都已具备
- **验证码不做点选/滑块**：图形字符码已满足等保那一条；更复杂的形态是体验优化，不是合规门槛

---

## 0.1.0 · 第 2 周 · M1 认证链与双令牌

### 这一版能做什么

**用账号登录，并带着令牌访问受保护接口**（三个动作都是实测过的）：

```
GET  /api/v1/users/me        不带令牌 → HTTP 401 {"code":401,"hint":"登录已过期或令牌无效，请重新登录"}
POST /api/v1/auth/login      {"username":"admin","password":"admin"} → accessToken(30m) + refreshToken(7d)
GET  /api/v1/users/me        带令牌 → HTTP 200 + 用户名 / 角色名 / 16 个权限点
POST /api/v1/auth/refresh    换发新的一对；★ 旧令牌再用 → 判定重放，撤销该用户全部会话
```

### 交付物

| # | 交付 | 关键点 |
|---|---|---|
| 1 | **迁移 V3/V4**：`iam_user` / `iam_role` / `iam_refresh_token` + 5 角色 4 演示账号 | 用户名唯一用**部分索引**（删掉的账号不该永久占用名字）；刷新令牌只存 **sha256 哈希** |
| 2 | **domain**：`User` / `Role` / `RefreshToken` / `Permissions` + 仓储接口 | 权限点 16 个与前端 `permissions.ts` 逐项对齐；角色→权限点照 mock fixtures 对齐 |
| 3 | **JWT 签发**（`JwtTokenService` + `NimbusJwtEncoder`，HS256） | ★ 权限点放进令牌 ⇒ 省掉"每请求一次权限查询"，代价是改动最长 30 分钟生效（写在类注释里） |
| 4 | **责任链补全**：`oauth2ResourceServer().jwt()` + `@EnableMethodSecurity` | 验签解析全走官方；401/403 用官方扩展点，输出包络 |
| 5 | **`AuthService`**：登录 / 刷新轮换 / 重放检测 / 登出 | ★ 这是 `08 §4.2.3` 说的"两处必须自己写"的第一处 |
| 6 | **Web 层**：`/auth/login` · `/auth/refresh` · `/auth/logout` · `/users/me` | 当前用户从 `@AuthenticationPrincipal Jwt` 取，不用 ThreadLocal |
| 7 | **统一异常映射**（`GlobalExceptionHandler`） | ★ 401 返 **HTTP 401**（前端靠它静默刷新，见下） |
| 8 | ★ **第三条闸门**：写端点必须声明权限 + 金丝雀 | 与模块边界、内核纯净性同构 |
| 9 | **16 个新测试** | 令牌签发/验签 6 · 端到端认证链 8 · 权限声明 2 |

### 验证记录（本地实跑）

```
mvn -B verify  → BUILD SUCCESS · Tests run: 38, Failures: 0, Errors: 0
                  （0.0.1 的 22 个 + 本周 16 个）
curl 演示      → 401 带 hint ✅ · 登录拿双令牌 ✅ · 带令牌取到 16 个权限点 ✅
```

### ★ 这一版踩到的坑

| # | 现象 | 根因 | 处理 |
|---|---|---|---|
| 1 | `Unable to convert claim 'iss' of type String to URL` | ★ **JWT 的 `iss` 必须是 URL 形式** —— Spring 的 `Jwt.getIssuer()` 返回 `URL`，给裸串（`ai-knowledge-platform`）会在读取时抛转换异常 | issuer 改成 `http://ai-knowledge-platform.local`，并在配置里注明"必须是 URL" |
| 2 | `expiresAt must be after issuedAt` | 想用"负 TTL"造过期令牌，但 `JwtClaimsSet` 在**构建期**就拒绝 | 测试改为手工构造"签发于过去"的声明 —— 那才是真正想验的（验签方是否检查过期） |
| 3 | ArchUnit 报 `failed to check any classes` | 第 2 周的三个写端点**全在白名单里**（`AuthController`）⇒ 规则空跑，而 ArchUnit 默认把空跑当失败 | 主规则 `allowEmptyShould(true)` + 由**金丝雀**负责证明规则有效（第 3 周有 CRUD 后才有真实检查对象） |
| 4 | `haveSimpleNameNotEqualTo` 不存在 | ArchUnit 的 `ClassesThat` 没有"不等于" | 白名单并入一个 `DescribedPredicate`，只保留一个条件表达式 |
| 5 | ★ 重放检测的断言失败，但响应体里**明明有**那个词 | 我把服务端文案记成了"已使用"，**实际是"已被使用"**（中间隔着"被"字） | 打印**码点**定论（`U+5DF2 U+4F7F U+7528` 两边一致）⇒ 是断言写错，不是编码问题。**若跳过诊断直接去查编码，方向就全错了** |

### ⚠️ 本版新增的待确认 / 欠账

| # | 事项 | 说明 |
|---|---|---|
| 1 | ★ **契约该补 `BAD_REQUEST(400)`** | 请求体校验失败总得有个码，而契约的 `ErrorCode` 只有 8 个。400 是 401/403/404 那三个"与 HTTP 对齐"的码的同族补全。前端不针对它分支（走通用错误渲染），所以现在加不破坏任何东西 |
| 2 | **登录失败不区分"账号不存在"与"密码错误"** | 我按安全默认做的（区分它们等于提供用户名探测器），文案统一为"账号或密码不正确"。★ 前端 mock 是**区分**的（`账号「x」不存在` / `密码不正确`）—— 若产品上要那种提示，需要显式决定并改这一处 |
| 3 | **`CREATOR` 角色的权限点是我按 `07 §4.1` 矩阵推的** | 前端 mock 里**没有**这个角色（只有 r1~r4），所以它没有可对齐的真源。seed 里照实建出了这个角色，没有为了"四个角色刚好整齐"而假装它不存在 |
| 4 | **登出撤销的是全部会话** | 契约的 `POST /auth/logout` 不带令牌参数，服务端没法知道"是哪一台"。单设备登出需要契约加参数 |
| 5 | `AuthorizationDeclarationTest` 的白名单按**类名** `AuthController` | 改名会让白名单失效 ⇒ 构建失败（方向安全，会有人被通知到）。第 3 周有了真正的受保护端点后，这条规则才第一次有实际检查对象 |

### 未做（刻意的）

- **`GET` 端点不在权限声明闸门内**：查询类端点归**数据范围**（第 3 周）管
- **不给 `iam_role` 预埋 `data_scope`**：第 3 周才用，而该表只有几行 ⇒ 届时 ALTER 无成本（判断标准是"行数 × 是否进查询谓词"，见 V3 迁移的注释）
- **不做公开令牌 filter**：`08 §4.2.3` 的第二处自研，M4 才需要
- **不做契约漂移检测**：按计划是 0.1.2（第 4 周），本周的端点路径是手工核对的

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
| 12 | **三份技术结论的探针**与判定口径 | `ModelCapabilityProbeTest`（模型缺席、**或端口上是桩服务**时整体跳过，不让构建变红）+ `docs/技术结论/M0-技术结论.md` |
| 13 | ★ **OpenAI 兼容的测试桩服务** | `backend/tools/openai_stub.py` + `tools/dev-model.cmd`。监听端口与 Ollama 相同 ⇒ `application.yml` 一行不用改。开发期用它替代真模型，让 M1/M2/M4b 的链路不必等模型 |
| 14 | ★ **Spring AI ↔ 桩的契约测试**（6 条） | `OpenAiStubContractTest`。其中"实际请求路径"与"是否下发 `reasoning_effort`"两条给出了**真证据**，而不是推断 |

### 验证记录（本地实跑，不采信旧绿）

```
mvn -B verify         → BUILD SUCCESS，Tests run: 22, Failures: 0, Errors: 0
                        Spotless: 全部 Java 文件 clean
                        （桩服务在跑 ⇒ 契约测试 6 条真跑；探针被反向保护拦住，正确跳过）
前端闸门（起 dev server）→ 14 个确定性闸门全绿，两遍结果完全一致
                        ★ 但实测另发现 3 个闸门当前 flaky（见下节「检测发现」），
                          故未接入 CI —— 接入一个会随机变红的闸门，
                          等于让 CI 的红失去信号价值
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

### ★ 第 1 周检测发现（不是本版引入的问题，但会挡住"CI 全绿"）

> 这些是**重跑**才暴露出来的 —— 按本项目的铁律"**旧的绿一律不能继承**"。
> 它们都在前端侧、不属第 1 周的产出，但会影响 0.0.1 验收第 ③ 条（CI 含前端脚本），
> 所以必须记账，而不是把它当成"已经跑过了"。

| # | 发现 | 证据（本机实测） | 处理 |
|---|---|---|---|
| 1 | ★ **3 个闸门当前 flaky**：`verify_features` / `verify_permissions` / `smoke_test` | 连"断言总数"都在变：`verify_permissions` 49/49 → 49/50 → 49/51；`verify_features` 35/39 → 38/39 → 39/39；`smoke_test` 24/33 → 26/33 → 28/33，控制台错误 0~3 条 | **不接入 CI**（接入 = 随机变红）。待办：让它们等页面就绪，或改用确定性选择器 |
| 2 | ★ **无 dev server 时是"假绿"** | `verify_permissions` 在没有 server 时报"49/49 通过" —— 它**根本没跑浏览器那一批断言**（有 server 时是 51 条） | 已从 CI 的"静态闸门"组移出；CI 现在**先起 server 再跑**该组 |
| 3 | `check_publish` 把页面错误当告警 | 两遍都输出 `PAGEERROR: Cannot read properties of undefined (reading 'validate')`，但 exit=0 | ★ 它**报了一个真错却不拦**。`check_publish` 的判定要复核 —— 这可能是发布流程的真实缺陷 |
| 4 | `19 个 .py` 里 **16 个依赖 playwright** | 逐文件扫描：只有 `verify_refs` / `check_template_tags` / `check_design` 是纯静态 | 已据此重写 CI 的前端 job（分两组），并在注释里写明每组的依赖 |

### 三份技术结论（`ROADMAP §6.1` P3）

> ★ **分条记状态，不笼统说"待实测"**。真模型（Ollama）在本机有两个现实障碍：
> **镜像拉不到**（`registry-1.docker.io` 被 DNS 污染，解析到 `31.13.83.34`；
> 几个镜像源虽可达但拉取未成功）且 **CPU 跑推理不现实**。所以结论表**不填猜测值**。
>
> 判定口径、判据、以及"桩服务能回答什么/不能回答什么"的边界，都在
> `docs/技术结论/M0-技术结论.md`。

| # | 结论 | 状态 |
|---|---|---|
| ① | 各模型思考模式三态取值（`NONE` / `TOGGLEABLE` / `ALWAYS_ON`） | ⏳ 待真模型 |
| ② | Spring AI 能否下发思考开关（★ 两侧分开测） | **框架侧 ✅ 已答：会下发**（桩侧记录到请求体里出现了 `reasoning_effort`；不给时则不出现）· **上游侧 ⏳ 待真模型**（Ollama 认不认这个字段） |
| ③ | 能否稳定产出结构化输出（N=20 的失败率） | ⏳ 待真模型（**解析链路已验**：桩按 schema 生成的 JSON 能被 `.entity()` 正确解析，含嵌套数组字段） |

★ 这次实测顺带**确认**了两件以前只是推断的事：

| 事 | 以前 | 现在 |
|---|---|---|
| `base-url` 写 `http://host/v1` 对不对 | 照 Ollama 文档推的 | ★ **实测正确** —— 桩记录到的真实路径是 `/v1/chat/completions`，不是 `/v1/v1/chat/completions` |
| 结论一的三态判定会不会被"参数没传出去"污染 | 未知 | ★ **不会** —— 框架侧已证会下发，所以 `ALWAYS_ON` 的判定是可信的 |

### 未做（刻意的，不是遗漏）

- **不引 `spring-ai-starter-vector-store-pgvector`** 与 **Tika**：M2 有向量表之后再引（`09 §9.4`）
- **不写 `GlobalExceptionHandler`**：它需要"HTTP 状态 ↔ 业务码"的映射规则，而那是端点契约的细节 ——
  第 2 周有第一个端点时用真契约测试来定，现在猜一个等于埋一个静默失效
- **不做应用的容器化**：开发期形态是 **2 个进程**（PG + 应用），见 `ROADMAP §4.6`
- **Checkstyle / SpotBugs**：按需引入（`09 §4.3`），记在 `pom.xml` 的注释里
