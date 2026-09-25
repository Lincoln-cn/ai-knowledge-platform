import type { App } from 'vue'
import { createPinia } from 'pinia'

export function installStores(app: App) {
  app.use(createPinia())
}
