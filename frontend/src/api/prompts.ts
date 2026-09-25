import { request } from '@/lib/http'
import type { PageQuery, PageResult } from './types'

/**
 * 提示词契约（契约缺口的只加不改最小扩展）。
 *
 * 旧系统把提示词类型 / 提示词配置做成 7 份副本（markedSystem × 3 目录），
 * 冻结的 `assistants.ts` 只留了 `promptIds`，没有实体本身的 CRUD。
 * 这里补齐两个实体的最小契约，不改任何已有文件。
 */

/* ────────────────────────── 契约类型 ────────────────────────── */

/** 提示词类型（字段命名沿用了被替换的旧契约） */
export interface PromptType {
  id: string
  typeName: string
  icon: string
  promptCount: number
}

/** 提示词配置（字段命名沿用了被替换的旧契约） */
export interface PromptConfig {
  id: string
  /** 角色名称 */
  roleName: string
  /** 提示词正文 */
  callWord: string
  typeId: string
  typeName: string
  description: string
  icon: string
  /** 被多少个应用引用 */
  usageCount: number
  createdAt: string
}

export interface PromptTypeQuery extends PageQuery {}

export interface PromptConfigQuery extends PageQuery {
  typeId?: string
}

export interface UpsertPromptTypeBody {
  typeName: string
  icon: string
}

export interface UpsertPromptConfigBody {
  roleName: string
  callWord: string
  typeId: string
  description: string
  icon: string
}

/* ────────────────────────── 接口 ────────────────────────── */

export const listPromptTypes = (query: PromptTypeQuery = {}) =>
  request<PageResult<PromptType>>({ method: 'GET', url: '/prompt-types', query })

export const createPromptType = (body: UpsertPromptTypeBody) =>
  request<PromptType>({ method: 'POST', url: '/prompt-types', body })

export const updatePromptType = (id: string, body: Partial<UpsertPromptTypeBody>) =>
  request<PromptType>({ method: 'PUT', url: '/prompt-types/:id', path: { id }, body })

export const deletePromptType = (id: string) =>
  request<void>({ method: 'DELETE', url: '/prompt-types/:id', path: { id } })

export const listPromptConfigs = (query: PromptConfigQuery = {}) =>
  request<PageResult<PromptConfig>>({ method: 'GET', url: '/prompt-configs', query })

export const createPromptConfig = (body: UpsertPromptConfigBody) =>
  request<PromptConfig>({ method: 'POST', url: '/prompt-configs', body })

export const updatePromptConfig = (id: string, body: Partial<UpsertPromptConfigBody>) =>
  request<PromptConfig>({ method: 'PUT', url: '/prompt-configs/:id', path: { id }, body })

export const deletePromptConfig = (id: string) =>
  request<void>({ method: 'DELETE', url: '/prompt-configs/:id', path: { id } })
