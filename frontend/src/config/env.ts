/** 运行期配置。改后端地址只改 .env，不在代码里写死。 */
export const APP_TITLE = import.meta.env.VITE_APP_TITLE || '知识助理平台'

/** API 前缀，与后端约定为 /api/v1 */
export const API_PREFIX = import.meta.env.VITE_API_BASE || '/api/v1'

/**
 * 是否走 mock。
 * 接口契约已固定（src/api/**），mock 只是传输层的一种实现：
 * 置 false 即切到真实后端，调用方代码一行不改。
 */
export const USE_MOCK = (import.meta.env.VITE_USE_MOCK ?? 'true') !== 'false'
