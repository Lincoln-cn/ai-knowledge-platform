import ApplicationNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'
class ApplicationNode extends AppNode {
  constructor(props: any) {
    super(props, ApplicationNodeVue)
  }
}
export default {
  type: 'APPLICATION_NODE',
  model: AppNodeModel,
  view: ApplicationNode,
}
