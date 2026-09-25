<template>
  <div class="page">
    <PageHeader
      title="命中测试"
      desc="用真实业务问题验证召回效果。三种检索模式可分别测试，也可并列对比"
    />

    <div v-if="kbOptions.length" class="panel kb-pick">
      <span class="micro muted">限定知识库</span>
      <el-select
        v-model="kbId"
        size="small"
        placeholder="全部已启用知识库"
        clearable
        style="width: 220px"
      >
        <el-option v-for="k in kbOptions" :key="k.id" :label="k.name" :value="k.id" />
      </el-select>
      <span class="micro muted">
        不选则检索全部已启用知识库。参数（阈值 / TopN）可直接在下方调整。
      </span>
    </div>

    <HitTestPanel :kb-id="kbId || undefined" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { knowledgeApi } from '@/api'
import type { KnowledgeBase } from '@/api/knowledge'
import PageHeader from '@/components/PageHeader.vue'
import HitTestPanel from '../components/HitTestPanel.vue'

const route = useRoute()
const router = useRouter()

const kbId = ref(String(route.query.kbId ?? ''))
const kbOptions = ref<KnowledgeBase[]>([])

const loadOptions = async () => {
  const result = await knowledgeApi.listKnowledgeBases({ enabled: true, size: 100 })
  kbOptions.value = result.list
}

watch(kbId, (id) => {
  router.replace({ query: { ...route.query, kbId: id || undefined } })
})

onMounted(loadOptions)
</script>

<style scoped>
.kb-pick {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  padding: var(--space-sm) var(--space-md);
  flex-wrap: wrap;
}
</style>
