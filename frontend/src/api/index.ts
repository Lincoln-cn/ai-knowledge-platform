/**
 * 契约层出口。
 *
 * 页面只从这里 import 接口，不直接引 @/lib/http ——
 * 这样「哪些接口存在」只有一个可枚举的答案。
 */
export * as identityApi from './identity'
export * as modelsApi from './models'
export * as providersApi from './providers'
export * as knowledgeApi from './knowledge'
export * as ingestionApi from './ingestion'
export * as retrievalApi from './retrieval'
export * as assistantsApi from './assistants'
export * as conversationsApi from './conversations'
export * as feedbackApi from './feedback'
export * as analyticsApi from './analytics'
export * as streamApi from './stream'
export * as promptsApi from './prompts'
export * as workflowApi from './workflow'
export * as dictionariesApi from './dictionaries'
export * as orgsApi from './orgs'

export type { ApiResult, PageQuery, PageResult } from './types'
export { ApiError, ErrorCode } from './types'
