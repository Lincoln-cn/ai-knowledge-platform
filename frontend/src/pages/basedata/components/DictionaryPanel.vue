<template>
  <div class="dict-panel panel">
    <div class="hd">
      <div class="hd-main">
        <span class="h4">{{ title }}</span>
        <span class="micro muted">共 {{ list.length }} 项</span>
      </div>
      <div class="hd-actions">
        <el-button size="small" :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <!-- 密级只允许编辑与启停：新增档位要连数据库生成列一起迁移，所以入口整块不渲染 -->
        <el-button v-if="allowCreate" size="small" type="primary" @click="openForm()">新增</el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="list"
      size="small"
      :row-class-name="rowClass"
      :empty-text="'暂无' + title"
    >
      <el-table-column label="展示名" min-width="150">
        <template #default="{ row }">
          <span class="lb">{{ row.label }}</span>
        </template>
      </el-table-column>
      <el-table-column label="code" width="180">
        <template #default="{ row }"><code class="micro">{{ row.code }}</code></template>
      </el-table-column>
      <el-table-column label="排序" prop="sort" width="80" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.enabled" size="small" type="success" effect="plain">启用</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">已停用</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="备注" min-width="200">
        <template #default="{ row }">
          <span v-if="row.remark" class="micro">{{ row.remark }}</span>
          <span v-else class="micro faint">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130">
        <template #default="{ row }">
          <el-button link size="small" type="primary" @click="openForm(row)">编辑</el-button>
          <!-- ★ 没有「删除」：code 已经写进历史数据，删掉会让那些数据的展示回落成机器码而无人报错 -->
          <el-button link size="small" @click="toggleEnabled(row)">{{ row.enabled ? '停用' : '启用' }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      v-model="visible"
      :title="(editing ? '编辑' : '新增') + title"
      width="480px"
      :close-on-click-modal="false"
      @closed="reset"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item label="展示名" prop="label">
          <el-input v-model="form.label" maxlength="64" show-word-limit placeholder="例如：通知公告" />
        </el-form-item>

        <el-form-item label="code" prop="code">
          <!-- 编辑时只读：code 已经写进历史数据与召回前缀，改它等于让既有数据找不到展示名 -->
          <el-input v-model="form.code" :disabled="editing !== null" maxlength="64" placeholder="例如：通知公告" />
          <span class="field-hint micro muted">
            下拉里显示的是展示名，落进历史数据的是 code —— 两者分开，改名才不需要重新向量化
          </span>
        </el-form-item>

        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" :max="9999" controls-position="right" style="width: 130px" />
          <span class="field-hint micro muted">留空 = 不改动（新增时排到本类末尾）</span>
        </el-form-item>

        <el-form-item label="备注">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
            placeholder="维护页给管理员看的说明，留空则保持原值"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button size="small" @click="visible = false">取消</el-button>
        <el-button size="small" type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { dictionariesApi } from '@/api'
import { ApiError } from '@/api/types'
import type { CreateDictionaryBody, DictionaryItem, DictionaryKind, UpdateDictionaryBody } from '@/api/dictionaries'

const props = withDefaults(
  defineProps<{
    kind: DictionaryKind
    /** 表格与小标题上的名字。由调用方给，避免"分类名"与 tab 名在两处各写一份 */
    title: string
    /** 密级那一类传 false：它只允许改展示名与启停（见 Index.vue 的说明） */
    allowCreate?: boolean
  }>(),
  // ★ `allowCreate` **必须有默认值**：Vue 里只声明成可选 boolean 而不给默认时，缺省是 `undefined`
  //   ⇒ `v-if="allowCreate"` 为假 ⇒ 四个 tab 谁都看不到「新增」按钮，"字典可维护"整件事就只剩能看不能加。
  //   ★ 这个 bug 逮到它的不是人眼：走查闸门那条"提示词类型有增/改/停用入口"的断言报了 `新增 0 个`。
  { allowCreate: true }
)

const list = ref<DictionaryItem[]>([])
const loading = ref(false)
const saving = ref(false)
const visible = ref(false)
const editing = ref<DictionaryItem | null>(null)
const formRef = ref<FormInstance>()

const form = reactive<{ code: string; label: string; sort: number | undefined; remark: string }>({
  code: '',
  label: '',
  sort: undefined,
  remark: ''
})

const rules: FormRules = {
  label: [{ required: true, message: '请输入展示名', trigger: 'blur' }],
  code: [{ required: true, message: '请输入 code', trigger: 'blur' }]
}

/** 停用项置灰：既有数据仍读得到它，但必须一眼看出"新数据不该再选它" */
const rowClass = ({ row }: { row: DictionaryItem }) => (row.enabled ? '' : 'row-disabled')

/** 维护页必须带上停用项 —— 否则"我停用过什么"没人查得到 */
const load = async () => {
  loading.value = true
  try {
    list.value = await dictionariesApi.listDictionaries(props.kind, true)
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载基础数据失败')
  } finally {
    loading.value = false
  }
}

const openForm = (row?: DictionaryItem) => {
  editing.value = row ?? null
  form.code = row?.code ?? ''
  form.label = row?.label ?? ''
  form.sort = row?.sort
  form.remark = row?.remark ?? ''
  visible.value = true
}

const reset = () => {
  formRef.value?.resetFields()
  formRef.value?.clearValidate()
  form.code = ''
  form.label = ''
  form.sort = undefined
  form.remark = ''
  editing.value = null
}

const submit = async () => {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editing.value) {
      // 只传要改的字段（契约是 Partial）：sort 留空就是不改动，不是改成 0
      const body: UpdateDictionaryBody = {
        kind: props.kind,
        code: editing.value.code,
        label: form.label,
        sort: form.sort,
        remark: form.remark
      }
      await dictionariesApi.updateDictionary(body)
    } else {
      const body: CreateDictionaryBody = {
        kind: props.kind,
        code: form.code,
        label: form.label,
        sort: form.sort,
        remark: form.remark
      }
      await dictionariesApi.createDictionary(body)
    }
    ElMessage.success('已保存')
    visible.value = false
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

const toggleEnabled = async (row: DictionaryItem) => {
  const next = !row.enabled
  if (!next) {
    // 停用不是删除：说清"既有数据照旧可读"才不会让人以为是在删数据
    const confirmed = await ElMessageBox.confirm(
      `停用后「${row.label}」不再出现在新的下拉里，既有数据照旧可读。确认停用？`,
      '确认停用',
      { type: 'warning', confirmButtonText: '停用' }
    ).then(
      () => true,
      () => false
    )
    if (!confirmed) return
  }
  try {
    await dictionariesApi.updateDictionary({ kind: props.kind, code: row.code, enabled: next })
    ElMessage.success(next ? '已启用' : '已停用')
    await load()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '操作失败')
  }
}

onMounted(load)
</script>

<style scoped>
.dict-panel {
  padding: var(--space-md);
}

.hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  margin-bottom: var(--space-xs);
}

.hd-main {
  display: flex;
  align-items: baseline;
  gap: var(--space-xs);
  min-width: 0;
}

.hd-actions {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  flex-shrink: 0;
}

/* 停用项置灰。着色落在 td 上：Element Plus 的单元格自带 text-color，写在 tr 上会被它盖掉 */
.dict-panel :deep(.row-disabled td.el-table__cell) {
  color: var(--color-faint);
}

.field-hint {
  display: block;
  margin-top: var(--space-3xs);
  line-height: var(--leading-body);
}
</style>
