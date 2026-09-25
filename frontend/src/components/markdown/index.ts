/**
 * 共享 Markdown 渲染器。
 *
 * 最小用法（与会话 AnswerBody 引注口径一致）：
 * ```vue
 * <MdPreview :source="text" citations @cite="onCite" />
 * ```
 *
 * 带图表 / 表单特殊块：
 * ```vue
 * <MdRenderer :source="text" citations @cite="onCite" @submit="onSubmit" />
 * ```
 *
 * 不依赖 md-editor-v3；内容先转义再插标签。
 */
export { default as MdRenderer } from './MdRenderer.vue'
export { default as MdPreview } from './MdPreview.vue'
export { default as HtmlRander } from './HtmlRander.vue'
export { default as EchartsRander } from './EchartsRander.vue'
export { default as FormRander } from './FormRander.vue'
export {
  citeIndexFromEvent,
  escapeHtml,
  parseMarkdown,
  renderInline,
  splitSpecialSections
} from './markdown'
export type { FormSettingData, MdBlock, MdRenderOptions, MdSection } from './types'
