/**
 * 记忆管理：平台已经记下来的东西（分层计数、画像、逐条记忆）。
 *
 * <p>★ 权限用 `conversation:settings`（与「长期记忆」页同源）：能改记忆规则的人才该能逐条改/删记忆 ——
 * 记忆是喂给模型的上下文，改它等于间接影响所有人的回答。
 */
import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

const route: RouteRecordRaw = {
  path: 'memories',
  name: 'memories',
  meta: { title: '记忆管理', permission: PERM.chatSettings },
  component: () => import('./Index.vue'),
}

export default route
