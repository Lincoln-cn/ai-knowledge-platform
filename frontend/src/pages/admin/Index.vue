<template>
  <div class="page">
    <PageHeader
      title="账号与权限"
      desc="单后台轻量 RBAC：用户 ↔ 角色 ↔ 权限点。权限点是代码常量（资源:操作），不是数据库里配的菜单"
    />

    <!-- 权限不足时整块不渲染，而不是渲染后报错 -->
    <template v-if="canManage">
      <div class="notice info">
        权限点在代码里定义、启动时同步。新增功能时清单自动多出一项，只需分配给角色 ——
        不需要有人记得去库里配菜单。多租户、数据权限、字典/参数/菜单管理均不在本版范围。
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

const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const canManage = computed(() => session.can(PERM.systemManage))

const TABS = ['users', 'roles', 'permissions'] as const
const active = ref<(typeof TABS)[number]>(
  TABS.includes(route.query.tab as (typeof TABS)[number])
    ? (route.query.tab as (typeof TABS)[number])
    : 'users'
)

watch(active, (val) => {
  router.replace({ query: { ...route.query, tab: val === 'users' ? undefined : val } })
})
</script>

<style scoped>
.admin-tabs :deep(.el-tabs__header) {
  margin-bottom: var(--space-sm);
}
</style>
