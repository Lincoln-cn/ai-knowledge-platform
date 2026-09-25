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
