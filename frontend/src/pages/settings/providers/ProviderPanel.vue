<template>
    <div class="provider-page">
      <div class="notice info">
        服务商是<b>模型从哪来</b>的唯一登记处。密钥只写不回显；停用一个服务商后，引用它的模型不会被删除，
        但<b>加载会失败并给出原因</b>（不会静默回退到默认上游）。
      </div>

      <!--
        一键绑定 JAiRouter（`#289` 打磨）：
        ★ 措辞是「网络可达」不是「本机」—— 网关可以跑在另一台机器上，地址填到 /v1 这一层即可。
        ★ 默认**收起**：它只对"有 JAiRouter 网关"的部署有用，不该和列表抢同一份视觉权重。
      -->
      <section class="quick panel">
        <div class="quick-hd" role="button" tabindex="0" @click="quickOpen = !quickOpen" @keydown.enter="quickOpen = !quickOpen">
          <div>
            <div class="quick-title">
              一键绑定 JAiRouter
              <el-tag size="small" effect="plain" type="info" class="quick-tag">GATEWAY</el-tag>
            </div>
            <p class="micro muted">
              地址填到 <code>/v1</code> 这一层，<b>网络可达即可</b>（不要求装在本机）。绑定只是登记地址并探活；
              之后在「添加模型」里选它即可。
            </p>
          </div>
          <el-button link size="small" class="quick-toggle">{{ quickOpen ? '收起' : '展开' }}</el-button>
        </div>
        <template v-if="quickOpen">
          <div class="quick-row">
            <el-input
              v-model="quick.apiBase"
              size="small"
              placeholder="http://网关地址:8080/v1（留空用默认）"
              clearable
            />
            <el-input
              v-model="quick.apiKey"
              size="small"
              type="password"
              show-password
              placeholder="网关密钥（未校验可留空）"
              clearable
            />
            <el-button size="small" type="primary" :loading="quickBusy" @click="quickBind">
              绑定并探活
            </el-button>
          </div>
          <p class="micro muted quick-hint">
            探活失败不会创建服务商；地址重复时直接复用已有记录（幂等）。
          </p>
        </template>
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
            <!-- ★ 数字要能点开（`#289` 打磨）：写「3 个模型」却点不了，等于报了个数不给出处 -->
            <el-button
              v-if="p.modelCount > 0"
              link
              size="small"
              class="micro"
              @click="goProviderModels(p)"
            >
              {{ p.modelCount }} 个模型
            </el-button>
            <span v-else class="micro muted">0 个模型</span>
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
            <!--
              换 / 清密钥走独立端点（`POST` / `DELETE /model-providers/:id/key`）。
              编辑表单里也能改，但那条路要重填整张表；密钥轮换是个单动作。
            -->
            <el-button link size="small" type="primary" @click="changeKey(p)">换密钥</el-button>
            <el-button
              v-if="p.hasApiKey"
              link
              size="small"
              type="danger"
              @click="clearKey(p)"
            >
              清除密钥
            </el-button>
            <el-button link size="small" :type="p.enabled ? 'warning' : 'success'" @click="toggle(p)">
              {{ p.enabled ? '停用' : '启用' }}
            </el-button>
            <!--
              ★ #301：按钮**不再因"有模型"而禁用** —— 禁用等于把"强制删除"这条出路藏起来
              （后端 `ModelProviderService.delete` 一直支持 force）。有关联时点开强制删除确认框：
              写明"将一并删除 N 个模型"，并说明"若这些模型自己还被应用/知识库引用，删除会被拒绝"。
            -->
            <el-button link size="small" type="danger" @click="remove(p)">
              删除
            </el-button>
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

    <!--
      ★ #301：有关联的服务商走"确认后带 force=true 删"这条路。
      与 models/Index.vue 的引用弹框同族（列明细 → 二次确认），差别是服务商删的是"一整串"：
      下面 N 个模型跟着走 —— 弹框必须写明"将一并删除 N 个模型"。
    -->
    <el-dialog
      v-model="removeDialog.visible"
      :title="`删除服务商「${removeDialog.provider?.name ?? ''}」`"
      width="560px"
      append-to-body
      :close-on-click-modal="!removeDialog.deleting"
    >
      <div v-if="removeDialog.loading" class="ref-loading">正在核对引用…</div>
      <template v-else-if="removeDialog.refs">
        <p class="ref-lead">
          将一并删除 <b>{{ removeDialog.refs.models }}</b> 个模型：
          <b>{{ removeDialog.refs.modelNames.join('、') || '（无）' }}</b>
        </p>
        <ul v-if="downstreamCount(removeDialog.refs) > 0" class="ref-list">
          <li v-if="removeDialog.refs.apps.length">
            <span class="ref-key">被这些应用使用</span>
            <span class="ref-val">{{ removeDialog.refs.apps.map((a) => a.name).join('、') }}</span>
          </li>
          <li v-if="removeDialog.refs.knowledgeBases.length">
            <span class="ref-key">被这些知识库使用</span>
            <span class="ref-val">{{ removeDialog.refs.knowledgeBases.map((k) => k.name).join('、') }}</span>
          </li>
          <li v-if="removeDialog.refs.documents > 0">
            <span class="ref-key">涉及文档</span>
            <span class="ref-val">{{ removeDialog.refs.documents.toLocaleString() }} 篇</span>
          </li>
          <li v-if="removeDialog.refs.defaultKeys.length">
            <span class="ref-key">平台默认</span>
            <span class="ref-val">{{ removeDialog.refs.defaultKeys.join('、') }}</span>
          </li>
        </ul>
        <div class="notice err ref-warn">
          <span>
            <b>若这些模型自己还被应用/知识库引用，删除会被拒绝（409）：</b>
            请先把上面这些引用改绑到别的模型，再回来删 —— 改绑不是删除，数据不会丢。
          </span>
        </div>
        <div v-if="removeDialog.error" class="ref-error">{{ removeDialog.error }}</div>
      </template>

      <template #footer>
        <el-button size="small" :disabled="removeDialog.deleting" @click="removeDialog.visible = false">取消</el-button>
        <el-button
          size="small"
          type="danger"
          :loading="removeDialog.deleting"
          :disabled="removeDialog.loading"
          @click="forceRemove"
        >
          我了解后果，连 {{ removeDialog.refs?.models ?? 0 }} 个模型一起强制删除
        </el-button>
      </template>
    </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
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
import type { ModelReference } from '@/api/models'
import { ApiError } from '@/api/types'

const emit = defineEmits<{ changed: [] }>()
const router = useRouter()

const loading = ref(false)
const list = ref<ModelProviderInfo[]>([])
const testingId = ref('')

const quick = reactive({ apiBase: '', apiKey: '' })
// ★ 默认收起：这条只对"有 JAiRouter 网关"的部署有用，不该和列表抢同一份视觉权重。
const quickOpen = ref(false)
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

const changeKey = async (p: ModelProviderInfo) => {
  try {
    const { value } = await ElMessageBox.prompt(
      `给「${p.name}」设置新密钥。保存后旧密钥立即失效；界面不会回显明文。`,
      '换密钥',
      {
        inputType: 'password',
        inputPlaceholder: '粘贴新密钥',
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputValidator: (v: string) => (v && v.trim() ? true : '密钥不能为空')
      }
    )
    await providersApi.setProviderKey(p.id, value.trim())
    ElMessage.success('密钥已更新')
    await fetchList()
    emit('changed')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof ApiError ? error.message : '更新密钥失败')
    }
  }
}

const clearKey = async (p: ModelProviderInfo) => {
  try {
    await ElMessageBox.confirm(
      `清除「${p.name}」的密钥？之后调用会以"无密钥"发出，直到重新配置。`,
      '清除密钥',
      { type: 'warning' }
    )
    await providersApi.clearProviderKey(p.id)
    ElMessage.success('密钥已清除')
    await fetchList()
    emit('changed')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof ApiError ? error.message : '清除密钥失败')
    }
  }
}

// ★ #301：强制删除确认框（有关联的服务商走这里；无模型的走原来的 ElMessageBox）
const removeDialog = reactive({
  visible: false,
  loading: false,
  deleting: false,
  provider: null as ModelProviderInfo | null,
  refs: null as ModelReference | null,
  error: ''
})

/** 弹框里的"下游引用"计数（不含 models：那是它自己下面的模型，不是被谁引用） */
const downstreamCount = (refs: ModelReference): number =>
  refs.apps.length + refs.knowledgeBases.length + refs.documents + refs.defaultKeys.length

const remove = async (p: ModelProviderInfo) => {
  // ★ #301：有关联 ⇒ 弹确认框（写明"将一并删除 N 个模型"），确认后带 force=true 再删。
  //   原来这里是 `:disabled="p.modelCount > 0"` —— 用户看不到"强制删除"这条路径（issue ①）。
  if (p.modelCount > 0) {
    removeDialog.provider = p
    removeDialog.refs = null
    removeDialog.error = ''
    removeDialog.visible = true
    removeDialog.loading = true
    try {
      removeDialog.refs = await providersApi.getProviderReferences(p.id)
    } catch (error) {
      // 读引用失败不堵死删除：真后端的 409 才是最终守卫，这里只提示
      removeDialog.error = error instanceof ApiError ? error.message : '读取引用失败，仍可尝试强制删除'
    } finally {
      removeDialog.loading = false
    }
    return
  }
  try {
    await ElMessageBox.confirm(
      `删除服务商「${p.name}」？`,
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

const forceRemove = async () => {
  const p = removeDialog.provider
  if (!p) return
  removeDialog.deleting = true
  removeDialog.error = ''
  try {
    // ★ 判据①：这里就是 `?force=true` 的发出点（providersApi.deleteProvider(id, true)）
    await providersApi.deleteProvider(p.id, true)
    ElMessage.success(`已删除服务商「${p.name}」及其模型`)
    removeDialog.visible = false
    await fetchList()
    emit('changed')
  } catch (error) {
    // ★ issue 判据 1：有下游引用时 force 仍 409 —— 原因要**留在弹框里**（一闪的 toast 记不住）
    if (error instanceof ApiError) {
      removeDialog.error = error.hint ? `${error.message}（${error.hint}）` : error.message
    } else {
      removeDialog.error = '删除失败'
    }
  } finally {
    removeDialog.deleting = false
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

/** 点「N 个模型」⇒ 去模型页，并把服务商作为筛选条件带上（不是"跳过去自己找"）。 */
const goProviderModels = (p: ModelProviderInfo) => {
  router.push({ path: '/models', query: { providerId: p.id } })
}

defineExpose({ fetchList })

// 页面模式下没有"打开"这个动作：进页面就该看到列表。
onMounted(fetchList)
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
  cursor: pointer;
}

.quick-tag {
  margin-left: var(--space-2xs);
  vertical-align: middle;
}

.quick-toggle {
  flex: none;
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

/* ── 强制删除确认框（#301）── 与 models/Index.vue 的引用弹框同款版式 */
.ref-loading {
  padding: var(--space-md) 0;
  color: var(--text-3);
  font-size: 14px;
}

.ref-lead {
  margin: 0 0 var(--space-sm);
  line-height: 1.6;
}

.ref-list {
  margin: 0 0 var(--space-sm);
  padding: 0;
  list-style: none;
}

.ref-list li {
  display: flex;
  gap: var(--space-sm);
  padding: 6px 0;
  border-bottom: 1px dashed var(--color-rule);
}

.ref-key {
  flex: 0 0 110px;
  color: var(--text-3);
  font-size: 13px;
}

.ref-val {
  min-width: 0;
  flex: 1;
  overflow-wrap: anywhere;
  line-height: 1.6;
}

.ref-warn {
  margin-top: var(--space-sm);
}

.ref-error {
  margin-top: var(--space-xs);
  color: var(--color-danger);
  font-size: var(--text-sm);
}
</style>
