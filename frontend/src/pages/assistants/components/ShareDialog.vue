<template>
  <el-dialog
    v-model="visible"
    :title="assistant ? `分享 · ${assistant.name}` : '分享应用'"
    width="560px"
    :close-on-click-modal="false"
    @closed="reset"
  >
    <div v-if="assistant" class="body">
      <p class="micro muted">
        分享只改<b>谁看得见</b>，不改应用本身。两档之外还有「仅自己」——
        选它等于<b>撤销分享</b>（可见性回到 private，已有授权一并清空）。
        免登录公开链接是另一套机制，去「概览 · 公开链接与访问凭证」生成。
      </p>

      <el-form label-width="88px" @submit.prevent>
        <el-form-item label="可见范围">
          <el-radio-group v-model="mode" :disabled="saving">
            <el-radio-button value="public">公开</el-radio-button>
            <el-radio-button value="shared">定向</el-radio-button>
            <el-radio-button value="private">仅自己</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <div class="mode-hint micro muted">
          <template v-if="mode === 'public'">
            登录用户里人人可见（<b>不等于免登录</b> —— 免登录走访问凭证）。
          </template>
          <template v-else-if="mode === 'shared'">
            只有下面选中的账号与角色可见。至少选一个主体。
          </template>
          <template v-else>
            撤销分享：可见性回到「仅自己」，当前授权会被清空。
          </template>
        </div>

        <template v-if="mode === 'shared'">
          <el-form-item label="共享账号">
            <el-select
              v-model="userIds"
              multiple
              filterable
              collapse-tags
              collapse-tags-tooltip
              placeholder="选择账号"
              style="width: 100%"
              :loading="loadingSubjects"
              :disabled="saving"
            >
              <el-option
                v-for="u in userOptions"
                :key="u.id"
                :label="`${u.displayName}（${u.username}）`"
                :value="u.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="共享角色">
            <el-select
              v-model="roleIds"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="选择角色"
              style="width: 100%"
              :loading="loadingSubjects"
              :disabled="saving"
            >
              <el-option v-for="r in roleOptions" :key="r.id" :label="r.name" :value="r.id" />
            </el-select>
          </el-form-item>
          <p class="micro muted">
            主体形状是 07 §8.1 的 <code>Grant</code>（<code>{ subject, actions }</code>），
            每条授予「查看应用」（<code>assistant:view</code>）。
          </p>
        </template>
      </el-form>

      <div v-if="saveError" class="notice err">
        <div><b>保存失败：</b>{{ saveError }}</div>
      </div>
    </div>

    <template #footer>
      <el-button size="small" :disabled="saving" @click="visible = false">取消</el-button>
      <el-button size="small" type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { assistantsApi, identityApi } from '@/api'
import { ApiError } from '@/api/types'
import type { Assistant } from '@/api/assistants'
import type { Grant, ResourceVisibility, SubjectKind } from '@/api/types'
import type { Role, User } from '@/api/identity'
import { PERM } from '@/constants/permissions'

const props = defineProps<{ assistant: Assistant | null }>()
const emit = defineEmits<{ saved: [] }>()

const visible = ref(false)
const saving = ref(false)
const saveError = ref('')
const mode = ref<'public' | 'shared' | 'private'>('public')
const userIds = ref<string[]>([])
const roleIds = ref<string[]>([])
const userOptions = ref<User[]>([])
const roleOptions = ref<Role[]>([])
const loadingSubjects = ref(false)
/** 打开时锁定的目标应用 —— 不依赖 props 的刷新时机 */
const target = ref<Assistant | null>(null)

/** 从当前 assistant 的 grants 反填已选主体（kind 与 id 一起比对，部门「3」≠ 角色「3」） */
const restoreFromGrants = (grants: Grant[] | undefined) => {
  userIds.value = []
  roleIds.value = []
  for (const g of grants ?? []) {
    if (g.subject.kind === 'user') userIds.value.push(g.subject.id)
    if (g.subject.kind === 'role') roleIds.value.push(g.subject.id)
  }
}

const open = async (payload?: Assistant | null) => {
  saveError.value = ''
  // 显式收 payload：调用方刚改完 `current` 就 open()，props 可能还没更新到子组件
  const a = payload ?? props.assistant
  if (!a) return
  target.value = a
  mode.value = a.visibility === 'public' ? 'public' : a.visibility === 'shared' ? 'shared' : 'private'
  restoreFromGrants(a.grants)
  visible.value = true
  await loadSubjects()
}

const loadSubjects = async () => {
  if (userOptions.value.length && roleOptions.value.length) return
  loadingSubjects.value = true
  try {
    const [users, roles] = await Promise.all([
      identityApi.listUsers({ size: 200 }),
      identityApi.listRoles()
    ])
    userOptions.value = users.list
    roleOptions.value = roles
  } catch (error) {
    // 失败要说出来 —— 静默空选项会让"定向共享"看起来像没有可选主体
    saveError.value =
      error instanceof ApiError ? error.message : '账号/角色清单加载失败，请关闭后重试'
  } finally {
    loadingSubjects.value = false
  }
}

const buildGrants = (): Grant[] => {
  const actions = [PERM.assistantView]
  const grants: Grant[] = []
  for (const id of userIds.value) {
    grants.push({ subject: { kind: 'user' as SubjectKind, id }, actions: [...actions] })
  }
  for (const id of roleIds.value) {
    grants.push({ subject: { kind: 'role' as SubjectKind, id }, actions: [...actions] })
  }
  return grants
}

const save = async () => {
  const a = target.value
  if (!a) return
  saveError.value = ''

  if (mode.value === 'shared' && !userIds.value.length && !roleIds.value.length) {
    saveError.value = '定向共享至少要选一个账号或角色'
    return
  }

  saving.value = true
  try {
    const grants = mode.value === 'shared' ? buildGrants() : []
    // 先写授权再改可见性：反过来会有一瞬「shared 但 grants 还是旧的」
    await assistantsApi.setAssistantGrants(a.id, grants)
    const visibility: ResourceVisibility = mode.value
    await assistantsApi.setAssistantVisibility(a.id, visibility)
    ElMessage.success(
      visibility === 'public'
        ? '已公开：登录用户里人人可见'
        : visibility === 'shared'
          ? `已定向共享给 ${grants.length} 个主体`
          : '已撤销分享：仅自己可见'
    )
    visible.value = false
    emit('saved')
  } catch (error) {
    saveError.value =
      error instanceof ApiError
        ? `${error.message}${error.hint ? '。' + error.hint : ''}`
        : '保存失败，请稍后重试'
  } finally {
    saving.value = false
  }
}

const reset = () => {
  saveError.value = ''
}

defineExpose({ open })
</script>

<style scoped>
.body {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.mode-hint {
  margin: 0 0 var(--space-xs) 88px;
  line-height: var(--leading-ui);
}

code {
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
}
</style>
