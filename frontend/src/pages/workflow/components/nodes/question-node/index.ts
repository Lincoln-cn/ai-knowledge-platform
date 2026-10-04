import QuestionNodeVue from './index.vue';
import { AppNode, AppNodeModel } from '@/pages/workflow/components/common/app-node';
class QuestionNode extends AppNode {
  constructor(props: any) {
    super(props, QuestionNodeVue);
  }
}
export default {
  type: 'QUESTION_NODE',
  model: AppNodeModel,
  view: QuestionNode,
};
