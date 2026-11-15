/**
 * 长期记忆（0.9.0）的接口契约。
 *
 * <p>★ 与「对话设置」分开：对话设置管的是上下文窗口，记忆管的是"跨会话还剩下什么" ——
 * 两件事的生效范围不同（前者一轮、后者永久），混在一个页面里会让人以为关掉窗口就等于清空记忆。
 */
import { request } from '@/lib/http';

/** 记忆的四个层级（后端 `MemoryLevel` 的 code）。 */
export type MemoryLevel = 'user' | 'conversation' | 'persona' | 'cross_session';

/** 记忆的种类（后端 `MemoryKind` 的 code）。 */
export type MemoryKind = 'fact' | 'preference' | 'constraint' | 'goal' | 'event' | 'summary';

/** 一条记忆。 */
export interface MemoryItem {
  id: string;
  level: MemoryLevel;
  scopeId: string | null;
  kind: MemoryKind;
  content: string;
  importance: number;
  confidence: number;
  hitCount: number;
  lastHitAt?: string | null;
  sourceMessageIds?: string[];
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** 每层各有多少条 —— 页面上用来显示"记忆长在哪一层"。 */
export interface MemoryLevelCount {
  level: MemoryLevel;
  count: number;
}

/** 用户画像。空（`summary` 为空）表示还没形成画像。 */
export interface MemoryProfile {
  tone?: string | null;
  domains?: string[];
  taboos?: string[];
  summary?: string | null;
  updatedAt?: string | null;
}

/** 记忆的全局设定。 */
export interface MemorySettings {
  enabled: boolean;
  minImportance: number;
  recallLimit: number;
  injectProfile: boolean;
  dreamCron: string;
  forgetAfterDays: number;
  lastDreamAt?: string | null;
  lastDreamNote?: string | null;
  updatedAt?: string | null;
  updatedBy?: string | null;
}

/** 新增一条记忆（手动）。 */
export interface MemoryCreateBody {
  level: MemoryLevel;
  kind: MemoryKind;
  content: string;
  importance?: number;
}

export const listMemories = (level?: MemoryLevel) =>
  request<MemoryItem[]>({
    method: 'GET',
    url: '/memories',
    query: level ? { level } : undefined,
  });

export const memoryCounts = () => request<MemoryLevelCount[]>({ method: 'GET', url: '/memories/counts' });

export const getMemoryProfile = () => request<MemoryProfile>({ method: 'GET', url: '/memories/profile' });

export const createMemory = (body: MemoryCreateBody) =>
  request<MemoryItem>({ method: 'POST', url: '/memories', body });

export const updateMemory = (id: string, body: { content: string; importance?: number }) =>
  request<MemoryItem>({ method: 'PUT', url: '/memories/:id', path: { id }, body });

export const deleteMemory = (id: string) =>
  request<void>({ method: 'DELETE', url: '/memories/:id', path: { id } });

export const getMemorySettings = () =>
  request<MemorySettings>({ method: 'GET', url: '/memories/settings' });

export const updateMemorySettings = (body: Omit<MemorySettings, 'updatedAt' | 'updatedBy' | 'lastDreamAt' | 'lastDreamNote'>) =>
  request<MemorySettings>({ method: 'PUT', url: '/memories/settings', body });
