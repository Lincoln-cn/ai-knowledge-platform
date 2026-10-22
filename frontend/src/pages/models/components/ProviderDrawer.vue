<template>
  <el-drawer
    v-model="visible"
    title="模型服务商"
    size="760px"
    :close-on-click-modal="false"
    @closed="reset"
  >
    <div class="provider-page">
      <div class="notice info">
        服务商是<b>模型从哪来</b>的唯一登记处。密钥只写不回显；停用一个服务商后，引用它的模型不会被删除，
        但<b>加载会失败并给出原因</b>（不会静默回退到默认上游）。
      </div>

      <!-- 一键绑定 JAiRouter：本机网关，地址与密钥都留在服务端，前端只发一次 POST -->
      <section class="quick panel">
        <div class="quick-hd">
          <div>
            <div class="quick-title">一键绑定 JAiRouter</div>
            <p class="micro muted">
              本机网关默认 <code>http://localhost:8080/v1</code>。绑定只是登记地址并探活；
              之后在「添加模型」里选它即可。
            </p>
          </div>
          <el-tag size="small" effect="plain" type="info">GATEWAY</el-tag>
        </div>
        <div class="quick-row">
          <el-input
            v-model="quick.apiBase"
            size="small"
            placeholder="http://localhost:8080/v1（留空用默认）"
            clearable
          />
          <el-input
            v-model="quick.apiKey"
            size="small"
            type="password"
            show-password
            placeholder="网关密钥（本地未校验可留空）"
            clearable
          />
          <el-button size="small" type="primary" :loading="quickBusy" @click="quickBind">
            绑定并探活
          </el-button>
        </div>
        <p class="micro muted quick-hint">
          探活失败不会创建服务商；地址重复时直接复用已有记录（幂等）。
        </p>
      </section>

      <div class="list-hd">
        <span class="list-title">已登记服务商 <span class="micro muted">（{{ list.length }}）</span></span>
        <div class="list-actions">
          <el-button size="small" :icon="Refresh" :loading="loading" @click="fetchList">刷新</el-button>
          <el-button size="small" type="primary" @click="openCreate">添加服务商</el-button>
        </div>
      </div>

      <div v-loading="loading" class="providers">
        <div v-for="p in list" :key="p.id" class="provider-card" :class="{ off: !p.enabled }">
          <div class="pc-head">
            <span class="pc-name ellipsis">{{ p.name }}</span>
            <el-tag size="small" effect="plain">{{ KIND_LABEL[p.kind] }}</el-tag>
            <el-tag size="small" effect="plain" :type="p.egress === 'LOCAL' ? 'success' : 'warning'">
              {{ p.egress === 'LOCAL' ? '不出网' : '出网' }}
            </el-tag>
            <el-tag v-if="!p.enabled" size="small" type="info" effect="dark">已停用</el-tag>
            <span class="grow" />
            <span class="micro muted">{{ p.modelCount }} 个模型</span>
          </div>

          <dl class="pc-facts micro">
            <div>
              <dt>地址</dt>
              <dd class="ellipsis" :title="p.apiBase"><code>{{ p.apiBase || '（默认上游）' }}</code></dd>
            </div>
            <div>
              <dt>认证</dt>
              <dd>{{ AUTH_LABEL[p.authStyle] }}</dd>
            </div>
            <div>
              <dt>密钥</dt>
              <dd>{{ p.hasApiKey ? '已配置（不回显）' : '未配置' }}</dd>
            </div>
            <div v-if="p.remark">
              <dt>备注</dt>
              <dd class="ellipsis">{{ p.remark }}</dd>
            </div>
          </dl>

          <div class="pc-actions">
            <el-button link size="small" type="primary" :loading="testingId === p.id" @click="test(p)">
              测试连接
            </el-button>
            <el-button link size="small" @click="openEdit(p)">编辑</el-button>
            <el-button link size="small" :type="p.enabled ? 'warning' : 'success'" @click="toggle(p)">
              {{ p.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button link size="small" type="danger" :disabled="p.modelCount > 0" @click="remove(p)">
              删除
            </el-button>
            <span v-if="p.modelCount > 0" class="micro muted">先解绑 {{ p.modelCount }} 个模型</span>
          </div>
        </div>

        <div v-if="!loading && !list.length" class="empty panel">
          <div class="empty-title">还没有服务商</div>
          <hr class="rule-2" />
          <p class="micro muted">先一键绑定本机 JAiRouter，或手动添加一个外部 API 服务商。</p>
        </div>
      </div>
    </div>

    <!-- 添加 / 编辑 -->
    <el-dialog
      v-model="editorVisible"
      :title="editing ? '编辑服务商' : '添加服务商'"
      width="540px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-form ref="editorRef" :model="editor" :rules="editorRules" label-width="88px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="editor.name" maxlength="40" show-word-limit placeholder="例如 生产 DeepSeek" />
        </el-form-item>
        <el-form-item label="类型" prop="kind">
          <el-select v-model="editor.kind" style="width: 100%" @change="onKindChange">
            <el-option v-for="k in KIND_VALUES" :key="k" :label="KIND_LABEL[k]" :value="k" />
          </el-select>
          <div class="hint micro">{{ kindHint }}</div>
        </el-form-item>
        <el-form-item label="服务地址" prop="apiBase">
          <el-input v-model="editor.apiBase" clearable placeholder="https://api.deepseek.com/v1" />
          <div class="hint micro">填到 <code>/v1</code> 这一层；目录与推理都从这里拼。</div>
        </el-form-item>
        <el-form-item label="认证方式" prop="authStyle">
          <el-select v-model="editor.authStyle" style="width: 100%">
            <el-option v-for="a in AUTH_VALUES" :key="a" :label="AUTH_LABEL[a]" :value="a" />
          </el-select>
          <div class="hint micro">JAiRouter 用 <code>X-API-Key</code>；OpenAI 兼容用 <code>Bearer</code>。</div>
        </el-form-item>
        <el-form-item label="是否出网" prop="egress">
          <el-radio-group v-model="editor.egress">
            <el-radio-button value="LOCAL">不出网（本机/内网）</el-radio-button>
            <el-radio-button value="EXTERNAL">出网（第三方）</el-radio-button>
          </el-radio-group>
          <div class="hint micro">
            不是看厂家名，而是看数据是否离开本机。自建 vLLM 也可以用 OpenAI 协议，但它是 LOCAL。
          </div>
        </el-form-item>
        <el-form-item label="密钥">
          <el-input
            v-model="editor.apiKey"
            type="password"
            show-password
            clearable
            :placeholder="editing?.hasApiKey ? '留空则不修改已有密钥' : '可留空（本地网关未校验时）'"
          />
          <div class="hint micro">只写不回显；保存后不再展示。</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editor.remark" type="textarea" :rows="2" maxlength="100" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="editorVisible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { providersApi } from '@/api'
import type {
  AuthStyle,
  ModelProviderInfo,
  ProviderEgress,
  ProviderKind,
  UpsertProviderBody
} from '@/api/providers'
import { ApiError } from '@/api/types'

const emit = defineEmits<{ changed: [] }>()

const visible = ref(false)
const loading = ref(false)
const list = ref<ModelProviderInfo[]>([])
const testingId = ref('')

const quick = reactive({ apiBase: '', apiKey: '' })
const quickBusy = ref(false)

const KIND_LABEL: Record<ProviderKind, string> = {
  OPENAI: 'OpenAI',
  DEEPSEEK: 'DeepSeek',
  MIMO: 'MiMo',
  GLM: '智谱 GLM',
  OLLAMA: 'Ollama',
  GATEWAY: '网关',
  OPENAI_COMPATIBLE: 'OpenAI 兼容'
}
const KIND_VALUES = Object.keys(KIND_LABEL) as ProviderKind[]
const AUTH_LABEL: Record<AuthStyle, string> = {
  BEARER: 'Authorization: Bearer',
  X_API_KEY: 'X-API-Key',
  NONE: '不认证'
}
const AUTH_VALUES = Object.keys(AUTH_LABEL) as AuthStyle[]

const kindHint = computed(() => {
  switch (editor.kind) {
    case 'OLLAMA':
      return 'Ollama 默认 http://localhost:11434/v1，不出网。'
    case 'GATEWAY':
      return 'JAiRouter 等网关：认证通常是 X-API-Key；是否出网看网关后面的真实上游。'
    case 'DEEPSEEK':
      return '默认 https://api.deepseek.com/v1。'
    case 'MIMO':
      return 'MiMo 的 OpenAI 兼容地址。'
    case 'GLM':
      return '智谱开放平台 OpenAI 兼容地址。'
    default:
      return 'OpenAI 或任何 OpenAI 兼容服务。'
  }
})

const editorVisible = ref(false)
const saving = ref(false)
const editing = ref<ModelProviderInfo | null>(null)
const editorRef = ref<FormInstance>()
const editor = reactive({
  name: '',
  kind: 'OPENAI' as ProviderKind,
  apiBase: '',
  authStyle: 'BEARER' as AuthStyle,
  egress: 'EXTERNAL' as ProviderEgress,
  apiKey: '',
  remark: ''
})

const editorRules: FormRules = {
  name: [{ required: true, message: '请输入服务商名称', trigger: 'blur' }],
  kind: [{ required: true, message: '请选择类型', trigger: 'change' }],
  authStyle: [{ required: true, message: '请选择认证方式', trigger: 'change' }],
  egress: [{ required: true, message: '请选择是否出网', trigger: 'change' }]
}

const open = async () => {
  visible.value = true
  await fetchList()
}

const fetchList = async () => {
  loading.value = true
  try {
    list.value = await providersApi.listProviders()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '服务商列表加载失败')
  } finally {
    loading.value = false
  }
}

const reset = () => {
  quick.apiBase = ''
  quick.apiKey = ''
  editorVisible.value = false
  editing.value = null
}

const openCreate = () => {
  editing.value = null
  Object.assign(editor, {
    name: '',
    kind: 'OPENAI' as ProviderKind,
    apiBase: '',
    authStyle: 'BEARER' as AuthStyle,
    egress: 'EXTERNAL' as ProviderEgress,
    apiKey: '',
    remark: ''
  })
  editorVisible.value = true
}

const openEdit = (p: ModelProviderInfo) => {
  editing.value = p
  Object.assign(editor, {
    name: p.name,
    kind: p.kind,
    apiBase: p.apiBase,
    authStyle: p.authStyle,
    egress: p.egress,
    apiKey: '',
    remark: p.remark ?? ''
  })
  editorVisible.value = true
}

const onKindChange = () => {
  const defaults: Record<ProviderKind, { apiBase: string; authStyle: AuthStyle; egress: ProviderEgress }> = {
    OPENAI: { apiBase: 'https://api.openai.com/v1', authStyle: 'BEARER', egress: 'EXTERNAL' },
    DEEPSEEK: { apiBase: 'https://api.deepseek.com/v1', authStyle: 'BEARER', egress: 'EXTERNAL' },
    MIMO: { apiBase: '', authStyle: 'BEARER', egress: 'EXTERNAL' },
    GLM: { apiBase: 'https://open.bigmodel.cn/api/paas/v4', authStyle: 'BEARER', egress: 'EXTERNAL' },
    OLLAMA: { apiBase: 'http://localhost:11434/v1', authStyle: 'NONE', egress: 'LOCAL' },
    GATEWAY: { apiBase: 'http://localhost:8080/v1', authStyle: 'X_API_KEY', egress: 'EXTERNAL' },
    OPENAI_COMPATIBLE: { apiBase: '', authStyle: 'BEARER', egress: 'EXTERNAL' }
  }
  const d = defaults[editor.kind]
  // 用户已经手填过地址时不要覆盖 —— 只补空值
  if (!editor.apiBase.trim() || editor.apiBase === defaults[editing.value?.kind ?? 'OPENAI'].apiBase) {
    editor.apiBase = d.apiBase
  }
  editor.authStyle = d.authStyle
  editor.egress = d.egress
}

const save = async () => {
  const valid = await editorRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    const body: UpsertProviderBody = {
      name: editor.name.trim(),
      kind: editor.kind,
      apiBase: editor.apiBase.trim(),
      authStyle: editor.authStyle,
      egress: editor.egress,
      remark: editor.remark,
      ...(editor.apiKey ? { apiKey: editor.apiKey } : {})
    }
    if (editing.value) {
      await providersApi.updateProvider(editing.value.id, body)
      ElMessage.success('已保存')
    } else {
      await providersApi.createProvider(body)
      ElMessage.success('已添加')
    }
    editorVisible.value = false
    await fetchList()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

const test = async (p: ModelProviderInfo) => {
  testingId.value = p.id
  try {
    const result = await providersApi.testProvider(p.id)
    if (result.ok) {
      ElMessage.success(
        `连接正常：${result.modelCount} 个模型，${result.elapsedMs}ms` +
          (result.sampleModels.length ? `（${result.sampleModels.slice(0, 3).join('、')}…）` : '')
      )
    } else {
      ElMessage.error(`连接失败：${result.error || '未知原因'}`)
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '测试失败')
  } finally {
    testingId.value = ''
  }
}

const toggle = async (p: ModelProviderInfo) => {
  try {
    await providersApi.setProviderEnabled(p.id, !p.enabled)
    await fetchList()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

const remove = async (p: ModelProviderInfo) => {
  try {
    await ElMessageBox.confirm(
      `删除服务商「${p.name}」？它没有被任何模型引用才能删除。`,
      '删除服务商',
      { type: 'warning' }
    )
    await providersApi.deleteProvider(p.id)
    ElMessage.success('已删除')
    await fetchList()
    emit('changed')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
    }
  }
}

const quickBind = async () => {
  quickBusy.value = true
  try {
    const result = await providersApi.quickBindJairouter({
      ...(quick.apiBase.trim() ? { apiBase: quick.apiBase.trim() } : {}),
      ...(quick.apiKey ? { apiKey: quick.apiKey } : {})
    })
    ElMessage.success(
      result.created
        ? `已绑定 ${result.provider.name}：${result.modelCount} 个模型`
        : `已存在的 ${result.provider.name}：${result.modelCount} 个模型`
    )
    quick.apiKey = ''
    await fetchList()
    emit('changed')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '绑定失败')
  } finally {
    quickBusy.value = false
  }
}

defineExpose({ open, fetchList })
</script>

<style scoped>
.provider-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.quick {
  padding: var(--space-sm) var(--space-md);
}

.quick-hd {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
}

.quick-title {
  font-weight: 600;
  font-size: var(--text-sm);
}

.quick-row {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(0, 1fr) auto;
  gap: var(--space-xs);
  margin-top: var(--space-sm);
}

.quick-hint {
  margin-top: var(--space-xs);
}

.list-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
}

.list-title {
  font-weight: 600;
  font-size: var(--text-sm);
}

.providers {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  min-height: 120px;
}

.provider-card {
  border: 1px solid var(--color-rule);
  border-radius: var(--radius);
  padding: var(--space-sm) var(--space-md);
  background: var(--color-surface);
}

.provider-card.off {
  background: var(--color-sunken);
}

.pc-head {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
}

.pc-name {
  font-weight: 600;
  font-size: var(--text-sm);
  max-width: 260px;
}

.grow {
  flex: 1;
}

.pc-facts {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2xs) var(--space-md);
  margin: var(--space-xs) 0;
}

.pc-facts div {
  display: flex;
  gap: var(--space-xs);
  min-width: 0;
}

.pc-facts dt {
  color: var(--color-muted);
  flex-shrink: 0;
}

.pc-facts dd {
  margin: 0;
  min-width: 0;
}

.pc-actions {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-wrap: wrap;
  border-top: 1px solid var(--color-rule-2);
  padding-top: var(--space-xs);
}

.hint {
  color: var(--color-muted);
  line-height: var(--leading-body);
}

.empty {
  padding: var(--space-lg);
  text-align: center;
}
</style>
