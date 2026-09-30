/** markdown 渲染器公共类型 */

export type MdBlock =
  | { type: 'p'; html: string }
  /** ATX 标题：`#` ~ `######`，level ∈ [1,6] */
  | { type: 'h'; level: number; html: string }
  | { type: 'ul'; items: string[] }
  | { type: 'ol'; items: string[] }
  | { type: 'code'; html: string; lang: string }
  /** GFM 管道表格：header + 行数据；cell 已过 renderInline（转义 + 行内语法） */
  | { type: 'table'; header: string[]; rows: string[][] }

/** MdRenderer 把源文本切成的段落块 */
export type MdSection =
  | { type: 'md'; content: string }
  | { type: 'echarts'; content: string }
  | { type: 'form'; content: string }
  /** 旧版可插任意 HTML；现在只作转义文本展示，绝不当 HTML 执行 */
  | { type: 'html'; content: string }

export interface MdRenderOptions {
  /** 把 [N] 渲染成可点击引注（与会话 AnswerBody 口径一致） */
  citations?: boolean
}

export interface FormSettingData {
  form_field_list?: Array<any>
  form_data?: Record<string, any>
  is_submit?: boolean
}
