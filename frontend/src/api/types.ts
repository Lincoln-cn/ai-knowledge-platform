/** 契约层公共类型。接口一旦冻结，这里的改动就是破坏性改动。 */

/** 后端统一响应包。code 为 0 表示成功。 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
  /**
   * 可行动提示（如「未从上游拉到模型：请检查服务地址…」）。
   * ★ 后端包络会带；**成功响应也可能有**（空目录 + hint），只在错误分支读它会漏掉。
   * 契约允许只加可选字段。
   */
  hint?: string
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
  /**
   * 认证：登录失败次数超限，账号被临时锁定（等保 · 三级：应对登录失败做次数限制与冻结）。
   * 本项目取 3 次 / 30 分钟。
   */
  ACCOUNT_LOCKED: 1001,
  /** 认证：新密码不满足强度要求（等保：长度 + 字符类别） */
  PASSWORD_TOO_WEAK: 1002,
  /**
   * 认证：验证码缺失、已过期或填写不正确。
   * ★ 三者共用一个码是有意的 —— 对使用者来说下一步动作完全一样（刷新验证码重填），
   * 而分开反而会让人以为"某个错误不用刷新"。
   */
  CAPTCHA_INVALID: 1003,
  /**
   * 改密时提供的老密码不正确。
   *
   * ★ 它与 `UNAUTHORIZED`（401）**不是一件事**：401 = 会话失效 ⇒ 前端会「静默刷新并重放一次」；
   * 而「表单里填错了老密码」是字段错误。两者共用一个码会让前端白跑一次刷新 + 一次重放，
   * 排障时也分不清哪个 401 是会话、哪个是字段（issue #15）。
   */
  OLD_PASSWORD_MISMATCH: 1004,
  /** 请求参数不合法。与 401/403/404 同族 —— 码值与 HTTP 状态对齐 */
  BAD_REQUEST: 400,
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  NOT_FOUND: 404,
  /**
   * 请求过于频繁（公开面限流，`#214`）。与 401/403/404 同族 —— 码值与 HTTP 429 对齐。
   *
   * ★ 可重试：`hint` 与响应头 `Retry-After` 都会说"还有多少秒"。
   * 它**不是** `UPSTREAM_UNAVAILABLE`：那个要人去查模型服务，这个只要等。
   */
  TOO_MANY_REQUESTS: 429,
  /**
   * 请求与资源当前状态冲突（`#198` 发布编排版本：取号后撞上唯一约束，重试一次仍冲突）。
   *
   * ★ 与 400 分开：400 的下一步动作是"改请求"，这个的下一步动作是"稍后重试" ——
   * 合并成 400 会让人去改一个根本没错的请求体。码值与 HTTP 409 对齐（与 401/403/404/429 同族）。
   */
  CONFLICT: 409,
  /** 模型未加载完成等可重试状态 */
  MODEL_NOT_READY: 2001,
  /**
   * 上游模型服务不可用（连不上 / 响应不合规）。
   * ★ 与 400 分开：这不是"请求写错了"，用户改不动请求 —— 下一步动作是查模型服务或稍后重试，
   * 混进 400 会让人去改一个根本没错的入参。
   */
  UPSTREAM_UNAVAILABLE: 2002,
  /** 知识库已有向量化内容，向量模型被锁定（KB-I4） */
  EMBEDDING_LOCKED: 3001,
  /** 分段超长（SEG-I1） */
  SEGMENT_TOO_LONG: 3002,
  /** 应用发布前校验不通过（AS-I2~I5） */
  PUBLISH_CHECK_FAILED: 6001,
  /**
   * ★★ 公开面四态（免登录的访问凭证 / 分享链接）—— 一个数字一族，**不可合并**。
   *
   * 这四态**不是同一个错误的四种措辞**：它们指向不同的**下一步动作**，
   * 合并成一句"链接无效"会让人按错的假设去等（已停用可以恢复、已撤销不能）——
   * 本项目头号病 `silent-failure` 的界面版。界面文案的唯一出口是 `utils/public-gate.ts`。
   *
   * ★ 它们原先**只活在 mock 与前端**（`13 §8.3` 的遗留两格之一），
   * 现在两侧同时登记：契约（本处）与后端枚举（`ErrorCode.java`），由 `check_error_codes.py` 每轮比对。
   * ★ 它们偏离 HTTP（`40001–59999` 段与 HTTP 状态**不重合**）⇒ 传输层 HTTP 仍是 404/400，
   * **判据取包络里的 `code`，不取 HTTP 状态**。
   * ★ `40405`（内容升密 ⇒ 原链接立即失效）是**预留**，按 `13 §5.3` 的 **(a)** 口径（内容不再返回、
   * 凭证仍有效）**不需要它** —— 所以**不登记**，免得读成"这态存在"。
   */
  /** 公开面：认不出这个 token（链接本身不对，重试无意义） */
  PUBLIC_TOKEN_UNKNOWN: 40401,
  /** 公开面：已撤销（★ 终态，不能重新启用） */
  PUBLIC_TOKEN_REVOKED: 40402,
  /** 公开面：已过期（有效期创建时定下、之后不能改） */
  PUBLIC_TOKEN_EXPIRED: 40403,
  /** 公开面：已停用（★ 可恢复：重新启用即可） */
  PUBLIC_TOKEN_DISABLED: 40404
} as const

/* ────────────────── 归属与数据权限（07 角色与数据权限模型） ────────────────── */

/**
 * 角色侧的数据范围（07 §3.2）。
 *
 * `OWN` 只有自己建的 · `SHARED` 自己建的 + 被共享给自己的 · `ALL` 全平台。
 * 三档是**预设档位**，不是唯一形状 —— 范围规则的逃生口见 §8.3（`dataScopeRule`）。
 */
export type DataScope = 'OWN' | 'SHARED' | 'ALL'

/** 资源侧的可见性（07 §3.1）。**`PUBLIC` 不等于免登录** —— 它是"登录用户里人人可见" */
export type ResourceVisibility = 'PRIVATE' | 'SHARED' | 'PUBLIC'

/**
 * 密级（企业四级）。
 *
 * ★ 这四级是**工程惯例，不是法定密级** —— 法定的只有《保守国家秘密法》的
 * 绝密 / 机密 / 秘密。企业内控需要这四档（公开/内部/秘密/机密），
 * 但**不要**在文档或界面上声称它是法定定密（13 册 §1.1）。
 */
export type ClassificationLevel = 'PUBLIC' | 'INTERNAL' | 'SECRET' | 'CONFIDENTIAL'

/** 共享主体种类。MVP 只填 `USER` / `ROLE`；加"共享给部门"只是多一个枚举值（07 §8.1） */
export type SubjectKind = 'USER' | 'ROLE' | 'ORG' | 'GROUP' | 'LINK'

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
