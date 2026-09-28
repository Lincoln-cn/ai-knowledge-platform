<template>
  <div class="user-manage">
    <div class="filters panel">
      <el-input
        v-model="query.keyword"
        size="small"
        placeholder="搜索账号或姓名"
        style="width: 200px"
        clearable
        @keyup.enter="search"
        @clear="search"
      />
      <el-select v-model="query.roleId" size="small" placeholder="全部角色" style="width: 140px" @change="search">
        <el-option label="全部角色" value="" />
        <el-option v-for="r in roles" :key="r.id" :label="r.name" :value="r.id" />
      </el-select>
      <el-select v-model="query.status" size="small" placeholder="全部状态" style="width: 130px" @change="search">
        <el-option label="全部状态" value="" />
        <el-option v-for="s in STATUSES" :key="s" :label="s" :value="s" />
      </el-select>
      <span class="micro muted">共 {{ total }} 人</span>
      <span class="grow" />
      <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
      <el-button size="small" type="primary" @click="openCreate">新增用户</el-button>
    </div>

    <div class="panel" style="overflow: hidden">
      <el-table v-loading="loading" :data="list" style="width: 100%" empty-text="暂无用户">
        <el-table-column prop="username" label="账号" min-width="120">
          <template #default="{ row }">
            <code class="micro">{{ row.username }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="displayName" label="姓名" min-width="110" />
        <el-table-column prop="roleName" label="角色" min-width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.roleName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="status" :class="STATUS_CLASS[row.status as User['status']]">
              <i class="dot" :class="STATUS_DOT[row.status as User['status']]" />
              {{ labelOf(USER_STATUS_LABELS, row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="最后登录" min-width="140">
          <template #default="{ row }">
            <span class="micro muted">{{ row.lastLoginAt || '从未登录' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" type="primary" @click="openEdit(row)">编辑</el-button>
            <!-- 内置 admin 与当前登录账号不可删：按钮直接不渲染 -->
            <el-button
              v-if="canDelete(row)"
              link size="small" type="danger"
              @click="remove(row)"
            >删除</el-button>
            <span v-else class="micro muted">{{ undeletableHint(row) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        v-model:current-page="query.page"
        :page-size="pageSize"
        layout="total, prev, pager, next"
        :total="total"
        @current-change="onPageChange"
      />
    </div>

    <UserFormDialog ref="formRef" :user="editing" :roles="roles" @saved="onSaved" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { identityApi } from '@/api'
import { ApiError } from '@/api/types'
import type { Role, User } from '@/api/identity'
import { useSessionStore } from '@/stores/session'
import UserFormDialog from './UserFormDialog.vue'
import { USER_STATUS_LABELS, labelOf } from '@/constants/labels'

const STATUSES: Array<User['status']> = ['ACTIVE', 'DISABLED', 'PENDING_REVIEW']

const STATUS_CLASS: Record<User['status'], string> = {
  ACTIVE: 'st-ok',
  DISABLED: 'st-err',
  PENDING_REVIEW: 'st-warn'
}
const STATUS_DOT: Record<User['status'], string> = {
  ACTIVE: 'ok',
  DISABLED: 'err',
  PENDING_REVIEW: 'warn'
}

const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const list = ref<User[]>([])
const roles = ref<Role[]>([])
const total = ref(0)
const loading = ref(false)
const pageSize = 10

/** 筛选/分页入 URL —— 刷新与直链能恢复同一筛选态（一.2） */
const query = reactive({
  keyword: (route.query.keyword as string) ?? '',
  roleId: (route.query.roleId as string) ?? '',
  status: ((route.query.status as string) ?? '') as User['status'] | '',
  page: Number(route.query.page) || 1
})

const syncUrl = () => {
  router.replace({
    query: {
      ...route.query,
      ...(query.keyword ? { keyword: query.keyword } : { keyword: undefined }),
      ...(query.roleId ? { roleId: query.roleId } : { roleId: undefined }),
      ...(query.status ? { status: query.status } : { status: undefined }),
      ...(query.page > 1 ? { page: String(query.page) } : { page: undefined })
    }
  })
}

const fetchList = async () => {
  loading.value = true
  try {
    const result = await identityApi.listUsers({
      keyword: query.keyword || undefined,
      roleId: query.roleId || undefined,
      status: query.status || undefined,
      page: query.page,
      size: pageSize
    })
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

const fetchRoles = async () => {
  roles.value = await identityApi.listRoles()
}

const search = () => {
  query.page = 1
  syncUrl()
  fetchList()
}

const onPageChange = () => {
  syncUrl()
  fetchList()
}

// URL → 筛选/分页：浏览器前进/后退、页内改 hash 时界面跟随。
// 值比较后再赋值，避免与 syncUrl 互写成环；只在真变了时才拉列表。
watch(
  () => [route.query.keyword, route.query.roleId, route.query.status, route.query.page],
  () => {
    const kw = route.query.keyword ? String(route.query.keyword) : ''
    const roleId = route.query.roleId ? String(route.query.roleId) : ''
    const status = (route.query.status ? String(route.query.status) : '') as User['status'] | ''
    const pg = route.query.page ? Number(route.query.page) || 1 : 1
    if (kw === query.keyword && roleId === query.roleId && status === query.status && pg === query.page) return
    query.keyword = kw
    query.roleId = roleId
    query.status = status
    query.page = pg
    fetchList()
  }
)

onMounted(() => {
  fetchList()
  fetchRoles()
})

/* ── 删除边界：内置 admin、当前登录账号 ── */
const isSelf = (u: User) => u.id === session.user?.id
const isBuiltinAdmin = (u: User) => u.username === 'admin'

const canDelete = (u: User) => !isBuiltinAdmin(u) && !isSelf(u)

const undeletableHint = (u: User) =>
  isBuiltinAdmin(u) ? '内置管理员不可删除' : isSelf(u) ? '不能删除自己' : ''

/* ── 操作 ── */
const formRef = ref<InstanceType<typeof UserFormDialog>>()
const editing = ref<User | null>(null)

const openCreate = () => {
  editing.value = null
  formRef.value?.open()
}

const openEdit = (user: User) => {
  editing.value = user
  formRef.value?.open()
}

const onSaved = () => {
  fetchList()
  // 成员数变了，角色筛选项里的 memberCount 也要跟上
  fetchRoles()
}

const remove = async (user: User) => {
  const ok = await ElMessageBox.confirm(
    `删除后该账号将无法登录，此操作不可恢复。确认删除「${user.displayName}（${user.username}）」？`,
    '确认删除用户',
    { type: 'warning', confirmButtonText: '删除' }
  )
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  try {
    await identityApi.deleteUser(user.id)
    ElMessage.success('已删除')
    await fetchList()
    await fetchRoles()
  } catch (error) {
    // 如「内置管理员账号不可删除」「不能删除当前登录的账号」—— 原文展示
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败，请稍后重试')
  }
}
</script>

<style scoped>
.user-manage {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.filters {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
  padding: var(--space-sm) var(--space-md);
}

.status {
  display: inline-flex;
  align-items: center;
  gap: var(--space-3xs);
  font-size: var(--text-2xs);
}

.st-ok {
  color: var(--color-ok);
}

.st-err {
  color: var(--color-danger);
}

.st-warn {
  color: var(--color-warn);
}

.pager {
  display: flex;
  justify-content: flex-end;
}
</style>
