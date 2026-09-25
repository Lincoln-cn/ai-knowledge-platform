/**
 * 模型相关类型（旧工程 `api/type/model` 的子集）。
 *
 * 只保留编排画布实际用到的 `Provider` / `BaseModel` —— 旧文件里
 * `Model` / `CreateModelRequest` 等与新建工程的 `api/models.ts`
 * （`ModelInfo` / `UpsertModelBody`）重复，留着就是第二份真相。
 */

export interface Provider {
  /** 供应商代号 */
  provider: string
  /** 供应商名称 */
  name: string
  /** 供应商 icon */
  icon: string
}

export interface BaseModel {
  /** 基础模型名称 */
  name: string
  /** 基础模型描述 */
  desc: string
  /** 基础模型类型 */
  model_type: string
}
