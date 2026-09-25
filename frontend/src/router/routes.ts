import type { RouteRecordRaw } from 'vue-router'
import type { PermissionCode } from '@/constants/permissions'

/**
 * 路由元信息的类型声明。
 *
 * `permission` 是**路由级门禁**的唯一声明点：守卫拦不拦、侧栏显不显，都只读它。
 * 不给 `NavItem` 另加一个权限字段 —— 那样同一个权限就有两处声明，
 * 迟早会出现「菜单看得见、点进去 403」或反过来的漂移。
 */
declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** 免登录（登录页、公开问答页、404） */
    public?: boolean
    /** 访问本路由所需的权限点；不声明 = 登录即可访问 */
    permission?: PermissionCode
  }
}

/**
 * 侧栏导航 —— 全平台唯一一套。
 *
 * 分组让「日常运营」与「管理」自解释：原项目把 30+ 个菜单平铺一列，
 * 用户得靠记忆而不是结构去找功能。
 */
export interface NavItem {
  path: string
  label: string
  icon: string
}

export interface NavGroup {
  title: string
  items: NavItem[]
}

export const navGroups: NavGroup[] = [
  {
    title: '问答与知识',
    items: [
      { path: '/conversation', label: '问答', icon: 'ChatDotRound' },
      { path: '/knowledge', label: '知识库', icon: 'Collection' },
      { path: '/assistants', label: '应用管理', icon: 'Grid' },
      { path: '/assistants/prompts', label: '提示词', icon: 'EditPen' }
    ]
  },
  {
    title: '配置与运营',
    items: [
      { path: '/models', label: '模型管理', icon: 'Cpu' },
      { path: '/analytics', label: '运营看板', icon: 'TrendCharts' },
      { path: '/feedback', label: '反馈处理', icon: 'ChatLineSquare' }
    ]
  },
  {
    title: '管理',
    items: [{ path: '/admin', label: '系统管理', icon: 'Setting' }]
  }
]

/**
 * 页面路由自动发现。
 *
 * 每个 `src/pages/<模块>/route.ts` 默认导出一组子路由，这里是唯一的汇总点 ——
 * 新增模块只加文件，不改本文件。这样多个模块可以并行开发而不互相踩同一个注册表。
 */
const discovered = import.meta.glob<{ default: RouteRecordRaw[] }>('../pages/*/route.ts', {
  eager: true
})

const pageRoutes: RouteRecordRaw[] = Object.entries(discovered).flatMap(
  ([, mod]) => mod.default ?? []
)

/**
 * 沉浸布局路由：不挂在 shell 下（无侧栏）。
 *
 * 自动发现只能拿到各模块 route.ts 的 **default** 导出，也就是 shell 子路由；
 * 沉浸页必须在这里显式合并 —— 模块自己挂不上顶层，只能导出、由本文件接纳。
 */
import { immersiveRoutes } from '@/pages/conversation/route'

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/pages/login/Index.vue'),
    meta: { public: true, title: '登录' }
  },
  ...immersiveRoutes,
  {
    path: '/',
    component: () => import('@/pages/_shell/Index.vue'),
    children: [
      {
        path: '',
        name: 'overview',
        component: () => import('@/pages/overview/Index.vue'),
        meta: { title: '迁移总览' }
      },
      ...pageRoutes
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/pages/not-found/Index.vue'),
    meta: { public: true, title: '页面不存在' }
  }
]
