/** 默认图标：等于它就被当作「没有图标」，界面改显示字母头像 */
export const defaultIcon = '/ui/favicon.ico'

export function isAppIcon(url: string | undefined) {
  return url === defaultIcon ? '' : url
}

/**
 * 是否是编排型应用。
 *
 * **两套值都认**：旧工程用 `WORK_FLOW`，新契约（`api/assistants.ts`）用 `workflow`。
 * 搬迁期画布里的数据可能来自任一侧 —— 只认一个值会让另一侧静默走错分支，
 * 而这类分支的后果是「编排应用被当成简单应用渲染」，页面上不报错、只是不对。
 */
export function isWorkFlow(type: string | undefined) {
  return type === 'WORK_FLOW' || type === 'workflow'
}
