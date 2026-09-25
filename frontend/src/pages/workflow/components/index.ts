import { type App } from 'vue'
import WorkFlowIcon from './icons/WorkFlowIcon.vue'
import AppAvatar from './app-avatar/index.vue'
import BackButton from './back-button/index.vue'
import WorkFlowReadWrite from './read-write/index.vue'
import CommonList from './common-list/index.vue'
import CardCheckbox from './card-checkbox/index.vue'
import WorkFlowAutoTooltip from './auto-tooltip/index.vue'
import LogoIcon from './logo/LogoIcon.vue'
import SendIcon from './logo/SendIcon.vue'
import MdEditorMagnify from './common/MdEditorMagnify.vue'

export default {
  install(app: App) {
    const reg = (name: string | undefined, c: any) => {
      if (name) app.component(name, c)
    }
    reg(WorkFlowIcon.name, WorkFlowIcon)
    reg(AppAvatar.name, AppAvatar)
    reg(BackButton.name, BackButton)
    reg(WorkFlowReadWrite.name, WorkFlowReadWrite)
    reg(CommonList.name, CommonList)
    reg(CardCheckbox.name, CardCheckbox)
    reg(WorkFlowAutoTooltip.name, WorkFlowAutoTooltip)
    reg(LogoIcon.name, LogoIcon)
    reg(SendIcon.name, SendIcon)
    // 节点表单里的放大编辑器（旧 md-editor-v3 的替代）
    reg(MdEditorMagnify.name, MdEditorMagnify)
  }
}
