<template>
  <NodeContainer :nodeModel="nodeModel">
    <!--
      卡片摘要写「哪个函数」而不是「函数库 / 函数」：图上有五个函数节点时，
      只写类型名等于五张卡片长得一样，排查时得逐个点开。
    -->
    <template #essential>
      {{ typeLabel }} · {{ form_data.functionName || '未选函数' }}
    </template>

    <h5 class="title-decoration-1 mb-8">节点设置</h5>
    <el-card shadow="never" class="card-never">
      <el-form label-position="top">
        <el-form-item label="函数" required>
          <el-select
            :model-value="form_data.functionName"
            filterable
            allow-create
            default-first-option
            placeholder="选一个内置函数（也可以直接填名字）"
            @change="pickFunction"
          >
            <el-option v-for="name in names" :key="name" :value="name" :label="name" />
          </el-select>
        </el-form-item>

        <el-form-item label="参数">
          <div class="arg-row arg-head">
            <span>来源</span>
            <span>值</span>
            <span class="arg-ops" />
          </div>
          <div v-for="(arg, index) in form_data.args" :key="index" class="arg-row">
            <el-select
              :model-value="arg.from ?? 'literal'"
              @change="(v: string) => writeArg(index, 'from', v)"
            >
              <el-option value="literal" label="字面量" />
              <el-option value="variable" label="流程变量" />
            </el-select>
            <el-input
              :model-value="arg.value"
              :placeholder="arg.from === 'variable' ? '变量名，如 query' : '原样传进去的文本'"
              @update:model-value="(v: string) => writeArg(index, 'value', v)"
            />
            <span class="arg-ops">
              <el-button link type="danger" @click="removeArg(index)">删除</el-button>
            </span>
          </div>
          <el-button link type="primary" @click="addArg">+ 添加参数</el-button>
        </el-form-item>

        <el-form-item label="输出到变量">
          <el-input
            :model-value="form_data.outputKey"
            placeholder="留空写 function_result"
            @update:model-value="(v: string) => writeField('outputKey', v)"
          />
        </el-form-item>
      </el-form>

      <!--
        ★ 这句必须写在面板上：「函数库」在设计册里指的是执行用户脚本的沙箱，而那个东西排在 0.9.0。
          一个"看起来能跑代码、实际跑不了"的节点比没有更糟 —— 用户会以为自己的脚本跑过了。
      -->
      <p class="function-hint">
        这里调的是<strong>平台内置函数</strong>（如 <code>upper</code> / <code>concat</code> /
        <code>jsonpath</code>），<strong>不执行自定义脚本</strong> —— 沙箱在 0.9.0。
        参数选「流程变量」时读当前流程里的变量；变量不存在按空串传（这样 <code>default(...)</code> 才兜得住
        「上一步没产出」）。
      </p>
    </el-card>
  </NodeContainer>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { set } from 'lodash-es'
import NodeContainer from '@/pages/workflow/components/common/NodeContainer.vue'
import { listWorkflowFunctions } from '@/api/workflow'

/**
 * 函数节点 / 函数库节点共用的面板。
 *
 * <p>两个类型执行同一段核心（`FunctionInvocation`），配置三格也完全一样，差别只在"这个组件叫什么"。
 * 所以面板只写一份：写两份的话，将来加一个参数就得记着改两处，而两处不同步的症状是
 * "从函数库拖进来的能配、从函数拖进来的配了不生效"。
 *
 * <p>★ 不用 `SchemaForm`：`args` 是**可增删的数组**，而那个表单只渲染平铺字段（数组项的表单还没支持）。
 * 硬塞进去会变成"能显示、不能加行"。
 *
 * <p>★ `nodeModel.properties` 不是响应式对象，所以沿用 `search-dataset-node` 的版本号模式：
 * 写 `node_data` 之后 `tick++`，让 `form_data` 这个 computed 重新求值。直接 `v-model` 改的是快照。
 */
const props = defineProps<{ nodeModel: any }>()
const tick = ref(0)
const names = ref<string[]>([])

const typeLabel = computed(() => (props.nodeModel?.type === 'FUNCTION_LIB_NODE' ? '函数库' : '函数'))

interface ArgLike {
  from?: 'literal' | 'variable'
  value: string
}

interface FunctionData {
  functionName: string | null
  args: ArgLike[]
  outputKey: string
}

const form_data = computed<FunctionData>({
  get() {
    void tick.value
    const data = props.nodeModel.properties?.node_data ?? {}
    return {
      functionName: typeof data.functionName === 'string' && data.functionName ? data.functionName : null,
      args: Array.isArray(data.args)
        ? data.args.map((arg: any) => ({
            from: arg?.from === 'variable' ? 'variable' : 'literal',
            value: typeof arg?.value === 'string' ? arg.value : ''
          }))
        : [],
      outputKey: typeof data.outputKey === 'string' ? data.outputKey : ''
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

/**
 * 写数组里的一项。
 *
 * ★ 必须整体换一个新数组再写回：直接改 `data.args[index].value` 改的是原对象，
 * `tick++` 之后 computed 拿到的还是同一个引用，界面不会重绘（看起来像"输入框吃字"）。
 */
function writeArg(index: number, key: 'from' | 'value', value: string) {
  const next = form_data.value.args.map((arg, i) => (i === index ? { ...arg, [key]: value } : arg))
  writeField('args', next)
}

function addArg() {
  writeField('args', [...form_data.value.args, { from: 'literal', value: '' }])
}

function removeArg(index: number) {
  writeField(
    'args',
    form_data.value.args.filter((_, i) => i !== index)
  )
}

function pickFunction(name: string) {
  writeField('functionName', name)
}

onMounted(async () => {
  try {
    names.value = await listWorkflowFunctions()
  } catch {
    // ★ 清单拉不到不能让面板打不开：下拉为空，但 allow-create 仍能手填函数名。
    names.value = []
  }
})

defineExpose({
  validate: () => {
    if (!form_data.value.functionName) {
      return `${typeLabel.value}节点要先选一个函数名`
    }
    return true
  }
})
</script>

<style scoped>
.arg-row {
  display: grid;
  grid-template-columns: 108px 1fr 52px;
  gap: 8px;
  width: 100%;
  margin-bottom: 6px;
  align-items: center;
}

.arg-head {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.arg-ops {
  text-align: right;
  white-space: nowrap;
}

.function-hint {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
