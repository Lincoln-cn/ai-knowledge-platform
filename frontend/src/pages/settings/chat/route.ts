import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

export default [
  {
    path: 'settings/chat',
    name: 'settings-chat',
    component: () => import('./Index.vue'),
    // ★ 页面本身允许"看"（`conversation:view`）：界面要显示当前生效值，
    //   否则用户遇到"生成超时"时连"现在多少秒"都看不到。
    //   真正的写权限（`system:manage`）在页面内按按钮与 API 各自把住 ——
    //   权限点分开是因为「能看自己的会话」不等于「能改平台默认」。
    meta: { title: '对话设置', permission: PERM.conversationView }
  }
] satisfies RouteRecordRaw[]
