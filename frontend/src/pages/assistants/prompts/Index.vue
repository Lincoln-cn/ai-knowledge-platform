<template>
  <div class="page">
    <PageHeader
      :title="kind === 'QUESTION' ? '提示词 · 提问模板' : '提示词 · 角色指令'"
      :desc="
        kind === 'QUESTION'
          ? '给用户看的问法示例：出现在对话页的空态与上弹抽屉里；类型维护也在这页'
          : '给模型的角色设定，被应用按引用选用；类型维护也在这页'
      "
    >
      <template #actions>
        <el-radio-group v-model="kind" size="small" @change="onKindChange">
          <el-radio-button value="ROLE">角色指令</el-radio-button>
          <el-radio-button value="QUESTION">提问模板</el-radio-button>
        </el-radio-group>
        <el-button size="small" :icon="Setting" @click="openTypes">类型管理</el-button>
        <el-button size="small" :icon="Refresh" :loading="loadingTypes || loadingConfigs" @click="reloadAll">
          刷新
        </el-button>
      </template>
    </PageHeader>

    <!--
      ★ 这两段说明是两种用途的**分界**，不是操作步骤。
      用户最容易搞错的就是"把角色指令当提问模板用"（我第一版就搞错了），
      所以切换档位时把差别当场讲清楚，而不是让人去记忆。
    -->
    <div v-if="kind === 'ROLE'" class="notice info">
      角色指令是写给<b>模型</b>的：在应用配置里点「从提示词选用」，它会成为那个应用的身份与口径。
      被应用引用过的不能直接删，要先在应用里解除引用。
    </div>
    <div v-else class="notice info">
      提问模板是写给<b>用户</b>的：对话页空态会平铺几条，点一下填进输入框。
      正文就是用户会看到的那句问题，<code>{例}</code> 这样的花括号是留给用户替换的占位。
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
      :kind="kind"
      @create="openConfig()"
      @edit="openConfig"
      @remove="removeConfig"
      @filter="onFilter"
      @search="onSearch"
      @page="onPage"
    />

    <PromptFormDialog ref="formRef" :types="types" :kind="kind" @saved="onSaved" />
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
import type { PromptConfig, PromptKind, PromptType } from '@/api/prompts'
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
/**
 * 当前看哪一档：角色指令（给模型）还是提问模板（给用户）。
 * ★ 默认 `ROLE` —— 这一页原本就是角色指令的，默认值不能悄悄换掉既有语义。
 */
const kind = ref<PromptKind>('ROLE')
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
      // ★ 显式传 kind：不传就是"两档混在一起"，而这一页永远只看一档
      kind: kind.value,
      page: page.value,
      size: pageSize
    })
    configs.value = result.list
    total.value = result.total
  } finally {
    loadingConfigs.value = false
  }
}

/** 换档位要回到第一页并清掉筛选：上一档的类别在另一档里可能是空的，留着只会看到空表 */
const onKindChange = () => {
  page.value = 1
  typeId.value = ''
  loadConfigs()
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
