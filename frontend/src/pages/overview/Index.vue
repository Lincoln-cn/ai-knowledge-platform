<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h1 class="h1">迁移总览</h1>
        <p class="micro muted intro">
          本工程是从零搭的脚手架，正在把 <code>qa-ui-3</code> 的可复用内容按目标设计迁进来。
          接口契约已固定（<code>src/api/**</code>），当前走 mock，接上后端只改 <code>VITE_USE_MOCK</code>。
        </p>
      </div>
    </div>

    <div class="facts panel">
      <div class="fact">
        <span class="fact-k label">源工程</span><span class="fact-v">516 文件 · 91,545 行</span>
      </div>
      <div class="fact">
        <span class="fact-k label">可复用</span><span class="fact-v">15,179 行（21.0%）</span>
      </div>
      <div class="fact">
        <span class="fact-k label">已迁移模块</span>
        <span class="fact-v">{{ doneCount }} / {{ modules.length }}</span>
      </div>
      <div class="fact fact-end">
        <span class="micro muted">不依赖 avue / vuex / BladeX</span>
      </div>
    </div>

    <div class="panel table-panel">
      <el-table :data="modules" style="width: 100%">
        <el-table-column label="目标模块" width="130">
          <template #default="{ row }">
            <span class="name">{{ row.name }}</span>
          </template>
        </el-table-column>

        <el-table-column label="来源（qa-ui-3）" min-width="330">
          <template #default="{ row }">
            <code class="micro">{{ row.source }}</code>
          </template>
        </el-table-column>

        <el-table-column label="可复用" width="110" align="right">
          <template #default="{ row }">
            <span class="num">{{ row.reuseLines.toLocaleString() }} 行</span>
          </template>
        </el-table-column>

        <el-table-column label="需合并" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.mergeGroups" size="small" type="warning" effect="plain">
              {{ row.mergeGroups }} 组
            </el-tag>
            <span v-else class="muted micro">—</span>
          </template>
        </el-table-column>

        <el-table-column label="avue 重写" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.hasAvue" size="small" type="danger" effect="plain">需重写</el-tag>
            <span v-else class="muted micro">—</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="row.status === '已完成' ? 'success' : 'info'">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <router-link :to="row.path" class="link">打开</router-link>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="notice warn">
      <b>未迁移的模块点开会看到「尚未迁移」页</b> —— 这是刻意的：不拿假数据把页面填满，
      免得「能点开」被误当成「已做完」。
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { migrationModules } from '@/constants/migration'

const modules = migrationModules
const doneCount = computed(() => modules.filter((m) => m.status === '已完成').length)
</script>

<style scoped>
.intro {
  margin: 0;
  line-height: var(--leading-body);
  max-width: 760px;
}

/* 事实条：4pt 刻度 */
.facts {
  display: flex;
  align-items: center;
  overflow: hidden;
}

.fact {
  display: flex;
  align-items: baseline;
  gap: var(--space-xs);
  padding: var(--space-sm) var(--space-md);
  border-right: 1px solid var(--color-rule-2);
}

.fact-end {
  margin-left: auto;
  border-right: none;
}

.fact-k {
  color: var(--color-muted);
}

.fact-v {
  font-size: var(--text-xs);
  font-weight: 500;
  color: var(--color-ink);
}

.table-panel {
  overflow: hidden;
}

.name {
  font-weight: 500;
  color: var(--color-ink);
}

code {
  background: var(--color-sunken);
  padding: 0 var(--space-2xs);
  border-radius: var(--radius-xs);
}

/* 文字按钮：accent 色，hover 出下划线 */
.link {
  color: var(--color-accent);
  font-size: var(--text-2xs);
  text-underline-offset: 2px;
}

.link:hover {
  text-decoration: underline;
}
</style>
