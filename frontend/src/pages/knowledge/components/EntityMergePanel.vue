<script setup lang="ts">
/**
 * 实体归并（第二档，§11.36.1 的 D 项）。
 *
 * ★ 这一档**不自动合并**：属性重叠只是「可能」相同（`{department: 财务}` 的两个实体可以是
 * 「财务部」和「财务共享中心」），而并入哪一边会直接改权限归属。候选清单给人看，人点了才动。
 *
 * ★ 确认的动作是**写一条别名**，不删任何行 —— 合并必须可拆。所以这个面板
 * 没有「删除」按钮，只有「确认合并」。
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  confirmEntityMerge,
  listEntityMergeCandidates,
  type EntityMergeCandidate
} from '@/api/knowledge'

const props = defineProps<{ kbId: string }>()

const rows = ref<EntityMergeCandidate[]>([])
const loading = ref(false)
const busy = ref('')

const load = async () => {
  loading.value = true
  try {
    rows.value = await listEntityMergeCandidates(props.kbId)
  } catch (e) {
    ElMessage.error((e as Error).message || '加载候选失败')
    rows.value = []
  } finally {
    loading.value = false
  }
}

const confirmOne = async (row: EntityMergeCandidate) => {
  try {
    await ElMessageBox.confirm(
      `把「${row.mergeName}」记成「${row.keepName}」的别名。\n\n` +
        '★ 不会删除任何实体行 —— 合并可拆，别名表里多一行而已。\n' +
        `依据：${row.reason}`,
      '确认合并',
      { type: 'warning', confirmButtonText: '确认合并', cancelButtonText: '再想想' }
    )
  } catch {
    return
  }
  busy.value = row.mergeId
  try {
    const res = await confirmEntityMerge(props.kbId, row.keepId, row.mergeName)
    ElMessage.success(res.added > 0 ? '已记入别名表' : '这条别名之前已经记过，没有新增')
    await load()
  } catch (e) {
    ElMessage.error((e as Error).message || '确认失败')
  } finally {
    busy.value = ''
  }
}

onMounted(load)
</script>

<template>
  <section class="merge-panel">
    <header class="head">
      <div>
        <h3>实体归并候选</h3>
        <p class="hint">
          属性重叠只说明<strong>可能</strong>是同一个实体。确认后只写一条别名，
          <strong>不删除任何实体行</strong> —— 合错了把别名去掉就行。
        </p>
      </div>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </header>

    <el-skeleton v-if="loading && rows.length === 0" :rows="4" animated />

    <el-empty v-else-if="rows.length === 0" description="没有需要归并的候选" />

    <el-table v-else :data="rows" size="small" row-key="mergeId">
      <el-table-column label="保留" min-width="160">
        <template #default="{ row }">{{ row.keepName }}</template>
      </el-table-column>
      <el-table-column label="并入" min-width="160">
        <template #default="{ row }">{{ row.mergeName }}</template>
      </el-table-column>
      <el-table-column label="依据" min-width="220">
        <template #default="{ row }">
          <span class="reason">{{ row.reason }}</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" align="right">
        <template #default="{ row }">
          <el-button
            size="small"
            type="primary"
            :loading="busy === row.mergeId"
            @click="confirmOne(row)"
          >
            确认合并
          </el-button>
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.merge-panel {
  padding: 4px 2px;
}
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.head h3 {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0;
}
.hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}
.reason {
  font-size: 12px;
  color: var(--el-text-color-regular);
}
</style>
