import { request } from '@/lib/http'
import type { WorkflowGraph } from '@/api/workflow'
import type { ModelInfo } from '@/api/models'

/**
 * 编排画布的接口适配层 —— 只服务于从旧工程整体搬过来的画布代码。
 *
 * 画布的调用点用的是一套旧函数名，并且期待**双层包装**的响应体
 * （`res.data.data`）。本文件把这些旧形状映射到新契约。
 *
 * **为什么不直接改画布的调用点**：画布是一整棵 6,796 行的既有代码，此刻把它
 * 半现代化只会得到一个比两端都差的混合体。适配层让旧脸接新芯，
 * 并且**它是唯一的删除点** —— 将来把画布调用点正规化到 `@/api/*` 之后，
 * 整个 `src/api/legacy/` 目录删掉即可，不必再去 81 个文件里找残留。
 *
 * ⚠️ 新写代码**不要** import 这里。新页面一律直接调 `@/api/<域>`。
 */
const wrap = <T>(payload: T) => ({ data: { data: payload } })

/**
 * 模型列表（旧 `getAiList`）。
 *
 * 两处适配，都在这一层做完（**不要改节点组件** —— 那是旧代码，改了容易与别处不一致）：
 *  ① **改名**：旧组件读 `modelName` / `model_type`，契约叫 `name` / `kind`。
 *     不改名的话下拉里每行的 label 都是 `undefined`（「没有正确展示可用模型」的根因）。
 *  ② **只给对话模型**：编排节点的「AI 模型」选的是对话模型，
 *     向量模型放进去选了也调不动（`kind: 'EMBEDDING'`）。
 */
export const getAiList = () =>
  request<{ list: ModelInfo[] }>({ method: 'GET', url: '/models', query: { size: 200 } }).then((r) =>
    wrap(
      (r.list ?? [])
        .filter((m) => m.kind === 'CHAT')
        .map((m) => ({ ...m, modelName: m.name, model_type: m.kind }))
    )
  )

/**
 * 应用详情 / 流程草稿（旧 `detail`）。
 *
 * 旧实现返回 `{ workFlow: '<JSON string>', updateTime }`；现在直接返回
 * **契约格式的 `WorkflowGraph`**，由页面自己转成 LogicFlow 格式
 * （见 `pages/workflow/lib/graph-format.ts`）。字符串包一层 JSON 是旧系统的
 * 包袱，没有理由在新链路上继续背着。
 */
export const detail = (row: { id: string }) =>
  request<WorkflowGraph>({
    method: 'GET',
    url: '/assistants/:appId/workflow/draft',
    path: { appId: row.id }
  }).then((graph) => wrap(graph))

/** 保存草稿（旧 `addSubmit`，旧语义是"保存并发布"，这里只保存） */
export const addSubmit = (row: { applicationId: string; graph: WorkflowGraph }) =>
  request<WorkflowGraph>({
    method: 'PUT',
    url: '/assistants/:appId/workflow/draft',
    path: { appId: row.applicationId },
    body: { graph: row.graph }
  })

/**
 * 发布（旧 `workFlowPublish`）。发布说明由调用方给，空说明后端会拒。
 *
 * ★ 编排页的发布已改走契约函数 `api/workflow.ts` 的 `publishWorkflow`（同一端点，
 *   但要拿回 `WorkflowVersion` 里的版本号与节点数）。这一条**暂时没有调用方**，
 *   留着是因为 `docs/ROADMAP.md` 与 `09` 设计册仍按旧别名描述这一层；
 *   真要删，得连同那两处一起改（不在本次改动范围内）。
 */
export const workFlowPublish = (row: {
  applicationId: string
  graph: WorkflowGraph
  note: string
}) =>
  request({
    method: 'POST',
    url: '/assistants/:appId/workflow/publish',
    path: { appId: row.applicationId },
    body: { graph: row.graph, note: row.note }
  })

/** 发布历史（旧 `getWorkFlowVersion`）。 */
export const getWorkFlowVersion = (applicationId: string) =>
  request<unknown[]>({
    method: 'GET',
    url: '/assistants/:appId/workflow/versions',
    path: { appId: applicationId }
  }).then((list) => wrap(list ?? []))

/** 取某一版的图（预览/回滚前看它长什么样）。列表不带图，故单独取。 */
export const getVersionGraph = (applicationId: string, version: number) =>
  request<WorkflowGraph>({
    method: 'GET',
    url: '/assistants/:appId/workflow/versions/:version',
    path: { appId: applicationId, version }
  })

