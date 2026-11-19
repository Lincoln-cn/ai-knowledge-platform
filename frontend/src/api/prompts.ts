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

/**
 * 提示词的两种用途。
 *
 * - `ROLE` 角色指令：给**模型**的身份设定。在应用配置里选用后写进应用的 `role_prompt`。
 * - `QUESTION` 提问模板：给**终端用户**的问法示例。出现在对话页的空态与上弹抽屉里，正文就是那句问题。
 *
 * ★ 两者共用一张表（字段几乎重合），差别只在受益对象 —— 见后端 `PromptKind` 的说明。
 */
export type PromptKind = 'ROLE' | 'QUESTION'

/** 提示词配置（字段命名沿用了被替换的旧契约） */
export interface PromptConfig {
  id: string
  kind: PromptKind
  /** 提问模板推荐给哪个应用；null = 通用。★ 只是候选池标注，运行期不读 */
  appId: string | null
  /** 名称（角色指令叫"角色名称"，提问模板叫"模板名"） */
  roleName: string
  /** 正文：角色指令是给模型的指令，提问模板就是用户会看到的那句问题 */
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
  /**
   * 只看某一类。★ 不传 = 全部；调用方要"只看角色指令"必须显式传 `'ROLE'` ——
   * 给它一个默认值，提问模板就会悄悄混进应用配置的选用列表。
   */
  kind?: PromptKind
  appId?: string
}

export interface UpsertPromptTypeBody {
  typeName: string
  icon: string
}

export interface UpsertPromptConfigBody {
  kind?: PromptKind
  appId?: string | null
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

/* ────────────────────── 版本历史（ROADMAP §11.62） ────────────────────── */

/** 一版历史的摘要（列表用）。`versionNo` 越大越新。 */
export interface PromptConfigVersionSummary {
  versionNo: number
  savedAt: string
  savedBy: string | null
  /** 正文前 60 字 —— 列表里够辨认，看全文再按需取。 */
  preview: string
}

/** 某一版的全文；`callWord` 就是正文。 */
export interface PromptConfigVersionDetail {
  versionNo: number
  roleName: string
  callWord: string
  typeId: string
  description: string
  icon: string
  savedAt: string
  savedBy: string | null
}

export const listPromptConfigVersions = (id: string) =>
  request<PromptConfigVersionSummary[]>({
    method: 'GET',
    url: '/prompt-configs/:id/versions',
    path: { id }
  })

export const getPromptConfigVersion = (id: string, versionNo: number) =>
  request<PromptConfigVersionDetail>({
    method: 'GET',
    url: '/prompt-configs/:id/versions/:v',
    path: { id, v: versionNo }
  })

/**
 * 回滚到某一版。
 *
 * ★ 语义是"以那一版的内容再写一次"，服务端会生成**新的一版** —— 历史一条都不删。
 * 所以回滚之后版本数 +1，而不是回到当时的样子。
 */
export const restorePromptConfigVersion = (id: string, versionNo: number) =>
  request<PromptConfig>({
    method: 'POST',
    url: '/prompt-configs/:id/versions/:v/restore',
    path: { id, v: versionNo },
    body: {}
  })
