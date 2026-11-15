import type { RouteRecordRaw } from 'vue-router'
import { PERM } from '@/constants/permissions'

/**
 * 「对话设置」（`/settings/chat`）。
 *
 * ★★ 这个文件**必须放在 `pages/settings/` 这一层**，不能放进 `pages/settings/chat/`：
 * 自动发现的 glob 只匹配「pages 下一层目录 + route.ts」，两层目录里的 route.ts **不会被扫到** ——
 * 页面组件仍在 `./chat/Index.vue`，但路由声明只能在第一层。
 *
 * ★ 我第一版把它放在两层目录里，结果是：`vue-tsc` 通过、dev server 也能转译那个模块、
 *   菜单也加了，**但点进去是 404** —— 因为 `pageRoutes` 里根本没有这一条。
 *   「编译通过 / 模块能加载」都**不能**证明「路由注册上了」，这一条要单独验。
 *
 * ★ 还有一个刚踩的坑：这份注释的第一版里写了那个 glob 字面量，其中「星号 + 斜杠」
 *   恰好是**块注释的结束符** ⇒ 注释被提前终止、后半句变成代码 ⇒ esbuild 报
 *   `Expected ";" but found "route"`。写注释提到通配路径时要避开这个组合。
 *
 * ★ 读权限 `conversation:view`：界面要显示「当前生效值」，否则用户遇到「生成超时」
 *   连「现在多少秒」都看不到；真正的写权限（`system:manage`）在页面内按按钮与 API 各自把住。
 */
export default [
  {
    path: 'settings/chat',
    name: 'settings-chat',
    component: () => import('./chat/Index.vue'),
    meta: { title: '对话设置', permission: PERM.chatSettings }
  },
  {
    path: 'settings/providers',
    name: 'settings-providers',
    component: () => import('./providers/Index.vue'),
    meta: { title: '服务商配置', permission: PERM.modelManage }
  },
  {
    /**
     * 检索引擎运行参数（0.8.x 的配置页）。
     *
     * ★ 读权限给 `knowledge:view` 而不是 `retrievalManage`：这几个值决定"一次问答要等多久"，
     * 排障时任何人都该看得到；写权限（`platform:retrieval:manage`）由页面内的按钮与 API 各自把住。
     */
    path: 'settings/retrieval',
    name: 'settings-retrieval',
    component: () => import('./retrieval/Index.vue'),
    meta: { title: '检索引擎', permission: PERM.knowledgeView }
  },
  {
    /**
     * 长期记忆（0.9.0）。
     *
     * ★ 读权限同样给 `conversation:view` 一族里最宽的那个：记忆里存的是"用户自己说过的话"，
     * 他能看到自己的记忆本来就是应有之义；真正的写权限（`conversation:settings`）由页面内的
     * 按钮与 API 各自把住 —— 与「检索引擎」一页的取舍一致。
     */
    path: 'settings/memory',
    name: 'settings-memory',
    component: () => import('./memory/Index.vue'),
    meta: { title: '长期记忆', permission: PERM.conversationView }
  }
] satisfies RouteRecordRaw[]
