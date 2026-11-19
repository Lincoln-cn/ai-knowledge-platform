<template>
  <div class="panel wrap">
    <div class="hd">
      <span class="h4">{{ isQuestion ? '提问模板' : '角色指令' }}</span>
      <div class="row gap-8">
        <el-select v-model="typeId" size="small" placeholder="全部类别" style="width: 140px" @change="emit('filter', typeId)">
          <el-option label="全部类别" value="" />
          <el-option v-for="t in types" :key="t.id" :label="t.typeName" :value="t.id" />
        </el-select>
        <el-input
          v-model="keyword"
          size="small"
          :placeholder="isQuestion ? '搜索模板名 / 问题' : '搜索角色名称 / 提示词'"
          style="width: 200px"
          clearable
          @keyup.enter="emit('search', keyword)"
          @clear="emit('search', '')"
        />
        <el-button size="small" type="primary" @click="emit('create')">
          {{ isQuestion ? '新建模板' : '新建提示词' }}
        </el-button>
      </div>
    </div>

    <!-- 六列对齐 workBench/markedSystem/markedConfig.vue 基准 -->
    <el-table v-loading="loading" :data="list" size="small">
      <!-- 空态要说下一步：一条都没有时「暂无数据」不告诉用户该做什么；
           搜索无结果与真的没有，是两件事，提示也该不同 -->
      <template #empty>
        <div class="empty">
          <template v-if="keyword">
            <div class="empty-title">没有匹配「{{ keyword }}」的{{ isQuestion ? '模板' : '提示词' }}</div>
            <div>换个关键词，或清空搜索看全部</div>
            <el-button link size="small" type="primary" @click="clearKeyword">清空搜索</el-button>
          </template>
          <template v-else>
            <div class="empty-title">还没有{{ isQuestion ? '提问模板' : '角色指令' }}</div>
            <div>
              {{
                isQuestion
                  ? '提问模板是给用户看的问法示例：出现在对话页的空态和上弹抽屉里，正文就是那句问题'
                  : '角色指令决定应用以什么身份、用什么口径回答，在应用配置里「从提示词选用」'
              }}
            </div>
            <el-button size="small" type="primary" @click="emit('create')">
              {{ isQuestion ? '新建模板' : '新建提示词' }}
            </el-button>
          </template>
        </div>
      </template>
      <el-table-column label="图标" width="60">
        <template #default="{ row }"><span class="icon">{{ row.icon }}</span></template>
      </el-table-column>
      <el-table-column :label="isQuestion ? '模板名' : '角色名称'" prop="roleName" min-width="120" show-overflow-tooltip />
      <el-table-column :label="isQuestion ? '问题' : '提示词'" prop="callWord" min-width="200" show-overflow-tooltip />
      <el-table-column label="类别" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.typeName }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="描述" prop="description" min-width="140" show-overflow-tooltip />
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button link size="small" type="primary" @click="emit('edit', row)">编辑</el-button>
          <!-- 「历史」与编辑并列：改之前先看看以前是怎么写的，是同一个动作的两半 -->
          <el-button link size="small" @click="openHistory(row)">历史</el-button>
          <el-button link size="small" type="danger" @click="emit('remove', row)">删除</el-button>
          <span v-if="row.usageCount > 0" class="micro muted">被 {{ row.usageCount }} 应用引用</span>
        </template>
      </el-table-column>
    </el-table>

    <div v-if="total > pageSize" class="pager">
      <el-pagination
        :current-page="page"
        :page-size="pageSize"
        layout="total, prev, pager, next"
        :total="total"
        @current-change="(p: number) => emit('page', p)"
      />
    </div>

    <PromptVersionDrawer
      ref="versionDrawerRef"
      :current-text="historyRow?.callWord ?? ''"
      :current-name="historyRow?.roleName"
      @restored="emit('restored')"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { PromptConfig, PromptKind, PromptType } from '@/api/prompts'
import PromptVersionDrawer from './PromptVersionDrawer.vue'

const props = defineProps<{
  list: PromptConfig[]
  types: PromptType[]
  loading: boolean
  total: number
  page: number
  pageSize: number
  activeTypeId: string
  activeKeyword: string
  kind: PromptKind
}>()

/** 一套组件两种用途：只换称呼，不换布局 —— 两套表格会让同一件事长成两个样子 */
const isQuestion = computed(() => props.kind === 'QUESTION')

const emit = defineEmits<{
  create: []
  edit: [PromptConfig]
  remove: [PromptConfig]
  filter: [string]
  search: [string]
  page: [number]
  /** 回滚成功后让父级重拉列表（当前正文变了） */
  restored: []
}>()

const versionDrawerRef = ref<InstanceType<typeof PromptVersionDrawer> | null>(null)
/** 打开历史抽屉那一行；抽屉要用它的「当前正文」做对比基线 */
const historyRow = ref<PromptConfig | null>(null)

function openHistory(row: PromptConfig) {
  historyRow.value = row
  versionDrawerRef.value?.open(row.id, row.roleName)
}

const typeId = ref(props.activeTypeId)
const keyword = ref(props.activeKeyword)

watch(() => props.activeTypeId, (v) => (typeId.value = v))
watch(() => props.activeKeyword, (v) => (keyword.value = v))

/** 空态里的「清空搜索」—— 清输入框并让父级重新拉全量 */
const clearKeyword = () => {
  keyword.value = ''
  emit('search', '')
}
</script>

<style scoped>
.wrap {
  padding: var(--space-md);
}

.hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-xs);
  flex-wrap: wrap;
  gap: var(--space-xs);
}

.icon {
  font-size: var(--text-md);
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: var(--space-xs);
}
</style>
