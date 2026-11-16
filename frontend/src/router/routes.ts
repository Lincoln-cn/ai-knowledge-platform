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
 * 顶栏导航 —— 全平台唯一一套（`_shell/Index.vue` 把它渲染成「组按钮 + 下拉」）。
 *
 * ## 分组判据（ROADMAP §11.52.3）
 *
 * **判据 A（逻辑聚合）**：同一组的所有项，必须能被**一句不含「以及」的话**概括。
 * 做不到就说明这一组是拿几个不同的话题硬拼的 —— 用户当然分不清。
 * 反例（2026-11-16 之前的「配置与运营」）= 「模型怎么配**以及**运营怎么看」；
 * 旧「管理」= 「账号、字典、各种设置**以及**只读事实表」。这两组都是"以及"拼出来的。
 *
 * **判据 B（一个对象一个组）**：一个对象只允许一个菜单入口，且必须与它的兄弟同组。
 * 旧布局违反两处：提示词（父路径 `/assistants/*` 却在另一组）、长期记忆（与知识库同一件事的两端）。
 *
 * ## 命名规范（ROADMAP §11.52.4，N1–N6）
 *
 * N1 label 2–4 字（对象用固有词 2–3 字，其余一律 4 字，禁 5 字）
 * N2 纯名词短语；禁「配置/设置」后缀；「管理」只在与兄弟对照时才可用
 * N3 **label 必须与页面 `PageHeader` 的 title 一字不差**（`verify_permissions.py` 有断言）
 * N4 页面 desc 一句话 24–36 字，禁「——」、禁括号、禁指代词、管理页不用第二人称
 * N5 同组图标互不重复
 * N6 desc 只写「管什么 + 一条最易错的约束」，不写设计理由
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
    // 主语：可以被提问的内容资产。
    // 一句概括：用户能找到的东西、能提问的东西、能复用的东西。
    title: '对话与知识',
    items: [
      { path: '/conversation', label: '对话', icon: 'ChatDotRound' },
      { path: '/knowledge', label: '知识库', icon: 'Collection' },
      { path: '/market', label: '应用市场', icon: 'Shop' },
      { path: '/assistants', label: '应用管理', icon: 'Grid' },
      // 应用模板紧挨应用管理：模板是「应用的配置快照」，两者是同一件东西的两个状态
      { path: '/templates', label: '应用模板', icon: 'Files' },
      // 提示词跟着「应用管理」办（父路径就在 /assistants 下），别再拆到别的组
      { path: '/assistants/prompts', label: '提示词', icon: 'EditPen' }
    ]
  },
  {
    // 主语：回答链路上的一段参数或一台机器。
    // 一句概括：一次回答要经过的每个环节，按链路顺序排 ——
    // 数据从哪出去 → 用哪个模型 → 默认链路怎么跑 → 怎么检索 → 记住什么 → 什么不许问。
    title: '模型与检索',
    items: [
      { path: '/settings/providers', label: '模型服务商', icon: 'Connection' },
      { path: '/models', label: '模型', icon: 'Cpu' },
      { path: '/settings/chat', label: '通用问答', icon: 'ChatLineRound' },
      // 检索引擎是"怎么算"，通用问答是"要不要查"：开关归链路、旋钮归引擎（§11.52.3）
      { path: '/settings/retrieval', label: '检索引擎', icon: 'Search' },
      { path: '/settings/memory', label: '长期记忆', icon: 'Notebook' },
      // 安全围栏与检索引擎同级：两者的改动都对全平台所有链路的下一次请求生效，
      // 而「通用问答」那条链路可以整条关掉 —— 爆炸半径是组内排序的依据（§11.53.2）。
      { path: '/settings/guard', label: '安全围栏', icon: 'Lock' }
    ]
  },
  {
    // 主语：谁在里面、谁能看到什么。
    // 一句概括：平台里有哪些人、他们怎么组织、他们能碰到的字典值。
    title: '组织与权限',
    items: [
      // 原名「系统管理」而页面 title 是「账号与权限」—— 菜单名与页面名不一致，
      // 现在统一叫「用户角色」：这页管的就是用户、角色、权限点三件事。
      { path: '/admin', label: '用户角色', icon: 'User' },
      // 组织机构与上面三项不是同类：它是**数据权限的载体**（共享给部门、按部门筛文档）
      { path: '/org', label: '组织机构', icon: 'OfficeBuilding' },
      { path: '/basedata', label: '基础数据', icon: 'Tickets' }
    ]
  },
  {
    // 主语：已经发生过的事，以及对外要登记的事。按时间序排：
    // 正在发生 → 用户说了什么 → 用户投诉了 → 留下了什么 → 对外要登记什么。
    title: '运营与合规',
    items: [
      { path: '/analytics', label: '运营看板', icon: 'TrendCharts' },
      { path: '/feedback', label: '反馈处理', icon: 'ChatLineSquare' },
      { path: '/complaints', label: '投诉举报', icon: 'Warning' },
      // 审计记录是一张**只读的事实表**，与"改配置"不是同一类动作，所以不塞进任何 tab
      { path: '/audit', label: '审计记录', icon: 'Document' },
      { path: '/compliance', label: '合规备案', icon: 'Stamp' }
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
