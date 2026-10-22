<template>
  <div class="page">
    <PageHeader
      title="提示词"
      desc="提示词配置：可复用的角色指令，被应用按引用选用；类型可在本页「类型管理」里维护"
    >
      <template #actions>
        <el-button size="small" :icon="Setting" @click="openTypes">类型管理</el-button>
        <el-button size="small" :icon="Refresh" :loading="loadingTypes || loadingConfigs" @click="reloadAll">
          刷新
        </el-button>
      </template>
    </PageHeader>

    <div class="notice info">
      提示词配置里的「角色名称 + 提示词」可在应用配置中被引用（<b>从提示词选用</b>）。
      被应用引用的配置删除前会阻止，并提示先解除引用。
    </div>

    <PromptConfigPanel
      :list="configs"
      :types="types"
      :loading="loadingConfigs"
      :total="total"
      :page="page"
      :page-size="pageSize"
      :active-type-id="typeId"
      :active-keyword="keyword"
      @create="openConfig()"
      @edit="openConfig"
      @remove="removeConfig"
      @filter="onFilter"
      @search="onSearch"
      @page="onPage"
    />

    <PromptFormDialog ref="formRef" :types="types" @saved="onSaved" />
    <PromptTypeDialog
      ref="typeDialogRef"
      :types="types"
      :loading="loadingTypes"
      @changed="onTypesChanged"
    />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Setting } from '@element-plus/icons-vue'
import { promptsApi } from '@/api'
import { ApiError } from '@/api/types'
import type { PromptConfig, PromptType } from '@/api/prompts'
import PageHeader from '@/components/PageHeader.vue'
import PromptConfigPanel from '../components/prompts/PromptConfigPanel.vue'
import PromptFormDialog from '../components/prompts/PromptFormDialog.vue'
import PromptTypeDialog from '../components/prompts/PromptTypeDialog.vue'

const types = ref<PromptType[]>([])
const configs = ref<PromptConfig[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 10
const typeId = ref('')
const keyword = ref('')
const loadingTypes = ref(false)
const loadingConfigs = ref(false)

const formRef = ref<InstanceType<typeof PromptFormDialog>>()
const typeDialogRef = ref<InstanceType<typeof PromptTypeDialog>>()

/** 类型与模板同在提示词域：本页负责读写，避免「基础数据」与提示词页两套入口各写一半 */
const loadTypes = async () => {
  loadingTypes.value = true
  try {
    const result = await promptsApi.listPromptTypes({ page: 1, size: 100 })
    types.value = result.list
  } finally {
    loadingTypes.value = false
  }
}

const loadConfigs = async () => {
  loadingConfigs.value = true
  try {
    const result = await promptsApi.listPromptConfigs({
      keyword: keyword.value || undefined,
      typeId: typeId.value || undefined,
      page: page.value,
      size: pageSize
    })
    configs.value = result.list
    total.value = result.total
  } finally {
    loadingConfigs.value = false
  }
}

const reloadAll = async () => {
  await Promise.all([loadTypes(), loadConfigs()])
}

const openConfig = (row?: PromptConfig) => {
  formRef.value?.openConfig(row)
}

const onSaved = () => {
  reloadAll()
}

const openTypes = () => {
  typeDialogRef.value?.open()
}

const onTypesChanged = async () => {
  await loadTypes()
  // 类型名称/图标会出现在筛选项与模板表里，一并刷新
  await loadConfigs()
}

const removeConfig = async (row: PromptConfig) => {
  const confirmed = await ElMessageBox.confirm(
    `删除提示词「${row.roleName}」？若正被应用引用会阻止删除。`,
    '确认删除提示词',
    { type: 'warning', confirmButtonText: '删除' }
  ).then(
    () => true,
    () => false
  )
  if (!confirmed) return
  try {
    await promptsApi.deletePromptConfig(row.id)
    ElMessage.success('已删除')
    await loadConfigs()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

const onFilter = (id: string) => {
  typeId.value = id
  page.value = 1
  loadConfigs()
}

const onSearch = (kw: string) => {
  keyword.value = kw
  page.value = 1
  loadConfigs()
}

const onPage = (p: number) => {
  page.value = p
  loadConfigs()
}

onMounted(reloadAll)
</script>
