<template>
    <el-dialog title="添加关联知识库" v-model="dialogVisible" width="560px" append-to-body class="addDataset-dialog" align-center
        :close-on-click-modal="false" :close-on-press-escape="false">
        <template #header="{ titleId, titleClass }">
            <!--
              ★ 说明与搜索框原来挤在同一行：560px 宽的弹窗里搜索框固定 240px，
              说明文字只剩 260px ⇒ 折成两行、还顶着搜索框。现在拆成两行：
              说明一行、搜索一行通栏。
            -->
            <h4 :id="titleId" :class="titleClass" class="mb-8"> 添加关联知识库 </h4>
            <p class="dlg-hint">所选知识库必须使用相同的 Embedding 模型</p>
            <el-input v-model="searchValue" placeholder="按名称搜索" prefix-icon="Search" class="dlg-search" clearable />
        </template>
        <el-scrollbar>
            <div class="max-height">
                <el-row :gutter="12" v-loading="loading">
                    <!--
                      `:key` 必须是 `item.id`。用 `index` 时，勾选第一个知识库会触发
                      `changeHandle` ⇒ `currentEmbedding` 变化 ⇒ `filterData` 整个换一批，
                      Vue 按 `index` 复用 DOM ⇒ 用户点的那张卡与真正被勾的知识库错位
                      （「勾了看着没勾」「勾三个只回填一个」都是这个形状）。
                    -->
                    <el-col :span="12" v-for="item in filterData" :key="item.id" class="mb-16">
                        <CardCheckbox value-field="id" :data="item" v-model="checkList" @change="changeHandle">
                            <span class="ellipsis cursor" :title="item.baseName"> {{ item.baseName }}</span>
                        </CardCheckbox>
                    </el-col>
                </el-row>
            </div>
        </el-scrollbar>
        <template #footer>
            <div class="flex-between">
                <div class="flex">
                    <el-text type="info" class="color-secondary mr-8" v-if="checkList.length > 0">
                        已选 {{ checkList.length }} 个知识库
                    </el-text>
                    <el-button link type="primary" v-if="checkList.length > 0" @click="clearCheck">
                        清空
                    </el-button>
                </div>
                <span>
                    <el-button @click.prevent="dialogVisible = false">
                        取消
                    </el-button>
                    <el-button type="primary" @click="submitHandle">
                        确认
                    </el-button>
                </span>
            </div>
        </template>
    </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
const props = defineProps({
    data: {
        type: Array<any>,
        default: () => []
    },
    loading: Boolean
})

const emit = defineEmits(['addData', 'refresh'])

const dialogVisible = ref<boolean>(false)
// ★ 必须显式声明元素类型（`#318`）：`ref([])` 会被推断成 `never[]`，
//   于是 `checkList.value.includes(v.id)` 里的 `any` 参数直接编译不过（TS2345）。
const checkList = ref<any[]>([])
const currentEmbedding = ref('')
const searchValue = ref('')
const searchDate = ref<any[]>([])

/**
 * 列表**只受搜索框影响**。
 * `currentEmbedding` 仍然算着（提交时用来判「混选了不同 embedding」），但**不再裁剪列表** ——
 * 让列表在勾选过程中保持稳定，否则用户点的和勾的会错位。
 */
const filterData = computed(() => searchDate.value)

watch(dialogVisible, (bool) => {
    if (!bool) {
        checkList.value = []
        currentEmbedding.value = ''
        searchValue.value = ''
    }
})

watch(searchValue, (val) => {
    if (val) {
        searchDate.value = props.data.filter((v) => v.baseName.includes(val))
    } else {
        searchDate.value = props.data
    }
})
onMounted(() => {
})
/**
 * 勾选**不再改动列表**。
 *
 * 旧行为：勾第一个 ⇒ `changeHandle` 把 `currentEmbedding` 设成它的 embedding ⇒
 * `filterData` 立刻把不同 embedding 的知识库全部滤掉 ⇒ 用户视线里的卡片整批换人。
 * 而 `:key` 又是 index，DOM 复用后「点的那张卡」和「被勾的知识库」根本不是同一个
 * ⇒ 表现为「勾了没回填」「勾三个只回填一个」。
 *
 * 现在：列表稳定（只受搜索框影响），同一个 embedding 的约束放到**提交时**校验，
 * 不同 embedding 混选会明确报出来，而不是让用户在一张会变形的列表上猜。
 */
function changeHandle() {
    // 当前选中的第一个知识库决定「还可以再选哪些」的提示，但不再裁剪列表
    const first = checkList.value.length > 0 ? props.data.filter((v) => v.id === checkList.value[0])[0] : null
    currentEmbedding.value = first ? first.embedding_mode_id : ''
}
function clearCheck() {
    checkList.value = []
    currentEmbedding.value = ''
}

const open = (checked: any) => {
    searchDate.value = props.data
    checkList.value = checked
    if (checkList.value.length > 0) {
        currentEmbedding.value = props.data.filter(
            (v) => v.id === checkList.value[0]
        )[0].embedding_mode_id
    }

    dialogVisible.value = true
}
/**
 * 提交时校验「所选知识库必须使用相同的 Embedding 模型」。
 * 这条约束以前靠「勾第一个就把其它 embedding 的卡片从列表里删掉」来满足 ——
 * 代价是列表会变形；现在改成提交时说清楚是哪几个不一致。
 */
const submitHandle = () => {
    const picked = props.data.filter((v) => checkList.value.includes(v.id))
    const modes = new Set(picked.map((v) => v.embedding_mode_id).filter(Boolean))
    if (modes.size > 1) {
        const names = picked.map((v) => v.baseName).join('、')
        ElMessage.error(`所选知识库必须使用相同的 Embedding 模型：${names}`)
        return
    }
    emit('addData', checkList.value)
    dialogVisible.value = false
}

defineExpose({ open })
</script>
<style lang="scss" scoped>
/* 弹窗的头/正文/底三段留白与分割线走全局口径（src/styles/element-plus.css），
   这里只保留本弹窗自己的两件事：搜索区排版、列表区滚动高度。 */
.addDataset-dialog {
  .dlg-hint {
    margin: 0 0 var(--space-sm);
    font-size: var(--text-sm);
    color: var(--color-ink-muted);
  }

  .dlg-search {
    width: 100%;
    margin-bottom: var(--space-md);
  }

  /* ★ 原来写的是 `height: calc(100vh - 560px)`：窗口矮时算出来的值可能比可用空间大，
     弹窗把内容裁掉（卡片第三行被切一半、还不出滚动条）；窗口高时又留一大片空白。
     改成 `max-height` —— 内容少就矮、内容多才滚。 */
  .max-height {
    max-height: min(52vh, 480px);
    padding: 0 4px;
  }
}
</style>
