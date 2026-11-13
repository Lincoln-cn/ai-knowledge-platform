<template>
  <NodeContainer :nodeModel="nodeModel">
    <!--
      卡片摘要写「哪个应用」而不是「应用调用」：图上有两个应用调用节点时，
      只写类型名等于两张卡片长得一样，排查时得逐个点开。
    -->
    <template #essential>
      调用 · {{ appLabel }}
    </template>

    <h5 class="title-decoration-1 mb-8">节点设置</h5>
    <el-card shadow="never" class="card-never">
      <el-form label-position="top">
        <el-form-item label="被调应用">
          <el-select
            :model-value="form_data.applicationId"
            filterable
            placeholder="选一个编排应用"
            @change="pickApp"
          >
            <el-option
              v-for="app in apps"
              :key="app.id"
              :value="app.id"
              :label="appOptionLabel(app)"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="版本">
          <el-input
            :model-value="form_data.version"
            placeholder="留空 = 最新已发布版本"
            @update:model-value="(v: string) => writeField('version', v)"
          />
        </el-form-item>
      </el-form>

      <!--
        ★ 把「必须是已发布」写在面板上：草稿允许不合规，但被调应用没有发布版本时，
          这个节点在运行时一定会失败 —— 早点说比跑到一半再报清楚。
      -->
      <p class="application-hint">
        只能调用<strong>已发布</strong>的编排应用：没发布过、或当前账号看不到它时，这一步会明确失败。
        上游输入就是它的入口输入，它的最终输出写进流程变量 <code>app_output</code>。
      </p>
    </el-card>
  </NodeContainer>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { set } from 'lodash-es'
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import { listAssistants, type Assistant } from '@/api/assistants'

/**
 * 应用调用面板。
 *
 * ★ 不用 `SchemaForm`：那个表单的下拉只认 `knowledge-bases` / `documents` 两种数据源，
 *   而这里要列的是「编排应用」。为一种数据源改 SchemaForm 的 optionsSource 表，
 *   会把「哪个面板用哪种数据源」变成一张要同步维护的表 —— 与 `#337` 的方向相反。
 *
 * ★ `nodeModel.properties` 不是响应式对象，所以沿用 `search-dataset-node` / `document-extract-node`
 *   的版本号模式：写 `node_data` 之后 `tick++`，让 `form_data` 这个 computed 重新求值。
 *   直接用 `v-model="form_data.xxx"` 改的是快照，画布保存时读不到。
 */
const props = defineProps<{ nodeModel: any }>()
const tick = ref(0)
const apps = ref<Assistant[]>([])

const form_data = computed<{ applicationId: string | null; version?: string }>({
  get() {
    void tick.value
    const data = props.nodeModel.properties?.node_data ?? {}
    return {
      applicationId: typeof data.applicationId === 'string' ? data.applicationId : null,
      version: typeof data.version === 'string' ? data.version : ''
    }
  },
  set(value) {
    set(props.nodeModel.properties, 'node_data', value)
    tick.value++
  }
})

function writeField(key: string, value: unknown) {
  set(props.nodeModel.properties.node_data, key, value)
  tick.value++
}

/** 换应用时清掉版本：上一部应用的版本号对新应用没有意义，留着会变成"版本 2 不存在"的怪错误。 */
function pickApp(id: string) {
  writeField('applicationId', id)
  writeField('version', '')
}

function appOptionLabel(app: Assistant) {
  // ★ 未发布的也列出来并标注，而不是隐藏：隐藏会让"我刚建的应用在列表里找不到"变成一次排查。
  return app.published ? app.name : `${app.name}（未发布）`
}

const appLabel = computed(() => {
  const id = form_data.value.applicationId
  if (!id) return '未选应用'
  return apps.value.find((a) => a.id === id)?.name ?? id
})

onMounted(async () => {
  try {
    const page = await listAssistants({ page: 1, size: 100, type: 'WORKFLOW' })
    apps.value = page.list
  } catch {
    // 列表拉不到不该让节点打不开：面板仍能显示已选的值，只是下拉是空的
    apps.value = []
  }
})

defineExpose({
  validate: () => {
    if (!form_data.value.applicationId) {
      return '应用调用节点要先选一个被调应用'
    }
    return true
  }
})
</script>

<style scoped>
.application-hint {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
