<template>
  <div class="role-manage" v-loading="loading">
    <div class="layout">
      <!-- 左：角色列表 -->
      <div class="role-list panel">
        <div class="list-hd">
          <span>角色</span>
          <span class="micro muted">{{ roles.length }} 个</span>
        </div>
        <button
          v-for="r in roles"
          :key="r.id"
          class="role-item"
          :class="{ on: selected?.id === r.id }"
          @click="select(r)"
        >
          <div class="ri-main">
            <div class="ri-name">
              {{ r.name }}
              <el-tag v-if="r.code === 'ADMIN'" size="small" effect="plain" type="warning">内置</el-tag>
            </div>
            <div class="ri-meta micro muted">
              <code>{{ r.code }}</code>
              · {{ r.memberCount }} 名成员
              · {{ r.permissions.length }} 项权限
            </div>
          </div>
        </button>
      </div>

      <!-- 右：权限点分组勾选 -->
      <div class="perm-editor panel">
        <template v-if="selected">
          <div class="editor-hd">
            <div>
              <div class="ed-title">{{ selected.name }}</div>
              <div class="micro muted">
                编码 <code>{{ selected.code }}</code>
                · 已勾选 {{ checked.length }} / {{ permissions.length }}
              </div>
            </div>
            <div class="ed-actions">
              <template v-if="isAdminRole">
                <span class="micro muted">管理员权限不可裁剪</span>
              </template>
              <template v-else>
                <el-button size="small" :disabled="!dirty" @click="revert">还原</el-button>
                <el-button size="small" type="primary" :loading="saving" :disabled="!dirty" @click="save">
                  保存权限
                </el-button>
              </template>
            </div>
          </div>

          <div v-if="isAdminRole" class="notice warn">
            管理员角色固定拥有全部权限点，不可裁剪 —— 避免把唯一的管理员锁在门外。
          </div>

          <div class="perm-groups">
            <div v-for="group in groups" :key="group.module" class="perm-group">
              <div class="pg-hd">
                <el-checkbox
                  :model-value="isModuleAll(group)"
                  :indeterminate="isModuleSome(group)"
                  :disabled="isAdminRole"
                  @change="(v: boolean | string | number) => toggleModule(group, Boolean(v))"
                >
                  <b>{{ group.module }}</b>
                </el-checkbox>
                <span class="micro muted">{{ group.items.length }} 项</span>
              </div>
              <div class="pg-body">
                <el-checkbox-group v-model="checked" :disabled="isAdminRole">
                  <el-checkbox v-for="p in group.items" :key="p.code" :value="p.code" class="perm-item">
                    <span class="pi-label">{{ p.label }}</span>
                    <code class="pi-code micro">{{ p.code }}</code>
                  </el-checkbox>
                </el-checkbox-group>
              </div>
            </div>
          </div>
        </template>

        <div v-else class="empty micro muted">在左侧选择一个角色，配置它的权限点</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { identityApi } from '@/api'
import { ApiError } from '@/api/types'
import type { PermissionItem, Role } from '@/api/identity'

interface PermGroup {
  module: string
  items: PermissionItem[]
}

const roles = ref<Role[]>([])
const permissions = ref<PermissionItem[]>([])
const selected = ref<Role | null>(null)
const checked = ref<string[]>([])
const loading = ref(false)
const saving = ref(false)

const isAdminRole = computed(() => selected.value?.code === 'ADMIN')

/** 按 module 字段分组，保持清单里首次出现的模块顺序 */
const groups = computed<PermGroup[]>(() => {
  const map = new Map<string, PermissionItem[]>()
  for (const p of permissions.value) {
    const list = map.get(p.module) ?? []
    list.push(p)
    map.set(p.module, list)
  }
  return [...map.entries()].map(([module, items]) => ({ module, items }))
})

const dirty = computed(() => {
  if (!selected.value) return false
  const origin = [...selected.value.permissions].sort()
  const now = [...checked.value].sort()
  return origin.length !== now.length || origin.some((code, i) => code !== now[i])
})

const isModuleAll = (group: PermGroup) =>
  group.items.length > 0 && group.items.every((p) => checked.value.includes(p.code))

const isModuleSome = (group: PermGroup) => {
  const n = group.items.filter((p) => checked.value.includes(p.code)).length
  return n > 0 && n < group.items.length
}

const toggleModule = (group: PermGroup, on: boolean) => {
  const codes = group.items.map((p) => p.code)
  if (on) {
    checked.value = [...new Set([...checked.value, ...codes])]
  } else {
    checked.value = checked.value.filter((c) => !codes.includes(c))
  }
}

const select = (role: Role) => {
  selected.value = role
  checked.value = [...role.permissions]
}

const revert = () => {
  if (selected.value) checked.value = [...selected.value.permissions]
}

const save = async () => {
  if (!selected.value) return
  saving.value = true
  try {
    const updated = await identityApi.updateRolePermissions(selected.value.id, [...checked.value])
    const idx = roles.value.findIndex((r) => r.id === updated.id)
    if (idx >= 0) roles.value[idx] = updated
    selected.value = updated
    checked.value = [...updated.permissions]
    ElMessage.success(`已保存「${updated.name}」的权限`)
  } catch (error) {
    // 如「管理员角色权限不可裁剪」—— 原文展示
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  loading.value = true
  try {
    const [roleList, permList] = await Promise.all([
      identityApi.listRoles(),
      identityApi.listPermissions()
    ])
    roles.value = roleList
    permissions.value = permList
    if (roleList.length) select(roleList[0])
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.role-manage {
  min-height: 320px;
}

.layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: var(--space-sm);
  align-items: start;
}

.role-list {
  overflow: hidden;
}

.list-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-sm) var(--space-md);
  border-bottom: 1px solid var(--color-rule-2);
  font-size: var(--text-xs);
  font-weight: 600;
}

.role-item {
  display: block;
  width: 100%;
  padding: var(--space-sm) var(--space-md);
  background: transparent;
  border: none;
  border-bottom: 1px solid var(--color-rule-2);
  cursor: pointer;
  font-family: inherit;
  text-align: left;
  transition: background var(--dur-micro) var(--ease-out);
}

.role-item:hover {
  background: var(--color-sunken);
}

.role-item.on {
  background: var(--color-accent-soft);
  box-shadow: inset 2px 0 0 var(--color-accent);
}

.role-item.on .ri-name {
  color: var(--color-ink);
}

.ri-name {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
  font-size: var(--text-xs);
  font-weight: 600;
}

.ri-meta {
  margin-top: var(--space-3xs);
}

.perm-editor {
  padding: var(--space-md);
  min-height: 320px;
}

.editor-hd {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
  margin-bottom: var(--space-sm);
}

.ed-title {
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--color-ink);
}

.ed-actions {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-shrink: 0;
}

.perm-groups {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
}

.perm-group {
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  overflow: hidden;
}

.pg-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-xs) var(--space-sm);
  background: var(--color-sunken);
  border-bottom: 1px solid var(--color-rule-2);
}

.pg-body {
  padding: var(--space-xs) var(--space-sm);
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: var(--space-2xs) var(--space-sm);
}

.perm-item {
  display: flex;
  align-items: baseline;
  gap: var(--space-2xs);
  margin-right: 0;
}

.pi-label {
  font-size: var(--text-xs);
}

.pi-code {
  color: var(--color-muted);
}

.empty {
  padding: var(--space-2xl) var(--space-md);
  text-align: center;
}
</style>
