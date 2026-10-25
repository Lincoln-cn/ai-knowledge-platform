# 贡献指南

本仓库是**模块化单体后端 + 契约冻结前端**的企业知识助理平台（当前 `0.4.5`）。
这份文档面向第一次改代码的人：先说三处真源，再说怎么把环境跑起来，最后说"加一个模块"要动哪些文件。

> 三处真源，不要另开一份：
> **状态与欠账** = GitHub Issues · **计划与验收判据** = [`docs/ROADMAP.md`](docs/ROADMAP.md) · **设计理由** = [`docs/系统设计方案/`](docs/系统设计方案/)。
> 任何"两边都写一点"的安排都等于没有真源。

---

## 1. 环境

| 依赖 | 说明 |
|---|---|
| **JDK 25（Temurin）** | `backend/pom.xml` 写的是 `java.version=25`。本机默认 `JAVA_HOME` 往往不是 25，**所有 Maven 命令都走 `backend/mvn-jdk25.cmd`**（脚本把 JDK 钉在项目内，不改系统变量；可用 `AAI_PLATFORM_JDK25` 覆盖路径） |
| **Maven 3.9+** | 由上面的 wrapper 调用 |
| **Docker Desktop** | 集成测试用 Testcontainers，镜像 `ai-knowledge-platform-pg:dev`（PG + pgvector + zhparser）。Docker 没起时测试会报 `Could not find a valid Docker environment`，**不要把它当代码失败** |
| **Node.js + npm** | 前端依赖锁在 `frontend/package-lock.json` |
| 模型服务 | 开发期不必装 Ollama：`backend/tools/dev-model.cmd` 在 `11434` 起一个 OpenAI 兼容桩（见 `backend/README.md`） |

## 2. 起服务

```powershell
# 1) 数据库（首次会 build 自制镜像，几分钟；宿主端口 15432）
docker compose up -d postgres

# 2) 后端 → http://localhost:8080
cd backend
.\mvn-jdk25.cmd spring-boot:run

# 3) 前端（默认 mock，不需要后端）→ http://localhost:5280
cd frontend
npm install
npm run dev
```

要让页面连真后端：在 `frontend/.env.local` 写 `VITE_USE_MOCK=false` 再 `npm run dev`。
**页面代码一行不改** —— `VITE_USE_MOCK` 是唯一开关，mock 只住在传输层。

## 3. 测试与闸门

```powershell
cd backend
# 单个测试类（本项目纪律：绝不用 -Dtest=A,B 逗号列表）
.\mvn-jdk25.cmd -Dtest=AssistantShareHttpTest test

# 架构 / 口径闸门（★ 通配符不跨包，两条都要跑；模式必须整体加引号）
.\mvn-jdk25.cmd "-Dtest=cn.lincoln.aiplatform.*Test" -DfailIfNoTests=false test
.\mvn-jdk25.cmd "-Dtest=cn.lincoln.aiplatform.caliber.*Test" -DfailIfNoTests=false test

# 格式化（Spotless 在 compile 阶段会 check；折行/超 100 列都会红）
.\mvn-jdk25.cmd com.diffplug.spotless:spotless-maven-plugin:3.10.3:apply
```

**本地不要跑整包 `mvn verify`**：那是 CI 的活（一条命令几分钟到几十分钟），
本地按上面的单类/单模式跑，红绿以 `$LASTEXITCODE` 为准（PowerShell 会把 Maven 的 stderr 当失败，但工具 exit code 不一定可信）。

前端：

```bash
npm run typecheck      # 0 错
npm run build:mock     # 生产构建但走 mock
python check_template_tags.py
python check_design.py
```

一次性跑全部前端闸门：`tools\run-frontend-gates.cmd`（需要 Vite 在 5280 与新鲜的 `dist/`，脚本头部写了前提）。

## 4. 加一个模块（后端）

以新增 `foo` 为例。**模块边界是框架强制的**（Spring Modulith + ArchUnit），不是靠自觉。

1. **建包并声明模块**
   `backend/src/main/java/cn/lincoln/aiplatform/foo/package-info.java`：

   ```java
   @ApplicationModule(allowedDependencies = {"platform", "identity"})
   package cn.lincoln.aiplatform.foo;
   ```

   `allowedDependencies` 就是"这个模块允许依赖谁"的**白名单**；漏写会让模块从视图里消失，而 `verify()` 反而更容易通过。

2. **按层放代码**：`domain`（实体/值对象/仓储端口）· `application`（服务/用例）· `infrastructure`（JPA/JDBC 适配器）· `web`（控制器）。不要建"跨层大杂烩"包。

3. **更新 `ModuleBoundaryTest`**：`backend/src/test/java/cn/lincoln/aiplatform/ModuleBoundaryTest.java`
   里 `十二个模块必须全部被识别()` 写死了 **12** 和模块名清单。新增模块必须同时把计数与清单改掉 ——
   否则 `verify()` 仍绿，但那个模块根本没被识别，这是本项目最怕的"假绿"。

4. **跨模块调用只走公开端口**
   把端口（接口）放在 `platform/<域>`（如 `platform/retrieval`、`platform/model`），实现放在提供方模块的 `infrastructure`。
   业务模块**不得** import 另一个模块的 `domain`/`infrastructure` 类。`ModuleBoundaryTest` 的 L3→L4 规则只允许一个方向。

5. **数据库迁移**
   Flyway 只有一个 location：`classpath:db/migration`，**版本号全库连续**（当前最新 `V53`，新迁移从 `V54` 起），
   文件放在 `db/migration/<模块>/` 下。**已经跑过的迁移不许改**，只能追加新版本。

6. **权限点**
   权限常量在 `identity/domain/Permissions.java`，角色映射同样在代码里；新增端点先想清楚"哪个角色能调"，
   控制器用 `@PreAuthorize("hasAuthority('...')")` 钉住。数据范围过滤复用 `AccessScopePort` / `ScopeFilter`，
   不要自己写一套"可见性"。

7. **契约与页面消费（三格都要有）**
   - 后端端点：真实映射
   - 契约登记：`frontend/src/api/<域>.ts`（页面只从 `@/api` import，**绝不直接引 `@/lib/http`**）
   - 页面消费：至少一个真实入口

   三格里空的那格要写清"谁在哪周做"。`ContractDriftTest` 的双向比对只证明"契约与后端一致"，
   **不证明有页面在调**；消费点用 `python tools/check_contract_consumers.py` 查（目标：零消费 0 条）。
   已实现但仍在 `ContractDriftTest.KNOWN_MISSING` 里登记的，落地时要把那条摘掉。

8. **测试与台账**
   真 HTTP 测试照 `AssistantShareHttpTest` / `ConversationShareHttpTest` 的形状（`@SpringBootTest(RANDOM_PORT)` +
   Testcontainers + 登录取 token），**正向 + 反向**都要有（403 / 404 / 400 的文案是可核对判据）。
   新功能在 Issue 里写"判据"，在 `docs/ROADMAP.md` 记读数，在 `CHANGELOG.md` 记"这一版能做什么"。

## 5. 改一个功能时的三条硬约束

- **契约先冻结**：`frontend/src/api/**` 是前后端共同的真源。改它 = 破坏性变更，要同步后端、mock 与页面。
- **mock 只住在传输层**：不要在组件里写"如果是 mock 就……"。
- **权限有两面**：前端按 `session.can(...)` 控制按钮显隐，后端必须独立拒绝（A7 的判据是"直接调接口被拒"）。
  只隐藏按钮不是权限。

## 6. 命名与编码

- 文件一律 **UTF-8 无 BOM**（`.editorconfig` 已钉）。Windows 上写文件别用会加 BOM 的方式；
  BOM 会让 Spotless 报 `非法字符: '\ufeff'`。
- **标识符（类/方法/字段/变量）一律 ASCII**；中文只出现在 javadoc、注释与面向用户的字符串里。
- javadoc 是维护入口（"人工维护走 javadoc，而不是方法名"）：**注释要写"为什么"**，不要复述代码。
- Spotless 会按 100 列折行；注释超宽同样会让快闸红。
- 测试方法名与命名规范由 `TestNamingConventionTest` 钉住，新测试跟着邻居写。

## 7. Issue、提交与 CI

- **发现的问题一律开 Issue**，不分大小。形状：现象 → 实测证据（不是推断）→ 根因 → 危害 → 处置 → 判据 → 出处。
  判据必须可核对；修完按判据逐条核过再关。
- 提交消息：`type(scope): 中文摘要`（`feat` / `fix` / `test` / `docs` / `chore` / `style`）。
  多行消息**落文件**再 `git commit -F <file>`；消息文件还在 = 提交没成功。
- **不要 `git add -A`**：工作区有长期未跟踪的本地文件。逐路径 `git add -- <path>`，提交前用 `git add -A --dry-run` 核对命中数。
- 推送 `main` 后 CI 有 5 条：快闸（编译 + 静态闸门）· 前端构建 · 浏览器闸门 · 真栈 · 后端全量回归 + 架构。
  全绿才算完；红在真栈时先看"后端起没起来"，不要先怀疑页面。

## 8. 提交前清单

- [ ] 新增/修改的端点有正向 + 反向测试，且**能红**（删掉实现/断言会失败）
- [ ] `spotless:apply` 已跑；`-DskipTests test-compile` 通过
- [ ] 单类测试 + 两条架构闸门通过
- [ ] 前端 `typecheck` 0 错、`build:mock` 通过（改了前端）
- [ ] 契约、mock、页面三格对齐；零消费工具仍为 0
- [ ] Issue 判据逐条核过；`docs/ROADMAP.md` 与 `CHANGELOG.md` 已记读数
- [ ] 提交只含本批文件（无 `target/`、无日志、无 `.dsh-*`）
