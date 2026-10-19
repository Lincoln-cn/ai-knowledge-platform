<template>
  <div class="page">
    <PageHeader
      title="服务商配置"
      desc="模型从哪来在这里登记：默认上游、网关、或某个厂家的直连。密钥只写不回显"
    >
      <template #actions>
        <el-button size="small" type="primary" @click="openPanel">打开服务商面板</el-button>
      </template>
    </PageHeader>

    <div class="notice info">
      服务商是<b>模型来源</b>的唯一登记处。绑定后，模型管理里的地址与密钥不再逐个重复填写；
      停用一个服务商时，引用它的模型<b>加载会失败并给出原因</b>，不会静默回退到默认上游。
    </div>

    <section class="panel entry">
      <h3 class="entry-title">这个页面做什么</h3>
      <ul class="entry-list">
        <li><b>一键绑定 JAiRouter</b>：本机网关默认 <code>http://localhost:8080/v1</code>，探活通过才落库；重复绑定幂等。</li>
        <li><b>登记直连服务商</b>：OpenAI / DeepSeek / Ollama / OpenAI 兼容等，地址、认证方式、是否出网分开填。</li>
        <li><b>密钥只写不回显</b>：保存后前端不再拿到明文；旋转密钥走「重新设置密钥」。</li>
      </ul>
      <div class="entry-actions">
        <el-button size="small" @click="openPanel">管理服务商</el-button>
        <el-button size="small" link type="primary" @click="goModels">去模型管理绑定模型</el-button>
      </div>
    </section>

    <!-- 复用模型管理里的同一份面板；这里作为独立菜单入口，面板关闭后仍可重新打开 -->
    <ProviderDrawer ref="drawerRef" @changed="onChanged" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import ProviderDrawer from '@/pages/models/components/ProviderDrawer.vue'

const router = useRouter()
const drawerRef = ref<InstanceType<typeof ProviderDrawer>>()

const openPanel = () => drawerRef.value?.open()
const goModels = () => router.push('/models')
const onChanged = () => {
  // 服务商增删改后，模型页下次进入会重新拉列表；这里不持有模型状态
}

onMounted(() => openPanel())
</script>

<style scoped>
.entry {
  max-width: 760px;
}

.entry-title {
  margin: 0 0 var(--space-sm);
  font-size: var(--text-md);
}

.entry-list {
  margin: 0 0 var(--space-md);
  padding-left: 1.2em;
  color: var(--color-text-secondary);
  line-height: var(--leading-body);
}

.entry-list li + li {
  margin-top: var(--space-2xs);
}

.entry-actions {
  display: flex;
  gap: var(--space-sm);
  align-items: center;
}
</style>
