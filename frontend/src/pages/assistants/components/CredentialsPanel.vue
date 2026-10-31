<template>
  <div class="cred panel">
    <div class="sec-hd">
      <span class="h4">公开链接与访问凭证</span>
      <span class="hd-ops">
        <el-button size="small" @click="fetch">刷新</el-button>
        <el-button size="small" type="primary" @click="openCreate">生成分享链接</el-button>
      </span>
    </div>

    <p class="micro muted">
      访问凭证用于<b>免登录公开链接</b>（形如 <code>{{ urlSample }}</code>）；与下方 API Key 是两套机制，互不替代。
      <b>撤销是终态</b>：撤销后不可重新启用，只能重新生成。
    </p>

    <!--
      ★ 冻结语义必须写在**生成入口旁边**（`13 §5.2` ①创建时冻结 / ②每次访问实时再滤）。
        这句话是用户理解安全边界的唯一依据，所以逐字照设计册，不自己发明说法。
    -->
    <div class="notice info">
      <b>密级上限在生成时冻结。</b>
      {{ freezeNote() }}
      它不是"这个应用现在有多密"，而是<b>这条链接被允许看到的最敏感内容</b>；每次打开都用它重新过滤一遍。
      <div class="micro muted mt">
        上限取自<b>你此刻的密级上限</b>，由系统记录，生成后不可更改 —— 想放宽只能换一个密级更高的账号重新生成。
      </div>
    </div>

    <!--
      ★ 错误不许伪装成空态：读失败时既不画"暂无数据"，也不留白 —— 给原因 + 重试。
    -->
    <div v-if="loadError" class="notice err">
      <div><b>凭证列表加载失败：</b>{{ loadError.reason }}</div>
      <div v-if="loadError.hint" class="micro">{{ loadError.hint }}</div>
      <el-button size="small" class="mt" @click="fetch">重试</el-button>
    </div>

    <template v-else>
      <!-- 密级字典读不到时只说一句，不把徽标画成机器码 -->
      <div v-if="dictHint" class="micro muted">{{ dictHint }}</div>

      <el-table v-loading="loading" :data="list" size="small">
        <!-- 空态给出口：公开链接是「分享给别人用」的唯一方式，空表要说去哪里生成 -->
        <template #empty>
          <div class="empty">
            <div class="empty-title">还没有生成过公开链接</div>
            <div>公开链接让外部用户免登录试用这个应用，按密级上限限定能看到的内容</div>
            <el-button size="small" type="primary" @click="openCreate">生成分享链接</el-button>
          </div>
        </template>
        <el-table-column label="密级上限" width="176">
          <template #default="{ row }">
            <ClassificationBadge :level="row.maxClassification" :labels="classificationLabels" />
            <el-tooltip
              content="创建时冻结 = 创建者当时的密级上限；被分享内容升密后，这条链接立即失效"
              placement="top"
            >
              <span class="frozen-mark" aria-hidden="true">冻结</span>
            </el-tooltip>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="credentialTagType(stateOf(row).kind)">
              {{ stateOf(row).label }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="这条链接现在能不能用" min-width="260">
          <template #default="{ row }">
            <div class="why">{{ stateOf(row).reason }}</div>
            <div class="micro muted">{{ stateOf(row).action }}</div>
          </template>
        </el-table-column>

        <el-table-column label="分享链接" min-width="260">
          <template #default="{ row }">
            <div class="link-line">
              <el-input :model-value="shareUrl(row.token)" readonly size="small" class="url-input">
                <template #prefix>
                  <el-icon class="link-ico"><Link /></el-icon>
                </template>
              </el-input>
              <el-button size="small" type="primary" plain @click="copyLink(row, stateOf(row))">复制</el-button>
            </div>
            <div class="micro muted">
              token: <code>{{ row.token }}</code>
              <span v-if="stateOf(row).kind !== 'ACTIVE'" class="dead">
                · 链接已不可用，复制后请勿再分发
              </span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="有效期" width="150">
          <template #default="{ row }">
            <span v-if="row.expiresAt">{{ formatTime(row.expiresAt) }}</span>
            <span v-else class="micro muted">长期有效</span>
          </template>
        </el-table-column>

        <el-table-column label="访问" width="150">
          <template #default="{ row }">
            <span class="num">{{ row.accessCount }}</span> 次
            <div class="micro muted">
              {{ row.lastAccessAt ? `最近 ${formatTime(row.lastAccessAt)}` : '从未访问' }}
            </div>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="132">
          <template #default="{ row }">
            <template v-if="stateOf(row).kind === 'REVOKED'">
              <span class="micro muted">终态，不可恢复</span>
            </template>
            <template v-else>
              <!--
                已过期的凭证**没有可做的启停**：它的 enabled 是什么都不影响访问。
                按钮照旧可点会把人送进"停用成功了吗"的假动作里；改成禁用 + 一句为什么。
              -->
              <el-tooltip
                v-if="stateOf(row).kind === 'EXPIRED'"
                content="已过期的凭证停用或启用都不改变结果：它已经打不开了，只能重新生成"
                placement="top"
              >
                <el-button link size="small" disabled>不可启停</el-button>
              </el-tooltip>
              <el-button v-else link size="small" @click="toggleEnabled(row)">
                {{ row.enabled ? '停用' : '启用' }}
              </el-button>
              <el-button link size="small" type="danger" @click="revoke(row)">撤销</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </template>

    <!-- 生成：有效期 + 冻结值预告 + 生成后一键复制 -->
    <el-dialog v-model="createVisible" title="生成分享链接" width="480px" :close-on-click-modal="false">
      <template v-if="!created">
        <el-form label-width="88px">
          <el-form-item label="有效期">
            <el-radio-group v-model="expiresMode">
              <el-radio-button value="never">长期有效</el-radio-button>
              <el-radio-button value="date">指定日期</el-radio-button>
            </el-radio-group>
            <el-date-picker
              v-if="expiresMode === 'date'"
              v-model="expiresAt"
              type="date"
              placeholder="选择到期日"
              style="width: 100%; margin-top: var(--space-xs)"
            />
          </el-form-item>
        </el-form>
        <div class="notice info">
          {{ ceilingFrozenNote() }}
          <div class="micro mt">{{ freezeNote() }}</div>
        </div>
      </template>

      <!-- 生成结果：链接当场可复制，冻结值当场可见（不让用户去列表里找） -->
      <template v-else>
        <div class="done-hd">
          <el-icon class="done-ico"><CircleCheck /></el-icon>
          <span>链接已生成</span>
        </div>
        <div class="ceiling-row">
          <span class="micro muted">密级上限（创建时冻结）</span>
          <ClassificationBadge :level="created.maxClassification" :labels="classificationLabels" />
        </div>
        <div class="link-line">
          <el-input :model-value="shareUrl(created.token)" readonly size="small" class="url-input" />
          <el-button size="small" type="primary" @click="copyLink(created)">复制</el-button>
        </div>
        <div class="notice info mt">{{ freezeNote(labelOfLevel(created.maxClassification)) }}</div>
        <div class="micro muted mt">
          有效期：{{ created.expiresAt ? formatTime(created.expiresAt) : '长期有效' }}
        </div>
      </template>

      <template #footer>
        <template v-if="created">
          <el-button size="small" @click="createVisible = false">完成</el-button>
          <el-button size="small" type="primary" @click="createAnother">再生成一条</el-button>
        </template>
        <template v-else>
          <el-button size="small" @click="createVisible = false">取消</el-button>
          <el-button size="small" type="primary" :loading="creating" @click="create">生成</el-button>
        </template>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CircleCheck, Link } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { assistantsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { AccessCredential } from '@/api/assistants'
import ClassificationBadge from '@/components/ClassificationBadge.vue'
import { useDictionary } from '@/composables/useDictionary'
import { copyText } from '@/utils/clipboard'
import {
  credentialState,
  credentialTagType,
  freezeNote,
  ceilingFrozenNote,
  type CredentialState
} from '@/utils/credential-state'
import { classificationLabelMap } from '@/utils/classification'

const props = defineProps<{ appId: string }>()

const list = ref<AccessCredential[]>([])
const loading = ref(false)
/** ★ 读失败必须说出来；空表 + 无提示 = 把"接口挂了"伪装成"没有凭证" */
const loadError = ref<{ reason: string; hint?: string } | null>(null)

const createVisible = ref(false)
const creating = ref(false)
const expiresMode = ref<'never' | 'date'>('never')
const expiresAt = ref<Date | null>(null)
/** 生成回执：链接 + 冻结值。生成后列表里也会多一行，但**结果要当场可见** */
const created = ref<AccessCredential | null>(null)

/**
 * 密级展示名以**基础数据字典**为真源（`08 §十`）—— 管理员改了展示名（"秘密" → "企业秘密"），
 * 这里跟着变，而不是在页面里再写一份常量。
 * 读不到字典时 `classificationLabelMap` 会兜住那四档的种子文案，绝不落到 `SECRET` 这种机器码。
 */
const { items: classificationItems, hint: dictHint, load: loadClassifications } =
  useDictionary('CLASSIFICATION', '密级')
const classificationLabels = computed(() => classificationLabelMap(classificationItems.value))
const labelOfLevel = (level: AccessCredential['maxClassification']) =>
  classificationLabels.value[level] ?? level

const formatTime = (iso: string) => dayjs(iso).format('YYYY-MM-DD HH:mm')

/**
 * 免登录公开链接的**真实路由形状**是 hash 路径 `/#/public/:token`
 * （`pages/conversation/route.ts`），不是查询串。
 * 拼成 `/#/public?token=xxx` 打开即 404 —— 那个形状在路由表里不存在，也没有任何页面读 `?token`。
 *
 * ★ 用 `origin + pathname` 而不是 `origin` 一个：应用可能挂在子路径下部署，
 *   只取 origin 会让复制出来的链接指向服务器根目录（打开是另一个站点）。
 */
const buildShareUrl = (token: string) => {
  if (typeof window === 'undefined' || !window.location) return `/#/public/${token}`
  const { origin, pathname } = window.location
  return `${origin}${pathname}#/public/${token}`
}
const shareUrl = buildShareUrl
const urlSample = computed(() => buildShareUrl('凭证令牌'))

/** 四态由凭证自身推导（`utils/credential-state.ts`），前端不额外存状态副本 */
const stateOf = (row: AccessCredential) => credentialState(row)

/**
 * 复制链接。★ 复制的同时**把冻结值说一遍** —— 只回一句"已复制"的话，
 * 用户手上多了一条链接，却少了他刚同意的那条安全边界。
 * 非启用态还要提醒一句"这条链接已经不能用了"，否则复制动作会让它看起来仍然是好的。
 */
const copyLink = async (
  row: Pick<AccessCredential, 'token' | 'maxClassification'>,
  state?: CredentialState
) => {
  const url = buildShareUrl(row.token)
  const ok = await copyText(url)
  if (!ok) {
    ElMessage.warning('复制失败，请手动选中链接复制')
    return
  }
  const tail = state && state.kind !== 'ACTIVE' ? `（注意：这条链接${state.label}）` : ''
  ElMessage.success(`已复制链接 · 密级上限「${labelOfLevel(row.maxClassification)}」${tail}`)
}

const fetch = async () => {
  loading.value = true
  loadError.value = null
  try {
    list.value = await assistantsApi.listCredentials(props.appId)
  } catch (error) {
    // ★ 清空并**说明原因**：留着旧列表会让人以为"凭证就这些"，而它们可能已被别人撤销
    list.value = []
    loadError.value = {
      reason: error instanceof ApiError ? error.message : '请稍后重试',
      hint: error instanceof ApiError ? error.hint : undefined
    }
  } finally {
    loading.value = false
  }
}

const openCreate = () => {
  created.value = null
  expiresMode.value = 'never'
  expiresAt.value = null
  createVisible.value = true
}

const createAnother = () => {
  created.value = null
  expiresMode.value = 'never'
  expiresAt.value = null
}

const create = async () => {
  if (expiresMode.value === 'date' && !expiresAt.value) {
    // 静默当成"长期有效"会生成一条永不过期的链接 —— 与用户点的那一档不符，必须拦
    ElMessage.warning('请选择到期日，或改回「长期有效」')
    return
  }
  creating.value = true
  try {
    const result = await assistantsApi.createCredential(props.appId, {
      expiresAt:
        expiresMode.value === 'date' && expiresAt.value
          ? dayjs(expiresAt.value).endOf('day').toISOString()
          : null
    })
    created.value = result
    await fetch()
    // 生成即复制：分享链路里"复制"是最常漏的一步（生成完链接躺在对话框里没人复制）
    const ok = await copyText(buildShareUrl(result.token))
    ElMessage.success(
      ok
        ? `已生成并复制链接 · 密级上限「${labelOfLevel(result.maxClassification)}」`
        : `已生成链接（复制失败，请点「复制」手动复制）· 密级上限「${labelOfLevel(result.maxClassification)}」`
    )
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '生成失败，请稍后重试')
  } finally {
    creating.value = false
  }
}

const toggleEnabled = async (row: AccessCredential) => {
  const turningOff = row.enabled
  try {
    await assistantsApi.setCredentialEnabled(props.appId, row.id, !row.enabled)
    ElMessage.success(turningOff ? '已停用，链接暂停访问（可再启用）' : '已启用，链接恢复访问')
    await fetch()
  } catch (error) {
    const msg = error instanceof ApiError ? error.message : '操作失败'
    const hint = error instanceof ApiError ? error.hint : undefined
    ElMessage.error(hint ? `${msg}（${hint}）` : msg)
  }
}

const revoke = async (row: AccessCredential) => {
  // 取消是正常路径：then 的拒绝回调把它收成 false，不落成 unhandled rejection
  const confirmed = await ElMessageBox.confirm(
    '撤销后该链接立即失效，且无法重新启用。只能重新生成一个新凭证。确认撤销？',
    '确认撤销凭证',
    { type: 'warning', confirmButtonText: '撤销' }
  ).then(
    () => true,
    () => false
  )
  if (!confirmed) return
  try {
    await assistantsApi.revokeCredential(props.appId, row.id)
    ElMessage.success('已撤销，链接立即失效；如需继续分享请重新生成')
    await fetch()
  } catch (error) {
    const msg = error instanceof ApiError ? error.message : '撤销失败'
    const hint = error instanceof ApiError ? error.hint : undefined
    ElMessage.error(hint ? `${msg}（${hint}）` : msg)
  }
}

onMounted(() => {
  // 字典与凭证并行读：密级徽标拿不到字典时走兜底文案，不阻塞列表
  void loadClassifications()
})

defineExpose({ fetch })
</script>

<style scoped>
.cred {
  padding: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.sec-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
}

.hd-ops {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
}

.mt {
  margin-top: var(--space-xs);
}

.link-line {
  display: flex;
  align-items: center;
  gap: var(--space-2xs);
}

.url-input {
  flex: 1;
  min-width: 0;
}

.url-input :deep(.el-input__inner) {
  font-family: var(--font-mono);
  font-size: var(--text-2xs);
}

.link-ico {
  color: var(--color-muted);
}

.frozen-mark {
  margin-left: var(--space-2xs);
  font-size: var(--text-2xs);
  color: var(--color-muted);
  border-bottom: 1px dotted var(--color-rule-strong);
  cursor: help;
}

.why {
  font-size: var(--text-xs);
  line-height: var(--leading-ui);
  color: var(--color-ink-2);
}

.dead {
  color: var(--color-danger);
}

.done-hd {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  font-size: var(--text-sm);
  font-weight: 500;
  color: var(--color-ink);
}

.done-ico {
  color: var(--color-ok);
}

.ceiling-row {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin: var(--space-sm) 0;
}
</style>
