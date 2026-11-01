/**
 * 「这个操作的后端还没实现」—— 把一次 404 变成一句点名欠账的话（`#160` 判据 2）。
 *
 * <h3>为什么不能只用通用提示</h3>
 * <p>用户点「保存」，拿到一句「保存失败」，会以为是自己的操作或数据有问题，于是反复重试；
 * 而真实原因是**后端还没写这个端点**。两者处置完全不同：前者要改用法，后者只能等排期。
 * 所以这里把「欠账」直接写进文案，并带上 Issue 号 —— 用户去提工单时也有话说。
 *
 * <h3>为什么不做成"降级成空数据"</h3>
 * <p>`degrade.ts` 的降级只适合**读**（为空就按空渲染）。保存/发布/预览版本这类**写或按需取数**的动作
 * 降级成空会让界面显示"已保存/暂无历史"，也就是把失败伪装成成功 —— 那是本工程的头号病
 * （`silent-failure`）。所以这里的形状是「**照旧算失败，但把原因说清楚**」。
 */

import { isEndpointMissing } from './degrade.ts'

/**
 * 若这个错误是「后端还没实现这个端点」，返回一句点名欠账的可读文案；否则返回 `null`。
 *
 * 返回 `null` 时调用方应走原有错误提示 —— ★ 不要在这里兜底成一句通用文案，
 * 那会把「500 / 越权 / 网络不通」也一并说成"没接后端"，反而更难查。
 *
 * @param error 捕获到的错误（`ApiError` 形状：带 `code` 与 `message`）
 * @param capability 用户视角的动作名，例如「在编排里保存」「查看历史版本」
 * @param issue 欠账的 Issue 号，例如 `#198`
 */
export function pendingEndpointHint(
  error: unknown,
  capability: string,
  issue: string
): string | null {
  if (!isEndpointMissing(error)) return null
  return `「${capability}」还没接后端（${issue}），这次操作不会生效 —— 不是你的操作有问题。`
}
