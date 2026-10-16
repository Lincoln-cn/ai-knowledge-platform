/**
 * 动效偏好开关（★ 第三轮新增）。
 *
 * <h3>为什么需要它</h3>
 * 使用者的机器上系统级"减少动效"是开着的（2026-10-16 实测 `prefers-reduced-motion: reduce`
 * 为 `true`，注册表 `MinAnimate = 0` 与之一致）。于是本工程所有动效都会被规范性地折叠 ——
 * 那是**正确的默认**，但使用者本人明确要求"看得到动效"（原话：「让登录有视觉先感受效果」）。
 *
 * ⇒ 结论：**系统默认仍然是默认，但使用者的显式要求优先。**
 *   开法是访问一次带 `?motion=on` 的地址；关掉是 `?motion=off`。两个值都会写进
 *   `localStorage`（只影响这一台机器的这一个浏览器），并投影成
 *   `<html data-motion="on">` —— CSS 侧的降动效守卫读的就是这个属性。
 *
 * <h3>三条纪律</h3>
 * <ol>
 *   <li>**默认行为一个字都不改**：没有这个属性时，`index.css` 末尾的全局塌缩照旧生效。</li>
 *   <li>**只有显式开启才绕过**：不猜、不因"用户看了登录页"就自动开。</li>
 *   <li>**不改系统设置**：这个开关是本工程的局部偏好，不碰 OS、不碰浏览器设置。</li>
 * </ol>
 *
 * <p>⚠️ 参数位置要宽容：本工程是 hash 路由，所以 `motion=on` 可能出现在 `?` 里
 * （`/?motion=on#/login`），也可能出现在 hash 的 query 里（`/#/login?motion=on`）。
 * 直接把整串 URL 扫一遍最稳 —— 少一处"只有某种写法才生效"的坑。
 */

const STORAGE_KEY = 'aip.motion'

export type MotionOverride = 'on' | 'off'

/** 从整串 URL 里读 `motion=on|off`（hash 路由下两种位置都要认） */
function readFromUrl(): MotionOverride | null {
  const matched = /[?&]motion=(on|off)\b/i.exec(window.location.href)
  if (!matched) return null
  return matched[1].toLowerCase() as MotionOverride
}

function readStored(): MotionOverride | null {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return stored === 'on' || stored === 'off' ? stored : null
  } catch {
    // 隐私模式 / 存储被禁用：不是错误，静默降级成"本次会话用 URL 上那个值"
    return null
  }
}

/**
 * 在挂载前调用一次。
 *
 * <p>为什么必须在挂载前：CSS 的降动效守卫读 `<html data-motion>`，
 * 挂载后再写会让首帧先按"塌缩"渲染一次、再跳成完整动效 —— 那是可见的闪烁。
 */
export function initMotionPreference(): void {
  const requested = readFromUrl()
  if (requested) {
    try {
      localStorage.setItem(STORAGE_KEY, requested)
    } catch {
      // 同上：存不下也要让**本次会话**生效
    }
  }

  const effective = requested ?? readStored()
  if (effective === 'on') {
    document.documentElement.dataset.motion = 'on'
  } else {
    delete document.documentElement.dataset.motion
  }
}
