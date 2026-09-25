/** 契约层公共类型。接口一旦冻结，这里的改动就是破坏性改动。 */

/** 后端统一响应包。code 为 0 表示成功。 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

/** 分页请求。原项目同时存在 page/size、current/size 两套，这里只留一套。 */
export interface PageQuery {
  page?: number
  size?: number
  /** 关键字模糊搜索 */
  keyword?: string
}

/** 分页响应 */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  size: number
}

/** 业务错误码分段，与后端约定：每模块 1000 段 */
export const ErrorCode = {
  OK: 0,
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  /** 模型未加载完成等可重试状态 */
  MODEL_NOT_READY: 2001,
  /** 知识库已有向量化内容，向量模型被锁定（KB-I4） */
  EMBEDDING_LOCKED: 3001,
  /** 分段超长（SEG-I1） */
  SEGMENT_TOO_LONG: 3002,
  /** 应用发布前校验不通过（AS-I2~I5） */
  PUBLISH_CHECK_FAILED: 6001
} as const

/* ────────────────── 归属与数据权限（07 角色与数据权限模型） ────────────────── */

/**
 * 角色侧的数据范围（07 §3.2）。
 *
 * `own` 只有自己建的 · `shared` 自己建的 + 被共享给自己的 · `all` 全平台。
 * 三档是**预设档位**，不是唯一形状 —— 范围规则的逃生口见 §8.3（`dataScopeRule`）。
 */
export type DataScope = 'own' | 'shared' | 'all'

/** 资源侧的可见性（07 §3.1）。**`public` 不等于免登录** —— 它是"登录用户里人人可见" */
export type ResourceVisibility = 'private' | 'shared' | 'public'

/** 共享主体种类。MVP 只填 `user` / `role`；加"共享给部门"只是多一个枚举值（07 §8.1） */
export type SubjectKind = 'user' | 'role' | 'org' | 'group' | 'link'

/** 一个共享主体。判定里按 `kind` + `id` 一起比对 —— 部门「3」不是角色「3」（07 §8.2） */
export interface Subject {
  kind: SubjectKind
  id: string
}

/** 一条共享授权。`actions` 与权限点同一套词汇：'knowledge:view' / 'assistant:edit' … */
export interface Grant {
  subject: Subject
  actions: string[]
  /** 时效；null 或不填表示长期有效（形状口子，MVP 不解析） */
  expiresAt?: string | null
  /** 可再分享（形状口子，MVP 不解析） */
  canDelegate?: boolean
}

/**
 * 用户产出型资源的归属 mixin（07 §8.1）。知识库 / 应用 / 会话三类都实现它，
 * 加第四类资源时判定逻辑零复制（§8.4）。
 *
 * ⚠️ 契约已冻结，所以三类资源的**响应字段都是可选的**（见各 `api/*.ts`），
 * 而不是让它们 `extends OwnedResource` —— 那会把必填性写进冻结契约（07 §六 阶段 1）。
 */
export interface OwnedResource {
  ownerId: string
  visibility: ResourceVisibility
  grants?: Grant[]
}

/** 后端返回的非 0 业务码，或网络/HTTP 层错误 */
export class ApiError extends Error {
  readonly code: number
  readonly hint?: string

  constructor(code: number, message: string, hint?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.hint = hint
  }
}
