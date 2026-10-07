<template>
  <div class="page">
    <PageHeader
      title="基础数据"
      desc="密级 / 文种 / 提示词类型 / 库分类的唯一真源 —— 这里改一次，全平台的下拉与既有数据的展示都跟着变"
    />

    <div class="notice info">
      字典项<b>只能停用、不能删除</b>：<code>code</code> 已经写进历史数据与召回前缀，
      删掉会让那些数据的展示回落成机器码，而<b>没有任何地方报错</b>。
      停用表达的是"新数据不要再选它"，既有数据照旧可读 —— 所以界面里只有「停用 / 启用」。
    </div>

    <el-tabs v-model="active" class="dict-tabs">
      <el-tab-pane label="密级" name="CLASSIFICATION" lazy>
        <div class="notice warn">
          密级<b>没有「新增」</b>，只能改展示名与启停 —— 它不是一张清单，而是一个<b>排序比较轴</b>：
          <code>classification_rank</code> 是数据库里的生成列，四档写死在迁移里（V21/V22）。
          加一档要连生成列一起改并再跑一次迁移，否则新档位的内容对<b>所有人</b>都不可见，而没有任何地方报错。
        </div>
        <DictionaryPanel kind="CLASSIFICATION" title="密级" :allow-create="false" />
      </el-tab-pane>

      <el-tab-pane label="文种" name="DOC_TYPE" lazy>
        <DictionaryPanel kind="DOC_TYPE" title="文种" />
      </el-tab-pane>

      <el-tab-pane label="提示词类型" name="PROMPT_TYPE" lazy>
        <DictionaryPanel kind="PROMPT_TYPE" title="提示词类型" />
      </el-tab-pane>

      <el-tab-pane label="库分类" name="KB_CATEGORY" lazy>
        <DictionaryPanel kind="KB_CATEGORY" title="库分类" />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { DictionaryKind } from '@/api/dictionaries'
import PageHeader from '@/components/PageHeader.vue'
import DictionaryPanel from './components/DictionaryPanel.vue'

/**
 * 基础数据维护页（08 §十）。
 *
 * 四类共用同一个面板：它们的形状完全一样（列表 + 编辑弹窗 + 启停），差别只有"能不能新增"
 * —— 所以面板按 kind + allowCreate 参数化，而不是抄四份。密级那一份的差别见上面那段说明。
 *
 * 入口权限是 `platform:dict:manage`（写在 route.ts 的 meta.permission 里，那是唯一的声明点）：
 * 读接口本身登录即可（上传/建档的下拉要读它），但改一次影响全平台，维护页就必须卡住写权限。
 */
const active = ref<DictionaryKind>('CLASSIFICATION')
</script>

<style scoped>
.dict-tabs :deep(.el-tabs__header) {
  margin-bottom: var(--space-sm);
}
</style>
