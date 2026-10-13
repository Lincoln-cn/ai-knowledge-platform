import { ElMessage } from 'element-plus'

/**
 * 复制到剪贴板 —— **唯一出口**。
 *
 * ★ 旧实现裸调 `navigator.clipboard.writeText`：它在**非安全上下文**（http 访问的内网
 *   部署很常见）与旧内核里是 `undefined`，会抛 `TypeError` ⇒ 用户点了"复制链接"没有任何
 *   反应。"复制"正是分享链路的最后一步 —— 它静默失败，等于分享功能不存在。
 *   所以先走 Clipboard API，**失败再落到 `execCommand` 兜底**（已废弃，但仍是唯一可用的
 *   同步路径）。
 *
 * ★ 返回布尔而不是自己弹提示：调用方要说的话各不相同（"已复制链接" / "已复制链接与内容"
 *   / "复制失败，请手动选中复制"），而"失败"必须由调用方说出来，不许吞。
 */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    /* 权限被拒 / 非安全上下文 —— 落到下面的兜底路径 */
  }

  try {
    const el = document.createElement('textarea')
    el.value = text
    el.setAttribute('readonly', 'readonly')
    el.style.position = 'fixed'
    el.style.top = '-1000px'
    el.style.opacity = '0'
    document.body.appendChild(el)
    el.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(el)
    return ok
  } catch {
    return false
  }
}

/**
 * 「点一下就复制」的便捷包装（画布节点里的全局变量、模板占位符用）。
 * 固定文案的第二类调用走它；需要自定义提示的走上面的 `copyText`。
 */
export async function copyClick(info: string) {
  const ok = await copyText(info)
  if (ok) ElMessage.success('复制成功')
  else ElMessage.error('复制失败，请手动选中复制')
}
