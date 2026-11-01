/**
 * 发布失败包络里的 issue 清单收窄（Issue #260）。
 *
 * ★ 为什么要有这个函数：
 *   后端 `WorkflowPublishService` 校验不过时抛 `BusinessException`，明细（每条 issue）装进失败包络的
 *   `data`，经 `ApiError.details` 到达页面。到达时它是 `unknown` —— 前端这一层**不认识**它的形状，
 *   而 `as WorkflowValidationIssue[]` 这种断言只是让编译器闭嘴：
 *   真来一条坏行（后端改了字段名、或 mock 与真后端不一致），面板会渲染出空白行、点一行也不定位，
 *   表现是「按钮坏了」。所以逐个字段校验，合格的才进清单。
 *
 * ★ 为什么不合格的行是**丢掉**而不是报错：
 *   这是展示层。丢掉一行 = 少显示一条问题，用户仍能看到 message 那段文本（页面在
 *   清单为空时退回原文展示）；而抛异常会把一次「发布没成功」变成「发布界面崩了」。
 *   代价是写下这条：**形状漂移在这里是静默的**，所以要有一条"形状不对时返回空数组"的测试钉着它。
 */
import type { WorkflowValidationIssue } from '@/api/workflow'

const isRecord = (v: unknown): v is Record<string, unknown> =>
  typeof v === 'object' && v !== null && !Array.isArray(v)

/**
 * 从失败包络的 `details` 里取出合格的 issue 清单。
 *
 * @param details `ApiError.details`（后端失败包络的 `data`），通常是数组，也可能缺失或是别的形状
 * @returns 只含形状合格项的新数组；一个都不合格时返回空数组（调用方据此退回文本说明）
 */
export const issuesFromDetails = (details: unknown): WorkflowValidationIssue[] => {
  if (!Array.isArray(details)) return []
  const out: WorkflowValidationIssue[] = []
  for (const raw of details) {
    if (!isRecord(raw)) continue
    const level = raw.level
    // 只认契约里那两个取值：其它值的行下面会被渲染成"既不是红也不是黄"的空壳
    if (level !== 'ERROR' && level !== 'WARNING') continue
    if (typeof raw.code !== 'string' || raw.code.trim().length === 0) continue
    if (typeof raw.message !== 'string' || raw.message.trim().length === 0) continue
    out.push({
      level,
      code: raw.code,
      message: raw.message,
      // hint / nodeId 在契约里是可空位：缺省与 null 同义，非字符串一律当没有
      hint: typeof raw.hint === 'string' ? raw.hint : null,
      nodeId: typeof raw.nodeId === 'string' && raw.nodeId.length > 0 ? raw.nodeId : null
    })
  }
  return out
}
