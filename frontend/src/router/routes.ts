import type { RouteRecordRaw } from 'vue-router'
import type { DataScope } from '@/api/types'
import type { PermissionCode } from '@/constants/permissions'

/**
 * 路由元信息的类型声明。
 *
 * `permission` 是**路由级门禁**的唯一声明点：守卫拦不拦、侧栏显不显，都只读它。
 * 不给 `NavItem` 另加一个权限字段 —— 那样同一个权限就有两处声明，
 * 迟早会出现「菜单看得见、点进去 403」或反过来的漂移。
 *
 * `scopeForms` 是**数据范围**那一维，与 `permission` 同源同上口径（07 §5/§6）：
 * 声明「本模块在哪些数据范围下**有页面形态**」，未声明 = 任何范围都有。
 * 它过滤的同样是**入口**，不替代后端的数据可见性判定（07 §3.3 第 2 条）。
 */
declare module 'vue-router' {
  interface RouteMeta {
    title?: string
    /** 免登录（登录页、公开问答页、404） */
    public?: boolean
    /** 访问本路由所需的权限点；不声明 = 登录即可访问 */
    permission?: PermissionCode
    /**
     * 本路由在哪些数据范围下**有形态**（07 §五）。不声明 = 任何范围都有。
     *
     * 为什么不是 `minScope: DataScope`：三档是**集合包含**关系（own ⊂ shared ⊂ all），
     * 而这里要回答的是「这个范围的人，这个页面长什么样」——
     * 知识库对 `own` 与 `all` 有形态，对纯消费者 `shared` 的形态是「不出现」，
     * 一句「最低 own」按包含关系读就是「人人都进」，正好读反。
     * 数组把判据写直白：`['own', 'all']` = 有自己资源的角色与全平台角色都进。
     */
    scopeForms?: DataScope[]
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
    title: '对话与知识',
    items: [
      { path: '/conversation', label: '对话', icon: 'ChatDotRound' },
      { path: '/knowledge', label: '知识库', icon: 'Collection' },
      { path: '/market', label: '应用市场', icon: 'Shop' },
      { path: '/assistants', label: '应用管理', icon: 'Grid' }
    ]
  },
  {
    title: '配置与运营',
    items: [
      // 提示词跟着「应用管理」办，但它是配置资产（写应用时才用），不是终端用户内容
      { path: '/assistants/prompts', label: '提示词', icon: 'EditPen' },
      { path: '/models', label: '模型管理', icon: 'Cpu' },
      { path: '/analytics', label: '运营看板', icon: 'TrendCharts' },
      { path: '/feedback', label: '反馈处理', icon: 'ChatLineSquare' }
    ]
  },
  {
    title: '管理',
    items: [
      // 基础数据（密级/文种/提示词类型/库分类）改一次影响全平台的下拉与既有数据的展示，
      // 与「账号与权限」同属管理员那一档，所以并排放在「管理」下
      { path: '/basedata', label: '基础数据', icon: 'Tickets' },
      // ★ 组织机构独立成一个菜单项（用户口径）：它是**数据权限的载体**（共享给部门、按部门筛文档），
      //   与"账号/角色/权限点"不是一件事，也不该再往系统管理里堆 tab
      { path: '/org', label: '组织机构', icon: 'OfficeBuilding' },
      { path: '/admin', label: '系统管理', icon: 'Setting' }
    ]
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
        meta: { title: '工作台' }
      },
      ...pageRoutes,
      {
        // 404 挂在 shell 内 —— 登录用户敲错 URL 时保留顶栏与一切入口（与 403 同理）
        path: ':pathMatch(.*)*',
        name: 'not-found',
        component: () => import('@/pages/not-found/Index.vue'),
        meta: { title: '页面不存在' }
      }
    ]
  },
  {
    // 未登录时的裸 404（无壳）。守卫在未登录命中 not-found 时重定向到这里
    path: '/404',
    name: 'not-found-public',
    component: () => import('@/pages/not-found/Index.vue'),
    meta: { public: true, title: '页面不存在' }
  }
]
