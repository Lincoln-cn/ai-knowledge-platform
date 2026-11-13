import ImageUnderstandNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'

class ImageUnderstandNode extends AppNode {
  constructor(props: any) {
    super(props, ImageUnderstandNodeVue)
  }
}

export default {
  type: 'IMAGE_UNDERSTAND_NODE',
  model: AppNodeModel,
  view: ImageUnderstandNode,
}
