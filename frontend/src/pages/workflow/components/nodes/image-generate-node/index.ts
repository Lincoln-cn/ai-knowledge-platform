import ImageGenerateNodeVue from './index.vue'
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node'

class ImageGenerateNode extends AppNode {
  constructor(props: any) {
    super(props, ImageGenerateNodeVue)
  }
}

export default {
  type: 'IMAGE_GENERATE_NODE',
  model: AppNodeModel,
  view: ImageGenerateNode,
}
