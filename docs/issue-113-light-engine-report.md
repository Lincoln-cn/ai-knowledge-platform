# issue #113 轻引擎（0.6.1）交付报告

分支 `feat/113-light-engine`，基线 `142574c`，4 个提交：`32e98a5`（判据2/6）· `9e79b9c`（判据1/3）· `8f1a613`（判据4/5）· `d14bbce`（见证人）。工作区已干净，未 push。全程未碰 `frontend/src/api/workflow.ts` / `docs/ROADMAP.md` / `.github/workflows/` / `node-types.json`。

---

## 一、改了哪些文件（各一句理由）

### 修改（7）
| 文件 | 判据 | 理由 |
|---|---|---|
| `assistant/application/WorkflowValidationService.java` | 2/6 | 发布校验收紧：PLANNED_TYPE 升 ERROR + 人工介入节点识别 + 环检测 |
| `assistant/application/WorkflowAdmissionService.java` | 2 | 删去重复的 `cycles()/walk()`，避免 CYCLE 报两次（`aCycleIsRejected` 的 singleElement 断言依赖） |
| `test .../WorkflowValidationServiceTest.java` | 2 | PLANNED_TYPE 语义反转断言（WARNING→ERROR） |
| `test .../WorkflowAdmissionServiceTest.java` | 2 | 同上，`publishable` 由 isTrue→isFalse |
| `test caliber/CaliberShape.java` | 4 | 登记 V69 迁移（`MigrationInventoryTest` 要求迁移清单与磁盘一致） |
| `test caliber/WorkflowEngineAdmissionTest.java` | 5 | 见证人从「禁 ExecutionPlan/NodeExecutor 符号」改为「六判据各有行为测试」 |
| `test caliber/WorkflowEngineAbsenceTest.java` | 5 | 见证人断言消息诚实化（执行引擎落 assistant/application，不在 workflow） |

### 新增（17）
| 文件 | 判据 | 理由 |
|---|---|---|
| `application/WorkflowCompiler.java` | 1 | Graph→ExecutionPlan（Kahn 拓扑 + 条件位置 branchHandle + 中断点清单） |
| `application/KeyUpdate.java` | 3 | REPLACE/APPEND 语义枚举 |
| `application/FlowState.java` | 3 | 状态写入（REPLACE 覆盖 / APPEND 追加） |
| `application/NodeExecutor.java` | 5 | 节点执行器接口（注册表边界） |
| `application/WorkflowNodeContext.java` | 4/5 | 节点执行上下文 |
| `application/LinearWorkflowExecutor.java` | 4/5 | 最小执行器（线性 4 节点 + L1 事务检查点） |
| `domain/WorkflowNodeExecutionRecord.java` | 4 | 节点执行记录实体 |
| `domain/WorkflowNodeExecutionRepository.java` | 4 | 数据端口 |
| `infrastructure/JpaWorkflowNodeExecutionRepository.java` | 4 | JPA 实现 |
| `db/migration/assistant/V69__...sql` | 4 | 建表 `as_workflow_node_execution` |
| `test .../WorkflowCompilerTest.java` | 1 | 判据1 测试 |
| `test .../FlowStateTest.java` | 3 | 判据3 测试 |
| `test .../LinearWorkflowExecutorTest.java` | 4/5 | 判据4/5 测试（纯逻辑） |
| `test .../LinearWorkflowExecutorL1Test.java` | 4 | 判据4 L1 真库测试（Testcontainers） |
| `test .../WorkflowInterruptPublishTest.java` | 2/6 | 判据2/6 先红后绿测试 |

---

## 二、六条判据逐条读数

| 判据 | 载体测试 | 读数 | 结论 |
|---|---|---|---|
| 1 只编译不跑 | `WorkflowCompilerTest` | Tests run:5, Failures:0 | 拓扑序 / 条件位置 / 中断点清单 / 成环返回错误 / 不连通返回错误 全过 |
| 2 中断点即拒绝发布 | `WorkflowInterruptPublishTest` + `WorkflowValidationServiceTest` + `WorkflowAdmissionServiceTest` | 3/3 + 7/7 + 4/4 | QUESTION/FORM（人工介入）+ PLANNED_TYPE（implemented=false）全 ERROR 拒发布；环检测在发布层 |
| 3 KeyUpdate 状态语义 | `FlowStateTest` | Tests run:2, Failures:0 | REPLACE 后写覆盖 / APPEND 追加 |
| 4 最小执行器 + L1 | `LinearWorkflowExecutorTest` + `LinearWorkflowExecutorL1Test` | 4/4 + 2/2 | 只跑线性 4 节点；L1：节点抛异常 ⇒ 该执行 SUCCEEDED 计数 0（同事务回滚） |
| 5 NodeExecutor 注册表边界 | `LinearWorkflowExecutorTest` + `WorkflowEngineAdmissionTest` | 4/4 + 3/3 | 注册表按 type 分发；见证人改造为六判据行为断言 |
| 6 先红后绿 | `WorkflowInterruptPublishTest` | 先红 Failures:2 → 后绿 3/3 | 证明「现状放行 → 收紧拒绝」（详见第四节） |

**三档闸门（改动后全绿）**：`caliber.*Test` **33/33** · `assistant.application.*Test` **40/40** · 顶层 `*Test` **36/36**（含 `ModuleBoundaryTest` 4、`PlatformPurityTest` 2、`ContractDriftTest` 15、`MigrationIntegrationTest` 4）。

---

## 三、反证读数

| 判据 | 反证 | 读数 | 证明了什么 |
|---|---|---|---|
| 2/6 | `aCleanGraphWithoutInterruptsStillPublishes` | 先红时绿、后绿仍绿 | 先红只因中断点识别缺失，不是其他校验误伤 |
| 3 | REPLACE vs APPEND 对比 | 覆盖（"第二次"）vs 追加（"第一次第二次"） | 两语义确实不同，不是同一个实现 |
| 4 | `aSuccessfulRunRecordsEveryNode` | 全成功 ⇒ SUCCEEDED==2 | 记录确实写入，L1 的 0 才有意义（非"记录根本不写"） |
| 5 | `thereIsNoExecutionEngineSymbolYet` | 落地后红（扫到 4 文件）→ 改造后绿 | 闸门从「禁符号」变「六判据测试」，且六判据测试齐备 |

---

## 四、判据6 先红后绿两次读数（唯一证据）

- **先红（改动前）**：`WorkflowInterruptPublishTest` Tests run:3, **Failures:2** —— `aGraphWithAQuestionNodeMustNotPublish` 与 `aGraphWithAFormNodeMustNotPublish` 均 `Expecting value to be false but was true`（含人工介入节点的图被放行 = 发布成功）。反证 `aCleanGraphWithoutInterruptsStillPublishes` 绿。
- **后绿（收紧后）**：Tests run:3, **Failures:0**, BUILD SUCCESS, MVN_EXIT=0。

> 判据6 字面「断言含中断点的图发布成功，跑一次记下它红」有歧义（"断言发布成功"与"记下它红"矛盾）。采用 TDD 解读：断言「必须被拒绝发布」（期望行为），现状红 = 证明放行、收紧后绿 = 已识别。两次读数如上，请核对。

---

## 五、没做的 / 不确定的 / 发现但没修

1. **没真跑 AI_CHAT/REPLY 的业务**（判据4 明确「接 conversation.ChatService 会越模块边界，先停下来报告」）：真跑需接 `conversation.ChatService`，而 `assistant` 的 `allowedDependencies` 不含 `conversation`。执行器用可注入假 `NodeExecutor` 验证线性结构 + L1 事务，未接真实对话。**需拍板**：是否改 `package-info` 允许 assistant→conversation（判据5 要求「先改闸门、后写实现」）。
2. **QUESTION_NODE 的 `node-types.json` 账目不准**（标 `implemented:true` 但无后端实现）：未改 `node-types.json`（遵守禁改），改用 `isHumanInTheLoop` 硬识别拦 QUESTION/FORM。**需拍板**：是否把 QUESTION_NODE 改成 `implemented:false`（改了 `WorkflowNodeTypeTest.exactlyEightAreImplemented` 断言 implemented==8 会变 7 红，需同步改）。
3. **L1 只验记录侧回滚**：业务操作是假 Stub，「业务与记录同事务回滚」只验到记录侧（SUCCEEDED 计数为 0）；真实业务副作用的回滚依赖 `@Transactional` 语义，未接真实业务验证。
4. **`CaliberShape` 的 `MAIN_ROOT` 常量**在见证人改造后变未用（未删，无害）。
5. **发现没修**：`WorkflowNodeTypeTest.catalogCoversAllEighteenTypes` 断言 `hasSize(18)`，但 `node-types.json` 实际 19 条（注释说 18）—— 18 vs 19 的账目不在本任务范围，留观察。
