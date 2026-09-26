<template>
  <el-dialog v-model="visible" title="我的账号" width="400px" :close-on-click-modal="true">
    <dl class="account-list">
      <div class="row">
        <dt>账号</dt>
        <dd><code class="mono">{{ session.user?.username || '—' }}</code></dd>
      </div>
      <div class="row">
        <dt>姓名</dt>
        <dd>{{ session.displayName || '—' }}</dd>
      </div>
      <div class="row">
        <dt>角色</dt>
        <dd>
          <el-tag size="small" effect="plain">{{ session.roleName || '—' }}</el-tag>
        </dd>
      </div>
      <div class="row">
        <dt>数据范围</dt>
        <dd>{{ scopeLabel }}</dd>
      </div>
    </dl>

    <template #footer>
      <el-button size="small" @click="goChangePassword">修改密码</el-button>
      <el-button size="small" type="primary" @click="visible = false">知道了</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const visible = ref(false)
const router = useRouter()
const session = useSessionStore()

const SCOPE_LABELS: Record<string, string> = {
  own: '仅自己',
  shared: '自己 + 共享',
  all: '全平台'
}

const scopeLabel = computed(() => SCOPE_LABELS[session.dataScope] ?? session.dataScope)

const goChangePassword = () => {
  visible.value = false
  router.push('/change-password')
}

const open = () => {
  visible.value = true
}

defineExpose({ open })
</script>

<style scoped>
.account-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  margin: 0;
}

.row {
  display: flex;
  align-items: baseline;
  gap: var(--space-sm);
}

.row dt {
  flex-shrink: 0;
  width: 4.5em;
  font-size: var(--text-sm);
  color: var(--color-muted);
  line-height: var(--leading-body);
}

.row dd {
  margin: 0;
  font-size: var(--text-sm);
  color: var(--color-ink);
  line-height: var(--leading-body);
}

code.mono {
  font-family: var(--font-mono);
  background: var(--color-sunken);
  padding: 0 var(--space-2xs);
  border-radius: var(--radius-xs);
}
</style>
