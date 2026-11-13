<template>
  <NodeContainer :nodeModel="nodeModel">
    <!--
      画布卡片的只读摘要：这个节点最该一眼看到的是「抽哪一篇」。
      ★ 不用 docId 本身（UUID 看不出来是什么），用文档名。
    -->
    <template #essential>
      文档 ·
      <template v-if="selectedDocument">{{ selectedDocument.name }}</template>
      <template v-else>未选择</template>
    </template>

    <h5 class="title-decoration-1 mb-8">节点设置</h5>
    <el-card shadow="never" class="card-never">
      <el-form @submit.prevent :model="form_data" label-position="top" ref="DocExtractFormRef">
        <el-form-item label="选择知识库">
          <el-select
            :model-value="form_data.kbId"
            :loading="kbLoading"
            placeholder="请选择知识库"
            class="w-full"
            @change="pickKb"
          >
            <el-option v-for="kb in kbList" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
        </el-form-item>
        <el-form-item
          label="选择文档"
          prop="docId"
          :rules="{ required: true, message: '请选择要抽取正文的文档', trigger: 'change' }"
        >
          <el-select
            :model-value="form_data.docId"
            :loading="docLoading"
            :disabled="!form_data.kbId"
            :placeholder="form_data.kbId ? '请选择文档' : '先选知识库'"
            class="w-full"
            @change="pickDoc"
          >
            <el-option
              v-for="doc in docList"
              :key="doc.id"
              :label="docOptionLabel(doc)"
              :value="doc.id"
            />
          </el-select>
          <div class="doc-hint">
            ★ 只有已经解析出正文的文档才能用：还在解析中的文档会在运行时报「还没有解析出正文」，
            括号里是它的当前状态。想先跑通流程，选状态为已完成的那篇。
            <br />
            ★ 正文会写进流程变量 document_markdown，下游节点按
            「节点id.字段名」的方式引用它（节点id 见卡片左上角，如 doc-extract-1）。
          </div>
        </el-form-item>
      </el-form>
    </el-card>
  </NodeContainer>
</template>

<script setup lang="ts">
import { set } from 'lodash-es'
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import type { FormInstance } from 'element-plus'
import { ref, computed, onMounted } from 'vue'
import { listKnowledgeBases } from '@/api/knowledge'
import { listDocuments } from '@/api/ingestion'

const props = defineProps<{ nodeModel: any }>()

/**
 * `props.nodeModel` 是 LogicFlow 的模型对象，不是 Vue 的响应式对象：
 * 只改 `properties.node_data` 里的字段，模板不会重渲染（同 search-dataset-node 的坑，
 * 那边留了详细说明）。这里同样用一个显式版本号 `tick` 建依赖。
 */
const tick = ref(0)

const DocExtractFormRef = ref<FormInstance>()

const form_data = computed({
  get: () => {
    void tick.value
    if (props.nodeModel.properties.node_data) {
      return props.nodeModel.properties.node_data
    }
    set(props.nodeModel.properties, 'node_data', { kbId: null, docId: null })
    return props.nodeModel.properties.node_data
  },
  set: (value) => {
    set(props.nodeModel.properties, 'node_data', value)
    tick.value++
  }
})

const kbList = ref<any[]>([])
const docList = ref<any[]>([])
const kbLoading = ref(false)
const docLoading = ref(false)

const selectedDocument = computed(() => {
  void tick.value
  const id = form_data.value?.docId
  return docList.value.find((d) => d.id === id) ?? null
})

function pickKb(id: string) {
  set(props.nodeModel.properties.node_data, 'kbId', id)
  // 换库就把文档清掉 —— 留着上一库的 id 会指向一篇别的库的文档
  set(props.nodeModel.properties.node_data, 'docId', null)
  tick.value++
  loadDocuments(id)
}

function pickDoc(id: string) {
  set(props.nodeModel.properties.node_data, 'docId', id)
  tick.value++
}

async function loadKbs() {
  kbLoading.value = true
  try {
    const result = await listKnowledgeBases({ page: 1, size: 100 })
    kbList.value = result.list ?? []
  } catch {
    // 库列表拿不到不该让画布崩掉；下拉空着，用户可以重开面板再试
    kbList.value = []
  } finally {
    kbLoading.value = false
  }
}

async function loadDocuments(kbId: string) {
  if (!kbId) {
    docList.value = []
    return
  }
  docLoading.value = true
  try {
    const result = await listDocuments(kbId, { page: 1, size: 100 })
    docList.value = result.list ?? []
  } catch {
    docList.value = []
  } finally {
    docLoading.value = false
  }
}

/** 未解析完的文档照样列出来，但把状态写在名字后面 —— 隐藏它们会让用户以为文档丢了 */
function docOptionLabel(doc: any): string {
  return doc.status === 'SUCCEEDED' ? doc.name : `${doc.name}（${doc.status}）`
}

const validate = () => {
  return DocExtractFormRef.value?.validate().catch((err) => {
    return Promise.reject({ node: props.nodeModel, errMessage: err })
  })
}

onMounted(() => {
  loadKbs()
  const kbId = props.nodeModel.properties?.node_data?.kbId
  if (kbId) loadDocuments(kbId)
  set(props.nodeModel, 'validate', validate)
})
</script>

<style lang="scss" scoped>
:deep(.el-form-item--label-top .el-form-item__label) {
  padding-right: 0;
}

.doc-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
