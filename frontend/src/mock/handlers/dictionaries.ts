import { ApiError, ErrorCode } from '@/api/types'
import type { CreateDictionaryBody, DictionaryItem, DictionaryKind, UpdateDictionaryBody } from '@/api/dictionaries'
import { PERM } from '@/constants/permissions'
import type { MockRoute } from '../dispatch'
import { dictionaryItems } from '../fixtures/dictionaries'
import { currentSubjects } from '../policy'

/**
 * 基础数据字典（08 §十）。与后端 `DictionaryController` / `DictionaryService` **逐条对齐** ——
 * mock 比后端严或松都会变成"演示时好用、部署后不一样"，而这正是本文件最容易犯的错。
 *
 * ★ 读与写的门禁**刻意不同**：读登录即可（建档/上传/筛选的下拉都要读它，卡上管理权限等于让
 * 所有人点上传对话框时撞 403），写要 `platform:dict:manage`。
 *
 * ★ **故意没有 DELETE**：字典项只停用不删除。`code` 一旦写进历史数据（`kb_document.doc_type`、
 * `kb_knowledge_base.category`），删掉会让那些数据的展示回落成机器码而**没有任何地方报错**；
 * 停用才表达得出"新数据不要再选它，既有数据照旧可读"。这里不实现 DELETE，
 * 与后端"没有这个接口"同形 —— 不写一个只是更严的假接口。
 */

const MANAGE_ACTION = PERM.dictManage

const KINDS: DictionaryKind[] = ['CLASSIFICATION', 'DOC_TYPE', 'PROMPT_TYPE', 'KB_CATEGORY']

/** 与后端同一个形状（`[\\p{IsHan}\\p{Alnum}_]{1,64}`）：中文取值（文种）也走这一支 */
const CODE_SHAPE = /^[\p{Script=Han}A-Za-z0-9_]{1,64}$/u

const MAX_LABEL = 64
const MAX_REMARK = 255

/** 动作级门禁：拿不到权限点一律 **403 且说清缺哪一个** —— 静默返回空列表会把"写被拒"伪装成"保存成功但没变" */
function requireManage(token: string, label: string): void {
  const subjects = currentSubjects(token)
  if (subjects.permissions.has(MANAGE_ACTION)) return
  throw new ApiError(
    ErrorCode.FORBIDDEN,
    `没有${label}的权限（需要 ${MANAGE_ACTION}）`,
    '请联系管理员为你的角色勾选该权限点'
  )
}

function requireKind(kind: unknown): DictionaryKind {
  if (typeof kind === 'string' && (KINDS as string[]).includes(kind)) return kind as DictionaryKind
  throw new ApiError(ErrorCode.BAD_REQUEST, `未知的字典种类：「${String(kind)}」`, `允许的取值：${KINDS.join(' / ')}`)
}

function requireCode(code: unknown): string {
  if (typeof code !== 'string' || !code.trim()) {
    throw new ApiError(ErrorCode.BAD_REQUEST, '缺少 code', 'code 是业务键，不能为空')
  }
  const trimmed = code.trim()
  if (!CODE_SHAPE.test(trimmed)) {
    throw new ApiError(
      ErrorCode.BAD_REQUEST,
      `code 只允许中文、字母、数字与下划线，且不超过 64 字：收到「${trimmed}」`,
      'code 会写进历史数据与召回前缀，形状必须稳定'
    )
  }
  return trimmed
}

function requireLabel(label: unknown): string {
  if (typeof label !== 'string' || !label.trim()) {
    throw new ApiError(ErrorCode.BAD_REQUEST, '缺少展示名', '展示名会出现在下拉里，不能为空')
  }
  const trimmed = label.trim()
  if (trimmed.length > MAX_LABEL) {
    throw new ApiError(ErrorCode.BAD_REQUEST, `展示名不能超过 ${MAX_LABEL} 字`)
  }
  return trimmed
}

/** 去空白后为空的备注一律当作"没有"（与后端 trim 同形），超长报错 */
function trimRemark(remark: unknown): string | null {
  if (typeof remark !== 'string') return null
  const trimmed = remark.trim()
  if (!trimmed) return null
  if (trimmed.length > MAX_REMARK) {
    throw new ApiError(ErrorCode.BAD_REQUEST, `备注不能超过 ${MAX_REMARK} 字`)
  }
  return trimmed
}

const findItem = (kind: DictionaryKind, code: string) =>
  dictionaryItems.find((item) => item.kind === kind && item.code === code)

/** 不传 sort 就排到本类末尾（后端是 `max(sort) + 1`） */
const nextSort = (kind: DictionaryKind) =>
  dictionaryItems.filter((item) => item.kind === kind).reduce((max, item) => Math.max(max, item.sort), 0) + 1

export const dictionaryRoutes: MockRoute[] = [
  [
    'GET',
    '/dictionaries',
    ({ query, token }) => {
      // 读不查权限点，但仍要登录（与后端 anyRequest().authenticated() 一致）
      currentSubjects(token)
      const kind = requireKind(query.kind)
      const includeDisabled = query.includeDisabled === true || query.includeDisabled === 'true'
      // filter 已经返回新数组，排序不会动到夹具；顺序与后端的 order by sort, code 对齐
      return dictionaryItems
        .filter((item) => item.kind === kind && (includeDisabled || item.enabled))
        .sort((a, b) => a.sort - b.sort || a.code.localeCompare(b.code))
    }
  ],

  [
    'POST',
    '/dictionaries',
    ({ body, token }) => {
      requireManage(token, '维护基础数据')
      const input = body as CreateDictionaryBody
      const kind = requireKind(input.kind)
      const code = requireCode(input.code)

      // ★ 密级是**排序比较轴**而不是一张清单：classification_rank 是生成列、四档写死在 V21/V22 里。
      //   往字典里插第五档的症状是"新档位的内容对所有人都不可见"（else 99 = fail-closed），
      //   而无人知道为什么 —— 所以这里明确拒绝，并把"要改什么"写进 hint（报错要指路）。
      if (kind === 'CLASSIFICATION') {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          '密级不允许新增档位',
          '密级是排序比较轴：classification_rank 是生成列，四档写死在 V21/V22 里。加一档要同时改生成列（一次迁移）并跑 ClassificationRankTest，否则新档位的内容对所有人都不可见'
        )
      }
      // 停用过的项也算重复：它是"不许再新增同名 code"，不是"不许出现两条启用项"
      if (findItem(kind, code)) {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          `「${code}」已经存在`,
          '同一种类内的 code 必须唯一 —— 要改展示名请编辑那一项，不要新增'
        )
      }

      const created: DictionaryItem = {
        kind,
        code,
        label: requireLabel(input.label),
        sort: typeof input.sort === 'number' ? input.sort : nextSort(kind),
        enabled: true,
        remark: trimRemark(input.remark)
      }
      dictionaryItems.push(created)
      return created
    }
  ],

  [
    'PUT',
    '/dictionaries',
    ({ body, token }) => {
      requireManage(token, '维护基础数据')
      const input = body as UpdateDictionaryBody
      const kind = requireKind(input.kind)
      const code = requireCode(input.code)

      const item = findItem(kind, code)
      if (!item) {
        throw new ApiError(ErrorCode.NOT_FOUND, `字典项不存在：「${code}」`, '请刷新后重试')
      }

      // 只落**传了**的字段（后端是 coalesce）：所以"不传"与"传 null"都是保持原值，
      // 而空备注去空白后是 null ⇒ 也等于不修改。界面因此不假装能清空备注。
      if (input.label != null) item.label = requireLabel(input.label)
      if (typeof input.sort === 'number') item.sort = input.sort
      if (typeof input.enabled === 'boolean') item.enabled = input.enabled
      const remark = trimRemark(input.remark)
      if (remark) item.remark = remark
      // code 不在可改字段里：它已经写进历史数据与召回前缀，改它要全表重新向量化
      return item
    }
  ]
]
