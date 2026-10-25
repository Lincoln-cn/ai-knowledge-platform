<template>
  <el-dialog
    v-model="visible"
    :title="kb ? `共享 · ${kb.name}` : '共享知识库'"
    width="560px"
    :close-on-click-modal="false"
    @closed="reset"
  >
    <div v-if="kb" class="body">
      <p class="micro muted">
        共享只改<b>谁看得见</b>，不改库里的文档。受控词表是
        <code>Grant</code>（<code>{ subject, actions }</code>）：
        账号 / 角色二选一，可多选；部门共享的「含下级」由判定侧展开，这里不填。
        <b>全部清空 = 收回共享</b>（库回到仅自己可见）。
      </p>

      <el-form label-width="88px" @submit.prevent>
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
            v-model="roleCodes"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择角色"
            style="width: 100%"
            :loading="loadingSubjects"
            :disabled="saving"
          >
            <!--
              ★ 必须传 `r.code`：共享授权的 `subject.id` 对 `ROLE` 一律是角色 code
              （库列注释、`GrantSubjectAdapter`、知识库契约三处同口径）。
              传 `id` 的症状是静默不命中 —— 共享了，但那个角色的人看不到。
            -->
            <el-option v-for="r in roleOptions" :key="r.id" :label="r.name" :value="r.code" />
          </el-select>
        </el-form-item>
        <p class="micro muted">
          每条授予「查看知识库」（<code>knowledge:view</code>）。收回共享后，已经打开页面的人
          下次请求就会被挡；不是"立刻踢下线"。
        </p>
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
import { identityApi, knowledgeApi } from '@/api'
import { ApiError } from '@/api/types'
import type { Grant, SubjectKind } from '@/api/types'
import type { KnowledgeBase } from '@/api/knowledge'
import type { Role, User } from '@/api/identity'
import { PERM } from '@/constants/permissions'

const props = defineProps<{ kb: KnowledgeBase | null }>()
const emit = defineEmits<{ saved: [] }>()

const visible = ref(false)
const saving = ref(false)
const saveError = ref('')
const userIds = ref<string[]>([])
const roleCodes = ref<string[]>([])
const userOptions = ref<User[]>([])
const roleOptions = ref<Role[]>([])
const loadingSubjects = ref(false)
/** 打开时锁定的目标库 —— 不依赖 props 的刷新时机 */
const target = ref<KnowledgeBase | null>(null)

/** 从当前库的 grants 反填已选主体（kind 与 id 一起比对） */
const restoreFromGrants = (grants: Grant[] | undefined) => {
  userIds.value = []
  roleCodes.value = []
  for (const g of grants ?? []) {
    if (g.subject.kind === 'USER') userIds.value.push(g.subject.id)
    if (g.subject.kind === 'ROLE') roleCodes.value.push(g.subject.id)
  }
}

const open = async (payload?: KnowledgeBase | null) => {
  saveError.value = ''
  // 显式收 payload：调用方刚改完 `current` 就 open()，props 可能还没更新到子组件
  const k = payload ?? props.kb
  if (!k) return
  target.value = k
  restoreFromGrants(k.grants)
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
  const actions = [PERM.knowledgeView]
  const grants: Grant[] = []
  for (const id of userIds.value) {
    grants.push({ subject: { kind: 'USER' as SubjectKind, id }, actions: [...actions] })
  }
  for (const id of roleCodes.value) {
    grants.push({ subject: { kind: 'ROLE' as SubjectKind, id }, actions: [...actions] })
  }
  return grants
}

const save = async () => {
  const k = target.value
  if (!k) return
  saveError.value = ''

  saving.value = true
  try {
    const grants = buildGrants()
    await knowledgeApi.setKnowledgeBaseGrants(k.id, grants)
    ElMessage.success(
      grants.length
        ? `已共享给 ${grants.length} 个主体`
        : '已收回共享：仅自己可见'
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

code {
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
}
</style>
