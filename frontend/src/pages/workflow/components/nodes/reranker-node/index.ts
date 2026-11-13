import RerankerNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'
class RerankerNode extends AppNode {
  constructor(props: any) {
    super(props, RerankerNodeVue)
  }
}
export default {
  type: 'RERANKER_NODE',
  model: AppNodeModel,
  view: RerankerNode,
}
