<template>
  <div class="page">
    <PageHeader
      title="服务商配置"
      desc="模型从哪来在这里登记：默认上游、网关、或某个厂家的直连。密钥只写不回显"
    >
      <template #actions>
        <el-button size="small" @click="goModels">去模型管理绑定模型</el-button>
      </template>
    </PageHeader>

    <!--
      ★ 这里**不再套抽屉**（`#289` 的前身是「一个配置页只有说明书」）。

      之前的结构是：页面正文 = 一段「这个页面做什么」的说明 + 两个按钮（都只是把抽屉打开），
      而真正的列表在 `ProviderDrawer` 里 —— 于是进页面会**自动弹出一个 760px 的抽屉**盖住半屏，
      再点「添加服务商」又弹出第二层对话框（抽屉只占右半边，对话框按全屏居中 ⇒ 一半悬在抽屉外）。

      ⇒ 现在：页面正文就是列表，编辑只留**一层**对话框。抽屉这个东西在这条路上不存在了。
    -->
    <ProviderPanel />
  </div>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'
import PageHeader from '@/components/PageHeader.vue'
import ProviderPanel from './ProviderPanel.vue'

const router = useRouter()
const goModels = () => router.push('/models')
</script>
