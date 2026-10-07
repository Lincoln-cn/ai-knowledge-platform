import type { DictionaryItem } from '@/api/dictionaries'

/**
 * 基础数据字典夹具（08 §十）。
 *
 * ★ 逐项照抄后端种子的**原值**（`V24__platform_dictionary.sql`）：文种与库分类就是历史数据里
 * 已经用着的那个中文值（不是新造的 ASCII 码 —— 换成 ASCII 要全表重新向量化），
 * 密级那一维才是 SCREAMING_SNAKE（它要在 SQL 里比较与排序）。
 *
 * 照抄而不是"顺手改名"的理由：两边不一致的症状是"演示时好用、部署后不一样"。
 * 备注也照抄 —— 密级那两条的备注正是"为什么不能加档"的指路文案。
 */
export const dictionaryItems: DictionaryItem[] = [
  // 密级：★ 只改 label / sort / enabled；增删档位要连 classification_rank 生成列一起迁移
  {
    kind: 'CLASSIFICATION',
    code: 'PUBLIC',
    label: '公开',
    sort: 1,
    enabled: true,
    remark: '对外可公开；★ 档位与排序由迁移固定（V21/V22 的生成列）'
  },
  { kind: 'CLASSIFICATION', code: 'INTERNAL', label: '内部', sort: 2, enabled: true, remark: '仅内部使用' },
  {
    kind: 'CLASSIFICATION',
    code: 'SECRET',
    label: '秘密',
    sort: 3,
    enabled: true,
    remark: '★ 工程惯例用语，非法定密级（13 §1.2）'
  },
  {
    kind: 'CLASSIFICATION',
    code: 'CONFIDENTIAL',
    label: '机密',
    sort: 4,
    enabled: true,
    remark: '★ 工程惯例用语，非法定密级（13 §1.2）'
  },

  // 文种（契约里叫 doc_type）
  { kind: 'DOC_TYPE', code: '制度', label: '制度', sort: 1, enabled: true, remark: null },
  { kind: 'DOC_TYPE', code: '合同', label: '合同', sort: 2, enabled: true, remark: null },
  { kind: 'DOC_TYPE', code: '报销规定', label: '报销规定', sort: 3, enabled: true, remark: null },
  { kind: 'DOC_TYPE', code: '管理办法', label: '管理办法', sort: 4, enabled: true, remark: null },
  { kind: 'DOC_TYPE', code: '操作规程', label: '操作规程', sort: 5, enabled: true, remark: null },
  { kind: 'DOC_TYPE', code: '通知公告', label: '通知公告', sort: 6, enabled: true, remark: null },
  { kind: 'DOC_TYPE', code: '工作报告', label: '工作报告', sort: 7, enabled: true, remark: null },
  { kind: 'DOC_TYPE', code: '培训手册', label: '培训手册', sort: 8, enabled: true, remark: null },

  // 库分类：以前是从已有库的 category 去重统计出来的，现在有真源了
  { kind: 'KB_CATEGORY', code: '制度规范', label: '制度规范', sort: 1, enabled: true, remark: null },
  { kind: 'KB_CATEGORY', code: '产品资料', label: '产品资料', sort: 2, enabled: true, remark: null },
  { kind: 'KB_CATEGORY', code: '行业标准', label: '行业标准', sort: 3, enabled: true, remark: null },

  // 提示词类型：界面上原有这个 tab，但只有 mock —— 现在归到同一个字典里
  { kind: 'PROMPT_TYPE', code: '通用', label: '通用', sort: 1, enabled: true, remark: null },
  { kind: 'PROMPT_TYPE', code: '合同法务', label: '合同法务', sort: 2, enabled: true, remark: null },
  { kind: 'PROMPT_TYPE', code: '制度咨询', label: '制度咨询', sort: 3, enabled: true, remark: null },
  { kind: 'PROMPT_TYPE', code: '写作辅助', label: '写作辅助', sort: 4, enabled: true, remark: null }
]
