import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIcons from '@element-plus/icons-vue'

/* 样式顺序不能乱：
 *   1. Element Plus 自带样式
 *   2. 本工程的令牌 + 字体 + 基线（tokens.css / fonts.css / 原子类）
 *   3. 令牌桥 —— 把 --el-* 映射到 design.md 的那套令牌。
 *      它必须最后进来：同优先级下后注入者胜，否则 EP 的默认蓝会盖回去。 */
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import '@/styles/element-plus.css'

import App from './App.vue'
import { installStores } from '@/bootstrap/stores'
import { installRouter } from '@/bootstrap/router'
import { permissionGuard } from '@/bootstrap/permission'

const app = createApp(App)

for (const [name, component] of Object.entries(ElementPlusIcons)) {
  app.component(name, component)
}

app.use(ElementPlus, { locale: zhCn })
installStores(app)
installRouter(app, [permissionGuard])

app.mount('#app')
