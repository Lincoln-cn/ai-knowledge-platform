<template>
  <el-dialog
    v-model="visible"
    :title="isEdit ? '编辑用户' : '新增用户'"
    width="480px"
    :close-on-click-modal="false"
    @closed="reset"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
      <el-form-item label="账号" prop="username">
        <el-input
          v-model="form.username"
          :disabled="isEdit"
          maxlength="30"
          placeholder="登录账号，字母或数字"
          autocomplete="off"
        />
        <div v-if="isEdit" class="hint micro">账号创建后不可修改</div>
      </el-form-item>

      <el-form-item label="姓名" prop="displayName">
        <el-input v-model="form.displayName" maxlength="30" placeholder="显示名称" />
      </el-form-item>

      <el-form-item label="角色" prop="roleId">
        <el-select
          v-model="form.roleId"
          placeholder="选择角色"
          style="width: 100%"
          :disabled="isSelf"
        >
          <el-option v-for="r in roles" :key="r.id" :label="r.name" :value="r.id">
            <span>{{ r.name }}</span>
            <span class="opt-code">{{ r.code }}</span>
          </el-option>
        </el-select>
        <div v-if="isSelf" class="hint micro">不能修改自己的角色 —— 请让另一位管理员操作，避免自锁</div>
      </el-form-item>

      <el-form-item :label="isEdit ? '重置密码' : '初始密码'">
        <el-input
          v-model="form.password"
          type="password"
          show-password
          maxlength="32"
          :placeholder="isEdit ? '留空则不修改' : '留空则默认 123456'"
          autocomplete="new-password"
        />
        <div class="hint micro">
          {{ isEdit ? '仅在需要重置时填写，保存后立即生效' : '建议首次登录后自行修改' }}
        </div>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button size="small" @click="visible = false">取消</el-button>
      <el-button size="small" type="primary" :loading="saving" @click="submit">
        {{ isEdit ? '保存' : '确定' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { identityApi } from '@/api'
import { ApiError } from '@/api/types'
import type { Role, User, UpsertUserBody } from '@/api/identity'
import { useSessionStore } from '@/stores/session'

const props = defineProps<{
  user: User | null
  roles: Role[]
}>()
const emit = defineEmits<{ saved: [] }>()

const session = useSessionStore()
const visible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const isEdit = computed(() => props.user !== null)

/** 编辑自己时锁住角色选择 —— 改自己的角色 = 把自己踢出管理位（自锁） */
const isSelf = computed(() => props.user !== null && props.user.id === session.user?.id)

const form = reactive({
  username: '',
  displayName: '',
  roleId: '',
  password: ''
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入账号', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_]{2,30}$/, message: '账号为 2–30 位字母、数字或下划线', trigger: 'blur' }
  ],
  displayName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  roleId: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

const open = () => {
  visible.value = true
  if (props.user) {
    form.username = props.user.username
    form.displayName = props.user.displayName
    form.roleId = props.user.roleId
    form.password = ''
  }
}

const reset = () => {
  formRef.value?.clearValidate()
  form.username = ''
  form.displayName = ''
  form.roleId = ''
  form.password = ''
}

const submit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const body: UpsertUserBody = {
    username: form.username,
    displayName: form.displayName,
    roleId: form.roleId,
    password: form.password || undefined
  }

  saving.value = true
  try {
    if (isEdit.value && props.user) {
      await identityApi.updateUser(props.user.id, body)
      ElMessage.success('已保存')
    } else {
      await identityApi.createUser(body)
      ElMessage.success(form.password ? '已创建' : '已创建，初始密码 123456')
    }
    visible.value = false
    emit('saved')
  } catch (error) {
    // 失败原因直接来自接口（如「账号已存在」），界面不自己编话术
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

watch(
  () => props.user,
  () => {
    if (visible.value) open()
  }
)

defineExpose({ open })
</script>

<style scoped>
.hint {
  color: var(--color-muted);
  line-height: var(--leading-body);
  margin-left: 0;
}

.opt-code {
  float: right;
  color: var(--color-muted);
  font-size: var(--text-2xs);
  margin-left: var(--space-sm);
  font-family: var(--font-mono);
}
</style>
