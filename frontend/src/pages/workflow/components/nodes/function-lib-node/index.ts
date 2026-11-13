// 与函数节点共用同一个面板：两者执行同一段核心、配置三格完全一样，差别只在类型名。
// 各写一份的话，将来加一个参数就得记着改两处 —— 而两处不同步的症状是
// 「从函数库拖进来的能配、从函数拖进来的配了不生效」。
import FunctionNodeVue from '../function-node/index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'
class FunctionLibNode extends AppNode {
  constructor(props: any) {
    super(props, FunctionNodeVue)
  }
}
export default {
  type: 'FUNCTION_LIB_NODE',
  model: AppNodeModel,
  view: FunctionLibNode,
}
