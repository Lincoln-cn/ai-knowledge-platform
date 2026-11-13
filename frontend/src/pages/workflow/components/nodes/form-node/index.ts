import FormNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'
class FormNode extends AppNode {
  constructor(props: any) {
    super(props, FormNodeVue)
  }
}
export default {
  type: 'FORM_NODE',
  model: AppNodeModel,
  view: FormNode,
}
