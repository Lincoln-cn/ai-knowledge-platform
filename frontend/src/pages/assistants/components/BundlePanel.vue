<template>
  <div class="bundle panel">
    <div class="sec-hd">
      <span class="h4">导入导出</span>
      <div class="row gap-8">
        <el-button size="small" :loading="exporting" @click="exportBundle">导出</el-button>
        <el-button size="small" @click="openImport">导入</el-button>
      </div>
    </div>
    <p class="micro muted">
      导出包含配置与<b>依赖清单</b>（模型 / 知识库 / 提示词）。导入前逐项校验依赖；
      <b>缺依赖时默认阻止</b>，必须确认降级后果后才能「跳过缺失引用导入」。
    </p>

    <div v-if="exportPreview" class="export-preview">
      <div class="row-between">
        <span class="micro"><b>导出包</b> · {{ exportPreview.format }}</span>
        <el-button link size="small" type="primary" @click="download">下载 JSON</el-button>
      </div>
      <div class="micro muted">是否含文档：{{ exportPreview.includesDocuments ? '是' : '否' }}</div>
      <div class="deps">
        <div v-for="(d, i) in exportPreview.dependencies" :key="i" class="dep micro">
          <el-tag size="small" effect="plain">{{ labelOf(BUNDLE_DEPENDENCY_KIND_LABELS, d.kind) }}</el-tag>
          <span>{{ d.name }}</span>
          <span class="muted">{{ d.detail }}</span>
        </div>
      </div>
    </div>

    <el-dialog v-model="importVisible" title="导入应用包" width="560px" :close-on-click-modal="false">
      <el-upload
        drag
        :auto-upload="false"
        :limit="1"
        accept=".json"
        :on-change="onFile"
        :on-remove="() => (bundle = null)"
      >
        <div class="micro muted">拖入或点击选择导出的 JSON 包</div>
      </el-upload>

      <template v-if="checkResult">
        <div class="notice" :class="checkResult.hasMissing ? 'warn' : 'info'" style="margin-top: var(--space-sm)">
          <div v-if="checkResult.legacy">该包为旧格式，已自动识别。</div>
          <div v-if="checkResult.hasMissing">
            ⚠ 有 {{ checkResult.dependencies.filter((d) => !d.satisfied).length }} 项依赖在本环境不存在。
            不勾选「跳过缺失引用导入」将阻止导入。
          </div>
          <div v-else>依赖全部满足，可直接导入。</div>
        </div>

        <el-table :data="checkResult.dependencies" size="small" style="margin-top: var(--space-xs)">
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ labelOf(BUNDLE_DEPENDENCY_KIND_LABELS, row.kind) }}</template>
          </el-table-column>
          <el-table-column label="名称" prop="name" min-width="120" />
          <el-table-column label="说明" min-width="140">
            <template #default="{ row }"><span class="micro muted">{{ row.detail }}</span></template>
          </el-table-column>
          <el-table-column label="校验" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.satisfied" size="small" type="success" effect="plain">满足</el-tag>
              <el-tag v-else size="small" type="danger" effect="plain">缺失</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="降级后果" min-width="160">
            <template #default="{ row }">
              <span v-if="row.degrade" class="micro" style="color: var(--color-warn)">{{ row.degrade }}</span>
              <span v-else class="micro muted">—</span>
            </template>
          </el-table-column>
        </el-table>

        <el-checkbox
          v-if="checkResult.hasMissing"
          v-model="skipMissing"
          style="margin-top: var(--space-xs)"
        >
          我已了解降级后果，跳过缺失引用导入
        </el-checkbox>
      </template>

      <template #footer>
        <el-button size="small" @click="importVisible = false">取消</el-button>
        <el-button
          size="small"
          type="primary"
          :loading="importing"
          :disabled="!bundle || (checkResult?.hasMissing && !skipMissing)"
          @click="doImport"
        >导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { assistantsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { AssistantBundle, BundleCheckResult } from '@/api/assistants'
import { BUNDLE_DEPENDENCY_KIND_LABELS, labelOf } from '@/constants/labels'

const props = defineProps<{ appId: string }>()
const emit = defineEmits<{ imported: [] }>()

const exporting = ref(false)
const importVisible = ref(false)
const importing = ref(false)
const exportPreview = ref<AssistantBundle | null>(null)
const bundle = ref<AssistantBundle | null>(null)
const checkResult = ref<BundleCheckResult | null>(null)
const skipMissing = ref(false)

const exportBundle = async () => {
  exporting.value = true
  try {
    exportPreview.value = await assistantsApi.exportAssistant(props.appId, { includeDocuments: false })
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '导出失败')
  } finally {
    exporting.value = false
  }
}

const download = () => {
  if (!exportPreview.value) return
  const blob = new Blob([JSON.stringify(exportPreview.value, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `assistant-${props.appId}.json`
  a.click()
  URL.revokeObjectURL(url)
}

const openImport = () => {
  bundle.value = null
  checkResult.value = null
  skipMissing.value = false
  importVisible.value = true
}

const onFile = async (file: UploadFile) => {
  const raw = file.raw
  if (!raw) return
  try {
    const text = await raw.text()
    bundle.value = JSON.parse(text) as AssistantBundle
    checkResult.value = await assistantsApi.checkBundleDependencies({
      format: bundle.value.format,
      dependencies: bundle.value.dependencies
    })
    skipMissing.value = false
  } catch (error) {
    bundle.value = null
    checkResult.value = null
    ElMessage.error(error instanceof ApiError ? error.message : '无法解析该文件，请确认是导出的 JSON 包')
  }
}

const doImport = async () => {
  if (!bundle.value) return
  importing.value = true
  try {
    await assistantsApi.importAssistant({
      bundle: bundle.value,
      skipMissingReferences: skipMissing.value
    })
    ElMessage.success('导入成功')
    importVisible.value = false
    emit('imported')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '导入失败')
  } finally {
    importing.value = false
  }
}
</script>

<style scoped>
.bundle {
  padding: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.sec-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.export-preview {
  padding: var(--space-xs) var(--space-sm);
  background: var(--color-sunken);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.deps {
  display: flex;
  flex-direction: column;
  gap: var(--space-2xs);
}

.dep {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}
</style>
