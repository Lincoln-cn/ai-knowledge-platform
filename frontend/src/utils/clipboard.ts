import { ElMessage } from 'element-plus'

/**
 * 复制到剪贴板。
 *
 * 旧实现依赖 `vue-clipboard3`（又一个依赖）—— 这里改用原生 Clipboard API。
 * 注意它需要 **安全上下文**（https 或 localhost），非安全上下文下
 * `navigator.clipboard` 为 undefined，所以保留失败提示而不是静默。
 */
export async function copyClick(info: string) {
  try {
    if (!navigator.clipboard) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(info)
    ElMessage.success('复制成功')
  } catch (e) {
    console.error(e)
    ElMessage.error('复制失败')
  }
}
