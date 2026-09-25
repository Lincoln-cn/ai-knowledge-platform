/**
 * 画布上下文。
 *
 * 为什么要这个：LogicFlow 的每个节点都会 `createApp()` 建一个**独立的 Vue 子应用**
 * （见 `components/common/app-node.ts`），子应用里没有装 router ⇒
 * 节点组件里 `useRoute()` 拿不到东西。
 *
 * 旧代码的解法是 `import { app } from '@/main'` 再读
 * `app.config.globalProperties.$route` —— 那是"从主应用偷全局变量"，
 * 既依赖主应用导出自己、又形成 main ↔ pages 的循环依赖。
 * 这里改成**显式的单向数据传递**：页面写入，节点读取。
 */
import { ref } from 'vue'

let appId = ''

/** 页面在渲染画布**之前**调用 */
export function setAppId(value: string) {
  appId = value
}

/** 节点在 setup 时读取当前编排应用 id */
export function getAppId() {
  return appId
}

/**
 * 「全部收起 / 全部展开」的批量开关。
 *
 * 必须是 **Vue ref**：LogicFlow 的 `nodeModel.properties` 不是响应式对象，
 * 直接改它不会触发节点组件重渲染。批量按钮只改这对 ref，
 * 各 NodeContainer 本地 `watch` 后更新自己的展开态。
 *
 * `collapseTick` 用于「已收起再点收起」时强制重同步 ——
 * 否则 collapseAll 已是 true，watch 不触发，单独展开过的节点关不掉。
 */
export const collapseAll = ref(false)
export const collapseTick = ref(0)

export function collapseEvery() {
  collapseAll.value = true
  collapseTick.value++
}

export function expandEvery() {
  collapseAll.value = false
  collapseTick.value++
}

