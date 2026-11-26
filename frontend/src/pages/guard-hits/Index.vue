<template>
  <div class="page">
    <PageHeader
      title="拦截记录"
      desc="被安全围栏拦下的提问，记着谁、什么时间、在哪个会话。"
    >
      <template #actions>
        <el-select v-model="days" size="small" class="filter-select" @change="reload">
          <el-option :value="7" label="最近 7 天" />
          <el-option :value="30" label="最近 30 天" />
          <el-option :value="90" label="最近 90 天" />
          <el-option :value="0" label="全部" />
        </el-select>
        <el-button size="small" :icon="Refresh" :loading="loading" @click="reload">刷新</el-button>
      </template>
    </PageHeader>

    <!-- 错误态 ≠ 空态：请求失败不能伪装成「这段时间没人被拦」 -->
    <div v-if="loadError" class="notice err">
      <b>读取拦截记录失败：</b>{{ loadError }}
      <el-button size="small" type="primary" plain style="margin-left: var(--space-sm)" @click="reload">
        重试
      </el-button>
    </div>

    <section class="panel">
      <div class="panel-head">
        <div>
          <div class="card-title">拦下来的每一条</div>
          <div class="micro muted">
            原话只存在会话里，不复制进审计（审计会长期留存，复制一份就删不掉了）。
            点「看会话」能回到那次提问。
          </div>
        </div>
        <div class="panel-tools">
          <span v-if="page.byCategory.length" class="hit-brief micro">
            <span v-for="h in page.byCategory" :key="h.categoryCode" class="hit-brief-item">
              {{ categoryLabel(h.categoryCode, h.categoryName) }} {{ h.hits }}
            </span>
          </span>
          <span class="micro muted">共 {{ page.total }} 次</span>
        </div>
      </div>
      <div class="panel-body">
        <div v-if="!page.recent.length" class="empty micro muted">
          {{ days === 0 ? '还没有任何拦截记录。' : '这段时间里没有拦截记录。' }}点击「刷新」重新拉一次。
        </div>
        <template v-else>
          <el-table v-loading="loading" :data="page.recent" size="small" row-key="at">
            <el-table-column label="时间" width="170">
              <template #default="{ row }">{{ formatAt(row.at) }}</template>
            </el-table-column>
            <el-table-column label="分类" width="100">
              <template #default="{ row }">
                {{ categoryLabel(row.categoryCode, row.categoryName) }}
              </template>
            </el-table-column>
            <el-table-column label="类型" width="90">
              <template #default="{ row }">
                {{ row.kind === 'QUESTION' ? '敏感问题' : '关键词' }}
              </template>
            </el-table-column>
            <el-table-column label="命中的规则" min-width="240">
              <template #default="{ row }">
                <span class="hit-rule">{{ ruleContentOf(row.ruleId) }}</span>
                <span class="micro muted"> · {{ row.contentHashPrefix }}</span>
              </template>
            </el-table-column>
            <el-table-column label="谁问的" width="150">
              <template #default="{ row }">{{ row.actorName || '（匿名/未知）' }}</template>
            </el-table-column>
            <el-table-column label="会话" width="120">
              <template #default="{ row }">
                <router-link
                  v-if="row.conversationId"
                  :to="{ path: '/conversation', query: { conversationId: row.conversationId } }"
                  class="hit-link"
                >
                  看会话
                </router-link>
                <span v-else class="micro muted">—</span>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-if="page.total > page.size"
            class="hit-pager"
            layout="prev, pager, next, total"
            :current-page="page.page"
            :page-size="page.size"
            :total="page.total"
            background
            @current-change="changePage"
          />
        </template>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import { guardApi } from '@/api'
import type { GuardHitPage, GuardRule } from '@/api/guard'
import PageHeader from '@/components/PageHeader.vue'
import { ApiError } from '@/api/types'

const EMPTY_PAGE: GuardHitPage = {
  days: 7,
  total: 0,
  page: 1,
  size: 20,
  byCategory: [],
  recent: []
}

const loading = ref(false)
const loadError = ref('')
const days = ref(7)
const page = ref<GuardHitPage>({ ...EMPTY_PAGE })
const rules = ref<GuardRule[]>([])

/** 错误文案：契约错误优先取后端给的原因，其次才是运行时的 message */
const reasonOf = (e: unknown): string => {
  if (e instanceof ApiError) return e.message
  if (e instanceof Error) return e.message
  return String(e)
}

/**
 * ★ 分类名由后端随每条记录给出（它现场 join 分类表），这一页**不再拉分类列表**：
 * 分类是运营配置项，可改名、可自建 —— 前端另存一份码→名的映射，改名后这里就是旧名。
 * 分类被删掉时 `categoryName` 为 null，回落成码。
 *
 * ★ 规则原文必须从规则表拿：审计里只存了内容哈希，因为把词面复制进长期留存的表
 * 等于多一份删不掉的副本。规则被删掉之后哈希还在，那时显示「这条规则已被删除」比编一个名字诚实。
 */
const categoryLabel = (code: string, name: string | null): string => name ?? code

const ruleContentOf = (ruleId: string): string =>
  rules.value.find((r) => r.id === ruleId)?.content ?? '（这条规则已被删除）'

const formatAt = (iso: string): string => dayjs(iso).format('YYYY-MM-DD HH:mm')

const load = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const [rl, hits] = await Promise.all([
      guardApi.listGuardRules(),
      guardApi.listGuardHits(days.value, page.value.page)
    ])
    rules.value = rl
    page.value = hits
  } catch (e) {
    loadError.value = reasonOf(e)
  } finally {
    loading.value = false
  }
}

const reload = () => {
  page.value = { ...EMPTY_PAGE, days: days.value }
  return load()
}

const changePage = (next: number) => {
  page.value = { ...page.value, page: next }
  return load()
}

onMounted(load)
</script>

<style scoped>
.hit-brief {
  display: inline-flex;
  gap: var(--space-sm);
  align-items: center;
}

.hit-brief-item {
  padding: 1px var(--space-xs);
  border: 1px solid var(--color-rule);
  border-radius: var(--radius-sm);
}

.hit-rule {
  font-family: var(--font-mono);
}

.hit-link {
  color: var(--color-accent);
  text-decoration: none;
}

.hit-link:hover {
  text-decoration: underline;
}

.hit-pager {
  margin-top: var(--space-md);
  justify-content: flex-end;
}
</style>
