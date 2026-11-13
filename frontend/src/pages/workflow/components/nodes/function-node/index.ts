import FunctionNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'
class FunctionNode extends AppNode {
  constructor(props: any) {
    super(props, FunctionNodeVue)
  }
}
export default {
  type: 'FUNCTION_NODE',
  model: AppNodeModel,
  view: FunctionNode,
}
