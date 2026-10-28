/**
 * 「端点还没实现」的降级器 —— 让缺后端的功能变成一次可读的 WARN，而不是一次页面异常（`#160`）。
 *
 * <h3>为什么需要单独一层</h3>
 * <p>`ContractDriftTest` 的 `KNOWN_MISSING` 是**后端欠账**的口径（"哪个端点还没写"），
 * 它并不表达"这会让页面报错"。而前端拿到的是一个**正常的 404 包络**：
 * `{ code: 404, message: "接口不存在：api/v1/analytics/assistants" }`（后端
 * `NoResourceFoundException` 的处理器生成）。于是 `await` 一处未实现的端点 ⇒ 未处理 rejection ⇒
 * 页面级 `PAGEERROR`，而用户看到的只是"这块空着"。
 *
 * <p>★ 两种处置完全不同：**功能还没做** ⇒ 等排期；**已做的功能被一个未实现端点拖坏** ⇒ 要隔离依赖。
 * 这段话就是本次分开它们的地方。
 *
 * <h3>★ 只降级「接口不存在」这一种</h3>
 * <p>500 / 越权 / 网络不通**照旧抛**。否则"静默"就从页面搬到了数据层 —— 那比原来更难查
 * （页面至少还会亮一行红，数据层只会安静地回 0）。
 */

/** 只要能看见 `code` 与 `message` 两个字段的错误对象（`ApiError` 满足，别处不必引 `http.ts`） */
export interface EndpointMissingAware {
  code?: unknown
  message?: unknown
}

/**
 * 这个错误是不是"后端还没有这个端点"。
 *
 * ★ 判据是**两段**：`code` 为 404/501，**且** message 里带「接口不存在」。
 * 只用 code 会把"资源真的不存在"（例如查一个已删除的应用）也降级成空数据 —— 那是另一件事，
 * 而且它必须让用户看见。
 */
export function isEndpointMissing(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) return false
  const { code, message } = error as EndpointMissingAware
  if (code !== 404 && code !== 501) return false
  return typeof message === 'string' && message.includes('接口不存在')
}

const warned = new Set<string>()

/**
 * 给"后端还没实现"的请求一个空数据形状；其它错误原样往上报。
 *
 * @param promise 原始请求
 * @param fallback 降级时用的空数据（形状必须与该端点一致，页面才能照常渲染）
 * @param label 端点标识（进 WARN，一次流程里同一个端点只念一次，否则一个页面能刷出五条同样的噪音）
 */
export function degradeOnMissingEndpoint<T>(
  promise: Promise<T>,
  fallback: T,
  label: string
): Promise<T> {
  return promise.catch((error: unknown) => {
    if (!isEndpointMissing(error)) throw error
    if (!warned.has(label)) {
      warned.add(label)
      console.warn(
        `[降级] 端点还没实现：${label} —— 页面按空数据处理（#160）。后端补上之后这里不再触发。`
      )
    }
    return fallback
  })
}

/** 单测用：清掉"已警告过"的记录，免得用例之间互相影响 */
export function resetDegradeWarnings(): void {
  warned.clear()
}
