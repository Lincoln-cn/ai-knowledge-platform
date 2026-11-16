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
    meta: { title: '通用问答', permission: PERM.chatSettings }
  },
  {
    path: 'settings/providers',
    name: 'settings-providers',
    component: () => import('./providers/Index.vue'),
    meta: { title: '模型服务商', permission: PERM.modelManage }
  },
  {
    /**
     * 检索引擎运行参数。
     *
     * ★ 2026-11-16 读权限由 `knowledge:view` 收紧到 `platform:retrieval:manage`
     *   （ROADMAP §11.52.9）：原来按"排障时任何人都该看得到一次问答要等多久"给了宽读权限，
     *   但把页面挂进菜单之后，这个取舍的代价暴露了 —— 普通消费者顶栏里多出一个
     *   **他不能改也不该关心**的旋钮。排障要看的是读数（运营看板 / 审计），不是旋钮。
     */
    path: 'settings/retrieval',
    name: 'settings-retrieval',
    component: () => import('./retrieval/Index.vue'),
    meta: { title: '检索引擎', permission: PERM.retrievalManage }
  },
  {
    /**
     * 长期记忆。
     *
     * ★ 2026-11-16 读权限由 `conversation:view` 收紧到 `conversation:settings`
     *   （ROADMAP §11.52.9）。原来的理由是"记忆里存的是用户自己说过的话，他看自己的记忆理所当然"，
     *   但那要求这页**长成"我的记忆"**；它今天长成的是**全平台四层记忆的管理页**
     *   （USER / CONVERSATION / PERSONA / CROSS_SESSION），普通用户进来看到的是别人的记忆与平台级规则。
     *   ⇒ 拆页才治本，"我的记忆"页另立；在那之前只有管理员进来。
     */
    path: 'settings/memory',
    name: 'settings-memory',
    component: () => import('./memory/Index.vue'),
    meta: { title: '长期记忆', permission: PERM.chatSettings }
  }
] satisfies RouteRecordRaw[]
