<template>
  <div class="page">
    <PageHeader
      title="账号与权限"
      desc="单后台轻量 RBAC：用户 ↔ 角色 ↔ 权限点 ↔ 提示词类型。权限点是代码常量（资源:操作），不是数据库里配的菜单"
    />

    <!-- 权限不足时整块不渲染，而不是渲染后报错 -->
    <template v-if="canManage">
      <div class="notice info">
        权限点在代码里定义、启动时同步。新增功能时清单自动多出一项，只需分配给角色 ——
        不需要有人记得去库里配菜单。提示词类型同理：它是分类法（契约），只在本页维护，
        应用配置人只引用不新建。多租户、数据权限、字典/参数/菜单管理均不在本版范围。
      </div>

      <el-tabs v-model="active" class="admin-tabs">
        <el-tab-pane label="用户管理" name="users" lazy>
          <UserManage />
        </el-tab-pane>
        <el-tab-pane label="角色管理" name="roles" lazy>
          <RoleManage />
        </el-tab-pane>
        <el-tab-pane label="权限点清单" name="permissions" lazy>
          <PermissionList />
        </el-tab-pane>
        <el-tab-pane label="提示词类型" name="promptTypes" lazy>
          <PromptTypeManage />
        </el-tab-pane>
      </el-tabs>
    </template>

    <div v-else class="notice warn">
      当前账号没有「账号与权限」权限（<code>{{ PERM.systemManage }}</code>），本页内容不可见。
      如需管理账号，请联系管理员为你的角色勾选该权限点。
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import { PERM } from '@/constants/permissions'
import { useSessionStore } from '@/stores/session'
import UserManage from './components/UserManage.vue'
import RoleManage from './components/RoleManage.vue'
import PermissionList from './components/PermissionList.vue'
import PromptTypeManage from './components/PromptTypeManage.vue'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const canManage = computed(() => session.can(PERM.systemManage))

const TABS = ['users', 'roles', 'permissions', 'promptTypes'] as const
type TabKey = (typeof TABS)[number]

/** URL query.tab → 合法 Tab；undefined / 非法值 / 非字符串（含数组）一律回落 users */
const tabFromQuery = (raw: unknown): TabKey =>
  typeof raw === 'string' && (TABS as readonly string[]).includes(raw) ? (raw as TabKey) : 'users'

/** query.tab 的规范形态：users 是默认值、不写进 query，故规范值是 undefined */
const canonicalTabInQuery = (next: TabKey): string | undefined =>
  next === 'users' ? undefined : next

const active = ref<TabKey>(tabFromQuery(route.query.tab))

// Tab → URL：页内点选写 query；users 是默认值不写，保持 URL 干净
watch(active, (val) => {
  router.replace({ query: { ...route.query, tab: canonicalTabInQuery(val) } })
})

// URL → Tab：浏览器前进/后退、页内改 hash 时界面跟随（缺的这一向）。
// 不会与上面的 watch(active) 成环：两边都只在「值真的变了」才写对方。
// 本 watch 先比 next === active.value，相同直接 return、不再赋值；
// 赋值后 watch(active) 触发 router.replace 写回的是同一个 tab 值，
// route.query.tab 不变则本 watch 的 getter 不产生新值，不会二次触发。
// immediate 必须开：新挂载 ?tab=bogus 时 active 初值就是 users，
// 若不 immediate 本回调根本不会跑，非法值会一直留在 URL 里。
watch(
  () => route.query.tab,
  (raw) => {
    const next = tabFromQuery(raw)
    const canonical = canonicalTabInQuery(next)
    // URL 归一化：非法值、以及默认值 users 显式写在 query 里（?tab=users），
    // 都要从 query 清掉，否则 URL 与 UI 不一致（刷新会再次回落）。
    // 只在「URL 里的原始值 ≠ 规范值」时才 replace；replace 后 raw 变成规范值，
    // 下一次进来 rawIsCanonical 为真、不再写，不成环。
    const rawIsCanonical =
      (raw === undefined && canonical === undefined) || raw === canonical
    if (next !== active.value) {
      // active 变了 → watch(active) 会写规范值回 URL，这里不重复 replace
      active.value = next
    } else if (!rawIsCanonical) {
      router.replace({ query: { ...route.query, tab: canonical } })
    }
  },
  { immediate: true }
)
</script>

<style scoped>
.admin-tabs :deep(.el-tabs__header) {
  margin-bottom: var(--space-sm);
}
</style>
