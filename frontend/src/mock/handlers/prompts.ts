import { ApiError, ErrorCode } from '@/api/types'
import type { PromptConfig, PromptType, UpsertPromptConfigBody, UpsertPromptTypeBody } from '@/api/prompts'
import type { MockRoute } from '../dispatch'
import { paginate } from '../dispatch'
import { promptConfigs, promptTypes } from '../fixtures/prompts'

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
    ({ query }) => {
      const keyword = String(query.keyword ?? '')
      const list = promptTypes.filter((t) => !keyword || t.typeName.includes(keyword))
      return paginate(list, query)
    }
  ],

  [
    'POST',
    '/prompt-types',
    ({ body }) => {
      const input = body as UpsertPromptTypeBody
      if (promptTypes.some((t) => t.typeName === input.typeName)) {
        throw new ApiError(4002, `提示词类型「${input.typeName}」已存在`)
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
    ({ path, body }) => {
      const item = findType(path.id)
      const input = body as Partial<UpsertPromptTypeBody>
      if (input.typeName && input.typeName !== item.typeName && promptTypes.some((t) => t.typeName === input.typeName)) {
        throw new ApiError(4002, `提示词类型「${input.typeName}」已存在`)
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
    ({ path }) => {
      const item = findType(path.id)
      const used = promptConfigs.filter((c) => c.typeId === item.id).length
      if (used > 0) {
        throw new ApiError(
          4003,
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
    ({ body }) => {
      const input = body as UpsertPromptConfigBody
      const type = findType(input.typeId)
      if (promptConfigs.some((c) => c.roleName === input.roleName)) {
        throw new ApiError(4002, `角色名称「${input.roleName}」已存在`)
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
    ({ path, body }) => {
      const item = findConfig(path.id)
      const input = body as Partial<UpsertPromptConfigBody>
      if (input.roleName && input.roleName !== item.roleName && promptConfigs.some((c) => c.roleName === input.roleName)) {
        throw new ApiError(4002, `角色名称「${input.roleName}」已存在`)
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
    ({ path }) => {
      const item = findConfig(path.id)
      if (item.usageCount > 0) {
        throw new ApiError(
          4004,
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
