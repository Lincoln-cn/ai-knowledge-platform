import { ApiError, ErrorCode } from '@/api/types'
import type { PromptConfig, PromptType, UpsertPromptConfigBody, UpsertPromptTypeBody } from '@/api/prompts'
import { PERM } from '@/constants/permissions'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import { promptConfigs, promptTypes } from '../fixtures/prompts'
import { currentSubjects } from '../policy'

/**
 * 提示词的两个实体的动作（07 §3.3 硬规矩 2：界面上藏入口不是边界，这里才是）。
 *
 * 类型（分类法）：读沿用「配置应用」，写要「账号与权限」—— 改一次类型，所有配置的
 * 归类与应用里「从提示词选用」的分组都跟着变，与权限点清单同族，归管理员维护。
 * 配置（内容）：读写都归「配置应用」—— 页面 /assistants/prompts 的门禁就是它，
 * 接口用同一个点才叫界面与接口一致；否则「进不去页面却写得动接口」就是一条越权路径。
 */
const TYPE_READ_ACTION = PERM.assistantEdit
const TYPE_WRITE_ACTION = PERM.systemManage
const CONFIG_WRITE_ACTION = PERM.assistantEdit

/**
 * 动作级门禁。拿不到权限点一律 **403 且说清缺哪一个** ——
 * 不能静默返回空列表：那会让「写被拒」伪装成「保存成功但没变」。
 */
function requireAction(token: string, action: string, label: string): void {
  const subjects = currentSubjects(token)
  if (subjects.permissions.has(action)) return
  throw new ApiError(
    ErrorCode.FORBIDDEN,
    `没有${label}的权限（需要 ${action}）`,
    '请联系管理员为你的角色勾选该权限点'
  )
}

const findType = (id: string) => {
  const item = promptTypes.find((t) => t.id === id)
  if (!item) throw new ApiError(ErrorCode.NOT_FOUND, '提示词类型不存在')
  return item
}

const findConfig = (id: string) => {
  const item = promptConfigs.find((c) => c.id === id)
  if (!item) throw new ApiError(ErrorCode.NOT_FOUND, '提示词不存在')
  return item
}

const syncTypeCount = (typeId: string) => {
  const type = promptTypes.find((t) => t.id === typeId)
  if (type) type.promptCount = promptConfigs.filter((c) => c.typeId === typeId).length
}

export const promptRoutes: MockRoute[] = [
  [
    'GET',
    '/prompt-types',
    ({ query, token }) => {
      requireAction(token, TYPE_READ_ACTION, '查看提示词类型')
      const keyword = String(query.keyword ?? '')
      const list = promptTypes.filter((t) => !keyword || t.typeName.includes(keyword))
      return paginate(list, query)
    }
  ],

  [
    'POST',
    '/prompt-types',
    ({ body, token }) => {
      requireAction(token, TYPE_WRITE_ACTION, '维护提示词类型')
      const input = body as UpsertPromptTypeBody
      if (promptTypes.some((t) => t.typeName === input.typeName)) {
        throw new ApiError(ErrorCode.BAD_REQUEST, `提示词类型「${input.typeName}」已存在`)
      }
      const created: PromptType = {
        id: `pt${Date.now()}`,
        typeName: input.typeName,
        icon: input.icon,
        promptCount: 0
      }
      promptTypes.push(created)
      return created
    }
  ],

  [
    'PUT',
    '/prompt-types/:id',
    ({ path, body, token }) => {
      requireAction(token, TYPE_WRITE_ACTION, '维护提示词类型')
      const item = findType(path.id)
      const input = body as Partial<UpsertPromptTypeBody>
      if (input.typeName && input.typeName !== item.typeName && promptTypes.some((t) => t.typeName === input.typeName)) {
        throw new ApiError(ErrorCode.BAD_REQUEST, `提示词类型「${input.typeName}」已存在`)
      }
      if (input.typeName) {
        item.typeName = input.typeName
        for (const c of promptConfigs.filter((x) => x.typeId === item.id)) {
          c.typeName = input.typeName
        }
      }
      if (input.icon) item.icon = input.icon
      return item
    }
  ],

  [
    'DELETE',
    '/prompt-types/:id',
    ({ path, token }) => {
      requireAction(token, TYPE_WRITE_ACTION, '维护提示词类型')
      const item = findType(path.id)
      const used = promptConfigs.filter((c) => c.typeId === item.id).length
      if (used > 0) {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          `类型「${item.typeName}」下还有 ${used} 条提示词`,
          '请先删除或移动这些提示词，再删除类型'
        )
      }
      promptTypes.splice(promptTypes.indexOf(item), 1)
      return undefined
    }
  ],

  [
    'GET',
    '/prompt-configs',
    ({ query }) => {
      const keyword = String(query.keyword ?? '')
      const typeId = query.typeId ? String(query.typeId) : ''
      const list = promptConfigs
        .filter((c) => !keyword || c.roleName.includes(keyword) || c.callWord.includes(keyword))
        .filter((c) => !typeId || c.typeId === typeId)
      return paginate(list, query)
    }
  ],

  [
    'POST',
    '/prompt-configs',
    ({ body, token }) => {
      requireAction(token, CONFIG_WRITE_ACTION, '维护提示词配置')
      const input = body as UpsertPromptConfigBody
      const type = findType(input.typeId)
      if (promptConfigs.some((c) => c.roleName === input.roleName)) {
        throw new ApiError(ErrorCode.BAD_REQUEST, `角色名称「${input.roleName}」已存在`)
      }
      const created: PromptConfig = {
        id: `pc${Date.now()}`,
        roleName: input.roleName,
        callWord: input.callWord,
        typeId: type.id,
        typeName: type.typeName,
        description: input.description,
        icon: input.icon,
        usageCount: 0,
        createdAt: new Date().toISOString()
      }
      promptConfigs.unshift(created)
      syncTypeCount(type.id)
      return created
    }
  ],

  [
    'PUT',
    '/prompt-configs/:id',
    ({ path, body, token }) => {
      requireAction(token, CONFIG_WRITE_ACTION, '维护提示词配置')
      const item = findConfig(path.id)
      const input = body as Partial<UpsertPromptConfigBody>
      if (input.roleName && input.roleName !== item.roleName && promptConfigs.some((c) => c.roleName === input.roleName)) {
        throw new ApiError(ErrorCode.BAD_REQUEST, `角色名称「${input.roleName}」已存在`)
      }
      if (input.roleName) item.roleName = input.roleName
      if (input.callWord !== undefined) item.callWord = input.callWord
      if (input.description !== undefined) item.description = input.description
      if (input.icon) item.icon = input.icon
      if (input.typeId && input.typeId !== item.typeId) {
        const type = findType(input.typeId)
        const old = item.typeId
        item.typeId = type.id
        item.typeName = type.typeName
        syncTypeCount(old)
        syncTypeCount(type.id)
      }
      return item
    }
  ],

  [
    'DELETE',
    '/prompt-configs/:id',
    ({ path, token }) => {
      requireAction(token, CONFIG_WRITE_ACTION, '维护提示词配置')
      const item = findConfig(path.id)
      if (item.usageCount > 0) {
        throw new ApiError(
          ErrorCode.BAD_REQUEST,
          `「${item.roleName}」正被 ${item.usageCount} 个应用使用`,
          '请先在应用配置中移除引用，再删除该提示词'
        )
      }
      promptConfigs.splice(promptConfigs.indexOf(item), 1)
      syncTypeCount(item.typeId)
      return undefined
    }
  ]
]
