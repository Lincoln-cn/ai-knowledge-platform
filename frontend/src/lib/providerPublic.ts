/**
 * 服务商「公开形状」的唯一构造点（Issue #264）。
 *
 * ★ 为什么必须单独有个函数：
 *   契约 `frontend/src/api/providers.ts` 的 `ModelProviderInfo` 只有一个
 *   `hasApiKey: boolean`（注释原话「只回“有没有”，绝不回明文」），
 *   而 mock 早期用 `{ ...p }` 整条展开，把内部留着的明文 `apiKey` 一起塞进了响应体 ——
 *   形状上凭空多一个键，那句注释就成了假话。
 *
 * ★ 为什么是「显式挑字段」而不是「展开后删键」：
 *   白名单的默认方向是「不下发」。以后内部记录再加 `refreshToken` / `apiSecret`
 *   之类的字段，这里什么都不用改；而 `delete out.apiKey` 那种黑名单写法，
 *   下次谁改回 `{ ...p }` 就再漏一次。
 */
import type { ModelProviderInfo } from '@/api/providers'

/** mock 内部的完整记录：比公开契约多一个明文密钥，只供内部模拟上游调用 */
export type ProviderWithSecret = ModelProviderInfo & { apiKey?: string }

/**
 * 把内部记录裁成可下发的公开形状。
 *
 * @param p 内部记录；其中的明文 `apiKey` 只用来算 `hasApiKey`，绝不被带出
 * @param modelCount 引用该服务商的模型数（由调用方按当前模型表现算）
 * @returns 只含契约字段的响应体。`apiKey` 这个键**不存在**（不是空串、不是 null、不是 undefined）
 */
export const toPublicProvider = (p: ProviderWithSecret, modelCount: number): ModelProviderInfo => {
  const out: ModelProviderInfo = {
    id: p.id,
    name: p.name,
    kind: p.kind,
    apiBase: p.apiBase,
    authStyle: p.authStyle,
    egress: p.egress,
    enabled: p.enabled,
    hasApiKey: !!p.apiKey,
    modelCount
  }
  // 可选字段用条件赋值：没有备注时连键也不出现（`{ remark: undefined }` 序列化后虽会消失，
  // 但内存里 `'remark' in out` 仍为 true，形状断言会因此绕开它）
  if (p.remark !== undefined) out.remark = p.remark
  return out
}
